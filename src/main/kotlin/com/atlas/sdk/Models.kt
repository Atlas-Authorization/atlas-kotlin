package com.atlas.sdk

/**
 * A sign-in attempt (§5). The SDK never advances the flow itself — it reads
 * `status` and lets the server say what comes next. Mirrors the FAPI
 * `sign_in_attempt` / `AttemptView` shape.
 */
data class SignInAttempt(
    val id: String,
    val status: String,
    /** The identifier the attempt was started with; null before one is collected. */
    val identifier: String?,
    /** The server's first-factor strategy list; never filtered client-side (§13.2). */
    val supportedFirstFactors: List<String>?,
    val authorizationUrl: String?,
    /** The session id the server stamps on a completed attempt (`created_session_id`). */
    val createdSessionId: String?,
    /** Present for exactly the step that reached `complete`; exchanged then gone. */
    val ticket: String?,
) {
    val isComplete: Boolean get() = status == "complete"

    companion object {
        fun from(json: JsonValue): SignInAttempt = SignInAttempt(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("Attempt has no id.")),
            status = json.string("status") ?: "unknown",
            identifier = json.string("identifier"),
            supportedFirstFactors = json.arr("supported_first_factors")?.items
                ?.mapNotNull { (it as? JsonValue.Str)?.value },
            authorizationUrl = json.string("authorization_url"),
            createdSessionId = json.string("created_session_id"),
            ticket = json.string("ticket"),
        )
    }
}

/**
 * A sign-up attempt (§5.1). Like [SignInAttempt] the SDK reads `status` and lets
 * the server decide the next step; a `complete` attempt carries the one-time
 * `ticket` to exchange for a session.
 */
data class SignUpAttempt(
    val id: String,
    val status: String,
    val identifier: String?,
    val createdSessionId: String?,
    val ticket: String?,
) {
    val isComplete: Boolean get() = status == "complete"

    companion object {
        fun from(json: JsonValue): SignUpAttempt = SignUpAttempt(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("Attempt has no id.")),
            status = json.string("status") ?: "unknown",
            identifier = json.string("identifier"),
            createdSessionId = json.string("created_session_id"),
            ticket = json.string("ticket"),
        )
    }
}

/**
 * The reply to `prepare_first_factor` for a code/link strategy. `pollSecret` is
 * held by THIS tab only — it is what makes the cross-device email-link flow safe
 * (§5.3), returned so a caller that wants to poll can do so.
 */
data class PreparedFirstFactor(
    val attemptId: String,
    val status: String,
    val strategy: String?,
    val pollSecret: String?,
) {
    companion object {
        fun from(json: JsonValue): PreparedFirstFactor = PreparedFirstFactor(
            attemptId = json.string("id") ?: "",
            status = json.string("status") ?: "unknown",
            strategy = json.string("strategy"),
            pollSecret = json.string("poll_secret"),
        )
    }
}

/**
 * The reply to `prepare_second_factor`. The fields present depend on the
 * strategy: `sms` returns `sentTo` (a masked number); `push` returns
 * `challengeId` + `numberMatch` (shown on this screen, tapped on the device);
 * a passkey/security-key returns the raw WebAuthn request options, left as parsed
 * JSON for a native layer to turn into a platform assertion.
 */
data class SecondFactorChallenge(
    val strategy: String?,
    val sentTo: String?,
    val challengeId: String?,
    val numberMatch: Int?,
    val expiresAt: Long?,
    /** The raw challenge object (passkey request options etc.), as parsed JSON. */
    val raw: JsonValue,
) {
    companion object {
        fun from(json: JsonValue): SecondFactorChallenge = SecondFactorChallenge(
            strategy = json.string("strategy"),
            sentTo = json.string("sent_to"),
            challengeId = json.string("challenge_id"),
            numberMatch = json.number("number_match")?.toInt(),
            expiresAt = json.long("expires_at"),
            raw = json,
        )
    }
}

/**
 * The reply to `prepare_mfa_enrollment` — a TOTP secret returned exactly once.
 * `uri` is the `otpauth://` provisioning URI a caller renders as a QR code.
 */
data class MfaEnrollment(
    val factorId: String,
    val secret: String,
    val uri: String,
) {
    companion object {
        fun from(json: JsonValue): MfaEnrollment = MfaEnrollment(
            factorId = json.string("factor_id") ?: throw AtlasException(AtlasError.Decoding("No factor_id.")),
            secret = json.string("secret") ?: throw AtlasException(AtlasError.Decoding("No secret.")),
            uri = json.string("uri") ?: "",
        )
    }
}

/** An organization (§8/§9.2). */
data class Organization(
    val id: String,
    val name: String,
    val slug: String?,
    val imageUrl: String?,
    val maxAllowedMemberships: Int?,
    val createdAt: Long?,
    val publicMetadata: JsonValue?,
) {
    companion object {
        fun from(json: JsonValue): Organization = Organization(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("Organization has no id.")),
            name = json.string("name") ?: "",
            slug = json.string("slug"),
            imageUrl = json.string("image_url"),
            maxAllowedMemberships = json.number("max_allowed_memberships")?.toInt(),
            createdAt = json.long("created_at"),
            publicMetadata = (json as? JsonValue.Obj)?.entries?.get("public_metadata"),
        )
    }
}

/** A membership of the signed-in user in an [Organization] with their role. */
data class OrganizationMembership(
    val role: String?,
    val organization: Organization,
) {
    companion object {
        fun from(json: JsonValue): OrganizationMembership = OrganizationMembership(
            role = json.string("role"),
            organization = Organization.from(
                json.obj("organization")
                    ?: throw AtlasException(AtlasError.Decoding("Membership has no organization.")),
            ),
        )
    }
}

/**
 * One of the signed-in user's active sessions/devices (`GET /v1/client/sessions`).
 * `current` marks the device the request itself arrived on.
 */
data class SessionDevice(
    val id: String,
    val status: String,
    val current: Boolean,
    val lastActiveAt: Long?,
    val expireAt: Long?,
    val ipAddress: String?,
    val deviceLabel: String?,
    val browser: String?,
    val os: String?,
    val location: String?,
) {
    companion object {
        fun from(json: JsonValue): SessionDevice = SessionDevice(
            id = json.string("id") ?: "",
            status = json.string("status") ?: "unknown",
            current = json.bool("current") ?: false,
            lastActiveAt = json.long("last_active_at"),
            expireAt = json.long("expire_at"),
            ipAddress = json.string("ip_address"),
            deviceLabel = json.string("device_label"),
            browser = json.string("browser"),
            os = json.string("os"),
            location = json.string("location"),
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
