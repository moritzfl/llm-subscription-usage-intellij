package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.kimi.KimiDeviceIdStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiQuotaClient
import de.moritzf.quota.kimi.KimiQuotaException

class KimiQuotaProvider(
    override val accountId: String = QuotaProviderType.KIMI.id,
    private val client: KimiQuotaClient = KimiQuotaClient(deviceId = KimiDeviceIdStore.get()),
) : CachedQuotaProvider<KimiQuota>() {
    override val type = QuotaProviderType.KIMI
    override val notConfiguredMessage = "Kimi login required. Log in from settings."

    override fun refresh() {
        val credentials = KimiCredentialsStore.forAccount(accountId).loadBlocking()
        if (credentials?.isUsable() != true) {
            clearData(notConfiguredMessage)
            return
        }
        try {
            val result = client.fetchQuota(credentials)
            if (result.credentials != credentials) {
                KimiCredentialsStore.forAccount(accountId).save(result.credentials)
            }
            storeQuota(result.quota, result.quota.rawJson)
        } catch (exception: KimiQuotaException) {
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
