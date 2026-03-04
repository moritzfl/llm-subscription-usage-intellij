package de.moritzf.quota.idea.auth;

import org.jetbrains.annotations.NotNull;

/**
 * Immutable OAuth client configuration values for the login flow.
 */
public record OAuthClientConfig(
        @NotNull String clientId,
        @NotNull String authorizationEndpoint,
        @NotNull String tokenEndpoint,
        @NotNull String redirectUri,
        @NotNull String originator,
        @NotNull String scopes,
        int callbackPort
) {
    public static OAuthClientConfig openAiUsageQuotaDefaults() {
        return new OAuthClientConfig(
                "app_EMoamEEZ73f0CkXaXp7hrann",
                "https://auth.openai.com/oauth/authorize",
                "https://auth.openai.com/oauth/token",
                "http://localhost:1455/auth/callback",
                "openai-usage-quota-plugin",
                "openid profile email offline_access",
                1455
        );
    }
}
