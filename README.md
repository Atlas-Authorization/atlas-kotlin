# Atlas Kotlin (JVM) SDK

The official **Kotlin / JVM** SDK for the [Atlas](../../) auth platform — a
dependency-light, pure-JVM library (no Android SDK required) that speaks the
Atlas Frontend API (FAPI) with `HttpURLConnection` + coroutines. It mirrors the
vanilla JS client (`@atlas/js`) endpoint-for-endpoint and shape-for-shape.

## Install

Gradle (Kotlin DSL). Publishes as [`net.atlasauth:atlas-kotlin`](https://central.sonatype.com/artifact/net.atlasauth/atlas-kotlin):

```kotlin
implementation("net.atlasauth:atlas-kotlin:0.3.0")
```

For a native **Android** app that needs the passkey ceremony, use the
Android-library SDK [`net.atlasauth:atlas-android`](https://central.sonatype.com/artifact/net.atlasauth/atlas-android) instead (source in `sdks/kotlin`).

> **Scope.** This is a solid, tested *foundation*: the client-facing auth core an
> Android app needs. It is not yet a complete SDK — see [Scope](#scope) for what a
> full release still needs (prebuilt UI, the multi-step MFA driver).

> **Passkeys live elsewhere.** This module is deliberately a plain Kotlin/JVM
> library that builds and unit-tests with no Android SDK (see
> [Design](#design-dependency-light-on-purpose)). A native passkey ceremony needs
> the Android-only Jetpack Credential Manager and an `Activity`, which cannot run
> in a plain-JVM module. Passkey register + sign-in ship in the Android-library
> SDK `net.atlasauth:atlas-android` (`sdks/kotlin`) via its `PasskeyManager` — use
> that SDK if you need passkeys.

## Design: dependency-light on purpose

- **No third-party HTTP client** — the JDK's `HttpURLConnection`, behind an
  `HttpTransport` interface so tests inject a fake and run with no network.
- **No JSON library** — a small hand-rolled reader/writer (`Json.kt`), so there
  is no `org.json` (absent from plain-JVM tests) and no kotlinx.serialization
  compiler plugin to fetch.
- **Plain Kotlin/JVM module**, not an Android app module: it builds and unit-tests
  with no Android SDK. The one Android-only piece — EncryptedSharedPreferences —
  is reached through a `KeyValueStore` seam (see below).

The only runtime dependency is `kotlinx-coroutines-core`.

## Quick start

```kotlin
val atlas = AtlasClient(
    publishableKey = "pk_live_…",
    frontendApi = "clerk.your-domain.com",   // bare host upgraded to https://
    tokenStore = SecurePrefsTokenStore(encryptedPrefsAdapter),
)

// Password sign-in: create attempt → attempt first factor → exchange ticket.
val user = atlas.signIn("ada@example.com", "…")

// Read the signed-in user later.
val me = atlas.currentUser()

// Rotate the token (before expiry, or on a 401 retry).
atlas.refresh()

// Sign out — revokes server-side and clears storage.
atlas.signOut()
```

All auth methods are `suspend` — call them from a coroutine.

### OAuth (Custom Tabs)

```kotlin
val authUrl = atlas.oauthAuthorizeUrl(provider = "google", redirectUri = "myapp://callback")
CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))

// In your deep-link handler for myapp://callback:
val attempt = uri.getQueryParameter("__atlas_attempt")
val ticket  = uri.getQueryParameter("__atlas_ticket")
if (attempt != null && ticket != null) atlas.exchangeTicket(attempt, ticket)
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

Every request sends `x-publishable-key`. The short-lived session **JWT** is
stored via the `TokenStore`; the long-lived **`__atlas_rt`** refresh token is
captured from `Set-Cookie` and replayed on authenticated calls — the app never
handles it directly.

## Token storage

`TokenStore` is an interface, so persistence is yours to choose:

- **`SecurePrefsTokenStore`** — the production shape. Back it with androidx's
  `EncryptedSharedPreferences` via the `KeyValueStore` adapter:

  ```kotlin
  val prefs = EncryptedSharedPreferences.create(
      context, "atlas_session",
      MasterKey.Builder(context).setKeyScheme(AES256_GCM).build(),
      AES256_SIV, AES256_GCM,
  )
  val store = SecurePrefsTokenStore(object : KeyValueStore {
      override fun getString(key: String) = prefs.getString(key, null)
      override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
      override fun remove(key: String) = prefs.edit().remove(key).apply()
  })
  ```

- **`InMemoryTokenStore`** — process-lifetime; tests and previews.
- Implement `TokenStore` yourself for a custom vault.

## Errors

Everything throws `AtlasException`, wrapping an `AtlasError` decoded from the §9.1
envelope `{ errors: [{ code, message, param? }] }`:

```kotlin
try {
    atlas.signIn(email, password)
} catch (e: AtlasException) {
    when (e.error.code) {
        "form_password_incorrect" -> …
        "form_identifier_not_found" -> …
        else -> showBanner(e.error.message)   // message is always non-null
    }
    val status = e.error.status               // HTTP status for Api errors
}
```

`AtlasError` is a sealed class: `Api` (server envelope), `Transport` (network),
`Decoding` (contract drift), `NotSignedIn` (raised locally).

## Build & test

```bash
gradle test        # or ./gradlew test once a wrapper is added
```

12 unit tests run entirely offline against a `FakeTransport` — no network, no
MockWebServer. They pin: the auth header + base URL on every request; that
password sign-in walks the exact three endpoints with the exact bodies and stores
the returned JWT + refresh cookie; that a 4xx/5xx becomes an `AtlasError` with the
right `code`; that `currentUser()` parses the full `/me` shape and presents the
cookie; that `refresh()` rotates the stored token; and both token-store round
trips (in-memory and the EncryptedSharedPreferences-shaped store).

Built as a plain Kotlin/JVM library. To ship it inside an Android app, add the
`com.android.library` plugin, the `androidx.security:security-crypto` dependency,
and (optionally) a Gradle wrapper.

## Scope

A complete native SDK on top of this foundation would add:

- **A multi-step flow driver** mirroring `@atlas/js`'s `nextStep` / `advance` —
  email-code, second factor, MFA enrollment, password reset — instead of the
  single password happy-path here.
- **Prebuilt Compose components** (`SignIn` / `UserButton` equivalents) and an
  observable session holder for reactive UI.
- **Native Google / One-Tap** token exchange (`POST /v1/client/sign_ins/id_token`).
- Organizations, session listing, and the `/me` mutation surface.
