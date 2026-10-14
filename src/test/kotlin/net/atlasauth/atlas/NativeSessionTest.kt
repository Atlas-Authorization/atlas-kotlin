package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Ports the Swift `NativeSessionTests` to the same MockWebServer harness
 * `AtlasClientTest` uses: fully offline, deterministic, one canned response per
 * enqueue, with `takeRequest()` as the mutation-check surface. Covers the
 * token-exchange happy + failure paths, a cookie-free rotate, and the manager's
 * persistence + lazy-refresh behaviour.
 */
class NativeSessionTest {

    private val pk = "pk_test_123"
    private val clientId = "client_first_party"
    private lateinit var server: MockWebServer

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

    private fun baseUrl(): HttpUrl = AtlasClient.resolveBaseUrl(server.url("/").toString())

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    // MARK: exchangeForSession — happy path

    @Test
    fun exchangeForSessionParsesSessionAndPostsForm() = runTest {
        server.enqueue(
            jsonResponse(
                200,
                """{"access_token":"sess_jwt_1","issued_token_type":"urn:atlas:token-type:session",""" +
                    """"token_type":"Bearer","expires_in":60,"refresh_token":"rt_1","session_id":"sess_abc"}""",
            ),
        )

        val session = exchangeForSession(baseUrl(), clientId, "oauth_at_xyz", client())

        assertNotNull(session)
        assertEquals("sess_jwt_1", session!!.sessionToken)
        assertEquals("rt_1", session.refreshToken)
        assertEquals("sess_abc", session.sessionId)
        assertEquals(60, session.expiresInSeconds)

        val request = server.takeRequest()
        assertEquals("/oauth2/token", request.path)
        assertEquals("POST", request.method)
        assertTrue(
            request.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") == true,
        )
        val body = request.body.readUtf8()
        // FormBody url-encodes the values; the colons in the URNs become %3A.
        assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"))
        assertTrue(body.contains("subject_token=oauth_at_xyz"))
        assertTrue(body.contains("client_id=client_first_party"))
        assertTrue(body.contains("requested_token_type=urn%3Aatlas%3Atoken-type%3Asession"))
    }

    // MARK: exchangeForSession — failure paths fail soft (null, never throw)

    @Test
    fun exchangeForSessionReturnsNullOnNon2xx() = runTest {
        server.enqueue(jsonResponse(400, """{"error":"invalid_grant"}"""))
        assertNull(exchangeForSession(baseUrl(), clientId, "bad", client()))
    }

    @Test
    fun exchangeForSessionReturnsNullWhenSessionIdMissing() = runTest {
        // A token without a session_id is unusable — the refresh path needs the id.
        server.enqueue(jsonResponse(200, """{"access_token":"sess_jwt_1","expires_in":60}"""))
        assertNull(exchangeForSession(baseUrl(), clientId, "at", client()))
    }

    @Test
    fun exchangeForSessionReturnsNullOnTransportFailure() = runTest {
        val base = baseUrl()
        server.shutdown() // nothing is listening -> the call fails at transport
        assertNull(exchangeForSession(base, clientId, "at", client()))
    }

    // MARK: refreshNativeSession — rotates the refresh token

    @Test
    fun refreshNativeSessionRotatesRefreshToken() = runTest {
        server.enqueue(
            jsonResponse(
                200,
                """{"object":"session_tokens","jwt":"sess_jwt_2","session_id":"sess_abc","expires_in":60,"refresh_token":"rt_2"}""",
            ),
        )

        val rotated = refreshNativeSession(baseUrl(), pk, "sess_abc", "rt_1", client())

        assertNotNull(rotated)
        assertEquals("sess_jwt_2", rotated!!.sessionToken)
        assertEquals("rt_2", rotated.refreshToken) // the refresh token must rotate
        assertEquals("sess_abc", rotated.sessionId)

        val request = server.takeRequest()
        assertEquals("/v1/client/sessions/sess_abc/tokens", request.path)
        assertEquals(pk, request.getHeader("x-publishable-key"))
        assertTrue(request.body.readUtf8().contains("\"refresh_token\":\"rt_1\""))
    }

    @Test
    fun refreshKeepsPresentedTokenWhenServerOmitsRotation() = runTest {
        server.enqueue(
            jsonResponse(
                200,
                """{"object":"session_tokens","jwt":"sess_jwt_2","session_id":"sess_abc","expires_in":60}""",
            ),
        )
        val rotated = refreshNativeSession(baseUrl(), pk, "sess_abc", "rt_keep", client())
        assertEquals("rt_keep", rotated!!.refreshToken)
    }

    @Test
    fun refreshReturnsNullOnNon2xx() = runTest {
        server.enqueue(jsonResponse(401, """{"errors":[{"code":"session_expired"}]}"""))
        assertNull(refreshNativeSession(baseUrl(), pk, "sess_abc", "rt_dead", client()))
    }

    // MARK: NativeSessionManager — exchange persists to the TokenStore

    @Test
    fun managerExchangePersistsSessionToStore() = runTest {
        server.enqueue(
            jsonResponse(
                200,
                """{"access_token":"sess_jwt_1","expires_in":60,"refresh_token":"rt_1","session_id":"sess_abc"}""",
            ),
        )
        val store = InMemoryTokenStore()
        val manager = NativeSessionManager(
            publishableKey = pk,
            frontendApi = server.url("/").toString(),
            clientId = clientId,
            tokenStore = store,
            httpClient = client(),
        )

        val session = manager.exchange("oauth_at_xyz")
        assertEquals("sess_jwt_1", session!!.sessionToken)

        val stored = store.load() ?: error("expected a stored session")
        assertEquals("sess_jwt_1", stored.token)
        assertEquals("rt_1", stored.refreshToken)
        assertEquals("sess_abc", stored.sessionId)

        val headers = manager.authHeaders()
        assertEquals("Bearer sess_jwt_1", headers["Authorization"])
        assertEquals(pk, headers["x-publishable-key"])
    }

    // MARK: NativeSessionManager — token() lazily refreshes near expiry + persists

    @Test
    fun managerTokenRefreshesWhenExpiredAndPersistsRotation() = runTest {
        val store = InMemoryTokenStore()
        val manager = NativeSessionManager(
            publishableKey = pk,
            frontendApi = server.url("/").toString(),
            clientId = clientId,
            tokenStore = store,
            httpClient = client(),
        )
        // Seed a session that is already due for refresh (expires_in 0).
        manager.setSession(NativeSession("old", "rt_1", "sess_abc", 0))

        server.enqueue(
            jsonResponse(
                200,
                """{"object":"session_tokens","jwt":"fresh","session_id":"sess_abc","expires_in":60,"refresh_token":"rt_2"}""",
            ),
        )

        assertEquals("fresh", manager.token()) // rotated before it is handed out

        val stored = store.load() ?: error("expected a stored session")
        assertEquals("fresh", stored.token)
        assertEquals("rt_2", stored.refreshToken)
    }

    @Test
    fun managerTokenKeepsCurrentTokenWhenRefreshFails() = runTest {
        val manager = NativeSessionManager(
            publishableKey = pk,
            frontendApi = server.url("/").toString(),
            tokenStore = InMemoryTokenStore(),
            httpClient = client(),
        )
        manager.setSession(NativeSession("still_valid", "rt_1", "sess_abc", 0))
        server.shutdown() // the refresh fails at transport; the existing token stands
        assertEquals("still_valid", manager.token())
    }

    @Test
    fun managerTokenIsNullWhenSignedOut() = runTest {
        val manager = NativeSessionManager(
            publishableKey = pk,
            frontendApi = server.url("/").toString(),
            tokenStore = InMemoryTokenStore(),
            httpClient = client(),
        )
        assertNull(manager.token())
        assertEquals(mapOf("x-publishable-key" to pk), manager.authHeaders())
    }
}
