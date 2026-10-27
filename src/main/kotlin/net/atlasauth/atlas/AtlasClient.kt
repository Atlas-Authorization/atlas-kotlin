package net.atlasauth.atlas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * The direct happy-path methods below ([signIn], [currentUser], [refresh],
 * [signOut]) cover the common case; the multi-step [signInFlow] / [signUpFlow] /
 * [passwordResetFlow] drivers handle email/phone codes, second factors and MFA
 * enrollment, and the organizations / sessions / `/me` surfaces live alongside.
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

    @OptIn(ExperimentalSerializationApi::class)
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Supplies the stable device id sent on account-creating starts (see
     * [DeviceId]). Defaults to an id stable for the process; set
     * `DeviceId.persistent(context)` to keep it across launches, or return null to
     * send none.
     */
    var deviceIdProvider: () -> String? = { DeviceId.forProcess() }

    /**
     * App and device attestation (Play Integrity). When set, sign-up, sign-in and
     * SMS-send requests fetch a single-use nonce, call this with it (pass it as
     * the Integrity request `nonce`), and send the proof as `Atlas-Attestation`.
     * Needed once the instance turns on `attackProtection.appAttestation`.
     */
    var attestationProvider: (suspend (nonce: String) -> AtlasAttestationProof)? = null

    /**
     * Opens the upstream logout URL [signOut] hands back — the upstream OIDC
     * provider's end-session URL, or the SAML Single Logout start URL — so the
     * user is signed out there too. [create] sets it to open the system browser;
     * null (the default for a hand-built client) only returns the URL in the
     * [SignOutResult].
     */
    var logoutUrlOpener: ((String) -> Unit)? = null

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
     *
     * When the session came from an upstream OIDC provider set to RP-initiated
     * logout, or a SAML connection with Single Logout, Atlas answers with the URL
     * that signs the user out there too ([SignOutResult.upstreamLogoutUrl] /
     * [SignOutResult.samlLogoutUrl], https only). With [followLogoutUrl] (the
     * default) it is handed to [logoutUrlOpener] — the system browser for a
     * client built with [create].
     *
     * @param returnUrl where the upstream sends the browser back; must be on one
     *   of the instance's allowed origins.
     */
    @JvmOverloads
    suspend fun signOut(returnUrl: String? = null, followLogoutUrl: Boolean = true): SignOutResult {
        val stored = tokenStore.load()
        var result = SignOutResult()
        try {
            if (stored != null) {
                try {
                    val body = returnUrl?.let { json.encodeToString(stringMapSerializer, mapOf("return_url" to it, "redirect_url" to it)) }
                    val response = sendRaw(
                        method = "POST",
                        path = "/v1/client/sessions/${stored.sessionId}/revoke",
                        bodyJson = body,
                        cookie = cookieHeader(stored),
                    )
                    if (response.status in 200..299) result = SignOutResult.fromResponse(json, response.body)
                } catch (_: AtlasException) {
                    // Best-effort revoke; local clear below is what matters.
                }
            }
        } finally {
            tokenStore.clear()
        }
        val url = result.logoutUrl
        if (followLogoutUrl && url != null) logoutUrlOpener?.invoke(url)
        return result
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
     * A string-map body convenience over [sendRaw] — encodes the map to JSON and
     * sends it. The existing happy-path flows (password sign-in, ticket exchange,
     * passkey) all have flat string bodies, so this keeps them unchanged.
     */
    private suspend fun send(
        method: String,
        path: String,
        body: Map<String, String>?,
        cookie: String?,
    ): RawResponse = sendRaw(
        method = method,
        path = path,
        bodyJson = body?.let { json.encodeToString(stringMapSerializer, it) },
        cookie = cookie,
    )

    /**
     * The single place a request is built and sent. Every call flows through here
     * so the auth header, base URL, and JSON content type are set in exactly one
     * place — closing off the class of bug where one endpoint forgets the key.
     *
     * [bodyJson] is the already-encoded JSON body (or null). A richer body than a
     * flat string map — nested objects, arrays, booleans, a PATCH of
     * `unsafe_metadata` — is encoded by the caller and handed in here, so the
     * multi-step flow driver and the `/me` mutation surface reuse this exact path
     * rather than opening a second one.
     */
    private suspend fun sendRaw(
        method: String,
        path: String,
        bodyJson: String?,
        cookie: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): RawResponse = withContext(Dispatchers.IO) {
        val stampedBody = DeviceId.stamp(json, method, path, bodyJson, deviceIdProvider)
        val url = baseUrl.resolve(path)
            ?: throw AtlasException.Transport("Could not build a request URL for $path.")

        val builder = Request.Builder()
            .url(url)
            .header("x-publishable-key", publishableKey)
        if (cookie != null) {
            builder.header("Cookie", cookie)
        }
        for ((name, value) in extraHeaders) {
            builder.header(name, value)
        }
        val provider = attestationProvider
        if (provider != null && AtlasAttestation.isGuarded(path, method)) {
            val challenge = runCatching { sendRaw("POST", "/v1/client/attestation/challenge", "{}", null) }.getOrNull()
            val nonce = challenge?.takeIf { it.status in 200..299 }?.let { runCatching { json.parseToJsonElement(it.body).jsonObject["nonce"]?.jsonPrimitive?.content }.getOrNull() }
            if (nonce != null) builder.header("Atlas-Attestation", AtlasAttestation.header(nonce, provider(nonce)))
        }

        val requestBody: RequestBody? = when {
            stampedBody != null -> {
                builder.header("content-type", "application/json")
                stampedBody.toRequestBody(jsonMediaType)
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

    /**
     * The shared request path for the flow driver, native id_token sign-in, and
     * the organizations / sessions / `/me` surfaces. Sends an arbitrary pre-encoded
     * JSON [bodyJson] (or none), presents the stored session cookie when
     * [authenticated], throws on a non-2xx, and hands back the raw body plus the
     * `Set-Cookie` header(s) so a caller that completes into a session can capture
     * the rotated refresh token.
     *
     * @throws AtlasException.NotSignedIn when [authenticated] is set but no session
     *   is stored.
     */
    internal suspend fun requestRaw(
        method: String,
        path: String,
        bodyJson: String?,
        authenticated: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
    ): AtlasResponse {
        val cookie = if (authenticated) {
            val stored = tokenStore.load() ?: throw AtlasException.NotSignedIn
            cookieHeader(stored)
        } else {
            null
        }
        val response = sendRaw(method, path, bodyJson, cookie, extraHeaders)
        throwIfError(response)
        return AtlasResponse(body = response.body, setCookies = response.setCookies)
    }

    /** The raw body + `Set-Cookie` header(s) of a successful request. */
    internal data class AtlasResponse(val body: String, val setCookies: List<String>)

    /** Decode a body with the SDK's tolerant JSON, mapping failures to [AtlasException.Decoding]. */
    internal fun <T> decodeOrThrow(body: String, deserializer: DeserializationStrategy<T>): T =
        decode(body, deserializer)

    /** Pull the rotated `__atlas_rt` refresh token out of a response's cookies, if any. */
    internal fun extractRefreshCookie(setCookies: List<String>): String? =
        extractCookie(Cookie.REFRESH, setCookies)

    /**
     * Persist a session minted DIRECTLY by a completed attempt (the passkey /
     * native shape: `jwt` + `created_session_id` in the body, refresh token as a
     * Set-Cookie), the fallback the flow driver uses when a completion carries no
     * exchange ticket. Returns the signed-in user.
     */
    internal suspend fun persistDirectSession(
        createdSessionId: String?,
        response: AtlasResponse,
    ): AtlasUser {
        val tokens = decode(response.body, SessionTokens.serializer())
        val refresh = extractCookie(Cookie.REFRESH, response.setCookies)
        val sessionId = createdSessionId ?: tokens.resolvedSessionId ?: ""
        tokenStore.save(AtlasSession(sessionId = sessionId, token = tokens.jwt, refreshToken = refresh))
        return currentUser()
    }

    // MARK: - Multi-step flow drivers

    /** A suspend-driven sign-in state machine (password, email/phone code, 2FA, MFA enrollment). */
    fun signInFlow(): SignInFlow = SignInFlow(this)

    /** A suspend-driven sign-up state machine (create → verify → complete). */
    fun signUpFlow(): SignUpFlow = SignUpFlow(this)

    /** A suspend-driven password-reset state machine (request → verify → 2FA → set new password). */
    fun passwordResetFlow(): PasswordResetFlow = PasswordResetFlow(this)

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
        ).apply {
            val appContext = context.applicationContext
            logoutUrlOpener = { url -> openInSystemBrowser(appContext, url) }
        }

        /** Open [url] in the system browser (a new task, so any context works). */
        @JvmStatic
        fun openInSystemBrowser(context: Context, url: String) {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

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
