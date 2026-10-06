package com.atlas.sdk

/**
 * One item of the §9.1 error envelope: `{ errors: [{ code, message, param?, meta? }] }`.
 * `message` is written for humans by the API and surfaced verbatim; `param`
 * attaches it to a field for inline form rendering.
 */
data class AtlasErrorItem(
    val code: String,
    val message: String,
    val param: String? = null,
)

/**
 * Every failure the SDK surfaces, as one sealed type so a caller has exactly one
 * thing to catch (wrapped in [AtlasException] where a throwable is needed).
 *
 * - [Api] — the server's §9.1 envelope with the HTTP status.
 * - [Transport] — a network/IO failure.
 * - [Decoding] — a malformed body; contract drift worth distinguishing.
 * - [NotSignedIn] — raised locally when an authenticated call has no session.
 */
sealed class AtlasError {
    data class Api(val httpStatus: Int, val errors: List<AtlasErrorItem>) : AtlasError()
    data class Transport(val detail: String) : AtlasError()
    data class Decoding(val detail: String) : AtlasError()
    data object NotSignedIn : AtlasError()

    /** The first server error code — the value most callers branch on. */
    val code: String?
        get() = (this as? Api)?.errors?.firstOrNull()?.code

    /** HTTP status for an [Api] error; null otherwise. */
    val status: Int?
        get() = (this as? Api)?.httpStatus

    /** A human-readable message, always non-null so it can go straight to UI. */
    val message: String
        get() = when (this) {
            is Api -> errors.firstOrNull()?.message ?: "The request failed (HTTP $status)."
            is Transport -> detail
            is Decoding -> detail
            NotSignedIn -> "You must be signed in."
        }

    companion object {
        /**
         * Decode the envelope from a non-2xx body. Falls back to a synthetic item
         * when the body is not the expected shape (a proxy page, an empty 500), so
         * a caller always gets a code to branch on rather than a parse crash.
         */
        fun fromResponse(status: Int, body: String): Api {
            val items = try {
                val root = JsonValue.parse(body)
                (root.arr("errors")?.items ?: emptyList()).mapNotNull { item ->
                    val code = item.string("code")
                    val message = item.string("message")
                    if (code != null && message != null) {
                        AtlasErrorItem(code, message, item.string("param"))
                    } else null
                }
            } catch (_: Exception) {
                emptyList()
            }
            return if (items.isNotEmpty()) {
                Api(status, items)
            } else {
                Api(status, listOf(AtlasErrorItem("unexpected", "The request failed (HTTP $status).")))
            }
        }
    }
}

/** The throwable carrier for an [AtlasError]. */
class AtlasException(val error: AtlasError) : Exception(error.message)
