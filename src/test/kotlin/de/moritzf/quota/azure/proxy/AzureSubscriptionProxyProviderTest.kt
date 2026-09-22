package de.moritzf.quota.azure.proxy

import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.azure.AzureAccountConfig
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.azureUpstreamUrl
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AzureSubscriptionProxyProviderTest {
    @Test
    fun advertisedModelsUseAzPrefixAndUnknownDeploymentsStillProxy() {
        val provider = provider(deploymentNames = listOf("gpt-4o"))
        val advertised = provider.models().single()
        assertEquals("az-gpt-4o", advertised.localId)
        assertEquals("gpt-4o", advertised.upstreamId)
        assertEquals(true, advertised.isDefault)

        val fallback = provider.fallbackModel("my-deployment", SubscriptionProxyRoute.CHAT_COMPLETIONS)
        assertEquals("my-deployment", fallback?.upstreamId)
        assertNull(provider.fallbackModel("../etc", SubscriptionProxyRoute.CHAT_COMPLETIONS))
        assertNull(provider.fallbackModel("gpt-4o", SubscriptionProxyRoute.FIM_COMPLETIONS))
    }

    @Test
    fun upstreamUrlUsesOpenAiV1AndApiVersion() {
        assertEquals(
            "https://demo.openai.azure.com/openai/v1/chat/completions?api-version=v1",
            azureUpstreamUrl("https://demo.openai.azure.com/openai/v1", "/chat/completions"),
        )
        assertNotNull(provider().isConfigured())
    }

    private fun provider(deploymentNames: List<String> = emptyList()) = AzureSubscriptionProxyProvider(
        configProvider = {
            AzureSubscriptionProxyProvider.AzureProxyConfig(
                executable = Path.of("/usr/bin/az"),
                account = AzureAccountConfig(resourceName = "demo", deploymentNames = deploymentNames),
            )
        },
        cliFactory = { AzureCli(it, run = { _, _, _, _ -> error("az should not run while listing models") }) },
    )
}
