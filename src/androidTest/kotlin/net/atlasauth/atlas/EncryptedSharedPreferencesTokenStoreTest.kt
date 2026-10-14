package net.atlasauth.atlas

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the production store — needs the Android Keystore, so it
 * runs on a device / emulator (`./gradlew connectedAndroidTest`), not in the JVM
 * unit-test source set.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedSharedPreferencesTokenStoreTest {

    private lateinit var store: EncryptedSharedPreferencesTokenStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = EncryptedSharedPreferencesTokenStore(
            context = context,
            account = "pk_test_123",
            fileName = "atlas_sdk_session_test",
        )
        store.clear()
    }

    @Test
    fun roundTripsThroughEncryptedStorage() {
        assertNull(store.load())

        val session = AtlasSession(sessionId = "sess_1", token = "jwt_abc", refreshToken = "rt_xyz")
        store.save(session)

        val loaded = store.load()
        assertEquals(session, loaded)

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun saveReplacesExistingSession() {
        store.save(AtlasSession(sessionId = "sess_1", token = "old", refreshToken = "rt_old"))
        store.save(AtlasSession(sessionId = "sess_1", token = "new", refreshToken = "rt_new"))

        val loaded = store.load()
        assertEquals("new", loaded?.token)
        assertEquals("rt_new", loaded?.refreshToken)
    }
}
