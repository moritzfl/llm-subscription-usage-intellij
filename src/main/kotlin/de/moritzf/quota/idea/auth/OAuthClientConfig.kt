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

        // OAuth credentials sourced from Google's gemini-cli (https://github.com/google-gemini/gemini-cli).
        // These are public credentials shipped in the CLI's open-source code; they are not personal credentials.
        @JvmStatic
        fun geminiDefaults(): OAuthClientConfig {
            return OAuthClientConfig(
                clientId = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com",
                authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenEndpoint = "https://oauth2.googleapis.com/token",
                redirectUri = "http://localhost:1456/auth/callback",
                originator = "gemini-usage-quota-plugin",
                scopes = "openid profile email https://www.googleapis.com/auth/cloud-platform",
                callbackPort = 1456,
                clientSecret = "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",
                extraParameters = mapOf(
                    "access_type" to "offline",
                    "prompt" to "consent"
                )
            )
        }
    }
}
