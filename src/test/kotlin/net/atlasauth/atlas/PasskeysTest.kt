package net.atlasauth.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-logic tests for the WebAuthn-JSON → `/finish`-body mapping. They need no
 * device, emulator, or Credential Manager — only the standard WebAuthn JSON the
 * platform returns and the WebAuthn options the server's `begin` sends.
 */
class PasskeysTest {

    // A registration `begin` response (WebAuthn creation options), trimmed.
    private val registerBegin = """
        {
          "challenge": "Y2hhbGxlbmdlX3JlZw",
          "rp": { "id": "fapi.acme.atlasauth.net", "name": "Acme" },
          "user": { "id": "dXNlcl8x", "name": "ada@acme.com", "displayName": "Ada" },
          "pubKeyCredParams": [ { "type": "public-key", "alg": -7 } ],
          "excludeCredentials": [],
          "timeout": 60000,
          "attestation": "none"
        }
    """.trimIndent()

    // What Credential Manager's CreatePublicKeyCredentialResponse.registrationResponseJson looks like.
    private val registrationResponse = """
        {
          "id": "Y3JlZF9hYmM",
          "rawId": "Y3JlZF9hYmM",
          "type": "public-key",
          "authenticatorAttachment": "platform",
          "response": {
            "clientDataJSON": "Y2xpZW50X2RhdGFfcmVn",
            "attestationObject": "YXR0ZXN0YXRpb25fb2Jq",
            "transports": [ "internal" ]
          },
          "clientExtensionResults": {}
        }
    """.trimIndent()

    // An assertion `begin` response (WebAuthn request options + echoed handle).
    private val signInBegin = """
        {
          "handle": "handle_123",
          "challenge": "Y2hhbGxlbmdlX2F1dGg",
          "rpId": "fapi.acme.atlasauth.net",
          "allowCredentials": [ { "type": "public-key", "id": "Y3JlZF9hYmM" } ],
          "userVerification": "required",
          "timeout": 60000
        }
    """.trimIndent()

    // What Credential Manager's PublicKeyCredential.authenticationResponseJson looks like.
    private val authenticationResponse = """
        {
          "id": "Y3JlZF9hYmM",
          "rawId": "Y3JlZF9hYmM",
          "type": "public-key",
          "authenticatorAttachment": "platform",
          "response": {
            "clientDataJSON": "Y2xpZW50X2RhdGFfYXV0aA",
            "authenticatorData": "YXV0aF9kYXRh",
            "signature": "c2lnbmF0dXJl",
            "userHandle": "dXNlcl8x"
          },
          "clientExtensionResults": {}
        }
    """.trimIndent()

    @Test
    fun registrationBodyMapsExactFieldsAndEchoesChallenge() {
        val body = toRegistrationFinishBody(registerBegin, registrationResponse, name = null)

        // Challenge is echoed from the server's options, not the platform response.
        assertEquals("Y2hhbGxlbmdlX3JlZw", body["challenge"])
        assertEquals("YXR0ZXN0YXRpb25fb2Jq", body["attestation_object"])
        assertEquals("Y2xpZW50X2RhdGFfcmVn", body["client_data_json"])
        // No name requested → the optional field is absent, not empty.
        assertFalse(body.containsKey("name"))
        // Exactly the contract's fields.
        assertEquals(setOf("challenge", "attestation_object", "client_data_json"), body.keys)
    }

    @Test
    fun registrationBodyIncludesNameWhenGiven() {
        val body = toRegistrationFinishBody(registerBegin, registrationResponse, name = "Pixel 8")
        assertEquals("Pixel 8", body["name"])
    }

    @Test
    fun assertionBodyMapsExactFields() {
        val body = toAssertionFinishBody(signInBegin, authenticationResponse)

        assertEquals("handle_123", body["handle"])
        // Challenge echoed from the server's options.
        assertEquals("Y2hhbGxlbmdlX2F1dGg", body["challenge"])
        // credential_id = rawId.
        assertEquals("Y3JlZF9hYmM", body["credential_id"])
        assertEquals("YXV0aF9kYXRh", body["authenticator_data"])
        assertEquals("Y2xpZW50X2RhdGFfYXV0aA", body["client_data_json"])
        assertEquals("c2lnbmF0dXJl", body["signature"])
        assertEquals(
            setOf("handle", "challenge", "credential_id", "authenticator_data", "client_data_json", "signature"),
            body.keys,
        )
    }

    @Test
    fun assertionCredentialIdFallsBackToIdWhenRawIdAbsent() {
        val responseWithoutRawId = """
            {
              "id": "only_id",
              "type": "public-key",
              "response": {
                "clientDataJSON": "Yw", "authenticatorData": "YQ", "signature": "cw"
              }
            }
        """.trimIndent()
        val body = toAssertionFinishBody(signInBegin, responseWithoutRawId)
        assertEquals("only_id", body["credential_id"])
    }

    @Test
    fun assertionHandleOmittedWhenServerDidNotIssueOne() {
        val beginWithoutHandle = """
            { "challenge": "Y2hhbGxlbmdl", "rpId": "fapi.acme.atlasauth.net" }
        """.trimIndent()
        val body = toAssertionFinishBody(beginWithoutHandle, authenticationResponse)
        assertFalse(body.containsKey("handle"))
        assertEquals("Y2hhbGxlbmdl", body["challenge"])
    }

    @Test
    fun registrationMissingChallengeThrowsDecoding() {
        val beginNoChallenge = """{ "rp": { "id": "x" } }"""
        try {
            toRegistrationFinishBody(beginNoChallenge, registrationResponse, name = null)
            fail("expected a Decoding error")
        } catch (e: AtlasException.Decoding) {
            assertTrue(e.detail.contains("challenge"))
        }
    }

    @Test
    fun registrationMissingResponseObjectThrowsDecoding() {
        val noResponse = """{ "id": "x", "rawId": "x", "type": "public-key" }"""
        try {
            toRegistrationFinishBody(registerBegin, noResponse, name = null)
            fail("expected a Decoding error")
        } catch (e: AtlasException.Decoding) {
            assertTrue(e.detail.contains("response"))
        }
    }

    @Test
    fun malformedJsonThrowsDecoding() {
        try {
            toAssertionFinishBody("not json at all", authenticationResponse)
            fail("expected a Decoding error")
        } catch (e: AtlasException.Decoding) {
            assertTrue(e.detail.contains("parse"))
        }
    }
}
