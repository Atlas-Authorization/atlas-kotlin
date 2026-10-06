# Atlas Kotlin (JVM) SDK

The official **Kotlin / JVM** SDK for the [Atlas](../../) auth platform — a
dependency-light, pure-JVM library (no Android SDK required) that speaks the
Atlas Frontend API (FAPI) with `HttpURLConnection` + coroutines. It mirrors the
vanilla JS client (`@atlas/js`) endpoint-for-endpoint and shape-for-shape.

## Install

Gradle (Kotlin DSL). Publishes as [`net.atlasauth:atlas-kotlin`](https://central.sonatype.com/artifact/net.atlasauth/atlas-kotlin):

```kotlin
implementation("net.atlasauth:atlas-kotlin:0.4.0")
```

For a native **Android** app that needs on-device UI or the native token
ceremonies, use the Android-library SDK
[`net.atlasauth:atlas-android`](https://central.sonatype.com/artifact/net.atlasauth/atlas-android) instead (source in `sdks/kotlin`).

> **Scope.** The client surface is **complete for a pure-JVM library**: the full
> multi-step sign-in / sign-up / password-reset [flow driver](#flow-driver), the
> [native id_token exchange](#native--one-tap-idtoken), and the
> [organizations, session listing, and `/me` mutation](#surface) surface — all
> tested offline, with no Android SDK. What is left out is precisely what a
> plain-JVM module *cannot* host: on-device UI and native token ceremonies. Those
> live in the Android SDK — see [What lives in the Android SDK](#what-lives-in-the-android-sdk).

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

## Flow driver

`signIn(email, password)` is the single-shot happy path and still works. For
everything else — email/phone codes, a second factor, MFA enrollment,
password reset — use the suspend-based flow driver, which mirrors the vanilla JS
client (`@atlas/js`'s `nextStep` / `advance`): you read `status`, the driver
tells you the next `step`, and you call the matching `advance` method. It never
picks the step itself (§5), and an unknown status maps to `SignInStep.Unknown`
rather than a blank screen.

```kotlin
val flow = atlas.beginSignIn("ada@example.com")
when (val step = flow.step) {
    is SignInStep.CollectFirstFactor -> {
        // step.strategies is the server's list — never filter it client-side.
        flow.attemptPassword("…")              // or prepareFirstFactor("email_code") + attemptEmailCode(code)
    }
    else -> { /* … */ }
}
if (flow.step is SignInStep.CollectSecondFactor) {
    flow.prepareSecondFactor("sms")            // sms / push / passkey options
    flow.attemptSecondFactor("123456")         // TOTP, SMS OTP, or a backup code
}
if (flow.isComplete) {
    val user = flow.complete()                 // exchanges the ticket + persists the session
}
```

| Flow | Entry | Steps |
| --- | --- | --- |
| Sign-in | `atlas.beginSignIn(identifier)` → `SignInFlow` | `attemptPassword` · `prepareFirstFactor`/`attemptEmailCode`/`attemptPhoneCode` · `prepareSecondFactor`/`attemptSecondFactor`/`attemptPushSecondFactor` · `prepareMfaEnrollment`/`attemptMfaEnrollment` · `complete` |
| Sign-up | `atlas.beginSignUp(email, password, …)` → `SignUpFlow` | `prepareVerification` · `attemptVerification` · `complete` |
| Password reset | `atlas.beginPasswordReset(email)` → `PasswordResetFlow` | `attemptVerification` · `attemptSecondFactor` · `setNewPassword` · `complete` |

`complete()` exchanges the one-time ticket and persists the session via the
`TokenStore`. `nextStep(attempt)` and `isTerminal(attempt)` are exposed as pure
functions too, for a UI that wants to map status itself.

## Native / One-Tap (id_token)

A native sign-in (Google One-Tap, Apple, Facebook Limited Login) produces a
provider **id_token**. This library does **no native ceremony** — the app
obtains the token string however it likes (the Android SDK, below) and hands it
over:

```kotlin
val nonce = atlas.mintNativeNonce("google")           // optional replay-binding nonce
// … native layer obtains an id_token bound to `nonce` …
val flow = atlas.signInWithIdToken("google", idToken, nonce)
val user = if (flow.isComplete) flow.complete()        // or resume a second factor on `flow`
           else error("second factor owed: ${flow.step}")
```

## Surface

| Method | FAPI endpoint(s) |
| --- | --- |
| `signIn(email, password)` | `POST /v1/client/sign_ins` → `…/attempt_first_factor` → `POST /v1/client/tickets/exchange` |
| `beginSignIn` / `beginSignUp` / `beginPasswordReset` | the flow driver (see above) |
| `mintNativeNonce(provider)` | `POST /v1/client/sign_ins/id_token/nonce` |
| `signInWithIdToken(provider, idToken, nonce?)` | `POST /v1/client/sign_ins/id_token` |
| `oauthAuthorizeUrl(provider, redirectUri)` | `POST /v1/client/sign_ins/oauth` |
| `exchangeTicket(attemptId, ticket)` | `POST /v1/client/tickets/exchange` |
| `currentUser()` | `GET /v1/client/me` |
| `updateProfile(…)` | `PATCH /v1/client/me` (writes `unsafe_metadata` only — §4.1) |
| `addEmailAddress` / `verifyEmailAddress` / `setPrimaryEmail` / `deleteEmailAddress` | `/v1/client/me/email_addresses[/:id[/…]]` |
| `connectExternalAccount` / `deleteExternalAccount` | `/v1/client/me/external_accounts/connect`, `DELETE …/:id` |
| `changePassword` / `setPassword` | `POST /v1/client/me/change_password`, `…/set_password` |
| `organizationMemberships()` | `GET /v1/client/me/organizations` |
| `createOrganization(name, slug)` / `organization(id)` | `/v1/client/organizations[/:id]` |
| `sessions()` / `revokeSession(id)` / `revokeOtherSessions()` | `GET /v1/client/sessions`, `…/:id/revoke`, `…/revoke_all` |
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

The unit tests run entirely offline against a `FakeTransport` — no network, no
MockWebServer, no emulator. They pin: the auth header + base URL on every
request; the exhaustive `nextStep` status mapping (including the unknown-status
fallback); the sign-in / sign-up / password-reset flow driver walking its exact
endpoints and bodies through to a persisted session; the id_token exchange body
and its complete / needs-second-factor outcomes; the organizations, session
listing and `/me` mutation surface (request shapes + response mapping); the JSON
writer (nested objects, arrays, null-omission, whole-number rendering); that a
4xx/5xx becomes an `AtlasError` with the right `code`; and both token-store round
trips (in-memory and the EncryptedSharedPreferences-shaped store).

Built as a plain Kotlin/JVM library. To ship it inside an Android app, add the
`com.android.library` plugin, the `androidx.security:security-crypto` dependency,
and (optionally) a Gradle wrapper.

## What lives in the Android SDK

This module is deliberately a plain Kotlin/JVM library that builds and
unit-tests with **no Android SDK** (see
[Design](#design-dependency-light-on-purpose)). Three things therefore cannot
live here — they need the Android runtime (an `Activity`, Jetpack Credential
Manager, Google Identity Services) — and ship in the Android-library SDK
[`net.atlasauth:atlas-android`](https://central.sonatype.com/artifact/net.atlasauth/atlas-android) (`sdks/kotlin`):

- **Prebuilt Compose UI** (`SignIn` / `UserButton` equivalents) and an
  observable session holder for reactive UI.
- **Native token acquisition** — the Credential Manager / Google One-Tap
  ceremony that *produces* the provider `id_token`. This library *exchanges* an
  id_token string ([`signInWithIdToken`](#native--one-tap-idtoken)); obtaining it
  is the Android SDK's job (or the platform's).
- **The passkey ceremony** — WebAuthn register + sign-in via Credential Manager.
  This library returns the server's challenge options
  (`prepareSecondFactor` → `SecondFactorChallenge.raw`); turning them into a
  platform assertion is native.

Everything else a client needs — the full multi-step flow driver, id_token
exchange, organizations, session listing, and the `/me` mutation surface — is
here and tested.
