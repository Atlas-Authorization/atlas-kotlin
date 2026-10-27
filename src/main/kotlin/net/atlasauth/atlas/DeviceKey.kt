package net.atlasauth.atlas

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Device-bound biometric sign-in (the Kotlin peer of the JS registerDeviceKey /
 * signInWithDeviceKey). An ES256 (P-256) key lives in the Android Keystore and is
 * unlocked by BiometricPrompt; Atlas stores only the public half.
 *
 *   register: POST /v1/client/me/device_keys/challenge -> sign -> /device_keys
 *   sign in : POST /v1/client/sign_ins/device_key/challenge -> sign -> /finish
 */

/** A P-256 public key as a JWK (`x`, `y` base64url). */
data class DeviceKeyJwk(val x: String, val y: String)

/** The keystore contract; swap in your own (or a fake in tests). */
interface DeviceKeySigner {
    /** Creates the keypair; `biometric` asks for a key that needs the biometric prompt to use. */
    suspend fun createKey(biometric: Boolean): DeviceKeyJwk

    /** Signs the UTF-8 message with ES256 and returns base64url IEEE-P1363 (`r || s`). */
    suspend fun sign(message: String): String
}

/** The `device_key` resource the server returns. */
@Serializable
data class DeviceKey(
    val id: String,
    val label: String? = null,
    val platform: String? = null,
    @SerialName("biometric_bound") val biometricBound: Boolean = false,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
private data class DeviceKeyChallenge(val nonce: String)

internal object DeviceKeyCodec {
    /** The exact string the device signs; mirrors the server. */
    fun message(purpose: String, instanceId: String, keyId: String, nonce: String): String =
        "atlas-device-key:v1:$purpose:$instanceId:$keyId:$nonce"

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= 32) { "coordinate is wider than 256 bits" }
        return ByteArray(32 - unsigned.size) + unsigned
    }

    fun jwk(x: BigInteger, y: BigInteger): DeviceKeyJwk =
        DeviceKeyJwk(AtlasAttestation.base64Url(fixed32(x)), AtlasAttestation.base64Url(fixed32(y)))

    /** Converts an ASN.1 DER ECDSA signature (what the JCA emits) to the 64-byte `r || s`; null when malformed. */
    fun p1363(der: ByteArray): ByteArray? {
        var i = 0
        fun readLength(): Int? {
            if (i >= der.size) return null
            val first = der[i++].toInt() and 0xff
            if (first < 0x80) return first
            val n = first and 0x7f
            if (n == 0 || n > 2 || i + n > der.size) return null
            var len = 0
            repeat(n) { len = (len shl 8) or (der[i++].toInt() and 0xff) }
            return len
        }
        fun readInt(): ByteArray? {
            if (i >= der.size || der[i] != 0x02.toByte()) return null
            i++
            val len = readLength() ?: return null
            if (len <= 0 || i + len > der.size) return null
            val v = BigInteger(1, der.copyOfRange(i, i + len))
            i += len
            return if (v.bitLength() > 256) null else fixed32(v)
        }
        if (der.isEmpty() || der[0] != 0x30.toByte()) return null
        i = 1
        readLength() ?: return null
        val r = readInt() ?: return null
        val s = readInt() ?: return null
        return r + s
    }
}

/** An Android Keystore signer behind [BiometricPrompt] (StrongBox-backed when the device has it). */
class AndroidKeystoreDeviceKeySigner @JvmOverloads constructor(
    private val activity: FragmentActivity,
    private val alias: String = "net.atlasauth.device-key",
    private val title: String = "Sign in",
    private val negativeButtonText: String = "Cancel",
) : DeviceKeySigner {
    private var biometricBound = true

    override suspend fun createKey(biometric: Boolean): DeviceKeyJwk {
        biometricBound = biometric
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(alias)
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
            if (biometric) {
                b.setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Every signature needs a fresh biometric (timeout 0 = per-use).
                    b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) b.setInvalidatedByBiometricEnrollment(true)
            }
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val pair = try {
            generator.initialize(spec(strongBox = true)); generator.generateKeyPair()
        } catch (e: Exception) {
            // No StrongBox on this device: the TEE-backed key is still hardware-bound.
            generator.initialize(spec(strongBox = false)); generator.generateKeyPair()
        }
        val point = (pair.public as ECPublicKey).w
        return DeviceKeyCodec.jwk(point.affineX, point.affineY)
    }

    override suspend fun sign(message: String): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as? PrivateKey
            ?: throw AtlasException.Ceremony("No device key is registered on this device.")
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        val signed = if (biometricBound) authenticate(signature) else signature
        signed.update(message.toByteArray(Charsets.UTF_8))
        val raw = DeviceKeyCodec.p1363(signed.sign())
            ?: throw AtlasException.Ceremony("The device key produced a malformed signature.")
        return AtlasAttestation.base64Url(raw)
    }

    private suspend fun authenticate(signature: Signature): Signature =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlocked = result.cryptoObject?.signature
                        if (unlocked != null) cont.resume(unlocked)
                        else cont.resumeWithException(AtlasException.Ceremony("The biometric prompt returned no key."))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        cont.resumeWithException(AtlasException.Ceremony(errString.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(negativeButtonText)
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            activity.runOnUiThread { prompt.authenticate(info, BiometricPrompt.CryptoObject(signature)) }
            cont.invokeOnCancellation { activity.runOnUiThread { prompt.cancelAuthentication() } }
        }
}

/** Register / sign in / remove device keys over [client]. */
class DeviceKeyManager(private val client: AtlasClient, private val signer: DeviceKeySigner) {
    /**
     * Registers this device's key for the signed-in user (needs a recent
     * re-authentication and `accountUx.deviceKeys.enabled`). Keep the returned
     * [DeviceKey.id] and pass it to [signInWithDeviceKey].
     *
     * @param instanceId your instance id (`ins_...`), shown in the dashboard.
     */
    @JvmOverloads
    suspend fun registerDeviceKey(instanceId: String, label: String? = null, biometric: Boolean = true): DeviceKey {
        val challenge = client.decodeOrThrow(
            client.postRaw("/v1/client/me/device_keys/challenge", emptyMap(), authenticated = true),
            DeviceKeyChallenge.serializer(),
        )
        val jwk = signer.createKey(biometric)
        // Proof of possession: the register message names the key by its x coordinate.
        val signature = signer.sign(DeviceKeyCodec.message("register", instanceId, jwk.x, challenge.nonce))
        val body = buildJsonObject {
            put("nonce", challenge.nonce)
            put("public_key", buildJsonObject { put("kty", "EC"); put("crv", "P-256"); put("x", jwk.x); put("y", jwk.y) })
            put("signature", signature)
            put("biometric_bound", biometric)
            put("platform", "android")
            if (!label.isNullOrEmpty()) put("label", label)
        }
        val response = client.requestRaw("POST", "/v1/client/me/device_keys", body.toString(), authenticated = true)
        return client.decodeOrThrow(response.body, DeviceKey.serializer())
    }

    /** Removes one of the signed-in user's device keys (needs a recent re-authentication). */
    suspend fun removeDeviceKey(id: String) {
        client.requestRaw("DELETE", "/v1/client/me/device_keys/$id", null, authenticated = true)
    }

    /** Signs in with a registered device key (biometric prompt) and persists the session. */
    suspend fun signInWithDeviceKey(instanceId: String, deviceKeyId: String): AtlasUser {
        val challenge = client.decodeOrThrow(
            client.postRaw("/v1/client/sign_ins/device_key/challenge", mapOf("device_key_id" to deviceKeyId), authenticated = false),
            DeviceKeyChallenge.serializer(),
        )
        val signature = signer.sign(DeviceKeyCodec.message("sign_in", instanceId, deviceKeyId, challenge.nonce))
        val body = buildJsonObject {
            put("device_key_id", deviceKeyId)
            put("nonce", challenge.nonce)
            put("signature", signature)
        }
        val response = client.requestRaw("POST", "/v1/client/sign_ins/device_key/finish", body.toString(), authenticated = false)
        val attempt = client.decodeOrThrow(response.body, SignInAttempt.serializer())
        if (!attempt.isComplete) {
            throw AtlasException.Api(
                statusCode = 200,
                errors = listOf(AtlasErrorItem(code = "sign_in_not_complete", message = "Device-key sign-in needs an additional step: ${attempt.status}.")),
            )
        }
        return client.persistDirectSession(attempt.createdSessionId, response)
    }
}
