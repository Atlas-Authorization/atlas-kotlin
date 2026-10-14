package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests for the observable [AtlasSessionState] — the non-UI half of the Compose
 * surface, so its transitions are verified without a device.
 */
class SessionStateTest {

    private val pk = "pk_test_state"
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(store: TokenStore): AtlasClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        return AtlasClient(pk, server.url("/").toString(), store, http)
    }

    private fun resp(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun startsLoading() = runTest {
        val state = AtlasSessionState(client(InMemoryTokenStore()), backgroundScope)
        assertEquals(AtlasSessionStatus.Loading, state.status.value)
    }

    @Test
    fun reloadWithNoSessionIsSignedOut() = runTest {
        val state = AtlasSessionState(client(InMemoryTokenStore()), backgroundScope)
        state.reload()
        assertEquals(AtlasSessionStatus.SignedOut, state.status.value)
    }

    @Test
    fun reloadWithSessionBecomesSignedIn() = runTest {
        server.enqueue(resp(200, """{"object":"user","id":"user_1","first_name":"Ada","created_at":1}"""))
        val store = InMemoryTokenStore(AtlasSession("sess_1", "jwt_1", "rt_1"))
        val state = AtlasSessionState(client(store), backgroundScope)

        state.reload()
        val status = state.status.value
        assertTrue(status is AtlasSessionStatus.SignedIn)
        assertEquals("user_1", (status as AtlasSessionStatus.SignedIn).user.id)
        assertEquals("user_1", state.user?.id)
    }

    @Test
    fun rejectedSessionBecomesSignedOut() = runTest {
        server.enqueue(resp(401, """{"errors":[{"code":"authentication_invalid","message":"Signed out."}]}"""))
        val store = InMemoryTokenStore(AtlasSession("sess_1", "jwt_1", "rt_1"))
        val state = AtlasSessionState(client(store), backgroundScope)
        state.reload()
        assertEquals(AtlasSessionStatus.SignedOut, state.status.value)
    }

    @Test
    fun displayNamePrefersFullNameThenUsernameThenId() {
        assertEquals("Ada Lovelace", displayName(AtlasUser(id = "u", firstName = "Ada", lastName = "Lovelace")))
        assertEquals("ada", displayName(AtlasUser(id = "u", username = "ada")))
        assertEquals("u", displayName(AtlasUser(id = "u")))
    }
}
