package net.atlasauth.atlas

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * The stable device id the SDK sends on every start that can create an account
 * (email sign-up, social / SSO / id_token sign-ins), so the instance's
 * accounts-per-device abuse limit can count devices rather than networks — the
 * native peer of the JS SDK's `device_id`. Password and passwordless sign-ins
 * never create an account and do not send it.
 *
 * The id is random (`d_` + a UUID) and carries no hardware identifier. Use
 * [persistent] so it survives app restarts; [AtlasClient.deviceIdProvider]
 * defaults to an id that lasts for the process.
 */
object DeviceId {
    /** The routes that carry `device_id` (the JS SDK's `DEVICE_ID_ROUTES`). */
    val ROUTES: Set<String> = setOf(
        "/v1/client/sign_ups",
        "/v1/client/sign_ins/oauth",
        "/v1/client/sign_ins/sso",
        "/v1/client/sign_ins/saml",
        "/v1/client/sign_ins/discourse",
        "/v1/client/sign_ins/id_token",
        "/v1/client/sign_ins/external_jwt",
        "/v1/client/sign_ins/ldap",
        "/v1/oauth/telegram",
        "/v1/oauth/siwe/verify",
        "/v1/oauth/siws/verify",
        "/v1/oauth/siwf/verify",
    )

    private const val PREFS = "net.atlasauth.atlas.device"
    private const val KEY = "__atlas_did"

    /** A fresh random device id. */
    fun fresh(): String = "d_" + UUID.randomUUID().toString().replace("-", "")

    private val processId: String by lazy { fresh() }

    /** An id stable for this process. */
    fun forProcess(): String = processId

    /** A provider whose id is kept in `SharedPreferences`, stable across launches. */
    fun persistent(context: Context): () -> String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return {
            prefs.getString(KEY, null) ?: fresh().also { prefs.edit().putString(KEY, it).apply() }
        }
    }

    /** [bodyJson] with `device_id` added when [path] is a device-id route and the body lacks one. */
    internal fun stamp(json: Json, method: String, path: String, bodyJson: String?, id: () -> String?): String? {
        if (method != "POST" || bodyJson == null) return bodyJson
        if (path.substringBefore('?') !in ROUTES) return bodyJson
        val obj = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }.getOrNull() ?: return bodyJson
        if ("device_id" in obj) return bodyJson
        val value = id() ?: return bodyJson
        return JsonObject(obj + ("device_id" to JsonPrimitive(value))).toString()
    }
}
