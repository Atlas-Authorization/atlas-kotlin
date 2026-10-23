package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

/** Policy acceptance: wire readers, the controller, the flow calls. Fully offline. */
class PolicyAcceptanceTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(): AtlasClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        return AtlasClient("pk_test_pol", server.url("/").toString(), InMemoryTokenStore(), http)
    }

    private fun resp(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun body(req: RecordedRequest): JsonObject = json.parseToJsonElement(req.body.readUtf8()).jsonObject

    private fun policy(
        key: String = "tos",
        display: String = "checkbox",
        required: Boolean = true,
        html: String? = "<p>Terms</p>",
    ) = AtlasPolicy(
        key = key, title = key.uppercase(), display = display, required = required, appliesTo = "sign_up",
        versionId = "polv_$key", version = "2026-10-24", contentHtml = html, contentMarkdown = null, contentUrl = null,
        contentSha256 = "sha_$key", effectiveAt = null, summaryOfChanges = null, reaccept = null,
    )

    private val policiesJson = """
        {"object":"list","data":[
          {"object":"policy","key":"tos","title":"Terms of Service","display":"checkbox","required":true,"applies_to":"sign_in_and_sign_up",
           "version_id":"polv_1","version":"2026-10-24","content_html":"<p>Hello</p>","content_sha256":"abc","effective_at":1790000000000,"summary_of_changes":null},
          {"object":"policy","key":"privacy","title":"Privacy","display":"scroll","required":true,"applies_to":"sign_up",
           "version_id":"polv_2","version":"3","content_markdown":"# Privacy","content_html":null,"content_sha256":"def"}
        ],"legacy_consent":{"required":false,"terms_url":"https://x/t","privacy_url":null}}
    """.trimIndent()

    @Test
    fun policiesAreReadFromTheWire() = runTest {
        server.enqueue(resp(200, policiesJson))
        val res = client().policies(locale = "fr")
        assertEquals("/v1/client/policies?locale=fr", server.takeRequest().path)
        assertEquals(2, res.policies.size)
        val tos = res.policies[0]
        assertEquals("tos", tos.key)
        assertEquals("checkbox", tos.display)
        assertEquals("polv_1", tos.versionId)
        assertEquals(1790000000000L, tos.effectiveAt)
        assertEquals(1, res.signIn.size)
        assertEquals("scroll", res.policies[1].display)
        assertNull(res.policies[1].contentHtml)
        assertEquals("# Privacy", res.policies[1].contentMarkdown)
        assertEquals("https://x/t", res.legacyConsent?.termsUrl)
    }

    @Test
    fun nestedVersionAndPolicyKeyShapesAreAccepted() {
        val raw = json.parseToJsonElement(
            """{"policy":"tos","title":"T","version":{"id":"polv_9","version":"v9","content_html":"<p>x</p>","content_sha256":"zz"}}""",
        )
        val p = AtlasPolicy.fromJson(raw)!!
        assertEquals("tos", p.key)
        assertEquals("polv_9", p.versionId)
        assertEquals("v9", p.version)
        assertEquals("<p>x</p>", p.contentHtml)
        assertEquals("checkbox", p.display)
        assertNull(AtlasPolicy.fromJson(json.parseToJsonElement("""{"title":"no key"}""")))
    }

    @Test
    fun controllerBuildsAcceptancesAndGatesTheSubmit() {
        var clock = 1_000L
        val c = PolicyAcceptanceController(
            listOf(policy("tos"), policy("privacy", "scroll"), policy("cookies", "link"), policy("news", required = false)),
            now = { clock },
        )
        // A required checkbox blocks; scroll-mode policies are read on submit and do not block the button.
        assertFalse(c.canSubmit())
        c.setAllChecked(true)
        assertTrue(c.canSubmit())
        assertFalse(c.satisfied())
        assertEquals("privacy", c.pendingScroll()?.key)

        c.startClock("privacy")
        clock += 4_000
        c.stopClock("privacy")
        c.acceptScroll("privacy")
        assertTrue(c.satisfied())
        assertNull(c.pendingScroll())

        val built = c.build()
        val byKey = built.associateBy { it.policy }
        assertEquals(setOf("tos", "privacy", "cookies", "news"), byKey.keys)
        assertEquals(true, byKey.getValue("privacy").scrolledToEnd)
        assertEquals(4_000L, byKey.getValue("privacy").viewMs)
        assertEquals("polv_privacy", byKey.getValue("privacy").versionId)
        assertEquals("sha_tos", byKey.getValue("tos").contentSha256)
        assertNull(byKey.getValue("tos").viewMs)
        assertNull(byKey.getValue("tos").scrolledToEnd)
        // The clickwrap policy is accepted by submitting.
        assertEquals("link", byKey.getValue("cookies").display)
    }

    @Test
    fun unscrolledScrollPolicyIsNotBuilt() {
        val c = PolicyAcceptanceController(listOf(policy("privacy", "scroll")))
        assertTrue(c.build().isEmpty())
    }

    @Test
    fun readingClockRecordsViewMsWhileTheDocumentIsOpen() {
        var clock = 0L
        val c = PolicyAcceptanceController(listOf(policy("tos")), now = { clock })
        c.openDocument("tos")
        clock += 2_500
        assertEquals("tos", c.viewing?.key)
        c.closeDocument()
        c.setChecked("tos", true)
        assertEquals(2_500L, c.build().single().viewMs)
    }

    @Test
    fun ticksSurviveARefreshOfUnchangedVersionsOnly() {
        val c = PolicyAcceptanceController(listOf(policy("tos"), policy("privacy")))
        c.setAllChecked(true)
        c.setPolicies(listOf(policy("tos"), policy("privacy").copy(versionId = "polv_new")))
        assertTrue(c.isChecked("tos"))
        assertFalse(c.isChecked("privacy"))
    }

    @Test
    fun separateModeTicksOneAtATime() {
        val c = PolicyAcceptanceController(listOf(policy("tos"), policy("privacy")), combined = false)
        c.setChecked("tos", true)
        assertFalse(c.combinedChecked)
        assertEquals(listOf("privacy"), c.missing().map { it.key })
    }

    @Test
    fun subscribersAreToldOfChangesAndCanStop() {
        val c = PolicyAcceptanceController(listOf(policy("tos")))
        var n = 0
        val stop = c.subscribe { n++ }
        c.setChecked("tos", true)
        stop()
        c.setChecked("tos", false)
        assertEquals(1, n)
    }

    @Test
    fun signUpSendsPolicyAcceptances() = runTest {
        server.enqueue(resp(201, """{"id":"sua_1","status":"needs_email_verification"}"""))
        val c = PolicyAcceptanceController(listOf(policy("tos")))
        c.setAllChecked(true)
        client().signUpFlow().create("a@b.co", "pw", policyAcceptances = c.build())
        val req = server.takeRequest()
        assertEquals("/v1/client/sign_ups", req.path)
        val sent = body(req)["policy_acceptances"]!!.jsonArray.single().jsonObject
        assertEquals("tos", sent["policy"]!!.jsonPrimitive.content)
        assertEquals("polv_tos", sent["version_id"]!!.jsonPrimitive.content)
        assertEquals("sha_tos", sent["content_sha256"]!!.jsonPrimitive.content)
        assertEquals("checkbox", sent["display"]!!.jsonPrimitive.content)
    }

    @Test
    fun signUpWithoutAcceptancesOmitsTheField() = runTest {
        server.enqueue(resp(201, """{"id":"sua_1","status":"needs_email_verification"}"""))
        client().signUpFlow().create("a@b.co", "pw")
        assertFalse(body(server.takeRequest()).containsKey("policy_acceptances"))
    }

    @Test
    fun a422RefusalIsRecognisedAndReloadsThePolicies() = runTest {
        server.enqueue(resp(422, """{"errors":[{"code":"POLICY_ACCEPTANCE_REQUIRED","message":"Accept the terms."}]}"""))
        val atlas = client()
        val failure = runCatching { atlas.signUpFlow().create("a@b.co", "pw") }.exceptionOrNull() as AtlasException
        assertTrue(failure.isPolicyAcceptanceRequired)

        server.enqueue(resp(200, policiesJson))
        val c = PolicyAcceptanceController(emptyList())
        assertTrue(atlas.refreshPoliciesOnRequired(c, failure))
        assertEquals(2, c.policies.size)

        val other = AtlasException.Api(400, listOf(AtlasErrorItem("VALIDATION_FAILED", "x")))
        assertFalse(atlas.refreshPoliciesOnRequired(c, other))
    }

    @Test
    fun heldAttemptMapsToAcceptPoliciesWithItsPendingList() {
        val a = json.decodeFromString(
            FlowAttempt.serializer(),
            """{"id":"sia_1","status":"needs_policy_acceptance","policy_acceptance_token":"pat_1",
               "pending_policies":[{"policy":"tos","title":"Terms","display":"scroll","version_id":"polv_1","version":"2","content_sha256":"h","reaccept":true,"summary_of_changes":"New clause"}]}""",
        )
        assertEquals("pat_1", a.policyAcceptanceToken)
        val step = nextStep(a) as FlowStep.AcceptPolicies
        val p = step.policies.single()
        assertEquals("tos", p.key)
        assertEquals("scroll", p.display)
        assertEquals(true, p.reaccept)
        assertEquals("New clause", p.summaryOfChanges)
        assertFalse(p.hasContent)
    }

    @Test
    fun signInHeldAtPolicyAcceptanceContinuesWithTheToken() = runTest {
        server.enqueue(resp(201, """{"id":"sia_1","status":"needs_first_factor","supported_first_factors":["password"]}"""))
        server.enqueue(
            resp(
                200,
                """{"id":"sia_1","status":"needs_policy_acceptance","policy_acceptance_token":"pat_9",
                   "pending_policies":[{"policy":"tos","title":"Terms","display":"checkbox","version_id":"polv_1","version":"2","content_sha256":"h"}]}""",
            ),
        )
        server.enqueue(resp(200, """{"id":"sia_1","status":"complete","ticket":"tk_1"}"""))
        val flow = client().signInFlow()
        flow.create("a@b.co")
        val step = flow.attemptPassword("pw")
        assertTrue(step is FlowStep.AcceptPolicies)
        server.takeRequest(); server.takeRequest()

        val done = flow.acceptPolicies(listOf(PolicyAcceptance("tos", "polv_1", "h", "checkbox")))
        assertTrue(done is FlowStep.Done)
        val req = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_1/accept_policies", req.path)
        val b = body(req)
        assertEquals("pat_9", b["policy_acceptance_token"]!!.jsonPrimitive.content)
        assertEquals("tos", b["policy_acceptances"]!!.jsonArray.single().jsonObject["policy"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("poll_secret"))
    }

    @Test
    fun policyControllerFillsInContentTheAttemptOnlyListed() = runTest {
        val a = json.decodeFromString(
            FlowAttempt.serializer(),
            """{"id":"sia_1","status":"needs_policy_acceptance","policy_acceptance_token":"t",
               "pending_policies":[{"policy":"tos","title":"Terms","display":"scroll","version_id":"polv_1","version":"2","content_sha256":"h","reaccept":true}]}""",
        )
        server.enqueue(resp(200, policiesJson))
        val c = client().policyController(a)
        val p = c.policies.single()
        assertEquals("<p>Hello</p>", p.contentHtml)
        assertEquals("scroll", p.display)        // the attempt's display wins
        assertEquals("polv_1", p.versionId)
        assertEquals("Terms", p.title)
        assertTrue(p.required)
    }

    @Test
    fun policyControllerForAResumedRedirectShowsEveryRequiredPolicy() = runTest {
        server.enqueue(resp(200, policiesJson))
        val c = client().policyController(FlowAttempt(id = "sia_1", status = "needs_policy_acceptance"))
        assertEquals(listOf("tos", "privacy"), c.policies.map { it.key })
    }

    @Test
    fun redirectParamsAreReadAndTheFlowResumes() = runTest {
        val r = AtlasRedirectResult.parse(
            "myapp://cb?__atlas_attempt=sia_7&__atlas_status=needs_policy_acceptance&__atlas_policy_token=pat%2B1#x",
        )!!
        assertEquals("sia_7", r.attemptId)
        assertTrue(r.needsPolicyAcceptance)
        assertEquals("pat+1", r.policyToken)
        assertNull(AtlasRedirectResult.parse("myapp://cb?foo=bar"))
        // A token that does not belong to a held status is ignored.
        assertNull(AtlasRedirectResult.parse("myapp://cb?__atlas_attempt=a&__atlas_status=complete&__atlas_policy_token=x")!!.policyToken)

        server.enqueue(resp(200, """{"id":"sia_7","status":"complete","ticket":"tk"}"""))
        val flow = client().resumeSignIn(r)
        assertTrue(flow.step is FlowStep.AcceptPolicies)
        flow.acceptPolicies(listOf(PolicyAcceptance("tos", "v", "h")))
        val req = server.takeRequest()
        assertEquals("/v1/client/sign_ins/sia_7/accept_policies", req.path)
        assertEquals("pat+1", body(req)["policy_acceptance_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun passkeySignUpAcceptsWithThePollSecretWhenThereIsNoToken() = runTest {
        server.enqueue(resp(201, """{"id":"sua_1","status":"needs_policy_acceptance","poll_secret":"ps_1"}"""))
        server.enqueue(resp(200, """{"id":"sua_1","status":"needs_first_factor","supported_first_factors":["passkey"]}"""))
        val flow = PasskeySignUpFlow(client())
        flow.start(email = "a@b.co", policyAcceptances = listOf(PolicyAcceptance("tos", "v", "h")))
        assertTrue(body(server.takeRequest()).containsKey("policy_acceptances"))
        flow.acceptPolicies(listOf(PolicyAcceptance("tos", "v", "h")))
        val b = body(server.takeRequest())
        assertEquals("ps_1", b["poll_secret"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("policy_acceptance_token"))
    }

    @Test
    fun myPoliciesAndInAppAcceptanceUseTheSessionRoutes() = runTest {
        val store = InMemoryTokenStore()
        store.save(AtlasSession(sessionId = "s", token = "j", refreshToken = "rt"))
        val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val atlas = AtlasClient("pk", server.url("/").toString(), store, http)
        server.enqueue(
            resp(
                200,
                """{"object":"list","needs_acceptance":true,"blocking":false,"data":[
                  {"policy":"tos","title":"Terms","accepted_version":"1","current_version":"2","needs_reaccept":true,"grace_ends_at":1790000000000,"blocking":false},
                  {"policy":"privacy","title":"Privacy","needs_reaccept":false}]}""",
            ),
        )
        val mine = atlas.myPolicies()
        assertEquals("/v1/client/me/policies", server.takeRequest().path)
        assertTrue(mine[0].needsReaccept)
        assertEquals(1790000000000L, mine[0].graceEndsAt)
        assertFalse(mine[1].needsReaccept)

        server.enqueue(resp(200, "{}"))
        atlas.acceptMyPolicies(listOf(PolicyAcceptance("tos", "polv_2", "h", "scroll", scrolledToEnd = true, viewMs = 5000)))
        val req = server.takeRequest()
        assertEquals("/v1/client/me/policies/accept", req.path)
        val sent = body(req)["policy_acceptances"]!!.jsonArray.single().jsonObject
        assertTrue(sent["scrolled_to_end"]!!.jsonPrimitive.boolean)
        assertEquals("5000", sent["view_ms"]!!.jsonPrimitive.content)
    }

    @Test
    fun gateBuildsAControllerForOnlyTheStalePolicies() = runTest {
        val store = InMemoryTokenStore()
        store.save(AtlasSession(sessionId = "s", token = "j", refreshToken = "rt"))
        val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val gate = PolicyGate(AtlasClient("pk", server.url("/").toString(), store, http))
        server.enqueue(resp(200, """{"data":[{"policy":"tos","title":"Terms","needs_reaccept":true,"grace_ends_at":1790000000000},{"policy":"privacy","needs_reaccept":false}]}"""))
        gate.load()
        assertEquals(1790000000000L, gate.graceEndsAt)
        server.enqueue(resp(200, policiesJson))
        val c = gate.begin()
        assertEquals(listOf("tos"), c.policies.map { it.key })
        assertTrue(c.policies.single().required)
    }

    @Test
    fun helpersFormatAndSplit() {
        assertEquals("A, B and C", joinPolicyTitles(listOf("A", "B", "C"), "and"))
        assertEquals("A", joinPolicyTitles(listOf("A"), "and"))
        assertEquals("I agree to the " to ".", splitPolicyTemplate("I agree to the {policies}.", "policies"))
        assertEquals("2026-10-24", formatPolicyDate(1_792_800_000_000L))
        assertEquals("", formatPolicyDate(null))
        assertEquals("Please accept Terms", fillPolicyTemplate("Please accept {policy}", "policy" to "Terms"))
        assertTrue(isPolicyScrolledToEnd(0, 0))
        assertTrue(isPolicyScrolledToEnd(98, 100))
        assertFalse(isPolicyScrolledToEnd(10, 100))
        assertFalse(isPolicyScrolledToEnd(0, Int.MAX_VALUE))
    }
}
