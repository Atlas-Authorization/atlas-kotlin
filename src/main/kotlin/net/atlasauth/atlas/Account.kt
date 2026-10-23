package net.atlasauth.atlas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account-management surface: organizations, session (device) listing, and
 * the `/me` mutation set. These are `suspend` extension functions on
 * [AtlasClient] so the core class stays focused on auth; they all go through the
 * one shared request path ([AtlasClient.requestRaw]) — publishable key on every
 * call, the stored session presented when authenticated — exactly as
 * [AtlasClient.currentUser] does.
 */

/* ── organizations ─────────────────────────────────────────────────────────── */

/**
 * The organizations the signed-in user belongs to, with their role in each
 * (`GET /v1/client/me/organizations`).
 */
suspend fun AtlasClient.listOrganizationMemberships(): List<OrganizationMembership> {
    val response = requestRaw("GET", "/v1/client/me/organizations", null, authenticated = true)
    return decodeOrThrow(
        response.body,
        ListResponse.serializer(OrganizationMembership.serializer()),
    ).data
}

/**
 * Create an organization (`POST /v1/client/organizations`). Only succeeds when
 * the instance allows user-created organizations; the creator is seated as admin.
 */
suspend fun AtlasClient.createOrganization(name: String, slug: String): Organization {
    val response = requestRaw(
        "POST",
        "/v1/client/organizations",
        buildJsonObject {
            put("name", name)
            put("slug", slug)
        }.toString(),
        authenticated = true,
    )
    return decodeOrThrow(response.body, Organization.serializer())
}

/**
 * Read one organization (`GET /v1/client/organizations/:id`). Resolves against
 * the session's ACTIVE organization — an org the session is not currently acting
 * in reads as not-found (§8.1).
 */
suspend fun AtlasClient.getOrganization(id: String): Organization {
    val response = requestRaw("GET", "/v1/client/organizations/$id", null, authenticated = true)
    return decodeOrThrow(response.body, Organization.serializer())
}

/**
 * One page of an organization's audit trail
 * (`GET /v1/client/organizations/:id/audit_logs`), newest first. Needs the
 * `org:sys_memberships:manage` permission (organization admins). [action]
 * filters exactly, or by prefix when it ends in a dot (`member.`); page on with
 * [OrganizationAuditLogPage.nextCursor] as [startingAfter].
 */
suspend fun AtlasClient.organizationAuditLog(
    organizationId: String,
    limit: Int? = null,
    startingAfter: String? = null,
    action: String? = null,
): OrganizationAuditLogPage {
    val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
    val query = listOfNotNull(
        limit?.let { "limit=$it" },
        startingAfter?.let { "starting_after=${enc(it)}" },
        action?.let { "action=${enc(it)}" },
    ).joinToString("&")
    val path = "/v1/client/organizations/${enc(organizationId)}/audit_logs" + (if (query.isEmpty()) "" else "?$query")
    val response = requestRaw("GET", path, null, authenticated = true)
    return decodeOrThrow(response.body, OrganizationAuditLogPage.serializer())
}

/* ── sessions / devices ──────────────────────────────────────────────────────── */

/** The signed-in user's active devices (`GET /v1/client/sessions`). */
suspend fun AtlasClient.listSessions(): List<DeviceSession> {
    val response = requestRaw("GET", "/v1/client/sessions", null, authenticated = true)
    return decodeOrThrow(response.body, ListResponse.serializer(DeviceSession.serializer())).data
}

/** Revoke one device by id (`POST /v1/client/sessions/:id/revoke`) — sign it out. */
suspend fun AtlasClient.revokeSession(id: String) {
    requestRaw("POST", "/v1/client/sessions/$id/revoke", "{}", authenticated = true)
}

/**
 * Sign out of every OTHER device (`POST /v1/client/sessions/revoke_all`). The
 * current session is spared. Returns how many were revoked.
 */
suspend fun AtlasClient.revokeOtherSessions(): Int {
    val response = requestRaw("POST", "/v1/client/sessions/revoke_all", "{}", authenticated = true)
    return decodeOrThrow(response.body, SessionsRevokedResult.serializer()).sessionsRevoked
}

/* ── /me mutations ───────────────────────────────────────────────────────────── */

/**
 * Edit the signed-in user's profile (`PATCH /v1/client/me`). The frontend may
 * write only [unsafeMetadata] (and display fields); `public_metadata` /
 * `private_metadata` are backend-only (§4.1). Only the arguments you pass are
 * sent, so a null leaves that field unchanged.
 */
suspend fun AtlasClient.updateProfile(
    firstName: String? = null,
    lastName: String? = null,
    username: String? = null,
    locale: String? = null,
    unsafeMetadata: Map<String, JsonValue>? = null,
): AtlasUser {
    val body = buildJsonObject {
        if (firstName != null) put("first_name", firstName)
        if (lastName != null) put("last_name", lastName)
        if (username != null) put("username", username)
        if (locale != null) put("locale", locale)
        if (unsafeMetadata != null) {
            put("unsafe_metadata", JsonValue.toElement(JsonValue.Obj(unsafeMetadata)))
        }
    }
    val response = requestRaw("PATCH", "/v1/client/me", body.toString(), authenticated = true)
    return decodeOrThrow(response.body, AtlasUser.serializer())
}

/** Add an email address (`POST /v1/client/me/email_addresses`). Starts unverified. */
suspend fun AtlasClient.addEmailAddress(email: String): EmailAddress {
    val response = requestRaw(
        "POST",
        "/v1/client/me/email_addresses",
        buildJsonObject { put("email_address", email) }.toString(),
        authenticated = true,
    )
    return decodeOrThrow(response.body, EmailAddress.serializer())
}

/** Verify an added address with its emailed code. */
suspend fun AtlasClient.verifyEmailAddress(id: String, code: String): EmailAddress {
    val response = requestRaw(
        "POST",
        "/v1/client/me/email_addresses/$id/attempt_verification",
        buildJsonObject { put("code", code) }.toString(),
        authenticated = true,
    )
    return decodeOrThrow(response.body, EmailAddress.serializer())
}

/** Make a VERIFIED address the primary one (`POST …/email_addresses/:id/primary`). */
suspend fun AtlasClient.setPrimaryEmailAddress(id: String) {
    requestRaw("POST", "/v1/client/me/email_addresses/$id/primary", "{}", authenticated = true)
}

/** Remove an email address (`DELETE …/email_addresses/:id`). */
suspend fun AtlasClient.deleteEmailAddress(id: String) {
    requestRaw("DELETE", "/v1/client/me/email_addresses/$id", null, authenticated = true)
}

/**
 * Start linking a NEW OAuth provider to the signed-in user
 * (`POST /v1/client/me/external_accounts/connect`). Navigate the browser to the
 * returned [ExternalAccountConnection.authorizationUrl].
 */
suspend fun AtlasClient.connectExternalAccount(
    provider: String,
    redirectUrl: String,
    additionalScopes: List<String>? = null,
): ExternalAccountConnection {
    val body = buildJsonObject {
        put("provider", provider)
        put("redirect_url", redirectUrl)
        if (additionalScopes != null) {
            put("additional_scopes", JsonArray(additionalScopes.map { JsonPrimitive(it) }))
        }
    }
    val response = requestRaw(
        "POST",
        "/v1/client/me/external_accounts/connect",
        body.toString(),
        authenticated = true,
    )
    return decodeOrThrow(response.body, ExternalAccountConnection.serializer())
}

/** Unlink an OAuth provider (`DELETE …/external_accounts/:id`). */
suspend fun AtlasClient.disconnectExternalAccount(id: String) {
    requestRaw("DELETE", "/v1/client/me/external_accounts/$id", null, authenticated = true)
}

/**
 * Change the password of an account that HAS one
 * (`POST /v1/client/me/change_password`). Proves the current password; revokes
 * every other session. Returns the count revoked.
 */
suspend fun AtlasClient.changePassword(currentPassword: String, newPassword: String): Int {
    val response = requestRaw(
        "POST",
        "/v1/client/me/change_password",
        buildJsonObject {
            put("current_password", currentPassword)
            put("new_password", newPassword)
        }.toString(),
        authenticated = true,
    )
    return decodeOrThrow(response.body, ChangePasswordResult.serializer()).sessionsRevoked
}

/**
 * Set a FIRST password on an account that has none — an OAuth-only or guest
 * account (`POST /v1/client/me/set_password`).
 */
suspend fun AtlasClient.setPassword(password: String) {
    requestRaw(
        "POST",
        "/v1/client/me/set_password",
        buildJsonObject { put("password", password) }.toString(),
        authenticated = true,
    )
}

/* ── device sign-in approval (RFC 8628) ─────────────────────────────────────── */

/**
 * Normalise what a person typed into the `XXXX-XXXX` form devices show:
 * uppercase, spaces and dashes ignored. Returns the trimmed input unchanged when
 * it is not 8 letters or digits, so the server answers "not valid".
 */
fun formatUserCode(input: String): String {
    val cleaned = input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
    return if (cleaned.length == 8) "${cleaned.take(4)}-${cleaned.drop(4)}" else input.trim()
}

/** The `user_code` of a verification URL a device displayed (`?user_code=`), formatted; null when none. */
fun userCodeFromUrl(url: String): String? {
    val query = url.substringAfter("://", "").substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    val raw = query.split('&').firstOrNull { it.startsWith("user_code=") }?.substringAfter('=') ?: return null
    val value = java.net.URLDecoder.decode(raw, "UTF-8")
    return if (value.isEmpty()) null else formatUserCode(value)
}

/**
 * Look up a pending device sign-in by the code a TV, watch or CLI shows
 * (`POST /v1/client/me/device_authorizations/lookup`) — e.g. typed in, or read
 * from its QR code with [userCodeFromUrl]. Show [DeviceAuthorization.client] and
 * [DeviceAuthorization.scopes], then approve or deny.
 */
suspend fun AtlasClient.lookupDeviceAuthorization(userCode: String): DeviceAuthorization {
    val body = buildJsonObject { put("user_code", formatUserCode(userCode)) }.toString()
    val response = requestRaw("POST", "/v1/client/me/device_authorizations/lookup", body, authenticated = true)
    return decodeOrThrow(response.body, DeviceAuthorization.serializer())
}

/**
 * Approve the device sign-in for the signed-in user; the device's next poll
 * receives its tokens. When the application requires a recent re-authentication
 * this throws [AtlasException.Api] with code `STEP_UP_REQUIRED` — re-authenticate,
 * then approve again.
 */
suspend fun AtlasClient.approveDeviceAuthorization(authorization: DeviceAuthorization): DeviceAuthorization =
    decideDeviceAuthorization(authorization, "approve")

/** Deny the device sign-in; the device's next poll is told `access_denied`. */
suspend fun AtlasClient.denyDeviceAuthorization(authorization: DeviceAuthorization): DeviceAuthorization =
    decideDeviceAuthorization(authorization, "deny")

private suspend fun AtlasClient.decideDeviceAuthorization(
    authorization: DeviceAuthorization,
    decision: String,
): DeviceAuthorization {
    val id = java.net.URLEncoder.encode(authorization.id, "UTF-8")
    val body = buildJsonObject { put("user_code", authorization.userCode) }.toString()
    val response = requestRaw("POST", "/v1/client/me/device_authorizations/$id/$decision", body, authenticated = true)
    return decodeOrThrow(response.body, DeviceAuthorization.serializer())
}
