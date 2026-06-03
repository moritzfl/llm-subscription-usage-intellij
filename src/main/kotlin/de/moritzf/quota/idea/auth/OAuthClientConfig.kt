package de.moritzf.quota.idea.auth

/**
 * Immutable OAuth client configuration values for the login flow.
 */
data class OAuthClientConfig(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val originator: String,
    val scopes: String,
    val callbackPort: Int,
    val clientSecret: String? = null,
    val extraParameters: Map<String, String> = emptyMap(),
) {
    companion object {
        @JvmStatic
        fun openAiUsageQuotaDefaults(): OAuthClientConfig {
            return OAuthClientConfig(
                clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
                authorizationEndpoint = "https://auth.openai.com/oauth/authorize",
                tokenEndpoint = "https://auth.openai.com/oauth/token",
                redirectUri = "http://localhost:1455/auth/callback",
                originator = "openai-usage-quota-plugin",
                scopes = "openid profile email offline_access",
                callbackPort = 1455,
                extraParameters = mapOf(
                    "codex_cli_simplified_flow" to "true",
                    "originator" to "openai-usage-quota-plugin"
                )
            )
        }
    }
}
