package net.atlasauth.atlas

import android.content.Context
import android.content.ContextWrapper
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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Device id on account-creating starts, captcha on passkey sign-in, passkey-only
 * sign-up, SMS MFA enrolment + the TOTP QR image, and the organization audit log.
 */
class BxMobileTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(store: TokenStore = InMemoryTokenStore()): AtlasClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        return AtlasClient("pk_test_bx", server.url("/").toString(), store, http).also {
            it.deviceIdProvider = { "d_test" }
        }
    }

    private fun resp(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun body(req: RecordedRequest): JsonObject = json.parseToJsonElement(req.body.readUtf8()) as JsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun deviceIdRidesOnAccountCreatingStartsOnly() = runTest {
        server.enqueue(resp(201, """{"id":"sua_1","status":"needs_email_verification"}"""))
        server.enqueue(resp(201, """{"id":"sia_1","status":"needs_first_factor","supported_first_factors":["password"]}"""))
        server.enqueue(resp(200, """{"id":"sia_o","status":"needs_first_factor","authorization_url":"https://idp.example/auth"}"""))
        val c = client()
        c.signUpFlow().create("a@b.com", "pw")
        assertEquals("d_test", body(server.takeRequest()).str("device_id"))
        c.signInFlow().create("a@b.com")
        assertNull("password sign-in never carries the device id", body(server.takeRequest())["device_id"])
        c.oauthAuthorizeUrl("google", "app://cb")
        assertEquals("d_test", body(server.takeRequest()).str("device_id"))

        val kept = DeviceId.stamp(json, "POST", "/v1/client/sign_ups", """{"device_id":"mine"}""") { "x" }
        assertEquals("""{"device_id":"mine"}""", kept)
        assertNull(DeviceId.stamp(json, "POST", "/v1/client/sign_ups", null) { "x" })
        assertTrue(DeviceId.fresh().startsWith("d_"))
        assertEquals(DeviceId.forProcess(), DeviceId.forProcess())
    }

    @Test
    fun captchaRequiredIsTyped() {
        val e = AtlasException.Api(400, listOf(AtlasErrorItem("CAPTCHA_REQUIRED", "Solve the captcha.")))
        assertEquals(AtlasErrorCode.CAPTCHA_REQUIRED, e.knownCode)
        assertTrue(e.isCaptchaRequired)
        assertEquals(mapOf("captcha_token" to "tok"), passkeySignInBeginBody("tok"))
        assertEquals(emptyMap<String, String>(), passkeySignInBeginBody(null))
    }

    @Test
    fun smsMfaEnrollmentAndTotpQr() = runTest {
        server.enqueue(resp(201, """{"id":"sia_m","status":"needs_mfa_enrollment"}"""))
        server.enqueue(resp(201, """{"factor_id":"fac_s","strategy":"phone_code","phone":"+1*******0123"}"""))
        server.enqueue(resp(200, """{"id":"sia_m","status":"complete","ticket":"tk_m","backup_codes":["aaaa"]}"""))
        val flow = client().signInFlow()
        flow.create("a@b.com")
        server.takeRequest()
        val enrollment = flow.prepareSmsMfaEnrollment("+14155550123")
        val prep = body(server.takeRequest())
        assertEquals("phone_code", prep.str("strategy"))
        assertEquals("+14155550123", prep.str("phone"))
        assertEquals("phone_code", enrollment.strategy)
        assertEquals("+1*******0123", enrollment.phone)
        assertEquals("", enrollment.secret)
        flow.attemptSmsMfaEnrollment(enrollment.factorId, "123456")
        val confirm = body(server.takeRequest())
        assertEquals("123456", confirm.str("code"))
        assertNull(confirm["codes"])

        val totp = json.decodeFromString(MfaEnrollment.serializer(), """{"factor_id":"f","secret":"S","uri":"otpauth://x","qr_code":"data:image/gif;base64,R0lGOD=="}""")
        assertEquals("totp", totp.strategy)
        assertEquals("data:image/gif;base64,R0lGOD==", totp.qrImageDataUrl)
        val unsafe = json.decodeFromString(MfaEnrollment.serializer(), """{"factor_id":"f","qr_code":"javascript:alert(1)"}""")
        assertNull(unsafe.qrImageDataUrl)
    }

    @Test
    fun passkeySignUpRunsStartBeginAndFinish() = runTest {
        server.enqueue(resp(201, """{"object":"sign_up_attempt","id":"sua_p","status":"needs_first_factor","supported_first_factors":["passkey"],"poll_secret":"ps_1"}"""))
        server.enqueue(resp(201, """{"challenge":"ch_1","rp":{"id":"clerk.example.com"},"user":{"id":"dXNlcg","name":"a@b.com"}}"""))
        server.enqueue(resp(200, """{"object":"sign_up_attempt","id":"sua_p","status":"complete","ticket":"tk_p"}"""))
        val c = client()
        val flow = PasskeySignUpFlow(c)
        flow.start(email = "a@b.com", captchaToken = "cap")
        val start = server.takeRequest()
        assertEquals("/v1/client/sign_ups/passkey", start.path)
        assertEquals("cap", body(start).str("captcha_token"))
        assertTrue(flow.awaitingPasskey)

        val fake = object : PasskeyCeremony {
            override suspend fun createCredential(activity: Context, requestJson: String): String =
                """{"id":"cred","rawId":"cred","type":"public-key","response":{"attestationObject":"att","clientDataJSON":"cdj"}}"""
            override suspend fun getCredential(activity: Context, requestJson: String): String = error("unused")
        }
        val step = PasskeyManager(c, fake).createSignUpPasskey(ContextWrapper(null), flow)
        val begin = server.takeRequest()
        assertEquals("/v1/client/sign_ups/sua_p/passkey/begin", begin.path)
        assertEquals("ps_1", body(begin).str("poll_secret"))
        val finish = server.takeRequest()
        assertEquals("/v1/client/sign_ups/sua_p/passkey/finish", finish.path)
        val fb = body(finish)
        assertEquals("ps_1", fb.str("poll_secret"))
        assertEquals("ch_1", fb.str("challenge"))
        assertEquals("att", fb.str("attestation_object"))
        assertEquals("complete", flow.attempt?.status)
        assertFalse(step is FlowStep.CollectIdentifier)
    }

    @Test
    fun organizationAuditLog() = runTest {
        server.enqueue(resp(200, """{"object":"list","data":[{"object":"audit_log","id":"al_1","action":"member.added","actor":{"type":"user","id":"u_1"},"target":{"type":"user","id":"u_2"},"organization_id":"org_1","created_at":1}],"has_more":true,"next_cursor":"al_1"}"""))
        val store = InMemoryTokenStore().also { it.save(AtlasSession("sess_1", "jwt", "rt")) }
        val page = client(store).organizationAuditLog("org_1", limit = 10, action = "member.")
        val req = server.takeRequest()
        assertEquals("/v1/client/organizations/org_1/audit_logs?limit=10&action=member.", req.path)
        assertEquals("member.added", page.data.first().action)
        assertEquals("al_1", page.nextCursor)
        assertTrue(page.hasMore)
    }
}
