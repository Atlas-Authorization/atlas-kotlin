package net.atlasauth.atlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/*
 * Prebuilt Jetpack Compose UI for policy acceptance. Every piece reads and writes
 * a [PolicyAcceptanceController], the same state machine the headless API uses, so
 * an app can mix them with its own screens:
 *
 *  - [AtlasPolicyConsent]   the checkbox(es) for a sign-up form, with an in-app document viewer;
 *  - [AtlasPolicyClickwrap] the "By continuing you agree to ..." sentence for `link` policies;
 *  - [AtlasPolicyScrollHost] the unskippable scroll-to-accept dialog for `scroll` policies;
 *  - [AtlasPolicyAcceptStep] the step a held sign-in shows (`FlowStep.AcceptPolicies`);
 *  - [AtlasPolicyGate]      the banner + re-accept flow for a signed-in user.
 *
 * Documents are drawn natively from the server's sanitised HTML ([PolicyHtml]);
 * nothing is rendered in a web view.
 */

/** Re-compose when [controller] changes. */
@Composable
internal fun rememberPolicyTick(controller: PolicyAcceptanceController): Int {
    var tick by remember(controller) { mutableStateOf(0) }
    DisposableEffect(controller) {
        val stop = controller.subscribe { tick += 1 }
        onDispose { stop() }
    }
    return tick
}

/** A policy document as an [AnnotatedString]-based list of blocks. */
@Composable
fun AtlasPolicyText(
    html: String,
    modifier: Modifier = Modifier,
    onLink: ((String) -> Unit)? = null,
) {
    val blocks = remember(html) { PolicyHtml.parse(html) }
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b.kind) {
                PolicyBlock.Kind.RULE -> androidx.compose.material3.HorizontalDivider()
                else -> {
                    val annotated = remember(b, linkColor) { policyAnnotated(b.spans, linkColor) }
                    val style = when (b.kind) {
                        PolicyBlock.Kind.HEADING -> when (b.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }.copy(fontWeight = FontWeight.SemiBold)
                        PolicyBlock.Kind.QUOTE -> MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                        else -> MaterialTheme.typography.bodyMedium
                    }
                    val indent = (b.depth * 16 + if (b.kind == PolicyBlock.Kind.QUOTE) 12 else 0).dp
                    Row(Modifier.padding(start = indent)) {
                        if (b.marker != null) {
                            Text(b.marker + " ", style = style, modifier = Modifier.padding(end = 4.dp))
                        }
                        ClickableText(
                            text = annotated,
                            style = style.copy(color = MaterialTheme.colorScheme.onSurface),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { onLink?.invoke(it.item) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Style [spans] (bold, italic, code, links) into an [AnnotatedString]. */
internal fun policyAnnotated(spans: List<PolicySpan>, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        for (s in spans) {
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                color = if (s.href != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (s.href != null) TextDecoration.Underline else null,
            )
            if (s.href != null) pushStringAnnotation("URL", s.href)
            withStyle(style) { append(s.text) }
            if (s.href != null) pop()
        }
    }

/** The header lines of a document: version, effective date. */
private fun policyMeta(policy: AtlasPolicy, strings: AtlasPolicyStrings): String {
    val parts = ArrayList<String>()
    if (policy.version.isNotEmpty()) parts.add(fillPolicyTemplate(strings.version, "version" to policy.version))
    val date = formatPolicyDate(policy.effectiveAt)
    if (date.isNotEmpty()) parts.add(fillPolicyTemplate(strings.effective, "date" to date))
    return parts.joinToString(" · ")
}

/**
 * The in-app document viewer: shows whichever document the [controller] has open
 * (`controller.openDocument(key)`), with a Back button. Reading time is recorded as
 * `view_ms`. Shown as a full-screen dialog, so it works wherever the links are.
 */
@Composable
fun AtlasPolicyViewer(
    controller: PolicyAcceptanceController,
    strings: AtlasPolicyStrings = AtlasPolicyStrings(),
    onLink: ((String) -> Unit)? = null,
) {
    rememberPolicyTick(controller)
    val policy = controller.viewing ?: return
    Dialog(
        onDismissRequest = { controller.closeDocument() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val scroll = rememberScrollState()
            LaunchedEffect(policy.versionId, scroll.value, scroll.maxValue) {
                if (isPolicyScrolledToEnd(scroll.value, scroll.maxValue)) controller.markScrolledToEnd(policy.key)
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { controller.closeDocument() }) { Text(strings.viewerBack) }
                Text(policy.title, style = MaterialTheme.typography.headlineSmall)
                val meta = policyMeta(policy, strings)
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(scroll)) {
                    AtlasPolicyText(PolicyHtml.htmlFor(policy), Modifier.fillMaxWidth(), onLink)
                }
            }
        }
    }
}

/** A sentence with each policy title tappable (opening the viewer). */
@Composable
private fun PolicySentence(
    template: String,
    titles: List<AtlasPolicy>,
    strings: AtlasPolicyStrings,
    controller: PolicyAcceptanceController,
    onPlainTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val name = if (template.contains("{policies}")) "policies" else "policy"
    val (before, after) = splitPolicyTemplate(template, name)
    val text = remember(template, titles, linkColor) {
        buildAnnotatedString {
            append(before)
            titles.forEachIndexed { i, p ->
                if (i > 0) append(if (i == titles.size - 1) " ${strings.and} " else ", ")
                pushStringAnnotation("POLICY", p.key)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(p.title) }
                pop()
            }
            append(after)
        }
    }
    ClickableText(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier,
        onClick = { offset ->
            val hit = text.getStringAnnotations("POLICY", offset, offset).firstOrNull()
            if (hit != null) controller.openDocument(hit.item) else onPlainTap?.invoke()
        },
    )
}

/**
 * The agreement checkbox(es) for a sign-up form: one combined "I agree to the A and
 * B" box (or one each when the controller was built with `combined = false`) for
 * the `checkbox` policies. Tapping a title opens the document in the in-app viewer.
 * Read [PolicyAcceptanceController.canSubmit] to enable the submit button.
 *
 * Pass `showViewer = false` when another policy component on the same screen
 * already hosts the viewer ([AtlasPolicyViewer]).
 */
@Composable
fun AtlasPolicyConsent(
    controller: PolicyAcceptanceController,
    modifier: Modifier = Modifier,
    strings: AtlasPolicyStrings = AtlasPolicyStrings(),
    showViewer: Boolean = true,
    onLink: ((String) -> Unit)? = null,
) {
    rememberPolicyTick(controller)
    val boxes = controller.checkboxPolicies()
    if (boxes.isNotEmpty()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (controller.combined) {
                val checked = controller.combinedChecked
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { controller.setAllChecked(it) })
                    PolicySentence(strings.agreeCombined, boxes, strings, controller, onPlainTap = { controller.setAllChecked(!checked) })
                }
            } else {
                for (p in boxes) {
                    val checked = controller.isChecked(p.key)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { controller.setChecked(p.key, it) })
                        PolicySentence(strings.agreeOne, listOf(p), strings, controller, onPlainTap = { controller.setChecked(p.key, !checked) })
                    }
                }
            }
        }
    }
    if (showViewer) AtlasPolicyViewer(controller, strings, onLink)
}

/**
 * The clickwrap sentence for `link` policies ("By continuing you agree to the A and
 * B"), meant to sit under the submit button: submitting accepts them. Draws nothing
 * when there are none.
 */
@Composable
fun AtlasPolicyClickwrap(
    controller: PolicyAcceptanceController,
    modifier: Modifier = Modifier,
    strings: AtlasPolicyStrings = AtlasPolicyStrings(),
    showViewer: Boolean = true,
    onLink: ((String) -> Unit)? = null,
) {
    rememberPolicyTick(controller)
    val links = controller.linkPolicies()
    if (links.isNotEmpty()) {
        PolicySentence(strings.clickwrap, links, strings, controller, modifier = modifier)
    }
    if (showViewer) AtlasPolicyViewer(controller, strings, onLink)
}

/**
 * The unskippable dialog for one `scroll` policy: it cannot be dismissed (back
 * button and outside taps are ignored), Accept turns on only once the end of the
 * text has been seen (or at once when the text fits), and Decline cancels. For a
 * re-acceptance it shows what changed.
 */
@Composable
fun AtlasPolicyScrollDialog(
    controller: PolicyAcceptanceController,
    policy: AtlasPolicy,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    strings: AtlasPolicyStrings = AtlasPolicyStrings(),
    onLink: ((String) -> Unit)? = null,
) {
    DisposableEffect(policy.versionId) {
        controller.startClock(policy.key)
        onDispose { controller.stopClock(policy.key) }
    }
    val scroll = rememberScrollState()
    val atEnd = isPolicyScrolledToEnd(scroll.value, scroll.maxValue)
    LaunchedEffect(policy.versionId, atEnd) {
        if (atEnd) controller.markScrolledToEnd(policy.key)
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val title = if (policy.reaccept == true) fillPolicyTemplate(strings.reacceptTitle, "policy" to policy.title) else policy.title
                Text(title, style = MaterialTheme.typography.headlineSmall)
                val meta = policyMeta(policy, strings)
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
                if (policy.reaccept == true && !policy.summaryOfChanges.isNullOrEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(strings.summaryTitle, style = MaterialTheme.typography.titleSmall)
                            Text(policy.summaryOfChanges, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(scroll)) {
                    AtlasPolicyText(PolicyHtml.htmlFor(policy), Modifier.fillMaxWidth(), onLink)
                }
                if (!atEnd) {
                    Text(
                        strings.scrollHint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { contentDescription = strings.scrollHint },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDecline) { Text(strings.decline) }
                    Button(onClick = onAccept, enabled = atEnd) { Text(strings.accept) }
                }
            }
        }
    }
}

/**
 * Walks the `scroll` policies one dialog at a time while [active] is true: each
 * [AtlasPolicyScrollDialog] accepts into the [controller], and when none are left
 * [onDone] is called (send the sign-up or accept the held sign-in then). Declining
 * any of them calls [onDecline] with that policy and stops.
 */
@Composable
fun AtlasPolicyScrollHost(
    controller: PolicyAcceptanceController,
    active: Boolean,
    onDone: () -> Unit,
    onDecline: (AtlasPolicy) -> Unit,
    strings: AtlasPolicyStrings = AtlasPolicyStrings(),
    onLink: ((String) -> Unit)? = null,
) {
    rememberPolicyTick(controller)
    if (!active) return
    val next = controller.pendingScroll()
    if (next == null) {
        LaunchedEffect(Unit) { onDone() }
    } else {
        AtlasPolicyScrollDialog(
            controller = controller,
            policy = next,
            onAccept = { controller.acceptScroll(next.key) },
            onDecline = { onDecline(next) },
            strings = strings,
            onLink = onLink,
        )
    }
}

/**
 * The step a sign-in shows when the server holds the attempt at
 * `needs_policy_acceptance` ([FlowStep.AcceptPolicies]): the policies to accept
 * (checkboxes, the clickwrap sentence, and scroll dialogs on submit), then
 * [onAccept] gets the `policy_acceptances` to send with
 * [SignInFlow.acceptPolicies]. [onDecline] abandons the sign-in.
 *
 * @param controller build it with [AtlasClient.policyController] from the held attempt.
 */
@Composable
fun AtlasPolicyAcceptStep(
    controller: PolicyAcceptanceController,
    onAccept: (List<PolicyAcceptance>) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    strings: AtlasPolicyStrings = AtlasPolicyStrings(),
    onLink: ((String) -> Unit)? = null,
) {
    rememberPolicyTick(controller)
    var reading by remember(controller) { mutableStateOf(false) }
    var message by remember(controller) { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.pendingTitle, style = MaterialTheme.typography.titleMedium)
        Text(strings.pendingIntro, style = MaterialTheme.typography.bodyMedium)
        for (p in controller.policies.filter { it.reaccept == true && !it.summaryOfChanges.isNullOrEmpty() }) {
            Text("${p.title}: ${p.summaryOfChanges}", style = MaterialTheme.typography.bodySmall)
        }
        AtlasPolicyConsent(controller, strings = strings, showViewer = false)
        Button(
            onClick = {
                message = null
                if (controller.scrollPolicies().any { !controller.isChecked(it.key) }) reading = true else onAccept(controller.build())
            },
            enabled = enabled && controller.canSubmit(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(strings.submit) }
        AtlasPolicyClickwrap(controller, strings = strings, showViewer = false)
        TextButton(onClick = onDecline, enabled = enabled) { Text(strings.decline) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    AtlasPolicyViewer(controller, strings, onLink)
    AtlasPolicyScrollHost(
        controller = controller,
        active = reading,
        onDone = {
            reading = false
            onAccept(controller.build())
        },
        onDecline = { p ->
            reading = false
            message = fillPolicyTemplate(strings.declined, "policy" to p.title)
            onDecline()
        },
        strings = strings,
        onLink = onLink,
    )
}

/**
 * A banner for a signed-in user whose policies need re-accepting (a newer version
 * is in force, perhaps with a grace window). Draws nothing when there is nothing to
 * accept. "Review and accept" opens the acceptance step in a dialog and posts the
 * result to `POST /v1/client/me/policies/accept`.
 *
 * ```
 * AtlasPolicyGate(atlas)   // put it at the top of your signed-in screen
 * ```
 */
@Composable
fun AtlasPolicyGate(
    client: AtlasClient,
    modifier: Modifier = Modifier,
    locale: String? = null,
    strings: AtlasPolicyStrings = AtlasPolicyStrings(),
    onAccepted: () -> Unit = {},
    onLink: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val gate = remember(client, locale) { PolicyGate(client, locale) }
    var loadedTick by remember(gate) { mutableStateOf(0) }
    var controller by remember(gate) { mutableStateOf<PolicyAcceptanceController?>(null) }
    var busy by remember(gate) { mutableStateOf(false) }
    var error by remember(gate) { mutableStateOf<String?>(null) }
    LaunchedEffect(gate) {
        // A failed load just hides the banner: it must never block the signed-in screen.
        runCatching { gate.load() }
        loadedTick += 1
    }
    loadedTick.let { }
    if (gate.pending.isEmpty() && controller == null) return
    val titles = joinPolicyTitles(gate.pending.map { it.title }, strings.and)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.bannerTitle, style = MaterialTheme.typography.titleSmall)
            Text(fillPolicyTemplate(strings.bannerBody, "policies" to titles), style = MaterialTheme.typography.bodyMedium)
            gate.graceEndsAt?.let {
                Text(fillPolicyTemplate(strings.bannerGrace, "date" to formatPolicyDate(it)), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            controller = gate.begin()
                        } catch (e: AtlasException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(strings.bannerReview) }
        }
    }
    controller?.let { c ->
        Dialog(onDismissRequest = { controller = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                AtlasPolicyAcceptStep(
                    controller = c,
                    modifier = Modifier.padding(16.dp),
                    enabled = !busy,
                    strings = strings.copy(pendingTitle = strings.bannerTitle, pendingIntro = strings.reacceptIntro),
                    onLink = onLink,
                    onDecline = { controller = null },
                    onAccept = { acceptances ->
                        busy = true
                        scope.launch {
                            try {
                                client.acceptMyPolicies(acceptances)
                                gate.load()
                                loadedTick += 1
                                controller = null
                                error = null
                                onAccepted()
                            } catch (e: AtlasException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
        }
    }
}
