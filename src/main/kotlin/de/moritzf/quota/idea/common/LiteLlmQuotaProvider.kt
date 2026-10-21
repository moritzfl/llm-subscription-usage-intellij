package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.compatible.CompatibleApiKeyStore
import de.moritzf.quota.idea.compatible.compatibleEndpoint
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.litellm.LiteLlmClient
import de.moritzf.quota.litellm.LiteLlmQuota
import de.moritzf.quota.litellm.LiteLlmResponseException

internal class LiteLlmQuotaProvider(override val accountId: String) :
    CachedQuotaProvider<LiteLlmQuota>() {
    override val type = QuotaProviderType.LITELLM
    override val notConfiguredMessage = "Enter a LiteLLM gateway URL and virtual key in settings."

    override fun refresh() {
        val settings = QuotaSettingsState.getInstance()
        val endpoint = compatibleEndpoint(settings.account(accountId))
        val key = CompatibleApiKeyStore.forAccount(accountId).loadBlocking()
        if (endpoint == null || key.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }
        try {
            val quota = LiteLlmClient(endpoint).quota(key)
            synchronized(settings) {
                if (
                    endpoint == compatibleEndpoint(settings.account(accountId)) &&
                        key == CompatibleApiKeyStore.forAccount(accountId).load()
                ) {
                    if (quota.hasUsageState()) {
                        storeQuota(quota, quota.rawJson)
                    } else {
                        storeError(
                            QuotaBundle.message("quota.litellm.unavailable"),
                            rawJson = quota.rawJson,
                            transient = true,
                        )
                    }
                }
            }
        } catch (failure: Exception) {
            failure.rethrowIfCancellation()
            synchronized(settings) {
                if (
                    endpoint == compatibleEndpoint(settings.account(accountId)) &&
                        key == CompatibleApiKeyStore.forAccount(accountId).load()
                ) {
                    storeError(
                        "Key budget unavailable. Check LiteLLM /key/info permissions. Chat can still be used.",
                        rawJson = (failure as? LiteLlmResponseException)?.rawResponse,
                        transient = true,
                    )
                }
            }
        }
    }

    // Never display another key's budget after a connection/key edit or restart.
    override fun hydrateFromCache(settings: QuotaSettingsState) {
        settings.dropAccountData(accountId)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        settings.updateTimestamp(accountId)
    }
}
