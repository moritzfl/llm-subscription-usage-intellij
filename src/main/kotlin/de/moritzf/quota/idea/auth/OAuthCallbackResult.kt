package de.moritzf.quota.idea.auth

/**
 * Result produced by the local OAuth callback endpoint or a pasted callback value.
 */
data class OAuthCallbackResult(
    val code: String? = null,
    val state: String? = null,
    val error: String? = null,
)

data class ParsedOAuthCallback(
    val code: String,
    val state: String,
)
