package de.moritzf.quota.openai.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Team/business `spend_control.individual_limit` is either:
 * - a bare number (legacy),
 * - null,
 * - or an object `{ limit, used, remaining, used_percent, reset_at, ... }`
 *   where money fields may be strings.
 */
object FlexibleIndividualLimitSerializer : KSerializer<FlexibleIndividualLimit?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FlexibleIndividualLimit")

    override fun deserialize(decoder: Decoder): FlexibleIndividualLimit? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("FlexibleIndividualLimitSerializer requires JsonDecoder")
        return parse(jsonDecoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: FlexibleIndividualLimit?) {
        error("Serialization of FlexibleIndividualLimit is not supported")
    }

    fun parse(element: JsonElement): FlexibleIndividualLimit? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> {
                if (element.booleanOrNull != null) return null
                val amount = element.doubleOrNull ?: element.contentOrNull?.toDoubleOrNull()
                amount?.let { FlexibleIndividualLimit(amount = it) }
            }
            is JsonObject -> FlexibleIndividualLimit(
                amount = element.flexibleDouble("limit"),
                used = element.flexibleDouble("used"),
                remaining = element.flexibleDouble("remaining"),
                usedPercent = element.flexibleDouble("used_percent"),
                remainingPercent = element.flexibleDouble("remaining_percent"),
                resetAtEpochSeconds = element.flexibleLong("reset_at"),
                resetAfterSeconds = element.flexibleLong("reset_after_seconds"),
            ).takeUnless { it.isEmpty() }
            else -> null
        }
    }

    private fun JsonObject.flexibleDouble(name: String): Double? {
        val value = this[name] as? JsonPrimitive ?: return null
        return value.doubleOrNull ?: value.contentOrNull?.toDoubleOrNull()
    }

    private fun JsonObject.flexibleLong(name: String): Long? {
        val value = this[name] as? JsonPrimitive ?: return null
        return value.longOrNull
            ?: value.doubleOrNull?.toLong()
            ?: value.contentOrNull?.toLongOrNull()
            ?: value.contentOrNull?.toDoubleOrNull()?.toLong()
    }
}

data class FlexibleIndividualLimit(
    val amount: Double? = null,
    val used: Double? = null,
    val remaining: Double? = null,
    val usedPercent: Double? = null,
    val remainingPercent: Double? = null,
    val resetAtEpochSeconds: Long? = null,
    val resetAfterSeconds: Long? = null,
) {
    fun isEmpty(): Boolean {
        return amount == null &&
            used == null &&
            remaining == null &&
            usedPercent == null &&
            remainingPercent == null &&
            resetAtEpochSeconds == null &&
            resetAfterSeconds == null
    }
}
