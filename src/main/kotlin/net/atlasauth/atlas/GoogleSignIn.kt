package net.atlasauth.atlas

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Native "Sign in with Google" / One-Tap, the §6 id_token flow.
 *
 * Instead of a browser redirect, the app obtains a Google-issued OIDC `id_token`
 * on-device (via the Jetpack Credential Manager's Google-ID provider) and trades
 * it for an Atlas session at `POST /v1/client/sign_ins/id_token`. The exchange
 * is bound to a single-use nonce the server mints, so a captured token cannot be
 * replayed.
 *
 * Two layers, split so the HTTP half is testable without a device:
 *   - [AtlasClient.signInWithIdToken] / [AtlasClient.mintIdTokenNonce] — the pure
 *     network half (unit-tested against MockWebServer).
 *   - [GoogleSignInManager] + [GoogleIdTokenProvider] — the device ceremony
 *     (Credential Manager), behind an injectable seam like the passkey flow.
 */

/**
 * Build the `POST /v1/client/sign_ins/id_token` body: the provider key, the
 * id_token, and the nonce when one was minted (omitted, not null, otherwise).
 * Pure, so it is unit-tested without a device.
 */
internal fun idTokenBody(provider: String, idToken: String, nonce: String?): String =
    buildJsonObject {
        put("provider", provider)
        put("id_token", idToken)
        if (nonce != null) put("nonce", nonce)
    }.toString()

/**
 * Mint a single-use nonce for a native id_token sign-in
 * (`POST /v1/client/sign_ins/id_token/nonce`). Hand the returned nonce to the
 * provider SDK (Credential Manager's [GetGoogleIdOption.Builder.setNonce]), then
 * post the resulting id_token with the SAME nonce via [signInWithIdToken].
 * Returns null when the server did not issue one (e.g. the provider does not
 * support native sign-in) — the caller then proceeds nonce-less or surfaces an
 * error rather than guessing a value.
 */
suspend fun AtlasClient.mintIdTokenNonce(provider: String): String? {
    val response = requestRaw(
        "POST",
        "/v1/client/sign_ins/id_token/nonce",
        buildJsonObject { put("provider", provider) }.toString(),
        authenticated = false,
    )
    return runCatching {
        decodeOrThrow(response.body, NativeNonceResponse.serializer()).nonce
    }.getOrNull()
}

/**
 * Exchange a provider id_token for an Atlas session and return the signed-in user.
 * `POST /v1/client/sign_ins/id_token` → `tickets/exchange` → `/me`.
 *
 * A completed sign-in carries a one-time `ticket`, exchanged for the session the
 * same way every other flow completes. A response that is NOT complete (a second
 * factor is still owed) surfaces as [AtlasException.Api] `sign_in_not_complete`
 * carrying the attempt id — resume it with [AtlasClient.signInFlow] using that id.
 */
suspend fun AtlasClient.signInWithIdToken(
    provider: String,
    idToken: String,
    nonce: String? = null,
): AtlasUser {
    val response = requestRaw(
        "POST",
        "/v1/client/sign_ins/id_token",
        idTokenBody(provider, idToken, nonce),
        authenticated = false,
    )
    val attempt = decodeOrThrow(response.body, FlowAttempt.serializer())
    val ticket = attempt.ticket
    if (!attempt.isComplete || ticket == null) {
        throw AtlasException.Api(
            statusCode = 200,
            errors = listOf(
                AtlasErrorItem(
                    code = "sign_in_not_complete",
                    message = "Native sign-in needs an additional step: ${attempt.status}.",
                ),
            ),
        )
    }
    exchangeTicket(attemptId = attempt.id ?: "", ticket = ticket)
    return currentUser()
}

/**
 * The seam over the platform Google-ID ceremony — the one Android-only surface of
 * the native-Google flow, injectable so the HTTP half is testable without a
 * device (mirrors [PasskeyAuthenticator]). Returns the resolved OIDC id_token.
 */
interface GoogleIdTokenProvider {
    /**
     * Run the Credential Manager Google-ID ceremony and return the id_token.
     *
     * @param activity an Activity context — Credential Manager shows system UI.
     * @param serverClientId your Google **web/server** OAuth client id (NOT the
     *   Android client id) — the audience the id_token is minted for.
     * @param nonce the server-minted nonce to bind the token to this request.
     * @param filterByAuthorizedAccounts show only already-authorized accounts.
     */
    suspend fun getIdToken(
        activity: Context,
        serverClientId: String,
        nonce: String?,
        filterByAuthorizedAccounts: Boolean,
    ): String
}

/** The default [GoogleIdTokenProvider], backed by `androidx.credentials` + the Google-ID provider. */
class CredentialManagerGoogleIdTokenProvider(
    private val credentialManager: CredentialManager,
) : GoogleIdTokenProvider {

    override suspend fun getIdToken(
        activity: Context,
        serverClientId: String,
        nonce: String?,
        filterByAuthorizedAccounts: Boolean,
    ): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .apply { if (nonce != null) setNonce(nonce) }
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = try {
            credentialManager.getCredential(context = activity, request = request)
        } catch (e: GetCredentialException) {
            throw AtlasException.Ceremony(e.message ?: e.type)
        }

        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (e: Throwable) {
                throw AtlasException.Ceremony("Could not read the Google id_token: ${e.message}")
            }
        }
        throw AtlasException.Ceremony(
            "Unexpected credential type for Google sign-in: ${credential.type}",
        )
    }
}

/**
 * Drives native Google sign-in end to end: mint a nonce, run the Credential
 * Manager ceremony for an id_token, and exchange it for an Atlas session.
 *
 * The ceremony shows system UI, so [signIn] takes an **Activity** context.
 */
class GoogleSignInManager(
    private val client: AtlasClient,
    private val provider: GoogleIdTokenProvider,
) {
    /**
     * Sign in with Google and return the user. By default a replay-binding nonce
     * is minted first and carried through the whole exchange.
     *
     * @param activity an Activity context for the Credential Manager UI.
     * @param serverClientId your Google web/server OAuth client id.
     * @param useNonce mint + bind a single-use nonce (recommended; default true).
     * @param filterByAuthorizedAccounts restrict One-Tap to already-authorized
     *   accounts (default false, so a first-time user can pick an account).
     * @throws AtlasException.Ceremony on a cancelled / failed device ceremony.
     * @throws AtlasException.Api on a server rejection or an unfinished sign-in.
     */
    @JvmOverloads
    suspend fun signIn(
        activity: Context,
        serverClientId: String,
        useNonce: Boolean = true,
        filterByAuthorizedAccounts: Boolean = false,
    ): AtlasUser {
        val nonce = if (useNonce) client.mintIdTokenNonce("google") else null
        val idToken = provider.getIdToken(activity, serverClientId, nonce, filterByAuthorizedAccounts)
        return client.signInWithIdToken(provider = "google", idToken = idToken, nonce = nonce)
    }

    companion object {
        /**
         * Convenience factory wiring the default
         * [CredentialManagerGoogleIdTokenProvider]. The entry point most apps use.
         */
        @JvmStatic
        fun create(context: Context, client: AtlasClient): GoogleSignInManager =
            GoogleSignInManager(client, CredentialManagerGoogleIdTokenProvider(CredentialManager.create(context)))
    }
}
