package de.moritzf.quota.idea.settings

import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.quota.idea.common.IdeProxyBuildContext
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.openai.CompletionsFimTestResult
import de.moritzf.quota.idea.openai.CompletionsFimTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/** An isolated settings copy and in-memory key overrides for discovery and test requests. */
internal class ProxyTestSettings(
    val settings: QuotaSettingsState,
    private val apiKeys: Map<String, String> = emptyMap(),
) {
    fun providers() =
        ProviderCatalog.createIdeProxyProviders(
            IdeProxyBuildContext(
                settings = settings,
                logRequests = false,
                requestLogDir = System.getProperty("java.io.tmpdir"),
                apiKeyOverrides = apiKeys,
            ),
            settings.enabledSubscriptionProxyProviders(),
        )

    fun testCompletion(): CompletionsFimTestResult {
        val config = settings.completionsConfig()
        require(config.modelLocalId.isNotBlank()) { message("modelTest.completion.selectModel") }
        return runBlocking {
            val catalog = runInterruptible(Dispatchers.IO) { SubscriptionModelCatalog(providers()) }
            CompletionsFimTester.test(catalog, config)
        }
    }
}
