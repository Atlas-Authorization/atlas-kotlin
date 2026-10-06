package com.atlas.sdk

/**
 * A tiny, dependency-free JSON reader/writer.
 *
 * The Android SDK ships with zero third-party runtime dependencies on purpose:
 * no `org.json` (absent from plain-JVM unit tests) and no kotlinx.serialization
 * (a compiler plugin + runtime fetch). This keeps the module buildable and
 * testable with only the Kotlin stdlib, and keeps the FAPI wire shapes readable
 * in one place.
 *
 * It implements the subset of JSON the FAPI actually returns — objects, arrays,
 * strings with escapes, numbers, booleans, null — which is all of JSON in
 * practice. It is not a validating parser; malformed input throws, which the
 * client turns into an `AtlasError.Decoding`.
 */
sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    // ---- typed accessors, null when absent or the wrong type -----------------

    fun obj(key: String): Obj? = (this as? Obj)?.entries?.get(key) as? Obj
    fun arr(key: String): Arr? = (this as? Obj)?.entries?.get(key) as? Arr
    fun string(key: String): String? = ((this as? Obj)?.entries?.get(key) as? Str)?.value
    fun bool(key: String): Boolean? = ((this as? Obj)?.entries?.get(key) as? Bool)?.value
    fun number(key: String): Double? = ((this as? Obj)?.entries?.get(key) as? Num)?.value
    fun long(key: String): Long? = number(key)?.toLong()

    companion object {
        fun parse(text: String): JsonValue = JsonParser(text).parseTop()
    }
}

/** Serialize a flat string map to a JSON object body. */
internal fun encodeJsonObject(fields: Map<String, String>): String {
    val body = fields.entries.joinToString(",") { (k, v) ->
        "${quote(k)}:${quote(v)}"
    }
    return "{$body}"
}

/**
 * Serialize any [JsonValue] tree to a JSON string — the writer the flow driver
 * and the `/me` mutations use for bodies that are not flat string maps (nested
 * `unsafe_metadata`, a `scopes` array, a code list). Still dependency-free: the
 * same hand-rolled path as the reader, so the module keeps its only runtime
 * dependency at kotlinx-coroutines.
 */
internal fun encodeJsonValue(value: JsonValue): String {
    val sb = StringBuilder()
    writeJson(value, sb)
    return sb.toString()
}

private fun writeJson(value: JsonValue, sb: StringBuilder) {
    when (value) {
        is JsonValue.Obj -> {
            sb.append('{')
            var first = true
            for ((k, v) in value.entries) {
                if (!first) sb.append(',')
                first = false
                sb.append(quote(k)).append(':')
                writeJson(v, sb)
            }
            sb.append('}')
        }
        is JsonValue.Arr -> {
            sb.append('[')
            value.items.forEachIndexed { i, item ->
                if (i > 0) sb.append(',')
                writeJson(item, sb)
            }
            sb.append(']')
        }
        is JsonValue.Str -> sb.append(quote(value.value))
        is JsonValue.Num -> {
            val d = value.value
            // Render a whole number without the ".0" the server never sends back.
            if (!d.isInfinite() && !d.isNaN() && d == Math.floor(d) && kotlin.math.abs(d) < 1e15) {
                sb.append(d.toLong().toString())
            } else {
                sb.append(d.toString())
            }
        }
        is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
        JsonValue.Null -> sb.append("null")
    }
}

/**
 * Build a JSON object body from name/value pairs, dropping any pair whose value
 * is null — so an absent optional field is simply omitted (matching the FAPI,
 * which distinguishes "omitted" from an explicit null). The value helpers
 * ([jstr], [jarr], [jbool], [jnum]) return null for an absent input, so an
 * optional argument folds straight into the object.
 */
internal fun jsonBody(vararg pairs: Pair<String, JsonValue?>): String {
    val entries = LinkedHashMap<String, JsonValue>()
    for ((k, v) in pairs) if (v != null) entries[k] = v
    return encodeJsonValue(JsonValue.Obj(entries))
}

internal fun jstr(value: String?): JsonValue? = value?.let { JsonValue.Str(it) }
internal fun jbool(value: Boolean?): JsonValue? = value?.let { JsonValue.Bool(it) }
internal fun jnum(value: Number?): JsonValue? = value?.let { JsonValue.Num(it.toDouble()) }
internal fun jarr(values: List<String>?): JsonValue? =
    values?.let { JsonValue.Arr(it.map { s -> JsonValue.Str(s) }) }

private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

private class JsonParser(private val text: String) {
    private var pos = 0

    fun parseTop(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        return when (val c = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't', 'f' -> parseBool()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw AtlasException(AtlasError.Decoding("Unexpected character '$c' at $pos."))
        }
    }

    private fun parseObject(): JsonValue.Obj {
        expect('{')
        val entries = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { pos++; return JsonValue.Obj(entries) }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            entries[key] = parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                '}' -> break
                else -> throw AtlasException(AtlasError.Decoding("Expected ',' or '}' but got '$c'."))
            }
        }
        return JsonValue.Obj(entries)
    }

    private fun parseArray(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { pos++; return JsonValue.Arr(items) }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                ']' -> break
                else -> throw AtlasException(AtlasError.Decoding("Expected ',' or ']' but got '$c'."))
            }
        }
        return JsonValue.Arr(items)
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            when (val c = next()) {
                '"' -> return sb.toString()
                '\\' -> when (val e = next()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        val hex = text.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> throw AtlasException(AtlasError.Decoding("Bad escape '\\$e'."))
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos] in '0'..'9' || text[pos] in ".eE+-")) pos++
        return JsonValue.Num(text.substring(start, pos).toDouble())
    }

    private fun parseBool(): JsonValue.Bool =
        if (text.startsWith("true", pos)) { pos += 4; JsonValue.Bool(true) }
        else if (text.startsWith("false", pos)) { pos += 5; JsonValue.Bool(false) }
        else throw AtlasException(AtlasError.Decoding("Invalid literal at $pos."))

    private fun parseNull(): JsonValue {
        if (text.startsWith("null", pos)) { pos += 4; return JsonValue.Null }
        throw AtlasException(AtlasError.Decoding("Invalid literal at $pos."))
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char {
        if (pos >= text.length) throw AtlasException(AtlasError.Decoding("Unexpected end of JSON."))
        return text[pos]
    }

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        val actual = next()
        if (actual != c) throw AtlasException(AtlasError.Decoding("Expected '$c' but got '$actual'."))
    }
}
