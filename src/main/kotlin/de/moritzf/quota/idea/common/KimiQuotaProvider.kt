package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiQuotaClient
import de.moritzf.quota.kimi.KimiQuotaException
import java.util.concurrent.atomic.AtomicReference

class KimiQuotaProvider(
    private val client: KimiQuotaClient = KimiQuotaClient(),
) : QuotaProvider {
    override val id = "kimi"
    private val lastQuotaRef = AtomicReference<KimiQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastRawJsonRef = AtomicReference<String?>()

    fun getLastQuota(): KimiQuota? = lastQuotaRef.get()
    fun getLastError(): String? = lastErrorRef.get()
    fun getLastRawJson(): String? = lastRawJsonRef.get()

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
            lastQuotaRef.set(result.quota)
            lastErrorRef.set(null)
            lastRawJsonRef.set(result.quota.rawJson)
        } catch (exception: KimiQuotaException) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed")
            lastRawJsonRef.set(exception.rawBody)
        } catch (exception: Exception) {
            lastQuotaRef.set(null)
            lastErrorRef.set(exception.message ?: "Request failed")
            lastRawJsonRef.set(null)
        }
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastRawJsonRef.set(null)
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
            settings.updateTimestamp(id)
        }
    }
}
