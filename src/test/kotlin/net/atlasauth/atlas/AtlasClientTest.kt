package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Ports the Swift `AtlasClientTests` + `MockURLProtocol` approach to
 * MockWebServer: fully offline, deterministic, one canned response per enqueue,
 * with `takeRequest()` as the mutation-check surface (URL, method, headers, body).
 */
class AtlasClientTest {

    private val pk = "pk_test_123"
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // already shut down by a test
        }
    }

    private fun frontendApi(): String = server.url("/").toString()

    private fun makeClient(store: TokenStore = InMemoryTokenStore()): AtlasClient {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return AtlasClient(
            publishableKey = pk,
            frontendApi = frontendApi(),
            tokenStore = store,
            httpClient = client,
        )
    }

    private fun jsonResponse(code: Int, body: String, setCookie: String? = null): MockResponse {
        val response = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
        if (setCookie != null) response.addHeader("Set-Cookie", setCookie)
        return response
    }

    private fun bodyOf(request: RecordedRequest): JsonObject =
        json.parseToJsonElement(request.body.readUtf8()) as JsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    // MARK: base URL + auth header

    @Test
    fun resolvesBareHostToHttps() {
        val url = AtlasClient.resolveBaseUrl("clerk.example.com")
        assertEquals("https", url.scheme)
        assertEquals("clerk.example.com", url.host)
    }

    @Test
    fun resolvesFullOriginUntouched() {
        val url = AtlasClient.resolveBaseUrl("http://localhost:4000/")
        assertEquals("http", url.scheme)
        assertEquals("localhost", url.host)
        assertEquals(4000, url.port)
    }

    @Test
    fun everyRequestCarriesPublishableKeyAndBaseUrl() = runTest {
        server.enqueue(jsonResponse(201, """{"id":"sia_1","status":"needs_first_factor"}"""))
        server.enqueue(jsonResponse(200, """{"id":"sia_1","status":"complete","ticket":"tk_1"}"""))
        server.enqueue(
            jsonResponse(
                200,
                """{"object":"session","id":"sess_1","jwt":"jwt_abc","expires_in":60}""",
                setCookie = "__atlas_rt=rt_xyz; Path=/v1; HttpOnly",
            ),
        )
        server.enqueue(jsonResponse(200, userJson))

        val client = makeClient()
        client.signIn(email = "a@b.com", password = "hunter2")

        val first = server.takeRequest()
        assertEquals("/v1/client/sign_ins", first.path)
        assertEquals(pk, first.getHeader("x-publishable-key"))

        // Every subsequent request also presented the publishable key.
        repeat(3) {
            val req = server.takeRequest()
            assertEquals(pk, req.getHeader("x-publishable-key"))
        }
    }

    // MARK: password sign-in — endpoint + body mutation-checks + token storage

    @Test
    fun passwordSignInHitsExactEndpointsWithExactBodies() = runTest {
        server.enqueue(jsonResponse(201, """{"id":"sia_42","status":"needs_first_factor"}"""))
        server.enqueue(jsonResponse(200, """{"id":"sia_42","status":"complete","ticket":"tk_9"}"""))
        server.enqueue(
            jsonResponse(
                200,
                """{"object":"session","id":"sess_9","jwt":"jwt_final","expires_in":60}""",
                setCookie = "__atlas_rt=rt_final; Path=/v1; HttpOnly",
            ),
        )
        server.enqueue(jsonResponse(200, userJson))

        val store = InMemoryTokenStore()
        val client = makeClient(store)
        val user = client.signIn(email = "a@b.com", password = "hunter2")

        // Step 1: create attempt with the identifier (never the password).
        val r0 = server.takeRequest()
        assertEquals("/v1/client/sign_ins", r0.path)
        val b0 = bodyOf(r0)
        assertEquals("a@b.com", b0.str("identifier"))
        assertNull("the password must not leak into the create call", b0["password"])

        // Step 2: first factor with strategy=password on the attempt id.
        val r1 = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_42/attempt_first_factor", r1.path)
        val b1 = bodyOf(r1)
        assertEquals("password", b1.str("strategy"))
        assertEquals("hunter2", b1.str("password"))

        // Step 3: exchange the completion ticket.
        val r2 = server.takeRequest()
        assertEquals("/v1/client/tickets/exchange", r2.path)
        val b2 = bodyOf(r2)
        assertEquals("sia_42", b2.str("attempt_id"))
        assertEquals("tk_9", b2.str("ticket"))

        // Token stored: the JWT from the exchange and the refresh cookie captured.
        val stored = store.load() ?: error("expected a stored session")
        assertEquals("jwt_final", stored.token)
        assertEquals("rt_final", stored.refreshToken)
        assertEquals("sess_9", stored.sessionId)

        assertEquals("user_1", user.id)
    }

    // MARK: 4xx -> AtlasException with code (mutation-check on the envelope)

    @Test
    fun wrongPasswordSurfacesApiErrorWithCode() = runTest {
        server.enqueue(jsonResponse(201, """{"id":"sia_1","status":"needs_first_factor"}"""))
        server.enqueue(
            jsonResponse(
                422,
                """{"errors":[{"code":"form_password_incorrect","message":"Incorrect password.","param":"password"}]}""",
            ),
        )

        val client = makeClient()
        try {
            client.signIn(email = "a@b.com", password = "wrong")
            fail("expected an AtlasException")
        } catch (error: AtlasException) {
            assertEquals("form_password_incorrect", error.code)
            assertEquals(422, error.status)
            assertEquals("Incorrect password.", error.message)
        }
    }

    @Test
    fun malformedErrorBodyStillYieldsACode() = runTest {
        server.enqueue(jsonResponse(500, "not json at all"))
        val client = makeClient()
        try {
            client.oauthAuthorizeUrl(provider = "google", redirectUri = "app://cb")
            fail("expected an AtlasException")
        } catch (error: AtlasException) {
            assertEquals(500, error.status)
            assertEquals("unexpected", error.code)
        }
    }

    // MARK: currentUser decodes

    @Test
    fun currentUserDecodesFullShape() = runTest {
        val store = InMemoryTokenStore(AtlasSession(sessionId = "sess_1", token = "jwt", refreshToken = "rt"))
        server.enqueue(jsonResponse(200, userJson))

        val client = makeClient(store)
        val user = client.currentUser()

        assertEquals("user_1", user.id)
        assertEquals("Ada", user.firstName)
        assertEquals("email_1", user.primaryEmailId)
        assertEquals("ada@example.com", user.emailAddresses?.first()?.emailAddress)
        assertEquals(true, user.emailAddresses?.first()?.verified)
        assertEquals("google", user.externalAccounts?.first()?.provider)
        assertEquals("pro", user.publicMetadata?.get("plan")?.stringValue)

        // The authenticated call presented the refresh cookie + session JWT.
        val req = server.takeRequest()
        assertEquals("/v1/client/me", req.path)
        val cookie = req.getHeader("Cookie") ?: ""
        assertTrue(cookie.contains("__atlas_rt=rt"))
        assertTrue(cookie.contains("__session=jwt"))
    }

    @Test
    fun currentUserWithoutSessionThrowsNotSignedIn() = runTest {
        val client = makeClient() // empty store
        try {
            client.currentUser()
            fail("expected NotSignedIn")
        } catch (error: AtlasException) {
            assertTrue(error is AtlasException.NotSignedIn)
        }
    }

    // MARK: OAuth authorize URL

    @Test
    fun oauthAuthorizeUrlReturnsProviderUrl() = runTest {
        server.enqueue(
            jsonResponse(
                201,
                """{"object":"sign_in_attempt","id":"sia_o","status":"needs_oauth_callback","authorization_url":"https://accounts.google.com/o/oauth2/auth?x=1"}""",
            ),
        )
        val client = makeClient()
        val url = client.oauthAuthorizeUrl(provider = "google", redirectUri = "myapp://callback")

        assertTrue(url.startsWith("https://accounts.google.com/"))

        val req = server.takeRequest()
        assertEquals("/v1/client/sign_ins/oauth", req.path)
        val b = bodyOf(req)
        assertEquals("google", b.str("provider"))
        assertEquals("myapp://callback", b.str("redirect_url"))
    }

    // MARK: refresh rotates the stored token

    @Test
    fun refreshRotatesStoredToken() = runTest {
        val store = InMemoryTokenStore(AtlasSession(sessionId = "sess_1", token = "old", refreshToken = "rt_old"))
        server.enqueue(
            jsonResponse(
                200,
                """{"object":"session_tokens","jwt":"jwt_new","session_id":"sess_1","expires_in":60}""",
                setCookie = "__atlas_rt=rt_new; Path=/v1; HttpOnly",
            ),
        )
        val client = makeClient(store)
        val rotated = client.refresh()

        assertEquals("jwt_new", rotated.token)
        assertEquals("rt_new", rotated.refreshToken)

        val req = server.takeRequest()
        assertEquals("/v1/client/sessions/sess_1/tokens", req.path)
        assertEquals("jwt_new", store.load()?.token)
    }

    // MARK: sign-out clears storage even on network failure

    @Test
    fun signOutClearsStoreEvenWhenRevokeFails() = runTest {
        val store = InMemoryTokenStore(AtlasSession(sessionId = "sess_1", token = "jwt", refreshToken = "rt"))
        val client = makeClient(store)
        // Kill the server so the revoke request fails at transport. Store must
        // still be cleared.
        server.shutdown()

        client.signOut()
        assertNull(store.load())
    }

    companion object {
        val userJson = """
        {
          "object": "user",
          "id": "user_1",
          "first_name": "Ada",
          "last_name": "Lovelace",
          "username": null,
          "image_url": "https://img.example.com/a.png",
          "locale": "en-US",
          "public_metadata": { "plan": "pro" },
          "unsafe_metadata": {},
          "mfa_enabled": false,
          "has_password": true,
          "created_at": 1700000000000,
          "primary_email_id": "email_1",
          "email_addresses": [
            { "object": "email_address", "id": "email_1", "email_address": "ada@example.com", "verified": true, "primary": true }
          ],
          "external_accounts": [
            { "object": "external_account", "id": "ext_1", "provider": "google", "provider_email": "ada@gmail.com", "connected_at": 1700000000000 }
          ],
          "passkeys": []
        }
        """.trimIndent()
    }
}
