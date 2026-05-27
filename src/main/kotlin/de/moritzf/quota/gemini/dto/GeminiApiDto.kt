package de.moritzf.quota.gemini.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoadCodeAssistRequestDto(
    val cloudaicompanionProject: String? = null,
    val metadata: GeminiMetadataDto
)

@Serializable
data class GeminiMetadataDto(
    val ideType: String = "IDE_UNSPECIFIED",
    val platform: String = "PLATFORM_UNSPECIFIED",
    val pluginType: String = "GEMINI",
    val project: String? = null
)

@Serializable
data class LoadCodeAssistResponseDto(
    val currentTier: GeminiTierInfoDto? = null,
    val gcpManaged: Boolean = false,
    val cloudaicompanionProject: String? = null,
    val paidTier: GeminiTierInfoDto? = null
)

@Serializable
data class GeminiTierInfoDto(
    val id: String? = null,
    val name: String? = null
)

@Serializable
data class RetrieveUserQuotaRequestDto(
    val project: String
)

@Serializable
data class RetrieveUserQuotaResponseDto(
    val buckets: List<GeminiBucketDto> = emptyList()
)

@Serializable
data class GeminiBucketDto(
    val remainingAmount: String? = null,
    val remainingFraction: Double? = null,
    val resetTime: String? = null,
    val tokenType: String? = null,
    val modelId: String? = null
)
