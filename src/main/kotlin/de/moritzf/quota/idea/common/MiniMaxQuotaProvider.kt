package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxQuotaClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import java.util.concurrent.atomic.AtomicReference

class MiniMaxQuotaProvider(
    private val client: MiniMaxQuotaClient = MiniMaxQuotaClient(),
    private val settingsProvider: () -> QuotaSettingsState? = { runCatching { QuotaSettingsState.getInstance() }.getOrNull() },
) : QuotaProvider {
    override val id = "minimax"
    private val lastQuotaRef = AtomicReference<MiniMaxQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): MiniMaxQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()
    fun getLastRawJson(): String? = lastRawJsonRef.get()

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
                lastQuotaRef.set(quota)
                lastErrorRef.set(null)
                lastRawJsonRef.set(quota.rawJson)
                return
            } catch (exception: MiniMaxQuotaException) {
                lastException = exception
            }
        }

        lastQuotaRef.set(null)
        lastErrorRef.set(lastException?.message ?: "Request failed. Check your connection.")
        lastRawJsonRef.set(lastException?.rawBody)
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastRawJsonRef.set(null)
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
            settings.updateTimestamp(id)
        }
    }
}
