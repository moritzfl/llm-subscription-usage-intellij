package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxQuotaClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference

class MiniMaxQuotaProvider(
    override val accountId: String = QuotaProviderType.MINIMAX.id,
    private val client: MiniMaxQuotaClient = MiniMaxQuotaClient(),
    private val settingsProvider: () -> QuotaSettingsState? = { runCatching { QuotaSettingsState.getInstance() }.getOrNull() },
) : CachedQuotaProvider<MiniMaxQuota>() {
    override val type = QuotaProviderType.MINIMAX
    override val notConfiguredMessage = "MiniMax subscription key missing. Add a MiniMax Token Plan subscription key in settings."

    override fun refresh() {
        val apiKey = MiniMaxApiKeyStore.forAccount(accountId).loadBlocking()
        if (apiKey.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        val preference = settingsProvider()?.miniMaxRegionFor(accountId) ?: MiniMaxRegionPreference.AUTO
        val regions = when (preference) {
            MiniMaxRegionPreference.GLOBAL -> listOf(MiniMaxRegion.GLOBAL)
            MiniMaxRegionPreference.CN -> listOf(MiniMaxRegion.CN)
            MiniMaxRegionPreference.AUTO -> listOf(MiniMaxRegion.GLOBAL, MiniMaxRegion.CN)
        }
        var lastException: MiniMaxQuotaException? = null
        for (region in regions) {
            try {
                val quota = client.fetchQuota(apiKey, region)
                storeQuota(quota, quota.rawJson)
                return
            } catch (exception: MiniMaxQuotaException) {
                lastException = exception
            }
        }

        storeFetchFailure(
            lastException?.statusCode ?: 0,
            lastException?.message ?: "Request failed. Check your connection.",
            lastException?.rawBody,
        )
    }
}
