package de.moritzf.quota.openai

import de.moritzf.quota.openai.dto.AdditionalRateLimitDto
import de.moritzf.quota.openai.dto.CreditsDto
import de.moritzf.quota.openai.dto.RateLimitDto
import de.moritzf.quota.openai.dto.RateLimitReachedTypeDto
import de.moritzf.quota.openai.dto.SpendControlDto
import de.moritzf.quota.openai.dto.UsageResponseDto
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object OpenAiCodexQuotaSerializer : KSerializer<OpenAiCodexQuota> {
    override val descriptor: SerialDescriptor = UsageResponseDto.serializer().descriptor

    /**
     * Sections are decoded individually so one unparsable block (for example a reshaped credits or
     * spend_control object, or a single broken additional rate limit entry) only drops that block
     * instead of failing the whole usage payload.
     */
    override fun deserialize(decoder: Decoder): OpenAiCodexQuota {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: return decoder.decodeSerializableValue(UsageResponseDto.serializer()).toQuota()
        val root =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Usage response is not a JSON object")

        val dto =
            UsageResponseDto(
                userId = root.stringOrNull("user_id"),
                accountId = root.stringOrNull("account_id"),
                email = root.stringOrNull("email"),
                rateLimit =
                    JsonSupport.decodeSectionOrNull(root["rate_limit"], RateLimitDto.serializer()),
                codeReviewRateLimit =
                    JsonSupport.decodeSectionOrNull(
                        root["code_review_rate_limit"],
                        RateLimitDto.serializer(),
                    ),
                planType = root.stringOrNull("plan_type"),
                credits = JsonSupport.decodeSectionOrNull(root["credits"], CreditsDto.serializer()),
                spendControl =
                    JsonSupport.decodeSectionOrNull(
                        root["spend_control"],
                        SpendControlDto.serializer(),
                    ),
                rateLimitReachedType =
                    JsonSupport.decodeSectionOrNull(
                        root["rate_limit_reached_type"],
                        RateLimitReachedTypeDto.serializer(),
                    ),
                rateLimitResetCredits =
                    JsonSupport.decodeSectionOrNull(
                        root["rate_limit_reset_credits"],
                        RateLimitResetCredits.serializer(),
                    ),
                additionalRateLimits =
                    JsonSupport.decodeListItemsLeniently(
                        root["additional_rate_limits"],
                        AdditionalRateLimitDto.serializer(),
                    ),
            )
        return dto.toQuota()
    }

    override fun serialize(encoder: Encoder, value: OpenAiCodexQuota) {
        throw SerializationException("Serialization of OpenAiCodexQuota is not supported")
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
