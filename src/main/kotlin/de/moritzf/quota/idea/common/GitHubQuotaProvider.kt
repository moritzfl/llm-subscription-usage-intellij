package de.moritzf.quota.idea.common

import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.GitHubQuotaException
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.JsonSupport

class GitHubQuotaProvider(
    private val client: GitHubQuotaClient = GitHubQuotaClient(),
) : CachedQuotaProvider<GitHubQuota>() {
    override val type = QuotaProviderType.GITHUB
    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()
    override fun cachedUsageFraction(settings: QuotaSettingsState): Double? {
        return QuotaSnapshotCache.decodeGitHubQuota(settings.cachedGitHubQuotaJson)?.usageFraction()
    }
    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return runCatching { JsonSupport.json.encodeToString(GitHubQuota.serializer(), quota) }.getOrNull()
    }

    override fun refresh() {
        val credentials = GitHubCredentialsStore.getInstance().loadBlocking()
        if (credentials?.isUsable() != true) {
            clearData("GitHub login required. Log in from settings.")
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

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeGitHubQuota(settings.cachedGitHubQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeGitHubQuota(quota)?.let { settings.cachedGitHubQuotaJson = it }
            settings.updateTimestamp(type)
        }
    }
}
