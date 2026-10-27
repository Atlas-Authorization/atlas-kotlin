package net.atlasauth.atlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GamePlatformTest {
    @Test fun playGamesBodyCarriesProviderAndCode() {
        val body = Json.parseToJsonElement(playGamesBody("4/abc")).jsonObject
        assertEquals("google_play_games", body["provider"]!!.jsonPrimitive.content)
        assertEquals("4/abc", body["server_auth_code"]!!.jsonPrimitive.content)
    }
}
