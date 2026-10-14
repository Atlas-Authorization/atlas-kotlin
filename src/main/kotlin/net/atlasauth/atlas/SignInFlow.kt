package net.atlasauth.atlas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A suspend-driven sign-in state machine over `/v1/client/sign_ins`, the Kotlin
 * peer of `@atlas/js`'s sign-in flow. It holds the current [attempt], exposes the
 * server-dictated [step] (via [nextStep]), and offers one `suspend` method per
 * action — password, email/phone code, second factor (TOTP / SMS / backup code),
 * and mid-sign-in MFA enrollment. [complete] turns a finished attempt into a
 * persisted session.
 *
 * Each action returns the NEXT [FlowStep], so a UI loops: call the method the
 * current step asks for, render the step it returns, repeat until [FlowStep.Done].
 *
 * The driver never picks the next step; it reads the server's `status` (§5). All
 * methods are `suspend`; call them from a coroutine. Not thread-safe — drive one
 * flow from one coroutine at a time.
 */
class SignInFlow internal constructor(private val client: AtlasClient) {

    private var last: AtlasClient.AtlasResponse? = null

    /** The current attempt, or null before [create]. */
    var attempt: FlowAttempt? = null
        private set

    /** The server-dictated next step; [FlowStep.CollectIdentifier] before [create]. */
    val step: FlowStep get() = attempt?.let { nextStep(it) } ?: FlowStep.CollectIdentifier

    /** The current attempt status, or null before [create]. */
    val status: String? get() = attempt?.status

    private fun absorb(response: AtlasClient.AtlasResponse): FlowStep {
        last = response
        val decoded = client.decodeOrThrow(response.body, FlowAttempt.serializer())
        attempt = decoded
        return nextStep(decoded)
    }

    private fun attemptId(): String =
        attempt?.id ?: throw AtlasException.Decoding("No active sign-in attempt — call create() first.")

    private suspend fun post(path: String, body: JsonObject): FlowStep =
        absorb(client.requestRaw("POST", path, body.toString(), authenticated = false))

    /** Start the attempt with an identifier (§5). `POST /v1/client/sign_ins`. */
    suspend fun create(identifier: String): FlowStep =
        post("/v1/client/sign_ins", buildJsonObject { put("identifier", identifier) })

    /** Submit a password as the first factor. */
    suspend fun attemptPassword(password: String): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/attempt_first_factor",
            buildJsonObject {
                put("strategy", "password")
                put("password", password)
            },
        )

    /**
     * Send an emailed one-time code (`prepare_first_factor`, `strategy=email_code`).
     * The attempt stays in `needs_first_factor`; collect the code, then call
     * [attemptEmailCode].
     */
    suspend fun prepareEmailCode(): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/prepare_first_factor",
            buildJsonObject { put("strategy", "email_code") },
        )

    /** Submit an emailed one-time code as the first factor. */
    suspend fun attemptEmailCode(code: String): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/attempt_first_factor",
            buildJsonObject {
                put("strategy", "email_code")
                put("code", code)
            },
        )

    /**
     * Send a texted / WhatsApp / voice one-time code (`prepare_first_factor`,
     * `strategy=phone_code`). [channel] is `sms` (default), `whatsapp` or `voice`.
     */
    suspend fun preparePhoneCode(channel: String? = null): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/prepare_first_factor",
            buildJsonObject {
                put("strategy", "phone_code")
                if (channel != null) put("channel", channel)
            },
        )

    /** Submit a texted one-time code as the first factor. */
    suspend fun attemptPhoneCode(code: String): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/attempt_first_factor",
            buildJsonObject {
                put("strategy", "phone_code")
                put("code", code)
            },
        )

    /**
     * Prepare a second-factor challenge (§5.3). [strategy] is `sms`, `push`, or
     * null for a passkey (the default when the account has one). Returns the
     * challenge shape for the chosen strategy (not a step — the attempt stays in
     * `needs_second_factor`); then collect the factor and call
     * [attemptSecondFactor] (code) or complete the passkey ceremony separately.
     */
    suspend fun prepareSecondFactor(strategy: String? = null): SecondFactorChallenge {
        val body = buildJsonObject { if (strategy != null) put("strategy", strategy) }
        val response = client.requestRaw(
            "POST",
            "/v1/client/sign_ins/${attemptId()}/prepare_second_factor",
            body.toString(),
            authenticated = false,
        )
        return client.decodeOrThrow(response.body, SecondFactorChallenge.serializer())
    }

    /**
     * Submit a second factor as a code — a TOTP code, an SMS OTP, or a backup
     * recovery code (the server decides which by what matches, §5.3). Pass
     * [rememberDevice] to skip 2FA on this device next time, when the instance
     * enabled it.
     */
    suspend fun attemptSecondFactor(code: String, rememberDevice: Boolean = false): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/attempt_second_factor",
            buildJsonObject {
                put("code", code)
                if (rememberDevice) put("remember_device", true)
            },
        )

    /**
     * Poll an in-app PUSH second factor — the attempt completes only once the
     * device approves with the matching number shown by [prepareSecondFactor]
     * (`strategy=push`). Call it until the step is no longer
     * [FlowStep.CollectSecondFactor].
     */
    suspend fun pollPushSecondFactor(): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/attempt_second_factor",
            buildJsonObject { put("strategy", "push") },
        )

    /**
     * Start mid-sign-in TOTP enrollment (§11.1 MFA policy `required`). Returns the
     * [MfaEnrollment] (secret + provisioning URI, shown once); confirm it with the
     * authenticator's codes via [attemptMfaEnrollment].
     */
    suspend fun prepareMfaEnrollment(): MfaEnrollment {
        val response = client.requestRaw(
            "POST",
            "/v1/client/sign_ins/${attemptId()}/prepare_mfa_enrollment",
            "{}",
            authenticated = false,
        )
        return client.decodeOrThrow(response.body, MfaEnrollment.serializer())
    }

    /**
     * Confirm TOTP enrollment with one or more consecutive authenticator codes
     * (the instance decides how many). On completion the attempt carries the
     * one-time [FlowAttempt.backupCodes] — surface them to the user, they are not
     * shown again.
     */
    suspend fun attemptMfaEnrollment(factorId: String, codes: List<String>): FlowStep =
        post(
            "/v1/client/sign_ins/${attemptId()}/attempt_mfa_enrollment",
            buildJsonObject {
                put("factor_id", factorId)
                put("codes", JsonArray(codes.map { JsonPrimitive(it) }))
            },
        )

    /**
     * Finish a completed sign-in into a persisted session, and return the user.
     *
     * A normal completion carries a one-time `ticket` in the body — exchanged for
     * cookies via the existing [AtlasClient.exchangeTicket]. A completion that
     * instead minted the session directly (the passkey / native shape: `jwt` +
     * `created_session_id`) is persisted through the same direct-session handling
     * the passkey flow uses. Throws [AtlasException.Api] `sign_in_not_complete`
     * when the attempt has not reached `complete`.
     */
    suspend fun complete(): AtlasUser {
        val current = attempt
        val response = last
        if (current == null || response == null || !current.isComplete) {
            throw AtlasException.Api(
                statusCode = 200,
                errors = listOf(
                    AtlasErrorItem(
                        code = "sign_in_not_complete",
                        message = "Sign-in is not complete: ${current?.status ?: "no attempt"}.",
                    ),
                ),
            )
        }
        val ticket = current.ticket
        if (ticket != null) {
            client.exchangeTicket(attemptId = current.id ?: "", ticket = ticket)
            return client.currentUser()
        }
        // No ticket: the attempt minted the session directly (passkey / native).
        return client.persistDirectSession(current.createdSessionId, response)
    }
}
