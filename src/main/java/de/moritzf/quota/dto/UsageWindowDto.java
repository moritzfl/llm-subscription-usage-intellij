package de.moritzf.quota.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for one raw usage window entry returned by the usage endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageWindowDto(
        @JsonProperty("used_percent") Double usedPercent,
        @JsonProperty("limit_window_seconds") Double limitWindowSeconds,
        @JsonProperty("reset_at") Double resetAt
) {
}
