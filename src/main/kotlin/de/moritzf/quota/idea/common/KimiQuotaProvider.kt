package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiQuotaClient
import de.moritzf.quota.kimi.KimiQuotaException
import de.moritzf.quota.shared.JsonSupport

class KimiQuotaProvider(
    private val client: KimiQuotaClient = KimiQuotaClient(),
) : CachedQuotaProvider<KimiQuota>() {
    override val type = QuotaProviderType.KIMI
    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()
    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return runCatching { JsonSupport.json.encodeToString(KimiQuota.serializer(), quota) }.getOrNull()
    }

    override fun refresh() {
        val credentials = KimiCredentialsStore.getInstance().loadBlocking()
        if (credentials?.isUsable() != true) {
            clearData("Kimi login required. Log in from settings.")
            return
        }
        try {
            val result = client.fetchQuota(credentials)
            if (result.credentials != credentials) {
                KimiCredentialsStore.getInstance().save(result.credentials)
            }
            storeQuota(result.quota, result.quota.rawJson)
        } catch (exception: KimiQuotaException) {
            storeError(exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = QuotaSnapshotCache.decodeKimiQuota(settings.cachedKimiQuotaJson)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get()
        if (quota != null) {
            QuotaSnapshotCache.encodeKimiQuota(quota)?.let { settings.cachedKimiQuotaJson = it }
            settings.updateTimestamp(type)
        }
    }

    private fun KimiQuota.usageFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, totalUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }
}
