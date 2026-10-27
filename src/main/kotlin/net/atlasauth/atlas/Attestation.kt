package net.atlasauth.atlas

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The OS proof for one single-use nonce (see [AtlasClient.attestationProvider]). */
sealed class AtlasAttestationProof {
    /** A Google Play Integrity token whose request nonce is the server nonce. */
    data class PlayIntegrity(val token: String) : AtlasAttestationProof()

    /** Apple App Attest assertion plus the registration receipt (rarely used from Kotlin Multiplatform). */
    data class AppAttest(val assertion: String, val receipt: String) : AtlasAttestationProof()

    /** Apple DeviceCheck token (base64), for devices without App Attest. */
    data class DeviceCheck(val token: String) : AtlasAttestationProof()
}

/**
 * App and device attestation for native clients. When an instance turns on
 * `attackProtection.appAttestation`, sign-up, sign-in and SMS-send requests must
 * carry an `Atlas-Attestation` proof. Set [AtlasClient.attestationProvider] and
 * the client fetches a single-use nonce and attaches the proof to those calls.
 */
internal object AtlasAttestation {
    fun isGuarded(path: String, method: String): Boolean {
        if (method != "POST") return false
        val bare = path.substringBefore('?')
        return bare == "/v1/client/sign_ups" || bare == "/v1/client/sign_ins" || bare == "/v1/client/me/phone_numbers" ||
            (bare.startsWith("/v1/client/me/phone_numbers/") && bare.endsWith("/prepare_verification"))
    }

    /** base64url JSON, the format the server reads from the `Atlas-Attestation` header. */
    fun header(nonce: String, proof: AtlasAttestationProof): String {
        val body = buildJsonObject {
            put("n", nonce)
            when (proof) {
                is AtlasAttestationProof.PlayIntegrity -> { put("p", "android"); put("t", proof.token) }
                is AtlasAttestationProof.AppAttest -> { put("p", "ios"); put("a", proof.assertion); put("r", proof.receipt) }
                is AtlasAttestationProof.DeviceCheck -> { put("p", "ios_dc"); put("t", proof.token) }
            }
        }
        return base64Url(body.toString().toByteArray())
    }

    // java.util.Base64 needs API 26 and android.util.Base64 is stubbed in unit tests (minSdk is 24).
    internal fun base64Url(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(alphabet[b0 shr 2]).append(alphabet[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) out.append(alphabet[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) out.append(alphabet[b2 and 63])
            i += 3
        }
        return out.toString()
    }
}
