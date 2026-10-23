package net.atlasauth.atlas

import android.content.Context
import android.content.ContextWrapper
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Upstream / SAML logout on sign-out, device approval from a signed-in phone, passkey PRF. */
class ByMobileTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(store: TokenStore = InMemoryTokenStore()): AtlasClient {
        val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        return AtlasClient("pk_test_1", server.url("/").toString(), store, http)
    }

    private fun signedIn() = InMemoryTokenStore().also { it.save(AtlasSession("sess_1", "jwt", "rt")) }

    private fun resp(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    private fun body(req: RecordedRequest): JsonObject = json.parseToJsonElement(req.body.readUtf8()) as JsonObject

    @Test
    fun signOutFollowsTheUpstreamLogoutUrl() = runTest {
        server.enqueue(resp(200, """{"object":"session","id":"sess_1","status":"revoked","upstream_logout_url":"https://idp.example.com/logout?id_token_hint=x","saml_logout_url":null}"""))
        val store = signedIn()
        val c = client(store)
        val opened = mutableListOf<String>()
        c.logoutUrlOpener = { opened += it }
        val result = c.signOut(returnUrl = "https://app.example.com/bye")
        val req = server.takeRequest()
        assertEquals("/v1/client/sessions/sess_1/revoke", req.path)
        assertEquals("https://app.example.com/bye", body(req)["return_url"]?.jsonPrimitive?.content)
        assertEquals("https://idp.example.com/logout?id_token_hint=x", result.upstreamLogoutUrl)
        assertEquals(listOf("https://idp.example.com/logout?id_token_hint=x"), opened)
        assertNull(store.load())
    }

    @Test
    fun signOutPrefersSamlAndDropsNonHttpsUrls() = runTest {
        server.enqueue(resp(200, """{"upstream_logout_url":"http://insecure.example.com/logout","saml_logout_url":"https://fapi.example.com/v1/sso/saml/sso_1/slo/start?request=abc"}"""))
        val c = client(signedIn())
        val opened = mutableListOf<String>()
        c.logoutUrlOpener = { opened += it }
        val result = c.signOut(followLogoutUrl = true)
        assertNull(result.upstreamLogoutUrl)
        assertEquals("https://fapi.example.com/v1/sso/saml/sso_1/slo/start?request=abc", result.logoutUrl)
        assertEquals(1, opened.size)

        server.enqueue(resp(200, """{"saml_logout_url":"https://fapi.example.com/slo"}"""))
        val c2 = client(signedIn())
        c2.logoutUrlOpener = { fail("must not open when followLogoutUrl is false") }
        assertEquals("https://fapi.example.com/slo", c2.signOut(followLogoutUrl = false).samlLogoutUrl)
    }

    @Test
    fun deviceApprovalLookupApproveDeny() = runTest {
        val da = """{"object":"device_authorization","id":"da_1","user_code":"WDJB-MJHT","status":"pending","client":{"client_id":"oc_tv","name":"Living room TV","first_party":true},"scopes":[{"scope":"openid","description":"Sign you in"}],"audience":null,"requires_step_up":false,"created_at":1,"expires_at":2}"""
        server.enqueue(resp(200, da))
        server.enqueue(resp(200, da.replace("pending", "approved")))
        server.enqueue(resp(200, da.replace("pending", "denied")))
        server.enqueue(resp(403, """{"errors":[{"code":"STEP_UP_REQUIRED","message":"Re-authenticate."}]}"""))
        val c = client(signedIn())
        val auth = c.lookupDeviceAuthorization(" wdjb mjht ")
        val lookup = server.takeRequest()
        assertEquals("/v1/client/me/device_authorizations/lookup", lookup.path)
        assertEquals("WDJB-MJHT", body(lookup)["user_code"]?.jsonPrimitive?.content)
        assertTrue(lookup.getHeader("Cookie")!!.contains("jwt"))
        assertEquals("Living room TV", auth.client.name)
        assertEquals("Sign you in", auth.scopes.first().description)
        assertEquals("approved", c.approveDeviceAuthorization(auth).status)
        assertEquals("/v1/client/me/device_authorizations/da_1/approve", server.takeRequest().path)
        assertEquals("denied", c.denyDeviceAuthorization(auth).status)
        assertEquals("/v1/client/me/device_authorizations/da_1/deny", server.takeRequest().path)
        try {
            c.approveDeviceAuthorization(auth)
            fail("expected a refusal")
        } catch (e: AtlasException.Api) {
            assertEquals("STEP_UP_REQUIRED", e.code)
        }
        assertEquals("abc", formatUserCode("abc"))
        assertEquals("WDJB-MJHT", userCodeFromUrl("https://example.com/device?user_code=wdjbmjht"))
        assertNull(userCodeFromUrl("nope"))
    }

    @Test
    fun deviceApprovalNeedsASession() = runTest {
        try {
            client().lookupDeviceAuthorization("WDJB-MJHT")
            fail("expected NotSignedIn")
        } catch (e: AtlasException.NotSignedIn) {
            // expected
        }
    }

    @Test
    fun prfExtensionIsAddedAndResultsReachOnlyTheCallback() = runTest {
        server.enqueue(resp(200, """{"challenge":"ch_1","rpId":"clerk.example.com","handle":"h_1","allowCredentials":[]}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("content-type", "application/json")
                .addHeader("Set-Cookie", "__atlas_rt=rt_2; Path=/")
                .setBody("""{"object":"sign_in_attempt","id":"sia_1","status":"complete","created_session_id":"sess_2","jwt":"jwt_2","user":{"object":"user","id":"user_1"}}"""),
        )
        server.enqueue(resp(200, """{"object":"user","id":"user_1"}"""))
        val first = ByteArray(32) { 7 }
        val firstB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(first)
        var seenRequest = ""
        val fake = object : PasskeyAuthenticator {
            override suspend fun createCredential(activity: Context, requestJson: String): String = error("unused")
            override suspend fun getCredential(activity: Context, requestJson: String): String {
                seenRequest = requestJson
                return """{"id":"cred","rawId":"cred","type":"public-key","response":{"clientDataJSON":"cdj","authenticatorData":"ad","signature":"sig"},"clientExtensionResults":{"prf":{"results":{"first":"$firstB64"}}}}"""
            }
        }
        var result: PasskeyPrfResult? = null
        runCatching {
            PasskeyManager(client(), fake).signInWithPasskey(
                ContextWrapper(null),
                prf = PasskeyPrf.ofSalt("my-app-key"),
                onPrfResult = { result = it },
            )
        }
        val ext = json.parseToJsonElement(seenRequest).jsonObject["extensions"]!!.jsonObject["prf"]!!.jsonObject
        val salt = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("my-app-key".toByteArray())
        assertEquals(salt, ext["eval"]!!.jsonObject["first"]!!.jsonPrimitive.content)
        server.takeRequest() // begin
        val finish = server.takeRequest()
        val sent = finish.body.readUtf8()
        assertFalse("PRF output must never be sent to Atlas", sent.contains(firstB64) || sent.contains("prf"))
        assertEquals("cred", result?.credentialId)
        assertArrayEquals(first, result?.first)
    }

    @Test
    fun prfHelpersArePure() {
        val request = """{"challenge":"c","extensions":{"credProps":true}}"""
        assertEquals(request, withPrfExtension(request, null, registration = true))
        val withPrf = json.parseToJsonElement(
            withPrfExtension(
                request,
                PasskeyPrf(byteArrayOf(1), byteArrayOf(2), mapOf("cred" to (byteArrayOf(3) to null))),
                registration = false,
            ),
        ).jsonObject
        val ext = withPrf["extensions"]!!.jsonObject
        assertTrue(ext.containsKey("credProps"))
        assertEquals("Ag", ext["prf"]!!.jsonObject["eval"]!!.jsonObject["second"]!!.jsonPrimitive.content)
        assertEquals("Aw", ext["prf"]!!.jsonObject["evalByCredential"]!!.jsonObject["cred"]!!.jsonObject["first"]!!.jsonPrimitive.content)
        val reg = readPrfResult("""{"id":"c1","clientExtensionResults":{"prf":{"enabled":true}}}""")
        assertEquals(true, reg?.enabled)
        assertNull(reg?.first)
        assertNull(readPrfResult("""{"id":"c1","clientExtensionResults":{}}"""))
    }
}
