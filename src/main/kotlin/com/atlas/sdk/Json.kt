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

/** Serialize a flat string map to a JSON object body — all the SDK ever POSTs. */
internal fun encodeJsonObject(fields: Map<String, String>): String {
    val body = fields.entries.joinToString(",") { (k, v) ->
        "${quote(k)}:${quote(v)}"
    }
    return "{$body}"
}

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
