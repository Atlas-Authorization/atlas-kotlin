package net.atlasauth.atlas

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native passkeys / WebAuthn.
 *
 * A passkey ceremony is a two-call dance that mirrors the web: the server's
 * `begin` response describes the ceremony (WebAuthn options — `rpId`, `challenge`,
 * allow/exclude lists, …), the platform authenticator performs it, and the result
 * is POSTed to `finish`. The browser runs the ceremony with
 * `navigator.credentials`; on Android there is no `navigator`, so this delegates
 * the ceremony to the Jetpack **Credential Manager** (`androidx.credentials`) and
 * owns only the two HTTP calls and the body mapping between them.
 *
 *   register: POST /v1/client/me/passkeys/begin     → create → /finish
 *   sign in : POST /v1/client/sign_ins/passkey/begin → get    → /finish
 *
 * Credential Manager consumes the server's standard WebAuthn options JSON
 * verbatim and returns a standard WebAuthn response JSON, so the `begin` body is
 * passed straight into the request and the `rpId` is taken from it — never
 * hardcoded. The ceremony needs an **Activity** `Context` to show its UI, so that
 * is threaded through the public API per call.
 *
 * All network methods are `suspend` functions; call them from a coroutine.
 */
class PasskeyManager(
    private val client: AtlasClient,
    private val authenticator: PasskeyAuthenticator,
) {
    /**
     * Register a passkey for the signed-in user (`POST /v1/client/me/passkeys/begin`
     * → Credential Manager `createCredential` → `…/finish`). Requires a stored
     * session — throws [AtlasException.NotSignedIn] otherwise.
     *
     * @param activity an **Activity** context — Credential Manager shows system UI,
     *   so an application context will not do.
     * @param name an optional human label for the credential, shown in the user's
     *   device passkey list.
     * @throws AtlasException.Ceremony if the user cancels or the authenticator fails.
     * @throws AtlasException.Api on a server rejection.
     */
    @JvmOverloads
    suspend fun registerPasskey(activity: Context, name: String? = null) {
        val beginJson = client.postRaw("/v1/client/me/passkeys/begin", emptyMap(), authenticated = true)
        val registrationResponseJson = authenticator.createCredential(activity, beginJson)
        val body = toRegistrationFinishBody(beginJson, registrationResponseJson, name)
        client.postRaw("/v1/client/me/passkeys/finish", body, authenticated = true)
    }

    /**
     * Sign in with a passkey, end to end
     * (`POST /v1/client/sign_ins/passkey/begin` → Credential Manager
     * `getCredential` → `…/finish`), returning the freshly signed-in user. Sends
     * only the publishable key — no session is required. Passkey finish mints the
     * session DIRECTLY (no ticket exchange): the completed attempt carries the jwt
     * + created_session_id and the refresh token as a Set-Cookie.
     *
     * A finish that does not reach `complete` (a factor is still owed) surfaces as
     * [AtlasException.Api] with `sign_in_not_complete`.
     *
     * @param activity an **Activity** context for the Credential Manager UI.
     * @throws AtlasException.Ceremony if the user cancels or the authenticator fails.
     */
    suspend fun signInWithPasskey(activity: Context): AtlasUser {
        val beginJson = client.postRaw("/v1/client/sign_ins/passkey/begin", emptyMap(), authenticated = false)
        val authenticationResponseJson = authenticator.getCredential(activity, beginJson)
        val body = toAssertionFinishBody(beginJson, authenticationResponseJson)
        return client.completePasskeySignIn(body)
    }

    companion object {
        /**
         * Convenience factory wiring the default [CredentialManagerAuthenticator].
         * This is the entry point most apps use.
         *
         * @param context any context — used only to construct the Credential
         *   Manager. The per-ceremony **Activity** context is passed to
         *   [registerPasskey] / [signInWithPasskey].
         */
        @JvmStatic
        fun create(context: Context, client: AtlasClient): PasskeyManager =
            PasskeyManager(client, CredentialManagerAuthenticator(CredentialManager.create(context)))
    }
}

/**
 * The seam over the platform authenticator — the one Android-only surface of the
 * passkey flow, injectable so the HTTP + body-mapping logic is testable without a
 * device (mirrors the SDK's `HttpTransport` / `KeyValueStore` seams). Each method
 * takes the server's WebAuthn options JSON and returns the platform's standard
 * WebAuthn response JSON.
 */
interface PasskeyAuthenticator {
    /** Run a registration ceremony; returns the `registrationResponseJson`. */
    suspend fun createCredential(activity: Context, requestJson: String): String

    /** Run an assertion ceremony; returns the `authenticationResponseJson`. */
    suspend fun getCredential(activity: Context, requestJson: String): String
}

/**
 * The default [PasskeyAuthenticator], backed by `androidx.credentials`. Maps a
 * cancellation or platform failure to [AtlasException.Ceremony].
 */
class CredentialManagerAuthenticator(
    private val credentialManager: CredentialManager,
) : PasskeyAuthenticator {

    override suspend fun createCredential(activity: Context, requestJson: String): String {
        val response = try {
            credentialManager.createCredential(
                context = activity,
                request = CreatePublicKeyCredentialRequest(requestJson),
            )
        } catch (e: CreateCredentialException) {
            throw AtlasException.Ceremony(e.message ?: e.type)
        }
        val publicKeyResponse = response as? CreatePublicKeyCredentialResponse
            ?: throw AtlasException.Ceremony(
                "Unexpected create-credential response: ${response::class.java.name}",
            )
        return publicKeyResponse.registrationResponseJson
    }

    override suspend fun getCredential(activity: Context, requestJson: String): String {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetPublicKeyCredentialOption(requestJson))
            .build()
        val response = try {
            credentialManager.getCredential(context = activity, request = request)
        } catch (e: GetCredentialException) {
            throw AtlasException.Ceremony(e.message ?: e.type)
        }
        val credential = response.credential as? PublicKeyCredential
            ?: throw AtlasException.Ceremony(
                "Unexpected credential type: ${response.credential::class.java.name}",
            )
        return credential.authenticationResponseJson
    }
}

/* ── pure body mapping (unit-tested without a device) ──────────────────────── */

/** Tolerant parser reused by the mapping functions. */
private val mappingJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun parseObject(label: String, raw: String): JsonObject = try {
    mappingJson.parseToJsonElement(raw).jsonObject
} catch (e: Throwable) {
    throw AtlasException.Decoding("Could not parse the $label JSON: ${e.message}")
}

/**
 * Map a registration `begin` response + the platform `registrationResponseJson` to
 * the `/v1/client/me/passkeys/finish` body.
 *
 * The platform returns standard WebAuthn registration JSON
 * (`{ id, rawId, type, response: { clientDataJSON, attestationObject, … } }`), all
 * base64url. The `challenge` is echoed from the server's options — the server
 * matched and stored it.
 */
internal fun toRegistrationFinishBody(
    beginJson: String,
    registrationResponseJson: String,
    name: String?,
): Map<String, String> {
    val begin = parseObject("passkey begin", beginJson)
    val registration = parseObject("registration response", registrationResponseJson)
    val response = registration["response"]?.jsonObject
        ?: throw AtlasException.Decoding("The registration response had no \"response\" object.")

    val body = linkedMapOf(
        "challenge" to (begin.string("challenge")
            ?: throw AtlasException.Decoding("The passkey begin response had no challenge.")),
        "attestation_object" to (response.string("attestationObject")
            ?: throw AtlasException.Decoding("The registration response had no attestationObject.")),
        "client_data_json" to (response.string("clientDataJSON")
            ?: throw AtlasException.Decoding("The registration response had no clientDataJSON.")),
    )
    if (name != null) body["name"] = name
    return body
}

/**
 * Map an assertion `begin` response + the platform `authenticationResponseJson` to
 * the `/v1/client/sign_ins/passkey/finish` body.
 *
 * The platform returns standard WebAuthn assertion JSON
 * (`{ id, rawId, type, response: { clientDataJSON, authenticatorData, signature,
 * userHandle } }`), all base64url. `credential_id` is the credential's id
 * (`rawId`, falling back to `id`); `handle` and `challenge` are echoed from the
 * server's options (`handle` is omitted when the server did not issue one).
 */
internal fun toAssertionFinishBody(
    beginJson: String,
    authenticationResponseJson: String,
): Map<String, String> {
    val begin = parseObject("passkey begin", beginJson)
    val assertion = parseObject("authentication response", authenticationResponseJson)
    val response = assertion["response"]?.jsonObject
        ?: throw AtlasException.Decoding("The authentication response had no \"response\" object.")

    val credentialId = assertion.string("rawId")
        ?: assertion.string("id")
        ?: throw AtlasException.Decoding("The authentication response had no credential id.")

    val body = linkedMapOf(
        "challenge" to (begin.string("challenge")
            ?: throw AtlasException.Decoding("The passkey begin response had no challenge.")),
        "credential_id" to credentialId,
        "authenticator_data" to (response.string("authenticatorData")
            ?: throw AtlasException.Decoding("The authentication response had no authenticatorData.")),
        "client_data_json" to (response.string("clientDataJSON")
            ?: throw AtlasException.Decoding("The authentication response had no clientDataJSON.")),
        "signature" to (response.string("signature")
            ?: throw AtlasException.Decoding("The authentication response had no signature.")),
    )
    // The server echoes a `handle` back on begin for the finish to carry; omit it
    // (rather than invent one) when the server did not issue one.
    begin.string("handle")?.let { body["handle"] = it }
    return body
}
