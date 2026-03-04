package de.moritzf.quota.idea.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO for persisted OAuth credentials stored in PasswordSafe.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class OAuthCredentials {
    public String accessToken;
    public String refreshToken;
    public long expiresAt;
    public String accountId;
}
