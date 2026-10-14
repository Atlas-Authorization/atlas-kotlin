package net.atlasauth.atlas

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * The production [TokenStore]: the session lives in an
 * [EncryptedSharedPreferences] file, encrypted at rest by a key held in the
 * Android Keystore (hardware-backed where available) and outside the app's plain
 * files.
 *
 * It is the Android peer of the Swift SDK's `KeychainTokenStore`. One entry per
 * [account] (usually the publishable key), holding the JSON-encoded
 * [AtlasSession], so two Atlas instances in one app do not collide.
 *
 * @param context any [Context]; the application context is used internally.
 * @param account the entry key, usually the publishable key.
 * @param fileName the encrypted preferences file name.
 */
class EncryptedSharedPreferencesTokenStore(
    context: Context,
    private val account: String,
    fileName: String = DEFAULT_FILE_NAME,
) : TokenStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entryKey = "session:$account"
    private val prefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            appContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(session: AtlasSession) {
        val encoded = json.encodeToString(AtlasSession.serializer(), session)
        // commit() (synchronous) so a refresh that fires during process teardown
        // cannot lose the rotated token to an unflushed async write.
        prefs.edit().putString(entryKey, encoded).commit()
    }

    override fun load(): AtlasSession? {
        val raw = prefs.getString(entryKey, null) ?: return null
        return try {
            json.decodeFromString(AtlasSession.serializer(), raw)
        } catch (_: Throwable) {
            // A corrupt entry is treated as "signed out" rather than crashing the
            // caller; the next sign-in overwrites it.
            null
        }
    }

    override fun clear() {
        prefs.edit().remove(entryKey).commit()
    }

    companion object {
        const val DEFAULT_FILE_NAME: String = "atlas_sdk_session"
    }
}
