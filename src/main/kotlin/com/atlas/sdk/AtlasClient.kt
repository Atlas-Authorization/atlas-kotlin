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
            mapOf("attempt_id" to attemptId, "ticket" to ticket),
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

    // ---- HTTP core -----------------------------------------------------------

    private suspend fun postJson(path: String, body: Map<String, String>): JsonValue {
        val response = send("POST", path, body, cookie = null)
        throwIfError(response)
        return JsonValue.parse(response.body)
    }

    /**
     * The single place a request is built and sent, so the auth header, base URL,
     * and JSON content type are set in exactly one place.
     */
    private suspend fun send(
        method: String,
        path: String,
        body: Map<String, String>?,
        cookie: String?,
    ): HttpResponse = withContext(dispatcher) {
        val headers = LinkedHashMap<String, String>()
        headers["x-publishable-key"] = publishableKey
        if (cookie != null) headers["Cookie"] = cookie
        val encoded = body?.let {
            headers["content-type"] = "application/json"
            encodeJsonObject(it)
        }
        transport.execute(HttpRequest(method, baseUrl + path, headers, encoded))
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
