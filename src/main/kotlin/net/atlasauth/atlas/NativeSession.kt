package net.atlasauth.atlas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Native session — the first-party OAuth→session exchange, cookie-free.
 *
 * A FIRST-PARTY OAuth client (the app IS the tenant's own property, not a
 * third-party integration) already holds an Atlas OAuth access token. On the web
 * that token would ride in a cookie and the browser would carry the session for
 * free; a native Android app has no cookie jar against the FAPI origin, so it
 * trades that OAuth access token for a real Atlas SESSION and then carries the
 * session itself, by hand, as a bearer.
 *
 * This file is the Kotlin peer of `@atlas/js`'s `native-session.ts` and of the
 * Swift `NativeSession.swift`:
 *
 *   1. [exchangeForSession] — POST the RFC 8693 token-exchange form to
 *      `/oauth2/token` and get back a [NativeSession].
 *   2. [refreshNativeSession] — rotate the session WITHOUT a cookie, via
 *      `POST /v1/client/sessions/:sid/tokens` with the stored refresh token.
 *   3. [NativeSessionManager] — holds the current session, hands out a live JWT
 *      (auto-refreshing near expiry, single-flight), and persists each rotated
 *      refresh token to the SDK's [TokenStore] (an encrypted store in production).
 *
 * Neither network helper ever throws — a failure is `null`, the caller's cue to
 * re-run the OAuth flow rather than crash.
 */

/** RFC 8693 token-exchange grant. */
private const val TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"

/** The subject token the first-party app presents is an OAuth access token. */
private const val ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"

/** What we ask for in return: an Atlas session, not another OAuth token. */
private const val SESSION_TOKEN_TYPE = "urn:atlas:token-type:session"

/** The default refresh lead: rotate a token once it is within 10s of expiry. */
private const val DEFAULT_REFRESH_LEAD_MILLIS = 10_000L

/** Shared JSON config, mirroring [AtlasClient]'s: tolerant of unknown keys. */
private val nativeSessionJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * A live Atlas session held outside a cookie.
 *
 * [sessionToken] is the short-lived (~60s) session JWT sent as `Authorization:
 * Bearer …` on `/v1/client/me/…`. [refreshToken] mints the next one and ROTATES on
 * every refresh — persist the new value, discard the old. [expiresInSeconds] is
 * the lifetime the server reported for [sessionToken], a scheduling hint only.
 */
@Serializable
data class NativeSession(
    val sessionToken: String,
    val refreshToken: String,
    val sessionId: String,
    val expiresInSeconds: Int = 0,
) {
    /**
     * Map to the [AtlasSession] the SDK's [TokenStore] persists, so a native
     * session reuses the same encrypted entry as the cookie-based client.
     */
    fun toSession(): AtlasSession =
        AtlasSession(sessionId = sessionId, token = sessionToken, refreshToken = refreshToken)

    companion object {
        /**
         * Rebuild from a persisted [AtlasSession]. The reported expiry is not
         * persisted, so it rehydrates as `0` — the manager treats the token as due
         * for a refresh on its first use, which is the safe default.
         */
        fun fromSession(session: AtlasSession): NativeSession = NativeSession(
            sessionToken = session.token,
            refreshToken = session.refreshToken ?: "",
            sessionId = session.sessionId,
        )
    }
}

/** The shape `/oauth2/token` answers a successful token-exchange with. */
@Serializable
private data class TokenExchangeResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("issued_token_type") val issuedTokenType: String? = null,
)

/** The shape `/v1/client/sessions/:sid/tokens` answers a cookie-free refresh with. */
@Serializable
private data class SessionTokensResponse(
    val jwt: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
)

/** The JSON body of a cookie-free refresh request. */
@Serializable
private data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

/**
 * Exchange a first-party OAuth access token for an Atlas session.
 *
 * POSTs the RFC 8693 token-exchange form to `{baseUrl}/oauth2/token` and parses
 * the result into a [NativeSession]. Returns `null` — never throws — on a network
 * failure, a non-2xx, or a body missing the session token or id, so a caller
 * treats a failed exchange as "re-run OAuth" rather than a crash.
 */
suspend fun exchangeForSession(
    baseUrl: HttpUrl,
    clientId: String,
    accessToken: String,
    httpClient: OkHttpClient,
    json: Json = nativeSessionJson,
): NativeSession? = withContext(Dispatchers.IO) {
    val url = baseUrl.resolve("/oauth2/token") ?: return@withContext null
    // FormBody sets `application/x-www-form-urlencoded`, per the grant.
    val form = FormBody.Builder()
        .add("grant_type", TOKEN_EXCHANGE_GRANT)
        .add("client_id", clientId)
        .add("subject_token", accessToken)
        .add("subject_token_type", ACCESS_TOKEN_TYPE)
        .add("requested_token_type", SESSION_TOKEN_TYPE)
        .build()
    val request = Request.Builder().url(url).post(form).build()

    val body = try {
        httpClient.newCall(request).execute().use {
            if (!it.isSuccessful) return@withContext null
            it.body?.string().orEmpty()
        }
    } catch (_: IOException) {
        return@withContext null
    }

    val parsed = try {
        json.decodeFromString(TokenExchangeResponse.serializer(), body)
    } catch (_: Throwable) {
        return@withContext null
    }

    // A session is only a session if it carries both the JWT and the id the
    // refresh path needs; anything short of that is a failed exchange.
    val token = parsed.accessToken ?: return@withContext null
    val sessionId = parsed.sessionId ?: return@withContext null
    NativeSession(
        sessionToken = token,
        refreshToken = parsed.refreshToken ?: "",
        sessionId = sessionId,
        expiresInSeconds = parsed.expiresIn ?: 0,
    )
}

/**
 * Rotate a native session WITHOUT a cookie.
 *
 * POSTs the stored refresh token to `/v1/client/sessions/{sessionId}/tokens` with
 * the publishable-key header, and returns the rotated [NativeSession]. Each
 * refresh ROTATES the refresh token — the caller MUST persist what comes back. If
 * the server omits a fresh `refresh_token` (it may, when it reuses the presented
 * one), the presented token is carried forward. Returns `null` — never throws — on
 * a network failure, a non-2xx, or a body with no `jwt`.
 */
suspend fun refreshNativeSession(
    baseUrl: HttpUrl,
    publishableKey: String,
    sessionId: String,
    refreshToken: String,
    httpClient: OkHttpClient,
    json: Json = nativeSessionJson,
): NativeSession? = withContext(Dispatchers.IO) {
    val url = baseUrl.resolve("/v1/client/sessions/$sessionId/tokens") ?: return@withContext null
    val payload = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
    val request = Request.Builder()
        .url(url)
        .header("x-publishable-key", publishableKey)
        .header("content-type", "application/json")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()

    val body = try {
        httpClient.newCall(request).execute().use {
            if (!it.isSuccessful) return@withContext null
            it.body?.string().orEmpty()
        }
    } catch (_: IOException) {
        return@withContext null
    }

    val parsed = try {
        json.decodeFromString(SessionTokensResponse.serializer(), body)
    } catch (_: Throwable) {
        return@withContext null
    }

    val jwt = parsed.jwt ?: return@withContext null
    NativeSession(
        sessionToken = jwt,
        // Carry the rotated token; fall back to the presented one if the server
        // reused it rather than issuing a new value.
        refreshToken = parsed.refreshToken ?: refreshToken,
        sessionId = parsed.sessionId ?: sessionId,
        expiresInSeconds = parsed.expiresIn ?: 0,
    )
}

/**
 * Holds the current [NativeSession] and keeps its JWT live.
 *
 * It refreshes LAZILY — on [token]/[authHeaders], when the token is within the
 * refresh lead (~10s) of expiry — rather than on a timer, because a backgrounded
 * app cannot keep one alive anyway. A [Mutex] makes the rotation single-flight:
 * concurrent callers that reach it together take the result of the one refresh
 * that runs. Each rotation persists the new session to the [TokenStore] so the
 * encrypted store captures the rotated refresh token; the previous one is dead.
 *
 * All network methods are `suspend` functions; call them from a coroutine.
 *
 * @param publishableKey the instance's `pk_...` key, sent on the refresh call and
 *   in [authHeaders].
 * @param frontendApi the FAPI host (`clerk.example.com`) or a full origin.
 * @param clientId the first-party OAuth client id; `null` disables [exchange].
 * @param tokenStore where the session is persisted.
 * @param httpClient injectable for tests; defaults to a cookie-less OkHttp client.
 */
class NativeSessionManager(
    val publishableKey: String,
    frontendApi: String,
    val clientId: String? = null,
    val tokenStore: TokenStore,
    httpClient: OkHttpClient? = null,
    private val refreshLeadMillis: Long = DEFAULT_REFRESH_LEAD_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** The resolved FAPI base URL, e.g. `https://clerk.example.com/`. */
    val baseUrl: HttpUrl = AtlasClient.resolveBaseUrl(frontendApi)

    // OkHttp defaults to CookieJar.NO_COOKIES, so an injected/default client is
    // already cookie-less — the manager carries the session as a bearer by hand.
    private val http: OkHttpClient = httpClient ?: OkHttpClient()
    private val json: Json = nativeSessionJson
    private val refreshMutex = Mutex()

    @Volatile
    private var session: NativeSession? = null

    @Volatile
    private var expiresAtMillis: Long = 0

    init {
        // Rehydrate a persisted session. Its expiry is unknown (not persisted), so
        // seed it as already-due: the first `token()` refreshes before use.
        val stored = runCatching { tokenStore.load() }.getOrNull()
        if (stored != null) {
            session = NativeSession.fromSession(stored)
            expiresAtMillis = now()
        }
    }

    /** The current session, or `null` when signed out. Does NOT refresh. */
    val current: NativeSession? get() = session

    /**
     * Exchange a first-party OAuth access token for a session, store it, and
     * return it. Returns `null` when no [clientId] was configured or the exchange
     * fails (the caller's cue to re-run OAuth).
     */
    suspend fun exchange(accessToken: String): NativeSession? {
        val id = clientId ?: return null
        val exchanged = exchangeForSession(baseUrl, id, accessToken, http, json) ?: return null
        setSession(exchanged)
        return exchanged
    }

    /**
     * The current session JWT, refreshed first if it is within the refresh lead of
     * expiry. Returns `null` when signed out. If the refresh fails the EXISTING
     * token is handed back rather than `null` — a truly-dead token is rejected on
     * use (the 401 is the caller's cue), a better failure than a pre-emptive
     * sign-out on a flaky connection.
     */
    suspend fun token(): String? {
        val current = session ?: return null
        if (needsRefresh()) {
            val rotated = refresh()
            if (rotated != null) return rotated.sessionToken
        }
        return session?.sessionToken ?: current.sessionToken
    }

    /**
     * The headers an authenticated `/v1/client/me/…` call needs: a fresh bearer
     * (auto-refreshed like [token]) plus the publishable key. When signed out, only
     * the publishable key is returned.
     */
    suspend fun authHeaders(): Map<String, String> {
        val t = token()
        return if (t != null) {
            mapOf("Authorization" to "Bearer $t", "x-publishable-key" to publishableKey)
        } else {
            mapOf("x-publishable-key" to publishableKey)
        }
    }

    /** Replace the current session and persist it (e.g. after a manual exchange). */
    fun setSession(session: NativeSession) {
        this.session = session
        this.expiresAtMillis = now() + session.expiresInSeconds * 1000L
        runCatching { tokenStore.save(session.toSession()) }
    }

    /** Forget the session (sign-out). Does not touch the store. */
    fun clear() {
        session = null
        expiresAtMillis = 0
    }

    /**
     * Rotate the session now. Single-flight via [refreshMutex]: a caller that waits
     * on the lock and finds the session already rotated takes that result instead
     * of issuing a second call. On success the new session is stored and returned;
     * `null` on failure, leaving the current session untouched.
     */
    suspend fun refresh(): NativeSession? {
        val before = session ?: return null
        return refreshMutex.withLock {
            // If another coroutine rotated while we waited, take its result.
            val latest = session
            if (latest != null && latest !== before) return@withLock latest
            val rotated = refreshNativeSession(
                baseUrl = baseUrl,
                publishableKey = publishableKey,
                sessionId = before.sessionId,
                refreshToken = before.refreshToken,
                httpClient = http,
                json = json,
            ) ?: return@withLock null
            setSession(rotated)
            rotated
        }
    }

    private fun needsRefresh(): Boolean {
        if (session == null) return false
        return expiresAtMillis - refreshLeadMillis <= now()
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
            clientId: String? = null,
            httpClient: OkHttpClient? = null,
        ): NativeSessionManager = NativeSessionManager(
            publishableKey = publishableKey,
            frontendApi = frontendApi,
            clientId = clientId,
            tokenStore = EncryptedSharedPreferencesTokenStore(context, publishableKey),
            httpClient = httpClient,
        )
    }
}
