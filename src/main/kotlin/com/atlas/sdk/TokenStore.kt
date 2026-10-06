package com.atlas.sdk

/**
 * Where the SDK keeps the signed-in session between launches. A protocol, not a
 * concrete type, so the persistence policy is the app's — EncryptedSharedPreferences
 * in production, in-memory in tests, or a custom vault.
 */
interface TokenStore {
    fun save(session: AtlasSession)
    fun load(): AtlasSession?
    fun clear()
}

/** A process-lifetime store: tests, and a fallback where secure storage is absent. */
class InMemoryTokenStore(initial: AtlasSession? = null) : TokenStore {
    private var session: AtlasSession? = initial

    @Synchronized override fun save(session: AtlasSession) { this.session = session }
    @Synchronized override fun load(): AtlasSession? = session
    @Synchronized override fun clear() { session = null }
}

/**
 * A minimal key/value contract that Android's `SharedPreferences` satisfies at
 * the call site. Depending on this — rather than importing `android.*` — keeps
 * the module compiling and testable on a plain JVM while still being a drop-in
 * for EncryptedSharedPreferences on device.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * The production store shape. Back it with androidx.security's
 * EncryptedSharedPreferences so the session is encrypted at rest:
 *
 * ```kotlin
 * val prefs = EncryptedSharedPreferences.create(
 *     context, "atlas_session",
 *     MasterKey.Builder(context).setKeyScheme(AES256_GCM).build(),
 *     AES256_SIV, AES256_GCM,
 * )
 * val store = SecurePrefsTokenStore(object : KeyValueStore {
 *     override fun getString(key: String) = prefs.getString(key, null)
 *     override fun putString(key: String, value: String) =
 *         prefs.edit().putString(key, value).apply()
 *     override fun remove(key: String) = prefs.edit().remove(key).apply()
 * })
 * ```
 *
 * The session is serialized as a tab-delimited triple (sessionId, token,
 * refreshToken) — no delimiter can appear in a JWT or an opaque token id, so no
 * escaping is needed, and it avoids pulling a JSON writer into storage.
 */
class SecurePrefsTokenStore(
    private val prefs: KeyValueStore,
    private val key: String = "atlas.session",
) : TokenStore {

    override fun save(session: AtlasSession) {
        prefs.putString(key, listOf(session.sessionId, session.token, session.refreshToken ?: "").joinToString("\t"))
    }

    override fun load(): AtlasSession? {
        val raw = prefs.getString(key) ?: return null
        val parts = raw.split("\t")
        if (parts.size < 2) return null
        val refresh = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
        return AtlasSession(sessionId = parts[0], token = parts[1], refreshToken = refresh)
    }

    override fun clear() {
        prefs.remove(key)
    }
}
