package de.moritzf.quota.idea.common

import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.GitHubQuotaException
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.settings.QuotaSettingsState

class GitHubQuotaProvider(
    override val accountId: String = QuotaProviderType.GITHUB.id,
    private val client: GitHubQuotaClient = GitHubQuotaClient(),
) : CachedQuotaProvider<GitHubQuota>() {
    override val type = QuotaProviderType.GITHUB
    override val notConfiguredMessage = "GitHub login required. Log in from settings."

    override fun refresh() {
        val credentials = GitHubCredentialsStore.forAccount(accountId).loadBlocking()
        if (credentials?.isUsable() != true) {
            clearData(notConfiguredMessage)
            return
        }
        try {
            val settings = QuotaSettingsState.getInstance()
            val quota =
                client.fetchQuota(
                    credentials = credentials,
                    enterpriseHost = settings.githubHostFor(accountId),
                )
            storeQuota(quota, quota.rawJson)
        } catch (exception: GitHubQuotaException) {
            storeFetchFailure(
                exception.statusCode,
                exception.message ?: "Request failed",
                exception.rawBody,
            )
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }
}
