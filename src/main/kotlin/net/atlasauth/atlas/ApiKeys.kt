package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * End-user API key self-service over `/v1/client/me/api_keys`: the signed-in
 * user's own `ak_` keys, or (with `organizationId`) an organization's. The feature
 * is off until the application enables it (a 403 on the list). Creating a key
 * needs a recent re-authentication: a stale session throws [AtlasException.Api]
 * with code `STEP_UP_REQUIRED`; call [stepUp], then create again.
 */

/** An API key as its owner sees it. The secret is never part of it. */
@Serializable
data class UserApiKey(
    val id: String,
    /** `user` or `organization`. */
    @SerialName("subject_type") val subjectType: String = "user",
    @SerialName("subject_id") val subjectId: String = "",
    val name: String? = null,
    /** The public `ak_` head: recognises a key, cannot be used as one. */
    val prefix: String = "",
    val type: String? = null,
    val scopes: List<String>? = null,
    @SerialName("last_used_at") val lastUsedAt: Double? = null,
    @SerialName("expires_at") val expiresAt: Double? = null,
    @SerialName("revoked_at") val revokedAt: Double? = null,
    /** Why it was revoked when the owner did not. */
    @SerialName("revoked_reason") val revokedReason: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("created_at") val createdAt: Double = 0.0,
) {
    /** Whether the key still works at [nowMillis]. */
    fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        revokedAt == null && (expiresAt == null || expiresAt > nowMillis)
}

/** The create response: the key plus its one-time [secret], shown exactly once. */
@Serializable
data class CreatedUserApiKey(
    val id: String,
    @SerialName("subject_type") val subjectType: String = "user",
    @SerialName("subject_id") val subjectId: String = "",
    val name: String? = null,
    val prefix: String = "",
    val type: String? = null,
    val scopes: List<String>? = null,
    @SerialName("expires_at") val expiresAt: Double? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("created_at") val createdAt: Double = 0.0,
    /** The plaintext `ak_` key. Store it now; it is never returned again. */
    val secret: String,
)

/** The user's keys with the limits of the instance. */
@Serializable
data class UserApiKeyList(
    @SerialName("data") val keys: List<UserApiKey> = emptyList(),
    /** The scopes a new key may carry. */
    @SerialName("allowed_scopes") val allowedScopes: List<String> = emptyList(),
    /** The most active keys this user (or organization) may hold; 0 when unknown. */
    @SerialName("max_active_keys") val maxActiveKeys: Int = 0,
    @SerialName("active_count") val activeCount: Int = 0,
)

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * The user's API keys, or an organization's with [organizationId]. With [bound],
 * your own keys bound to that organization instead.
 */
suspend fun AtlasClient.listApiKeys(organizationId: String? = null, type: String? = null, bound: Boolean = false): UserApiKeyList {
    val q = buildList {
        if (organizationId != null) add("organization_id=${enc(organizationId)}")
        if (bound && organizationId != null) add("subject_type=user")
        if (type != null) add("type=${enc(type)}")
    }.joinToString("&")
    val response = requestRaw("GET", "/v1/client/me/api_keys" + if (q.isEmpty()) "" else "?$q", null, authenticated = true)
    return decodeOrThrow(response.body, UserApiKeyList.serializer())
}

/**
 * Create a key. [expiresInSeconds] and [expiresAt] (epoch milliseconds) are
 * mutually exclusive. [organizationId] creates the organization's key; with
 * [bindToOrganization], your own key bound to that organization.
 */
suspend fun AtlasClient.createApiKey(
    name: String? = null,
    expiresInSeconds: Long? = null,
    expiresAt: Long? = null,
    scopes: List<String>? = null,
    organizationId: String? = null,
    bindToOrganization: Boolean = false,
    type: String? = null,
): CreatedUserApiKey {
    val body = buildJsonObject {
        if (name != null) put("name", name)
        if (expiresInSeconds != null) put("expires_in_seconds", expiresInSeconds)
        if (expiresAt != null) put("expires_at", expiresAt)
        if (scopes != null) put("scopes", JsonArray(scopes.map { JsonPrimitive(it) }))
        if (organizationId != null) put("organization_id", organizationId)
        if (bindToOrganization) put("subject_type", "user")
        if (type != null) put("type", type)
    }.toString()
    return decodeOrThrow(requestRaw("POST", "/v1/client/me/api_keys", body, authenticated = true).body, CreatedUserApiKey.serializer())
}

/** Rename a key; null clears the name. */
suspend fun AtlasClient.renameApiKey(id: String, name: String?): UserApiKey {
    val body = buildJsonObject { put("name", if (name == null) JsonNull else JsonPrimitive(name)) }.toString()
    return decodeOrThrow(requestRaw("PATCH", "/v1/client/me/api_keys/${enc(id)}", body, authenticated = true).body, UserApiKey.serializer())
}

/** Revoke a key. Immediate and permanent. */
suspend fun AtlasClient.revokeApiKey(id: String) {
    requestRaw("POST", "/v1/client/me/api_keys/${enc(id)}/revoke", null, authenticated = true)
}

/* ── active organization + step-up ───────────────────────────────────────────── */

@Serializable
private data class SessionTouchResult(
    val id: String? = null,
    val jwt: String = "",
    @SerialName("last_active_organization_id") val lastActiveOrganizationId: String? = null,
)

/**
 * Switch the active organization for the current session
 * (`POST /v1/client/sessions/:id/touch`). Pass null for the personal workspace.
 * The re-minted JWT is persisted so later calls carry the new active-org claim.
 * Returns the new active organization id.
 */
suspend fun AtlasClient.setActiveOrganization(organizationId: String?): String? {
    val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
    val body = buildJsonObject { put("active_organization_id", if (organizationId == null) JsonNull else JsonPrimitive(organizationId)) }.toString()
    val response = requestRaw("POST", "/v1/client/sessions/${enc(stored.sessionId)}/touch", body, authenticated = true)
    val result = decodeOrThrow(response.body, SessionTouchResult.serializer())
    // The touch response carries no new refresh token: keep the stored one.
    tokenStore.save(AtlasSession(result.id ?: stored.sessionId, result.jwt.ifEmpty { stored.token }, stored.refreshToken))
    return result.lastActiveOrganizationId
}

/**
 * The active organization id carried by the stored session token (`org_id`
 * claim), or null for the personal workspace or when signed out. Offline.
 */
fun AtlasClient.activeOrganizationId(): String? {
    val token = runCatching { tokenStore.load() }.getOrNull()?.token ?: return null
    return activeOrganizationIdOf(token)
}

internal fun activeOrganizationIdOf(token: String): String? = OidcClaims.fromJwt(token)?.orgId

/**
 * Step-up re-authentication (`POST /v1/client/me/step_up`): re-prove one
 * credential (`password`, `totp`, `backup_code`, `email_code`, `phone_code`)
 * before a sensitive action such as creating an API key. The fresh session token
 * is persisted. A pending push/duo answer (202) returns without one.
 */
suspend fun AtlasClient.stepUp(strategy: String, password: String? = null, code: String? = null) {
    val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
    val body = buildJsonObject {
        put("strategy", strategy)
        if (password != null) put("password", password)
        if (code != null) put("code", code)
    }.toString()
    persistStepUp(stored, requestRaw("POST", "/v1/client/me/step_up", body, authenticated = true).body)
}

/** Persist the fresh session token a step-up answers with; false when it carried none (a push still pending, 202). */
internal fun AtlasClient.persistStepUp(stored: AtlasSession, responseBody: String): Boolean {
    val tokens = runCatching { decodeOrThrow(responseBody, SessionTouchResult.serializer()) }.getOrNull()
    if (tokens == null || tokens.jwt.isEmpty()) return false
    tokenStore.save(AtlasSession(tokens.id ?: stored.sessionId, tokens.jwt, stored.refreshToken))
    return true
}

/** What starting a push step-up returns: show [numberMatch] on this screen; the user taps it on their device. */
@Serializable
data class StepUpPushChallenge(@SerialName("number_match") val numberMatch: String? = null)

/**
 * Send the signed-in session a push step-up prompt (`POST /v1/client/me/step_up/push`;
 * 429 `LOCKED` while prompts are paused after denials). Then call [stepUpPush] until it returns true.
 */
suspend fun AtlasClient.beginPushStepUp(): StepUpPushChallenge =
    decodeOrThrow(requestRaw("POST", "/v1/client/me/step_up/push", "{}", authenticated = true).body, StepUpPushChallenge.serializer())

/** Ask whether the user answered the push prompt: true once they approved (the fresh token is persisted), false while pending. */
suspend fun AtlasClient.stepUpPush(): Boolean {
    val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
    val body = buildJsonObject { put("strategy", "push") }.toString()
    return persistStepUp(stored, requestRaw("POST", "/v1/client/me/step_up", body, authenticated = true).body)
}

/** The `POST /me/step_up` body for a passkey assertion: the sign-in finish body without its `handle`, plus the strategy. */
internal fun stepUpPasskeyBody(beginJson: String, authenticationResponseJson: String): String {
    val fields = toAssertionFinishBody(beginJson, authenticationResponseJson).filterKeys { it != "handle" }
    return buildJsonObject {
        put("strategy", "passkey")
        fields.forEach { (k, v) -> put(k, v) }
    }.toString()
}
