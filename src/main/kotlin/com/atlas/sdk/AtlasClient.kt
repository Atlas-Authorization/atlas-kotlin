package com.atlas.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Atlas FAPI client — the client-facing auth core for an Android app.
 *
 * It mirrors the vanilla JS (`@atlas/js`) FAPI contract exactly: every request
 * carries `x-publishable-key`, hits the `frontendApi` origin, and speaks the §5
 * attempt / §9.1 error / §9.2 session shapes. The session JWT is persisted
 * through a [TokenStore] (EncryptedSharedPreferences in production); the HttpOnly
 * `__atlas_rt` refresh cookie is captured and replayed so `me` and refresh work
 * without the app ever handling it.
 *
 * Thin by design — no multi-step MFA driver, no Custom Tabs launch, no passkeys.
 * See the README's scope note. What it does, it does to the server contract.
 *
 * @param publishableKey the `pk_...` key, sent as `x-publishable-key`.
 * @param frontendApi the FAPI host (`clerk.example.com`) or a full origin; a bare
 *   host is upgraded to `https://`.
 * @param tokenStore where the session is persisted.
 * @param transport the HTTP seam (injectable for tests).
 * @param dispatcher the background dispatcher the blocking transport runs on.
 */
class AtlasClient(
    val publishableKey: String,
    frontendApi: String,
    val tokenStore: TokenStore,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val baseUrl: String = resolveBaseUrl(frontendApi)

    private object Cookie {
        const val SESSION = "__session"
        const val REFRESH = "__atlas_rt"
    }

    // ---- auth flows ----------------------------------------------------------

    /**
     * Password sign-in, end to end (§5 → §7.1 → §9.2):
     * 1. `POST /v1/client/sign_ins` to create the attempt,
     * 2. `POST …/attempt_first_factor` with `strategy=password`,
     * 3. `POST /v1/client/tickets/exchange` to turn the ticket into a session,
     *    persisting the JWT + refresh cookie.
     *
     * Returns the freshly signed-in user. Throws [AtlasException] on any bad
     * step — a wrong password surfaces as `Api` with `form_password_incorrect`.
     */
    suspend fun signIn(email: String, password: String): AtlasUser {
        val attempt = SignInAttempt.from(
            postJson("/v1/client/sign_ins", mapOf("identifier" to email))
        )

        val completed = SignInAttempt.from(
            postJson(
                "/v1/client/sign_ins/${attempt.id}/attempt_first_factor",
                mapOf("strategy" to "password", "password" to password),
            )
        )

        if (!completed.isComplete || completed.ticket == null) {
            throw AtlasException(
                AtlasError.Api(200, listOf(AtlasErrorItem(
                    "sign_in_not_complete",
                    "Sign-in needs an additional step: ${completed.status}.",
                )))
            )
        }

        exchangeTicket(completed.id, completed.ticket)
        return currentUser()
    }

    /**
     * Exchange a one-time ticket for a session (`POST /v1/client/tickets/exchange`).
     * Also the completion of an OAuth redirect: read `__atlas_attempt` +
     * `__atlas_ticket` off the Custom Tabs callback and pass them here.
     */
    suspend fun exchangeTicket(attemptId: String, ticket: String) {
        val response = send(
            "POST",
            "/v1/client/tickets/exchange",
            encodeJsonObject(mapOf("attempt_id" to attemptId, "ticket" to ticket)),
            cookie = null,
        )
        throwIfError(response)

        val tokens = SessionTokens.from(JsonValue.parse(response.body))
        val refresh = extractCookie(Cookie.REFRESH, response)
        tokenStore.save(
            AtlasSession(
                sessionId = tokens.sessionId ?: attemptId,
                token = tokens.jwt,
                refreshToken = refresh,
            )
        )
    }

    /**
     * Build the provider authorize URL for an OAuth sign-in
     * (`POST /v1/client/sign_ins/oauth`). Launch it in a Custom Tab; on the
     * callback, pull the redirect params and call [exchangeTicket].
     */
    suspend fun oauthAuthorizeUrl(provider: String, redirectUri: String): String {
        val attempt = SignInAttempt.from(
            postJson(
                "/v1/client/sign_ins/oauth",
                mapOf("provider" to provider, "redirect_url" to redirectUri),
            )
        )
        return attempt.authorizationUrl
            ?: throw AtlasException(AtlasError.Decoding("The server returned no authorization_url."))
    }

    /**
     * The signed-in user (`GET /v1/client/me`). Presents the stored refresh
     * cookie; throws [AtlasError.NotSignedIn] when there is no session.
     */
    suspend fun currentUser(): AtlasUser {
        val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
        val response = send("GET", "/v1/client/me", body = null, cookie = cookieHeader(stored))
        throwIfError(response)
        return AtlasUser.from(JsonValue.parse(response.body))
    }

    /**
     * Rotate the refresh token and mint a fresh JWT
     * (`POST /v1/client/sessions/:id/tokens`), updating the stored session.
     */
    suspend fun refresh(): AtlasSession {
        val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
        val response = send(
            "POST",
            "/v1/client/sessions/${stored.sessionId}/tokens",
            body = null,
            cookie = cookieHeader(stored),
        )
        throwIfError(response)

        val tokens = SessionTokens.from(JsonValue.parse(response.body))
        val rotated = extractCookie(Cookie.REFRESH, response) ?: stored.refreshToken
        val updated = AtlasSession(
            sessionId = tokens.sessionId ?: stored.sessionId,
            token = tokens.jwt,
            refreshToken = rotated,
        )
        tokenStore.save(updated)
        return updated
    }

    /**
     * Sign out: revoke server-side (`POST /v1/client/sessions/:id/revoke`) and
     * clear local storage. Local state is cleared even if the network call fails
     * — a token kept after "sign out" is the worse failure.
     */
    suspend fun signOut() {
        val stored = tokenStore.load()
        try {
            if (stored != null) {
                runCatching {
                    send("POST", "/v1/client/sessions/${stored.sessionId}/revoke", body = null, cookie = cookieHeader(stored))
                }
            }
        } finally {
            tokenStore.clear()
        }
    }

    /** Whether a session is persisted — a cheap offline check, not a server validation. */
    fun hasSession(): Boolean = tokenStore.load() != null

    // ---- multi-step flow driver ----------------------------------------------

    /**
     * Begin a multi-step sign-in (§5). Creates the attempt and returns a
     * [SignInFlow] whose [SignInFlow.step] says what the server demands next —
     * password, an email/phone code, a second factor, MFA enrollment. Drive it to
     * [SignInStep.Done] then call [SignInFlow.complete] to persist the session.
     *
     * This is the general counterpart to the single-shot [signIn] happy path,
     * which still works unchanged.
     */
    suspend fun beginSignIn(identifier: String, captchaToken: String? = null): SignInFlow {
        val attempt = SignInAttempt.from(
            clientRequest(
                "POST",
                "/v1/client/sign_ins",
                jsonBody("identifier" to jstr(identifier), "captcha_token" to jstr(captchaToken)),
            ),
        )
        return SignInFlow(this, attempt)
    }

    /**
     * Begin a multi-step sign-up (§5.1). Creates the attempt with an email +
     * password and returns a [SignUpFlow]; verify the email with a code, then
     * [SignUpFlow.complete] to persist the session.
     */
    suspend fun beginSignUp(
        email: String,
        password: String,
        fields: Map<String, String>? = null,
        captchaToken: String? = null,
        consent: Boolean? = null,
        organizationId: String? = null,
    ): SignUpFlow {
        val fieldsJson = fields?.let { JsonValue.Obj(it.mapValues { (_, v) -> JsonValue.Str(v) }) }
        val attempt = SignUpAttempt.from(
            clientRequest(
                "POST",
                "/v1/client/sign_ups",
                jsonBody(
                    "email" to jstr(email),
                    "password" to jstr(password),
                    "captcha_token" to jstr(captchaToken),
                    "consent" to jbool(consent),
                    "organization_id" to jstr(organizationId),
                    "fields" to fieldsJson,
                ),
            ),
        )
        return SignUpFlow(this, attempt)
    }

    /** Begin a password reset (§5.4). See [PasswordResetFlow]. */
    suspend fun beginPasswordReset(email: String, captchaToken: String? = null): PasswordResetFlow {
        val attempt = SignInAttempt.from(
            clientRequest(
                "POST",
                "/v1/client/password_resets",
                jsonBody("email_address" to jstr(email), "captcha_token" to jstr(captchaToken)),
            ),
        )
        return PasswordResetFlow(this, attempt)
    }

    // ---- native / One-Tap id_token -------------------------------------------

    /**
     * Mint a single-use, replay-binding nonce for a native id_token sign-in
     * (`POST /v1/client/sign_ins/id_token/nonce`). The native layer (the Android
     * SDK / platform) hands this nonce to the provider SDK (e.g. Google GSI
     * `initialize({ nonce })`) so the returned id_token is bound to it.
     */
    suspend fun mintNativeNonce(provider: String): String {
        val json = clientRequest(
            "POST",
            "/v1/client/sign_ins/id_token/nonce",
            jsonBody("provider" to jstr(provider)),
        )
        return json.string("nonce")
            ?: throw AtlasException(AtlasError.Decoding("The server returned no nonce."))
    }

    /**
     * Exchange a provider **id_token string** for a session
     * (`POST /v1/client/sign_ins/id_token`).
     *
     * This library performs **no native token ceremony** — the app obtains the
     * id_token however it likes (the Android SDK `net.atlasauth:atlas-android`
     * via Credential Manager / Google One-Tap, Apple, Facebook Limited Login, …)
     * and passes the resulting string here. An optional [nonce] from
     * [mintNativeNonce] replay-binds it.
     *
     * Returns a [SignInFlow] positioned at the resulting attempt: when the token
     * alone signs the user in, [SignInFlow.step] is [SignInStep.Done] and
     * [SignInFlow.complete] persists the session; when the instance demands a
     * second factor, the flow is at [SignInStep.CollectSecondFactor] and the
     * usual `prepare`/`attempt`-second-factor methods finish it.
     */
    suspend fun signInWithIdToken(provider: String, idToken: String, nonce: String? = null): SignInFlow {
        val attempt = SignInAttempt.from(
            clientRequest(
                "POST",
                "/v1/client/sign_ins/id_token",
                jsonBody(
                    "provider" to jstr(provider),
                    "id_token" to jstr(idToken),
                    "nonce" to jstr(nonce),
                ),
            ),
        )
        return SignInFlow(this, attempt)
    }

    // ---- organizations --------------------------------------------------------

    /**
     * The organizations the signed-in user belongs to with their role
     * (`GET /v1/client/me/organizations`).
     */
    suspend fun organizationMemberships(): List<OrganizationMembership> {
        val json = authedRequest("GET", "/v1/client/me/organizations", null)
        return json.arr("data")?.items?.map { OrganizationMembership.from(it) } ?: emptyList()
    }

    /**
     * Create an organization (`POST /v1/client/organizations`). Only succeeds when
     * the instance allows user-created organizations; otherwise the server
     * answers 403 and this throws an [AtlasError.Api] with `forbidden`.
     */
    suspend fun createOrganization(name: String, slug: String): Organization {
        val json = authedRequest(
            "POST",
            "/v1/client/organizations",
            jsonBody("name" to jstr(name), "slug" to jstr(slug)),
        )
        return Organization.from(json)
    }

    /**
     * Read one organization (`GET /v1/client/organizations/:id`). Resolves against
     * the session's ACTIVE organization — reading one the session is not acting
     * inside answers 404 (see the server's active-org model).
     */
    suspend fun organization(id: String): Organization =
        Organization.from(authedRequest("GET", "/v1/client/organizations/$id", null))

    // ---- sessions / devices ---------------------------------------------------

    /**
     * The signed-in user's own active sessions/devices
     * (`GET /v1/client/sessions`). The list is scoped to the session the stored
     * refresh cookie resolves to — never to a user id in the request.
     */
    suspend fun sessions(): List<SessionDevice> {
        val json = authedRequest("GET", "/v1/client/sessions", null)
        return json.arr("data")?.items?.map { SessionDevice.from(it) } ?: emptyList()
    }

    /** Sign out one device by session id (`POST /v1/client/sessions/:id/revoke`). */
    suspend fun revokeSession(sessionId: String) {
        authedRequest("POST", "/v1/client/sessions/$sessionId/revoke", null)
    }

    /**
     * Sign out of every OTHER device (`POST /v1/client/sessions/revoke_all`); the
     * current session is spared. Returns the number of sessions revoked.
     */
    suspend fun revokeOtherSessions(): Int {
        val json = authedRequest("POST", "/v1/client/sessions/revoke_all", null)
        return json.number("sessions_revoked")?.toInt() ?: 0
    }

    // ---- /me mutations --------------------------------------------------------

    /**
     * Patch the signed-in user's profile (`PATCH /v1/client/me`). Only the fields
     * passed are sent. `unsafeMetadata` is the only metadata the frontend may
     * write (§4.1) — `public_metadata`/`private_metadata` are backend-only and the
     * server refuses them.
     */
    suspend fun updateProfile(
        firstName: String? = null,
        lastName: String? = null,
        username: String? = null,
        locale: String? = null,
        unsafeMetadata: JsonValue? = null,
    ): AtlasUser {
        val json = authedRequest(
            "PATCH",
            "/v1/client/me",
            jsonBody(
                "first_name" to jstr(firstName),
                "last_name" to jstr(lastName),
                "username" to jstr(username),
                "locale" to jstr(locale),
                "unsafe_metadata" to unsafeMetadata,
            ),
        )
        return AtlasUser.from(json)
    }

    /** Add an email address (`POST /v1/client/me/email_addresses`); it starts unverified. */
    suspend fun addEmailAddress(email: String): EmailAddress {
        val json = authedRequest(
            "POST",
            "/v1/client/me/email_addresses",
            jsonBody("email_address" to jstr(email)),
        )
        return EmailAddress.from(json)
    }

    /** Verify an added address with its emailed code (`…/email_addresses/:id/attempt_verification`). */
    suspend fun verifyEmailAddress(emailId: String, code: String): EmailAddress {
        val json = authedRequest(
            "POST",
            "/v1/client/me/email_addresses/$emailId/attempt_verification",
            jsonBody("code" to jstr(code)),
        )
        return EmailAddress.from(json)
    }

    /** Make a VERIFIED address the primary one (`…/email_addresses/:id/primary`). */
    suspend fun setPrimaryEmail(emailId: String) {
        authedRequest("POST", "/v1/client/me/email_addresses/$emailId/primary", null)
    }

    /** Remove an email address (`DELETE /v1/client/me/email_addresses/:id`). */
    suspend fun deleteEmailAddress(emailId: String) {
        authedRequest("DELETE", "/v1/client/me/email_addresses/$emailId", null)
    }

    /**
     * Start an OAuth flow to connect a NEW provider to the signed-in user
     * (`POST /v1/client/me/external_accounts/connect`). Returns the provider
     * `authorization_url` to open in a browser/Custom Tab; the callback returns to
     * [redirectUrl] with `__atlas_status=connected`.
     */
    suspend fun connectExternalAccount(
        provider: String,
        redirectUrl: String,
        additionalScopes: List<String>? = null,
    ): String {
        val json = authedRequest(
            "POST",
            "/v1/client/me/external_accounts/connect",
            jsonBody(
                "provider" to jstr(provider),
                "redirect_url" to jstr(redirectUrl),
                "additional_scopes" to jarr(additionalScopes),
            ),
        )
        return json.string("authorization_url")
            ?: throw AtlasException(AtlasError.Decoding("The server returned no authorization_url."))
    }

    /** Unlink a connected provider (`DELETE /v1/client/me/external_accounts/:id`). */
    suspend fun deleteExternalAccount(externalAccountId: String) {
        authedRequest("DELETE", "/v1/client/me/external_accounts/$externalAccountId", null)
    }

    /**
     * Change the password of a signed-in account (`POST /v1/client/me/change_password`),
     * proving the current one. Returns the number of other sessions revoked.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Int {
        val json = authedRequest(
            "POST",
            "/v1/client/me/change_password",
            jsonBody(
                "current_password" to jstr(currentPassword),
                "new_password" to jstr(newPassword),
            ),
        )
        return json.number("sessions_revoked")?.toInt() ?: 0
    }

    /**
     * Set a FIRST password on an account that has none — an OAuth-only or guest
     * account (`POST /v1/client/me/set_password`). Use [changePassword] when the
     * account already has one.
     */
    suspend fun setPassword(password: String) {
        authedRequest("POST", "/v1/client/me/set_password", jsonBody("password" to jstr(password)))
    }

    // ---- HTTP core -----------------------------------------------------------

    private suspend fun postJson(path: String, body: Map<String, String>): JsonValue {
        val response = send("POST", path, encodeJsonObject(body), cookie = null)
        throwIfError(response)
        return JsonValue.parse(response.body)
    }

    /**
     * A publishable-key-only request (no session) used by the pre-session flow
     * driver: builds the attempt body with [jsonBody], sends it, throws on a
     * non-2xx, and parses the JSON body. `bodyJson` is null for a bodyless POST.
     */
    internal suspend fun clientRequest(method: String, path: String, bodyJson: String?): JsonValue {
        val response = send(method, path, bodyJson, cookie = null)
        throwIfError(response)
        return JsonValue.parse(response.body)
    }

    /**
     * An authenticated `/v1/client/me|organizations|sessions` request: presents
     * the stored session cookie (the same replay [currentUser] uses), throws
     * [AtlasError.NotSignedIn] when there is no session, throws on a non-2xx, and
     * parses the JSON body. A bodyless response (an empty 200) parses as an empty
     * object so a caller never crashes on "" .
     */
    internal suspend fun authedRequest(method: String, path: String, bodyJson: String?): JsonValue {
        val stored = tokenStore.load() ?: throw AtlasException(AtlasError.NotSignedIn)
        val response = send(method, path, bodyJson, cookie = cookieHeader(stored))
        throwIfError(response)
        return if (response.body.isBlank()) JsonValue.Obj(emptyMap()) else JsonValue.parse(response.body)
    }

    /**
     * The single place a request is built and sent, so the auth header, base URL,
     * and JSON content type are set in exactly one place. `body` is a pre-encoded
     * JSON string (or null for a bodyless request).
     */
    private suspend fun send(
        method: String,
        path: String,
        body: String?,
        cookie: String?,
    ): HttpResponse = withContext(dispatcher) {
        val headers = LinkedHashMap<String, String>()
        headers["x-publishable-key"] = publishableKey
        if (cookie != null) headers["Cookie"] = cookie
        if (body != null) headers["content-type"] = "application/json"
        transport.execute(HttpRequest(method, baseUrl + path, headers, body))
    }

    private fun throwIfError(response: HttpResponse) {
        if (response.status !in 200..299) {
            throw AtlasException(AtlasError.fromResponse(response.status, response.body))
        }
    }

    /** The `Cookie` header from the stored session — session JWT + refresh token. */
    private fun cookieHeader(stored: AtlasSession): String {
        val parts = mutableListOf("${Cookie.SESSION}=${stored.token}")
        stored.refreshToken?.let { parts.add("${Cookie.REFRESH}=$it") }
        return parts.joinToString("; ")
    }

    /** Pull one cookie value out of the response `Set-Cookie` header(s). */
    private fun extractCookie(name: String, response: HttpResponse): String? {
        for (header in response.setCookies()) {
            val first = header.substringBefore(';').trim()
            val eq = first.indexOf('=')
            if (eq > 0 && first.substring(0, eq) == name) {
                return first.substring(eq + 1)
            }
        }
        return null
    }

    companion object {
        internal fun resolveBaseUrl(frontendApi: String): String {
            val trimmed = frontendApi.trim()
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}
