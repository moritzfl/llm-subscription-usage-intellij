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
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val apiKeyProvider: () -> String? = {
        MiniMaxApiKeyStore.forAccount(accountId).loadBlocking()
    },
) : CachedQuotaProvider<MiniMaxQuota>() {
    override val type = QuotaProviderType.MINIMAX
    override val notConfiguredMessage =
        "MiniMax subscription key missing. Add a MiniMax Token Plan subscription key in settings."

    override fun refresh() {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        val preference =
            settingsProvider()?.miniMaxRegionFor(accountId) ?: MiniMaxRegionPreference.AUTO
        val regions =
            when (preference) {
                MiniMaxRegionPreference.GLOBAL -> listOf(MiniMaxRegion.GLOBAL)
                MiniMaxRegionPreference.CN -> listOf(MiniMaxRegion.CN)
                MiniMaxRegionPreference.AUTO -> listOf(MiniMaxRegion.GLOBAL, MiniMaxRegion.CN)
            }
        var reported: MiniMaxQuotaException? = null
        for (region in regions) {
            try {
                val quota = client.fetchQuota(apiKey, region)
                storeQuota(quota, quota.rawJson)
                return
            } catch (exception: MiniMaxQuotaException) {
                // A key belongs to one region, so the other region's rejection says nothing
                // about it: keep the failure from the region that accepted the key.
                if (reported == null || reported.isRejectedKey() || !exception.isRejectedKey()) {
                    reported = exception
                }
            }
        }

        storeFetchFailure(
            reported?.statusCode ?: 0,
            reported?.message ?: "Request failed. Check your connection.",
            reported?.rawBody,
        )
    }

    private fun MiniMaxQuotaException.isRejectedKey(): Boolean {
        return statusCode == 401 || statusCode == 403
    }
}
