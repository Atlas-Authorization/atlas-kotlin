package com.atlas.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Organizations, session listing/revoke, /me mutations, and the JSON writer. */
class ClientSurfaceTest {
    private val pk = "pk_test_123"

    private fun signedIn(transport: FakeTransport): AtlasClient =
        AtlasClient(
            publishableKey = pk,
            frontendApi = "clerk.example.com",
            tokenStore = InMemoryTokenStore(AtlasSession("sess_1", "jwt", "rt")),
            transport = transport,
            dispatcher = Dispatchers.Unconfined,
        )

    private fun body(request: HttpRequest): JsonValue = JsonValue.parse(request.body!!)

    // ---- JSON writer ---------------------------------------------------------

    @Test
    fun jsonWriterSerializesNestedObjectsArraysAndOmitsNulls() {
        val metadata = JsonValue.Obj(
            linkedMapOf(
                "theme" to JsonValue.Str("dark"),
                "count" to JsonValue.Num(3.0),
                "beta" to JsonValue.Bool(true),
            ),
        )
        val out = jsonBody(
            "name" to jstr("Ada"),
            "missing" to jstr(null),
            "scopes" to jarr(listOf("read", "write")),
            "metadata" to metadata,
        )
        val parsed = JsonValue.parse(out)
        assertEquals("Ada", parsed.string("name"))
        assertNull((parsed as JsonValue.Obj).entries["missing"]) // null pair dropped
        assertEquals("read", (parsed.arr("scopes")!!.items[0] as JsonValue.Str).value)
        assertEquals("dark", parsed.obj("metadata")!!.string("theme"))
        // A whole number round-trips without a ".0" the server never sends.
        assertTrue(out.contains("\"count\":3"))
        assertTrue(!out.contains("3.0"))
    }

    // ---- organizations -------------------------------------------------------

    @Test
    fun organizationMembershipsParsesList() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(
            200,
            """{"object":"list","data":[
               {"object":"organization_membership","role":"admin","organization":{"object":"organization","id":"org_1","name":"Acme","slug":"acme","image_url":null,"public_metadata":{"tier":"pro"}}}
            ]}""",
        )
        val memberships = signedIn(transport).organizationMemberships()
        assertEquals("https://clerk.example.com/v1/client/me/organizations", transport.recorded[0].url)
        assertEquals(1, memberships.size)
        assertEquals("admin", memberships[0].role)
        assertEquals("Acme", memberships[0].organization.name)
        assertEquals("pro", memberships[0].organization.publicMetadata?.string("tier"))
    }

    @Test
    fun createOrganizationSendsNameAndSlug() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"organization","id":"org_9","name":"Beta","slug":"beta","created_at":1700000000000,"max_allowed_memberships":5}""")
        val org = signedIn(transport).createOrganization("Beta", "beta")
        assertEquals("https://clerk.example.com/v1/client/organizations", transport.recorded[0].url)
        assertEquals("Beta", body(transport.recorded[0]).string("name"))
        assertEquals("beta", body(transport.recorded[0]).string("slug"))
        assertEquals("org_9", org.id)
        assertEquals(5, org.maxAllowedMemberships)
    }

    @Test
    fun organizationCreationForbiddenSurfacesApiError() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(403, """{"errors":[{"code":"forbidden","message":"This instance does not allow users to create organizations."}]}""")
        val error = assertFailsWith<AtlasException> {
            signedIn(transport).createOrganization("Beta", "beta")
        }.error
        assertEquals(403, error.status)
        assertEquals("forbidden", error.code)
    }

    // ---- sessions ------------------------------------------------------------

    @Test
    fun sessionsListMarksCurrentDevice() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(
            200,
            """{"object":"list","data":[
               {"object":"session","id":"sess_1","status":"active","current":true,"last_active_at":1700000000000,"expire_at":1700003600000,"ip_address":"1.2.3.4","device_label":"Chrome on macOS","browser":"Chrome","os":"macOS","location":"Berlin, DE"},
               {"object":"session","id":"sess_2","status":"active","current":false,"device_label":"Safari on iOS"}
            ]}""",
        )
        val sessions = signedIn(transport).sessions()
        assertEquals("https://clerk.example.com/v1/client/sessions", transport.recorded[0].url)
        // The Cookie header replays both the session JWT and the refresh token.
        val cookie = transport.recorded[0].headers["Cookie"]!!
        assertTrue(cookie.contains("__session=jwt"))
        assertTrue(cookie.contains("__atlas_rt=rt"))
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].current)
        assertEquals("Chrome on macOS", sessions[0].deviceLabel)
        assertEquals("Berlin, DE", sessions[0].location)
        assertTrue(!sessions[1].current)
    }

    @Test
    fun revokeSessionHitsRevokeEndpoint() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(200, """{"object":"session","id":"sess_2","status":"revoked"}""")
        signedIn(transport).revokeSession("sess_2")
        assertEquals("https://clerk.example.com/v1/client/sessions/sess_2/revoke", transport.recorded[0].url)
        assertEquals("POST", transport.recorded[0].method)
    }

    @Test
    fun revokeOtherSessionsReturnsCount() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(200, """{"object":"client","sessions_revoked":4}""")
        val count = signedIn(transport).revokeOtherSessions()
        assertEquals("https://clerk.example.com/v1/client/sessions/revoke_all", transport.recorded[0].url)
        assertEquals(4, count)
    }

    @Test
    fun authedCallWithoutSessionThrowsNotSignedIn() = runBlocking {
        val transport = FakeTransport()
        val client = AtlasClient(pk, "clerk.example.com", InMemoryTokenStore(), transport, Dispatchers.Unconfined)
        val error = assertFailsWith<AtlasException> { client.sessions() }.error
        assertEquals(AtlasError.NotSignedIn, error)
    }

    // ---- /me mutations -------------------------------------------------------

    @Test
    fun updateProfileSendsOnlyGivenFieldsAndNestsMetadata() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(200, AtlasClientTest.USER_JSON)
        val metadata = JsonValue.Obj(linkedMapOf("theme" to JsonValue.Str("dark")))
        signedIn(transport).updateProfile(firstName = "Grace", unsafeMetadata = metadata)

        assertEquals("PATCH", transport.recorded[0].method)
        assertEquals("https://clerk.example.com/v1/client/me", transport.recorded[0].url)
        val sent = body(transport.recorded[0])
        assertEquals("Grace", sent.string("first_name"))
        // Fields not passed are omitted, not sent as null.
        assertNull((sent as JsonValue.Obj).entries["last_name"])
        assertNull(sent.entries["public_metadata"])
        assertEquals("dark", sent.obj("unsafe_metadata")!!.string("theme"))
    }

    @Test
    fun emailLifecycleAddVerifyPrimaryDelete() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"email_address","id":"em_1","email_address":"new@b.com","verified":false,"primary":false}""")
        transport.enqueue(200, """{"object":"email_address","id":"em_1","email_address":"new@b.com","verified":true}""")
        transport.enqueue(200, """{"object":"email_address","id":"em_1","primary":true}""")
        transport.enqueue(200, """{"object":"email_address","id":"em_1","deleted":true}""")

        val client = signedIn(transport)
        val added = client.addEmailAddress("new@b.com")
        assertEquals("new@b.com", body(transport.recorded[0]).string("email_address"))
        assertTrue(!added.verified)

        val verified = client.verifyEmailAddress("em_1", "123456")
        assertEquals("https://clerk.example.com/v1/client/me/email_addresses/em_1/attempt_verification", transport.recorded[1].url)
        assertEquals("123456", body(transport.recorded[1]).string("code"))
        assertTrue(verified.verified)

        client.setPrimaryEmail("em_1")
        assertEquals("https://clerk.example.com/v1/client/me/email_addresses/em_1/primary", transport.recorded[2].url)

        client.deleteEmailAddress("em_1")
        assertEquals("DELETE", transport.recorded[3].method)
        assertEquals("https://clerk.example.com/v1/client/me/email_addresses/em_1", transport.recorded[3].url)
    }

    @Test
    fun connectExternalAccountReturnsAuthorizationUrlWithScopes() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(201, """{"object":"external_account_connection","provider":"github","attempt_id":"at_1","authorization_url":"https://github.com/login/oauth/authorize?x=1","scopes":["repo"]}""")
        val url = signedIn(transport).connectExternalAccount("github", "myapp://cb", additionalScopes = listOf("repo"))
        assertEquals("https://clerk.example.com/v1/client/me/external_accounts/connect", transport.recorded[0].url)
        val sent = body(transport.recorded[0])
        assertEquals("github", sent.string("provider"))
        assertEquals("myapp://cb", sent.string("redirect_url"))
        assertEquals("repo", (sent.arr("additional_scopes")!!.items[0] as JsonValue.Str).value)
        assertEquals("https://github.com/login/oauth/authorize?x=1", url)
    }

    @Test
    fun deleteExternalAccountUsesDelete() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(200, """{"object":"external_account","id":"ext_1","deleted":true}""")
        signedIn(transport).deleteExternalAccount("ext_1")
        assertEquals("DELETE", transport.recorded[0].method)
        assertEquals("https://clerk.example.com/v1/client/me/external_accounts/ext_1", transport.recorded[0].url)
    }

    @Test
    fun changePasswordSendsBothAndReturnsRevokedCount() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(200, """{"object":"user","id":"user_1","sessions_revoked":2}""")
        val revoked = signedIn(transport).changePassword("old", "newpass123")
        assertEquals("https://clerk.example.com/v1/client/me/change_password", transport.recorded[0].url)
        assertEquals("old", body(transport.recorded[0]).string("current_password"))
        assertEquals("newpass123", body(transport.recorded[0]).string("new_password"))
        assertEquals(2, revoked)
    }

    @Test
    fun setPasswordSendsPassword() = runBlocking {
        val transport = FakeTransport()
        transport.enqueue(200, """{"object":"user","id":"user_1","has_password":true}""")
        signedIn(transport).setPassword("firstpass123")
        assertEquals("https://clerk.example.com/v1/client/me/set_password", transport.recorded[0].url)
        assertEquals("firstpass123", body(transport.recorded[0]).string("password"))
    }
}
