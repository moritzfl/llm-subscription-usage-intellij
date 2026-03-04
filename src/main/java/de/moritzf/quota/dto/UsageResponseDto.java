package de.moritzf.quota.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for the top-level usage response payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageResponseDto(
        @JsonProperty("user_id") String userId,
        @JsonProperty("account_id") String accountId,
        @JsonProperty("email") String email,
        @JsonProperty("rate_limit") RateLimitDto rateLimit,
        @JsonProperty("plan_type") String planType
) {
}
