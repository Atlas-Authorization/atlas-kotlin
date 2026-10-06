package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlasClientTest {
    private val pk = "pk_test_123"

    private fun client(store: TokenStore, transport: FakeTransport): AtlasClient =
        AtlasClient(
            publishableKey = pk,
            frontendApi = "clerk.example.com",
            tokenStore = store,
            transport = transport,
            // Run inline so runBlocking sees the work synchronously.
            dispatcher = Dispatchers.Unconfined,
        )

    private fun bodyJson(request: HttpRequest): JsonValue = JsonValue.parse(request.body!!)

    // ---- base URL + auth header ----------------------------------------------

    @Test
    fun resolvesBareHostToHttps() {
        assertEquals("https://clerk.example.com", AtlasClient.resolveBaseUrl("clerk.example.com"))
        assertEquals("http://localhost:4000", AtlasClient.resolveBaseUrl("http://localhost:4000/"))
    }

    @Test
    fun everyRequestCarriesPublishableKeyAndBaseUrl() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_1","status":"needs_first_factor"}""")
        transport.enqueue(200, """{"id":"sia_1","status":"complete","ticket":"tk_1"}""")
        transport.enqueue(200, """{"object":"session","id":"sess_1","jwt":"jwt_abc","expires_in":60}""", setCookie = "__atlas_rt=rt_xyz; Path=/v1; HttpOnly")
        transport.enqueue(200, USER_JSON)

        client(InMemoryTokenStore(), transport).signIn("a@b.com", "hunter2")

        assertEquals("https://clerk.example.com/v1/client/sign_ins", transport.recorded[0].url)
        transport.recorded.forEach { assertEquals(pk, it.headers["x-publishable-key"]) }
    }

    // ---- password sign-in: endpoints + bodies + token storage ----------------

    @Test
    fun passwordSignInHitsExactEndpointsWithExactBodies() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_42","status":"needs_first_factor"}""")
        transport.enqueue(200, """{"id":"sia_42","status":"complete","ticket":"tk_9"}""")
        transport.enqueue(200, """{"object":"session","id":"sess_9","jwt":"jwt_final","expires_in":60}""", setCookie = "__atlas_rt=rt_final; Path=/v1; HttpOnly")
        transport.enqueue(200, USER_JSON)

        val store = InMemoryTokenStore()
        val user = client(store, transport).signIn("a@b.com", "hunter2")

        // Step 1: create attempt with the identifier, never the password.
        assertEquals("https://clerk.example.com/v1/client/sign_ins", transport.recorded[0].url)
        assertEquals("a@b.com", bodyJson(transport.recorded[0]).string("identifier"))
        assertNull(bodyJson(transport.recorded[0]).string("password"))

        // Step 2: first factor, strategy=password, on the attempt id.
        assertEquals("https://clerk.example.com/v1/client/sign_ins/sia_42/attempt_first_factor", transport.recorded[1].url)
        assertEquals("password", bodyJson(transport.recorded[1]).string("strategy"))
        assertEquals("hunter2", bodyJson(transport.recorded[1]).string("password"))

        // Step 3: exchange the completion ticket.
        assertEquals("https://clerk.example.com/v1/client/tickets/exchange", transport.recorded[2].url)
        assertEquals("sia_42", bodyJson(transport.recorded[2]).string("attempt_id"))
        assertEquals("tk_9", bodyJson(transport.recorded[2]).string("ticket"))

        // Token stored: JWT from exchange, refresh cookie captured.
        val stored = assertNotNull(store.load())
        assertEquals("jwt_final", stored.token)
        assertEquals("rt_final", stored.refreshToken)
        assertEquals("sess_9", stored.sessionId)

        assertEquals("user_1", user.id)
    }

    // ---- 4xx -> AtlasError with code -----------------------------------------

    @Test
    fun wrongPasswordSurfacesApiErrorWithCode() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"id":"sia_1","status":"needs_first_factor"}""")
        transport.enqueue(422, """{"errors":[{"code":"form_password_incorrect","message":"Incorrect password.","param":"password"}]}""")

        val error = assertFailsWith<AtlasException> {
            client(InMemoryTokenStore(), transport).signIn("a@b.com", "wrong")
        }.error
        assertEquals("form_password_incorrect", error.code)
        assertEquals(422, error.status)
        assertEquals("Incorrect password.", error.message)
    }

    @Test
    fun malformedErrorBodyStillYieldsACode() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(500, "not json at all")
        val error = assertFailsWith<AtlasException> {
            client(InMemoryTokenStore(), transport).oauthAuthorizeUrl("google", "app://cb")
        }.error
        assertEquals(500, error.status)
        assertEquals("unexpected", error.code)
    }

    // ---- currentUser parse ----------------------------------------------------

    @Test
    fun currentUserDecodesFullShape() = runBlocking {
        val store = InMemoryTokenStore(AtlasSession("sess_1", "jwt", "rt"))
        val transport = FakeTransport()
        transport.enqueue(200, USER_JSON)

        val user = client(store, transport).currentUser()

        assertEquals("user_1", user.id)
        assertEquals("Ada", user.firstName)
        assertEquals("email_1", user.primaryEmailId)
        assertEquals("ada@example.com", user.emailAddresses.first().emailAddress)
        assertTrue(user.emailAddresses.first().verified)
        assertEquals("google", user.externalAccounts.first().provider)
        assertEquals("pro", user.publicMetadata?.string("plan"))

        val cookie = transport.recorded.last().headers["Cookie"]
        assertTrue(cookie!!.contains("__atlas_rt=rt"))
        assertTrue(cookie.contains("__session=jwt"))
    }

    @Test
    fun currentUserWithoutSessionThrowsNotSignedIn() = runBlocking {
        val error = assertFailsWith<AtlasException> {
            client(InMemoryTokenStore(), FakeTransport()).currentUser()
        }.error
        assertEquals(AtlasError.NotSignedIn, error)
    }

    // ---- OAuth authorize URL --------------------------------------------------

    @Test
    fun oauthAuthorizeUrlReturnsProviderUrl() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"sign_in_attempt","id":"sia_o","status":"needs_oauth_callback","authorization_url":"https://accounts.google.com/o/oauth2/auth?x=1"}""")

        val url = client(InMemoryTokenStore(), transport).oauthAuthorizeUrl("google", "myapp://callback")

        assertEquals("https://accounts.google.com/o/oauth2/auth?x=1", url)
        assertEquals("https://clerk.example.com/v1/client/sign_ins/oauth", transport.recorded[0].url)
        assertEquals("google", bodyJson(transport.recorded[0]).string("provider"))
        assertEquals("myapp://callback", bodyJson(transport.recorded[0]).string("redirect_url"))
    }

    // ---- refresh rotates the stored token ------------------------------------

    @Test
    fun refreshRotatesStoredToken() = runBlocking {
        val store = InMemoryTokenStore(AtlasSession("sess_1", "old", "rt_old"))
        val transport = FakeTransport()
        transport.enqueue(200, """{"object":"session_tokens","jwt":"jwt_new","session_id":"sess_1","expires_in":60}""", setCookie = "__atlas_rt=rt_new; Path=/v1; HttpOnly")

        val rotated = client(store, transport).refresh()

        assertEquals("jwt_new", rotated.token)
        assertEquals("rt_new", rotated.refreshToken)
        assertEquals("https://clerk.example.com/v1/client/sessions/sess_1/tokens", transport.recorded[0].url)
        assertEquals("jwt_new", store.load()?.token)
    }

    // ---- sign-out clears storage even when revoke fails ----------------------

    @Test
    fun signOutClearsStoreEvenWhenRevokeFails() = runBlocking {
        val store = InMemoryTokenStore(AtlasSession("sess_1", "jwt", "rt"))
        // No stub enqueued: revoke fails at transport; store must still clear.
        client(store, FakeTransport()).signOut()
        assertNull(store.load())
    }

    // ---- token store round-trip ----------------------------------------------

    @Test
    fun inMemoryTokenStoreRoundTrip() {
        val store = InMemoryTokenStore()
        assertNull(store.load())
        val session = AtlasSession("sess_1", "jwt", "rt")
        store.save(session)
        assertEquals(session, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun securePrefsTokenStoreRoundTrip() {
        // The EncryptedSharedPreferences-shaped store, exercised against a fake
        // KeyValueStore so the serialization is covered without an Android device.
        val backing = HashMap<String, String>()
        val kv = object : KeyValueStore {
            override fun getString(key: String) = backing[key]
            override fun putString(key: String, value: String) { backing[key] = value }
            override fun remove(key: String) { backing.remove(key) }
        }
        val store = SecurePrefsTokenStore(kv)
        assertNull(store.load())
        val session = AtlasSession("sess_1", "jwt.abc", "rt.xyz")
        store.save(session)
        assertEquals(session, store.load())
        store.clear()
        assertNull(store.load())
    }

    companion object {
        val USER_JSON = """
        {
          "object": "user",
          "id": "user_1",
          "first_name": "Ada",
          "last_name": "Lovelace",
          "username": null,
          "image_url": "https://img.example.com/a.png",
          "locale": "en-US",
          "public_metadata": { "plan": "pro" },
          "unsafe_metadata": {},
          "mfa_enabled": false,
          "has_password": true,
          "created_at": 1700000000000,
          "primary_email_id": "email_1",
          "email_addresses": [
            { "object": "email_address", "id": "email_1", "email_address": "ada@example.com", "verified": true, "primary": true }
          ],
          "external_accounts": [
            { "object": "external_account", "id": "ext_1", "provider": "google", "provider_email": "ada@gmail.com", "connected_at": 1700000000000 }
          ],
          "passkeys": []
        }
        """.trimIndent()
    }
}
