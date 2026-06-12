package de.moritzf.quota.idea.common

import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.GitHubQuotaException
import de.moritzf.quota.idea.github.GitHubCredentialsStore

class GitHubQuotaProvider(
    private val client: GitHubQuotaClient = GitHubQuotaClient(),
) : CachedQuotaProvider<GitHubQuota>() {
    override val type = QuotaProviderType.GITHUB
    override val notConfiguredMessage = "GitHub login required. Log in from settings."

    override fun refresh() {
        val credentials = GitHubCredentialsStore.getInstance().loadBlocking()
        if (credentials?.isUsable() != true) {
            clearData(notConfiguredMessage)
            return
        }
        try {
            val quota = client.fetchQuota(credentials)
            storeQuota(quota, quota.rawJson)
        } catch (exception: GitHubQuotaException) {
            storeError(exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }
}
