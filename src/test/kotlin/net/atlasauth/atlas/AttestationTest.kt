package net.atlasauth.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AttestationTest {
    @Test
    fun guardsOnlySignUpSignInAndSmsSend() {
        assertTrue(AtlasAttestation.isGuarded("/v1/client/sign_ups", "POST"))
        assertTrue(AtlasAttestation.isGuarded("/v1/client/sign_ins?x=1", "POST"))
        assertTrue(AtlasAttestation.isGuarded("/v1/client/me/phone_numbers/pn_1/prepare_verification", "POST"))
        assertFalse(AtlasAttestation.isGuarded("/v1/client/sign_ins", "GET"))
        assertFalse(AtlasAttestation.isGuarded("/v1/client/me", "POST"))
    }

    @Test
    fun headerIsBase64UrlJson() {
        val header = AtlasAttestation.header("n1", AtlasAttestationProof.PlayIntegrity("tok"))
        assertFalse(header.contains("=") || header.contains("+") || header.contains("/"))
        assertEquals("""{"n":"n1","p":"android","t":"tok"}""", String(Base64.getUrlDecoder().decode(header)))
    }
}
