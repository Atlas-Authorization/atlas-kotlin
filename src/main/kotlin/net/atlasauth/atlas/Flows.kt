package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * §5: "the client reads `status` and renders whatever the server demands next;
 * it never picks the step itself."
 *
 * This is the Kotlin peer of `@atlas/js`'s `attempt.ts` — the client half of
 * that contract, written as an exhaustive mapping rather than an `if` ladder on
 * purpose. An `if (status == …)` chain silently falls through when the server
 * introduces a status the client has not seen, and "falls through" in a sign-in
 * flow is a blank screen with no error — the single worst failure a login box
 * has. Here an unknown status maps to an explicit [FlowStep.Unknown] a UI can
 * render as "this needs an update", which is honest and actionable.
 */

/**
 * A sign-in / sign-up / password-reset attempt view (§5). The driver never
 * advances the flow itself — it reads [status] and lets the server say what the
 * next step is. [id] is a string on every real attempt; it is null only on the
 * pre-attempt captcha challenge marker (§15.4), where no attempt row exists yet,
 * so it is typed nullable for that one case rather than pretending it is always
 * present.
 */
@Serializable
data class FlowAttempt(
    val id: String? = null,
    val status: String,
    /**
     * The server's list of first-factor strategies. §13.2 makes this identical
     * for unknown identifiers, so it must never be filtered client-side.
     */
    @SerialName("supported_first_factors") val supportedFirstFactors: List<String>? = null,
    @SerialName("created_session_id") val createdSessionId: String? = null,
    /** Present only when a redirect flow returns an authorize URL. */
    @SerialName("authorization_url") val authorizationUrl: String? = null,
    /** Present for exactly one step — the one that reached `complete`. */
    val ticket: String? = null,
    /** §5.6 the backup codes handed back exactly once, on MFA enrollment completion. */
    @SerialName("backup_codes") val backupCodes: List<String>? = null,
) {
    val isComplete: Boolean get() = status == "complete"
}

/** The next thing a UI must collect, derived from an attempt's status. */
sealed class FlowStep {
    /** Collect an identifier (email / username / phone) to start the attempt. */
    object CollectIdentifier : FlowStep()

    /** Collect a first factor; [strategies] is the server's list, never a client guess. */
    data class CollectFirstFactor(val strategies: List<String>) : FlowStep()

    /** Collect a second factor (TOTP / SMS / backup code / passkey). */
    object CollectSecondFactor : FlowStep()

    /**
     * Enroll a second factor — distinct from [CollectSecondFactor]: this user has
     * NO factor to be asked for yet (§11.1 MFA policy `required`). Rendering an
     * "enter your code" box here would ask for something that does not exist.
     */
    object EnrollSecondFactor : FlowStep()

    /** Collect an emailed / texted one-time code. */
    object CollectEmailCode : FlowStep()

    /** Clear a CAPTCHA challenge before the attempt can be created (§15.4). */
    object CollectCaptcha : FlowStep()

    /** Await the OAuth redirect to come back (§6). */
    object AwaitOauthCallback : FlowStep()

    /** Collect a new password (the final step of a reset). */
    object CollectNewPassword : FlowStep()

    /** The attempt completed; [sessionId] is the created session when the server named one. */
    data class Done(val sessionId: String?) : FlowStep()

    /** The attempt was abandoned and the flow must restart. */
    data class Restart(val reason: String) : FlowStep()

    /** A status this SDK version does not know — render "this needs an update". */
    data class Unknown(val status: String) : FlowStep()
}

/** Map an attempt's status to the next [FlowStep]; the one place the flow is read. */
fun nextStep(attempt: FlowAttempt): FlowStep = when (attempt.status) {
    "needs_identifier" -> FlowStep.CollectIdentifier
    "needs_first_factor" -> FlowStep.CollectFirstFactor(attempt.supportedFirstFactors ?: emptyList())
    "needs_second_factor" -> FlowStep.CollectSecondFactor
    "needs_mfa_enrollment" -> FlowStep.EnrollSecondFactor
    "needs_email_verification" -> FlowStep.CollectEmailCode
    "needs_captcha" -> FlowStep.CollectCaptcha
    "needs_oauth_callback" -> FlowStep.AwaitOauthCallback
    "needs_new_password" -> FlowStep.CollectNewPassword
    "complete" -> FlowStep.Done(attempt.createdSessionId)
    "abandoned" -> FlowStep.Restart("abandoned")
    else -> FlowStep.Unknown(attempt.status)
}

/** Whether the flow can still progress, for deciding whether to keep polling. */
fun isTerminal(attempt: FlowAttempt): Boolean =
    attempt.status == "complete" || attempt.status == "abandoned"

/**
 * A prepared second-factor challenge (§5.3) — the response of
 * `prepare_second_factor`. The shape varies by strategy: `sms` carries [sentTo],
 * `push` carries [challengeId] + [numberMatch], and a passkey carries the
 * WebAuthn request options ([challenge], [rpId], …). Unknown keys are ignored, so
 * a caller reads whichever fields its chosen strategy populates.
 */
@Serializable
data class SecondFactorChallenge(
    val strategy: String? = null,
    @SerialName("sent_to") val sentTo: String? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("number_match") val numberMatch: Int? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    /** WebAuthn options (passkey as a second factor). */
    val challenge: String? = null,
    val rpId: String? = null,
)

/**
 * A started TOTP enrollment (§5.6) — the response of `prepare_mfa_enrollment`.
 * The [secret] and provisioning [uri] are returned exactly once, here; confirm
 * the enrollment with the authenticator's codes via
 * [SignInFlow.attemptMfaEnrollment].
 */
@Serializable
data class MfaEnrollment(
    @SerialName("factor_id") val factorId: String,
    val secret: String,
    val uri: String,
)
