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
    private val authenticator: PasskeyCeremony,
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
    suspend fun registerPasskey(
        activity: Context,
        name: String? = null,
        prf: PasskeyPrf? = null,
        onPrfResult: ((PasskeyPrfResult) -> Unit)? = null,
    ) {
        val beginJson = client.postRaw("/v1/client/me/passkeys/begin", emptyMap(), authenticated = true)
        val registrationResponseJson = authenticator.createCredential(activity, withPrfExtension(beginJson, prf, registration = true))
        deliverPrf(prf, registrationResponseJson, onPrfResult)
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
     * @param captchaToken the sign-in captcha token, when the instance challenges
     *   sign-ins. Without one such an instance answers 400 `CAPTCHA_REQUIRED`
     *   ([AtlasException.isCaptchaRequired]): run the captcha and call again.
     * @throws AtlasException.Ceremony if the user cancels or the authenticator fails.
     */
    @JvmOverloads
    suspend fun signInWithPasskey(
        activity: Context,
        captchaToken: String? = null,
        prf: PasskeyPrf? = null,
        onPrfResult: ((PasskeyPrfResult) -> Unit)? = null,
    ): AtlasUser {
        val beginJson = client.postRaw(
            "/v1/client/sign_ins/passkey/begin",
            passkeySignInBeginBody(captchaToken),
            authenticated = false,
        )
        val authenticationResponseJson = authenticator.getCredential(activity, withPrfExtension(beginJson, prf, registration = false))
        deliverPrf(prf, authenticationResponseJson, onPrfResult)
        val body = toAssertionFinishBody(beginJson, authenticationResponseJson)
        return client.completePasskeySignIn(body)
    }

    /**
     * Create the passkey for a passkey-only sign-up, and with it the account:
     * `sign_ups/:id/passkey/begin` → Credential Manager `createCredential` →
     * `…/finish`. Call once [PasskeySignUpFlow.awaitingPasskey] is true; on
     * success the flow is complete — finish it with [PasskeySignUpFlow.complete].
     */
    @JvmOverloads
    suspend fun createSignUpPasskey(
        activity: Context,
        flow: PasskeySignUpFlow,
        name: String? = null,
        prf: PasskeyPrf? = null,
        onPrfResult: ((PasskeyPrfResult) -> Unit)? = null,
    ): FlowStep {
        val (id, pollSecret) = flow.passkeyContext()
        val beginJson = client.postRaw(
            "/v1/client/sign_ups/$id/passkey/begin",
            mapOf("poll_secret" to pollSecret),
            authenticated = false,
        )
        val registrationResponseJson = authenticator.createCredential(activity, withPrfExtension(beginJson, prf, registration = true))
        deliverPrf(prf, registrationResponseJson, onPrfResult)
        val body = toRegistrationFinishBody(beginJson, registrationResponseJson, name) + ("poll_secret" to pollSecret)
        return flow.finish(body)
    }

    /** The PRF outputs reach only the app, right after the ceremony; they are never sent to Atlas. */
    private fun deliverPrf(prf: PasskeyPrf?, responseJson: String, onPrfResult: ((PasskeyPrfResult) -> Unit)?) {
        if (prf == null || onPrfResult == null) return
        readPrfResult(responseJson)?.let(onPrfResult)
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
interface PasskeyCeremony {
    /** Run a registration ceremony; returns the `registrationResponseJson`. */
    suspend fun createCredential(activity: Context, requestJson: String): String

    /** Run an assertion ceremony; returns the `authenticationResponseJson`. */
    suspend fun getCredential(activity: Context, requestJson: String): String
}

/**
 * The default [PasskeyCeremony], backed by `androidx.credentials`. Maps a
 * cancellation or platform failure to [AtlasException.Ceremony].
 */
class CredentialManagerAuthenticator(
    private val credentialManager: CredentialManager,
) : PasskeyCeremony {

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
/** The passkey sign-in `begin` body: the captcha token when there is one. */
internal fun passkeySignInBeginBody(captchaToken: String?): Map<String, String> =
    if (captchaToken.isNullOrEmpty()) emptyMap() else mapOf("captcha_token" to captchaToken)

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


/* ── passkey PRF (WebAuthn `prf` extension) pass-through ───────────────────── */

/**
 * Ask the authenticator for PRF outputs (the WebAuthn `prf` extension) during a
 * passkey ceremony — e.g. to derive a client-side encryption key that only this
 * passkey can unlock. The salts never leave the device in the clear and the
 * outputs reach only your `onPrfResult` callback: nothing PRF-related is sent to
 * Atlas.
 *
 * Credential Manager passes the extension through to the provider; providers
 * that support PRF (Google Password Manager on recent Android, and others)
 * return results, others return none — [PasskeyPrfResult.enabled] /
 * [PasskeyPrfResult.first] are then null.
 *
 * @param first the first salt (bytes).
 * @param second an optional second salt (for key rotation).
 * @param evalByCredential per-credential salts for a sign-in (base64url
 *   credential id → salts); used instead of [first]/[second] for that credential.
 */
class PasskeyPrf @JvmOverloads constructor(
    val first: ByteArray,
    val second: ByteArray? = null,
    val evalByCredential: Map<String, Pair<ByteArray, ByteArray?>> = emptyMap(),
) {
    companion object {
        /** Salts given as strings are encoded as UTF-8 (the same rule as the web SDK). */
        @JvmStatic
        @JvmOverloads
        fun ofSalt(first: String, second: String? = null): PasskeyPrf =
            PasskeyPrf(first.toByteArray(Charsets.UTF_8), second?.toByteArray(Charsets.UTF_8))
    }
}

/**
 * The PRF outputs of a ceremony. [enabled] is reported on registration (whether
 * the new passkey supports PRF); [first] / [second] are the 32-byte outputs for
 * the salts, when the authenticator evaluated them.
 */
class PasskeyPrfResult(
    val credentialId: String?,
    val enabled: Boolean?,
    val first: ByteArray?,
    val second: ByteArray?,
)

private fun b64url(bytes: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun b64urlDecode(value: String): ByteArray? =
    runCatching { java.util.Base64.getUrlDecoder().decode(value.trimEnd('=')) }.getOrNull()

/**
 * Add the WebAuthn `prf` extension to a ceremony's options JSON: `eval` on both
 * registration and sign-in, plus `evalByCredential` on sign-in. Returns the JSON
 * unchanged when [prf] is null.
 */
internal fun withPrfExtension(requestJson: String, prf: PasskeyPrf?, registration: Boolean): String {
    if (prf == null) return requestJson
    val options = parseObject("passkey begin", requestJson)
    fun salts(first: ByteArray, second: ByteArray?) = kotlinx.serialization.json.buildJsonObject {
        put("first", kotlinx.serialization.json.JsonPrimitive(b64url(first)))
        if (second != null) put("second", kotlinx.serialization.json.JsonPrimitive(b64url(second)))
    }
    val prfObject = kotlinx.serialization.json.buildJsonObject {
        put("eval", salts(prf.first, prf.second))
        if (!registration && prf.evalByCredential.isNotEmpty()) {
            put(
                "evalByCredential",
                kotlinx.serialization.json.buildJsonObject {
                    prf.evalByCredential.forEach { (id, s) -> put(id, salts(s.first, s.second)) }
                },
            )
        }
    }
    val existing = options["extensions"] as? JsonObject ?: JsonObject(emptyMap())
    val extensions = JsonObject(existing + ("prf" to prfObject))
    return JsonObject(options + ("extensions" to extensions)).toString()
}

/** Read the `prf` client-extension results from a ceremony's response JSON. */
internal fun readPrfResult(responseJson: String): PasskeyPrfResult? {
    val response = runCatching { mappingJson.parseToJsonElement(responseJson).jsonObject }.getOrNull() ?: return null
    val ext = response["clientExtensionResults"] as? JsonObject ?: return null
    val prf = ext["prf"] as? JsonObject ?: return null
    val enabled = (prf["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    val results = prf["results"] as? JsonObject
    val first = results?.string("first")?.let(::b64urlDecode)
    val second = results?.string("second")?.let(::b64urlDecode)
    return PasskeyPrfResult(response.string("rawId") ?: response.string("id"), enabled, first, second)
}
