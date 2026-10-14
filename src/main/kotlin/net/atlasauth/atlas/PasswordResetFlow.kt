package net.atlasauth.atlas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A suspend-driven password-reset state machine over `/v1/client/password_resets`
 * (§5.4). Request a reset for an email (the response is identical whether or not
 * the address exists — no enumeration oracle), submit the emailed code, clear the
 * second factor when the account has MFA, set the new password, then [complete].
 *
 * `set_new_password` revokes every other session; when the instance's
 * sign-in-after-reset toggle is on it hands back a one-time ticket, which
 * [complete] exchanges so the user ends up signed in. When the toggle is off
 * there is no ticket and the flow finishes without a session — the user signs in
 * fresh.
 *
 * All methods are `suspend`.
 */
class PasswordResetFlow internal constructor(private val client: AtlasClient) {

    private var last: AtlasClient.AtlasResponse? = null

    /** The current attempt, or null before [request]. */
    var attempt: FlowAttempt? = null
        private set

    /** The server-dictated next step; null before [request]. */
    val step: FlowStep? get() = attempt?.let { nextStep(it) }

    /** The current attempt status, or null before [request]. */
    val status: String? get() = attempt?.status

    private fun absorb(response: AtlasClient.AtlasResponse): FlowStep {
        last = response
        val decoded = client.decodeOrThrow(response.body, FlowAttempt.serializer())
        attempt = decoded
        return nextStep(decoded)
    }

    private fun attemptId(): String =
        attempt?.id ?: throw AtlasException.Decoding("No active reset — call request() first.")

    private suspend fun post(path: String, body: JsonObject): FlowStep =
        absorb(client.requestRaw("POST", path, body.toString(), authenticated = false))

    /** Request a reset for an email address. `POST /v1/client/password_resets`. */
    suspend fun request(email: String): FlowStep =
        post("/v1/client/password_resets", buildJsonObject { put("email_address", email) })

    /** Submit the emailed verification code. */
    suspend fun attemptVerification(code: String): FlowStep =
        post(
            "/v1/client/password_resets/${attemptId()}/attempt_verification",
            buildJsonObject { put("code", code) },
        )

    /**
     * Submit the second factor when the account has MFA — a reset must not bypass
     * it (§5.4). Accepts a TOTP / SMS / backup code.
     */
    suspend fun attemptSecondFactor(code: String): FlowStep =
        post(
            "/v1/client/password_resets/${attemptId()}/attempt_second_factor",
            buildJsonObject { put("code", code) },
        )

    /** Set the new password. Revokes every other session; may carry a sign-in ticket. */
    suspend fun setNewPassword(password: String): FlowStep =
        post(
            "/v1/client/password_resets/${attemptId()}/set_new_password",
            buildJsonObject { put("password", password) },
        )

    /** Whether the completed reset handed back a ticket (sign-in-after-reset on). */
    val canComplete: Boolean get() = attempt?.ticket != null

    /**
     * Exchange the reset's sign-in ticket for a session and return the user. Only
     * valid after [setNewPassword] completed with the sign-in-after-reset toggle
     * on ([canComplete] is true); throws otherwise, the caller's cue to send the
     * user to a fresh sign-in.
     */
    suspend fun complete(): AtlasUser {
        val current = attempt
        val ticket = current?.ticket
            ?: throw AtlasException.Api(
                statusCode = 200,
                errors = listOf(
                    AtlasErrorItem(
                        code = "reset_no_session",
                        message = "This reset did not issue a session; sign in with the new password.",
                    ),
                ),
            )
        client.exchangeTicket(attemptId = current.id ?: "", ticket = ticket)
        return client.currentUser()
    }
}
