package net.atlasauth.atlas

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Play Integrity, wired to [AtlasClient.attestationProvider]:
 *
 * ```kotlin
 * client.attestationProvider = PlayIntegrityProvider(context, cloudProjectNumber)::prove
 * ```
 *
 * `cloudProjectNumber` is the Google Cloud project linked to your Play Console app.
 */
class PlayIntegrityProvider(context: Context, private val cloudProjectNumber: Long) {
    private val manager = IntegrityManagerFactory.create(context.applicationContext)

    /** Requests a token whose nonce is the server nonce (Play Integrity wants it base64url). */
    suspend fun prove(nonce: String): AtlasAttestationProof = suspendCancellableCoroutine { cont ->
        manager
            .requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(cloudProjectNumber).build())
            .addOnSuccessListener { cont.resume(AtlasAttestationProof.PlayIntegrity(it.token())) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
