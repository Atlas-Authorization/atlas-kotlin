package net.atlasauth.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One item of the §9.1 error envelope: `{ errors: [{ code, message, param?, meta? }] }`.
 *
 * The API writes [message] for humans, so it is surfaced verbatim. [param]
 * attaches the message to a field so a form can render it inline rather than
 * dumping everything into a single banner.
 */
@Serializable
data class AtlasErrorItem(
    val code: String,
    val message: String,
    val param: String? = null,
    /** Structured detail some errors carry (e.g. `meta.required_scope` on `INSUFFICIENT_SCOPE`). */
    val meta: JsonObject? = null,
    /** `ROUTE_NOT_FOUND` only: the closest documented paths. */
    @SerialName("did_you_mean") val didYouMean: List<String>? = null,
    /** `ROUTE_NOT_FOUND` only: the methods the path does support. */
    @SerialName("allowed_methods") val allowedMethods: List<String>? = null,
    /** Where the error code is documented (`https://atlasauth.net/errors/<code>`). */
    @SerialName("docs_url") val docsUrl: String? = null,
)

/**
 * Every failure the SDK can surface, kept as one sealed type so a caller has
 * exactly one thing to `catch`.
 *
 * - [Api] is the server's §9.1 envelope with the HTTP status.
 * - [Transport] wraps an OkHttp/network failure.
 * - [Decoding] is a malformed body — a contract drift worth distinguishing from
 *   a network drop.
 * - [NotSignedIn] is raised locally before a request is even attempted, when an
 *   authenticated call has no stored session to present.
 */
sealed class AtlasException(message: String) : Exception(message) {

    /** The server's §9.1 error envelope, with the HTTP status that carried it. */
    class Api(
        val statusCode: Int,
        val errors: List<AtlasErrorItem>,
    ) : AtlasException(errors.firstOrNull()?.message ?: "The request failed (HTTP $statusCode).")

    /** A network / transport failure: the server could not be reached. */
    class Transport(val detail: String) : AtlasException(detail)

    /** A malformed response body — the server contract drifted. */
    class Decoding(val detail: String) : AtlasException(detail)

    /**
     * A local passkey (WebAuthn) ceremony failed or was cancelled by the user —
     * raised by the platform Credential Manager, not by the server. The [detail]
     * carries the Credential Manager's reason (e.g. a user cancellation or
     * "no credential available").
     */
    class Ceremony(val detail: String) : AtlasException(detail)

    /** An authenticated call was made with no stored session. */
    object NotSignedIn : AtlasException("You must be signed in.")

    /**
     * The first server error code, the value most callers branch on
     * (`form_password_incorrect`, `form_identifier_not_found`, …). Null for
     * local / transport failures.
     */
    val code: String?
        get() = (this as? Api)?.errors?.firstOrNull()?.code

    /** The HTTP status for an [Api] error; null for local/transport failures. */
    val status: Int?
        get() = (this as? Api)?.statusCode

    /** The first server error code as a typed [AtlasErrorCode], when the SDK knows it. */
    val knownCode: AtlasErrorCode?
        get() = AtlasErrorCode.fromWire(code)

    /**
     * The app cannot send email (422 `EMAIL_DELIVERY_NOT_CONFIGURED`); nothing was
     * sent. [message] is the server's end-user wording — show it as-is and offer
     * another sign-in method.
     */
    val isEmailDeliveryNotConfigured: Boolean
        get() = knownCode == AtlasErrorCode.EMAIL_DELIVERY_NOT_CONFIGURED

    /**
     * The step needs a captcha (`CAPTCHA_REQUIRED`) or the one sent was refused
     * (`CAPTCHA_FAILED`): run the captcha and retry with its token.
     */
    val isCaptchaRequired: Boolean
        get() = knownCode == AtlasErrorCode.CAPTCHA_REQUIRED || knownCode == AtlasErrorCode.CAPTCHA_FAILED

    /**
     * 403 `INSUFFICIENT_SCOPE`: the key or token lacks a scope. The older
     * `SCOPE_MISSING` spelling is still tolerated. [requiredScope] names the scope.
     */
    val isInsufficientScope: Boolean
        get() = knownCode == AtlasErrorCode.INSUFFICIENT_SCOPE || knownCode == AtlasErrorCode.SCOPE_MISSING

    /** The scope an `INSUFFICIENT_SCOPE` refusal asks for (`meta.required_scope`); null otherwise. */
    val requiredScope: String?
        get() = (this as? Api)?.errors?.firstOrNull { it.meta?.get("required_scope") != null }
            ?.meta?.get("required_scope")?.jsonPrimitive?.contentOrNull

    /** 403 `STEP_UP_REQUIRED`: a recent re-authentication (MFA) is needed; run it and retry. */
    val isStepUpRequired: Boolean
        get() = knownCode == AtlasErrorCode.STEP_UP_REQUIRED

    /** 403 `ORIGIN_NOT_ALLOWED`: the request came from an origin the key or app does not allow. */
    val isOriginNotAllowed: Boolean
        get() = knownCode == AtlasErrorCode.ORIGIN_NOT_ALLOWED

    /** 404 `ROUTE_NOT_FOUND`: no such method + path (a missing object is `NOT_FOUND`). */
    val isRouteNotFound: Boolean
        get() = knownCode == AtlasErrorCode.ROUTE_NOT_FOUND

    /** `ROUTE_NOT_FOUND`: the closest documented paths; empty for any other error. */
    val didYouMean: List<String>
        get() = (this as? Api)?.errors?.firstOrNull { it.code == AtlasErrorCode.ROUTE_NOT_FOUND.wire }
            ?.didYouMean.orEmpty()

    /** `ROUTE_NOT_FOUND`: the methods the path does support; empty for any other error. */
    val allowedMethods: List<String>
        get() = (this as? Api)?.errors?.firstOrNull { it.code == AtlasErrorCode.ROUTE_NOT_FOUND.wire }
            ?.allowedMethods.orEmpty()
}

/**
 * Server error codes the SDK names. [wire] is the code on the wire;
 * [AtlasException.code] still carries every code, known or not.
 */
enum class AtlasErrorCode(val wire: String) {
    /**
     * 422 — a flow would send an email (code, link, reset, verification) but the
     * app has no way to deliver it. Refused before anything is issued, so a form
     * never says "we sent you a code" when nothing will arrive.
     */
    EMAIL_DELIVERY_NOT_CONFIGURED("EMAIL_DELIVERY_NOT_CONFIGURED"),
    VALIDATION_FAILED("VALIDATION_FAILED"),
    RATE_LIMITED("RATE_LIMITED"),
    IDENTIFIER_NOT_FOUND("IDENTIFIER_NOT_FOUND"),
    IDENTIFIER_EXISTS("IDENTIFIER_EXISTS"),
    VERIFICATION_EXPIRED("VERIFICATION_EXPIRED"),
    VERIFICATION_FAILED("VERIFICATION_FAILED"),
    EMAIL_NOT_VERIFIED("EMAIL_NOT_VERIFIED"),
    TOO_MANY_ATTEMPTS("TOO_MANY_ATTEMPTS"),
    PASSWORD_BREACHED("PASSWORD_BREACHED"),
    PASSWORD_POLICY("PASSWORD_POLICY"),
    LOCKED("LOCKED"),

    /**
     * 400 — the instance challenges this step and no `captcha_token` was sent (e.g.
     * a passkey sign-in begin). Run the captcha and retry with its token.
     */
    CAPTCHA_REQUIRED("CAPTCHA_REQUIRED"),

    /** 400 — the `captcha_token` sent was refused. */
    CAPTCHA_FAILED("CAPTCHA_FAILED"),

    /** 403 — the step needs a challenge but no captcha provider is configured. */
    CHALLENGE_UNAVAILABLE("CHALLENGE_UNAVAILABLE"),

    /** 403 — the key or token lacks a scope; `meta.required_scope` names it. */
    INSUFFICIENT_SCOPE("INSUFFICIENT_SCOPE"),

    /** The pre-2026-10 spelling of [INSUFFICIENT_SCOPE]; still tolerated. */
    SCOPE_MISSING("SCOPE_MISSING"),

    /** 403 — the request origin is not one the key or app allows. */
    ORIGIN_NOT_ALLOWED("ORIGIN_NOT_ALLOWED"),

    /** 403 — a recent MFA / re-authentication is required first. */
    STEP_UP_REQUIRED("STEP_UP_REQUIRED"),

    /** 404 — no such method + path; carries `did_you_mean` / `allowed_methods`. */
    ROUTE_NOT_FOUND("ROUTE_NOT_FOUND"),
    ;

    companion object {
        /** The known code for a wire value, or null. */
        fun fromWire(code: String?): AtlasErrorCode? = values().firstOrNull { it.wire == code }
    }
}

@Serializable
private data class ErrorEnvelope(val errors: List<AtlasErrorItem> = emptyList())

/**
 * Decode the §9.1 envelope from a non-2xx body. Falls back to a synthetic item
 * when the body is not the expected shape (a proxy error page, an empty 500), so
 * a caller always gets a code to branch on rather than a decode crash.
 */
internal fun parseErrorEnvelope(json: Json, status: Int, body: String): AtlasException.Api {
    val parsed = try {
        json.decodeFromString(ErrorEnvelope.serializer(), body)
    } catch (_: Throwable) {
        null
    }
    if (parsed != null && parsed.errors.isNotEmpty()) {
        return AtlasException.Api(status, parsed.errors)
    }
    return AtlasException.Api(
        status,
        listOf(AtlasErrorItem(code = "unexpected", message = "The request failed (HTTP $status).")),
    )
}
