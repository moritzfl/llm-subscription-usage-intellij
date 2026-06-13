package de.moritzf.quota.openai.proxy

import com.aiproxyoauth.auth.AuthRequiredException
import com.aiproxyoauth.auth.CredentialsProvider

/**
 * Delegates Codex auth to the IDE auth service; token storage and refresh stay outside
 * the proxy/MCP layers.
 */
internal class QuotaCodexCredentialsProvider(
    private val accessTokenProvider: () -> String?,
    private val accountIdProvider: () -> String?,
    private val tokenRefresher: (staleAccessToken: String?) -> String?,
) : CredentialsProvider {
    @Throws(Exception::class)
    override fun getAuthHeaders(): Map<String, String> {
        val accessToken = accessTokenProvider()?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw AuthRequiredException("OpenAI login required: log in on the OpenAI settings tab, then retry.")
        val headers = linkedMapOf(
            "Authorization" to "Bearer $accessToken",
            "OpenAI-Beta" to "responses=experimental",
        )
        val accountId = accountIdProvider()?.trim().takeUnless { it.isNullOrBlank() }
        if (accountId != null) {
            headers["chatgpt-account-id"] = accountId
        }
        return headers
    }

    override fun refreshAfterUnauthorized(rejectedAuthorizationHeader: String?): Boolean {
        val staleToken = rejectedAuthorizationHeader
            ?.removePrefix("Bearer ")?.trim()?.takeUnless { it.isEmpty() }
        val tokenAfterRefresh = tokenRefresher(staleToken)
        return tokenAfterRefresh != null && tokenAfterRefresh != staleToken
    }
}
