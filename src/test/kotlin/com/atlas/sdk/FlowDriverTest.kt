package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The multi-step flow driver, id_token exchange, orgs/sessions and /me mutations. */
class FlowDriverTest {
    private val pk = "pk_test_123"

    private fun client(store: TokenStore, transport: FakeTransport): AtlasClient =
        AtlasClient(
            publishableKey = pk,
            frontendApi = "clerk.example.com",
            tokenStore = store,
            transport = transport,
            dispatcher = Dispatchers.Unconfined,
        )

    private fun body(request: HttpRequest): JsonValue = JsonValue.parse(request.body!!)

    // ---- nextStep: exhaustive status mapping ---------------------------------

    @Test
    fun nextStepMapsEveryKnownStatusAndFallsBackToUnknown() {
        fun attempt(status: String, factors: List<String>? = null, session: String? = null) =
            SignInAttempt("sia", status, null, factors, null, session, null)

        assertEquals(SignInStep.CollectIdentifier, nextStep(attempt("needs_identifier")))
        assertEquals(
            SignInStep.CollectFirstFactor(listOf("password", "email_code")),
            nextStep(attempt("needs_first_factor", listOf("password", "email_code"))),
        )
        assertEquals(SignInStep.CollectSecondFactor, nextStep(attempt("needs_second_factor")))
        assertEquals(SignInStep.EnrollSecondFactor, nextStep(attempt("needs_mfa_enrollment")))
        assertEquals(SignInStep.CollectEmailCode, nextStep(attempt("needs_email_verification")))
        assertEquals(SignInStep.CollectCaptcha, nextStep(attempt("needs_captcha")))
        assertEquals(SignInStep.AwaitOAuth, nextStep(attempt("needs_oauth_callback")))
        assertEquals(SignInStep.CollectNewPassword, nextStep(attempt("needs_new_password")))
        assertEquals(SignInStep.Done("sess_7"), nextStep(attempt("complete", session = "sess_7")))
        assertEquals(SignInStep.Restart("abandoned"), nextStep(attempt("abandoned")))
        // A status a future server adds, that this SDK version has not seen.
        assertEquals(SignInStep.Unknown("needs_teleport"), nextStep(attempt("needs_teleport")))
        assertTrue(isTerminal(attempt("complete")))
        assertTrue(isTerminal(attempt("abandoned")))
        assertTrue(!isTerminal(attempt("needs_first_factor")))
    }

    // ---- sign-in flow: password happy path -----------------------------------

    @Test
    fun signInFlowPasswordWalksEndpointsAndPersistsOnComplete() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_1","status":"needs_first_factor","supported_first_factors":["password","email_code"]}""")
        transport.enqueue(200, """{"id":"sia_1","status":"complete","ticket":"tk_1","created_session_id":"sess_1"}""")
        transport.enqueue(200, """{"object":"session","id":"sess_1","jwt":"jwt_abc","expires_in":60}""", setCookie = "__atlas_rt=rt_1; Path=/; HttpOnly")
        transport.enqueue(200, AtlasClientTest.USER_JSON)

        val store = InMemoryTokenStore()
        val flow = client(store, transport).beginSignIn("a@b.com")

        assertEquals("https://clerk.example.com/v1/client/sign_ins", transport.recorded[0].url)
        assertEquals("a@b.com", body(transport.recorded[0]).string("identifier"))
        assertEquals(SignInStep.CollectFirstFactor(listOf("password", "email_code")), flow.step)

        val step = flow.attemptPassword("hunter2")
        assertEquals("https://clerk.example.com/v1/client/sign_ins/sia_1/attempt_first_factor", transport.recorded[1].url)
        assertEquals("password", body(transport.recorded[1]).string("strategy"))
        assertEquals("hunter2", body(transport.recorded[1]).string("password"))
        assertEquals(SignInStep.Done("sess_1"), step)
        assertTrue(flow.isComplete)

        val user = flow.complete()
        assertEquals("https://clerk.example.com/v1/client/tickets/exchange", transport.recorded[2].url)
        assertEquals("sia_1", body(transport.recorded[2]).string("attempt_id"))
        assertEquals("tk_1", body(transport.recorded[2]).string("ticket"))
        assertEquals("user_1", user.id)

        val stored = assertNotNull(store.load())
        assertEquals("jwt_abc", stored.token)
        assertEquals("rt_1", stored.refreshToken)
    }

    @Test
    fun completeBeforeTerminalThrows() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_1","status":"needs_first_factor"}""")
        val flow = client(InMemoryTokenStore(), transport).beginSignIn("a@b.com")
        val error = assertFailsWith<AtlasException> { flow.complete() }.error
        assertEquals("sign_in_not_complete", error.code)
    }

    // ---- sign-in flow: email code --------------------------------------------

    @Test
    fun signInFlowEmailCodePreparesThenAttempts() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_2","status":"needs_first_factor","supported_first_factors":["email_code"]}""")
        transport.enqueue(200, """{"object":"sign_in_attempt","id":"sia_2","status":"needs_first_factor","strategy":"email_code","poll_secret":"ps_9"}""")
        transport.enqueue(200, """{"id":"sia_2","status":"complete","ticket":"tk_2","created_session_id":"sess_2"}""")

        val flow = client(InMemoryTokenStore(), transport).beginSignIn("a@b.com")
        val prepared = flow.prepareFirstFactor("email_code")
        assertEquals("https://clerk.example.com/v1/client/sign_ins/sia_2/prepare_first_factor", transport.recorded[1].url)
        assertEquals("email_code", body(transport.recorded[1]).string("strategy"))
        assertEquals("ps_9", prepared.pollSecret)

        val step = flow.attemptEmailCode("123456")
        assertEquals("email_code", body(transport.recorded[2]).string("strategy"))
        assertEquals("123456", body(transport.recorded[2]).string("code"))
        assertEquals(SignInStep.Done("sess_2"), step)
    }

    @Test
    fun phoneCodePrepareCarriesChannel() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_p","status":"needs_first_factor"}""")
        transport.enqueue(200, """{"object":"sign_in_attempt","id":"sia_p","status":"needs_first_factor","strategy":"phone_code","poll_secret":"ps_p"}""")

        val flow = client(InMemoryTokenStore(), transport).beginSignIn("a@b.com")
        flow.prepareFirstFactor("phone_code", channel = "whatsapp")
        assertEquals("phone_code", body(transport.recorded[1]).string("strategy"))
        assertEquals("whatsapp", body(transport.recorded[1]).string("channel"))
    }

    // ---- sign-in flow: second factor -----------------------------------------

    @Test
    fun signInFlowSecondFactorTotpCompletes() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_3","status":"needs_first_factor","supported_first_factors":["password"]}""")
        transport.enqueue(200, """{"id":"sia_3","status":"needs_second_factor"}""")
        transport.enqueue(200, """{"id":"sia_3","status":"complete","ticket":"tk_3","created_session_id":"sess_3"}""")

        val flow = client(InMemoryTokenStore(), transport).beginSignIn("a@b.com")
        assertEquals(SignInStep.CollectSecondFactor, flow.attemptPassword("pw"))

        val step = flow.attemptSecondFactor("000111")
        assertEquals("https://clerk.example.com/v1/client/sign_ins/sia_3/attempt_second_factor", transport.recorded[2].url)
        assertEquals("000111", body(transport.recorded[2]).string("code"))
        // remember_device defaults off, so it is omitted from the body.
        assertNull(body(transport.recorded[2]).bool("remember_device"))
        assertEquals(SignInStep.Done("sess_3"), step)
    }

    @Test
    fun rememberDeviceIsSentWhenRequested() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_r","status":"needs_second_factor"}""")
        // Seed directly at the second factor via id_token for brevity.
        transport.enqueue(200, """{"id":"sia_r","status":"complete","ticket":"tk_r"}""")
        val flow = client(InMemoryTokenStore(), transport).signInWithIdToken("google", "idtok")
        flow.attemptSecondFactor("424242", rememberDevice = true)
        assertEquals(true, body(transport.recorded[1]).bool("remember_device"))
    }

    @Test
    fun prepareSecondFactorSmsReturnsMaskedNumber() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_s","status":"needs_second_factor"}""")
        transport.enqueue(201, """{"object":"second_factor_challenge","strategy":"sms","sent_to":"+1 ••• ••• 1234"}""")
        val flow = client(InMemoryTokenStore(), transport).signInWithIdToken("google", "idtok")
        val challenge = flow.prepareSecondFactor("sms")
        assertEquals("sms", body(transport.recorded[1]).string("strategy"))
        assertEquals("sms", challenge.strategy)
        assertEquals("+1 ••• ••• 1234", challenge.sentTo)
    }

    @Test
    fun prepareSecondFactorPushReturnsNumberMatch() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_pu","status":"needs_second_factor"}""")
        transport.enqueue(201, """{"object":"second_factor_challenge","strategy":"push","challenge_id":"ch_1","number_match":42,"expires_at":1700000000000}""")
        val flow = client(InMemoryTokenStore(), transport).signInWithIdToken("google", "idtok")
        val challenge = flow.prepareSecondFactor("push")
        assertEquals("ch_1", challenge.challengeId)
        assertEquals(42, challenge.numberMatch)
    }

    // ---- sign-in flow: MFA enrollment ----------------------------------------

    @Test
    fun mfaEnrollmentReturnsSecretThenBackupCodes() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_4","status":"needs_first_factor","supported_first_factors":["password"]}""")
        transport.enqueue(200, """{"id":"sia_4","status":"needs_mfa_enrollment"}""")
        transport.enqueue(201, """{"object":"mfa_enrollment","factor_id":"mfa_1","secret":"ABCDEF","uri":"otpauth://totp/Atlas:a?secret=ABCDEF"}""")
        transport.enqueue(200, """{"id":"sia_4","status":"complete","ticket":"tk_4","backup_codes":["aaa-111","bbb-222"]}""")

        val flow = client(InMemoryTokenStore(), transport).beginSignIn("a@b.com")
        assertEquals(SignInStep.EnrollSecondFactor, flow.attemptPassword("pw"))

        val enrollment = flow.prepareMfaEnrollment()
        assertEquals("https://clerk.example.com/v1/client/sign_ins/sia_4/prepare_mfa_enrollment", transport.recorded[2].url)
        assertEquals("mfa_1", enrollment.factorId)
        assertEquals("ABCDEF", enrollment.secret)

        val backupCodes = flow.attemptMfaEnrollment("mfa_1", listOf("111222"))
        assertEquals("mfa_1", body(transport.recorded[3]).string("factor_id"))
        assertEquals("111222", (body(transport.recorded[3]).arr("codes")!!.items[0] as JsonValue.Str).value)
        assertEquals(listOf("aaa-111", "bbb-222"), backupCodes)
        assertTrue(flow.isComplete)
    }

    // ---- id_token ------------------------------------------------------------

    @Test
    fun idTokenBodyAndCompletePath() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"sign_in_attempt","id":"sia_id","status":"complete","ticket":"tk_id","created_session_id":"sess_id"}""")
        transport.enqueue(200, """{"object":"session","id":"sess_id","jwt":"jwt_id","expires_in":60}""", setCookie = "__atlas_rt=rt_id; Path=/; HttpOnly")
        transport.enqueue(200, AtlasClientTest.USER_JSON)

        val store = InMemoryTokenStore()
        val flow = client(store, transport).signInWithIdToken("google", "eyJ.id.token", nonce = "n_1")

        assertEquals("https://clerk.example.com/v1/client/sign_ins/id_token", transport.recorded[0].url)
        val sent = body(transport.recorded[0])
        assertEquals("google", sent.string("provider"))
        assertEquals("eyJ.id.token", sent.string("id_token"))
        assertEquals("n_1", sent.string("nonce"))
        assertEquals(SignInStep.Done("sess_id"), flow.step)

        val user = flow.complete()
        assertEquals("user_1", user.id)
        assertEquals("jwt_id", store.load()?.token)
    }

    @Test
    fun idTokenNeedsSecondFactorStopsAtCollectSecondFactor() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"sign_in_attempt","id":"sia_id2","status":"needs_second_factor"}""")
        val flow = client(InMemoryTokenStore(), transport).signInWithIdToken("apple", "tok")
        assertEquals(SignInStep.CollectSecondFactor, flow.step)
        assertTrue(!flow.isComplete)
    }

    @Test
    fun mintNativeNonceReturnsNonce() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"native_nonce","nonce":"nnc_1"}""")
        val nonce = client(InMemoryTokenStore(), transport).mintNativeNonce("google")
        assertEquals("https://clerk.example.com/v1/client/sign_ins/id_token/nonce", transport.recorded[0].url)
        assertEquals("google", body(transport.recorded[0]).string("provider"))
        assertEquals("nnc_1", nonce)
    }

    // ---- sign-up flow --------------------------------------------------------

    @Test
    fun signUpFlowVerifiesThenCompletes() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"sign_up_attempt","id":"su_1","status":"needs_email_verification"}""")
        transport.enqueue(200, """{"object":"sign_up_attempt","id":"su_1","status":"complete","ticket":"tk_su","created_session_id":"sess_su"}""")
        transport.enqueue(200, """{"object":"session","id":"sess_su","jwt":"jwt_su","expires_in":60}""", setCookie = "__atlas_rt=rt_su; Path=/; HttpOnly")
        transport.enqueue(200, AtlasClientTest.USER_JSON)

        val store = InMemoryTokenStore()
        val flow = client(store, transport).beginSignUp("new@b.com", "hunter2", consent = true)
        assertEquals("new@b.com", body(transport.recorded[0]).string("email"))
        assertEquals("hunter2", body(transport.recorded[0]).string("password"))
        assertEquals(true, body(transport.recorded[0]).bool("consent"))
        assertEquals(SignUpStep.CollectEmailCode, flow.step)

        val step = flow.attemptVerification("654321")
        assertEquals("https://clerk.example.com/v1/client/sign_ups/su_1/attempt_verification", transport.recorded[1].url)
        assertEquals("654321", body(transport.recorded[1]).string("code"))
        assertEquals(SignUpStep.Done("sess_su"), step)

        val user = flow.complete()
        assertEquals("user_1", user.id)
        assertEquals("jwt_su", store.load()?.token)
    }

    @Test
    fun signUpFieldsSerializeAsNestedObject() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"sign_up_attempt","id":"su_2","status":"needs_email_verification"}""")
        client(InMemoryTokenStore(), transport)
            .beginSignUp("x@b.com", "pw", fields = mapOf("first_name" to "Ada", "company" to "Analytical"))
        val fields = body(transport.recorded[0]).obj("fields")!!
        assertEquals("Ada", fields.string("first_name"))
        assertEquals("Analytical", fields.string("company"))
    }

    // ---- password reset flow -------------------------------------------------

    @Test
    fun passwordResetFlowResetsAndSignsIn() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"password_reset_attempt","id":"pr_1","status":"needs_email_verification"}""")
        transport.enqueue(200, """{"object":"password_reset_attempt","id":"pr_1","status":"needs_new_password"}""")
        transport.enqueue(200, """{"object":"password_reset_attempt","id":"pr_1","status":"complete","ticket":"tk_pr","sessions_revoked":3,"created_session_id":"sess_pr"}""")
        transport.enqueue(200, """{"object":"session","id":"sess_pr","jwt":"jwt_pr","expires_in":60}""", setCookie = "__atlas_rt=rt_pr; Path=/; HttpOnly")
        transport.enqueue(200, AtlasClientTest.USER_JSON)

        val store = InMemoryTokenStore()
        val flow = client(store, transport).beginPasswordReset("a@b.com")
        assertEquals("a@b.com", body(transport.recorded[0]).string("email_address"))
        assertEquals(PasswordResetStep.CollectCode, flow.step)

        assertEquals(PasswordResetStep.CollectNewPassword, flow.attemptVerification("999000"))
        assertEquals("https://clerk.example.com/v1/client/password_resets/pr_1/attempt_verification", transport.recorded[1].url)

        val step = flow.setNewPassword("newpass123")
        assertEquals("https://clerk.example.com/v1/client/password_resets/pr_1/set_new_password", transport.recorded[2].url)
        assertEquals("newpass123", body(transport.recorded[2]).string("password"))
        assertEquals(PasswordResetStep.Done("sess_pr"), step)
        assertTrue(flow.canSignIn)

        val user = flow.complete()
        assertEquals("user_1", user.id)
        assertEquals("jwt_pr", store.load()?.token)
    }

    @Test
    fun passwordResetSecondFactorStep() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"password_reset_attempt","id":"pr_2","status":"needs_email_verification"}""")
        transport.enqueue(200, """{"object":"password_reset_attempt","id":"pr_2","status":"needs_second_factor"}""")
        transport.enqueue(200, """{"object":"password_reset_attempt","id":"pr_2","status":"needs_new_password"}""")

        val flow = client(InMemoryTokenStore(), transport).beginPasswordReset("a@b.com")
        assertEquals(PasswordResetStep.CollectSecondFactor, flow.attemptVerification("111"))
        assertEquals(PasswordResetStep.CollectNewPassword, flow.attemptSecondFactor("222"))
        assertEquals("https://clerk.example.com/v1/client/password_resets/pr_2/attempt_second_factor", transport.recorded[2].url)
    }
}
