package net.atlasauth.atlas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/*
 * Policy acceptance (terms of service, privacy, any policy) — the client half.
 *
 * Mirrors `@atlasauth/js` `policy-acceptance`: the wire types with tolerant
 * readers, a small state machine ([PolicyAcceptanceController]) behind every
 * policy UI, and the Frontend API calls (load the policies, accept them in a
 * sign-up or a held sign-in, accept from inside the app).
 *
 * Display modes (a policy's `display`):
 *  - `checkbox`: a checkbox, combined into one sentence when several policies
 *    use it; the policy name opens the document in an in-app viewer;
 *  - `scroll`: an unskippable dialog, accepted only after the end of the text
 *    is seen (or the text fits); declining cancels;
 *  - `link`: a clickwrap sentence under the submit button; submitting accepts.
 *
 * `scrolled_to_end` and `view_ms` are client-attested evidence the server
 * records beside the acceptance; neither is a security boundary.
 */

/** One policy's current version as the Frontend API serves it. */
data class AtlasPolicy(
    val key: String,
    val title: String,
    /** `checkbox`, `scroll` or `link`. */
    val display: String,
    val required: Boolean,
    /** `sign_up` or `sign_in_and_sign_up`. */
    val appliesTo: String,
    val versionId: String,
    val version: String,
    /** Sanitised HTML: what the user sees and what [contentSha256] covers. */
    val contentHtml: String?,
    val contentMarkdown: String?,
    val contentUrl: String?,
    val contentSha256: String,
    /** Epoch milliseconds. */
    val effectiveAt: Long?,
    val summaryOfChanges: String?,
    /** On a held sign-in's pending policy: whether an earlier version was accepted (so "what changed" applies). Null when unknown. */
    val reaccept: Boolean?,
) {
    /** Whether this policy has any content to show. */
    val hasContent: Boolean get() = contentHtml != null || contentMarkdown != null

    companion object {
        /** Read one policy from the wire (flat, or with a nested `version` / `current_version` object). */
        fun fromJson(raw: JsonElement?): AtlasPolicy? {
            val r = raw as? JsonObject ?: return null
            val nested = (r["version"] as? JsonObject) ?: (r["current_version"] as? JsonObject)
            fun pick(name: String): JsonElement? = r[name]?.takeIf { it !is kotlinx.serialization.json.JsonNull } ?: nested?.get(name)
            fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            val key = str(r["key"]) ?: str(r["policy"]) ?: return null
            val versionId = str(pick("version_id")) ?: str(nested?.get("id")) ?: return null
            val display = str(r["display"]).takeIf { it == "checkbox" || it == "scroll" || it == "link" } ?: "checkbox"
            val version = str(r["version"]) ?: str(nested?.get("version")) ?: ""
            return AtlasPolicy(
                key = key,
                title = str(r["title"]) ?: key,
                display = display,
                required = (r["required"] as? JsonPrimitive)?.booleanOrNull != false,
                appliesTo = if (str(r["applies_to"]) == "sign_in_and_sign_up") "sign_in_and_sign_up" else "sign_up",
                versionId = versionId,
                version = version,
                contentHtml = str(pick("content_html")),
                contentMarkdown = str(pick("content_markdown")),
                contentUrl = str(pick("content_url")),
                contentSha256 = str(pick("content_sha256")) ?: "",
                effectiveAt = epochMs(pick("effective_at")),
                summaryOfChanges = str(pick("summary_of_changes")),
                reaccept = (r["reaccept"] as? JsonPrimitive)?.booleanOrNull,
            )
        }

        /** Read a list of policies from any shape the API uses (`[..]`, `{policies}`, `{data}`). */
        fun listFromJson(body: JsonElement?): List<AtlasPolicy> {
            val list = when (body) {
                is JsonArray -> body
                is JsonObject -> (body["policies"] ?: body["data"]) as? JsonArray
                else -> null
            } ?: return emptyList()
            return list.mapNotNull { fromJson(it) }
        }

        private fun epochMs(e: JsonElement?): Long? {
            val p = e as? JsonPrimitive ?: return null
            // The API sends epoch milliseconds; anything else is not a time we can use.
            return if (p.isString) p.content.toLongOrNull() else p.doubleOrNull?.toLong()
        }
    }
}

/** The policies a held attempt is waiting on (`pending_policies`). */
fun pendingPoliciesOf(attempt: FlowAttempt?): List<AtlasPolicy> =
    attempt?.pendingPolicies.orEmpty().mapNotNull { AtlasPolicy.fromJson(it) }

/** The legacy single legal checkbox (send `consent: true` on sign-up), when the instance has one. */
data class AtlasLegacyConsent(val required: Boolean, val termsUrl: String?, val privacyUrl: String?)

/** `GET /v1/client/policies`: the current version of every policy. */
data class AtlasPolicies(val policies: List<AtlasPolicy>, val legacyConsent: AtlasLegacyConsent?) {
    /** Only the policies a sign-in has to show. */
    val signIn: List<AtlasPolicy> get() = policies.filter { it.appliesTo == "sign_in_and_sign_up" }
}

/** A signed-in user's standing on one policy (`GET /v1/client/me/policies`). */
data class AtlasPolicyStanding(
    val key: String,
    val title: String,
    val acceptedVersion: String?,
    val currentVersion: String?,
    val needsReaccept: Boolean,
    /** Epoch milliseconds: sign-in still works until then. */
    val graceEndsAt: Long?,
    /** The next sign-in will stop for this policy. */
    val blocking: Boolean,
) {
    companion object {
        fun listFromJson(body: JsonElement?): List<AtlasPolicyStanding> {
            val list = when (body) {
                is JsonArray -> body
                is JsonObject -> (body["policies"] ?: body["data"]) as? JsonArray
                else -> null
            } ?: return emptyList()
            return list.mapNotNull { item ->
                val r = item as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (r[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                val key = str("key") ?: str("policy") ?: return@mapNotNull null
                AtlasPolicyStanding(
                    key = key,
                    title = str("title") ?: key,
                    acceptedVersion = str("accepted_version"),
                    currentVersion = str("current_version"),
                    needsReaccept = (r["needs_reaccept"] as? JsonPrimitive)?.booleanOrNull == true,
                    graceEndsAt = (r["grace_ends_at"] as? JsonPrimitive)?.doubleOrNull?.toLong(),
                    blocking = (r["blocking"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }
    }
}

/** One entry of `policy_acceptances`. */
data class PolicyAcceptance(
    val policy: String,
    val versionId: String,
    val contentSha256: String,
    val display: String? = null,
    val scrolledToEnd: Boolean? = null,
    val viewMs: Long? = null,
    val locale: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("policy", policy)
        put("version_id", versionId)
        put("content_sha256", contentSha256)
        if (display != null) put("display", display)
        if (scrolledToEnd != null) put("scrolled_to_end", scrolledToEnd)
        if (viewMs != null) put("view_ms", viewMs)
        if (locale != null) put("locale", locale)
    }
}

/** The `policy_acceptances` JSON array. */
fun policyAcceptancesJson(acceptances: List<PolicyAcceptance>): JsonArray =
    JsonArray(acceptances.map { it.toJson() })

/**
 * The `accept_policies` request body: the acceptances plus the proof of the held
 * attempt, the one-time `policy_acceptance_token` (else the attempt's
 * `poll_secret`). The attempt id alone is never enough.
 */
internal fun acceptPoliciesBody(
    acceptances: List<PolicyAcceptance>,
    policyToken: String?,
    pollSecret: String? = null,
): JsonObject = buildJsonObject {
    put("policy_acceptances", policyAcceptancesJson(acceptances))
    if (!policyToken.isNullOrEmpty()) put("policy_acceptance_token", policyToken)
    else if (!pollSecret.isNullOrEmpty()) put("poll_secret", pollSecret)
}

/** Whether the server refused with "accept the policies" (422 `POLICY_ACCEPTANCE_REQUIRED`). */
val AtlasException.isPolicyAcceptanceRequired: Boolean
    get() = code == "POLICY_ACCEPTANCE_REQUIRED" ||
        ((this as? AtlasException.Api)?.errors?.any { it.code == "POLICY_ACCEPTANCE_REQUIRED" } == true)

/** What a redirect, custom-tab or web-auth callback URL carries about an attempt. */
data class AtlasRedirectResult(
    val attemptId: String?,
    val status: String?,
    /** `__atlas_ticket`: a completed attempt's one-time ticket. */
    val ticket: String?,
    /** `__atlas_policy_token`: the proof that goes with `accept_policies` (only with `needs_policy_acceptance`). */
    val policyToken: String?,
) {
    /** Whether the attempt is held until policies are accepted. */
    val needsPolicyAcceptance: Boolean get() = status == "needs_policy_acceptance"

    companion object {
        /** Read the `__atlas_*` parameters of a callback URL (query or fragment); null when it carries none. */
        fun parse(url: String): AtlasRedirectResult? {
            val q = url.substringAfter('?', "").substringBefore('#')
            val f = url.substringAfter('#', "")
            val params = HashMap<String, String>()
            for (part in (f.split('&') + q.split('&'))) {
                if (part.isEmpty()) continue
                val k = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                if (k.startsWith("__atlas_")) {
                    params[k] = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                }
            }
            val attempt = params["__atlas_attempt"]
            val status = params["__atlas_status"]
            val ticket = params["__atlas_ticket"]
            if (attempt == null && status == null && ticket == null) return null
            val token = if (status == "needs_policy_acceptance") params["__atlas_policy_token"] else null
            return AtlasRedirectResult(attempt, status, ticket, token)
        }
    }
}

// ---- Controller -----------------------------------------------------------

/**
 * The state behind every policy UI. Subscribe for changes; read [canSubmit] to
 * enable the submit button and [build] for the request body. State is kept per
 * version id, so a refreshed list (after a 422) keeps the ticks the user already
 * made on versions that did not change.
 *
 * Not thread-safe: use it from the main thread, like the UI that drives it.
 */
class PolicyAcceptanceController(
    policies: List<AtlasPolicy> = emptyList(),
    /** One checkbox for every checkbox-mode policy (default) or one each. */
    val combined: Boolean = true,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(
        var checked: Boolean = false,
        var scrolledToEnd: Boolean = false,
        var viewMs: Long = 0,
        var openedAt: Long? = null,
    )

    private var list: List<AtlasPolicy> = emptyList()
    private val entries = HashMap<String, Entry>()
    private var open: String? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    init {
        setPolicies(policies)
    }

    /** Be told when anything changes. Returns the function that stops listening. */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun emit() {
        for (l in listeners.toList()) l()
    }

    private fun entry(key: String): Entry {
        val id = list.firstOrNull { it.key == key }?.versionId ?: key
        return entries.getOrPut(id) { Entry() }
    }

    val policies: List<AtlasPolicy> get() = list

    /** The document open in the in-app viewer, or null. */
    val viewing: AtlasPolicy? get() = list.firstOrNull { it.key == open }

    /** Replace the policies (e.g. after a refresh); ticks on unchanged versions survive. */
    fun setPolicies(policies: List<AtlasPolicy>) {
        list = policies
        if (open != null && policies.none { it.key == open }) open = null
        emit()
    }

    fun checkboxPolicies(): List<AtlasPolicy> = list.filter { it.display == "checkbox" }
    fun scrollPolicies(): List<AtlasPolicy> = list.filter { it.display == "scroll" }
    fun linkPolicies(): List<AtlasPolicy> = list.filter { it.display == "link" }

    fun isChecked(key: String): Boolean {
        val p = list.firstOrNull { it.key == key } ?: return false
        // A clickwrap sentence is accepted by submitting.
        if (p.display == "link") return true
        return entry(key).checked
    }

    fun setChecked(key: String, checked: Boolean) {
        entry(key).checked = checked
        emit()
    }

    /** Whether every checkbox-mode policy is ticked (the combined box). */
    val combinedChecked: Boolean
        get() = checkboxPolicies().let { boxes -> boxes.isNotEmpty() && boxes.all { isChecked(it.key) } }

    /** Tick or untick every checkbox-mode policy at once (the combined box). */
    fun setAllChecked(checked: Boolean) {
        for (p in checkboxPolicies()) entry(p.key).checked = checked
        emit()
    }

    /** The user accepted a scroll-mode policy in its dialog. */
    fun acceptScroll(key: String) {
        val e = entry(key)
        e.checked = true
        e.scrolledToEnd = true
        emit()
    }

    /** The first scroll-mode policy still to be read and accepted, or null. */
    fun pendingScroll(): AtlasPolicy? = scrollPolicies().firstOrNull { !isChecked(it.key) }

    /** Open a document in the in-app viewer and start its reading clock. */
    fun openDocument(key: String) {
        if (open != null) closeDocument()
        if (list.none { it.key == key }) return
        open = key
        entry(key).openedAt = now()
        emit()
    }

    fun closeDocument() {
        val k = open ?: return
        stopClock(k)
        open = null
        emit()
    }

    /** Start the reading clock for a policy shown some other way (a dialog). */
    fun startClock(key: String) {
        val e = entry(key)
        if (e.openedAt == null) e.openedAt = now()
    }

    fun stopClock(key: String) {
        val e = entry(key)
        val at = e.openedAt ?: return
        e.viewMs += maxOf(0L, now() - at)
        e.openedAt = null
    }

    /** The end of the document was reached while reading [key]. */
    fun markScrolledToEnd(key: String) {
        val e = entry(key)
        if (!e.scrolledToEnd) {
            e.scrolledToEnd = true
            emit()
        }
    }

    fun hasScrolledToEnd(key: String): Boolean = entry(key).scrolledToEnd

    fun viewMs(key: String): Long {
        val e = entry(key)
        val running = e.openedAt?.let { maxOf(0L, now() - it) } ?: 0L
        return e.viewMs + running
    }

    /** The required policies still unaccepted. */
    fun missing(): List<AtlasPolicy> = list.filter { it.required && !isChecked(it.key) }

    /** Whether every required policy is accepted. */
    fun satisfied(): Boolean = missing().isEmpty()

    /** Whether the submit button may be enabled: scroll-mode policies are read on submit, so they do not block it. */
    fun canSubmit(): Boolean = list.all { !it.required || it.display == "scroll" || isChecked(it.key) }

    /** The `policy_acceptances` body: every accepted policy (and every clickwrap one). */
    fun build(locale: String? = null): List<PolicyAcceptance> {
        val out = ArrayList<PolicyAcceptance>()
        for (p in list) {
            if (!isChecked(p.key)) continue
            val e = entry(p.key)
            val ms = viewMs(p.key)
            out.add(
                PolicyAcceptance(
                    policy = p.key,
                    versionId = p.versionId,
                    contentSha256 = p.contentSha256,
                    display = p.display,
                    scrolledToEnd = if (p.display == "scroll" || e.scrolledToEnd) e.scrolledToEnd else null,
                    viewMs = if (ms > 0) ms else null,
                    locale = locale,
                ),
            )
        }
        return out
    }
}

// ---- Client calls -----------------------------------------------------------

private fun localeQuery(locale: String?): String =
    if (locale.isNullOrEmpty()) "" else "?locale=" + java.net.URLEncoder.encode(locale, "UTF-8")

/** `GET /v1/client/policies`: the current version of every policy (publishable key only). */
suspend fun AtlasClient.policies(locale: String? = null): AtlasPolicies {
    val response = requestRaw("GET", "/v1/client/policies${localeQuery(locale)}", null, authenticated = false)
    val root = json.parseToJsonElement(response.body)
    val legacy = ((root as? JsonObject)?.get("legacy_consent") as? JsonObject)?.let {
        AtlasLegacyConsent(
            required = (it["required"] as? JsonPrimitive)?.booleanOrNull == true,
            termsUrl = (it["terms_url"] as? JsonPrimitive)?.contentOrNull,
            privacyUrl = (it["privacy_url"] as? JsonPrimitive)?.contentOrNull,
        )
    }
    return AtlasPolicies(AtlasPolicy.listFromJson(root), legacy)
}

/** `GET /v1/client/me/policies`: the signed-in user's standing on every policy. */
suspend fun AtlasClient.myPolicies(): List<AtlasPolicyStanding> {
    val response = requestRaw("GET", "/v1/client/me/policies", null, authenticated = true)
    return AtlasPolicyStanding.listFromJson(json.parseToJsonElement(response.body))
}

/** `POST /v1/client/me/policies/accept`: accept from inside the app (a banner during a grace window). */
suspend fun AtlasClient.acceptMyPolicies(acceptances: List<PolicyAcceptance>) {
    requestRaw(
        "POST",
        "/v1/client/me/policies/accept",
        buildJsonObject { put("policy_acceptances", policyAcceptancesJson(acceptances)) }.toString(),
        authenticated = true,
    )
}

/**
 * A controller for the policies a sign-up must show: every policy with a published
 * version. Required ones block the submit; optional ones may stay unticked.
 */
suspend fun AtlasClient.signUpPolicyController(
    locale: String? = null,
    combined: Boolean = true,
): PolicyAcceptanceController = PolicyAcceptanceController(policies(locale).policies, combined)

/**
 * A controller for the policies a held attempt is waiting on, with their content
 * filled in from `GET /v1/client/policies` when the attempt only listed them. An
 * attempt resumed from a redirect carries no list: every required policy is shown
 * and the server checks each acceptance.
 */
suspend fun AtlasClient.policyController(
    attempt: FlowAttempt?,
    locale: String? = null,
    combined: Boolean = true,
): PolicyAcceptanceController {
    var pending = pendingPoliciesOf(attempt)
    if (pending.isEmpty()) {
        return PolicyAcceptanceController(policies(locale).policies.filter { it.required }, combined)
    }
    if (pending.any { !it.hasContent }) {
        val all = policies(locale).policies
        pending = pending.map { p ->
            val full = all.firstOrNull { it.key == p.key } ?: return@map p
            full.copy(
                title = p.title.takeIf { it != p.key } ?: full.title,
                display = p.display,
                versionId = p.versionId,
                version = p.version.ifEmpty { full.version },
                contentHtml = p.contentHtml ?: full.contentHtml,
                contentMarkdown = p.contentMarkdown ?: full.contentMarkdown,
                contentSha256 = p.contentSha256.ifEmpty { full.contentSha256 },
                summaryOfChanges = p.summaryOfChanges ?: full.summaryOfChanges,
                reaccept = p.reaccept,
            )
        }
    }
    // A sign-in acceptance is always required of the user, whatever the policy says elsewhere.
    return PolicyAcceptanceController(pending.map { it.copy(required = true) }, combined)
}

/**
 * After the server answered 422 `POLICY_ACCEPTANCE_REQUIRED` (a policy was
 * published or changed while the form was open): reload the policies into the
 * controller so the form re-shows what is needed now. Returns whether [error] was
 * that refusal.
 */
suspend fun AtlasClient.refreshPoliciesOnRequired(
    controller: PolicyAcceptanceController,
    error: AtlasException,
    locale: String? = null,
): Boolean {
    if (!error.isPolicyAcceptanceRequired) return false
    val fresh = policies(locale).policies
    if (fresh.isNotEmpty()) controller.setPolicies(fresh)
    return true
}

/** The acceptances of a plain "I agree" tick for [policies], for apps that draw their own checkbox. */
fun acceptancesFor(policies: List<AtlasPolicy>, locale: String? = null): List<PolicyAcceptance> =
    policies.map {
        PolicyAcceptance(it.key, it.versionId, it.contentSha256, it.display, locale = locale)
    }

/**
 * In-app re-acceptance for a signed-in user: loads the standing, builds a
 * controller for the policies that need accepting, and posts the result.
 */
class PolicyGate(private val client: AtlasClient, private val locale: String? = null) {
    /** The policies that need re-acceptance (empty when none, or before [load]). */
    var pending: List<AtlasPolicyStanding> = emptyList()
        private set
    var loaded: Boolean = false
        private set

    suspend fun load() {
        pending = client.myPolicies().filter { it.needsReaccept }
        loaded = true
    }

    /** The soonest end of grace among the pending policies (epoch ms), or null. */
    val graceEndsAt: Long? get() = pending.mapNotNull { it.graceEndsAt }.minOrNull()

    /** A controller over the pending policies' current versions, all required. */
    suspend fun begin(): PolicyAcceptanceController {
        val wanted = pending.map { it.key }.toSet()
        val all = client.policies(locale).policies.filter { it.key in wanted }
        return PolicyAcceptanceController(all.map { it.copy(required = true) })
    }

    suspend fun accept(controller: PolicyAcceptanceController) {
        client.acceptMyPolicies(controller.build(locale))
        pending = emptyList()
    }
}

/**
 * Resume a sign-in the app was handed mid-stream: an OAuth, SSO or magic-link
 * redirect that came back `needs_policy_acceptance` (parse the callback URL with
 * [AtlasRedirectResult.parse]). The flow is seeded with the attempt id, its status
 * and the policy token the redirect carried, so [SignInFlow.acceptPolicies] can
 * continue it. Show the policies with [policyController].
 */
fun AtlasClient.resumeSignIn(redirect: AtlasRedirectResult): SignInFlow {
    val id = redirect.attemptId
        ?: throw AtlasException.Decoding("The redirect carries no attempt id.")
    return signInFlow().also {
        it.seed(FlowAttempt(id = id, status = redirect.status ?: "needs_policy_acceptance", policyAcceptanceToken = redirect.policyToken))
    }
}

// ---- Strings and small helpers ------------------------------------------------

/**
 * Every string the prebuilt policy UI draws. Override any of them (a translation,
 * different wording) by passing your own instance; `{name}` marks a placeholder.
 */
data class AtlasPolicyStrings(
    val agreeCombined: String = "I agree to the {policies}",
    val agreeOne: String = "I agree to the {policy}",
    val clickwrap: String = "By continuing you agree to the {policies}.",
    val and: String = "and",
    val viewerBack: String = "Back",
    val version: String = "Version {version}",
    val effective: String = "Effective {date}",
    val scrollHint: String = "Scroll to the end to accept",
    val accept: String = "Accept",
    val decline: String = "Decline",
    val summaryTitle: String = "What changed",
    val reacceptTitle: String = "{policy} has been updated",
    val reacceptIntro: String = "Please review and accept the updated terms to continue.",
    val pendingTitle: String = "Accept to continue",
    val pendingIntro: String = "Please accept the following to continue signing in.",
    val required: String = "Please accept the {policy} to continue.",
    val requiredGeneric: String = "Please accept the required policies to continue.",
    val declined: String = "You need to accept the {policy} to use this app.",
    val loading: String = "Loading…",
    val bannerTitle: String = "Updated policies",
    val bannerBody: String = "We have updated: {policies}. Please review and accept.",
    val bannerGrace: String = "Accept by {date} to keep your access.",
    val bannerReview: String = "Review and accept",
    val submit: String = "Accept and continue",
)

/** Fill the `{name}` placeholders of a template. */
fun fillPolicyTemplate(template: String, vararg vars: Pair<String, String>): String {
    var out = template
    for ((k, v) in vars) out = out.replace("{$k}", v)
    return out
}

/** Split a `{name}` template into the text before and after the placeholder (for sentences that embed links). */
fun splitPolicyTemplate(template: String, name: String): Pair<String, String> {
    val at = template.indexOf("{$name}")
    if (at < 0) return template to ""
    return template.substring(0, at) to template.substring(at + name.length + 2)
}

/** Join titles as "A, B and C". */
fun joinPolicyTitles(titles: List<String>, and: String): String = when {
    titles.size <= 1 -> titles.firstOrNull() ?: ""
    else -> titles.dropLast(1).joinToString(", ") + " $and " + titles.last()
}

/** `2026-10-24` for an epoch-millisecond time, or an empty string. */
fun formatPolicyDate(epochMs: Long?): String {
    if (epochMs == null) return ""
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return f.format(java.util.Date(epochMs))
}

/**
 * Whether a vertical scroller has reached its end. [maxValue] is the furthest
 * scroll offset (0 when the content fits, so a short document counts as read).
 */
fun isPolicyScrolledToEnd(value: Int, maxValue: Int, slack: Int = 2): Boolean =
    maxValue != Int.MAX_VALUE && value >= maxValue - slack

/** Like [resumeSignIn], for a social or SSO sign-up that came back `needs_policy_acceptance`. */
fun AtlasClient.resumeSignUp(redirect: AtlasRedirectResult): SignUpFlow {
    val id = redirect.attemptId
        ?: throw AtlasException.Decoding("The redirect carries no attempt id.")
    return signUpFlow().also {
        it.seed(FlowAttempt(id = id, status = redirect.status ?: "needs_policy_acceptance", policyAcceptanceToken = redirect.policyToken))
    }
}
