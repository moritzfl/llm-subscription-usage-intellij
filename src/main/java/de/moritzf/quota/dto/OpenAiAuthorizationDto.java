package de.moritzf.quota.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps the OpenAI-specific authorization object inside a decoded JWT payload.
 * The token decoding and extraction logic is implemented in {@link de.moritzf.quota.idea.QuotaTokenUtil}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiAuthorizationDto(
        @JsonProperty("chatgpt_account_id") String chatgptAccountId
) {
}
