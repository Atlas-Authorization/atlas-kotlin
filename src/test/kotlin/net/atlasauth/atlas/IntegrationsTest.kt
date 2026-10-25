package net.atlasauth.atlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Integration `getToken`, the OIDC org / SSO claims, and the 2026-10 error codes. */
class IntegrationsTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(signedIn: Boolean = true): AtlasClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val store = if (signedIn) InMemoryTokenStore(AtlasSession("sess_1", "jwt_1", "rt_1")) else InMemoryTokenStore()
        return AtlasClient("atlas_test_pk_abc", server.url("/").toString(), store, http)
    }

    private fun resp(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun getTokenPostsToTheSessionIntegrationRoute() = runTest {
        server.enqueue(resp(200, """{"jwt":"t.t.t","expires_in":300,"integration":"chatly","audience":"chatly"}"""))
        val token = client().getToken("chatly")
        assertNotNull(token)
        assertEquals("t.t.t", token!!.jwt)
        assertEquals(300, token.expiresIn)
        assertEquals("chatly", token.audience)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/client/sessions/sess_1/integration_tokens/chatly", req.path)
        assertEquals("atlas_test_pk_abc", req.getHeader("x-publishable-key"))
        assertTrue(req.getHeader("Cookie")!!.contains("__atlas_rt=rt_1"))
    }

    @Test
    fun getTokenCarriesTheForumulateSpace() = runTest {
        server.enqueue(resp(200, """{"jwt":"j","expires_in":60,"integration":"forumulate","audience":"a","org_id":"o1","space_id":"s 1","redeem_url":"https://f.example/redeem"}"""))
        val token = client().getToken("forumulate", spaceId = "s 1")!!
        assertEquals("o1", token.orgId)
        assertEquals("https://f.example/redeem", token.redeemUrl)
        assertEquals("/v1/client/sessions/sess_1/integration_tokens/forumulate?space_id=s%201", server.takeRequest().path)
    }

    @Test
    fun getTokenSendsThePerCallAudience() = runTest {
        server.enqueue(resp(200, """{"jwt":"j","expires_in":60,"integration":"acme","audience":"acme:dv-1"}"""))
        val token = client().getToken("acme", cbid = "dv 1", audience = "acme:dv-1", audienceParams = mapOf("tenant" to "t2"))!!
        assertEquals("acme:dv-1", token.audience)
        assertEquals(
            "/v1/client/sessions/sess_1/integration_tokens/acme?tenant=t2&cbid=dv%201&audience=acme%3Adv-1",
            server.takeRequest().path,
        )
    }

    @Test
    fun getTokenIsNullWhenTheIntegrationIsNotEnabled() = runTest {
        server.enqueue(resp(404, """{"errors":[{"code":"NOT_FOUND","message":"nope"}]}"""))
        assertNull(client().getToken("chatly"))
    }

    @Test
    fun getTokenThrowsOnOtherRefusalsAndWithoutASession() = runTest {
        server.enqueue(resp(401, """{"errors":[{"code":"UNAUTHENTICATED","message":"no"}]}"""))
        try { client().getToken("chatly"); throw AssertionError("expected") } catch (e: AtlasException.Api) {
            assertEquals(401, e.statusCode)
        }
        try { client(signedIn = false).getToken("chatly"); throw AssertionError("expected") } catch (e: AtlasException.NotSignedIn) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun atlasIdTokenSendsBearerOriginAndKeyInQueryWithoutCookies() = runTest {
        server.enqueue(resp(200, """{"jwt":"j","expires_in":300,"integration":"referalli","audience":"r","issuer":"https://atlasauth.net","jwks_uri":"https://atlasauth.net/jwks"}"""))
        val token = client().getAtlasIdToken("referalli", "at_123", origin = "https://app.example")!!
        assertEquals("https://atlasauth.net", token.issuer)
        assertEquals("https://atlasauth.net/jwks", token.jwksUri)
        val req = server.takeRequest()
        assertEquals("/v1/client/integrations/referalli/atlas_id_tokens?publishable_key=atlas_test_pk_abc", req.path)
        assertEquals("Bearer at_123", req.getHeader("Authorization"))
        assertEquals("https://app.example", req.getHeader("Origin"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test
    fun atlasIdTokenUsesDpopWhenAProofIsGiven() = runTest {
        server.enqueue(resp(403, """{"errors":[{"code":"ORIGIN_NOT_ALLOWED","message":"origin"}]}"""))
        try {
            client().getAtlasIdToken("referalli", "at_1", dpopProof = "proof")
            throw AssertionError("expected")
        } catch (e: AtlasException.Api) {
            assertTrue(e.isOriginNotAllowed)
        }
        val req = server.takeRequest()
        assertEquals("DPoP at_1", req.getHeader("Authorization"))
        assertEquals("proof", req.getHeader("DPoP"))
    }

    @Test
    fun atlasIdTokenIsNullOn404() = runTest {
        server.enqueue(resp(404, """{"errors":[{"code":"NOT_FOUND","message":"n"}]}"""))
        assertNull(client().getAtlasIdToken("x", "t"))
    }

    @Test
    fun insufficientScopeReadsTheRequiredScope() {
        val body = """{"errors":[{"code":"INSUFFICIENT_SCOPE","message":"m","meta":{"required_scope":"webhooks:write"},"docs_url":"https://atlasauth.net/errors/INSUFFICIENT_SCOPE"}]}"""
        val e = parseErrorEnvelope(json, 403, body)
        assertTrue(e.isInsufficientScope)
        assertEquals("webhooks:write", e.requiredScope)
        assertEquals("https://atlasauth.net/errors/INSUFFICIENT_SCOPE", e.errors[0].docsUrl)
        // The old spelling is still tolerated.
        val legacy = AtlasException.Api(403, listOf(AtlasErrorItem("SCOPE_MISSING", "m")))
        assertTrue(legacy.isInsufficientScope)
        assertNull(legacy.requiredScope)
    }

    @Test
    fun stepUpAndOriginNotAllowedAreTyped() {
        val step = AtlasException.Api(403, listOf(AtlasErrorItem("STEP_UP_REQUIRED", "m")))
        assertTrue(step.isStepUpRequired)
        assertFalse(step.isOriginNotAllowed)
        assertEquals(AtlasErrorCode.STEP_UP_REQUIRED, step.knownCode)
        assertTrue(AtlasException.Api(403, listOf(AtlasErrorItem("ORIGIN_NOT_ALLOWED", "m"))).isOriginNotAllowed)
    }

    @Test
    fun routeNotFoundCarriesSuggestions() {
        val body = """{"errors":[{"code":"ROUTE_NOT_FOUND","message":"m","did_you_mean":["/v1/webhook_endpoints"],"allowed_methods":["GET","POST"]}]}"""
        val e = parseErrorEnvelope(json, 404, body)
        assertTrue(e.isRouteNotFound)
        assertEquals(listOf("/v1/webhook_endpoints"), e.didYouMean)
        assertEquals(listOf("GET", "POST"), e.allowedMethods)
        assertTrue(AtlasException.Api(404, listOf(AtlasErrorItem("NOT_FOUND", "m"))).didYouMean.isEmpty())
    }

    @Test
    fun oidcClaimsReadOrgAndSsoClaims() {
        val claims = json.parseToJsonElement(
            """{"org_id":"org_1","org_slug":"acme","org_role":"admin","org_permissions":["a:b","c:d"],
               "orgs":[{"id":"org_1","slug":"acme","role":"admin"},{"id":"org_2","slug":"b","role":"member"},{"slug":"skipped"}],
               "sso_connection_id":"sso_9","sso_tenant_id":"tid","sso_issuer":"https://login.example","sso_domain":"acme.com","sso_organization_id":"org_7","sso_connection_type":"saml"}""",
        ) as JsonObject
        val c = OidcClaims.fromClaims(claims)
        assertEquals("org_1", c.orgId)
        assertEquals("admin", c.orgRole)
        assertEquals(listOf("a:b", "c:d"), c.orgPermissions)
        assertEquals(2, c.orgs.size)
        assertEquals("member", c.orgs[1].role)
        assertEquals("sso_9", c.sso?.connectionId)
        assertEquals("acme.com", c.sso?.domain)
        assertEquals("org_7", c.ssoOrganizationId)
        assertEquals("saml", c.ssoConnectionType)
        val none = OidcClaims.fromClaims(json.parseToJsonElement("""{"sub":"u"}""") as JsonObject)
        assertNull(none.orgId)
        assertNull(none.sso)
        assertNull(none.ssoOrganizationId)
        assertNull(none.ssoConnectionType)
        assertTrue(none.orgs.isEmpty())
    }

    @Test
    fun oidcClaimsFromJwtAndScopes() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"org_id":"o","sso_connection_id":"s"}""".toByteArray())
        val c = OidcClaims.fromJwt("e30.$payload.sig")!!
        assertEquals("o", c.orgId)
        assertEquals("s", c.sso?.connectionId)
        assertNull(OidcClaims.fromJwt("not-a-jwt"))
        assertEquals(listOf("openid", "organizations"), AtlasScopes.withOrganizations(listOf("openid")))
        assertEquals(listOf("openid", "organizations"), AtlasScopes.withOrganizations(listOf("openid", "organizations")))
    }
}
