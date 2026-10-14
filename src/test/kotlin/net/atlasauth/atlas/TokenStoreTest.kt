package net.atlasauth.atlas

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStoreTest {

    @Test
    fun inMemoryTokenStoreRoundTrip() {
        val store = InMemoryTokenStore()
        assertNull(store.load())

        val session = AtlasSession(sessionId = "sess_1", token = "jwt", refreshToken = "rt")
        store.save(session)
        assertEquals(session, store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun atlasSessionCodableRoundTrip() {
        // The EncryptedSharedPreferences impl persists this exact JSON; guard its
        // shape here so the encrypted store (not exercisable in a JVM unit test)
        // stays correct.
        val json = Json { encodeDefaults = true }
        val session = AtlasSession(sessionId = "sess_1", token = "jwt_abc", refreshToken = "rt_xyz")
        val encoded = json.encodeToString(AtlasSession.serializer(), session)
        val decoded = json.decodeFromString(AtlasSession.serializer(), encoded)
        assertEquals(session, decoded)
    }

    @Test
    fun jsonValueDecodesMetadataShapes() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            AtlasUser.serializer(),
            """
            {
              "id": "user_1",
              "public_metadata": {
                "plan": "pro",
                "seats": 5,
                "active": true,
                "tags": ["a", "b"],
                "nested": { "k": "v" },
                "nothing": null
              }
            }
            """.trimIndent(),
        )
        val meta = decoded.publicMetadata ?: error("expected metadata")
        assertEquals("pro", meta["plan"]?.stringValue)
        assertEquals(5.0, (meta["seats"] as JsonValue.Num).value, 0.0)
        assertEquals(true, (meta["active"] as JsonValue.Bool).value)
        assertEquals(JsonValue.Str("a"), (meta["tags"] as JsonValue.Arr).value.first())
        assertEquals("v", (meta["nested"] as JsonValue.Obj).value["k"]?.stringValue)
        assertEquals(JsonValue.Null, meta["nothing"])
    }
}
