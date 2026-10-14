# Atlas Android SDK

The official native **Android / Kotlin** SDK for the [Atlas](../../) auth
platform — a dependency-light Android library that speaks the Atlas Frontend API
(FAPI) with **OkHttp** + **Kotlin coroutines** + **kotlinx.serialization**. It is
the Android peer of the [Swift SDK](../swift) and mirrors it endpoint-for-endpoint
and shape-for-shape, which in turn mirrors the vanilla JS client (`@atlas/js`).

> **Complete.** The full client-facing SDK: the auth core, the native passkey
> ceremony, a suspend-based multi-step sign-in / sign-up / password-reset flow
> driver, prebuilt Jetpack Compose components with an observable session, native
> Google One-Tap (id_token) sign-in, and the organizations / sessions / `/me`
> surfaces. See [Flows](#multi-step-flow-driver), [Compose](#jetpack-compose-components),
> [Google](#native-google-sign-in-one-tap--id_token) and [Account](#organizations-sessions--account).

## Install

Gradle (Kotlin DSL). The library publishes as [`net.atlasauth:atlas-android`](https://central.sonatype.com/artifact/net.atlasauth/atlas-android) on Maven Central:

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("net.atlasauth:atlas-android:0.5.0")
}
```

Or depend on it locally inside this monorepo:

```kotlin
// settings.gradle.kts
include(":atlas-android")
project(":atlas-android").projectDir = file("../ssoly/sdks/kotlin")
```

- **min SDK 24**, compile SDK 34.
- Package: `net.atlasauth.atlas`.
- Transitive deps: OkHttp, kotlinx-serialization-json, kotlinx-coroutines,
  androidx.security:security-crypto, androidx.credentials (+ the Google-ID
  provider), and Jetpack Compose (runtime + Material 3) for the prebuilt UI. The
  non-UI core has no compile dependency on Compose — the composables are additive.

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
| `PasskeyManager.registerPasskey(activity, name?)` | `POST /v1/client/me/passkeys/begin` → `…/finish` |
| `PasskeyManager.signInWithPasskey(activity)` | `POST /v1/client/sign_ins/passkey/begin` → `…/finish` → `tickets/exchange` |

Every request sends `x-publishable-key`. The short-lived session **JWT** is
stored via the `TokenStore`; the long-lived **`__atlas_rt`** refresh token is
captured from the `Set-Cookie` header and re-presented on authenticated calls —
the app never handles it directly.

Models mirror `Models.swift`: `SignInAttempt`, `SessionTokens`, `AtlasUser`,
`EmailAddress`, `ExternalAccount`, `Passkey`, `AtlasSession`, plus the `JsonValue`
type for arbitrary `public_metadata` / `unsafe_metadata`.

### Native session (first-party OAuth, cookie-free)

New in **0.2.0** (`NativeSession.kt`). A first-party app trades an OAuth access
token it already holds for a real Atlas session and carries it by hand as a
bearer — no cookie jar needed:

- `exchangeForSession(baseUrl, clientId, accessToken, httpClient)` — RFC 8693
  token-exchange against `POST /oauth2/token`.
- `refreshNativeSession(baseUrl, publishableKey, sessionId, refreshToken, httpClient)`
  — rotate without a cookie via `POST /v1/client/sessions/:id/tokens`.
- `NativeSessionManager` — holds the session, hands out a fresh bearer via
  `token()` / `authHeaders()` (lazy, single-flight refresh ~10s before expiry via
  a `Mutex`), and persists each rotated refresh token to the `TokenStore`.
  `NativeSessionManager.create(context, publishableKey, frontendApi, clientId)`
  wires the `EncryptedSharedPreferencesTokenStore`.

Both `suspend` helpers **fail soft**, returning `null` on any error — the
caller's cue to re-run OAuth.

## Passkeys (WebAuthn)

New in **0.3.0** (`Passkeys.kt`). Native passkeys driven by the Jetpack
[Credential Manager](https://developer.android.com/jetpack/androidx/releases/credentials)
(`androidx.credentials`). The server's `begin` response is the standard WebAuthn
options JSON, which Credential Manager consumes directly; the ceremony result is
mapped to the `finish` body. The `rpId` comes from the server's options — it is
never hardcoded.

The ceremony shows system UI, so both methods take an **Activity** `Context`.

```kotlin
val atlas = AtlasClient.create(context, publishableKey = "pk_live_…", frontendApi = "…")
val passkeys = PasskeyManager.create(context, atlas)

lifecycleScope.launch {
    // Register a passkey for the signed-in user (requires an existing session).
    passkeys.registerPasskey(activity = this@MyActivity, name = "Pixel 8")

    // Sign in with a passkey — no session needed. Completes into a real Atlas
    // session (the ticket exchange is handled for you) and returns the user.
    val user = passkeys.signInWithPasskey(activity = this@MyActivity)
    Log.d("atlas", "signed in as ${user.id}")
}
```

A user cancellation or platform failure surfaces as `AtlasException.Ceremony`; a
server rejection as `AtlasException.Api`.

### Setup: Digital Asset Links

Android binds a passkey to your app via **Digital Asset Links**: the RP id (your
instance's Frontend API host) must publish an `assetlinks.json` that lists your
app's package name and signing-certificate SHA-256 fingerprints.

You do **not** host that file. Atlas serves `/.well-known/assetlinks.json` on the
Frontend API host per instance automatically — you only configure your app's
signing-cert fingerprints (debug and release) in your Atlas instance settings.
Add both, or passkeys will fail silently in the build whose fingerprint is
missing.

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
(network), `Decoding` (contract drift), `Ceremony` (a passkey/WebAuthn ceremony
failed or was cancelled on the device), and `NotSignedIn` (raised locally when an
authenticated call has no session). `code` and `status` are convenience
accessors that are non-null only for `Api`.

`knownCode` maps the code to the `AtlasErrorCode` enum when the SDK knows it.
When the app has no way to send email, any email-sending step (email code, magic
link, reset, verification) is refused with **422 `EMAIL_DELIVERY_NOT_CONFIGURED`**
before anything is issued; `error.message` is the server's end-user wording
("This app can't send email right now. Try another sign-in method or contact the
app owner.") — show it as-is. `AtlasSignIn` does, and stops offering "Email me a
code" afterwards.

```kotlin
if (error.isEmailDeliveryNotConfigured) showBanner(error.message)
// or: error.knownCode == AtlasErrorCode.EMAIL_DELIVERY_NOT_CONFIGURED
```

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
the revoke call fails; and the token-store + `JsonValue` round-trips. The passkey
WebAuthn-JSON → `finish`-body mapping is unit-tested without a device (the
Credential Manager sits behind the injectable `PasskeyAuthenticator` seam). The
`EncryptedSharedPreferences` store is exercised by the instrumented test, since it
needs the Android Keystore.

The 0.4.0 additions are covered the same way — all offline, no emulator:
`FlowStepTest` (the status → `FlowStep` mapping), `SignInFlowTest` (each driver
step's endpoint + body, and that `complete()` persists the session),
`GoogleSignInTest` (the id_token body + exchange; the device ceremony sits behind
the `GoogleIdTokenProvider` seam), `AccountTest` (the organizations / sessions /
`/me` request + response mapping), and `SessionStateTest` (the observable
`AtlasSessionState` transitions). The **Compose rendering and the live Google
ceremony are compile-verified and logic-tested here**; on-device behaviour is
verified in app QA.

## Multi-step flow driver

New in **0.4.0**. The single-call `signIn(email, password)` above is the happy
path; the flow driver handles everything else — email/phone codes, a second
factor (TOTP / SMS / backup code), mid-sign-in MFA enrollment, sign-up, and
password reset. It is the Kotlin peer of `@atlas/js`'s `nextStep` contract: the
driver reads the server's `status` and tells you the next [`FlowStep`](src/main/kotlin/net/atlasauth/atlas/Flows.kt);
**it never picks the step itself** (§5), and an unknown status maps to
`FlowStep.Unknown` rather than a blank screen.

Each action is a `suspend` function returning the next step; loop until
`FlowStep.Done`, then `complete()` to persist the session (via the same
`TokenStore` — ticket exchange, or the direct-session handling the passkey flow
uses).

```kotlin
val flow = atlas.signInFlow()

var step = flow.create("ada@example.com")   // → CollectFirstFactor(strategies)
step = flow.attemptPassword("…")            // → CollectSecondFactor | Done | …

when (step) {
    is FlowStep.CollectSecondFactor -> {
        step = flow.attemptSecondFactor(code = "123456", rememberDevice = true)
    }
    is FlowStep.EnrollSecondFactor -> {           // §11.1 MFA policy "required"
        val enrollment = flow.prepareMfaEnrollment()   // show secret / QR (uri)
        step = flow.attemptMfaEnrollment(enrollment.factorId, listOf("123456"))
        flow.attempt?.backupCodes                 // shown once on completion
    }
    else -> { /* CollectEmailCode, Unknown, … */ }
}

if (step is FlowStep.Done) {
    val user = flow.complete()                 // session persisted to the TokenStore
}
```

Passwordless first factor: `flow.prepareEmailCode()` / `flow.attemptEmailCode(code)`
(or `preparePhoneCode(channel)` / `attemptPhoneCode(code)`). SMS / push second
factor: `flow.prepareSecondFactor("sms" | "push")` returns a
[`SecondFactorChallenge`](src/main/kotlin/net/atlasauth/atlas/Flows.kt).

**Sign-up** and **password reset** are the same shape:

```kotlin
val signUp = atlas.signUpFlow()
signUp.create(email = "…", password = "…")
signUp.prepareVerification()
signUp.attemptVerification(code = "123456")
val user = signUp.complete()

val reset = atlas.passwordResetFlow()
reset.request(email = "…")
reset.attemptVerification(code = "123456")
reset.attemptSecondFactor(code = "…")          // only if the account has MFA
reset.setNewPassword("…")
if (reset.canComplete) reset.complete()        // when sign-in-after-reset is on
```

## Jetpack Compose components

New in **0.4.0**. Drop-in UI backed by the flow driver, the native peers of
`@atlas/js`'s `<SignIn>` / `<UserButton>`, plus an observable
[`AtlasSessionState`](src/main/kotlin/net/atlasauth/atlas/Session.kt) exposing a
`StateFlow<AtlasSessionStatus>` for reactive UI. They render Material 3 defaults
unless a dashboard design or `appearance` applies (below);
pass a `Modifier` to place and size them.

```kotlin
@Composable
fun AuthGate(atlas: AtlasClient) {
    val session = rememberAtlasSessionState(atlas)
    when (val s = session.status.collectAsState().value) {
        is AtlasSessionStatus.SignedIn -> {
            AtlasUserButton(session)             // name + "Sign out"
            Home(s.user)
        }
        AtlasSessionStatus.SignedOut -> AtlasSignIn(atlas, onSignedIn = { session.refresh() })
        AtlasSessionStatus.Loading -> CircularProgressIndicator()
    }
}
```

`AtlasSessionState` is plain, non-Compose logic (so it is unit-tested without a
device): `reload()` re-reads the session, a 401/403 flips it to signed-out, and a
transport blip leaves the last known status untouched — a flaky network is not a
sign-out.

### Dashboard design + `appearance` overrides

New in **0.5.0**. `AtlasSignIn` reads the instance's appearance
(`GET /v1/appearance`, also available as `client.appearance()`). When the app
owner has saved a design in the Atlas dashboard, the screen renders it natively:

- colours from the light / dark palettes (following the system setting, or the
  design's forced `colorScheme`);
- card radius, control radius (999 = pill), border width, card style
  (solid / glass / outline / none), card shadow level and width;
- density spacing (gaps, card padding, control height);
- button style (solid / outline / soft / gradient), input style
  (outline / filled / underline), social-button layout (list / grid / icons);
- base font size, heading scale, body / heading weights, uppercase buttons;
- the logo (`logo_url`, or `logo_dark_url` in dark mode).

With no saved design (`design: null`) and no `appearance`, the screen is exactly
the plain Material 3 one from 0.4.0.

Override any token in code — a set field wins over the dashboard:

```kotlin
AtlasSignIn(
    client = atlas,
    onSignedIn = { session.refresh() },
    appearance = AtlasAppearance(
        palette = mapOf(AtlasPaletteSlot.PRIMARY to "#0f766e"),
        controlRadius = 999,                     // pill buttons + inputs
        buttonStyle = AtlasButtonStyle.SOFT,
        colorScheme = AtlasColorScheme.LIGHT,    // ignore system dark mode
        // applyDashboardDesign = false,         // use ONLY these overrides
    ),
    onSocialSignIn = { provider -> startOAuth(provider) }, // shows the enabled providers
)
```

**Fonts.** Android cannot look a font up by name, so pass the families your app
bundles, keyed by the dashboard's font id; any font you don't supply falls back to
the system sans-serif / serif / monospace family at the design's weight.

```kotlin
// res/font/inter_regular.ttf, inter_semibold.ttf (bundled in your app)
val inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)
AtlasSignIn(atlas, onSignedIn = { … }, fonts = mapOf("inter" to inter))
```

A downloadable font (`androidx.compose.ui:ui-text-google-fonts`) works the same
way: build its `FontFamily` and pass it under the font id. Glass cards render as a
translucent surface (Compose has no backdrop blur).
## Native Google sign-in (One-Tap / id_token)

New in **0.4.0**. Instead of a browser redirect, obtain a Google `id_token`
on-device via the Jetpack Credential Manager and exchange it for an Atlas session
at `POST /v1/client/sign_ins/id_token`, bound to a single-use server nonce.

```kotlin
val google = GoogleSignInManager.create(context, atlas)

lifecycleScope.launch {
    // Mints a nonce, runs the Credential Manager Google-ID ceremony, exchanges
    // the id_token. serverClientId is your Google WEB/server OAuth client id.
    val user = google.signIn(activity = this@MyActivity, serverClientId = "….apps.googleusercontent.com")
}
```

The HTTP half is also available directly for a custom ceremony:
`atlas.mintIdTokenNonce("google")` then `atlas.signInWithIdToken("google", idToken, nonce)`.
The device ceremony sits behind the injectable `GoogleIdTokenProvider` seam, so
the exchange is unit-tested without a device. A sign-in that still owes a second
factor surfaces as `AtlasException.Api` `sign_in_not_complete` — resume it with
the flow driver.

## Organizations, sessions & account

New in **0.4.0**. Typed `suspend` methods over the organizations, device-session
and `/me`-mutation surfaces. All go through the one request path (publishable key
+ the stored session), like `currentUser()`.

```kotlin
// Organizations
val memberships: List<OrganizationMembership> = atlas.listOrganizationMemberships()
val org = atlas.createOrganization(name = "Acme", slug = "acme")  // if the instance allows it

// Device sessions (§10.2)
val devices: List<DeviceSession> = atlas.listSessions()           // `current` marks this device
atlas.revokeSession(id = "sess_…")                                // sign out one device
val revoked: Int = atlas.revokeOtherSessions()                    // every OTHER device

// /me mutations
atlas.updateProfile(firstName = "Ada", unsafeMetadata = mapOf("theme" to JsonValue.Str("dark")))
atlas.addEmailAddress("ada@new.com")                              // then verifyEmailAddress(id, code)
atlas.setPrimaryEmailAddress(id = "email_…")
atlas.connectExternalAccount(provider = "github", redirectUrl = "myapp://cb")  // → authorizationUrl
atlas.changePassword(currentPassword = "…", newPassword = "…")    // or setPassword("…") for an OAuth-only account
```

Only `unsafe_metadata` is writable from a client (§4.1); `public_metadata` /
`private_metadata` are backend-only and the server refuses them.

## License

MIT — see [LICENSE](LICENSE).
