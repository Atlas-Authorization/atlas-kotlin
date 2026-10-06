package com.atlas.sdk

/**
 * A sign-in attempt (§5). The SDK never advances the flow itself — it reads
 * `status` and lets the server say what comes next. Mirrors the FAPI
 * `sign_in_attempt` / `AttemptView` shape.
 */
data class SignInAttempt(
    val id: String,
    val status: String,
    /** The server's first-factor strategy list; never filtered client-side (§13.2). */
    val supportedFirstFactors: List<String>?,
    val authorizationUrl: String?,
    /** Present for exactly the step that reached `complete`; exchanged then gone. */
    val ticket: String?,
) {
    val isComplete: Boolean get() = status == "complete"

    companion object {
        fun from(json: JsonValue): SignInAttempt = SignInAttempt(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("Attempt has no id.")),
            status = json.string("status") ?: "unknown",
            supportedFirstFactors = json.arr("supported_first_factors")?.items
                ?.mapNotNull { (it as? JsonValue.Str)?.value },
            authorizationUrl = json.string("authorization_url"),
            ticket = json.string("ticket"),
        )
    }
}

/**
 * A ticket exchange / token rotation response (§9.2). `jwt` is the short-lived
 * session token; the long-lived refresh token arrives as an HttpOnly cookie.
 */
data class SessionTokens(
    val sessionId: String?,
    val jwt: String,
    val expiresIn: Int?,
) {
    companion object {
        fun from(json: JsonValue): SessionTokens = SessionTokens(
            // `id` on exchange, `session_id` on rotate.
            sessionId = json.string("id") ?: json.string("session_id"),
            jwt = json.string("jwt") ?: throw AtlasException(AtlasError.Decoding("Response has no jwt.")),
            expiresIn = json.number("expires_in")?.toInt(),
        )
    }
}

/**
 * The FAPI view of the signed-in user (`GET /v1/client/me`). `privateMetadata`
 * and `passwordHash` are absent by construction on the server (§4.1).
 */
data class AtlasUser(
    val id: String,
    val firstName: String?,
    val lastName: String?,
    val username: String?,
    val imageUrl: String?,
    val locale: String?,
    val mfaEnabled: Boolean?,
    val hasPassword: Boolean?,
    val createdAt: Long?,
    val primaryEmailId: String?,
    val emailAddresses: List<EmailAddress>,
    val externalAccounts: List<ExternalAccount>,
    /** Raw metadata objects, left as parsed JSON — shapes are customer-defined. */
    val publicMetadata: JsonValue?,
    val unsafeMetadata: JsonValue?,
) {
    companion object {
        fun from(json: JsonValue): AtlasUser = AtlasUser(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("User has no id.")),
            firstName = json.string("first_name"),
            lastName = json.string("last_name"),
            username = json.string("username"),
            imageUrl = json.string("image_url"),
            locale = json.string("locale"),
            mfaEnabled = json.bool("mfa_enabled"),
            hasPassword = json.bool("has_password"),
            createdAt = json.long("created_at"),
            primaryEmailId = json.string("primary_email_id"),
            emailAddresses = json.arr("email_addresses")?.items?.map { EmailAddress.from(it) } ?: emptyList(),
            externalAccounts = json.arr("external_accounts")?.items?.map { ExternalAccount.from(it) } ?: emptyList(),
            publicMetadata = (json as? JsonValue.Obj)?.entries?.get("public_metadata"),
            unsafeMetadata = (json as? JsonValue.Obj)?.entries?.get("unsafe_metadata"),
        )
    }
}

data class EmailAddress(
    val id: String,
    val emailAddress: String,
    val verified: Boolean,
    val primary: Boolean,
) {
    companion object {
        fun from(json: JsonValue): EmailAddress = EmailAddress(
            id = json.string("id") ?: "",
            emailAddress = json.string("email_address") ?: "",
            verified = json.bool("verified") ?: false,
            primary = json.bool("primary") ?: false,
        )
    }
}

data class ExternalAccount(
    val id: String,
    val provider: String,
    /** A provider snapshot, not authoritative for ownership (§4.2). */
    val providerEmail: String?,
    val connectedAt: Long?,
) {
    companion object {
        fun from(json: JsonValue): ExternalAccount = ExternalAccount(
            id = json.string("id") ?: "",
            provider = json.string("provider") ?: "",
            providerEmail = json.string("provider_email"),
            connectedAt = json.long("connected_at"),
        )
    }
}

/**
 * What the SDK persists after sign-in. `token` (the session JWT) goes in the
 * secure store; `refreshToken` is the HttpOnly `__atlas_rt` cookie replayed on
 * authenticated calls and rotated on refresh.
 */
data class AtlasSession(
    val sessionId: String,
    val token: String,
    val refreshToken: String?,
)
