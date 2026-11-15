package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Billing over the Frontend API (`/v1/client/billing/*`): the instance's plans,
 * the signed-in subject's subscription, invoices and card, and the hosted
 * Checkout / Billing Portal URLs the provider issues. There is no in-app card
 * form: card entry stays on the provider's hosted page. Pass `orgId` to act on an
 * organization the user administers instead of on the user.
 */

/** A display-only promotion on a plan. */
@Serializable
data class BillingPromotion(
    val label: String = "",
    @SerialName("percent_off") val percentOff: Double? = null,
    @SerialName("amount_off") val amountOff: Double? = null,
    @SerialName("ends_at") val endsAt: Double? = null,
    val code: String? = null,
)

/** How a plan is priced for display ([BillingPlan.priceView]). */
data class BillingPriceView(
    val price: String,
    val originalPrice: String?,
    /** `month` or `year`. */
    val interval: String,
    val free: Boolean,
    val trialDays: Int?,
    val promotionLabel: String?,
    val promotionCode: String?,
    /** Minor units after any promotion. */
    val amount: Long,
)

/** A plan the instance sells. Amounts are minor units (cents). */
@Serializable
data class BillingPlan(
    val id: String,
    val name: String = "",
    val slug: String = "",
    /** `month` or `year`. */
    val interval: String = "month",
    private val amount: JsonPrimitive? = null,
    val currency: String = "usd",
    val features: List<String> = emptyList(),
    /** `user` or `org`. */
    val audience: String = "user",
    val provider: String? = null,
    /** `base` (the subject's main plan) or `addon`. */
    val kind: String? = null,
    @SerialName("trial_days") val trialDays: Int? = null,
    @SerialName("annual_available") val annualAvailable: Boolean = false,
    @SerialName("annual_amount") private val annualAmountRaw: JsonPrimitive? = null,
    val promotion: BillingPromotion? = null,
    val description: String? = null,
    val badge: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
) {
    val amountMinor: Double get() = amount?.doubleOrNull ?: 0.0
    val annualAmountMinor: Double? get() = annualAmountRaw?.doubleOrNull

    /**
     * The price, trial and promotion to show at [wantedInterval] (default the plan's
     * own). A yearly interval uses the annual amount when the plan offers one; an
     * expired promotion is ignored. Mirrors the web `planPriceView`.
     */
    fun priceView(
        wantedInterval: String? = null,
        locale: Locale = Locale.getDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): BillingPriceView {
        val own = if (interval == "year") "year" else "month"
        val chosen = wantedInterval ?: own
        val annual = if (annualAvailable) annualAmountMinor else null
        val base = when {
            chosen == own -> amountMinor
            chosen == "year" && annual != null -> annual
            else -> amountMinor
        }
        val shown = if (chosen == own || (chosen == "year" && annual != null)) chosen else own
        val promo = promotion?.takeUnless { it.endsAt != null && it.endsAt <= nowMillis }
        var final = base
        if (promo?.percentOff != null && promo.percentOff != 0.0) {
            final = (base * (1 - promo.percentOff / 100)).roundToLong().toDouble()
        } else if (promo?.amountOff != null && promo.amountOff != 0.0) {
            final = max(0.0, base - promo.amountOff)
        }
        val discounted = promo != null && final < base
        return BillingPriceView(
            price = formatMoney(final, currency, locale),
            originalPrice = if (discounted) formatMoney(base, currency, locale) else null,
            interval = shown,
            free = final == 0.0 && base == 0.0,
            trialDays = trialDays?.takeIf { it > 0 },
            promotionLabel = promo?.label,
            promotionCode = promo?.code,
            amount = final.roundToLong(),
        )
    }

    companion object {
        /** A minor-unit amount as a locale-formatted currency string (`12.34 USD` for an unknown code). */
        fun formatMoney(minor: Double, currency: String, locale: Locale = Locale.getDefault()): String =
            try {
                NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(currency.uppercase()) }.format(minor / 100)
            } catch (_: IllegalArgumentException) {
                "%.2f %s".format(Locale.ROOT, minor / 100, currency.uppercase())
            }
    }
}

/** A subject's subscription. [status] is null when the subject has never subscribed. */
@Serializable
data class BillingSubscription(
    val id: String? = null,
    @SerialName("subject_type") val subjectType: String = "user",
    val status: String? = null,
    val plan: BillingPlan? = null,
    @SerialName("current_period_end") val currentPeriodEnd: Double? = null,
    @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
    val interval: String? = null,
    @SerialName("scheduled_plan_id") val scheduledPlanId: String? = null,
    @SerialName("trial_end") val trialEnd: Double? = null,
    @SerialName("trial_eligible") val trialEligible: Boolean = false,
    /** Every subscription the subject holds (base plan and add-ons). */
    val subscriptions: List<BillingSubscription> = emptyList(),
) {
    val isLive: Boolean get() = status in LIVE_STATUSES

    /** Every subscription of the read: [subscriptions], or the one it reports. */
    val all: List<BillingSubscription>
        get() = subscriptions.ifEmpty { if (status != null) listOf(this) else emptyList() }

    /** The live add-on subscriptions. */
    val liveAddons: List<BillingSubscription>
        get() = all.filter { it.plan?.kind == "addon" && it.isLive }

    companion object {
        /** Statuses in which a subscription is still running. */
        val LIVE_STATUSES = listOf("active", "trialing", "past_due")
    }
}

/** A stored invoice (billing history). */
@Serializable
data class BillingInvoice(
    val id: String,
    val number: String? = null,
    val status: String = "",
    @SerialName("amount_due") val amountDue: Double = 0.0,
    @SerialName("amount_paid") val amountPaid: Double = 0.0,
    val currency: String = "usd",
    @SerialName("hosted_invoice_url") val hostedInvoiceUrl: String? = null,
    @SerialName("invoice_pdf") val invoicePdf: String? = null,
    @SerialName("created_at") val createdAt: Double = 0.0,
)

/** The subject's default card. */
@Serializable
data class BillingPaymentMethod(
    val brand: String = "",
    val last4: String = "",
    @SerialName("exp_month") val expMonth: Int = 0,
    @SerialName("exp_year") val expYear: Int = 0,
)

/** A live feature check. For UI only: enforce on your server or from the token. */
@Serializable
data class BillingFeatureCheck(
    val feature: String = "",
    val allowed: Boolean = false,
    val source: String? = null,
    val remaining: Double? = null,
    val granted: Double? = null,
    val usage: Double? = null,
    val unlimited: Boolean = false,
)

@Serializable
data class BillingCouponDiscount(
    @SerialName("percent_off") val percentOff: Double? = null,
    @SerialName("amount_off") val amountOff: Double? = null,
    val currency: String? = null,
    val duration: String? = null,
    @SerialName("duration_months") val durationMonths: Int? = null,
)

/** The result of checking a discount code. */
@Serializable
data class BillingCouponCheck(
    val valid: Boolean = false,
    /** Why it is not valid (`invalid`, ...). */
    val reason: String? = null,
    val discount: BillingCouponDiscount? = null,
)

@Serializable
private data class BillingUrlResponse(val url: String = "")

@Serializable
private data class BillingPaymentMethodResponse(@SerialName("payment_method") val paymentMethod: BillingPaymentMethod? = null)

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun billingPath(path: String, vararg query: Pair<String, String?>): String {
    val q = query.filter { it.second != null }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }
    return "/v1/client/billing/$path" + if (q.isEmpty()) "" else "?$q"
}

private fun subscriptionPath(action: String, subscriptionId: String?): String =
    if (subscriptionId == null) "/v1/client/billing/subscription/$action"
    else "/v1/client/billing/subscriptions/${enc(subscriptionId)}/$action"

/** The plans the instance sells, in display order. */
suspend fun AtlasClient.billingPlans(): List<BillingPlan> =
    decodeOrThrow(requestRaw("GET", "/v1/client/billing/plans", null, authenticated = true).body, ListResponse.serializer(BillingPlan.serializer()))
        .data.sortedBy { it.sortOrder ?: 0 }

/** The subject's subscription (and add-ons); [orgId] reads an organization's. */
suspend fun AtlasClient.billingSubscription(orgId: String? = null): BillingSubscription =
    decodeOrThrow(requestRaw("GET", billingPath("subscription", "org_id" to orgId), null, authenticated = true).body, BillingSubscription.serializer())

/** The subject's invoices, newest first. */
suspend fun AtlasClient.billingInvoices(orgId: String? = null): List<BillingInvoice> =
    decodeOrThrow(requestRaw("GET", billingPath("invoices", "org_id" to orgId), null, authenticated = true).body, ListResponse.serializer(BillingInvoice.serializer())).data

/** The subject's default card, or null when there is none. */
suspend fun AtlasClient.billingPaymentMethod(orgId: String? = null): BillingPaymentMethod? =
    decodeOrThrow(requestRaw("GET", billingPath("payment_method", "org_id" to orgId), null, authenticated = true).body, BillingPaymentMethodResponse.serializer()).paymentMethod

/**
 * Start a provider Checkout and return its hosted URL; open it in a Custom Tab.
 * [successUrl] / [cancelUrl] are where the provider returns, usually your app link.
 */
suspend fun AtlasClient.billingCheckout(
    planId: String,
    successUrl: String,
    cancelUrl: String,
    orgId: String? = null,
    interval: String? = null,
    couponCode: String? = null,
): String {
    val body = buildJsonObject {
        put("plan_id", planId)
        put("success_url", successUrl)
        put("cancel_url", cancelUrl)
        if (orgId != null) put("org_id", orgId)
        if (interval != null) put("interval", interval)
        if (!couponCode.isNullOrEmpty()) put("coupon_code", couponCode)
    }.toString()
    return billingUrl(requestRaw("POST", "/v1/client/billing/checkout", body, authenticated = true).body)
}

/** Open the provider's Billing Portal (card, invoices, cancel) and return its hosted URL. */
suspend fun AtlasClient.billingPortal(returnUrl: String, orgId: String? = null, subscriptionId: String? = null): String {
    val body = buildJsonObject {
        put("return_url", returnUrl)
        if (orgId != null) put("org_id", orgId)
        if (subscriptionId != null) put("subscription_id", subscriptionId)
    }.toString()
    return billingUrl(requestRaw("POST", "/v1/client/billing/portal", body, authenticated = true).body)
}

private fun AtlasClient.billingUrl(body: String): String =
    decodeOrThrow(body, BillingUrlResponse.serializer()).url.ifEmpty { throw AtlasException.Decoding("The server returned no checkout URL.") }

/** Switch a live subscription to another plan or interval, with no checkout. */
suspend fun AtlasClient.billingChangePlan(
    planId: String,
    interval: String? = null,
    atPeriodEnd: Boolean = false,
    orgId: String? = null,
    subscriptionId: String? = null,
): BillingSubscription {
    val body = buildJsonObject {
        put("plan_id", planId)
        put("schedule_at_period_end", atPeriodEnd)
        if (orgId != null) put("org_id", orgId)
        if (interval != null) put("interval", interval)
    }.toString()
    return decodeOrThrow(requestRaw("POST", subscriptionPath("change", subscriptionId), body, authenticated = true).body, BillingSubscription.serializer())
}

/** Cancel at the end of the period, or now with [immediately]. */
suspend fun AtlasClient.billingCancel(immediately: Boolean = false, orgId: String? = null, subscriptionId: String? = null): BillingSubscription {
    val body = buildJsonObject {
        put("immediately", immediately)
        if (orgId != null) put("org_id", orgId)
    }.toString()
    return decodeOrThrow(requestRaw("POST", subscriptionPath("cancel", subscriptionId), body, authenticated = true).body, BillingSubscription.serializer())
}

/** Undo a cancellation scheduled for the end of the period. */
suspend fun AtlasClient.billingRestore(orgId: String? = null, subscriptionId: String? = null): BillingSubscription {
    val body = buildJsonObject { if (orgId != null) put("org_id", orgId) }.toString()
    return decodeOrThrow(requestRaw("POST", subscriptionPath("restore", subscriptionId), body, authenticated = true).body, BillingSubscription.serializer())
}

/** May the subject use [feature] (needing [required] units)? For UI only. */
suspend fun AtlasClient.billingCheck(feature: String, required: Int? = null, orgId: String? = null): BillingFeatureCheck =
    decodeOrThrow(
        requestRaw("GET", billingPath("check", "feature" to feature, "required" to required?.toString(), "org_id" to orgId), null, authenticated = true).body,
        BillingFeatureCheck.serializer(),
    )

/** Would this discount code work for this plan? For a checkout form's apply button. */
suspend fun AtlasClient.billingValidateCoupon(planId: String, code: String, orgId: String? = null): BillingCouponCheck {
    val body = buildJsonObject {
        put("code", code.trim())
        put("plan_id", planId)
        if (orgId != null) put("org_id", orgId)
    }.toString()
    return decodeOrThrow(requestRaw("POST", "/v1/client/billing/coupons/validate", body, authenticated = true).body, BillingCouponCheck.serializer())
}
