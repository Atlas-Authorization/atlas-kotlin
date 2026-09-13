package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A sign-in attempt (§5). The SDK never advances the flow itself — it reads
 * [status] and lets the server say what the next step is. Mirrors the FAPI
 * `sign_in_attempt` / `AttemptView` shape.
 */
@Serializable
data class SignInAttempt(
    val id: String,
    val status: String,
    /**
     * The server's list of first-factor strategies. §13.2 makes this identical
     * for unknown identifiers, so it must never be filtered client-side.
     */
    @SerialName("supported_first_factors") val supportedFirstFactors: List<String>? = null,
    @SerialName("created_session_id") val createdSessionId: String? = null,
    /** Present only when a redirect flow returns an authorize URL. */
    @SerialName("authorization_url") val authorizationUrl: String? = null,
    /**
     * Present for exactly one step — the one that reached `complete`. Exchanged
     * for a session, then gone.
     */
    val ticket: String? = null,
) {
    val isComplete: Boolean
        get() = status == "complete"
}

/**
 * The response of a ticket exchange or a token rotation (§9.2). [jwt] is the
 * short-lived session token; the long-lived refresh token is delivered as an
 * HttpOnly cookie and captured separately.
 */
@Serializable
data class SessionTokens(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val jwt: String,
    @SerialName("expires_in") val expiresIn: Int? = null,
) {
    /**
     * The session id, whichever key the endpoint used (`id` on exchange,
     * `session_id` on rotate).
     */
    val resolvedSessionId: String?
        get() = id ?: sessionId
}

/**
 * The FAPI view of the signed-in user (`GET /v1/client/me`). `private_metadata`
 * and `password_hash` are absent by construction on the server (§4.1); the
 * frontend may write `unsafe_metadata` and nothing else.
 */
@Serializable
data class AtlasUser(
    val id: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val locale: String? = null,
    @SerialName("public_metadata") val publicMetadata: Map<String, JsonValue>? = null,
    @SerialName("unsafe_metadata") val unsafeMetadata: Map<String, JsonValue>? = null,
    @SerialName("mfa_enabled") val mfaEnabled: Boolean? = null,
    @SerialName("has_password") val hasPassword: Boolean? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("primary_email_id") val primaryEmailId: String? = null,
    @SerialName("email_addresses") val emailAddresses: List<EmailAddress>? = null,
    @SerialName("external_accounts") val externalAccounts: List<ExternalAccount>? = null,
    val passkeys: List<Passkey>? = null,
)

@Serializable
data class EmailAddress(
    val id: String,
    @SerialName("email_address") val emailAddress: String,
    val verified: Boolean,
    val primary: Boolean,
)

@Serializable
data class ExternalAccount(
    val id: String,
    val provider: String,
    /**
     * The provider's email is a snapshot, not authoritative for ownership
     * (§4.2) — do not treat it as identity.
     */
    @SerialName("provider_email") val providerEmail: String? = null,
    @SerialName("connected_at") val connectedAt: Long? = null,
)

@Serializable
data class Passkey(
    val id: String,
    val name: String? = null,
)

/**
 * What the SDK persists after sign-in. The [token] (session JWT) goes in the
 * encrypted store; [refreshToken] is the HttpOnly `__atlas_rt` cookie the SDK
 * re-presents on authenticated calls and rotates on refresh.
 */
@Serializable
data class AtlasSession(
    val sessionId: String,
    val token: String,
    val refreshToken: String? = null,
)
