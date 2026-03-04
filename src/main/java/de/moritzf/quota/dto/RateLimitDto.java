package de.moritzf.quota.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO describing rate-limit availability and its primary/secondary windows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RateLimitDto(
        @JsonProperty("allowed") Boolean allowed,
        @JsonProperty("limit_reached") Boolean limitReached,
        @JsonProperty("primary_window") UsageWindowDto primaryWindow,
        @JsonProperty("secondary_window") UsageWindowDto secondaryWindow
) {
}
