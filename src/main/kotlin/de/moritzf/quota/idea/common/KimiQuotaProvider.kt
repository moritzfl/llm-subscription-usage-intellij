package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiQuotaClient
import de.moritzf.quota.kimi.KimiQuotaException

class KimiQuotaProvider(
    private val client: KimiQuotaClient = KimiQuotaClient(),
) : CachedQuotaProvider<KimiQuota>() {
    override val type = QuotaProviderType.KIMI
    override val notConfiguredMessage = "Kimi login required. Log in from settings."

    override fun refresh() {
        val credentials = KimiCredentialsStore.getInstance().loadBlocking()
        if (credentials?.isUsable() != true) {
            clearData(notConfiguredMessage)
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
}
