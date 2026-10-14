package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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

/** MockWebServer-driven tests for the multi-step sign-in flow driver. Fully offline. */
class SignInFlowTest {

    private val pk = "pk_test_flow"
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

    @Test
    fun passwordFlowCreatesAttemptAttemptsFirstFactorAndCompletes() = runTest {
        server.enqueue(resp(201, """{"id":"sia_1","status":"needs_first_factor","supported_first_factors":["password"]}"""))
        server.enqueue(resp(200, """{"id":"sia_1","status":"complete","ticket":"tk_1"}"""))
        server.enqueue(resp(200, """{"object":"session","id":"sess_1","jwt":"jwt_1","expires_in":60}""", "__atlas_rt=rt_1; Path=/v1; HttpOnly"))
        server.enqueue(resp(200, userJson))

        val store = InMemoryTokenStore()
        val flow = client(store).signInFlow()

        val step1 = flow.create("ada@example.com")
        assertTrue(step1 is FlowStep.CollectFirstFactor)
        assertEquals(listOf("password"), (step1 as FlowStep.CollectFirstFactor).strategies)

        val step2 = flow.attemptPassword("hunter2")
        assertTrue(step2 is FlowStep.Done)

        val user = flow.complete()
        assertEquals("user_1", user.id)

        // Step bodies + paths.
        val r0 = server.takeRequest()
        assertEquals("/v1/client/sign_ins", r0.path)
        assertEquals("ada@example.com", body(r0).str("identifier"))

        val r1 = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_1/attempt_first_factor", r1.path)
        val b1 = body(r1)
        assertEquals("password", b1.str("strategy"))
        assertEquals("hunter2", b1.str("password"))

        val r2 = server.takeRequest()
        assertEquals("/v1/client/tickets/exchange", r2.path)
        val b2 = body(r2)
        assertEquals("sia_1", b2.str("attempt_id"))
        assertEquals("tk_1", b2.str("ticket"))

        // Session persisted from the exchange.
        val stored = store.load() ?: error("expected a stored session")
        assertEquals("jwt_1", stored.token)
        assertEquals("rt_1", stored.refreshToken)
        assertEquals("sess_1", stored.sessionId)
    }

    @Test
    fun emailCodeFirstFactorSendsPrepareThenAttempt() = runTest {
        server.enqueue(resp(201, """{"id":"sia_2","status":"needs_first_factor"}"""))
        server.enqueue(resp(200, """{"id":"sia_2","status":"needs_first_factor","strategy":"email_code","poll_secret":"ps"}"""))
        server.enqueue(resp(200, """{"id":"sia_2","status":"complete","ticket":"tk_2"}"""))

        val flow = client().signInFlow()
        flow.create("ada@example.com")
        flow.prepareEmailCode()
        val step = flow.attemptEmailCode("123456")
        assertTrue(step is FlowStep.Done)

        server.takeRequest() // create
        val prep = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_2/prepare_first_factor", prep.path)
        assertEquals("email_code", body(prep).str("strategy"))
        val att = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_2/attempt_first_factor", att.path)
        val attBody = body(att)
        assertEquals("email_code", attBody.str("strategy"))
        assertEquals("123456", attBody.str("code"))
    }

    @Test
    fun secondFactorAdvancesFromNeedsSecondFactor() = runTest {
        server.enqueue(resp(201, """{"id":"sia_3","status":"needs_first_factor"}"""))
        server.enqueue(resp(200, """{"id":"sia_3","status":"needs_second_factor"}"""))
        server.enqueue(resp(200, """{"id":"sia_3","status":"complete","ticket":"tk_3"}"""))

        val flow = client().signInFlow()
        flow.create("ada@example.com")
        val afterPw = flow.attemptPassword("pw")
        assertTrue(afterPw is FlowStep.CollectSecondFactor)

        val done = flow.attemptSecondFactor("654321", rememberDevice = true)
        assertTrue(done is FlowStep.Done)

        server.takeRequest(); server.takeRequest()
        val sf = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_3/attempt_second_factor", sf.path)
        val sfBody = body(sf)
        assertEquals("654321", sfBody.str("code"))
        assertEquals(true, sfBody["remember_device"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun prepareSecondFactorDecodesSmsChallenge() = runTest {
        server.enqueue(resp(201, """{"id":"sia_4","status":"needs_first_factor"}"""))
        server.enqueue(resp(200, """{"id":"sia_4","status":"needs_second_factor"}"""))
        server.enqueue(resp(201, """{"object":"second_factor_challenge","strategy":"sms","sent_to":"+1••••1234"}"""))

        val flow = client().signInFlow()
        flow.create("a@b.com"); flow.attemptPassword("pw")
        val challenge = flow.prepareSecondFactor("sms")
        assertEquals("sms", challenge.strategy)
        assertEquals("+1••••1234", challenge.sentTo)

        server.takeRequest(); server.takeRequest()
        val prep = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_4/prepare_second_factor", prep.path)
        assertEquals("sms", body(prep).str("strategy"))
    }

    @Test
    fun mfaEnrollmentPreparesAndConfirmsWithCodesArray() = runTest {
        server.enqueue(resp(201, """{"id":"sia_5","status":"needs_first_factor"}"""))
        server.enqueue(resp(200, """{"id":"sia_5","status":"needs_mfa_enrollment"}"""))
        server.enqueue(resp(201, """{"object":"mfa_enrollment","factor_id":"mfa_1","secret":"ABC","uri":"otpauth://x"}"""))
        server.enqueue(resp(200, """{"id":"sia_5","status":"complete","ticket":"tk_5","backup_codes":["a1","b2"]}"""))

        val flow = client().signInFlow()
        flow.create("a@b.com")
        val afterPw = flow.attemptPassword("pw")
        assertTrue(afterPw is FlowStep.EnrollSecondFactor)

        val enrollment = flow.prepareMfaEnrollment()
        assertEquals("mfa_1", enrollment.factorId)
        assertEquals("ABC", enrollment.secret)

        val done = flow.attemptMfaEnrollment("mfa_1", listOf("111111", "222222"))
        assertTrue(done is FlowStep.Done)
        assertEquals(listOf("a1", "b2"), flow.attempt?.backupCodes)

        server.takeRequest(); server.takeRequest()
        val prep = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_5/prepare_mfa_enrollment", prep.path)
        val conf = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_5/attempt_mfa_enrollment", conf.path)
        val confBody = body(conf)
        assertEquals("mfa_1", confBody.str("factor_id"))
        assertEquals(listOf("111111", "222222"), confBody["codes"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun completeBeforeDoneThrows() = runTest {
        server.enqueue(resp(201, """{"id":"sia_6","status":"needs_first_factor"}"""))
        val flow = client().signInFlow()
        flow.create("a@b.com")
        try {
            flow.complete()
            fail("expected an AtlasException")
        } catch (e: AtlasException) {
            assertEquals("sign_in_not_complete", e.code)
        }
    }

    @Test
    fun attemptBeforeCreateThrowsDecoding() = runTest {
        val flow = client().signInFlow()
        try {
            flow.attemptPassword("pw")
            fail("expected a Decoding error")
        } catch (e: AtlasException.Decoding) {
            assertTrue(e.detail.contains("create()"))
        }
        assertNull(flow.attempt)
    }

    companion object {
        val userJson = """{"object":"user","id":"user_1","first_name":"Ada","created_at":1700000000000}"""
    }
}
