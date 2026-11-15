package net.atlasauth.atlas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The non-UI state behind the account-management screens (organization switcher,
 * billing, API keys, device approval). Each holder exposes a [StateFlow] a Compose
 * screen collects and `suspend` actions; they are unit-testable without Compose.
 */

private fun messageOf(e: Throwable): String = (e as? AtlasException)?.message ?: e.message ?: "Something went wrong."

/** `Acme Corp!` becomes `acme-corp`: letters and digits only, dashes between. */
fun organizationSlug(name: String): String {
    val out = StringBuilder()
    for (ch in name.lowercase()) {
        if (ch in 'a'..'z' || ch in '0'..'9') out.append(ch) else if (out.isNotEmpty() && out.last() != '-') out.append('-')
    }
    return out.toString().trim('-')
}

/* ── organization switcher ──────────────────────────────────────────────────── */

data class OrganizationSwitcherUiState(
    val memberships: List<OrganizationMembership> = emptyList(),
    /** The active organization id; null = the personal account. */
    val activeOrganizationId: String? = null,
    val isLoaded: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null,
)

/** Lists the user's organizations, switches the active one and creates a new one. */
class OrganizationSwitcherState(private val client: AtlasClient) {
    private val _state = MutableStateFlow(OrganizationSwitcherUiState())
    val state: StateFlow<OrganizationSwitcherUiState> = _state.asStateFlow()

    suspend fun load() {
        try {
            val list = client.listOrganizationMemberships()
            _state.value = _state.value.copy(memberships = list, activeOrganizationId = client.activeOrganizationId(), message = null, isLoaded = true)
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e), isLoaded = true)
        }
    }

    /** Switch the active organization; null selects the personal account. */
    suspend fun select(organizationId: String?) {
        _state.value = _state.value.copy(isBusy = true)
        try {
            client.setActiveOrganization(organizationId)
            _state.value = _state.value.copy(activeOrganizationId = organizationId, message = null, isBusy = false)
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e), isBusy = false)
        }
    }

    /** Create an organization and make it active. True on success. */
    suspend fun create(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        _state.value = _state.value.copy(isBusy = true)
        return try {
            val org = client.createOrganization(trimmed, organizationSlug(trimmed))
            client.setActiveOrganization(org.id)
            _state.value = _state.value.copy(
                memberships = client.listOrganizationMemberships(), activeOrganizationId = org.id, message = null, isBusy = false,
            )
            true
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e), isBusy = false)
            false
        }
    }
}

/* ── billing ────────────────────────────────────────────────────────────────── */

data class BillingUiState(
    val plans: List<BillingPlan> = emptyList(),
    val subscription: BillingSubscription? = null,
    val invoices: List<BillingInvoice> = emptyList(),
    val paymentMethod: BillingPaymentMethod? = null,
    val isLoaded: Boolean = false,
    val busyPlanId: String? = null,
    val message: String? = null,
)

/**
 * Plans, the subject's subscription, invoices and card. Checkout and the Billing
 * Portal return the provider's hosted URL for you to open (a Custom Tab).
 *
 * @param orgId bill an organization the user administers instead of the user.
 * @param returnUrl where the provider sends the user back (your app link).
 */
class BillingState(private val client: AtlasClient, val orgId: String? = null, val returnUrl: String) {
    private val _state = MutableStateFlow(BillingUiState())
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    suspend fun load() {
        try {
            val plans = client.billingPlans()
            val sub = runCatching { client.billingSubscription(orgId) }.getOrNull()
            val invoices = runCatching { client.billingInvoices(orgId) }.getOrDefault(emptyList())
            val card = runCatching { client.billingPaymentMethod(orgId) }.getOrNull()
            _state.value = BillingUiState(plans, sub, invoices, card, isLoaded = true)
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e), isLoaded = true)
        }
    }

    /**
     * Buy a plan, or switch in place when a base subscription is already live.
     * Returns the hosted Checkout URL to open, or null when the plan was switched.
     */
    suspend fun subscribe(plan: BillingPlan, interval: String?): String? {
        _state.value = _state.value.copy(busyPlanId = plan.id)
        return try {
            val current = _state.value.subscription
            if (plan.kind != "addon" && current?.isLive == true && current.plan != null && current.plan.kind != "addon") {
                val updated = client.billingChangePlan(plan.id, interval, orgId = orgId)
                _state.value = _state.value.copy(subscription = updated, message = null, busyPlanId = null)
                null
            } else {
                val url = client.billingCheckout(plan.id, returnUrl, returnUrl, orgId, if (plan.annualAvailable) interval else null)
                _state.value = _state.value.copy(message = null, busyPlanId = null)
                url
            }
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e), busyPlanId = null)
            null
        }
    }

    suspend fun portalUrl(subscriptionId: String? = null): String? = try {
        client.billingPortal(returnUrl, orgId, subscriptionId).also { _state.value = _state.value.copy(message = null) }
    } catch (e: AtlasException) {
        _state.value = _state.value.copy(message = messageOf(e))
        null
    }

    suspend fun cancel() = mutate { client.billingCancel(orgId = orgId) }
    suspend fun restore() = mutate { client.billingRestore(orgId = orgId) }

    private suspend fun mutate(block: suspend () -> BillingSubscription) {
        try {
            _state.value = _state.value.copy(subscription = block(), message = null)
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e))
        }
    }
}

/* ── API keys ───────────────────────────────────────────────────────────────── */

enum class ApiKeysStatus { LOADING, READY, DISABLED, FAILED }

data class ApiKeysUiState(
    val status: ApiKeysStatus = ApiKeysStatus.LOADING,
    val list: UserApiKeyList = UserApiKeyList(),
    val message: String? = null,
    /** True when the last create needs a fresh re-authentication ([ApiKeysState.completeStepUp]). */
    val needsStepUp: Boolean = false,
    /** The one-time secret of the key just created; call [ApiKeysState.dismissSecret] once copied. */
    val createdSecret: String? = null,
)

/** List, create, rename and revoke the user's (or an organization's) API keys, with the step-up wired in. */
class ApiKeysState(private val client: AtlasClient, val organizationId: String? = null) {
    private val _state = MutableStateFlow(ApiKeysUiState())
    val state: StateFlow<ApiKeysUiState> = _state.asStateFlow()
    private var parked: Triple<String?, Long?, List<String>?>? = null

    suspend fun load() {
        try {
            _state.value = _state.value.copy(list = client.listApiKeys(organizationId), status = ApiKeysStatus.READY, message = null)
        } catch (e: AtlasException) {
            val disabled = (e as? AtlasException.Api)?.statusCode == 403
            _state.value = _state.value.copy(status = if (disabled) ApiKeysStatus.DISABLED else ApiKeysStatus.FAILED, message = messageOf(e))
        }
    }

    suspend fun create(name: String?, expiresInDays: Int?, scopes: List<String>?) {
        attemptCreate(name?.trim()?.ifEmpty { null }, expiresInDays?.let { it * 86_400L }, scopes?.ifEmpty { null })
    }

    private suspend fun attemptCreate(name: String?, expiresInSeconds: Long?, scopes: List<String>?) {
        try {
            val created = client.createApiKey(name, expiresInSeconds, scopes = scopes, organizationId = organizationId)
            parked = null
            _state.value = _state.value.copy(createdSecret = created.secret, needsStepUp = false, message = null)
            load()
        } catch (e: AtlasException) {
            if (e.isStepUpRequired) {
                parked = Triple(name, expiresInSeconds, scopes)
                _state.value = _state.value.copy(needsStepUp = true)
            } else {
                _state.value = _state.value.copy(message = messageOf(e))
            }
        }
    }

    /** Re-authenticate with the password, then finish the parked create. */
    suspend fun completeStepUp(password: String) {
        try {
            client.stepUp("password", password = password)
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e))
            return
        }
        parked?.let { attemptCreate(it.first, it.second, it.third) }
    }

    suspend fun rename(key: UserApiKey, name: String) {
        try { client.renameApiKey(key.id, name.ifEmpty { null }); load() } catch (e: AtlasException) { _state.value = _state.value.copy(message = messageOf(e)) }
    }

    suspend fun revoke(key: UserApiKey) {
        try { client.revokeApiKey(key.id); load() } catch (e: AtlasException) { _state.value = _state.value.copy(message = messageOf(e)) }
    }

    fun dismissSecret() { _state.value = _state.value.copy(createdSecret = null) }
    fun cancelStepUp() { parked = null; _state.value = _state.value.copy(needsStepUp = false) }
}

/* ── device approval ────────────────────────────────────────────────────────── */

sealed interface DeviceApprovalPhase {
    object Entering : DeviceApprovalPhase
    data class Pending(val authorization: DeviceAuthorization) : DeviceApprovalPhase
    object Approved : DeviceApprovalPhase
    object Denied : DeviceApprovalPhase
}

data class DeviceApprovalUiState(
    val phase: DeviceApprovalPhase = DeviceApprovalPhase.Entering,
    val message: String? = null,
    val needsStepUp: Boolean = false,
    val isBusy: Boolean = false,
)

/**
 * Approve (or deny) the code a TV, console or CLI shows. A QR code that carries
 * `?user_code=` can be handed to [lookup] through [userCodeFromUrl].
 */
class DeviceApprovalState(private val client: AtlasClient) {
    private val _state = MutableStateFlow(DeviceApprovalUiState())
    val state: StateFlow<DeviceApprovalUiState> = _state.asStateFlow()

    suspend fun lookup(input: String) {
        _state.value = _state.value.copy(isBusy = true)
        _state.value = try {
            DeviceApprovalUiState(phase = DeviceApprovalPhase.Pending(client.lookupDeviceAuthorization(input)))
        } catch (e: AtlasException) {
            _state.value.copy(message = "That code is not valid. It may have expired or already been used.", isBusy = false)
        }
    }

    suspend fun decide(approve: Boolean) {
        val pending = _state.value.phase as? DeviceApprovalPhase.Pending ?: return
        _state.value = _state.value.copy(isBusy = true)
        _state.value = try {
            if (approve) client.approveDeviceAuthorization(pending.authorization) else client.denyDeviceAuthorization(pending.authorization)
            DeviceApprovalUiState(phase = if (approve) DeviceApprovalPhase.Approved else DeviceApprovalPhase.Denied)
        } catch (e: AtlasException) {
            if (e.isStepUpRequired) _state.value.copy(needsStepUp = true, isBusy = false)
            else _state.value.copy(message = messageOf(e), isBusy = false)
        }
    }

    suspend fun completeStepUp(password: String) {
        try {
            client.stepUp("password", password = password)
        } catch (e: AtlasException) {
            _state.value = _state.value.copy(message = messageOf(e))
            return
        }
        decide(true)
    }

    fun reset() { _state.value = DeviceApprovalUiState() }
}
