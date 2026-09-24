package de.moritzf.quota.azure.proxy

import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.quota.azure.AzureAccountConfig
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.azureUpstreamUrl
import java.nio.file.Path
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AzureSubscriptionProxyProviderTest {
    @Test
    fun azureDoesNotStealOtherProvidersUndiscoveredPrefixedModels() {
        val other = OpenAiCompatibleApiKeySubscriptionProxyProvider(
            id = "minimax",
            displayName = "MiniMax",
            litellmProvider = "minimax",
            baseUri = URI("https://unused.invalid"),
            apiKeyProvider = { "test-key" },
            localIdPrefix = "mm-",
            discoverModels = false,
        )
        val catalog = SubscriptionModelCatalog(listOf(provider(), other))
        assertEquals("minimax", catalog.resolve("mm-new-model", SubscriptionProxyRoute.CHAT_COMPLETIONS)?.providerId)
    }

    @Test
    fun advertisedModelsUseAzPrefixAndUnknownDeploymentsStillProxy() {
        val provider = provider(deploymentNames = listOf("gpt-4o"))
        val advertised = provider.models().single()
        assertEquals("az-gpt-4o", advertised.localId)
        assertEquals("gpt-4o", advertised.upstreamId)
        assertEquals(true, advertised.isDefault)

        val fallback = provider.fallbackModel("az-my-deployment", SubscriptionProxyRoute.CHAT_COMPLETIONS)
        assertEquals("my-deployment", fallback?.upstreamId)
        assertNull(provider.fallbackModel("../etc", SubscriptionProxyRoute.CHAT_COMPLETIONS))
        assertNull(provider.fallbackModel("my-deployment", SubscriptionProxyRoute.CHAT_COMPLETIONS))
        assertNull(provider.fallbackModel("az-../etc", SubscriptionProxyRoute.CHAT_COMPLETIONS))
        assertNull(provider.fallbackModel("gpt-4o", SubscriptionProxyRoute.FIM_COMPLETIONS))
    }

    @Test
    fun onlyChatDeploymentsAreAdvertisedToJunie() {
        val provider = provider(
            deploymentNames = listOf(
                "text-embedding-3-large", "mistral-ocr-4-0", "mistral-document-ai-2512", "Cohere-parse-v5",
                "gpt-4.1", "Mistral-Large-3",
            ),
            resourceName = "azure-chat-filter-test",
        )
        assertEquals(listOf("az-gpt-4.1", "az-Mistral-Large-3"), provider.models().map { it.localId })
        assertEquals("az-gpt-4.1", provider.models().single { it.isDefault }.localId)
        assertNull(provider.fallbackModel("az-text-embedding-3-large", SubscriptionProxyRoute.CHAT_COMPLETIONS))
        assertNull(provider.fallbackModel("az-mistral-ocr-4-0", SubscriptionProxyRoute.CHAT_COMPLETIONS))
        assertNull(provider.fallbackModel("az-mistral-document-ai-2512", SubscriptionProxyRoute.CHAT_COMPLETIONS))
        assertNull(provider.fallbackModel("az-Cohere-parse-v5", SubscriptionProxyRoute.CHAT_COMPLETIONS))
    }

    @Test
    fun upstreamUrlOmitsApiVersionOnV1Paths() {
        assertEquals(
            "https://demo.openai.azure.com/openai/v1/chat/completions",
            azureUpstreamUrl("https://demo.openai.azure.com/openai/v1", "/chat/completions"),
        )
        assertEquals(
            "https://ael.services.ai.azure.com/api/projects/ael/openai/v1/chat/completions",
            azureUpstreamUrl("https://ael.services.ai.azure.com/api/projects/ael/openai/v1", "chat/completions"),
        )
        assertEquals(
            "https://demo.openai.azure.com/openai/deployments/gpt-4o/chat/completions?api-version=v1",
            azureUpstreamUrl("https://demo.openai.azure.com/openai/deployments/gpt-4o", "/chat/completions"),
        )
        assertEquals(true, provider().isConfigured())
    }

    private fun provider(deploymentNames: List<String> = emptyList(), resourceName: String = "demo") = AzureSubscriptionProxyProvider(
        configProvider = {
            AzureSubscriptionProxyProvider.AzureProxyConfig(
                executable = Path.of("/usr/bin/az"),
                account = AzureAccountConfig(resourceName = resourceName, deploymentNames = deploymentNames),
            )
        },
        cliFactory = { AzureCli(it, run = { _, _, _, _ -> error("az should not run while listing models") }) },
    )
}
