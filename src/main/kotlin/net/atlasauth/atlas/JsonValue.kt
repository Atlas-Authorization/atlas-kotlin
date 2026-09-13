package net.atlasauth.atlas

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A minimal JSON value, so `public_metadata` / `unsafe_metadata` — arbitrary
 * customer-defined shapes — round-trip through kotlinx.serialization. It mirrors
 * the Swift SDK's `JSONValue`: a closed set of cases with a convenience
 * [stringValue] accessor for the common read.
 *
 * Under the hood it delegates to kotlinx's [JsonElement] tree, so the SDK stays
 * dependency-free of any `AnyValue` helper library while still exposing a typed,
 * pattern-matchable value to callers.
 */
@Serializable(with = JsonValueSerializer::class)
sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Obj(val value: Map<String, JsonValue>) : JsonValue()
    data class Arr(val value: List<JsonValue>) : JsonValue()
    object Null : JsonValue()

    /** The underlying string when this value is a string, else null. */
    val stringValue: String?
        get() = (this as? Str)?.value

    /** The underlying number when this value is numeric, else null. */
    val numberValue: Double?
        get() = (this as? Num)?.value

    /** The underlying boolean when this value is a boolean, else null. */
    val boolValue: Boolean?
        get() = (this as? Bool)?.value

    companion object {
        internal fun fromElement(element: JsonElement): JsonValue = when (element) {
            is JsonNull -> Null
            is JsonObject -> Obj(element.mapValues { fromElement(it.value) })
            is JsonArray -> Arr(element.map { fromElement(it) })
            is JsonPrimitive -> {
                val asBool = element.booleanOrNull
                val asNum = element.doubleOrNull
                when {
                    element.isString -> Str(element.content)
                    asBool != null -> Bool(asBool)
                    asNum != null -> Num(asNum)
                    else -> Str(element.content)
                }
            }
        }

        internal fun toElement(value: JsonValue): JsonElement = when (value) {
            is Str -> JsonPrimitive(value.value)
            is Num -> JsonPrimitive(value.value)
            is Bool -> JsonPrimitive(value.value)
            is Obj -> JsonObject(value.value.mapValues { toElement(it.value) })
            is Arr -> JsonArray(value.value.map { toElement(it) })
            Null -> JsonNull
        }
    }
}

/** Bridges [JsonValue] onto kotlinx's JSON element tree. */
object JsonValueSerializer : KSerializer<JsonValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("net.atlasauth.atlas.JsonValue")

    override fun deserialize(decoder: Decoder): JsonValue {
        val input = decoder as? JsonDecoder
            ?: error("JsonValue can only be decoded from JSON.")
        return JsonValue.fromElement(input.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: JsonValue) {
        val output = encoder as? JsonEncoder
            ?: error("JsonValue can only be encoded to JSON.")
        output.encodeJsonElement(JsonValue.toElement(value))
    }
}
