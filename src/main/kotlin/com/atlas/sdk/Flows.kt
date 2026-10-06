package com.atlas.sdk

/**
 * The multi-step flow driver — the client half of §5's contract: *"the client
 * reads `status` and renders whatever the server demands next; it never picks
 * the step itself."*
 *
 * It mirrors the vanilla JS driver (`@atlas/js`'s `nextStep` / `advance`): a
 * suspend-based state machine over `/v1/client/sign_ins`, `/v1/client/sign_ups`
 * and `/v1/client/password_resets`. Each `advance` method posts one step and
 * updates the held attempt; [SignInFlow.step] then resolves the next action from
 * the new `status`. On `complete`, [SignInFlow.complete] exchanges the one-time
 * ticket through the existing [AtlasClient.exchangeTicket] and persists the
 * session via the [TokenStore].
 *
 * The step mapping is an exhaustive `when`, not an `if` ladder, for the reason
 * the JS driver spells out: an `if (status == …)` chain silently falls through on
 * a status this SDK version has not seen, and "falls through" in a login flow is
 * a blank screen. An unknown status maps to [SignInStep.Unknown] so a UI can say
 * "this needs an update" instead of rendering nothing.
 */
sealed interface SignInStep {
    /** The server still needs an identifier (a fresh/abandoned attempt). */
    data object CollectIdentifier : SignInStep

    /** A first factor is owed; [strategies] is the server's list, never filtered client-side (§13.2). */
    data class CollectFirstFactor(val strategies: List<String>) : SignInStep

    /** A second factor (TOTP / SMS / push / passkey / backup code) is owed. */
    data object CollectSecondFactor : SignInStep

    /** MFA policy is `required` but this account has no second factor yet (§11.1). */
    data object EnrollSecondFactor : SignInStep

    /** An emailed verification code is owed. */
    data object CollectEmailCode : SignInStep

    /** A CAPTCHA challenge must be solved before the attempt can proceed (§15.4). */
    data object CollectCaptcha : SignInStep

    /** An OAuth redirect is in flight; resume via the ticket on the callback. */
    data object AwaitOAuth : SignInStep

    /** A new password is owed (reset / forced rotation). */
    data object CollectNewPassword : SignInStep

    /** The attempt completed; call [SignInFlow.complete] to persist the session. */
    data class Done(val sessionId: String?) : SignInStep

    /** The attempt was abandoned; restart from a fresh sign-in. */
    data class Restart(val reason: String) : SignInStep

    /** A status this SDK version does not know — surface it rather than render nothing. */
    data class Unknown(val status: String) : SignInStep
}

/** Resolve the next sign-in step from an attempt view — mirrors `@atlas/js` `nextStep`. */
fun nextStep(attempt: SignInAttempt): SignInStep = when (attempt.status) {
    "needs_identifier" -> SignInStep.CollectIdentifier
    "needs_first_factor" -> SignInStep.CollectFirstFactor(attempt.supportedFirstFactors ?: emptyList())
    "needs_second_factor" -> SignInStep.CollectSecondFactor
    "needs_mfa_enrollment" -> SignInStep.EnrollSecondFactor
    "needs_email_verification" -> SignInStep.CollectEmailCode
    "needs_captcha" -> SignInStep.CollectCaptcha
    "needs_oauth_callback" -> SignInStep.AwaitOAuth
    "needs_new_password" -> SignInStep.CollectNewPassword
    "complete" -> SignInStep.Done(attempt.createdSessionId)
    "abandoned" -> SignInStep.Restart("abandoned")
    else -> SignInStep.Unknown(attempt.status)
}

/** Whether the flow can still progress — for deciding whether to keep polling. */
fun isTerminal(attempt: SignInAttempt): Boolean =
    attempt.status == "complete" || attempt.status == "abandoned"

/**
 * A suspend-driven sign-in state machine (§5). Create it with
 * [AtlasClient.beginSignIn] (or [AtlasClient.signInWithIdToken] for a native
 * token), inspect [step], call the matching `advance` method, and finish with
 * [complete]. All methods are `suspend` — call them from a coroutine.
 */
class SignInFlow internal constructor(
    private val client: AtlasClient,
    attempt: SignInAttempt,
) {
    /** The current attempt view. Updated by each `advance` method. */
    var attempt: SignInAttempt = attempt
        private set

    /** The attempt's current server status. */
    val status: String get() = attempt.status

    /** What the server demands next — branch on this to render the right step. */
    val step: SignInStep get() = nextStep(attempt)

    /** Whether the attempt has reached `complete`. */
    val isComplete: Boolean get() = attempt.isComplete

    /** Attempt a `password` first factor. */
    suspend fun attemptPassword(password: String): SignInStep = advanceFirstFactor(
        jsonBody("strategy" to jstr("password"), "password" to jstr(password)),
    )

    /**
     * Prepare a code/link first factor — `email_code`, `email_link`, or
     * `phone_code` (with an optional `sms`/`whatsapp`/`voice` [channel]). Returns
     * the prepared factor, including the `poll_secret` held by this tab only.
     */
    suspend fun prepareFirstFactor(strategy: String, channel: String? = null): PreparedFirstFactor =
        PreparedFirstFactor.from(
            client.clientRequest(
                "POST",
                "/v1/client/sign_ins/${attempt.id}/prepare_first_factor",
                jsonBody("strategy" to jstr(strategy), "channel" to jstr(channel)),
            ),
        )

    /** Attempt an emailed-code first factor. */
    suspend fun attemptEmailCode(code: String): SignInStep = advanceFirstFactor(
        jsonBody("strategy" to jstr("email_code"), "code" to jstr(code)),
    )

    /** Attempt a texted-code (SMS/WhatsApp/voice) first factor. */
    suspend fun attemptPhoneCode(code: String): SignInStep = advanceFirstFactor(
        jsonBody("strategy" to jstr("phone_code"), "code" to jstr(code)),
    )

    /**
     * Prepare a second factor: `sms` (texts the OTP), `push` (returns a
     * `number_match` to show), or a passkey/security-key (returns the raw
     * WebAuthn request options). Completing a passkey assertion is a native
     * ceremony and lives in the Android SDK; this returns the challenge so that
     * layer can produce the assertion.
     */
    suspend fun prepareSecondFactor(strategy: String): SecondFactorChallenge =
        SecondFactorChallenge.from(
            client.clientRequest(
                "POST",
                "/v1/client/sign_ins/${attempt.id}/prepare_second_factor",
                jsonBody("strategy" to jstr(strategy)),
            ),
        )

    /**
     * Attempt a second factor with a code — a TOTP code, an SMS OTP, or a backup
     * recovery code (the server decides by what matches, not a declared strategy).
     * Pass [rememberDevice] to mint a "remember this device" trust where enabled.
     */
    suspend fun attemptSecondFactor(code: String, rememberDevice: Boolean = false): SignInStep =
        advanceSecondFactor(
            jsonBody("code" to jstr(code), "remember_device" to jbool(if (rememberDevice) true else null)),
        )

    /** Poll a `push` second factor — completes once the device approves. */
    suspend fun attemptPushSecondFactor(): SignInStep =
        advanceSecondFactor(jsonBody("strategy" to jstr("push")))

    /**
     * Begin mid-sign-in MFA enrollment for an account parked at
     * `needs_mfa_enrollment` (§11.1). Returns the TOTP secret + provisioning URI
     * (shown once); confirm with [attemptMfaEnrollment].
     */
    suspend fun prepareMfaEnrollment(): MfaEnrollment = MfaEnrollment.from(
        client.clientRequest(
            "POST",
            "/v1/client/sign_ins/${attempt.id}/prepare_mfa_enrollment",
            null,
        ),
    )

    /**
     * Confirm MFA enrollment with the authenticator [codes] for [factorId].
     * Returns the one-time backup codes (shown once) and advances the attempt —
     * inspect [step] / [isComplete] afterwards.
     */
    suspend fun attemptMfaEnrollment(factorId: String, codes: List<String>): List<String> {
        val json = client.clientRequest(
            "POST",
            "/v1/client/sign_ins/${attempt.id}/attempt_mfa_enrollment",
            jsonBody("factor_id" to jstr(factorId), "codes" to jarr(codes)),
        )
        attempt = SignInAttempt.from(json)
        return json.arr("backup_codes")?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
    }

    /**
     * Finish a completed sign-in: exchange the one-time ticket for a session and
     * persist it via the [TokenStore], then return the signed-in user. Throws if
     * the attempt is not `complete` (inspect [step] first).
     */
    suspend fun complete(): AtlasUser {
        val ticket = attempt.ticket ?: throw AtlasException(
            AtlasError.Api(
                200,
                listOf(AtlasErrorItem("sign_in_not_complete", "Sign-in is not complete (status: ${attempt.status}).")),
            ),
        )
        client.exchangeTicket(attempt.id, ticket)
        return client.currentUser()
    }

    private suspend fun advanceFirstFactor(body: String): SignInStep {
        attempt = SignInAttempt.from(
            client.clientRequest("POST", "/v1/client/sign_ins/${attempt.id}/attempt_first_factor", body),
        )
        return step
    }

    private suspend fun advanceSecondFactor(body: String): SignInStep {
        attempt = SignInAttempt.from(
            client.clientRequest("POST", "/v1/client/sign_ins/${attempt.id}/attempt_second_factor", body),
        )
        return step
    }
}

/** The next step of a sign-up flow. */
sealed interface SignUpStep {
    /** An emailed verification code is owed. */
    data object CollectEmailCode : SignUpStep

    /** The sign-up completed; call [SignUpFlow.complete] to persist the session. */
    data class Done(val sessionId: String?) : SignUpStep

    /** A status this SDK version does not know. */
    data class Unknown(val status: String) : SignUpStep
}

fun nextStep(attempt: SignUpAttempt): SignUpStep = when (attempt.status) {
    "needs_email_verification" -> SignUpStep.CollectEmailCode
    "complete" -> SignUpStep.Done(attempt.createdSessionId)
    else -> SignUpStep.Unknown(attempt.status)
}

/**
 * A suspend-driven sign-up state machine (§5.1). Create it with
 * [AtlasClient.beginSignUp]; verify the email with a code, then [complete].
 */
class SignUpFlow internal constructor(
    private val client: AtlasClient,
    attempt: SignUpAttempt,
) {
    var attempt: SignUpAttempt = attempt
        private set

    val status: String get() = attempt.status
    val step: SignUpStep get() = nextStep(attempt)
    val isComplete: Boolean get() = attempt.isComplete

    /** (Re)send the verification code for this attempt. */
    suspend fun prepareVerification(): SignUpStep {
        attempt = SignUpAttempt.from(
            client.clientRequest("POST", "/v1/client/sign_ups/${attempt.id}/prepare_verification", null),
        )
        return step
    }

    /** Submit the emailed verification code. */
    suspend fun attemptVerification(code: String): SignUpStep {
        attempt = SignUpAttempt.from(
            client.clientRequest(
                "POST",
                "/v1/client/sign_ups/${attempt.id}/attempt_verification",
                jsonBody("code" to jstr(code)),
            ),
        )
        return step
    }

    /** Exchange the completed sign-up's ticket for a session and persist it. */
    suspend fun complete(): AtlasUser {
        val ticket = attempt.ticket ?: throw AtlasException(
            AtlasError.Api(
                200,
                listOf(AtlasErrorItem("sign_up_not_complete", "Sign-up is not complete (status: ${attempt.status}).")),
            ),
        )
        client.exchangeTicket(attempt.id, ticket)
        return client.currentUser()
    }
}

/** The next step of a password-reset flow. */
sealed interface PasswordResetStep {
    /** The emailed reset code is owed. */
    data object CollectCode : PasswordResetStep

    /** A second factor is owed before the password may be reset (§5.4). */
    data object CollectSecondFactor : PasswordResetStep

    /** The new password is owed. */
    data object CollectNewPassword : PasswordResetStep

    /** The reset completed; [PasswordResetFlow.complete] signs the user in when enabled. */
    data class Done(val sessionId: String?) : PasswordResetStep

    /** A status this SDK version does not know. */
    data class Unknown(val status: String) : PasswordResetStep
}

/**
 * A suspend-driven password-reset state machine (§5.4). Create it with
 * [AtlasClient.beginPasswordReset]. A reset must not bypass MFA, so an account
 * with a second factor is parked at [PasswordResetStep.CollectSecondFactor]
 * before the new password is accepted.
 */
class PasswordResetFlow internal constructor(
    private val client: AtlasClient,
    attempt: SignInAttempt,
) {
    var attempt: SignInAttempt = attempt
        private set

    val status: String get() = attempt.status

    val step: PasswordResetStep get() = when (attempt.status) {
        "needs_email_verification" -> PasswordResetStep.CollectCode
        "needs_second_factor" -> PasswordResetStep.CollectSecondFactor
        "needs_new_password" -> PasswordResetStep.CollectNewPassword
        "complete" -> PasswordResetStep.Done(attempt.createdSessionId)
        else -> PasswordResetStep.Unknown(attempt.status)
    }

    val isComplete: Boolean get() = attempt.isComplete

    /** Submit the emailed reset code. */
    suspend fun attemptVerification(code: String): PasswordResetStep {
        attempt = SignInAttempt.from(
            client.clientRequest(
                "POST",
                "/v1/client/password_resets/${attempt.id}/attempt_verification",
                jsonBody("code" to jstr(code)),
            ),
        )
        return step
    }

    /** Submit the second factor a reset requires when the account has MFA. */
    suspend fun attemptSecondFactor(code: String): PasswordResetStep {
        attempt = SignInAttempt.from(
            client.clientRequest(
                "POST",
                "/v1/client/password_resets/${attempt.id}/attempt_second_factor",
                jsonBody("code" to jstr(code)),
            ),
        )
        return step
    }

    /**
     * Set the new password. The server revokes every other session; when
     * sign-in-after-reset is enabled it returns a ticket, captured here so
     * [complete] can establish the session.
     */
    suspend fun setNewPassword(password: String): PasswordResetStep {
        attempt = SignInAttempt.from(
            client.clientRequest(
                "POST",
                "/v1/client/password_resets/${attempt.id}/set_new_password",
                jsonBody("password" to jstr(password)),
            ),
        )
        return step
    }

    /** Whether the completed reset handed back a ticket to sign the user in. */
    val canSignIn: Boolean get() = attempt.ticket != null

    /**
     * Exchange the reset's sign-in ticket for a session and persist it. Only
     * valid when [canSignIn] is true (sign-in-after-reset enabled); otherwise the
     * user signs in afresh with the new password.
     */
    suspend fun complete(): AtlasUser {
        val ticket = attempt.ticket ?: throw AtlasException(
            AtlasError.Api(
                200,
                listOf(AtlasErrorItem("reset_no_sign_in", "This reset does not sign the user in; sign in with the new password.")),
            ),
        )
        client.exchangeTicket(attempt.id, ticket)
        return client.currentUser()
    }
}
