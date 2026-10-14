package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** MockWebServer tests for the organizations / sessions / `/me`-mutation surface. */
class AccountTest {

    private val pk = "pk_test_acct"
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    // A signed-in client: an authenticated call presents the stored session cookie.
    private fun signedInClient(): AtlasClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val store = InMemoryTokenStore(AtlasSession("sess_1", "jwt_1", "rt_1"))
        return AtlasClient(pk, server.url("/").toString(), store, http)
    }

    private fun resp(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun body(req: RecordedRequest): JsonObject =
        json.parseToJsonElement(req.body.readUtf8()) as JsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun listsOrganizationMembershipsAndPresentsCookie() = runTest {
        server.enqueue(
            resp(
                200,
                """{"object":"list","data":[
                  {"object":"organization_membership","role":"admin","organization":{"object":"organization","id":"org_1","name":"Acme","slug":"acme","image_url":null,"public_metadata":{"tier":"pro"}}}
                ]}""",
            ),
        )
        val memberships = signedInClient().listOrganizationMemberships()
        assertEquals(1, memberships.size)
        assertEquals("admin", memberships[0].role)
        assertEquals("org_1", memberships[0].organization.id)
        assertEquals("acme", memberships[0].organization.slug)
        assertEquals("pro", memberships[0].organization.publicMetadata?.get("tier")?.stringValue)

        val req = server.takeRequest()
        assertEquals("/v1/client/me/organizations", req.path)
        assertEquals("GET", req.method)
        assertEquals(pk, req.getHeader("x-publishable-key"))
        assertTrue((req.getHeader("Cookie") ?: "").contains("__atlas_rt=rt_1"))
    }

    @Test
    fun createsOrganization() = runTest {
        server.enqueue(resp(201, """{"object":"organization","id":"org_2","name":"Beta","slug":"beta"}"""))
        val org = signedInClient().createOrganization("Beta", "beta")
        assertEquals("org_2", org.id)
        val req = server.takeRequest()
        assertEquals("/v1/client/organizations", req.path)
        val b = body(req)
        assertEquals("Beta", b.str("name"))
        assertEquals("beta", b.str("slug"))
    }

    @Test
    fun listsDeviceSessions() = runTest {
        server.enqueue(
            resp(
                200,
                """{"object":"list","data":[
                  {"object":"session","id":"sess_1","status":"active","current":true,"browser":"Chrome","os":"macOS"},
                  {"object":"session","id":"sess_2","status":"active","current":false}
                ]}""",
            ),
        )
        val sessions = signedInClient().listSessions()
        assertEquals(2, sessions.size)
        assertEquals("sess_1", sessions[0].id)
        assertEquals(true, sessions[0].current)
        assertEquals("Chrome", sessions[0].browser)
        assertEquals("/v1/client/sessions", server.takeRequest().path)
    }

    @Test
    fun revokesOneSessionAndAllOthers() = runTest {
        server.enqueue(resp(200, """{"object":"session","id":"sess_2","status":"revoked"}"""))
        server.enqueue(resp(200, """{"object":"client","sessions_revoked":3}"""))

        val client = signedInClient()
        client.revokeSession("sess_2")
        val revoked = client.revokeOtherSessions()
        assertEquals(3, revoked)

        assertEquals("/v1/client/sessions/sess_2/revoke", server.takeRequest().path)
        assertEquals("/v1/client/sessions/revoke_all", server.takeRequest().path)
    }

    @Test
    fun updateProfileSendsPatchWithUnsafeMetadataOnly() = runTest {
        server.enqueue(resp(200, """{"object":"user","id":"user_1","first_name":"Ada","created_at":1}"""))
        val user = signedInClient().updateProfile(
            firstName = "Ada",
            unsafeMetadata = mapOf("theme" to JsonValue.Str("dark")),
        )
        assertEquals("user_1", user.id)

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/v1/client/me", req.path)
        val b = body(req)
        assertEquals("Ada", b.str("first_name"))
        assertEquals("dark", b["unsafe_metadata"]?.jsonObject?.get("theme")?.jsonPrimitive?.content)
        // A field not passed is absent (null leaves it unchanged server-side).
        assertTrue(!b.containsKey("last_name"))
        assertTrue(!b.containsKey("public_metadata"))
    }

    @Test
    fun addsEmailAddress() = runTest {
        server.enqueue(resp(201, """{"object":"email_address","id":"email_2","email_address":"new@x.com","verified":false,"primary":false}"""))
        val email = signedInClient().addEmailAddress("new@x.com")
        assertEquals("email_2", email.id)
        assertEquals("new@x.com", email.emailAddress)
        val req = server.takeRequest()
        assertEquals("/v1/client/me/email_addresses", req.path)
        assertEquals("new@x.com", body(req).str("email_address"))
    }

    @Test
    fun verifyEmailAddressToleratesPartialResponse() = runTest {
        // The verify response omits `primary`; the model defaults it rather than failing to decode.
        server.enqueue(resp(200, """{"object":"email_address","id":"email_2","email_address":"new@x.com","verified":true}"""))
        val email = signedInClient().verifyEmailAddress("email_2", "123456")
        assertEquals(true, email.verified)
        assertEquals(false, email.primary)
    }

    @Test
    fun changePasswordReturnsSessionsRevokedCount() = runTest {
        server.enqueue(resp(200, """{"object":"user","id":"user_1","sessions_revoked":4}"""))
        val revoked = signedInClient().changePassword("old-pw", "new-pw")
        assertEquals(4, revoked)
        val req = server.takeRequest()
        assertEquals("/v1/client/me/change_password", req.path)
        val b = body(req)
        assertEquals("old-pw", b.str("current_password"))
        assertEquals("new-pw", b.str("new_password"))
    }

    @Test
    fun connectExternalAccountReturnsAuthorizationUrl() = runTest {
        server.enqueue(
            resp(
                201,
                """{"object":"external_account_connection","provider":"github","attempt_id":"att_1","authorization_url":"https://github.com/login/oauth/authorize?x=1","scopes":["read:user"]}""",
            ),
        )
        val conn = signedInClient().connectExternalAccount("github", "myapp://cb", listOf("read:user"))
        assertEquals("github", conn.provider)
        assertTrue(conn.authorizationUrl.startsWith("https://github.com/"))
        assertEquals(listOf("read:user"), conn.scopes)
        val req = server.takeRequest()
        assertEquals("/v1/client/me/external_accounts/connect", req.path)
        assertEquals("myapp://cb", body(req).str("redirect_url"))
    }
}
