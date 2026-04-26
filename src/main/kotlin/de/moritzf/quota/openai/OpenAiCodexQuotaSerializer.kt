package de.moritzf.quota.openai

import de.moritzf.quota.openai.dto.UsageResponseDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object OpenAiCodexQuotaSerializer : KSerializer<OpenAiCodexQuota> {
    override val descriptor: SerialDescriptor = UsageResponseDto.serializer().descriptor

    override fun deserialize(decoder: Decoder): OpenAiCodexQuota {
        return decoder.decodeSerializableValue(UsageResponseDto.serializer()).toQuota()
    }

    override fun serialize(encoder: Encoder, value: OpenAiCodexQuota) {
        throw SerializationException("Serialization of OpenAiCodexQuota is not supported")
    }
}
