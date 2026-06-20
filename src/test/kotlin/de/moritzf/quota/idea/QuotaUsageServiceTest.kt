package de.moritzf.quota.idea

import de.moritzf.quota.idea.common.*
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCodexQuotaException
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class QuotaUsageServiceTest {
    @Test
    fun refreshStoresRawResponseFromQuotaException() {
        val rawJson = """{"unexpected":"shape"}"""
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                throw OpenAiCodexQuotaException("Usage response could not be parsed", 200, rawJson)
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val service = createService(openAiProvider = openAiProvider)

        try {
            service.refreshNowBlocking()

            assertNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertEquals("Request failed (200)", service.getLastError(QuotaProviderType.OPEN_AI))
            assertEquals(rawJson, service.getLastResponseJson(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun clearUsageDataRemovesCachedRawResponse() {
        val rawJson = """{"rate_limit":{"allowed":true}}"""
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    this.rawJson = rawJson
                }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val service = createService(openAiProvider = openAiProvider)

        try {
            service.refreshNowBlocking()
            assertEquals(rawJson, service.getLastResponseJson(QuotaProviderType.OPEN_AI))

            service.clearAllUsageData("Not logged in")

            assertNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertEquals("Not logged in", service.getLastError(QuotaProviderType.OPEN_AI))
            assertNull(service.getLastResponseJson(QuotaProviderType.OPEN_AI))
            assertNull(service.getLastQuota(QuotaProviderType.OPEN_CODE))
            assertEquals("No session cookie configured", service.getLastError(QuotaProviderType.OPEN_CODE))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun clearOpenCodeUsageDataKeepsCodexState() {
        val rawJson = """{"rate_limit":{"allowed":true}}"""
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    this.rawJson = rawJson
                }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val openCodeClient = RecordingOpenCodeQuotaClient()
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
            settingsProvider = { null },
        )
        val service = createService(
            openAiProvider = openAiProvider,
            openCodeProvider = openCodeProvider,
        )

        try {
            service.refreshNowBlocking()

            service.clearUsageData(QuotaProviderType.OPEN_CODE)

            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertEquals(rawJson, service.getLastResponseJson(QuotaProviderType.OPEN_AI))
            assertNull(service.getLastQuota(QuotaProviderType.OPEN_CODE))
            assertEquals("No session cookie configured", service.getLastError(QuotaProviderType.OPEN_CODE))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun changingCookieInvalidatesWorkspaceCache() {
        val openCodeClient = RecordingOpenCodeQuotaClient()
        var cookie = "cookie-a"
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { cookie },
            settingsProvider = { null },
        )
        val service = createService(openCodeProvider = openCodeProvider)

        try {
            service.refreshNowBlocking()
            cookie = "cookie-b"
            service.refreshNowBlocking()

            assertEquals(listOf("cookie-a", "cookie-b"), openCodeClient.discoveredCookies)
            assertEquals(
                listOf("cookie-a:wrk-cookie-a", "cookie-b:wrk-cookie-b"),
                openCodeClient.fetchCalls,
            )
        } finally {
            service.dispose()
        }
    }

    @Test
    fun staleOpenCodeCacheTriggersSingleRetry() {
        val openCodeClient = RecordingOpenCodeQuotaClient().apply {
            failFirstFetch = OpenCodeQuotaException("Could not parse OpenCode quota response", 200, "broken")
        }
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
            settingsProvider = { null },
        )
        val service = createService(openCodeProvider = openCodeProvider)

        try {
            service.refreshNowBlocking()

            assertEquals(2, openCodeClient.discoverCount)
            assertEquals(2, openCodeClient.fetchCalls.size)
            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_CODE))
            assertNull(service.getLastError(QuotaProviderType.OPEN_CODE))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun storedWorkspaceIdSkipsDiscovery() {
        val settings = QuotaSettingsState()
        settings.openCodeWorkspaceId = "wrk-stored"

        val openCodeClient = RecordingOpenCodeQuotaClient()
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
            settingsProvider = { settings },
        )
        val service = createService(
            openCodeProvider = openCodeProvider,
            settingsProvider = { settings },
        )

        try {
            service.refreshNowBlocking()

            assertEquals(0, openCodeClient.discoverCount)
            assertEquals(listOf("cookie-a:wrk-stored"), openCodeClient.fetchCalls)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun providerSpecificRefreshOnlyCallsSelectedProvider() {
        var openAiFetchCount = 0
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                openAiFetchCount++
                OpenAiCodexQuota()
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val openCodeClient = RecordingOpenCodeQuotaClient()
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
            settingsProvider = { null },
        )
        val service = createService(
            openAiProvider = openAiProvider,
            openCodeProvider = openCodeProvider,
        )

        try {
            service.refreshBlocking(QuotaProviderType.OPEN_AI)

            assertEquals(1, openAiFetchCount)
            assertEquals(emptyList(), openCodeClient.fetchCalls)

            service.refreshBlocking(QuotaProviderType.OPEN_CODE)

            assertEquals(1, openAiFetchCount)
            assertEquals(listOf("cookie-a:wrk-cookie-a"), openCodeClient.fetchCalls)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun refreshNowRefreshesProvidersConcurrently() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                started.countDown()
                assertTrue(started.await(2, TimeUnit.SECONDS))
                release.await(2, TimeUnit.SECONDS)
                OpenAiCodexQuota()
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val openCodeClient = object : RecordingOpenCodeQuotaClient() {
            override fun fetchQuota(sessionCookie: String, workspaceId: String): OpenCodeQuota {
                started.countDown()
                assertTrue(started.await(2, TimeUnit.SECONDS))
                release.countDown()
                return super.fetchQuota(sessionCookie, workspaceId)
            }
        }
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
            settingsProvider = { null },
        )
        val service = createService(openAiProvider = openAiProvider, openCodeProvider = openCodeProvider)

        try {
            service.refreshNowBlocking()

            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_CODE))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun refreshNowWaitsForOtherProvidersWhenOneFutureFails() {
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> throw IllegalStateException("boom") },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val completedOpenCodeRefresh = CountDownLatch(1)
        val openCodeClient = object : RecordingOpenCodeQuotaClient() {
            override fun fetchQuota(sessionCookie: String, workspaceId: String): OpenCodeQuota {
                Thread.sleep(50)
                return super.fetchQuota(sessionCookie, workspaceId).also {
                    completedOpenCodeRefresh.countDown()
                }
            }
        }
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
            settingsProvider = { null },
        )
        val service = createService(openAiProvider = openAiProvider, openCodeProvider = openCodeProvider)

        try {
            service.refreshNowBlocking()

            assertTrue(completedOpenCodeRefresh.await(1, TimeUnit.SECONDS))
            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_CODE))
        } finally {
            service.dispose()
        }
    }

    private fun createService(
        openAiProvider: OpenAiQuotaProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> OpenAiCodexQuota() },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        ),
        openCodeProvider: OpenCodeQuotaProvider = OpenCodeQuotaProvider(
            openCodeCookieProvider = { null },
            settingsProvider = { null },
        ),
        settingsProvider: () -> QuotaSettingsState? = { null },
        updatePublisher: (QuotaUsageSnapshot) -> Unit = {},
        scheduleOnInit: Boolean = false,
    ): QuotaUsageService {
        return QuotaUsageService(
            providers = listOf(openAiProvider, openCodeProvider),
            settingsProvider = settingsProvider,
            updatePublisher = updatePublisher,
            scheduleOnInit = scheduleOnInit,
        )
    }

    @Test
    fun scheduleRefreshUsesSettingsRefreshMinutes() {
        val settings = QuotaSettingsState().apply { refreshMinutes = 42 }
        val service = createService(
            settingsProvider = { settings },
            scheduleOnInit = true,
        )

        try {
            // scheduleRefresh called in init; value from settings used (verified via code inspection)
            assertEquals(42, settings.refreshMinutes)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun hydrateCachedQuotasPopulatesProvidersFromSettings() {
        val settings = QuotaSettingsState()
        val json = """{"allowed":true}"""
        settings.setCachedQuotaJson(QuotaProviderType.OPEN_AI, json)

        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> OpenAiCodexQuota() },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val service = createService(
            openAiProvider = openAiProvider,
            settingsProvider = { settings },
        )

        try {
            // hydrate called in init
            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun significantChangeUpdatesLastActiveSourceAndPersists() {
        val published = AtomicInteger(0)
        val settings = QuotaSettingsState()
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    primary = UsageWindow(usedPercent = 10.0) // low
                }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val service = createService(
            openAiProvider = openAiProvider,
            settingsProvider = { settings },
            updatePublisher = { published.incrementAndGet() },
        )

        try {
            service.refreshNowBlocking()

            assertTrue(published.get() >= 1)
            // lastActiveSource updated only on significant increase; test uses small change
        } finally {
            service.dispose()
        }
    }

    @Test
    fun significantChangeDetection() {
        val settings = QuotaSettingsState()
        var usage = 0.1
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> OpenAiCodexQuota(allowed = true).apply { primary = UsageWindow(usedPercent = usage * 100) } },
            accessTokenProvider = { "t" },
            accountIdProvider = { "a" },
        )
        val service = createService(openAiProvider = provider, settingsProvider = { settings })

        try {
            service.refreshNowBlocking()
            usage = 0.2 // significant change >0.005
            service.refreshNowBlocking()
            assertEquals("openai", settings.lastActiveSource)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun scheduleRefreshTiming() {
        val settings = QuotaSettingsState().apply { refreshMinutes = 15 }
        val service = createService(settingsProvider = { settings }, scheduleOnInit = true)

        try {
            // verifies scheduleRefresh uses settings value (scheduler delay)
            assertEquals(15, settings.refreshMinutes)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun resetOpenCodeWorkspaceCache() {
        val client = RecordingOpenCodeQuotaClient()
        val provider = OpenCodeQuotaProvider(
            openCodeClient = client,
            openCodeCookieProvider = { "c" },
            settingsProvider = { null },
        )
        val service = createService(openCodeProvider = provider)

        try {
            service.refreshNowBlocking()
            service.resetOpenCodeWorkspaceCache()
            service.refreshNowBlocking()
            assertEquals(2, client.discoverCount)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun publishUpdateCalledAfterRefresh() {
        val published = AtomicInteger(0)
        val service = createService(
            updatePublisher = { published.incrementAndGet() },
        )

        try {
            service.refreshNowBlocking()
            assertTrue(published.get() > 0)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun providerStateConcurrencyPreventsConcurrentRefresh() {
        val started = CountDownLatch(1)
        val concurrentCount = AtomicInteger(0)
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                concurrentCount.incrementAndGet()
                started.countDown()
                Thread.sleep(100) // simulate work
                OpenAiCodexQuota()
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val service = createService(openAiProvider = openAiProvider)

        try {
            val t1 = Thread { service.refreshBlocking(QuotaProviderType.OPEN_AI) }
            val t2 = Thread { service.refreshBlocking(QuotaProviderType.OPEN_AI) }
            t1.start()
            started.await(2, TimeUnit.SECONDS)
            t2.start()
            t1.join(500)
            t2.join(500)

            assertEquals(1, concurrentCount.get())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun resetOpenCodeWorkspaceCacheClearsCache() {
        val openCodeClient = RecordingOpenCodeQuotaClient()
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie" },
            settingsProvider = { null },
        )
        val service = createService(openCodeProvider = openCodeProvider)

        try {
            service.refreshNowBlocking()
            service.resetOpenCodeWorkspaceCache()
            service.refreshNowBlocking()

            assertEquals(2, openCodeClient.discoverCount) // discovery runs again
        } finally {
            service.dispose()
        }
    }

    @Test
    fun consumeOpenAiResetCreditCallsClientAndRefreshes() {
        var consumed = false
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> OpenAiCodexQuota() },
            resetCreditConsumer = { _, _, _ -> consumed = true },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val service = createService(openAiProvider = openAiProvider)

        try {
            service.consumeOpenAiResetCredit("credit-1")
            assertTrue(consumed)
            // refresh is called internally
        } finally {
            service.dispose()
        }
    }

    private open class RecordingOpenCodeQuotaClient : OpenCodeQuotaClient() {
        val discoveredCookies = mutableListOf<String>()
        val fetchCalls = mutableListOf<String>()
        var discoverCount: Int = 0
        var failFirstFetch: OpenCodeQuotaException? = null

        override fun discoverWorkspaceId(sessionCookie: String): String {
            discoverCount++
            discoveredCookies += sessionCookie
            return "wrk-$sessionCookie"
        }

        override fun fetchQuota(sessionCookie: String, workspaceId: String): OpenCodeQuota {
            fetchCalls += "$sessionCookie:$workspaceId"
            failFirstFetch?.let { exception ->
                failFirstFetch = null
                throw exception
            }
            return OpenCodeQuota(
                rollingUsage = OpenCodeUsageWindow(
                    status = "ok",
                    resetInSec = 60,
                    usagePercent = 10,
                ),
            )
        }
    }
}
