# Changelog

`net.atlasauth:atlas-android` — the Android-library SDK (source in `sdks/kotlin`).

## 0.10.0 — 2026-10-24

### Added

- **SSO claims.** `OidcClaims.ssoOrganizationId` / `ssoConnectionType` read the `sso_organization_id` (the Atlas organization that owns the enterprise SSO connection, independent of `org_id`) and `sso_connection_type` (`saml` or `oidc`) claims from userinfo, introspection, an ID or access token, or a JWT payload. Both are absent on a non-SSO sign-in; an instance-wide connection carries the type but no organization.

## 0.9.0 — 2026-10-24

### Added

- **`getToken({for})` for integrations.** ``AtlasClient.getToken(integration, spaceId)`` mints a short-lived token for a configured identity integration (a preset such as `chatly`, `forumulate`, `cookiemunch`, `referalli`, or a custom id) from the signed-in session; `null` when the integration is not enabled. Forumulate takes a `spaceId` and returns the redeem fields. ``AtlasClient.getAtlasIdToken(integration, accessToken, origin, dpopProof)`` is the Sign in with Atlas browser-token route (`POST /v1/client/integrations/:id/atlas_id_tokens`): Bearer or DPoP access token, an `Origin` the Sign in with Atlas app allows, no cookies.
- **OIDC claims.** `OidcClaims` reads `org_id`, `org_slug`, `org_role`, `org_permissions`, `orgs` and the `sso_connection_id` / `sso_tenant_id` / `sso_issuer` / `sso_domain` claims from userinfo, introspection or a JWT payload (the JWT form does not verify the signature). `AtlasScopes.organizations` / `withOrganizations(...)` add the `organizations` scope to a scope list such as the device sign-in's.
- **New error codes.** `INSUFFICIENT_SCOPE` (the old `SCOPE_MISSING` still reads as it; `requiredScope` reads `meta.required_scope`), `ORIGIN_NOT_ALLOWED`, `STEP_UP_REQUIRED`, `ROUTE_NOT_FOUND` (`didYouMean`, `allowedMethods`). Error items now carry `meta` and `docs_url`.
- **Key format.** The client SDKs check no key prefix; they send whatever publishable key they are given (`pk_...` or `atlas_<env>_pk_...`).
- FedCM / One Tap: not applicable to a native SDK (browser-only). Native Google sign-in keeps using the existing id_token route.

## 0.8.0 — 2026-10-24

### Added

- **Policy acceptance** (terms of service, privacy, any policy the app defines).
  - Client API: `client.policies(locale)` (`GET /v1/client/policies`), `client.myPolicies()` and `client.acceptMyPolicies(...)` (in-app re-acceptance), `PolicyGate`, and `client.signUpPolicyController()` / `client.policyController(attempt)` which build a `PolicyAcceptanceController` (per-policy ticks, reading clock, scroll-to-end flag, `canSubmit()`, `build()` into `policy_acceptances`).
  - Sign-up: `SignUpFlow.create(..., policyAcceptances)` and `PasskeySignUpFlow.start(..., policyAcceptances)`. A 422 `POLICY_ACCEPTANCE_REQUIRED` is `AtlasException.isPolicyAcceptanceRequired`; `client.refreshPoliciesOnRequired(controller, error)` reloads the list.
  - Sign-in: the new `FlowStep.AcceptPolicies` (status `needs_policy_acceptance`, `FlowAttempt.pendingPolicies` / `policyAcceptanceToken`) continues with `acceptPolicies(...)` on `SignInFlow`, `SignUpFlow`, `PasswordResetFlow` and `PasskeySignUpFlow`. `AtlasRedirectResult.parse(url)` reads `__atlas_attempt` / `__atlas_status` / `__atlas_policy_token` from a Custom Tab or web-auth callback, and `client.resumeSignIn(redirect)` / `resumeSignUp(redirect)` continue the held attempt. `client.beginIdTokenSignIn(...)` returns the flow for a native id_token sign-in that may be held.
  - Compose UI: `AtlasPolicyConsent` (checkbox, combined or per policy, with an in-app document viewer), `AtlasPolicyClickwrap`, `AtlasPolicyScrollDialog` / `AtlasPolicyScrollHost` (unskippable dialog, Accept after the end is seen or when the text fits, Decline cancels), `AtlasPolicyAcceptStep`, `AtlasPolicyGate` (banner plus re-acceptance), `AtlasPolicyViewer`, `AtlasPolicyText`. `AtlasSignIn` shows the accept step itself. Documents are drawn natively from the server's sanitised HTML (`PolicyHtml`), not in a web view. All wording is overridable with `AtlasPolicyStrings`.

## 0.7.0 — 2026-10-24

### Added

- **Upstream logout on sign-out.** `signOut(returnUrl, followLogoutUrl)` now returns a `SignOutResult` with the upstream OIDC provider's `upstreamLogoutUrl` and the SAML `samlLogoutUrl` (https only) that Atlas hands back when the session came from such a provider. By default the URL is opened through `AtlasClient.logoutUrlOpener` — the system browser for a client built with `AtlasClient.create(context, ...)` (`AtlasClient.openInSystemBrowser`). `returnUrl` must be on an allowed origin.
- **Device sign-in approval.** `lookupDeviceAuthorization(userCode)`, `approveDeviceAuthorization`, `denyDeviceAuthorization` (`DeviceAuthorization`) let a signed-in phone approve the code a TV, watch or CLI shows; `formatUserCode`, `userCodeFromUrl` (for a scanned QR code). A refusal needing a fresh re-authentication is `AtlasException.Api` with code `STEP_UP_REQUIRED`.
- **Passkey PRF pass-through.** `registerPasskey`, `signInWithPasskey` and `createSignUpPasskey` take `prf: PasskeyPrf` (salts as bytes, or `PasskeyPrf.ofSalt("...")` as UTF-8; per-credential salts on sign-in) and `onPrfResult`. The `prf` extension is passed to Credential Manager and the outputs (`PasskeyPrfResult`) reach only the callback — nothing PRF-related is sent to Atlas. Providers without PRF support return no results.

### Changed

- `signOut()` returns `SignOutResult` instead of `Unit` (existing calls compile unchanged).

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
