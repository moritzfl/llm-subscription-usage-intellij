package de.moritzf.quota.idea.auth

import de.moritzf.quota.idea.common.QuotaProviderType

enum class OAuthCallbackMode {
    LOOPBACK,
    PASTE,
}

enum class OAuthTokenBodyFormat {
    FORM,
    JSON,
}

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
    val includeNonce: Boolean = false,
    val callbackMode: OAuthCallbackMode = OAuthCallbackMode.LOOPBACK,
    val tokenBodyFormat: OAuthTokenBodyFormat = OAuthTokenBodyFormat.FORM,
    val includeStateInCodeExchange: Boolean = false,
) {
    companion object {
        @JvmStatic
        fun forProvider(type: QuotaProviderType): OAuthClientConfig {
            return when (type) {
                QuotaProviderType.OPEN_AI -> openAiUsageQuotaDefaults()
                QuotaProviderType.SUPERGROK -> xAiGrokDefaults()
                QuotaProviderType.CLAUDE -> anthropicClaudeDefaults()
                else -> openAiUsageQuotaDefaults()
            }
        }

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

        @JvmStatic
        fun xAiGrokDefaults(): OAuthClientConfig {
            return OAuthClientConfig(
                clientId = "b1a00492-073a-47ea-816f-4c329264a828",
                authorizationEndpoint = "https://auth.x.ai/oauth2/authorize",
                tokenEndpoint = "https://auth.x.ai/oauth2/token",
                redirectUri = "http://127.0.0.1:56121/callback",
                originator = "openai-usage-quota-plugin",
                scopes = "openid profile email offline_access grok-cli:access api:access",
                callbackPort = 56121,
                extraParameters = mapOf(
                    "plan" to "generic",
                    "referrer" to "openai-usage-quota-plugin",
                ),
                includeNonce = true,
            )
        }

        @JvmStatic
        fun anthropicClaudeDefaults(): OAuthClientConfig {
            return OAuthClientConfig(
                clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
                authorizationEndpoint = "https://claude.ai/oauth/authorize",
                tokenEndpoint = "https://platform.claude.com/v1/oauth/token",
                redirectUri = "https://platform.claude.com/oauth/code/callback",
                originator = "openai-usage-quota-plugin",
                // Exact scope list/order used by Claude Code / opencode-anthropic-auth.
                scopes = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload",
                callbackPort = 0,
                callbackMode = OAuthCallbackMode.PASTE,
                tokenBodyFormat = OAuthTokenBodyFormat.JSON,
                includeStateInCodeExchange = true,
            )
        }
    }
}
