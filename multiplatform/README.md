# atlas-kmp

Atlas sign-in and session handling for Kotlin Multiplatform (JVM and iOS), built on Ktor.

- Hosted sign-in in the system browser: authorization code with PKCE (S256, SHA-256 in common code, secure random from the platform). The `state` is checked, and the authorize and token endpoints must be on the issuer's origin.
- The OAuth access token is traded for an Atlas session (RFC 8693), so the app carries a short-lived session JWT instead of a cookie.
- `getToken()` rotates the session lazily and single-flight, and stores the new refresh token every time. A refresh token the server refuses signs the person out; a network error keeps the current token.
- `authPlugin(...)` is a Ktor client plugin that adds the token only to the API URLs you list.

```kotlin
val atlas = AtlasNativeClient(
    AtlasNativeOptions(
        issuer = "https://auth.example.com",
        publishableKey = "pk_live_...",
        clientId = "your_first_party_client",
        redirectUri = "myapp://callback",
    ),
    browser = AuthBrowser { authorizeUrl, redirectUri -> openCustomTabAndAwaitRedirect(authorizeUrl, redirectUri) },
    store = keychainBackedStore, // implement SessionStore over Keychain / Keystore
)

atlas.signIn()
val api = HttpClient { install(atlas.authPlugin(listOf("https://api.example.com"))) }
api.get("https://api.example.com/orders") // verified server side with a server SDK
```

`AuthBrowser` and `SessionStore` are the two platform seams: use `ASWebAuthenticationSession` and the Keychain on iOS, Custom Tabs and EncryptedSharedPreferences on Android. For an Android-only app the `atlas-android` SDK is the richer choice.

Build: `gradle jvmTest` (the iOS targets build on macOS).
