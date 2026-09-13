# Atlas Android SDK

The official native **Android / Kotlin** SDK for the [Atlas](../../) auth
platform — a dependency-light Android library that speaks the Atlas Frontend API
(FAPI) with **OkHttp** + **Kotlin coroutines** + **kotlinx.serialization**. It is
the Android peer of the [Swift SDK](../swift) and mirrors it endpoint-for-endpoint
and shape-for-shape, which in turn mirrors the vanilla JS client (`@atlas/js`).

> **Scope.** This is a solid, tested *foundation*: the client-facing auth core a
> native app needs. It is not yet a complete SDK — see [Scope](#scope) for what a
> full release still needs (native passkeys/WebAuthn, prebuilt UI, the multi-step
> MFA driver).

## Install

Gradle (Kotlin DSL). The library publishes as `net.atlasauth:atlas-android`:

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("net.atlasauth:atlas-android:0.1.0")
}
```

Or depend on it locally inside this monorepo:

```kotlin
// settings.gradle.kts
include(":atlas-android")
project(":atlas-android").projectDir = file("../atlas-kotlin")
```

- **min SDK 24**, compile SDK 34.
- Package: `net.atlasauth.atlas`.
- Transitive deps: OkHttp, kotlinx-serialization-json, kotlinx-coroutines,
  androidx.security:security-crypto.

## Quick start

```kotlin
import net.atlasauth.atlas.AtlasClient

// `create` wires the encrypted token store, namespaced by the publishable key.
val atlas = AtlasClient.create(
    context = applicationContext,
    publishableKey = "pk_live_…",
    frontendApi = "clerk.your-domain.com",   // bare host is upgraded to https://
)

// All network calls are suspend functions — call them from a coroutine.
lifecycleScope.launch {
    // Password sign-in: create attempt → attempt first factor → exchange ticket.
    // The session JWT + refresh cookie are persisted to EncryptedSharedPreferences.
    val user = atlas.signIn(email = "ada@example.com", password = "…")
    Log.d("atlas", "${user.id} ${user.primaryEmailId}")

    // Read the signed-in user later.
    val me = atlas.currentUser()

    // Rotate the token (call before it expires, or on a 401 retry).
    atlas.refresh()

    // Sign out — revokes server-side and clears the encrypted store.
    atlas.signOut()
}
```

### OAuth (Custom Tabs / browser)

```kotlin
lifecycleScope.launch {
    val authUrl = atlas.oauthAuthorizeUrl(
        provider = "google",
        redirectUri = "myapp://callback",
    )
    // Open authUrl in a Chrome Custom Tab / browser.
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
}

// In the Activity that receives the myapp://callback deep link:
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val data = intent.data ?: return
    // Atlas appends __atlas_attempt + __atlas_ticket to the callback.
    val attempt = data.getQueryParameter("__atlas_attempt")
    val ticket = data.getQueryParameter("__atlas_ticket")
    if (attempt != null && ticket != null) {
        lifecycleScope.launch { atlas.exchangeTicket(attemptId = attempt, ticket = ticket) }
    }
}
```

## Surface

| Method | FAPI endpoint(s) |
| --- | --- |
| `signIn(email, password)` | `POST /v1/client/sign_ins` → `…/attempt_first_factor` → `POST /v1/client/tickets/exchange` |
| `oauthAuthorizeUrl(provider, redirectUri)` | `POST /v1/client/sign_ins/oauth` |
| `exchangeTicket(attemptId, ticket)` | `POST /v1/client/tickets/exchange` |
| `currentUser()` | `GET /v1/client/me` |
| `refresh()` | `POST /v1/client/sessions/:id/tokens` |
| `signOut()` | `POST /v1/client/sessions/:id/revoke` |
| `hasSession()` | *(offline — reads the token store)* |

Every request sends `x-publishable-key`. The short-lived session **JWT** is
stored via the `TokenStore`; the long-lived **`__atlas_rt`** refresh token is
captured from the `Set-Cookie` header and re-presented on authenticated calls —
the app never handles it directly.

Models mirror `Models.swift`: `SignInAttempt`, `SessionTokens`, `AtlasUser`,
`EmailAddress`, `ExternalAccount`, `Passkey`, `AtlasSession`, plus the `JsonValue`
type for arbitrary `public_metadata` / `unsafe_metadata`.

## Token storage

`TokenStore` is an interface, so persistence is yours to choose:

- **`EncryptedSharedPreferencesTokenStore`** (default via `AtlasClient.create`) —
  one entry in an `EncryptedSharedPreferences` file, encrypted at rest by a key
  held in the Android Keystore (hardware-backed where available). The peer of the
  Swift SDK's Keychain store.
- **`InMemoryTokenStore`** — process-lifetime; tests and previews.
- Implement your own for a custom vault.

```kotlin
val atlas = AtlasClient(
    publishableKey = "pk_…",
    frontendApi = "clerk.your-domain.com",
    tokenStore = EncryptedSharedPreferencesTokenStore(context, account = "pk_…"),
)
```

## Errors

Everything throws `AtlasException`, decoded from the §9.1 envelope
`{ errors: [{ code, message, param? }] }`:

```kotlin
try {
    atlas.signIn(email = e, password = p)
} catch (error: AtlasException) {
    when (error.code) {
        "form_password_incorrect" -> …
        "form_identifier_not_found" -> …
        else -> showBanner(error.message)   // message is always non-null
    }
    Log.d("atlas", "${error.status}")        // HTTP status for Api errors
}
```

`AtlasException` is a sealed class: `Api(statusCode, errors)`, `Transport`
(network), `Decoding` (contract drift), and `NotSignedIn` (raised locally when an
authenticated call has no session). `code` and `status` are convenience
accessors that are non-null only for `Api`.

## ProGuard / R8

The library ships `consumer-rules.pro`, so apps that enable R8 need **no extra
configuration** — the rules keep kotlinx.serialization's generated `$serializer`
classes for every Atlas model, which R8 would otherwise strip (causing a
`SerializationException` at decode time). If you relocate/repackage the SDK,
carry those rules along.

## Tests

```bash
./gradlew test                    # JVM unit tests (offline, MockWebServer)
./gradlew connectedAndroidTest    # instrumented store test (device/emulator)
```

The unit tests run entirely offline against OkHttp's `MockWebServer` — a direct
port of the Swift SDK's `MockURLProtocol` suite. They pin: base-URL resolution
and the `x-publishable-key` header on every request; that password sign-in walks
the exact three endpoints with the exact bodies and stores the returned JWT +
refresh cookie; that a 4xx/5xx becomes an `AtlasException` with the right `code`;
that `currentUser()` decodes the full `/me` shape and presents the cookie; that
`refresh()` rotates the stored token; that `signOut()` clears storage even when
the revoke call fails; and the token-store + `JsonValue` round-trips. The
`EncryptedSharedPreferences` store is exercised by the instrumented test, since it
needs the Android Keystore.

## Scope

A complete native SDK on top of this foundation would add:

- **Native passkeys / WebAuthn** via the Credential Manager API (register +
  authenticate, first- and second-factor).
- **A multi-step flow driver** mirroring `@atlas/js`'s `nextStep` / `advance` —
  email-code, second factor, MFA enrollment, password reset — instead of the
  single password happy-path here.
- **Prebuilt Compose components** (`<SignIn>` / `<UserButton>` equivalents) and
  an observable session object for reactive UI.
- **Sign in with Google One-Tap** native token exchange
  (`POST /v1/client/sign_ins/id_token`).
- Organizations, session listing, and the `/me` mutation surface (email,
  external accounts, metadata).

## License

MIT — see [LICENSE](LICENSE).
