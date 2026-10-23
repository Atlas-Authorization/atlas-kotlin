package net.atlasauth.atlas

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Prebuilt Jetpack Compose UI for Atlas — the native peers of `@atlas/js`'s
 * `<SignIn>` and `<UserButton>`. They drive the same [SignInFlow] /
 * [AtlasSessionState] the headless API exposes, so an app can drop them in for
 * the common case and still reach for the flow driver when it needs its own UI.
 *
 * Without a dashboard design or an [AtlasAppearance] they use plain Material 3
 * defaults; with one, [AtlasSignIn] renders it (see its docs). Pass a [Modifier]
 * to place and size them. The screens compile against Compose and are
 * driven by the unit-tested flow driver underneath — on-device rendering /
 * ceremony is verified in app QA, not here.
 */

/**
 * Remember an [AtlasSessionState] bound to [client] and kick off the first
 * [AtlasSessionState.reload]. Collect its [AtlasSessionState.status] to render
 * reactively:
 *
 * ```
 * val session = rememberAtlasSessionState(atlas)
 * when (val s = session.status.collectAsState().value) {
 *     is AtlasSessionStatus.SignedIn -> Home(s.user)
 *     AtlasSessionStatus.SignedOut   -> AtlasSignIn(atlas) { session.refresh() }
 *     AtlasSessionStatus.Loading     -> Splash()
 * }
 * ```
 */
@Composable
fun rememberAtlasSessionState(client: AtlasClient): AtlasSessionState {
    val scope = rememberCoroutineScope()
    val state = remember(client) { AtlasSessionState(client, scope) }
    LaunchedEffect(state) { state.reload() }
    return state
}

/**
 * A prebuilt sign-in screen driving a [SignInFlow]: identifier → password (or an
 * emailed code) → second factor → done. On completion it persists the session and
 * calls [onSignedIn] with the user. It renders whatever the server's current step
 * demands and never picks a step itself.
 *
 * **Design.** It reads the instance's appearance (`GET /v1/appearance`, or the
 * [appearanceInfo] you pass). When the app owner saved a design in the Atlas
 * dashboard it is rendered — colours (light/dark), fonts, radii, borders, shadow,
 * density, button / input / social-button styles and the logo. [appearance]
 * overrides any token in code (code wins over the dashboard). With no dashboard
 * design and no [appearance], the screen keeps its plain Material 3 look.
 *
 * @param client the Atlas client the flow runs on.
 * @param onSignedIn invoked with the signed-in user once the flow completes.
 * @param modifier layout modifier for the root column.
 * @param appearance code-level design overrides (win over the dashboard design).
 * @param appearanceInfo a pre-fetched appearance; when null it is fetched (a
 *   failed fetch just keeps the default look).
 * @param fonts font families your app bundles, keyed by catalog id (`"inter"`,
 *   `"source-serif"`, …); others fall back to the system family of the same kind.
 * @param onSocialSignIn when set, the enabled social providers are shown and the
 *   tapped provider id is passed here (start OAuth with [AtlasClient.oauthAuthorizeUrl]).
 */
@Composable
fun AtlasSignIn(
    client: AtlasClient,
    onSignedIn: (AtlasUser) -> Unit,
    modifier: Modifier = Modifier,
    appearance: AtlasAppearance? = null,
    appearanceInfo: AtlasAppearanceInfo? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
    onSocialSignIn: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val flow = remember(client) { client.signInFlow() }

    var step by remember { mutableStateOf<FlowStep>(FlowStep.CollectIdentifier) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var usingEmailCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The policies a held attempt waits on (FlowStep.AcceptPolicies).
    var policies by remember { mutableStateOf<PolicyAcceptanceController?>(null) }
    // Set once the server said the app can't send email: stop offering email codes.
    var emailUnavailable by remember { mutableStateOf(false) }

    var info by remember(client) { mutableStateOf(appearanceInfo) }
    LaunchedEffect(client, appearanceInfo) {
        if (appearanceInfo != null) {
            info = appearanceInfo
        } else if (info == null) {
            // A failed fetch is not a user-facing error: the form keeps its default look.
            info = runCatching { client.appearance() }.getOrNull()
        }
    }
    val systemIsDark = isSystemInDarkTheme()
    val style = AtlasDesign.resolve(info?.design, appearance)?.let { AtlasStyle(it, systemIsDark, fonts) }

    // Run a flow step, map a thrown AtlasException to the inline error, and —
    // when the attempt reaches `complete` — finish into a session.
    fun run(block: suspend () -> FlowStep) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val next = block()
                if (next is FlowStep.AcceptPolicies) {
                    // Fill in the content the attempt only listed.
                    policies = client.policyController(flow.attempt)
                }
                step = next
                if (next is FlowStep.Done) {
                    onSignedIn(flow.complete())
                }
            } catch (e: AtlasException) {
                val view = signInErrorView(e)
                error = view.message
                if (view.emailUnavailable) {
                    emailUnavailable = true
                    usingEmailCode = false
                }
            } finally {
                busy = false
            }
        }
    }

    @Composable
    fun heading(text: String) {
        if (style != null) Text(text, style = style.heading) else Text(text)
    }

    @Composable
    fun caption(text: String) {
        if (style != null) Text(text, style = style.small) else Text(text)
    }

    @Composable
    fun field(
        value: String,
        onChange: (String) -> Unit,
        label: String,
        keyboardType: KeyboardType,
        secret: Boolean = false,
    ) {
        val transform = if (secret) PasswordVisualTransformation() else VisualTransformation.None
        if (style != null) {
            AtlasTextInput(style, value, onChange, label, KeyboardOptions(keyboardType = keyboardType), transform)
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                singleLine = true,
                visualTransformation = transform,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    fun primary(label: String, enabled: Boolean, onClick: () -> Unit) {
        if (style != null) {
            AtlasPrimaryButton(style, label, enabled, onClick)
        } else {
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }

    @Composable
    fun secondary(label: String, onClick: () -> Unit) {
        if (style != null) {
            AtlasSecondaryButton(style, label, enabled = !busy, onClick = onClick)
        } else {
            TextButton(onClick = onClick, enabled = !busy) { Text(label) }
        }
    }

    @Composable
    fun stepContent() {
        when (step) {
            is FlowStep.CollectIdentifier -> {
                heading("Sign in")
                val providers = info?.providers.orEmpty()
                if (onSocialSignIn != null && providers.isNotEmpty()) {
                    AtlasSocialButtons(
                        providers = providers,
                        onSelect = onSocialSignIn,
                        design = style?.design ?: AtlasDesign.DEFAULTS,
                        systemIsDark = systemIsDark,
                        fonts = fonts,
                    )
                }
                field(identifier, { identifier = it }, "Email", KeyboardType.Email)
                primary("Continue", !busy && identifier.isNotBlank()) { run { flow.create(identifier.trim()) } }
            }

            is FlowStep.CollectFirstFactor -> {
                if (usingEmailCode) {
                    heading("Enter the code we emailed you")
                    field(code, { code = it }, "Code", KeyboardType.Number)
                    primary("Verify", !busy && code.isNotBlank()) { run { flow.attemptEmailCode(code.trim()) } }
                } else {
                    heading("Enter your password")
                    field(password, { password = it }, "Password", KeyboardType.Password, secret = true)
                    primary("Sign in", !busy && password.isNotBlank()) { run { flow.attemptPassword(password) } }
                    if (!emailUnavailable) {
                        secondary("Email me a code instead") { run { usingEmailCode = true; flow.prepareEmailCode() } }
                    }
                }
            }

            is FlowStep.CollectEmailCode -> {
                heading("Enter the code we emailed you")
                field(code, { code = it }, "Code", KeyboardType.Number)
                primary("Verify", !busy && code.isNotBlank()) { run { flow.attemptEmailCode(code.trim()) } }
            }

            is FlowStep.CollectSecondFactor -> {
                heading("Two-step verification")
                field(code, { code = it }, "Authentication code", KeyboardType.Number)
                primary("Verify", !busy && code.isNotBlank()) { run { flow.attemptSecondFactor(code.trim()) } }
            }

            is FlowStep.AcceptPolicies -> policies?.let { controller ->
                AtlasPolicyAcceptStep(
                    controller = controller,
                    enabled = !busy,
                    onAccept = { acceptances -> run { flow.acceptPolicies(acceptances) } },
                    onDecline = {
                        // Declining abandons the held attempt: back to the first step, with the reason.
                        val names = joinPolicyTitles(controller.policies.map { it.title }, "and")
                        step = FlowStep.CollectIdentifier
                        error = fillPolicyTemplate(AtlasPolicyStrings().declined, "policy" to names)
                    },
                )
            } ?: caption("Loading\u2026")

            is FlowStep.Done -> caption("Signed in.")

            else -> caption("This sign-in step needs the full web flow or an app update.")
        }

        if (busy) {
            if (style != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = style.color(AtlasPaletteSlot.PRIMARY))
                }
            } else {
                CircularProgressIndicator()
            }
        }
        error?.let {
            if (style != null) Text(it, style = style.small.copy(color = style.color(AtlasPaletteSlot.DANGER))) else Text(it)
        }
    }

    if (style == null) {
        Column(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { stepContent() }
    } else {
        AtlasCard(style, modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(style.design.spacing.gap.dp)) {
                style.design.logo(systemIsDark)?.let { AtlasLogo(it, style.design.logoHeight) }
                stepContent()
            }
        }
    }
}

/** What the sign-in UI shows for a failed step. */
internal data class SignInErrorView(val message: String, val code: AtlasErrorCode?, val emailUnavailable: Boolean)

/**
 * Map a failed step to the UI: the server's message verbatim (it is written for
 * end users), its typed code, and whether email-based steps should be withdrawn
 * (`EMAIL_DELIVERY_NOT_CONFIGURED`).
 */
internal fun signInErrorView(e: AtlasException): SignInErrorView =
    SignInErrorView(
        message = e.message ?: "Something went wrong.",
        code = e.knownCode,
        emailUnavailable = e.isEmailDeliveryNotConfigured,
    )

/**
 * A prebuilt account button: shows the signed-in user and a sign-out action, or a
 * prompt when signed out. Collects the [state]'s status, so it re-renders when the
 * session changes.
 *
 * @param state the observable session (see [rememberAtlasSessionState]).
 * @param onSignIn tapped when signed out (open your sign-in screen).
 * @param modifier layout modifier for the root row.
 */
@Composable
fun AtlasUserButton(
    state: AtlasSessionState,
    onSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val status by state.status.collectAsState()

    Row(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val s = status) {
            is AtlasSessionStatus.SignedIn -> {
                Text(displayName(s.user))
                TextButton(onClick = { scope.launch { state.signOut() } }) { Text("Sign out") }
            }
            AtlasSessionStatus.SignedOut -> {
                Text("Not signed in")
                TextButton(onClick = onSignIn) { Text("Sign in") }
            }
            AtlasSessionStatus.Loading -> {
                Text("Loading…")
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/** A human label for a user — full name, else username, else the id. */
internal fun displayName(user: AtlasUser): String {
    val name = listOfNotNull(user.firstName, user.lastName).joinToString(" ").trim()
    return when {
        name.isNotEmpty() -> name
        !user.username.isNullOrBlank() -> user.username
        else -> user.id
    }
}
