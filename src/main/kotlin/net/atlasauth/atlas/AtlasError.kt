package net.atlasauth.atlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
