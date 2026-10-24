package net.atlasauth.atlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** OIDC scope names for the device and token-exchange helpers. */
object AtlasScopes {
    const val OPENID = "openid"
    const val PROFILE = "profile"
    const val EMAIL = "email"
    const val OFFLINE_ACCESS = "offline_access"

    /** Adds the `orgs` claim — `[{ id, slug, role }]`, at most 20 — to the ID token and userinfo. */
    const val ORGANIZATIONS = "organizations"

    /**
     * [base] plus `organizations` (once), for `startDeviceSignIn`-style helpers that
     * take a scope list.
     */
    fun withOrganizations(base: List<String> = listOf(OPENID, PROFILE, EMAIL)): List<String> =
        if (ORGANIZATIONS in base) base else base + ORGANIZATIONS
}

/** One entry of the `orgs` claim (`organizations` scope). */
data class OidcOrganization(val id: String, val slug: String?, val role: String?)

/** The enterprise SSO connection a sign-in came through (`sso_*` claims). */
data class OidcSso(
    val connectionId: String,
    val tenantId: String?,
    val issuer: String?,
    val domain: String?,
)

/**
 * The organization and enterprise-SSO claims of an Atlas ID token, access token or
 * userinfo response.
 *
 * - `org_id`, `org_slug`, `org_role`, `org_permissions`: the organization the
 *   sign-in applies to (absent when none applies).
 * - `orgs`: every organization, with the `organizations` scope.
 * - `sso_connection_id`, `sso_tenant_id`, `sso_issuer`, `sso_domain`: present only
 *   for sign-ins through an enterprise SSO connection.
 */
data class OidcClaims(
    val orgId: String? = null,
    val orgSlug: String? = null,
    val orgRole: String? = null,
    val orgPermissions: List<String> = emptyList(),
    val orgs: List<OidcOrganization> = emptyList(),
    val sso: OidcSso? = null,
    /** `sso_organization_id`: the Atlas organization that owns the SSO connection (independent of `org_id`). */
    val ssoOrganizationId: String? = null,
    /** `sso_connection_type`: `saml` or `oidc`; absent on a non-SSO sign-in. */
    val ssoConnectionType: String? = null,
) {
    companion object {
        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** Read the claims off a decoded claims object (userinfo, introspection, or a JWT payload). */
        fun fromClaims(claims: JsonObject): OidcClaims {
            val permissions = (claims["org_permissions"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val orgs = (claims["orgs"] as? JsonArray).orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                OidcOrganization(id, o.str("slug"), o.str("role"))
            }
            val ssoId = claims.str("sso_connection_id")
            val sso = ssoId?.let {
                OidcSso(it, claims.str("sso_tenant_id"), claims.str("sso_issuer"), claims.str("sso_domain"))
            }
            return OidcClaims(
                orgId = claims.str("org_id"),
                orgSlug = claims.str("org_slug"),
                orgRole = claims.str("org_role"),
                orgPermissions = permissions,
                orgs = orgs,
                sso = sso,
                ssoOrganizationId = claims.str("sso_organization_id"),
                ssoConnectionType = claims.str("sso_connection_type"),
            )
        }

        /**
         * Read the claims off a JWT's payload. The signature is NOT verified here —
         * use this for display and routing on a token your app just received from
         * Atlas, never to authorize on a token that arrived from elsewhere. Returns
         * null for something that is not a JWT.
         */
        fun fromJwt(jwt: String): OidcClaims? {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payload = runCatching {
                java.util.Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
            }.getOrNull() ?: return null
            val obj = runCatching {
                Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
            }.getOrNull() ?: return null
            return fromClaims(obj)
        }
    }
}
