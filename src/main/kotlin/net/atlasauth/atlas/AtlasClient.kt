package net.atlasauth.atlas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The Atlas FAPI client — the client-facing auth core for a native Android app.
 *
 * It is the Kotlin peer of the Swift `AtlasClient` and mirrors the vanilla JS
 * (`@atlas/js`) FAPI contract exactly: every request carries the
 * `x-publishable-key` header, hits the `frontendApi` origin, and speaks the §5
 * attempt / §9.1 error / §9.2 session shapes. The session token (JWT) is
 * persisted through a [TokenStore] (an encrypted store in production); the
 * HttpOnly `__atlas_rt` refresh cookie is captured and re-presented so the SDK
 * can call `me` and rotate the token without the app ever handling it.
 *
 * The client is intentionally thin. It does not drive multi-step MFA UI, own a
 * cookie jar, or bundle passkeys — see the README's scope note.
 *
 * All network methods are `suspend` functions; call them from a coroutine.
 *
 * @param publishableKey the instance's `pk_...` key, sent as `x-publishable-key`.
 * @param frontendApi the FAPI host (`clerk.example.com`) or a full origin; a bare
 *   host is upgraded to `https://`.
 * @param tokenStore where the session is persisted.
 * @param httpClient injectable for tests; defaults to a cookie-less OkHttp client.
 */
class AtlasClient(
    val publishableKey: String,
    frontendApi: String,
    val tokenStore: TokenStore,
    httpClient: OkHttpClient? = null,
) {
    /** Cookie names the server sets (mirrors the API's `COOKIE_NAMES`). */
    private object Cookie {
        const val SESSION = "__session"
        const val REFRESH = "__atlas_rt"
    }

    /** The resolved base URL, e.g. `https://clerk.example.com/`. */
    val baseUrl: HttpUrl = resolveBaseUrl(frontendApi)

    // The SDK captures and replays the refresh cookie explicitly, so it does not
    // want OkHttp quietly maintaining a second copy. OkHttp defaults to
    // CookieJar.NO_COOKIES, so an injected/default client is already cookie-less.
    private val http: OkHttpClient = httpClient ?: OkHttpClient()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json".toMediaType()
    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

    // MARK: - Auth flows

    /**
     * Password sign-in, end to end (§5 → §7.1 → §9.2):
     * 1. `POST /v1/client/sign_ins` to create the attempt,
     * 2. `POST …/attempt_first_factor` with `strategy: password`,
     * 3. `POST /v1/client/tickets/exchange` to turn the completion ticket into a
     *    session, persisting the JWT + refresh cookie.
     *
     * Returns the freshly signed-in user. Throws [AtlasException] on any bad step
     * — a wrong password surfaces as [AtlasException.Api] with
     * `form_password_incorrect`.
     */
    suspend fun signIn(email: String, password: String): AtlasUser {
        val attempt: SignInAttempt = postJson(
            "/v1/client/sign_ins",
            mapOf("identifier" to email),
            SignInAttempt.serializer(),
        )

        val completed: SignInAttempt = postJson(
            "/v1/client/sign_ins/${attempt.id}/attempt_first_factor",
            mapOf("strategy" to "password", "password" to password),
            SignInAttempt.serializer(),
        )

        val ticket = completed.ticket
        if (!completed.isComplete || ticket == null) {
            // A non-complete status (e.g. needs_second_factor) is a real flow the
            // foundation does not yet drive. Surfacing the status is honest.
            throw AtlasException.Api(
                statusCode = 200,
                errors = listOf(
                    AtlasErrorItem(
                        code = "sign_in_not_complete",
                        message = "Sign-in needs an additional step: ${completed.status}.",
                    ),
                ),
            )
        }

        exchangeTicket(attemptId = completed.id, ticket = ticket)
        return currentUser()
    }

    /**
     * Exchange a one-time ticket for a session (`POST /v1/client/tickets/exchange`).
     * Also the completion of an OAuth redirect: read `__atlas_attempt` +
     * `__atlas_ticket` off the callback URL and pass them here.
     */
    suspend fun exchangeTicket(attemptId: String, ticket: String) {
        val response = send(
            method = "POST",
            path = "/v1/client/tickets/exchange",
            body = mapOf("attempt_id" to attemptId, "ticket" to ticket),
            cookie = null,
        )
        throwIfError(response)

        val tokens = decode(response.body, SessionTokens.serializer())
        val refresh = extractCookie(Cookie.REFRESH, response.setCookies)
        val sessionId = tokens.resolvedSessionId ?: attemptId
        tokenStore.save(AtlasSession(sessionId = sessionId, token = tokens.jwt, refreshToken = refresh))
    }

    /**
     * Finish a passkey sign-in and persist the session. Unlike password sign-in,
     * a passkey does NOT use the ticket exchange — a verified passkey is two
     * factors in one gesture, so `POST /v1/client/sign_ins/passkey/finish` mints
     * the session itself and returns the completed attempt (`jwt` +
     * `created_session_id`) with the refresh token as a Set-Cookie. Used by
     * [PasskeyManager.signInWithPasskey] after it drives the platform ceremony.
     */
    internal suspend fun completePasskeySignIn(body: Map<String, String>): AtlasUser {
        val response = send(
            method = "POST",
            path = "/v1/client/sign_ins/passkey/finish",
            body = body,
            cookie = null,
        )
        throwIfError(response)

        val attempt = decode(response.body, SignInAttempt.serializer())
        if (!attempt.isComplete) {
            throw AtlasException.Api(
                statusCode = 200,
                errors = listOf(
                    AtlasErrorItem(
                        code = "sign_in_not_complete",
                        message = "Passkey sign-in needs an additional step: ${attempt.status}.",
                    ),
                ),
            )
        }
        val tokens = decode(response.body, SessionTokens.serializer())
        val refresh = extractCookie(Cookie.REFRESH, response.setCookies)
        val sessionId = attempt.createdSessionId ?: tokens.resolvedSessionId ?: ""
        tokenStore.save(AtlasSession(sessionId = sessionId, token = tokens.jwt, refreshToken = refresh))
        return currentUser()
    }

    /**
     * Build the provider authorize URL for an OAuth sign-in
     * (`POST /v1/client/sign_ins/oauth`). Hand the returned URL to a Custom Tab /
     * browser; on the callback, pull the redirect params and call
     * [exchangeTicket].
     *
     * @param provider the provider key (`google`, `github`, …).
     * @param redirectUri your app's callback URL / custom scheme.
     */
    suspend fun oauthAuthorizeUrl(provider: String, redirectUri: String): String {
        val attempt: SignInAttempt = postJson(
            "/v1/client/sign_ins/oauth",
            mapOf("provider" to provider, "redirect_url" to redirectUri),
            SignInAttempt.serializer(),
        )
        return attempt.authorizationUrl
            ?: throw AtlasException.Decoding("The server returned no authorization_url.")
    }

    /**
     * The signed-in user (`GET /v1/client/me`). Presents the stored refresh
     * cookie for authentication; throws [AtlasException.NotSignedIn] when there
     * is no session.
     */
    suspend fun currentUser(): AtlasUser {
        val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
        val response = send(
            method = "GET",
            path = "/v1/client/me",
            body = null,
            cookie = cookieHeader(stored),
        )
        throwIfError(response)
        return decode(response.body, AtlasUser.serializer())
    }

    /**
     * Rotate the refresh token and mint a fresh JWT
     * (`POST /v1/client/sessions/:id/tokens`). Updates the stored session with
     * the new token and rotated cookie.
     */
    suspend fun refresh(): AtlasSession {
        val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
        val response = send(
            method = "POST",
            path = "/v1/client/sessions/${stored.sessionId}/tokens",
            body = null,
            cookie = cookieHeader(stored),
        )
        throwIfError(response)

        val tokens = decode(response.body, SessionTokens.serializer())
        val rotated = extractCookie(Cookie.REFRESH, response.setCookies) ?: stored.refreshToken
        val updated = AtlasSession(
            sessionId = tokens.resolvedSessionId ?: stored.sessionId,
            token = tokens.jwt,
            refreshToken = rotated,
        )
        tokenStore.save(updated)
        return updated
    }

    /**
     * Sign out: revoke the session server-side
     * (`POST /v1/client/sessions/:id/revoke`) and clear local storage. Local
     * state is cleared even if the network call fails — a client that keeps a
     * token after the user tapped "sign out" is the worse failure.
     */
    suspend fun signOut() {
        val stored = tokenStore.load()
        try {
            if (stored != null) {
                try {
                    send(
                        method = "POST",
                        path = "/v1/client/sessions/${stored.sessionId}/revoke",
                        body = null,
                        cookie = cookieHeader(stored),
                    )
                } catch (_: AtlasException) {
                    // Best-effort revoke; local clear below is what matters.
                }
            }
        } finally {
            tokenStore.clear()
        }
    }

    /**
     * Whether a session is currently persisted. A cheap, offline check — it does
     * not validate the token against the server.
     */
    fun hasSession(): Boolean = runCatching { tokenStore.load() }.getOrNull() != null

    // MARK: - HTTP core

    private data class RawResponse(
        val status: Int,
        val body: String,
        val setCookies: List<String>,
    )

    private suspend fun <T> postJson(
        path: String,
        body: Map<String, String>,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val response = send("POST", path, body, cookie = null)
        throwIfError(response)
        return decode(response.body, deserializer)
    }

    /**
     * POST a string-valued JSON body and return the raw response body, letting the
     * caller parse it. Used by the passkey ceremony (see [PasskeyManager]), whose
     * `begin` responses are opaque WebAuthn options passed straight to the platform
     * authenticator rather than decoded into a model.
     *
     * [authenticated] presents the stored session the way [currentUser] does — the
     * `/me/passkeys/…` register routes require a signed-in user; the
     * `/sign_ins/passkey/…` routes send only the publishable key.
     *
     * @throws AtlasException.NotSignedIn when [authenticated] is set but no session
     *   is stored.
     */
    internal suspend fun postRaw(
        path: String,
        body: Map<String, String>,
        authenticated: Boolean,
    ): String {
        val cookie = if (authenticated) {
            val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
            cookieHeader(stored)
        } else {
            null
        }
        val response = send("POST", path, body, cookie)
        throwIfError(response)
        return response.body
    }

    /**
     * The single place a request is built and sent. Every call flows through here
     * so the auth header, base URL, and JSON content type are set in exactly one
     * place — closing off the class of bug where one endpoint forgets the key.
     */
    private suspend fun send(
        method: String,
        path: String,
        body: Map<String, String>?,
        cookie: String?,
    ): RawResponse = withContext(Dispatchers.IO) {
        val url = baseUrl.resolve(path)
            ?: throw AtlasException.Transport("Could not build a request URL for $path.")

        val builder = Request.Builder()
            .url(url)
            .header("x-publishable-key", publishableKey)
        if (cookie != null) {
            builder.header("Cookie", cookie)
        }

        val requestBody: RequestBody? = when {
            body != null -> {
                builder.header("content-type", "application/json")
                json.encodeToString(stringMapSerializer, body).toRequestBody(jsonMediaType)
            }
            method != "GET" && method != "HEAD" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(method, requestBody)

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw AtlasException.Transport("We could not reach the server: ${e.message}")
        }

        response.use {
            RawResponse(
                status = it.code,
                body = it.body?.string().orEmpty(),
                setCookies = it.headers("Set-Cookie"),
            )
        }
    }

    private fun throwIfError(response: RawResponse) {
        if (response.status !in 200..299) {
            throw parseErrorEnvelope(json, response.status, response.body)
        }
    }

    private fun <T> decode(body: String, deserializer: DeserializationStrategy<T>): T {
        return try {
            json.decodeFromString(deserializer, body)
        } catch (e: Throwable) {
            throw AtlasException.Decoding("Could not decode response: ${e.message}")
        }
    }

    /**
     * Build the `Cookie` header from the stored session — both the session JWT
     * and the refresh token, exactly as a browser would present them.
     */
    private fun cookieHeader(stored: AtlasSession): String {
        val parts = mutableListOf("${Cookie.SESSION}=${stored.token}")
        stored.refreshToken?.let { parts.add("${Cookie.REFRESH}=$it") }
        return parts.joinToString("; ")
    }

    /** Pull one cookie value out of a response's `Set-Cookie` header(s). */
    private fun extractCookie(name: String, setCookies: List<String>): String? {
        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == name) {
                return pair.substring(eq + 1)
            }
        }
        return null
    }

    companion object {
        /**
         * Convenience factory that wires the production
         * [EncryptedSharedPreferencesTokenStore], namespaced by the publishable
         * key. This is the entry point most apps use.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            publishableKey: String,
            frontendApi: String,
            httpClient: OkHttpClient? = null,
        ): AtlasClient = AtlasClient(
            publishableKey = publishableKey,
            frontendApi = frontendApi,
            tokenStore = EncryptedSharedPreferencesTokenStore(context, publishableKey),
            httpClient = httpClient,
        )

        internal fun resolveBaseUrl(frontendApi: String): HttpUrl {
            val trimmed = frontendApi.trim()
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            // A trailing slash would double up against the leading slash in each path.
            val normalized = if (withScheme.endsWith("/")) withScheme.dropLast(1) else withScheme
            return normalized.toHttpUrl()
        }
    }
}
