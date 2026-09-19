package de.moritzf.quota.idea.common

import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaQuotaException
import de.moritzf.quota.ollama.OllamaUsageWindow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OllamaQuotaProviderTest {
    @Test
    fun refreshKeepsTheLastReadingWhileOffline() {
        val quota = OllamaQuota(
            sessionUsage = OllamaUsageWindow(usagePercent = 4.6),
            rawJson = "{\"ok\":true}",
        )
        var fetchCount = 0
        val provider = OllamaQuotaProvider(
            ollamaClient = FakeOllamaClient {
                fetchCount++
                if (fetchCount == 1) {
                    quota
                } else {
                    throw OllamaQuotaException("Ollama usage request failed. Check your connection.", 0)
                }
            },
            apiKeyProvider = { "key" },
        )

        provider.refresh()
        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertTrue(provider.isLastErrorTransient(), "an outage must not replace the quota on screen")
    }

    @Test
    fun refreshReportsAnInvalidApiKey() {
        val provider = OllamaQuotaProvider(
            ollamaClient = FakeOllamaClient {
                throw OllamaQuotaException("Ollama API key invalid. Check your Ollama API key in settings.", 401)
            },
            apiKeyProvider = { "key" },
        )

        provider.refresh()

        assertEquals(
            "Ollama API key invalid. Check your Ollama API key in settings.",
            provider.getLastError(),
        )
    }

    @Test
    fun refreshAppliesConfiguredMonthlyReset() {
        val quota = OllamaQuota(
            monthlyUsage = OllamaUsageWindow(usagePercent = 76.9),
            rawJson = """{"limits":{"monthly":{"usage":0.769}}}""",
        )
        val provider = OllamaQuotaProvider(
            ollamaClient = FakeOllamaClient { quota },
            apiKeyProvider = { "key" },
            monthlyResetAnchorProvider = { Instant.parse("2026-10-02T18:08:50Z") },
            nowProvider = { Instant.parse("2026-09-19T09:00:00Z") },
        )

        provider.refresh()

        assertEquals(
            Instant.parse("2026-10-02T18:08:50Z"),
            assertNotNull(provider.getLastQuota()?.monthlyUsage).resetsAt,
        )
    }

    @Test
    fun refreshClearsDataWithoutAnApiKey() {
        val provider = OllamaQuotaProvider(
            ollamaClient = FakeOllamaClient { error("must not be called") },
            apiKeyProvider = { null },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals(provider.notConfiguredMessage, provider.getLastError())
    }

    private class FakeOllamaClient(private val fetch: () -> OllamaQuota) : OllamaQuotaClient() {
        override fun fetchQuota(apiKey: String): OllamaQuota = fetch()
    }
}
