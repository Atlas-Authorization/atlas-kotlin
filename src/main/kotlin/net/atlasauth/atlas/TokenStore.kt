package net.atlasauth.atlas

/**
 * Where the SDK keeps the signed-in session between launches.
 *
 * An interface, not a concrete type, so the persistence policy is the app's to
 * choose — [EncryptedSharedPreferencesTokenStore] in production, an in-memory
 * store in tests, or a customer's own vault. [AtlasClient] never assumes
 * anything beyond these three operations.
 */
interface TokenStore {
    /** Persist the session, replacing any existing one. */
    fun save(session: AtlasSession)

    /** The stored session, or null when signed out. */
    fun load(): AtlasSession?

    /** Remove the stored session (sign-out). */
    fun clear()
}

/**
 * A process-lifetime store. The default for tests, and a sane fallback where the
 * encrypted store is unavailable — but it does not survive a relaunch, so it is
 * never the right choice for a shipping app.
 */
class InMemoryTokenStore(initial: AtlasSession? = null) : TokenStore {
    private val lock = Any()
    private var session: AtlasSession? = initial

    override fun save(session: AtlasSession) {
        synchronized(lock) { this.session = session }
    }

    override fun load(): AtlasSession? = synchronized(lock) { session }

    override fun clear() {
        synchronized(lock) { session = null }
    }
}
