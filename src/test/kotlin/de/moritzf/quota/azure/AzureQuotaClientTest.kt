package de.moritzf.quota.azure

import de.moritzf.quota.azure.proxy.AzureSubscriptionProxyProvider
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AzureQuotaClientTest {
    @Test
    fun defaultSubscriptionDiscoveryReachesTheProxyCatalog() {
        val config = AzureAccountConfig(resourceName = "catalog-${UUID.randomUUID()}")
        val quota = AzureQuotaClient(cli(), AzureHttp { url, _ ->
            if ("/models?" in url) AzureHttpResult(200, """{"data":[{"id":"chat-deployment"}]}""")
            else AzureHttpResult(403, "")
        }).fetch(config)

        assertEquals(listOf("chat-deployment"), quota.models)
        assertEquals(listOf("az-chat-deployment"), proxy(config).models().map { it.localId })
    }

    @Test
    fun endpointSelectsItsOwnResourceAndAdvertisesDeploymentNames() {
        val calls = mutableListOf<String>()
        val config = AzureAccountConfig(
            resourceName = "other",
            endpoint = "https://selected.services.ai.azure.com/openai/v1",
        )
        val quota = AzureQuotaClient(cli(), AzureHttp { url, _ ->
            calls += url
            when {
                "/accounts?" in url -> resources()
                "/accounts/selected/deployments?" in url -> deployments("production-chat")
                "/accounts/other/deployments?" in url -> deployments("wrong-resource")
                else -> AzureHttpResult(403, "")
            }
        }).fetch(config)

        assertEquals(listOf("production-chat"), quota.models)
        assertEquals(listOf("az-production-chat"), proxy(config).models().map { it.localId })
        assertTrue(calls.none { "/accounts/other/deployments?" in it })
        assertTrue(calls.none { "/locations/eastus/" in it })
    }

    @Test
    fun missingExplicitResourceDoesNotSubstituteAnUnrelatedResource() {
        val calls = mutableListOf<String>()
        val quota = AzureQuotaClient(cli(), AzureHttp { url, _ ->
            calls += url
            if ("/accounts?" in url) resources() else AzureHttpResult(403, "")
        }).fetch(AzureAccountConfig(resourceName = "missing"))

        assertTrue(calls.none { "/deployments?" in it || "/usages?" in it }, calls.toString())
        assertTrue(quota.warnings.any { "match" in it })
    }

    @Test
    fun regionalQuotaLinesAndSameNamedDeploymentsAreNotCollapsed() {
        val quota = AzureQuotaClient(cli(), AzureHttp { url, _ ->
            when {
                "/accounts?" in url -> resources()
                "/usages?" in url -> AzureHttpResult(200, """{"value":[{
                    "name":{"value":"OpenAI.Standard.gpt-4o"},"currentValue":10,"limit":100
                }]}""")
                "/deployments?" in url -> deployments("chat")
                else -> AzureHttpResult(403, "")
            }
        }).fetch(AzureAccountConfig())

        assertEquals(2, quota.windows.count { it.kind == AzureUsageWindow.ALLOCATION })
        assertEquals(2, quota.windows.count { it.kind == AzureUsageWindow.DEPLOYMENT })
        assertEquals(4, quota.windows.map { it.label }.distinct().size)
    }

    @Test
    fun unreadableIdentityDoesNotHideDataPlaneOrLiveUsage() {
        val cli = AzureCli(Path.of("/test/az"), run = { _, args, _, _ ->
            if ("get-access-token" !in args) throw AzureCliException("Account metadata unavailable")
            """{"accessToken":"data-token","expires_on":4102444800}"""
        })
        val quota = AzureQuotaClient(cli, AzureHttp { url, _ ->
            if ("/models?" in url) AzureHttpResult(200, """{"data":[{"id":"chat"}]}""")
            else AzureHttpResult(403, "")
        }, liveUsage = { AzureRateLimitSnapshot("chat", 100.0, 25.0, null, null, 60) })
            .fetch(AzureAccountConfig(resourceName = "partial"))

        assertEquals(listOf("chat"), quota.models)
        assertEquals(75.0, quota.windows.single().usagePercent)
        assertTrue(quota.warnings.any { "metadata" in it })
    }

    @Test
    fun successfulEmptyDiscoveryClearsOldModelsButForbiddenDiscoveryKeepsThem() {
        val config = AzureAccountConfig(subscriptionId = SUBSCRIPTION, resourceName = "cache-${UUID.randomUUID()}")
        var response = AzureHttpResult(200, """{"data":[{"id":"old-chat"}]}""")
        val client = AzureQuotaClient(cli(), AzureHttp { url, _ ->
            if ("/models?" in url) response else AzureHttpResult(403, "")
        })
        client.fetch(config)
        response = AzureHttpResult(403, "")
        client.fetch(config)
        assertEquals(listOf("az-old-chat"), proxy(config).models().map { it.localId })
        response = AzureHttpResult(200, "<html>Temporarily unavailable</html>")
        val partial = client.fetch(config)
        assertTrue(partial.warnings.any { "Model list was not readable" in it })
        assertEquals(listOf("az-old-chat"), proxy(config).models().map { it.localId })
        response = AzureHttpResult(200, """{"data":[]}""")
        client.fetch(config)
        assertTrue(proxy(config).models().isEmpty())
    }

    private fun cli() = AzureCli(Path.of("/test/az"), run = { _, args, _, _ ->
        val identity = """{"id":"$SUBSCRIPTION","name":"Personal","isDefault":true,"user":{"name":"test@example.com","type":"user"}}"""
        when {
            "get-access-token" in args -> """{"accessToken":"test-token","expires_on":4102444800}"""
            "list" in args -> "[$identity]"
            else -> error("Unexpected command: $args")
        }
    })

    private fun proxy(config: AzureAccountConfig) = AzureSubscriptionProxyProvider(
        configProvider = { AzureSubscriptionProxyProvider.AzureProxyConfig(executable = Path.of("/test/az"), account = config) },
    )

    private fun resources() = AzureHttpResult(200, """{"value":[
        {"id":"/subscriptions/$SUBSCRIPTION/resourceGroups/group/providers/Microsoft.CognitiveServices/accounts/other",
         "name":"other","kind":"OpenAI","location":"eastus","properties":{"endpoint":"https://other.openai.azure.com/"}},
        {"id":"/subscriptions/$SUBSCRIPTION/resourceGroups/group/providers/Microsoft.CognitiveServices/accounts/selected",
         "name":"selected","kind":"AIServices","location":"westus","properties":{"endpoint":"https://selected.services.ai.azure.com/"}}
    ]}""")

    private fun deployments(name: String) = AzureHttpResult(200, """{"value":[{
        "name":"$name","sku":{"name":"Standard","capacity":10},
        "properties":{"model":{"name":"gpt-4o"}}
    }]}""")

    companion object {
        private const val SUBSCRIPTION = "00000000-0000-0000-0000-000000000001"
    }
}
