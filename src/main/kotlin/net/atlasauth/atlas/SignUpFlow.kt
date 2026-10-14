package net.atlasauth.atlas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A suspend-driven sign-up state machine over `/v1/client/sign_ups`. Create the
 * attempt with an email + password (plus optional tenant fields / metadata),
 * send and submit the email verification code, then [complete] into a session —
 * the same ticket handoff sign-in uses.
 *
 * Each action returns the next [FlowStep]; the driver reads the server's status
 * and never picks the step itself (§5). All methods are `suspend`.
 */
class SignUpFlow internal constructor(private val client: AtlasClient) {

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
        attempt?.id ?: throw AtlasException.Decoding("No active sign-up attempt — call create() first.")

    private suspend fun post(path: String, body: JsonObject): FlowStep =
        absorb(client.requestRaw("POST", path, body.toString(), authenticated = false))

    /**
     * Start a sign-up (§5.1). `POST /v1/client/sign_ups`.
     *
     * @param fields the tenant's configured extra fields (name, company, …).
     * @param unsafeMetadata frontend-writable metadata to seed on the new user.
     * @param organizationId seat this self-signup into an organization's reader pool.
     * @param consent set when the instance requires agreeing to the legal terms.
     */
    suspend fun create(
        email: String,
        password: String,
        fields: Map<String, String>? = null,
        unsafeMetadata: Map<String, JsonValue>? = null,
        organizationId: String? = null,
        consent: Boolean? = null,
    ): FlowStep =
        post(
            "/v1/client/sign_ups",
            buildJsonObject {
                put("email", email)
                put("password", password)
                if (fields != null) {
                    putJsonObject("fields") { fields.forEach { (k, v) -> put(k, v) } }
                }
                if (unsafeMetadata != null) {
                    put("unsafe_metadata", JsonValue.toElement(JsonValue.Obj(unsafeMetadata)))
                }
                if (organizationId != null) put("organization_id", organizationId)
                if (consent != null) put("consent", consent)
            },
        )

    /** Send the email verification code (`prepare_verification`). */
    suspend fun prepareVerification(): FlowStep =
        post("/v1/client/sign_ups/${attemptId()}/prepare_verification", buildJsonObject {})

    /** Submit the email verification code (`attempt_verification`). */
    suspend fun attemptVerification(code: String): FlowStep =
        post(
            "/v1/client/sign_ups/${attemptId()}/attempt_verification",
            buildJsonObject { put("code", code) },
        )

    /**
     * Finish a completed sign-up into a persisted session and return the user.
     * Uses the one-time `ticket` the completion carries (the same handoff
     * sign-in uses); falls back to direct-session persistence if the server
     * minted the session inline. Throws `sign_up_not_complete` when the attempt
     * has not reached `complete`.
     */
    suspend fun complete(): AtlasUser {
        val current = attempt
        val response = last
        if (current == null || response == null || !current.isComplete) {
            throw AtlasException.Api(
                statusCode = 200,
                errors = listOf(
                    AtlasErrorItem(
                        code = "sign_up_not_complete",
                        message = "Sign-up is not complete: ${current?.status ?: "no attempt"}.",
                    ),
                ),
            )
        }
        val ticket = current.ticket
        if (ticket != null) {
            client.exchangeTicket(attemptId = current.id ?: "", ticket = ticket)
            return client.currentUser()
        }
        return client.persistDirectSession(current.createdSessionId, response)
    }
}
