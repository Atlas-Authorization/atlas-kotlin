package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A passkey-only sign-up (`POST /v1/client/sign_ups/passkey`): the server runs
 * every sign-up gate and records the account it will create — no user exists
 * until the passkey is registered ([PasskeyManager.createSignUpPasskey]), so a
 * user who abandons midway leaves nothing behind.
 *
 *   1. [start] with the email (when the instance collects one), username, fields;
 *   2. when the instance requires a verified email, [attemptVerification];
 *   3. once [awaitingPasskey], [PasskeyManager.createSignUpPasskey], then [complete].
 */
class PasskeySignUpFlow(private val client: AtlasClient) {

    private var last: AtlasClient.AtlasResponse? = null
    private var pollSecret: String? = null

    /** The current attempt, or null before [start]. */
    var attempt: FlowAttempt? = null
        private set

    /** The server-dictated next step. */
    val step: FlowStep get() = attempt?.let { nextStep(it) } ?: FlowStep.CollectIdentifier

    /** True once the attempt waits for its passkey. */
    val awaitingPasskey: Boolean
        get() = attempt?.let { it.status == "needs_first_factor" && "passkey" in (it.supportedFirstFactors ?: emptyList()) } ?: false

    /** Start the sign-up. */
    @JvmOverloads
    suspend fun start(
        email: String? = null,
        username: String? = null,
        fields: Map<String, String>? = null,
        consent: Boolean? = null,
        captchaToken: String? = null,
        policyAcceptances: List<PolicyAcceptance>? = null,
    ): FlowStep {
        val body = buildJsonObject {
            if (!email.isNullOrEmpty()) put("email", email)
            if (!username.isNullOrEmpty()) put("username", username)
            if (!fields.isNullOrEmpty()) putJsonObject("fields") { fields.forEach { (k, v) -> put(k, v) } }
            if (consent == true) put("consent", true)
            if (!policyAcceptances.isNullOrEmpty()) put("policy_acceptances", policyAcceptancesJson(policyAcceptances))
            if (!captchaToken.isNullOrEmpty()) put("captcha_token", captchaToken)
        }
        val response = client.requestRaw("POST", "/v1/client/sign_ups/passkey", body.toString(), authenticated = false)
        pollSecret = client.decodeOrThrow(response.body, PollSecretEnvelope.serializer()).pollSecret
        return absorb(response)
    }

    /** Continue an attempt held at `needs_policy_acceptance` (proof: the policy token, else this sign-up's poll secret). */
    suspend fun acceptPolicies(acceptances: List<PolicyAcceptance>): FlowStep =
        absorb(
            client.requestRaw(
                "POST",
                "/v1/client/sign_ins/${attemptId()}/accept_policies",
                acceptPoliciesBody(acceptances, attempt?.policyAcceptanceToken, pollSecret).toString(),
                authenticated = false,
            ),
        )

    /** Submit the emailed verification code (when the instance requires a verified email). */
    suspend fun attemptVerification(code: String): FlowStep =
        absorb(
            client.requestRaw(
                "POST",
                "/v1/client/sign_ups/${attemptId()}/attempt_verification",
                buildJsonObject { put("code", code) }.toString(),
                authenticated = false,
            ),
        )

    /** Finish a completed sign-up into a persisted session and return the user. */
    suspend fun complete(): AtlasUser {
        val current = attempt
        val response = last
        if (current == null || response == null || !current.isComplete) {
            throw AtlasException.Api(
                statusCode = 200,
                errors = listOf(AtlasErrorItem(code = "sign_up_not_complete", message = "Sign-up is not complete: ${current?.status ?: "no attempt"}.")),
            )
        }
        val ticket = current.ticket
        if (ticket != null) {
            client.exchangeTicket(attemptId = current.id ?: "", ticket = ticket)
            return client.currentUser()
        }
        return client.persistDirectSession(current.createdSessionId, response)
    }

    internal fun passkeyContext(): Pair<String, String> {
        val id = attempt?.id
        val secret = pollSecret
        if (id == null || secret == null) throw AtlasException.Decoding("This sign-up cannot create a passkey (start it first).")
        return id to secret
    }

    internal suspend fun finish(body: Map<String, String>): FlowStep =
        absorb(
            client.requestRaw(
                "POST",
                "/v1/client/sign_ups/${attemptId()}/passkey/finish",
                JsonObject(body.mapValues { JsonPrimitive(it.value) }).toString(),
                authenticated = false,
            ),
        )

    private fun absorb(response: AtlasClient.AtlasResponse): FlowStep {
        last = response
        val decoded = client.decodeOrThrow(response.body, FlowAttempt.serializer())
        attempt = decoded
        return nextStep(decoded)
    }

    private fun attemptId(): String =
        attempt?.id ?: throw AtlasException.Decoding("No active sign-up attempt — call start() first.")
}

@Serializable
internal data class PollSecretEnvelope(@SerialName("poll_secret") val pollSecret: String? = null)
