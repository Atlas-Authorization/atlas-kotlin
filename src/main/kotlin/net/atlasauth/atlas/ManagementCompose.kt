package net.atlasauth.atlas

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Account-management screens in Jetpack Compose, mirroring the web components:
 * [AtlasOrganizationSwitcher], [AtlasPricingTable], [AtlasBillingOverview],
 * [AtlasApiKeys] and [AtlasDeviceApproval]. They read the instance's dashboard
 * design (`GET /v1/appearance`) and the code-level [AtlasAppearance] exactly as
 * [AtlasSignIn] does; with neither they use plain Material 3. Labels are the same
 * English strings as the web catalog. The logic lives in the state holders
 * ([OrganizationSwitcherState], [BillingState], [ApiKeysState],
 * [DeviceApprovalState]); on-device rendering is verified in app QA.
 *
 * Billing checkout and the portal hand your [onOpenUrl] the provider's hosted URL
 * (open it in a Custom Tab).
 */

/** The design (if any) for a management screen: fetched once, overridden by code. */
@Composable
private fun rememberManagementStyle(
    client: AtlasClient,
    appearance: AtlasAppearance?,
    appearanceInfo: AtlasAppearanceInfo?,
    fonts: Map<String, FontFamily>,
): AtlasStyle? {
    var info by remember(client) { mutableStateOf(appearanceInfo) }
    LaunchedEffect(client, appearanceInfo) {
        if (appearanceInfo != null) info = appearanceInfo
        else if (info == null) info = runCatching { client.appearance() }.getOrNull()
    }
    val dark = isSystemInDarkTheme()
    return AtlasDesign.resolve(info?.design, appearance)?.let { AtlasStyle(it, dark, fonts) }
}

@Composable
private fun MText(style: AtlasStyle?, text: String, kind: Int = 0, modifier: Modifier = Modifier) {
    // kind: 0 body, 1 heading, 2 small
    val textStyle: TextStyle = when {
        style == null -> when (kind) { 1 -> MaterialTheme.typography.titleMedium; 2 -> MaterialTheme.typography.bodySmall; else -> MaterialTheme.typography.bodyMedium }
        kind == 1 -> style.heading
        kind == 2 -> style.small
        else -> style.body
    }
    Text(text, style = textStyle, modifier = modifier)
}

@Composable
private fun MError(style: AtlasStyle?, message: String?) {
    if (message != null) {
        Text(
            message,
            style = style?.small ?: MaterialTheme.typography.bodySmall,
            color = style?.color(AtlasPaletteSlot.DANGER) ?: MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun MButton(style: AtlasStyle?, label: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    when {
        style != null && primary -> AtlasPrimaryButton(style, label, enabled, onClick)
        style != null -> AtlasSecondaryButton(style, label, enabled, Modifier, onClick)
        primary -> Button(onClick = onClick, enabled = enabled) { Text(label) }
        else -> OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun MField(style: AtlasStyle?, value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    val keyboard = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text)
    val transform = if (password) PasswordVisualTransformation() else VisualTransformation.None
    if (style != null) AtlasTextInput(style, value, onChange, label, keyboard, transform)
    else OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, keyboardOptions = keyboard, visualTransformation = transform, modifier = Modifier.fillMaxWidth())
}

private fun dateOf(millis: Double): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis.toLong()))

private fun launchIn(scope: CoroutineScope, block: suspend () -> Unit) { scope.launch { block() } }

/* ── organization switcher ──────────────────────────────────────────────────── */

/**
 * The active organization with a menu to switch (or return to the personal
 * account) and, when [allowCreate], "or create a new organization".
 *
 * @param onChange called with the new active organization id (null = personal).
 */
@Composable
fun AtlasOrganizationSwitcher(
    client: AtlasClient,
    modifier: Modifier = Modifier,
    allowCreate: Boolean = true,
    onChange: (String?) -> Unit = {},
    appearance: AtlasAppearance? = null,
    appearanceInfo: AtlasAppearanceInfo? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val holder = remember(client) { OrganizationSwitcherState(client) }
    val ui by holder.state.collectAsState()
    val style = rememberManagementStyle(client, appearance, appearanceInfo, fonts)
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(holder) { holder.load() }

    val activeName = ui.memberships.firstOrNull { it.organization.id == ui.activeOrganizationId }?.organization?.name ?: "Personal account"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }, enabled = !ui.isBusy) { Text(activeName) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (ui.activeOrganizationId == null) "Personal account (active)" else "Personal account") },
                onClick = { open = false; launchIn(scope) { holder.select(null); onChange(null) } },
            )
            ui.memberships.forEach { m ->
                DropdownMenuItem(
                    text = { Text(if (m.organization.id == ui.activeOrganizationId) "${m.organization.name} (active)" else m.organization.name) },
                    onClick = { open = false; launchIn(scope) { holder.select(m.organization.id); onChange(m.organization.id) } },
                )
            }
            if (allowCreate) DropdownMenuItem(text = { Text("or create a new organization") }, onClick = { open = false; creating = true })
        }
        MError(style, ui.message)
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Create organization") },
            text = { MField(style, newName, { newName = it }, "Organization name") },
            confirmButton = {
                TextButton(enabled = !ui.isBusy && newName.isNotBlank(), onClick = {
                    launchIn(scope) { if (holder.create(newName)) { creating = false; newName = ""; onChange(holder.state.value.activeOrganizationId) } }
                }) { Text("Create organization") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

/* ── billing ────────────────────────────────────────────────────────────────── */

/**
 * The instance's plans as cards: price (promotion, trial, monthly/yearly switch),
 * features and a Subscribe button that starts the provider's hosted Checkout
 * through [onOpenUrl]; the plan the subject is on shows Manage billing instead.
 *
 * @param audience `user` or `org` to show only those plans.
 */
@Composable
fun AtlasPricingTable(
    client: AtlasClient,
    returnUrl: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    orgId: String? = null,
    audience: String? = null,
    appearance: AtlasAppearance? = null,
    appearanceInfo: AtlasAppearanceInfo? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val holder = remember(client, orgId, returnUrl) { BillingState(client, orgId, returnUrl) }
    val ui by holder.state.collectAsState()
    val style = rememberManagementStyle(client, appearance, appearanceInfo, fonts)
    var interval by remember { mutableStateOf("month") }
    LaunchedEffect(holder) { holder.load() }

    val shown = ui.plans.filter { audience == null || it.audience == audience }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (shown.any { it.annualAvailable }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton(style, "Monthly", primary = interval == "month") { interval = "month" }
                MButton(style, "Yearly", primary = interval == "year") { interval = "year" }
            }
        }
        shown.forEach { plan ->
            val isAddon = plan.kind == "addon"
            val held = ui.subscription?.liveAddons?.firstOrNull { it.plan?.id == plan.id }
            val current = if (isAddon) held != null else plan.id == ui.subscription?.plan?.id
            val view = plan.priceView(interval)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MText(style, plan.name, 1)
                plan.badge?.let { MText(style, it, 2) }
                plan.description?.let { MText(style, it) }
                view.promotionLabel?.let { MText(style, it, 2) }
                MText(style, (view.originalPrice?.let { "$it " } ?: "") + (if (view.free) "Free" else "${view.price} / ${if (view.interval == "year") "year" else "month"}"), 1)
                view.trialDays?.let { MText(style, "$it-day free trial", 2) }
                view.promotionCode?.let { MText(style, "Use code $it at checkout", 2) }
                plan.features.forEach { MText(style, "• $it") }
                if (current) {
                    MButton(style, "Manage billing") { launchIn(scope) { holder.portalUrl(held?.id)?.let(onOpenUrl) } }
                } else {
                    MButton(style, "Subscribe", enabled = ui.busyPlanId != plan.id) {
                        launchIn(scope) { val url = holder.subscribe(plan, interval); if (url != null) onOpenUrl(url) else holder.load() }
                    }
                }
            }
        }
        MError(style, ui.message)
    }
}

/** The subscription's status, the card on file, Manage billing / cancel / resume, and the invoice history. */
@Composable
fun AtlasBillingOverview(
    client: AtlasClient,
    returnUrl: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    orgId: String? = null,
    appearance: AtlasAppearance? = null,
    appearanceInfo: AtlasAppearanceInfo? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val holder = remember(client, orgId, returnUrl) { BillingState(client, orgId, returnUrl) }
    val ui by holder.state.collectAsState()
    val style = rememberManagementStyle(client, appearance, appearanceInfo, fonts)
    LaunchedEffect(holder) { holder.load() }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val sub = ui.subscription
        if (sub?.status != null) {
            MText(style, sub.plan?.name ?: "Subscription", 1)
            MText(style, sub.status.replace('_', ' ').replaceFirstChar { it.uppercase() }, 2)
            sub.currentPeriodEnd?.let { MText(style, (if (sub.cancelAtPeriodEnd) "Ends " else "Renews ") + dateOf(it), 2) }
            ui.paymentMethod?.let { MText(style, "${it.brand.replaceFirstChar { c -> c.uppercase() }} ending ${it.last4}, expires ${it.expMonth}/${it.expYear}", 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton(style, "Manage billing") { launchIn(scope) { holder.portalUrl()?.let(onOpenUrl) } }
                if (sub.isLive) {
                    if (sub.cancelAtPeriodEnd) MButton(style, "Resume subscription", primary = false) { launchIn(scope) { holder.restore() } }
                    else MButton(style, "Cancel subscription", primary = false) { launchIn(scope) { holder.cancel() } }
                }
            }
        } else if (ui.isLoaded) {
            MText(style, "No subscription yet.")
        }
        MText(style, "Invoices", 1)
        if (ui.invoices.isEmpty() && ui.isLoaded) MText(style, "No invoices yet.")
        ui.invoices.forEach { inv ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MText(style, inv.number ?: inv.id)
                MText(style, BillingPlan.formatMoney(inv.amountDue, inv.currency))
                MText(style, inv.status, 2)
                inv.hostedInvoiceUrl?.let { url -> TextButton(onClick = { onOpenUrl(url) }) { Text("View") } }
            }
        }
        MError(style, ui.message)
    }
}

/* ── API keys ───────────────────────────────────────────────────────────────── */

/**
 * The user's (or, with [organizationId], an organization's) API keys: list,
 * create (name, expiry, scopes, with the re-authentication prompt on a stale
 * session), rename and revoke. The new key's secret is shown once with a Copy button.
 *
 * @param onCopy copy the secret to the clipboard (e.g. via `LocalClipboardManager`).
 */
@Composable
fun AtlasApiKeys(
    client: AtlasClient,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    organizationId: String? = null,
    appearance: AtlasAppearance? = null,
    appearanceInfo: AtlasAppearanceInfo? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val holder = remember(client, organizationId) { ApiKeysState(client, organizationId) }
    val ui by holder.state.collectAsState()
    val style = rememberManagementStyle(client, appearance, appearanceInfo, fonts)
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var password by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf<UserApiKey?>(null) }
    var renaming by remember { mutableStateOf<UserApiKey?>(null) }
    var renameText by remember { mutableStateOf("") }
    LaunchedEffect(holder) { holder.load() }

    val atLimit = ui.list.maxActiveKeys > 0 && ui.list.activeCount >= ui.list.maxActiveKeys
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MText(style, "API keys", 1)
        MText(
            style,
            if (organizationId == null) "Keys let scripts and integrations act as you. Treat them like passwords."
            else "Keys let scripts and integrations act as this organization. Treat them like passwords.",
            2,
        )
        if (ui.status == ApiKeysStatus.DISABLED) {
            MText(style, "API keys are not available for this account.")
        } else {
            if (ui.list.keys.isEmpty() && ui.status == ApiKeysStatus.READY) MText(style, "No API keys yet.")
            ui.list.keys.forEach { key ->
                Column(Modifier.fillMaxWidth()) {
                    MText(style, key.name ?: "Unnamed key")
                    MText(style, key.prefix, 2)
                    MText(style, apiKeyStatus(key), 2)
                    if (key.isActive()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton(style, "Rename", primary = false) { renameText = key.name ?: ""; renaming = key }
                        MButton(style, "Revoke", primary = false) { revoking = key }
                    }
                }
            }
            if (atLimit) MText(style, "You've reached the maximum of ${ui.list.maxActiveKeys} active keys. Revoke one to create another.", 2)
            MButton(style, "Create key", enabled = !atLimit) { creating = true }
            MError(style, ui.message)
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false; holder.cancelStepUp() },
            title = { Text("Create key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MField(style, name, { name = it }, "Name")
                    Text("Expires")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to "Never", 30 to "In 30 days", 90 to "In 90 days", 365 to "In 365 days").forEach { (d, label) ->
                            TextButton(onClick = { days = d }) { Text(if (days == d) "[$label]" else label) }
                        }
                    }
                    if (ui.list.allowedScopes.isNotEmpty()) {
                        Text("Scopes")
                        ui.list.allowedScopes.forEach { s ->
                            Row { Checkbox(checked = s in picked, onCheckedChange = { picked = if (it) picked + s else picked - s }); Text(s) }
                        }
                    }
                    if (ui.needsStepUp) {
                        Text("Enter your password to confirm.")
                        MField(style, password, { password = it }, "Password", password = true)
                    }
                    MError(style, ui.message)
                }
            },
            confirmButton = {
                TextButton(enabled = !ui.needsStepUp || password.isNotEmpty(), onClick = {
                    launchIn(scope) {
                        if (ui.needsStepUp) holder.completeStepUp(password)
                        else holder.create(name, days.takeIf { it > 0 }, picked.sorted())
                        val s = holder.state.value
                        if (!s.needsStepUp && s.message == null) { creating = false; name = ""; password = ""; picked = emptySet() }
                    }
                }) { Text(if (ui.needsStepUp) "Confirm" else "Create key") }
            },
            dismissButton = { TextButton(onClick = { creating = false; holder.cancelStepUp() }) { Text("Cancel") } },
        )
    }
    ui.createdSecret?.let { secret ->
        AlertDialog(
            onDismissRequest = { holder.dismissSecret() },
            title = { Text("Copy your new key") },
            text = { Text("$secret\n\nThis is the only time it will be shown. If you lose it, revoke it and create another.") },
            confirmButton = { TextButton(onClick = { onCopy(secret); holder.dismissSecret() }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { holder.dismissSecret() }) { Text("Done") } },
        )
    }
    revoking?.let { key ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke this key? Anything using it will stop working.") },
            confirmButton = { TextButton(onClick = { revoking = null; launchIn(scope) { holder.revoke(key) } }) { Text("Yes, revoke") } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { key ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { MField(style, renameText, { renameText = it }, "Name") },
            confirmButton = { TextButton(onClick = { renaming = null; launchIn(scope) { holder.rename(key, renameText) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

internal fun apiKeyStatus(key: UserApiKey, nowMillis: Long = System.currentTimeMillis()): String {
    if (key.revokedAt != null) return "Revoked"
    if (!key.isActive(nowMillis)) return "Expired"
    val used = key.lastUsedAt?.let { "Last used ${dateOf(it)}" } ?: "Never used"
    return key.expiresAt?.let { "$used · Expires ${dateOf(it)}" } ?: used
}

/* ── device approval ────────────────────────────────────────────────────────── */

/**
 * Approve (or deny) the code a TV, console or CLI shows: type the code (or pass
 * one read from its QR code as [userCode]), check the app and scopes, tap Allow.
 */
@Composable
fun AtlasDeviceApproval(
    client: AtlasClient,
    modifier: Modifier = Modifier,
    userCode: String? = null,
    appearance: AtlasAppearance? = null,
    appearanceInfo: AtlasAppearanceInfo? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val holder = remember(client) { DeviceApprovalState(client) }
    val ui by holder.state.collectAsState()
    val style = rememberManagementStyle(client, appearance, appearanceInfo, fonts)
    var code by remember { mutableStateOf(userCode ?: "") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(holder, userCode) { if (!userCode.isNullOrEmpty()) holder.lookup(userCode) }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MText(style, "Sign in on a device", 1)
        when (val phase = ui.phase) {
            DeviceApprovalPhase.Entering -> {
                MText(style, "Enter the code shown on your TV, console or terminal.", 2)
                MField(style, code, { code = formatUserCode(it) }, "Code")
                MButton(style, "Continue", enabled = !ui.isBusy && code.isNotEmpty()) { launchIn(scope) { holder.lookup(code) } }
            }
            is DeviceApprovalPhase.Pending -> {
                val auth = phase.authorization
                MText(style, "${auth.client.name} wants to sign in to your account on a device.")
                if (auth.scopes.isNotEmpty()) {
                    MText(style, "It will be able to:", 2)
                    auth.scopes.forEach { MText(style, "• " + it.description.ifEmpty { it.scope }) }
                }
                MText(style, "Only continue if your device shows this code: ${auth.userCode}", 2)
                if (ui.needsStepUp) {
                    MText(style, "Enter your password to confirm.", 2)
                    MField(style, password, { password = it }, "Password", password = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton(style, "Deny", primary = false, enabled = !ui.isBusy) { launchIn(scope) { holder.decide(false) } }
                    MButton(style, "Allow", enabled = !ui.isBusy && (!ui.needsStepUp || password.isNotEmpty())) {
                        launchIn(scope) { if (ui.needsStepUp) holder.completeStepUp(password) else holder.decide(true) }
                    }
                }
            }
            DeviceApprovalPhase.Approved -> {
                MText(style, "Your device is signed in. You can go back to it now.")
                MButton(style, "Enter another code", primary = false) { code = ""; holder.reset() }
            }
            DeviceApprovalPhase.Denied -> {
                MText(style, "Sign-in denied. Nothing was shared with the device.")
                MButton(style, "Enter another code", primary = false) { code = ""; holder.reset() }
            }
        }
        MError(style, ui.message)
    }
}
