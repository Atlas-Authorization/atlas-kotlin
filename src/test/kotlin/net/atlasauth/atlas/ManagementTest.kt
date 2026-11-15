package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import java.util.Locale
import java.util.concurrent.TimeUnit

/** MockWebServer tests for billing, API keys, the organization switcher and device approval. */
class ManagementTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(token: String = "jwt_1"): Pair<AtlasClient, InMemoryTokenStore> {
        val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val store = InMemoryTokenStore(AtlasSession("sess_1", token, "rt_1"))
        return AtlasClient("pk_test", server.url("/").toString(), store, http) to store
    }

    private fun ok(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun body(req: RecordedRequest) = json.parseToJsonElement(req.body.readUtf8()) as JsonObject

    private val plan = """{"id":"plan_pro","name":"Pro","slug":"pro","interval":"month","amount":1000,"currency":"usd","features":["SSO"],"audience":"user","annual_available":true,"annual_amount":"9600","trial_days":14,"promotion":{"label":"Launch","percent_off":50,"code":"LAUNCH"}}"""

    @Test fun plansDecodeAndSort() = runTest {
        server.enqueue(ok("""{"data":[{"id":"b","name":"B","sort_order":2,"amount":0},{"id":"a","sort_order":1,"amount":"500"}]}"""))
        val plans = client().first.billingPlans()
        assertEquals(listOf("a", "b"), plans.map { it.id })
        assertEquals(500.0, plans[0].amountMinor, 0.0)
        assertEquals("/v1/client/billing/plans", server.takeRequest().path)
    }

    @Test fun priceViewAppliesPromotionAndAnnual() {
        val p = json.decodeFromString(BillingPlan.serializer(), plan)
        val monthly = p.priceView("month", Locale.US)
        assertEquals(500L, monthly.amount)
        assertEquals("$10.00", monthly.originalPrice)
        assertEquals(14, monthly.trialDays)
        assertEquals("LAUNCH", monthly.promotionCode)
        val yearly = p.priceView("year", Locale.US)
        assertEquals("year", yearly.interval)
        assertEquals(4800L, yearly.amount)
        val expired = json.decodeFromString(BillingPlan.serializer(), plan.replace("\"code\":\"LAUNCH\"", "\"code\":\"LAUNCH\",\"ends_at\":1000"))
        assertEquals(1000L, expired.priceView(nowMillis = 5000).amount)
    }

    @Test fun subscriptionOrgQueryAndAddons() = runTest {
        server.enqueue(ok("""{"subject_type":"org","status":"active","id":"sub_1","cancel_at_period_end":true,"subscriptions":[{"id":"sub_1","status":"active"},{"id":"sub_2","status":"trialing","plan":{"id":"x","kind":"addon"}}]}"""))
        val sub = client().first.billingSubscription("org_1")
        assertEquals("/v1/client/billing/subscription?org_id=org_1", server.takeRequest().path)
        assertTrue(sub.isLive)
        assertTrue(sub.cancelAtPeriodEnd)
        assertEquals(listOf("sub_2"), sub.liveAddons.map { it.id })
    }

    @Test fun neverSubscribedHasNullStatus() = runTest {
        server.enqueue(ok("""{"subject_type":"user","status":null,"plan":null}"""))
        val sub = client().first.billingSubscription()
        assertNull(sub.status)
        assertTrue(sub.all.isEmpty())
    }

    @Test fun checkoutAndPortalBodies() = runTest {
        server.enqueue(ok("""{"url":"https://checkout.example/s/1"}"""))
        val (c, _) = client()
        assertEquals("https://checkout.example/s/1", c.billingCheckout("plan_pro", "app://ok", "app://no", "org_1", "year", "LAUNCH"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/client/billing/checkout", r.path)
        val b = body(r)
        assertEquals("plan_pro", b["plan_id"]!!.jsonPrimitive.content)
        assertEquals("org_1", b["org_id"]!!.jsonPrimitive.content)
        assertEquals("year", b["interval"]!!.jsonPrimitive.content)
        assertEquals("LAUNCH", b["coupon_code"]!!.jsonPrimitive.content)
        server.enqueue(ok("""{"url":"https://portal.example/p"}"""))
        c.billingPortal("app://back", subscriptionId = "sub 2")
        assertEquals("sub 2", body(server.takeRequest())["subscription_id"]!!.jsonPrimitive.content)
    }

    @Test fun cancelRestoreChangePaths() = runTest {
        val sub = """{"subject_type":"user","status":"active"}"""
        val (c, _) = client()
        server.enqueue(ok(sub)); c.billingCancel(immediately = true)
        var r = server.takeRequest()
        assertEquals("/v1/client/billing/subscription/cancel", r.path)
        assertTrue(body(r)["immediately"]!!.jsonPrimitive.boolean)
        server.enqueue(ok(sub)); c.billingRestore(subscriptionId = "sub_2")
        assertEquals("/v1/client/billing/subscriptions/sub_2/restore", server.takeRequest().path)
        server.enqueue(ok(sub)); c.billingChangePlan("plan_b", atPeriodEnd = true)
        r = server.takeRequest()
        assertEquals("/v1/client/billing/subscription/change", r.path)
        assertTrue(body(r)["schedule_at_period_end"]!!.jsonPrimitive.boolean)
    }

    @Test fun invoicesCardCheckAndCoupon() = runTest {
        val (c, _) = client()
        server.enqueue(ok("""{"data":[{"id":"in_1","number":"A-1","status":"paid","amount_due":1000,"currency":"usd","created_at":1700000000000}]}"""))
        assertEquals("A-1", c.billingInvoices().first().number)
        server.enqueue(ok("""{"payment_method":{"brand":"visa","last4":"4242","exp_month":4,"exp_year":2030}}"""))
        assertEquals("4242", c.billingPaymentMethod()?.last4)
        server.enqueue(ok("""{"payment_method":null}"""))
        assertNull(c.billingPaymentMethod())
        server.enqueue(ok("""{"feature":"seats","allowed":true,"remaining":3}"""))
        assertTrue(c.billingCheck("seats", 2).allowed)
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/client/billing/check?feature=seats&required=2", server.takeRequest().path)
        server.enqueue(ok("""{"valid":true,"discount":{"percent_off":20,"duration":"repeating","duration_months":3}}"""))
        val check = c.billingValidateCoupon("plan_pro", " LAUNCH ")
        assertEquals(3, check.discount?.durationMonths)
        assertEquals("LAUNCH", body(server.takeRequest())["code"]!!.jsonPrimitive.content)
    }

    private val key = """{"id":"ak_1","subject_type":"user","subject_id":"user_1","name":"CI","prefix":"ak_live_abcd","scopes":["calls:read"],"created_at":1700000000000}"""

    @Test fun apiKeyListCreateRenameRevoke() = runTest {
        val (c, _) = client()
        server.enqueue(ok("""{"data":[$key],"allowed_scopes":["calls:read","calls:write"],"max_active_keys":5,"active_count":1}"""))
        val list = c.listApiKeys("org_1", bound = true)
        assertEquals("/v1/client/me/api_keys?organization_id=org_1&subject_type=user", server.takeRequest().path)
        assertEquals(5, list.maxActiveKeys)
        assertTrue(list.keys[0].isActive())
        server.enqueue(ok(key.dropLast(1) + ""","secret":"ak_live_secret"}""", 201))
        val created = c.createApiKey("CI", expiresInSeconds = 86_400, scopes = listOf("calls:read"), organizationId = "org_1")
        assertEquals("ak_live_secret", created.secret)
        val b = body(server.takeRequest())
        assertEquals(86_400, b["expires_in_seconds"]!!.jsonPrimitive.int)
        assertEquals("org_1", b["organization_id"]!!.jsonPrimitive.content)
        assertNull(b["expires_at"])
        server.enqueue(ok(key)); c.renameApiKey("ak_1", null)
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertTrue(body(r)["name"] is JsonNull)
        server.enqueue(ok("""{"id":"ak_1","revoked":true}""")); c.revokeApiKey("ak_1")
        assertEquals("/v1/client/me/api_keys/ak_1/revoke", server.takeRequest().path)
    }

    @Test fun staleSessionSurfacesStepUp() = runTest {
        server.enqueue(ok("""{"errors":[{"code":"STEP_UP_REQUIRED","message":"Re-authenticate"}]}""", 403))
        try { client().first.createApiKey(); throw AssertionError("expected an error") } catch (e: AtlasException) { assertTrue(e.isStepUpRequired) }
    }

    @Test fun stepUpPersistsFreshJwt() = runTest {
        server.enqueue(ok("""{"id":"sess_1","jwt":"jwt_fresh"}"""))
        val (c, store) = client()
        c.stepUp("password", password = "pw")
        assertEquals("/v1/client/me/step_up", server.takeRequest().path)
        assertEquals("jwt_fresh", store.load()?.token)
        assertEquals("rt_1", store.load()?.refreshToken)
    }

    @Test fun setActiveOrganizationPersistsRotatedJwt() = runTest {
        server.enqueue(ok("""{"id":"sess_1","jwt":"jwt_rot","last_active_organization_id":"org_1"}"""))
        val (c, store) = client()
        assertEquals("org_1", c.setActiveOrganization("org_1"))
        val r = server.takeRequest()
        assertEquals("/v1/client/sessions/sess_1/touch", r.path)
        assertEquals("org_1", body(r)["active_organization_id"]!!.jsonPrimitive.content)
        assertEquals("jwt_rot", store.load()?.token)
        server.enqueue(ok("""{"id":"sess_1","jwt":"j2","last_active_organization_id":null}"""))
        assertNull(c.setActiveOrganization(null))
        assertTrue(body(server.takeRequest())["active_organization_id"] is JsonNull)
    }

    @Test fun activeOrganizationFromTokenClaim() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"org_id":"org_9"}""".toByteArray())
        assertEquals("org_9", client("h.$payload.s").first.activeOrganizationId())
        assertNull(client("opaque").first.activeOrganizationId())
    }

    @Test fun slug() {
        assertEquals("acme-corp", organizationSlug("Acme Corp!"))
        assertEquals("a-b", organizationSlug("  a  b "))
    }

    @Test fun switcherLoadsSwitchesAndCreates() = runTest {
        val (c, _) = client()
        val holder = OrganizationSwitcherState(c)
        server.enqueue(ok("""{"data":[{"role":"admin","organization":{"id":"org_1","name":"Acme","slug":"acme"}}]}"""))
        holder.load()
        assertEquals(1, holder.state.value.memberships.size)
        server.enqueue(ok("""{"id":"sess_1","jwt":"j","last_active_organization_id":"org_1"}"""))
        holder.select("org_1")
        assertEquals("org_1", holder.state.value.activeOrganizationId)
        server.enqueue(ok("""{"id":"org_2","name":"Beta","slug":"beta"}""", 201))
        server.enqueue(ok("""{"id":"sess_1","jwt":"j2","last_active_organization_id":"org_2"}"""))
        server.enqueue(ok("""{"data":[{"role":"admin","organization":{"id":"org_2","name":"Beta","slug":"beta"}}]}"""))
        assertTrue(holder.create("Beta"))
        assertEquals("org_2", holder.state.value.activeOrganizationId)
    }

    @Test fun apiKeysStateParksCreateOnStepUp() = runTest {
        val holder = ApiKeysState(client().first)
        server.enqueue(ok("""{"errors":[{"code":"STEP_UP_REQUIRED","message":"x"}]}""", 403))
        holder.create("CI", 30, null)
        assertTrue(holder.state.value.needsStepUp)
        server.enqueue(ok("""{"id":"sess_1","jwt":"fresh"}"""))
        server.enqueue(ok(key.dropLast(1) + ""","secret":"ak_secret"}""", 201))
        server.enqueue(ok("""{"data":[]}"""))
        holder.completeStepUp("pw")
        assertEquals("ak_secret", holder.state.value.createdSecret)
        assertFalse(holder.state.value.needsStepUp)
        server.takeRequest(); server.takeRequest() // failed create, step-up
        assertEquals(30 * 86_400, body(server.takeRequest())["expires_in_seconds"]!!.jsonPrimitive.int)
    }

    @Test fun apiKeysDisabledOn403() = runTest {
        val holder = ApiKeysState(client().first)
        server.enqueue(ok("""{"errors":[{"code":"FORBIDDEN","message":"off"}]}""", 403))
        holder.load()
        assertEquals(ApiKeysStatus.DISABLED, holder.state.value.status)
    }

    @Test fun deviceApprovalFlow() = runTest {
        val holder = DeviceApprovalState(client().first)
        server.enqueue(ok("""{"id":"da_1","user_code":"ABCD-EFGH","status":"pending","client":{"name":"TV"},"scopes":[]}"""))
        holder.lookup("abcdefgh")
        assertTrue(holder.state.value.phase is DeviceApprovalPhase.Pending)
        server.enqueue(ok("""{"id":"da_1","user_code":"ABCD-EFGH","status":"approved","client":{"name":"TV"},"scopes":[]}"""))
        holder.decide(true)
        assertEquals(DeviceApprovalPhase.Approved, holder.state.value.phase)
    }

    @Test fun deviceApprovalStepUp() = runTest {
        val holder = DeviceApprovalState(client().first)
        server.enqueue(ok("""{"id":"da_1","user_code":"ABCD-EFGH","client":{"name":"TV"}}"""))
        holder.lookup("ABCD-EFGH")
        server.enqueue(ok("""{"errors":[{"code":"STEP_UP_REQUIRED","message":"x"}]}""", 403))
        holder.decide(true)
        assertTrue(holder.state.value.needsStepUp)
    }

    @Test fun billingStateSwitchesInPlaceWhenLive() = runTest {
        val holder = BillingState(client().first, returnUrl = "app://back")
        server.enqueue(ok("""{"data":[{"id":"a","name":"A","amount":0},{"id":"b","name":"B","amount":500}]}"""))
        server.enqueue(ok("""{"subject_type":"user","status":"active","plan":{"id":"a","name":"A","amount":0}}"""))
        server.enqueue(ok("""{"data":[]}"""))
        server.enqueue(ok("""{"payment_method":null}"""))
        holder.load()
        server.enqueue(ok("""{"subject_type":"user","status":"active","plan":{"id":"b","name":"B","amount":500}}"""))
        assertNull(holder.subscribe(holder.state.value.plans[1], null))
        assertEquals("b", holder.state.value.subscription?.plan?.id)
    }

    @Test fun passkeyStepUpBodyDropsHandleAndSetsStrategy() {
        val begin = """{"challenge":"ch","handle":"h","rpId":"clerk.example.com"}"""
        val resp = """{"id":"cred","rawId":"cred","response":{"authenticatorData":"ad","clientDataJSON":"cd","signature":"sig"}}"""
        val b = json.parseToJsonElement(stepUpPasskeyBody(begin, resp)) as JsonObject
        assertEquals("passkey", b["strategy"]!!.jsonPrimitive.content)
        assertEquals("ch", b["challenge"]!!.jsonPrimitive.content)
        assertEquals("sig", b["signature"]!!.jsonPrimitive.content)
        assertNull(b["handle"])
    }

    @Test fun pushStepUpPendingThenApproved() = runTest {
        val (c, store) = client()
        server.enqueue(ok("""{"number_match":"42"}"""))
        assertEquals("42", c.beginPushStepUp().numberMatch)
        assertEquals("/v1/client/me/step_up/push", server.takeRequest().path)
        server.enqueue(ok("""{"status":"pending"}""", 202))
        assertFalse(c.stepUpPush())
        assertEquals("jwt_1", store.load()?.token)
        server.enqueue(ok("""{"id":"sess_1","jwt":"jwt_push"}"""))
        assertTrue(c.stepUpPush())
        assertEquals("jwt_push", store.load()?.token)
    }
}
