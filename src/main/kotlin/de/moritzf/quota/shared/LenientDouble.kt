package de.moritzf.quota.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads a JSON number whether the provider sent an int, a float/double, or a numeric string.
 * Non-numeric values are absent rather than a parse failure.
 *
 * JSON booleans stay absent. Providers use them as flags, not amounts — for example
 * `individual_limit: true` is a placeholder, not 1.0. `toDoubleOrNull("true")` failing is
 * incidental; the type check is the contract.
 */
internal fun JsonElement.lenientDoubleOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.booleanOrNull != null) return null
    val value =
        primitive.doubleOrNull
            ?: primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            ?: return null
    return value.takeIf { it.isFinite() }
}

internal object LenientDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val jsonDecoder =
            decoder as? JsonDecoder ?: error("LenientDoubleSerializer requires JsonDecoder")
        val element = jsonDecoder.decodeJsonElement()
        return element.lenientDoubleOrNull()
            ?: throw SerializationException("Expected a number, got ${element.toString().take(64)}")
    }

    override fun serialize(encoder: Encoder, value: Double) {
        encoder.encodeDouble(value)
    }
}

internal object LenientDoubleOrNullSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder =
            decoder as? JsonDecoder ?: error("LenientDoubleOrNullSerializer requires JsonDecoder")
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return element.lenientDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) {
            val jsonEncoder =
                encoder as? JsonEncoder
                    ?: error("LenientDoubleOrNullSerializer requires JsonEncoder")
            jsonEncoder.encodeJsonElement(JsonNull)
        } else {
            encoder.encodeDouble(value)
        }
    }
}
