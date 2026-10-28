package net.atlasauth.atlas.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISSUER = "https://auth.example.com"

class PkceTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesTheKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Pkce.sha256(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Pkce.sha256("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Pkce.sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun theRfc7636ExampleChallengeIsReproduced() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun createGivesAnUnpaddedVerifierOfTheRightLength() {
        val p = Pkce.create()
        assertEquals(43, p.verifier.length)
        assertEquals(Pkce.challengeFor(p.verifier), p.challenge)
    }
}

class AtlasNativeTest {
    private class World {
        var nowMillis = 1_800_000_000_000L
        var refreshStatus = HttpStatusCode.OK
        var refreshThrows = false
        var refreshes = 0
        var authEndpoint = "$ISSUER/oauth2/authorize"
        var wrongIssuer = false
        val bodies = mutableListOf<String>()
        val store = MemorySessionStore()
        var callback: (String) -> String = { authorize ->
            val q = Url(authorize).parameters
            "myapp://callback?code=abc&state=${q["state"]}"
        }

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            bodies += "$path $body"
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path == "/.well-known/openid-configuration" -> respond(
                    """{"issuer":"${if (wrongIssuer) "https://other.example.com" else ISSUER}","authorization_endpoint":"$authEndpoint","token_endpoint":"$ISSUER/oauth2/token"}""",
                    HttpStatusCode.OK, json,
                )
                path == "/oauth2/token" && "grant_type=authorization_code" in body -> respond("""{"access_token":"oat_1"}""", HttpStatusCode.OK, json)
                path == "/oauth2/token" -> respond(
                    """{"access_token":"jwt_1","session_id":"sess_1","refresh_token":"r1","expires_in":60}""", HttpStatusCode.OK, json,
                )
                path == "/v1/client/sessions/sess_1/tokens" -> {
                    refreshes++
                    if (refreshThrows) throw RuntimeException("offline")
                    respond(
                        """{"jwt":"jwt_${refreshes + 1}","session_id":"sess_1","refresh_token":"r${refreshes + 1}","expires_in":60}""",
                        refreshStatus, json,
                    )
                }
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }

        fun client() = AtlasNativeClient(
            AtlasNativeOptions(ISSUER, "pk_test_1", "client_1", "myapp://callback"),
            AuthBrowser { url, _ -> callback(url) },
            store,
            HttpClient(engine),
            { nowMillis },
        )
    }

    @Test
    fun signInRunsCodeWithPkceThenExchangesForASession() = runTest {
        val w = World()
        var challenge = ""
        w.callback = { url ->
            val q = Url(url).parameters
            challenge = q["code_challenge"]!!
            assertEquals("S256", q["code_challenge_method"])
            "myapp://callback?code=abc&state=${q["state"]}"
        }
        val client = w.client()
        client.signIn()
        val codeCall = w.bodies.first { "grant_type=authorization_code" in it }
        val verifier = codeCall.substringAfter("code_verifier=").substringBefore("&")
        assertEquals(challenge, Pkce.challengeFor(verifier))
        assertTrue(w.bodies.any { "subject_token=oat_1" in it })
        assertEquals("jwt_1", w.store.load()?.sessionToken)
        assertEquals("jwt_1", client.getToken())
    }

    @Test
    fun aWrongStateAnErrorOrNoCodeIsRefused() = runTest {
        for (cb in listOf<(String) -> String>(
            { _ -> "myapp://callback?code=abc&state=forged" },
            { u -> "myapp://callback?error=access_denied&state=${Url(u).parameters["state"]}" },
            { u -> "myapp://callback?state=${Url(u).parameters["state"]}" },
        )) {
            val w = World()
            w.callback = cb
            assertFailsWith<AtlasNativeException> { w.client().signIn() }
            assertNull(w.store.load())
        }
    }

    @Test
    fun discoveryForAnotherIssuerOrOriginIsRefusedBeforeTheBrowserOpens() = runTest {
        for (configure in listOf<(World) -> Unit>({ it.wrongIssuer = true }, { it.authEndpoint = "https://evil.example.com/oauth2/authorize" })) {
            val w = World()
            configure(w)
            var opened = false
            w.callback = { opened = true; "" }
            assertFailsWith<AtlasNativeException> { w.client().signIn() }
            assertFalse(opened)
        }
    }

    @Test
    fun theIssuerMustBeHttps() {
        assertFailsWith<IllegalArgumentException> {
            AtlasNativeClient(AtlasNativeOptions("http://auth.example.com", "pk", "c", "myapp://cb"), AuthBrowser { _, _ -> "" }, MemorySessionStore())
        }
    }

    @Test
    fun theTokenIsRotatedNearExpiryAndTheNewRefreshTokenIsStored() = runTest {
        val w = World()
        val client = w.client()
        client.signIn()
        assertEquals("jwt_1", client.getToken())
        assertEquals(0, w.refreshes)
        w.nowMillis += 55_000
        assertEquals("jwt_2", client.getToken())
        assertEquals("r2", w.store.load()?.refreshToken)
    }

    @Test
    fun aFailedRefreshKeepsTheTokenButADeadRefreshTokenSignsOut() = runTest {
        val w = World()
        val client = w.client()
        client.signIn()
        w.nowMillis += 55_000
        w.refreshThrows = true
        assertEquals("jwt_1", client.getToken())
        w.refreshThrows = false
        w.refreshStatus = HttpStatusCode.Unauthorized
        assertNull(client.getToken())
        assertFalse(client.isSignedIn())
        assertNull(w.store.load())
    }

    @Test
    fun signOutForgetsTheSession() = runTest {
        val w = World()
        val client = w.client()
        client.signIn()
        client.signOut()
        assertNull(client.getToken())
        assertNull(w.store.load())
    }

    @Test
    fun authorizedUrlsAreMatchedByOriginAndPathPrefix() = runTest {
        val client = World().client()
        val allowed = listOf("https://api.example.com/v1")
        assertTrue(client.isAuthorized("https://api.example.com/v1/orders", allowed))
        assertTrue(client.isAuthorized("https://api.example.com/v1", allowed))
        assertFalse(client.isAuthorized("https://api.example.com/v10/orders", allowed))
        assertFalse(client.isAuthorized("https://evil.example.com/v1/orders", allowed))
        assertFalse(client.isAuthorized("https://api.example.com:8443/v1/orders", allowed))
        assertFalse(client.isAuthorized("http://api.example.com/v1/orders", allowed))
    }

    @Test
    fun theKtorPluginAddsTheTokenOnlyToAuthorizedUrls() = runTest {
        val w = World()
        val auth = w.client()
        auth.signIn()
        val seen = mutableMapOf<String, String?>()
        val api = HttpClient(MockEngine { request ->
            seen[request.url.host] = request.headers[HttpHeaders.Authorization]
            respond("ok", HttpStatusCode.OK)
        }) { install(auth.authPlugin(listOf("https://api.example.com"))) }
        api.get("https://api.example.com/orders")
        api.get("https://evil.example.com/orders")
        assertEquals("Bearer jwt_1", seen["api.example.com"])
        assertNull(seen["evil.example.com"])
        assertNotNull(api)
    }
}
