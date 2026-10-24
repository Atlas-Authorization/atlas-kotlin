package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * A short-lived token for a configured identity integration — what
 * [AtlasClient.getToken] resolves. Any service that verifies the user with a JWT
 * (the built-in presets `chatly`, `forumulate`, `cookiemunch`, `referalli`, or a
 * custom integration id) accepts it as the user's identity.
 */
@Serializable
data class IntegrationToken(
    val jwt: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
    /** The integration id it was minted for (a preset or a custom id). */
    val integration: String = "",
    /** The token's `aud`. */
    val audience: String = "",
    /** Forumulate preset: the organization the reader belongs to. */
    @SerialName("org_id") val orgId: String? = null,
    /** Forumulate preset: the space the token is for. */
    @SerialName("space_id") val spaceId: String? = null,
    /** Forumulate preset: where the reader's browser redeems the token. */
    @SerialName("redeem_url") val redeemUrl: String? = null,
)

/**
 * An integration token for a Sign in with Atlas user (see
 * [AtlasClient.getAtlasIdToken]).
 */
@Serializable
data class AtlasIdIntegrationToken(
    val jwt: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
    val integration: String = "",
    val audience: String = "",
    /** The Sign in with Atlas issuer: the token's `iss`. */
    val issuer: String = "",
    /** Where the receiving service fetches the keys to verify it. */
    @SerialName("jwks_uri") val jwksUri: String = "",
)

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * `getToken({ for })` for the signed-in session
 * (`POST /v1/client/sessions/:id/integration_tokens/:integration`): a short-lived
 * token for a configured identity integration. The stored refresh cookie is
 * presented, never rotated.
 *
 * Returns null when the integration is not enabled (404). A refusal such as 401
 * or 403 throws [AtlasException.Api].
 *
 * @param integration the integration id (a preset such as `chatly`, or a custom id).
 * @param spaceId Forumulate preset only: the space to mint the reader token for.
 * @throws AtlasException.NotSignedIn when there is no stored session.
 */
suspend fun AtlasClient.getToken(integration: String, spaceId: String? = null): IntegrationToken? {
    val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
    val query = if (spaceId.isNullOrEmpty()) "" else "?space_id=${enc(spaceId)}"
    val response = try {
        requestRaw(
            "POST",
            "/v1/client/sessions/${enc(stored.sessionId)}/integration_tokens/${enc(integration)}$query",
            null,
            authenticated = true,
        )
    } catch (e: AtlasException.Api) {
        if (e.statusCode == 404) return null
        throw e
    }
    return decodeOrThrow(response.body, IntegrationToken.serializer())
}

/**
 * `getToken({ for })` for a user who signed in with Sign in with Atlas (not a
 * session of this instance), from an app with no server of its own
 * (`POST /v1/client/integrations/:integration/atlas_id_tokens`).
 *
 * The user's Sign in with Atlas access token goes as `Authorization: Bearer`, or
 * as `DPoP` with [dpopProof] when the token is DPoP-bound. Atlas checks the
 * request's `Origin` against the web origins of the Sign in with Atlas app the
 * token was issued to; a native app that shares that app's registration passes
 * one of those origins as [origin]. No cookie is sent.
 *
 * Returns null when the integration is not enabled for Sign in with Atlas users
 * (404). Other refusals — 401 for a token Atlas does not accept, 403
 * `ORIGIN_NOT_ALLOWED` — throw [AtlasException.Api].
 */
suspend fun AtlasClient.getAtlasIdToken(
    integration: String,
    accessToken: String,
    origin: String? = null,
    dpopProof: String? = null,
): AtlasIdIntegrationToken? {
    val headers = LinkedHashMap<String, String>()
    if (dpopProof != null) {
        headers["Authorization"] = "DPoP $accessToken"
        headers["DPoP"] = dpopProof
    } else {
        headers["Authorization"] = "Bearer $accessToken"
    }
    if (!origin.isNullOrEmpty()) headers["Origin"] = origin
    val response = try {
        requestRaw(
            "POST",
            "/v1/client/integrations/${enc(integration)}/atlas_id_tokens?publishable_key=${enc(publishableKey)}",
            null,
            authenticated = false,
            extraHeaders = headers,
        )
    } catch (e: AtlasException.Api) {
        if (e.statusCode == 404) return null
        throw e
    }
    return decodeOrThrow(response.body, AtlasIdIntegrationToken.serializer())
}
