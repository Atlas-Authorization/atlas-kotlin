package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Models for the organizations / sessions / `/me`-mutation surface. They mirror
 * the FAPI response shapes field-for-field (snake_case → camelCase), and the
 * tolerant JSON config means a server that adds a field never breaks decoding.
 */

/** A generic FAPI list envelope: `{ object: "list", data: [...] }`. */
@Serializable
internal data class ListResponse<T>(val data: List<T> = emptyList())

/** The `POST …/id_token/nonce` response. */
@Serializable
internal data class NativeNonceResponse(val nonce: String? = null)

/** The `change_password` response, carrying how many other sessions were revoked. */
@Serializable
internal data class ChangePasswordResult(
    @SerialName("sessions_revoked") val sessionsRevoked: Int = 0,
)

/** The `sessions/revoke_all` response. */
@Serializable
internal data class SessionsRevokedResult(
    @SerialName("sessions_revoked") val sessionsRevoked: Int = 0,
)

/**
 * An organization (§8). `private_metadata` is backend-only and never serialised
 * to the frontend, so it is absent here by construction (§4.4).
 */
@Serializable
data class Organization(
    val id: String,
    val name: String,
    val slug: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("public_metadata") val publicMetadata: Map<String, JsonValue>? = null,
    @SerialName("max_allowed_memberships") val maxAllowedMemberships: Int? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

/** The signed-in user's membership in an organization: their [role] and the [organization]. */
@Serializable
data class OrganizationMembership(
    val role: String,
    val organization: Organization,
)

/**
 * One of the signed-in user's active devices (§10.2). [current] marks the device
 * the request itself arrived on, so a UI can refuse to sign the current session
 * out silently. The geo / UA fields are best-effort and absent when never
 * captured or no GeoLite2 DB is configured.
 */
@Serializable
data class DeviceSession(
    val id: String,
    val status: String,
    val current: Boolean = false,
    @SerialName("last_active_at") val lastActiveAt: Long? = null,
    @SerialName("expire_at") val expireAt: Long? = null,
    @SerialName("abandon_at") val abandonAt: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
    @SerialName("device_label") val deviceLabel: String? = null,
    val browser: String? = null,
    val os: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
    val location: String? = null,
)

/**
 * The start of a "connect provider" OAuth flow (§6.6). Navigate the browser /
 * Custom Tab to [authorizationUrl]; the callback returns to your redirect URL
 * with `__atlas_status=connected`.
 */
@Serializable
data class ExternalAccountConnection(
    val provider: String,
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("authorization_url") val authorizationUrl: String,
    val scopes: List<String> = emptyList(),
)

/** Who acted, or what was acted on, in an organization audit entry. */
@Serializable
data class AuditParty(val type: String? = null, val id: String? = null)

/** One entry of an organization's audit trail. */
@Serializable
data class OrganizationAuditLogEntry(
    val id: String,
    /** e.g. `member.role_changed`. */
    val action: String,
    val actor: AuditParty = AuditParty(),
    val target: AuditParty = AuditParty(),
    @SerialName("organization_id") val organizationId: String,
    val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    val metadata: kotlinx.serialization.json.JsonObject? = null,
    /** Epoch ms. */
    @SerialName("created_at") val createdAt: Long,
)

/** One page of an organization's audit trail. */
@Serializable
data class OrganizationAuditLogPage(
    val data: List<OrganizationAuditLogEntry> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * What a sign-out hands back for signing out of the upstream identity provider
 * too: the upstream OIDC provider's end-session URL, and the SAML Single Logout
 * start URL. Only `https` URLs are kept. Open [logoutUrl] in the system browser.
 */
data class SignOutResult(
    val upstreamLogoutUrl: String? = null,
    val samlLogoutUrl: String? = null,
) {
    /** The URL to open: the SAML one, else the upstream OIDC one. */
    val logoutUrl: String? get() = samlLogoutUrl ?: upstreamLogoutUrl

    companion object {
        internal fun fromResponse(json: kotlinx.serialization.json.Json, body: String): SignOutResult {
            val obj = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                ?: return SignOutResult()
            val root = (obj["response"] as? kotlinx.serialization.json.JsonObject) ?: obj
            fun https(key: String): String? {
                val v = (root[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                return v?.takeIf { it.startsWith("https://") }
            }
            return SignOutResult(https("upstream_logout_url"), https("saml_logout_url"))
        }
    }
}

/** One requested scope of a [DeviceAuthorization], with a human description. */
@Serializable
data class DeviceAuthorizationScope(
    val scope: String,
    val description: String = "",
)

/** The client (the TV, console or CLI app) asking to sign in. */
@Serializable
data class DeviceAuthorizationClient(
    @SerialName("client_id") val clientId: String = "",
    val name: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("homepage_url") val homepageUrl: String? = null,
    @SerialName("first_party") val firstParty: Boolean = false,
)

/**
 * A pending device sign-in (OAuth 2.0 Device Authorization Grant): the code a TV,
 * watch or CLI shows, which the signed-in user approves from this app.
 */
@Serializable
data class DeviceAuthorization(
    val id: String,
    /** The code as the device shows it (`XXXX-XXXX`). */
    @SerialName("user_code") val userCode: String,
    /** `pending`, `approved` or `denied`. */
    val status: String = "pending",
    val client: DeviceAuthorizationClient = DeviceAuthorizationClient(),
    val scopes: List<DeviceAuthorizationScope> = emptyList(),
    /** The API the device asked a token for, if any. */
    val audience: String? = null,
    /** Approving needs a recent re-authentication. */
    @SerialName("requires_step_up") val requiresStepUp: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
)
