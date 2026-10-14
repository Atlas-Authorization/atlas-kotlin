package net.atlasauth.atlas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The reactive sign-in state an observable UI renders from. */
sealed interface AtlasSessionStatus {
    /** The initial state, before the first [AtlasSessionState.reload]. */
    object Loading : AtlasSessionStatus

    /** No session is persisted (or the session was rejected). */
    object SignedOut : AtlasSessionStatus

    /** A session is live; [user] is the signed-in user. */
    data class SignedIn(val user: AtlasUser) : AtlasSessionStatus
}

/**
 * An observable holder of the current session, for reactive UI. It wraps an
 * [AtlasClient] and exposes a [StateFlow] a Compose screen (or any observer)
 * collects, so the UI re-renders when the user signs in or out. The non-UI core
 * so it is unit-testable without Compose or a device.
 *
 * Refreshing is explicit: call [reload] after any auth action (or on first
 * composition via [rememberAtlasSessionState]). A transport failure leaves the
 * last known status in place rather than flipping to signed-out — a flaky
 * network is not a sign-out.
 *
 * @param scope the coroutine scope the fire-and-forget [refresh] / [signOut]
 *   launch into (typically a `lifecycleScope` or a Compose-remembered scope).
 */
class AtlasSessionState(
    val client: AtlasClient,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<AtlasSessionStatus>(AtlasSessionStatus.Loading)

    /** The current sign-in status; collect it to drive reactive UI. */
    val status: StateFlow<AtlasSessionStatus> = _status.asStateFlow()

    /** The signed-in user, or null when loading / signed out. */
    val user: AtlasUser? get() = (_status.value as? AtlasSessionStatus.SignedIn)?.user

    /** Fire-and-forget [reload] on [scope] — the convenience most call sites want. */
    fun refresh() {
        scope.launch { reload() }
    }

    /**
     * Re-read the session and publish the new [status]. Signed-out when no session
     * is stored or the server rejects it (401); unchanged on a transport error, so
     * a dropped connection does not sign the user out. Suspends, so it is directly
     * awaitable in a test.
     */
    suspend fun reload() {
        if (!client.hasSession()) {
            _status.value = AtlasSessionStatus.SignedOut
            return
        }
        try {
            _status.value = AtlasSessionStatus.SignedIn(client.currentUser())
        } catch (_: AtlasException.NotSignedIn) {
            _status.value = AtlasSessionStatus.SignedOut
        } catch (error: AtlasException.Api) {
            // A rejected session (401/403) is a real sign-out; other API errors and
            // transport drops leave the current status untouched.
            if (error.statusCode == 401 || error.statusCode == 403) {
                _status.value = AtlasSessionStatus.SignedOut
            }
        } catch (_: AtlasException) {
            // Transport / decoding hiccup — keep the last known status.
        }
    }

    /** Sign out (revoke + clear) and publish [AtlasSessionStatus.SignedOut]. Suspends. */
    suspend fun signOut() {
        client.signOut()
        _status.value = AtlasSessionStatus.SignedOut
    }
}
