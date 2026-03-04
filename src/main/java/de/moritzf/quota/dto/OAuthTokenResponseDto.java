package de.moritzf.quota.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO for OAuth token endpoint responses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class OAuthTokenResponseDto {
    public String access_token;
    public String refresh_token;
    public String id_token;
    public long expires_in;
}
