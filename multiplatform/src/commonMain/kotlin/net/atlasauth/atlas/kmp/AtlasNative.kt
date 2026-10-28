package net.atlasauth.atlas.kmp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A live Atlas session held outside a cookie. */
@Serializable
public data class NativeSession(
    /** The short-lived session JWT, sent as `Authorization: Bearer`. */
    val sessionToken: String,
    /** Mints the next session token; it ROTATES on every refresh, so the new one is always stored. */
    val refreshToken: String,
    val sessionId: String,
    /** Epoch millis the session token expires; null when unknown, which refreshes on first use. */
    val expiresAtMillis: Long? = null,
)

/** Where the session is kept between launches. Back it with the platform keychain / keystore. */
public interface SessionStore {
    public suspend fun load(): NativeSession?
    public suspend fun save(session: NativeSession)
    public suspend fun clear()
}

public class MemorySessionStore : SessionStore {
    private var session: NativeSession? = null
    override suspend fun load(): NativeSession? = session
    override suspend fun save(session: NativeSession) { this.session = session }
    override suspend fun clear() { session = null }
}

/** Opens the system browser on [authorizeUrl] and returns the full URL it was redirected back to. */
public fun interface AuthBrowser {
    public suspend fun authenticate(authorizeUrl: String, redirectUri: String): String
}

public class AtlasNativeOptions(
    /** The instance's frontend API origin (the `iss` of its tokens). */
    public val issuer: String,
    /** The instance publishable key (`pk_...`), sent when the session is refreshed. */
    public val publishableKey: String,
    /** Your first-party public OAuth client. */
    public val clientId: String,
    /** The redirect URI registered for the client, e.g. `myapp://callback`. */
    public val redirectUri: String,
    public val scope: String = "openid profile email",
    /** Rotate the session token once it is within this long of expiry. */
    public val refreshLeadMillis: Long = 10_000,
)

public class AtlasNativeException(message: String) : Exception(message)

/**
 * Signs a person in through Atlas's hosted pages (authorization code with PKCE in the system browser),
 * trades the OAuth access token for an Atlas session, and keeps that session fresh: rotated lazily and
 * single-flight, and persisted to the [SessionStore] each time.
 */
public class AtlasNativeClient(
    private val options: AtlasNativeOptions,
    private val browser: AuthBrowser,
    private val store: SessionStore,
    private val http: HttpClient = HttpClient(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val issuer: String = options.issuer.trimEnd('/')
    private val mutex = Mutex()
    private var session: NativeSession? = null
    private var loaded = false

    init {
        val url = Url(issuer)
        val local = url.host == "localhost" || url.host == "127.0.0.1"
        require(url.protocol.name == "https" || (local && url.protocol.name == "http")) { "AtlasNativeOptions.issuer must be an https URL." }
        require(options.publishableKey.isNotEmpty() && options.clientId.isNotEmpty() && options.redirectUri.isNotEmpty()) {
            "publishableKey, clientId and redirectUri are required."
        }
    }

    public suspend fun isSignedIn(): Boolean {
        ensureLoaded()
        return session != null
    }

    /** Run the hosted sign-in and store the session. Throws [AtlasNativeException] on any refusal. */
    public suspend fun signIn() {
        val (verifier, challenge) = Pkce.create()
        val state = Pkce.base64Url(secureRandomBytes(16))
        val (authorizeEndpoint, tokenEndpoint) = discover()

        val authorizeUrl = URLBuilder(authorizeEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", options.clientId)
            parameters.append("redirect_uri", options.redirectUri)
            parameters.append("scope", options.scope)
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

        val callback = Url(browser.authenticate(authorizeUrl, options.redirectUri))
        callback.parameters["error"]?.let { throw AtlasNativeException("Sign-in was refused: $it.") }
        val returnedState = callback.parameters["state"]
        if (returnedState == null || !constantTimeEquals(returnedState, state)) {
            throw AtlasNativeException("The sign-in callback did not match the request.")
        }
        val code = callback.parameters["code"]?.takeIf { it.isNotEmpty() } ?: throw AtlasNativeException("The sign-in callback carried no code.")

        val tokens = postForm(tokenEndpoint, parameters {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", options.redirectUri)
            append("client_id", options.clientId)
            append("code_verifier", verifier)
        })
        val accessToken = tokens.string("access_token") ?: throw AtlasNativeException("The token endpoint returned no access token.")

        val exchanged = postForm("$issuer/oauth2/token", parameters {
            append("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
            append("client_id", options.clientId)
            append("subject_token", accessToken)
            append("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
            append("requested_token_type", "urn:atlas:token-type:session")
        })
        val fresh = toSession(exchanged, exchanged.string("access_token"), exchanged.string("refresh_token"), exchanged.string("session_id"))
            ?: throw AtlasNativeException("The session exchange returned no session.")
        mutex.withLock { set(fresh) }
    }

    /**
     * A usable session token, rotated first when it is within the refresh lead of expiry; null when signed out.
     * A failed refresh keeps the current token (the API's 401 is the cue to sign in again); a refresh the server
     * REFUSES (the refresh token is dead) signs the person out.
     */
    public suspend fun getToken(): String? {
        ensureLoaded()
        val first = session ?: return null
        if (!needsRefresh(first)) return first.sessionToken
        return mutex.withLock {
            val current = session ?: return@withLock null
            if (!needsRefresh(current) || current.refreshToken.isEmpty()) return@withLock current.sessionToken
            val response = try {
                http.post("$issuer/v1/client/sessions/${current.sessionId}/tokens") {
                    header("x-publishable-key", options.publishableKey)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("refresh_token", current.refreshToken) }.toString())
                }
            } catch (e: Exception) {
                return@withLock current.sessionToken
            }
            val status = response.status.value
            if (status == 401 || status == 403) {
                clearLocked()
                return@withLock null
            }
            if (status !in 200..299) return@withLock current.sessionToken
            val body = parse(response.bodyAsText()) ?: return@withLock current.sessionToken
            val rotated = toSession(body, body.string("jwt"), body.string("refresh_token") ?: current.refreshToken, body.string("session_id") ?: current.sessionId)
                ?: return@withLock current.sessionToken
            set(rotated)
            rotated.sessionToken
        }
    }

    /** Forget the session on this device. */
    public suspend fun signOut() {
        mutex.withLock { clearLocked() }
    }

    /**
     * A Ktor client plugin that adds the session token to requests whose URL starts with one of
     * [authorizedUrls], and to nothing else: `HttpClient { install(client.authPlugin(listOf("https://api.example.com"))) }`.
     */
    public fun authPlugin(authorizedUrls: List<String>): io.ktor.client.plugins.api.ClientPlugin<Unit> {
        val client = this
        return createClientPlugin("AtlasAuth") {
            onRequest { request, _ ->
                val url = request.url.buildString()
                if (client.isAuthorized(url, authorizedUrls)) {
                    client.getToken()?.let { request.header(HttpHeaders.Authorization, "Bearer $it") }
                }
            }
        }
    }

    internal fun isAuthorized(url: String, authorizedUrls: List<String>): Boolean {
        val target = runCatching { Url(url) }.getOrNull() ?: return false
        return authorizedUrls.any { allowed ->
            val base = runCatching { Url(allowed) }.getOrNull() ?: return@any false
            val basePath = base.encodedPath.trimEnd('/')
            target.protocol == base.protocol && target.host.equals(base.host, ignoreCase = true) && target.port == base.port &&
                (basePath.isEmpty() || target.encodedPath == basePath || target.encodedPath.startsWith("$basePath/"))
        }
    }

    private fun needsRefresh(s: NativeSession): Boolean {
        val expires = s.expiresAtMillis ?: return true
        return now() >= expires - options.refreshLeadMillis
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            session = try { store.load() } catch (e: Exception) { null } // unreadable storage means signed out
            loaded = true
        }
    }

    private suspend fun set(s: NativeSession) {
        session = s
        loaded = true
        store.save(s)
    }

    private suspend fun clearLocked() {
        session = null
        loaded = true
        try { store.clear() } catch (e: Exception) { /* nothing left to protect */ }
    }

    private fun toSession(body: JsonObject, token: String?, refresh: String?, sessionId: String?): NativeSession? {
        if (token.isNullOrEmpty() || sessionId.isNullOrEmpty()) return null
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        return NativeSession(token, refresh ?: "", sessionId, expiresIn?.let { now() + it * 1000L })
    }

    private suspend fun discover(): Pair<String, String> {
        val response = http.get("$issuer/.well-known/openid-configuration")
        if (response.status.value !in 200..299) throw AtlasNativeException("Could not load the sign-in configuration.")
        val doc = parse(response.bodyAsText()) ?: throw AtlasNativeException("The sign-in configuration was not JSON.")
        if (doc.string("issuer")?.trimEnd('/') != issuer) throw AtlasNativeException("The sign-in configuration is for a different issuer.")
        val origin = Url(issuer)
        fun endpoint(name: String): String {
            val raw = doc.string(name) ?: throw AtlasNativeException("The sign-in configuration has no $name.")
            val url = runCatching { Url(raw) }.getOrNull() ?: throw AtlasNativeException("The $name is not a URL.")
            // A code or token is never sent to another origin than the issuer.
            if (url.protocol != origin.protocol || !url.host.equals(origin.host, ignoreCase = true) || url.port != origin.port) {
                throw AtlasNativeException("The $name is not on the issuer's origin.")
            }
            return raw
        }
        return endpoint("authorization_endpoint") to endpoint("token_endpoint")
    }

    private suspend fun postForm(url: String, form: Parameters): JsonObject {
        val response: HttpResponse = http.submitForm(url, formParameters = form)
        if (response.status.value !in 200..299) throw AtlasNativeException("$url refused the request (${response.status.value}).")
        return parse(response.bodyAsText()) ?: throw AtlasNativeException("$url did not answer JSON.")
    }

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }
}
