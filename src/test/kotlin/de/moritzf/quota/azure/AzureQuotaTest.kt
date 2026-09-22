package de.moritzf.quota.azure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzureQuotaTest {
    @Test
    fun tokenAcceptsExpiresOnSecondsAndLegacyExpiresOn() {
        val now = 1_700_000_000_000L
        val modern = parseAzureCliToken(
            """{"accessToken":"modern","expires_on":${now / 1000 + 3600}}""",
            now,
        )
        assertEquals("modern", modern.accessToken)
        assertTrue(modern.expiresAtMillis > now)

        val legacy = parseAzureCliToken(
            """{"accessToken":"legacy","expiresOn":"2026-09-22 18:00:00"}""",
            now,
        )
        assertEquals("legacy", legacy.accessToken)
    }

    @Test
    fun tokenRejectsMissingExpirationWithoutEchoingTheBody() {
        val error = assertFailsWith<AzureCliException> {
            parseAzureCliToken("""{"accessToken":"secret-token"}""", 0)
        }
        assertTrue("secret-token" !in error.message.orEmpty())
    }

    @Test
    fun scopeFollowsOpenCodeHostRules() {
        assertEquals(
            AZURE_COGNITIVE_SCOPE,
            azureScopeForUrl("https://demo.openai.azure.com/openai/v1/chat/completions"),
        )
        assertEquals(
            AZURE_COGNITIVE_SCOPE,
            azureScopeForUrl("https://demo.services.ai.azure.com/models"),
        )
        assertEquals(
            AZURE_FOUNDRY_SCOPE,
            azureScopeForUrl("https://demo.services.ai.azure.com/openai/v1/responses"),
        )
        assertEquals(
            "https://cognitiveservices.azure.us/.default",
            azureScopeForUrl("https://demo.openai.azure.us/openai/v1/chat/completions"),
        )
    }

    @Test
    fun endpointRejectsNonAzureHostsAndBuildsTheV1Route() {
        assertNull(normalizeAzureEndpoint("http://demo.openai.azure.com"))
        assertNull(normalizeAzureEndpoint("https://example.com/openai/v1"))
        assertEquals(
            "https://demo.openai.azure.com/openai/v1",
            normalizeAzureEndpoint("https://demo.openai.azure.com"),
        )
        assertEquals(
            "https://demo.cognitiveservices.azure.com/openai/v1",
            normalizeAzureEndpoint("https://demo.cognitiveservices.azure.com/openai"),
        )
        val config = azureAccountConfig(null, "demo", null, null, null)
        assertEquals("https://demo.openai.azure.com/openai/v1", azureInferenceTarget(config)?.baseUrl)
    }

    @Test
    fun usagesStayPartialWhenLinesAreIncomplete() {
        val (windows, warnings) = parseAzureUsages(
            """
            {"value":[
              {"name":{"value":"OpenAI.Standard.gpt-4o","localizedValue":"Tokens Per Minute (thousands) - gpt-4o"},"currentValue":12,"limit":150,"unit":"Count"},
              {"name":{"value":"OpenAI.Standard.broken"},"currentValue":"nope","limit":10},
              {"limit":0,"name":{"value":"OpenAI.Standard.empty"}}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, windows.size)
        assertEquals(12.0, windows.first().used)
        assertEquals(150.0, windows.first().limit)
        assertEquals(null, windows[1].used)
        assertTrue(warnings.any { it.contains("broken") })
    }

    @Test
    fun quotaFetchKeepsIdentityWhenUsageIsForbidden() {
        val cli = AzureCli(java.nio.file.Path.of("/usr/bin/az"), run = { _, args, _, _ ->
            when {
                args.contains("list") -> """[{"id":"00000000-0000-0000-0000-000000000000","name":"Personal","isDefault":true,"user":{"name":"me@contoso.com","type":"user"}}]"""
                args.contains("--scope") && args.any { it.contains("management") } -> """{"accessToken":"mgmt","expires_on":4102444800}"""
                args.contains("--scope") -> """{"accessToken":"data","expires_on":4102444800}"""
                else -> error("unexpected $args")
            }
        })
        val http = AzureHttp { url, _ ->
            when {
                url.contains("/usages") -> AzureHttpResult(403, """{"error":{"code":"AuthorizationFailed"}}""")
                url.contains("/accounts?") -> AzureHttpResult(403, "")
                url.contains("/models") -> AzureHttpResult(200, """{"data":[{"id":"gpt-4o"}]}""")
                else -> AzureHttpResult(404, "")
            }
        }
        val quota = AzureQuotaClient(cli, http, liveUsage = {
            AzureRateLimitSnapshot("gpt-4o", 1000.0, 250.0, null, null, 30)
        }).fetch(azureAccountConfig("00000000-0000-0000-0000-000000000000", "demo", null, "eastus", null))

        assertEquals("me@contoso.com", quota.account?.userName)
        assertEquals(listOf("gpt-4o"), quota.models)
        assertTrue(quota.windows.any { it.kind == AzureUsageWindow.LIVE && it.usagePercent == 75.0 })
        assertTrue(quota.warnings.any { it.contains("Usages Reader") })
        assertTrue(quota.windows.none { it.kind == AzureUsageWindow.ALLOCATION })
    }

    @Test
    fun executableSearchIgnoresAzureConfigDirectory() {
        val home = Files.createTempDirectory("azure-home")
        val hidden = Files.createDirectories(home.resolve(".azure/bin"))
        val planted = hidden.resolve("az")
        Files.writeString(planted, "#!/bin/sh\n")
        planted.toFile().setExecutable(true)
        val pathDir = Files.createDirectories(home.resolve("bin"))
        val real = pathDir.resolve("az")
        Files.writeString(real, "#!/bin/sh\n")
        real.toFile().setExecutable(true)

        assertEquals(real, AzureCli.findExecutable(environment = mapOf("PATH" to pathDir.toString()), home = home.toString(), windows = false))
        assertNull(AzureCli.findExecutable(environment = emptyMap(), home = home.toString(), windows = false))
    }

    @Test
    fun tokenCommandPinsSubscriptionAndDoesNotSwitchTheCliAccount() {
        val calls = mutableListOf<List<String>>()
        val cli = AzureCli(java.nio.file.Path.of("/usr/bin/az"), run = { _, args, _, _ ->
            calls += args
            """{"accessToken":"token","expires_on":4102444800}"""
        })
        cli.accessToken(AZURE_COGNITIVE_SCOPE, "00000000-0000-0000-0000-000000000000")
        assertEquals(
            listOf(
                "account", "get-access-token",
                "--subscription", "00000000-0000-0000-0000-000000000000",
                "--scope", AZURE_COGNITIVE_SCOPE,
                "--output", "json",
            ),
            calls.single(),
        )
        assertTrue(calls.none { it.contains("set") })
    }
}
