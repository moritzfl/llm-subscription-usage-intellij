package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxQuotaClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.shared.JsonSupport

class MiniMaxQuotaProvider(
    private val client: MiniMaxQuotaClient = MiniMaxQuotaClient(),
    private val settingsProvider: () -> QuotaSettingsState? = { runCatching { QuotaSettingsState.getInstance() }.getOrNull() },
) : CachedQuotaProvider<MiniMaxQuota>() {
    override val type = QuotaProviderType.MINIMAX
    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()
    override fun cachedUsageFraction(settings: QuotaSettingsState): Double? {
        return QuotaSnapshotCache.decodeMiniMaxQuota(settings.cachedMiniMaxQuotaJson)?.usageFraction()
    }
    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return runCatching { JsonSupport.json.encodeToString(MiniMaxQuota.serializer(), quota) }.getOrNull()
    }

    override fun refresh() {
        val apiKey = MiniMaxApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            clearData("MiniMax API key missing. Add a MiniMax API key in settings.")
            return
        }

        val preference = settingsProvider()?.miniMaxRegionPreference() ?: MiniMaxRegionPreference.AUTO
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

        storeError(lastException?.message ?: "Request failed. Check your connection.", lastException?.rawBody)
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeMiniMaxQuota(settings.cachedMiniMaxQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeMiniMaxQuota(quota)?.let { settings.cachedMiniMaxQuotaJson = it }
            settings.updateTimestamp(type)
        }
    }
}
