package de.moritzf.quota.openai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Maps the OpenAI-specific authorization object inside a decoded JWT payload.
 * The token decoding and extraction logic is implemented in {@link de.moritzf.quota.idea.QuotaTokenUtil}.
 */
@Serializable
data class OpenAiAuthorizationDto(
    @SerialName("chatgpt_account_id") val chatgptAccountId: String? = null,
)
