package net.atlasauth.atlas

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Google Play Games sign-in. Request a server auth code with the Play Games SDK
 * (`GamesSignInClient.requestServerSideAccess(webClientId, false)`), then pass it here.
 * Atlas exchanges it with your web OAuth client and reads the player id
 * (`POST /v1/oauth/game_platform`). The player's id is the identity; Play Games provides no email.
 */
internal fun playGamesBody(serverAuthCode: String): String =
    buildJsonObject {
        put("provider", "google_play_games")
        put("server_auth_code", serverAuthCode)
    }.toString()

suspend fun AtlasClient.signInWithPlayGames(serverAuthCode: String): AtlasUser {
    val response = requestRaw("POST", "/v1/oauth/game_platform", playGamesBody(serverAuthCode), authenticated = false)
    val attempt = decodeOrThrow(response.body, FlowAttempt.serializer())
    val ticket = attempt.ticket
    if (!attempt.isComplete || ticket == null) {
        throw AtlasException.Api(
            statusCode = 200,
            errors = listOf(
                AtlasErrorItem(
                    code = "sign_in_not_complete",
                    message = "Play Games sign-in needs an additional step: ${attempt.status}.",
                ),
            ),
        )
    }
    exchangeTicket(attemptId = attempt.id ?: "", ticket = ticket)
    return currentUser()
}
