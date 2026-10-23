# Changelog

`net.atlasauth:atlas-android` — the Android-library SDK (source in `sdks/kotlin`).

## 0.6.0 — 2026-10-23

### Added

- **Device id on account-creating starts.** Email sign-up and the social / SSO / id_token sign-in starts carry a random `device_id` (`DeviceId`) for the instance's accounts-per-device abuse limit, like the JS SDK. `AtlasClient.deviceIdProvider` defaults to an id stable for the process; set `DeviceId.persistent(context)` to keep it across launches.
- **Passkey sign-in captcha.** `PasskeyManager.signInWithPasskey(activity, captchaToken)` forwards the sign-in captcha token; an instance that challenges sign-ins answers `CAPTCHA_REQUIRED` without one (`AtlasException.isCaptchaRequired`, `AtlasErrorCode.CAPTCHA_REQUIRED` / `CAPTCHA_FAILED` / `CHALLENGE_UNAVAILABLE`).
- **Passkey-only sign-up.** `PasskeySignUpFlow` (`start`, `attemptVerification`, `awaitingPasskey`, `complete`) and `PasskeyManager.createSignUpPasskey(activity, flow)` (begin → Credential Manager → finish). The account exists only once the passkey is registered.
- **SMS MFA enrolment mid-sign-in.** `SignInFlow.prepareSmsMfaEnrollment(phone)` and `attemptSmsMfaEnrollment(factorId, code)`. `MfaEnrollment` carries `strategy`, `phone`, and for TOTP the QR image `qrCode` / `qrImageDataUrl`.
- **Organization audit log.** `AtlasClient.organizationAuditLog(organizationId, limit, startingAfter, action)` (`GET /v1/client/organizations/:id/audit_logs`, organization admins).

## 0.5.0

### Added

- **Dashboard design in `AtlasSignIn`.** The screen reads `GET /v1/appearance`
  (also public as `client.appearance()`) and, when the app owner saved a design,
  renders it natively: light/dark palettes (system or forced `colorScheme`),
  card + control radii (999 = pill), border width, card style / shadow / width,
  density spacing, button style (solid / outline / soft / gradient), input style
  (outline / filled / underline), social-button layout (list / grid / icons),
  font size / weights / type scale / uppercase buttons, and the light/dark logo.
  With `design: null` the screen is unchanged from 0.4.0.
- **`appearance` overrides.** `AtlasSignIn(appearance = AtlasAppearance(…))`
  overrides any design token in code (code wins over the dashboard;
  `applyDashboardDesign = false` ignores the dashboard). `fonts` maps catalog
  font ids to `FontFamily`s the app bundles; others fall back to the system
  family of the same category.
- **Social buttons.** `onSocialSignIn` shows the enabled providers in the
  design's layout; `AtlasSocialButtons` is public for custom screens.
- **Design model.** `AtlasDesign` (tolerant decoder of `design.resolved`:
  unknown keys ignored, missing/invalid values default), `AtlasAppearanceInfo`,
  and the token enums.
- **Typed error codes.** `AtlasException.knownCode` → `AtlasErrorCode`;
  `isEmailDeliveryNotConfigured` for the new 422
  `EMAIL_DELIVERY_NOT_CONFIGURED`. `AtlasSignIn` shows the server's message and
  stops offering "Email me a code" after that refusal.
