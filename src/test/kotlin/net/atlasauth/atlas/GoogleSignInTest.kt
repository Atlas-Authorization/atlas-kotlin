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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Tests for the native Google / id_token exchange. The device ceremony sits behind a seam. */
class GoogleSignInTest {

    private val pk = "pk_test_gid"
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(store: TokenStore = InMemoryTokenStore()): AtlasClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        return AtlasClient(pk, server.url("/").toString(), store, http)
    }

    private fun resp(code: Int, body: String, setCookie: String? = null): MockResponse {
        val r = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
        if (setCookie != null) r.addHeader("Set-Cookie", setCookie)
        return r
    }

    private fun body(req: RecordedRequest): JsonObject =
        json.parseToJsonElement(req.body.readUtf8()) as JsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    // MARK: pure body builder

    @Test
    fun idTokenBodyOmitsNonceWhenAbsent() {
        val obj = json.parseToJsonElement(idTokenBody("google", "tok", null)) as JsonObject
        assertEquals("google", obj.str("provider"))
        assertEquals("tok", obj.str("id_token"))
        assertFalse(obj.containsKey("nonce"))
    }

    @Test
    fun idTokenBodyIncludesNonceWhenGiven() {
        val obj = json.parseToJsonElement(idTokenBody("google", "tok", "n0nce")) as JsonObject
        assertEquals("n0nce", obj.str("nonce"))
    }

    // MARK: nonce mint

    @Test
    fun mintNonceReturnsServerNonce() = runTest {
        server.enqueue(resp(201, """{"object":"native_nonce","nonce":"NONCE_1"}"""))
        val nonce = client().mintIdTokenNonce("google")
        assertEquals("NONCE_1", nonce)
        val req = server.takeRequest()
        assertEquals("/v1/client/sign_ins/id_token/nonce", req.path)
        assertEquals("google", body(req).str("provider"))
    }

    // MARK: exchange → session

    @Test
    fun signInWithIdTokenExchangesTicketAndStoresSession() = runTest {
        server.enqueue(resp(201, """{"object":"sign_in_attempt","id":"sia_g","status":"complete","ticket":"tk_g"}"""))
        server.enqueue(resp(200, """{"object":"session","id":"sess_g","jwt":"jwt_g","expires_in":60}""", "__atlas_rt=rt_g; Path=/v1; HttpOnly"))
        server.enqueue(resp(200, """{"object":"user","id":"user_g","created_at":1}"""))

        val store = InMemoryTokenStore()
        val user = client(store).signInWithIdToken("google", "goog_id_token", "NONCE_1")
        assertEquals("user_g", user.id)

        val r0 = server.takeRequest()
        assertEquals("/v1/client/sign_ins/id_token", r0.path)
        val b0 = body(r0)
        assertEquals("google", b0.str("provider"))
        assertEquals("goog_id_token", b0.str("id_token"))
        assertEquals("NONCE_1", b0.str("nonce"))

        assertEquals("/v1/client/tickets/exchange", server.takeRequest().path)
        assertEquals("jwt_g", store.load()?.token)
        assertEquals("rt_g", store.load()?.refreshToken)
    }

    @Test
    fun signInWithIdTokenIncompleteThrowsNotComplete() = runTest {
        server.enqueue(resp(201, """{"object":"sign_in_attempt","id":"sia_g","status":"needs_second_factor"}"""))
        try {
            client().signInWithIdToken("google", "goog_id_token")
            fail("expected an AtlasException")
        } catch (e: AtlasException) {
            assertEquals("sign_in_not_complete", e.code)
        }
    }

    @Test
    fun mintNonceReturnsNullOnEmptyBody() = runTest {
        server.enqueue(resp(201, """{"object":"native_nonce"}"""))
        assertNull(client().mintIdTokenNonce("google"))
    }
}
