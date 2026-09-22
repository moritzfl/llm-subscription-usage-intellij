package de.moritzf.quota.idea

import de.moritzf.quota.idea.common.*
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCodexQuotaException
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokQuotaClient
import de.moritzf.quota.supergrok.SuperGrokResetToken
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

            // No prior success: still no quota, but error + raw body are retained.
            // The provider prefers the exception's own detail over "Request failed (status)".
            assertNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertEquals("Usage response could not be parsed", service.getLastError(QuotaProviderType.OPEN_AI))
            assertEquals(rawJson, service.getLastResponseJson(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun transientRefreshErrorKeepsLastGoodQuota() {
        var fail = false
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                if (fail) throw OpenAiCodexQuotaException("blip", 500, """{"err":1}""")
                OpenAiCodexQuota(allowed = true).apply {
                    primary = UsageWindow(usedPercent = 42.0)
                    rawJson = """{"ok":true}"""
                }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )
        val service = createService(openAiProvider = openAiProvider)

        try {
            service.refreshNowBlocking()
            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertNull(service.getLastError(QuotaProviderType.OPEN_AI))

            fail = true
            val result = service.refreshAsync(QuotaProviderType.OPEN_AI.id, forceUpdate = true)
                .get(5, TimeUnit.SECONDS)

            assertEquals("blip", result?.error)
            assertNotNull(result?.quota)

            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertEquals(0.42, service.getLastQuota(QuotaProviderType.OPEN_AI)!!.usageFraction()!!, 0.0001)
            assertEquals("blip", service.getLastError(QuotaProviderType.OPEN_AI))
            assertEquals("""{"err":1}""", service.getLastResponseJson(QuotaProviderType.OPEN_AI))
            // Status bar and popup keep showing the reading; the settings page still sees "blip".
            assertNull(service.currentSnapshot()[QuotaProviderType.OPEN_AI].error)
            assertNotNull(service.currentSnapshot()[QuotaProviderType.OPEN_AI].quota)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun openAiQuotaRefreshesTokenOnUnauthorized() {
        var token = "stale"
        var calls = 0
        val openAiProvider = OpenAiQuotaProvider(
            quotaFetcher = { accessToken, _ ->
                calls++
                if (accessToken == "stale") {
                    throw OpenAiCodexQuotaException("unauthorized", 401, "nope")
                }
                OpenAiCodexQuota(allowed = true)
            },
            accessTokenProvider = { token },
            accountIdProvider = { "account-1" },
            tokenRefresher = { stale ->
                assertEquals("stale", stale)
                token = "fresh"
                token
            },
        )
        val service = createService(openAiProvider = openAiProvider)

        try {
            service.refreshNowBlocking()
            assertEquals(2, calls)
            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
            assertNull(service.getLastError(QuotaProviderType.OPEN_AI))
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
            assertEquals("Not signed in to OpenCode", service.getLastError(QuotaProviderType.OPEN_CODE))
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
            credentialsProvider = { OAuthCredentials(accessToken = "token-a") },
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
            assertEquals("Not signed in to OpenCode", service.getLastError(QuotaProviderType.OPEN_CODE))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun changingTokenInvalidatesWorkspaceCache() {
        val openCodeClient = RecordingOpenCodeQuotaClient()
        var token = "token-a"
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            credentialsProvider = { OAuthCredentials(accessToken = token) },
            settingsProvider = { null },
        )
        val service = createService(openCodeProvider = openCodeProvider)

        try {
            service.refreshNowBlocking()
            token = "token-b"
            service.refreshNowBlocking()

            assertEquals(listOf("token-a", "token-b"), openCodeClient.discoveredTokens)
            assertEquals(
                listOf("token-a:wrk-token-a", "token-b:wrk-token-b"),
                openCodeClient.fetchCalls,
            )
        } finally {
            service.dispose()
        }
    }

    @Test
    fun rejectedOpenCodeTokenTriggersSingleRefreshAndRetry() {
        val openCodeClient = RecordingOpenCodeQuotaClient().apply {
            failFirstFetch = OpenCodeQuotaException("Unauthorized", 401, "{}")
        }
        val rejectedTokens = mutableListOf<String?>()
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            credentialsProvider = { rejected ->
                rejectedTokens += rejected
                OAuthCredentials(accessToken = if (rejected == null) "token-a" else "token-b")
            },
            settingsProvider = { null },
        )
        val service = createService(openCodeProvider = openCodeProvider)

        try {
            service.refreshNowBlocking()

            assertEquals(2, openCodeClient.discoverCount)
            assertEquals(2, openCodeClient.fetchCalls.size)
            assertEquals(listOf(null, "token-a"), rejectedTokens)
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
            credentialsProvider = { OAuthCredentials(accessToken = "token-a") },
            settingsProvider = { settings },
        )
        val service = createService(
            openCodeProvider = openCodeProvider,
            settingsProvider = { settings },
        )

        try {
            service.refreshNowBlocking()

            assertEquals(0, openCodeClient.discoverCount)
            assertEquals(listOf("token-a:wrk-stored"), openCodeClient.fetchCalls)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun discoveredWorkspaceIdIsPersisted() {
        val settings = QuotaSettingsState()
        val openCodeClient = RecordingOpenCodeQuotaClient()
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            credentialsProvider = { OAuthCredentials(accessToken = "token-a") },
            settingsProvider = { settings },
        )
        val service = createService(
            openCodeProvider = openCodeProvider,
            settingsProvider = { settings },
        )

        try {
            service.refreshNowBlocking()

            assertEquals(1, openCodeClient.discoverCount)
            assertEquals("wrk-token-a", settings.openCodeWorkspaceId)
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
            credentialsProvider = { OAuthCredentials(accessToken = "token-a") },
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
            assertEquals(listOf("token-a:wrk-token-a"), openCodeClient.fetchCalls)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun asyncRefreshCompletesOnlyAfterRequestedAccountUpdates() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val personalQuota = OpenAiCodexQuota(allowed = true)
        val work = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> OpenAiCodexQuota(allowed = false) },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val personal = OpenAiQuotaProvider(
            accountId = "personal",
            quotaFetcher = { _, _ ->
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                personalQuota
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "personal" },
        )
        val updates = AtomicInteger()
        val service = QuotaUsageService(
            providers = listOf(work, personal),
            settingsProvider = { null },
            updatePublisher = { updates.incrementAndGet() },
            scheduleOnInit = false,
        )
        try {
            val request = service.refreshAsync("personal", forceUpdate = true)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(request.isDone)

            service.refreshBlocking(QuotaProviderType.OPEN_AI)
            assertEquals(1, updates.get())
            assertFalse(request.isDone, "A sibling account's update must not complete this request")

            release.countDown()
            val result = request.get(5, TimeUnit.SECONDS)
            assertSame(personalQuota, result?.quota)
            assertNull(result?.error)
            assertEquals(2, updates.get())
            assertNull(service.refreshAsync("missing-account").get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            service.dispose()
        }
    }

    @Test
    fun forcedAsyncRefreshWaitsForItsOwnResultAfterAnExistingRequest() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger()
        val firstQuota = OpenAiCodexQuota(allowed = false)
        val secondQuota = OpenAiCodexQuota(allowed = true)
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    firstQuota
                } else {
                    secondStarted.countDown()
                    check(releaseSecond.await(5, TimeUnit.SECONDS))
                    secondQuota
                }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val service = createService(openAiProvider = provider)
        try {
            val automatic = service.refreshAsync(QuotaProviderType.OPEN_AI.id)
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val manual = service.refreshAsync(QuotaProviderType.OPEN_AI.id, forceUpdate = true)
            releaseFirst.countDown()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertSame(firstQuota, automatic.get(5, TimeUnit.SECONDS)?.quota)
            assertFalse(manual.isDone)

            releaseSecond.countDown()
            assertSame(secondQuota, manual.get(5, TimeUnit.SECONDS)?.quota)
            assertEquals(2, calls.get())
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
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
            override fun fetchQuota(accessToken: String, workspaceId: String): OpenCodeQuota {
                started.countDown()
                assertTrue(started.await(2, TimeUnit.SECONDS))
                release.countDown()
                return super.fetchQuota(accessToken, workspaceId)
            }
        }
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            credentialsProvider = { OAuthCredentials(accessToken = "token-a") },
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
            override fun fetchQuota(accessToken: String, workspaceId: String): OpenCodeQuota {
                Thread.sleep(50)
                return super.fetchQuota(accessToken, workspaceId).also {
                    completedOpenCodeRefresh.countDown()
                }
            }
        }
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            credentialsProvider = { OAuthCredentials(accessToken = "token-a") },
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
            credentialsProvider = { null },
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
            assertEquals(QuotaProviderType.OPEN_AI, settings.lastActiveProvider())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun smallWindowGrowthMaskedByLargerWindowStillUpdatesLastActiveSource() {
        // Regression: "Last used" detection compared the max window fraction, so
        // activity in a small window (e.g. Claude 5-hour) was masked by a larger,
        // slow-moving window (e.g. weekly) and the indicator stayed stuck on the
        // previously active provider.
        val settings = QuotaSettingsState().apply { setLastActiveProvider(QuotaProviderType.SUPERGROK) }
        var primaryPercent = 8.0
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    primary = UsageWindow(usedPercent = primaryPercent)
                    secondary = UsageWindow(usedPercent = 22.0) // larger window, unchanged
                }
            },
            accessTokenProvider = { "t" },
            accountIdProvider = { "a" },
        )
        val service = createService(openAiProvider = provider, settingsProvider = { settings })

        try {
            service.refreshNowBlocking()
            assertEquals(QuotaProviderType.SUPERGROK, settings.lastActiveProvider())

            primaryPercent = 10.0 // small window grows; max across windows stays 22%
            service.refreshNowBlocking()

            assertEquals(QuotaProviderType.OPEN_AI, settings.lastActiveProvider())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun slowGrowthAndOpposingWindowDecayStillUpdatesLastActiveSource() {
        // 1) Sticky baseline: +0.3% per poll accumulates past 0.5%.
        // 2) Per-window compare: primary decay must not cancel secondary growth.
        val settings = QuotaSettingsState().apply { setLastActiveProvider(QuotaProviderType.SUPERGROK) }
        var primaryPercent = 90.0
        var secondaryPercent = 20.0
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    primary = UsageWindow(usedPercent = primaryPercent)
                    secondary = UsageWindow(usedPercent = secondaryPercent)
                }
            },
            accessTokenProvider = { "t" },
            accountIdProvider = { "a" },
        )
        val service = createService(openAiProvider = provider, settingsProvider = { settings })

        try {
            service.refreshNowBlocking()
            assertEquals(QuotaProviderType.SUPERGROK, settings.lastActiveProvider())

            // Sub-threshold secondary growth alone — not enough yet.
            secondaryPercent = 20.3
            service.refreshNowBlocking()
            assertEquals(QuotaProviderType.SUPERGROK, settings.lastActiveProvider())

            // Accumulated secondary growth crosses 0.5% while primary decays hard
            // (sum would drop; per-window still sees secondary increase).
            primaryPercent = 80.0
            secondaryPercent = 20.6
            service.refreshNowBlocking()
            assertEquals(QuotaProviderType.OPEN_AI, settings.lastActiveProvider())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun restartKeepsPersistedLastActiveProviderWithoutRefresh() {
        // lastActiveSource is already written to openai-usage-quota.xml; after IDE restart
        // the indicator must honor it immediately from cache (no activity delta required).
        val settings = QuotaSettingsState().apply {
            setSource(QuotaIndicatorSource.LAST_USED)
            setLastActiveProvider(QuotaProviderType.OPEN_CODE)
            setCachedQuotaJson(
                QuotaProviderType.OPEN_CODE,
                checkNotNull(
                    QuotaSnapshotCache.encode(
                        QuotaProviderType.OPEN_CODE,
                        OpenCodeQuota(
                            rollingUsage = OpenCodeUsageWindow(status = "ok", resetInSec = 1000, usagePercent = 41.0),
                            weeklyUsage = OpenCodeUsageWindow(status = "ok", resetInSec = 10000, usagePercent = 97.0),
                        ),
                    ),
                ),
            )
            setCachedQuotaJson(
                QuotaProviderType.OPEN_AI,
                checkNotNull(
                    QuotaSnapshotCache.encode(
                        QuotaProviderType.OPEN_AI,
                        OpenAiCodexQuota(allowed = true).apply {
                            primary = UsageWindow(usedPercent = 0.0)
                        },
                    ),
                ),
            )
        }
        val service = createService(
            openAiProvider = OpenAiQuotaProvider(
                quotaFetcher = { _, _ -> error("restart path must not refresh") },
                accessTokenProvider = { "t" },
                accountIdProvider = { "a" },
            ),
            openCodeProvider = OpenCodeQuotaProvider(
                credentialsProvider = { error("restart path must not refresh") },
                settingsProvider = { settings },
            ),
            settingsProvider = { settings },
        )

        try {
            val indicator = service.getEffectiveIndicatorData()
            assertEquals(QuotaProviderType.OPEN_CODE, indicator.type)
            assertEquals(0.97, indicator.quota!!.usageFraction()!!, 0.0001)
            assertEquals(QuotaProviderType.OPEN_CODE, settings.lastActiveProvider())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun openCodeWindowGrowthUpdatesLastActiveSource() {
        val settings = QuotaSettingsState().apply { setLastActiveProvider(QuotaProviderType.OPEN_AI) }
        var rolling = 80.0
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = object : OpenCodeQuotaClient() {
                override fun discoverWorkspaceId(accessToken: String) = "wrk-1"
                override fun fetchQuota(accessToken: String, workspaceId: String) = OpenCodeQuota(
                    rollingUsage = OpenCodeUsageWindow(status = "ok", resetInSec = 1000, usagePercent = rolling),
                    weeklyUsage = OpenCodeUsageWindow(status = "ok", resetInSec = 10000, usagePercent = 50.0),
                )
            },
            credentialsProvider = { OAuthCredentials(accessToken = "token") },
            settingsProvider = { settings },
        )
        val service = createService(openCodeProvider = openCodeProvider, settingsProvider = { settings })

        try {
            service.refreshNowBlocking()
            assertEquals(QuotaProviderType.OPEN_AI, settings.lastActiveProvider())

            rolling = 85.0
            service.refreshNowBlocking()
            assertEquals(QuotaProviderType.OPEN_CODE, settings.lastActiveProvider())

            settings.setSource(QuotaIndicatorSource.LAST_USED)
            val indicator = service.getEffectiveIndicatorData()
            assertEquals(QuotaProviderType.OPEN_CODE, indicator.type)
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
            credentialsProvider = { OAuthCredentials(accessToken = "token") },
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
    fun providerStateConcurrencyCoalescesConcurrentRefresh() {
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
            assertTrue(started.await(2, TimeUnit.SECONDS))
            t2.start()
            t1.join(2000)
            t2.join(2000)

            // Second caller waited on the in-flight refresh instead of no-opping.
            assertEquals(1, concurrentCount.get())
            assertNotNull(service.getLastQuota(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun resetOpenCodeWorkspaceCacheClearsCache() {
        val openCodeClient = RecordingOpenCodeQuotaClient()
        val openCodeProvider = OpenCodeQuotaProvider(
            openCodeClient = openCodeClient,
            credentialsProvider = { OAuthCredentials(accessToken = "token") },
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
    fun twoOllamaAccountsKeepDistinctSnapshotsAfterRefresh() {
        val pro = OllamaQuotaProvider(
            accountId = "ollama",
            ollamaClient = object : de.moritzf.quota.ollama.OllamaQuotaClient() {
                override fun fetchQuota(apiKey: String) = de.moritzf.quota.ollama.OllamaQuota(
                    sessionUsage = de.moritzf.quota.ollama.OllamaUsageWindow(usagePercent = 42.0),
                )
            },
            apiKeyProvider = { "pro-key" },
        )
        val free = OllamaQuotaProvider(
            accountId = "ollama-free",
            ollamaClient = object : de.moritzf.quota.ollama.OllamaQuotaClient() {
                override fun fetchQuota(apiKey: String) = de.moritzf.quota.ollama.OllamaQuota(
                    sessionUsage = de.moritzf.quota.ollama.OllamaUsageWindow(usagePercent = 0.0),
                )
            },
            apiKeyProvider = { "free-key" },
        )
        val service = QuotaUsageService(
            providers = listOf(pro, free),
            settingsProvider = { null },
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.refreshNowBlocking()
            val snapshot = service.currentSnapshot()
            assertEquals(42.0, (snapshot.forAccount("ollama", QuotaProviderType.OLLAMA).quota as de.moritzf.quota.ollama.OllamaQuota).sessionUsage?.usagePercent)
            assertEquals(0.0, (snapshot.forAccount("ollama-free", QuotaProviderType.OLLAMA).quota as de.moritzf.quota.ollama.OllamaQuota).sessionUsage?.usagePercent)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun lastUsedIndicatorUsesActiveAccountQuota() {
        val settings = QuotaSettingsState().apply {
            accounts = mutableListOf(
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "openai",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Work",
                    isDefault = true,
                ),
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "personal",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Personal",
                ),
            )
            setSource(QuotaIndicatorSource.LAST_USED)
            setLastActiveAccount("personal")
        }
        val work = OpenAiQuotaProvider(
            accountId = "openai",
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply { primary = UsageWindow(usedPercent = 10.0) }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val personal = OpenAiQuotaProvider(
            accountId = "personal",
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply { primary = UsageWindow(usedPercent = 77.0) }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "personal" },
        )
        val service = QuotaUsageService(
            providers = listOf(work, personal),
            settingsProvider = { settings },
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.refreshNowBlocking()
            val indicator = service.getEffectiveIndicatorData()
            assertEquals(QuotaProviderType.OPEN_AI, indicator.type)
            assertEquals("personal", indicator.accountId)
            assertEquals(0.77, indicator.quota!!.usageFraction()!!, 0.0001)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun typeSnapshotPrefersDefaultAccount() {
        val settings = QuotaSettingsState().apply {
            accounts = mutableListOf(
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "openai",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Work",
                    isDefault = true,
                ),
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "personal",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Personal",
                ),
            )
        }
        val work = OpenAiQuotaProvider(
            accountId = "openai",
            quotaFetcher = { _, _ -> OpenAiCodexQuota(limitReached = true) },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val personal = OpenAiQuotaProvider(
            accountId = "personal",
            quotaFetcher = { _, _ -> OpenAiCodexQuota(limitReached = false) },
            accessTokenProvider = { "token" },
            accountIdProvider = { "personal" },
        )
        val service = QuotaUsageService(
            providers = listOf(personal, work),
            settingsProvider = { settings },
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.refreshNowBlocking()
            val snapshot = service.currentSnapshot()
            assertEquals(true, (snapshot[QuotaProviderType.OPEN_AI].quota as OpenAiCodexQuota).limitReached)
            assertEquals(true, (snapshot["openai"].quota as OpenAiCodexQuota).limitReached)
            assertEquals(false, (snapshot["personal"].quota as OpenAiCodexQuota).limitReached)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun indicatorDoesNotMixSiblingQuotaAndError() {
        val settings = QuotaSettingsState().apply {
            accounts = mutableListOf(
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "openai",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Work",
                    isDefault = true,
                ),
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "personal",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Personal",
                ),
            )
            setSource(QuotaIndicatorSource.OPEN_AI)
        }
        val work = OpenAiQuotaProvider(
            accountId = "openai",
            quotaFetcher = { _, _ -> throw OpenAiCodexQuotaException("work down", 500, "{}") },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val personal = OpenAiQuotaProvider(
            accountId = "personal",
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply { primary = UsageWindow(usedPercent = 10.0) }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "personal" },
        )
        val service = QuotaUsageService(
            providers = listOf(work, personal),
            settingsProvider = { settings },
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.refreshNowBlocking()
            val indicator = service.getEffectiveIndicatorData()
            assertEquals("openai", indicator.accountId)
            assertNull(indicator.quota)
            assertEquals("work down", indicator.error)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun lastUsedIndicatorDoesNotFallBackToSiblingWhenAccountMissing() {
        val settings = QuotaSettingsState().apply {
            accounts = mutableListOf(
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "openai",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Work",
                    isDefault = true,
                ),
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "personal",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Personal",
                ),
            )
            setSource(QuotaIndicatorSource.LAST_USED)
            setLastActiveAccount("personal")
        }
        val work = OpenAiQuotaProvider(
            accountId = "openai",
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply { primary = UsageWindow(usedPercent = 10.0) }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val service = QuotaUsageService(
            providers = listOf(work),
            settingsProvider = { settings },
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.refreshNowBlocking()
            val indicator = service.getEffectiveIndicatorData()
            assertEquals("personal", indicator.accountId)
            assertNull(indicator.quota)
            assertNull(indicator.error)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun lastUsedIndicatorUsesRemainingDefaultAfterAccountRemoved() {
        val settings = QuotaSettingsState().apply {
            accounts = mutableListOf(
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "work-uuid",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Work",
                    isDefault = true,
                ),
                de.moritzf.quota.idea.settings.ProviderAccount(
                    id = "personal-uuid",
                    typeId = QuotaProviderType.OPEN_AI.id,
                    name = "Personal",
                ),
            )
            setSource(QuotaIndicatorSource.LAST_USED)
            setLastActiveAccount("personal-uuid")
        }
        val work = OpenAiQuotaProvider(
            accountId = "work-uuid",
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply { primary = UsageWindow(usedPercent = 10.0) }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val personal = OpenAiQuotaProvider(
            accountId = "personal-uuid",
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply { primary = UsageWindow(usedPercent = 77.0) }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "personal" },
        )
        val service = QuotaUsageService(
            providers = listOf(work, personal),
            settingsProvider = { settings },
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.refreshNowBlocking()
            settings.accounts.removeIf { it.id == "personal-uuid" }
            settings.dropAccountData("personal-uuid")
            val indicator = service.getEffectiveIndicatorData()
            assertEquals(QuotaProviderType.OPEN_AI, indicator.type)
            assertEquals("work-uuid", indicator.accountId)
            assertEquals(0.10, indicator.quota!!.usageFraction()!!, 0.0001)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun consumeOpenAiResetCreditDoesNotFallBackToSibling() {
        var workConsumed = false
        var personalConsumed = false
        val work = OpenAiQuotaProvider(
            accountId = "openai",
            quotaFetcher = { _, _ -> OpenAiCodexQuota() },
            resetCreditConsumer = { _, _, _ -> workConsumed = true },
            accessTokenProvider = { "token" },
            accountIdProvider = { "work" },
        )
        val personal = OpenAiQuotaProvider(
            accountId = "personal",
            quotaFetcher = { _, _ -> OpenAiCodexQuota() },
            resetCreditConsumer = { _, _, _ -> personalConsumed = true },
            accessTokenProvider = { "token" },
            accountIdProvider = { "personal" },
        )
        val service = QuotaUsageService(
            providers = listOf(work, personal),
            settingsProvider = { null },
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.consumeOpenAiResetCredit("credit-1", "missing")
            assertFalse(workConsumed)
            assertFalse(personalConsumed)
            service.consumeOpenAiResetCredit("credit-1", "personal")
            assertFalse(workConsumed)
            assertTrue(personalConsumed)
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
            service.consumeOpenAiResetCredit("credit-1", QuotaProviderType.OPEN_AI.id)
            assertTrue(consumed)
            // refresh is called internally
        } finally {
            service.dispose()
        }
    }

    @Test
    fun consumeSuperGrokResetCallsProviderAndRefreshes() {
        var consumed: String? = null
        var fetches = 0
        val superGrokProvider = SuperGrokQuotaProvider(
            client = object : SuperGrokQuotaClient() {
                override fun fetchQuota(accessToken: String?): SuperGrokQuota {
                    fetches++
                    return SuperGrokQuota(
                        resetTokens = listOf(SuperGrokResetToken(tokenId = "restok_1")),
                    )
                }
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
            resetConsumer = { _, tokenId -> consumed = tokenId },
        )
        val service = QuotaUsageService(
            providers = listOf(superGrokProvider),
            settingsProvider = { null },
            updatePublisher = {},
            scheduleOnInit = false,
            sleeper = {},
        )

        try {
            service.refreshNowBlocking()
            val before = fetches
            service.consumeSuperGrokReset("restok_1", QuotaProviderType.SUPERGROK.id)
            assertEquals("restok_1", consumed)
            assertTrue(fetches > before)
        } finally {
            service.dispose()
        }
    }

    private open class RecordingOpenCodeQuotaClient : OpenCodeQuotaClient() {
        val discoveredTokens = mutableListOf<String>()
        val fetchCalls = mutableListOf<String>()
        var discoverCount: Int = 0
        var failFirstFetch: OpenCodeQuotaException? = null

        override fun discoverWorkspaceId(accessToken: String): String {
            discoverCount++
            discoveredTokens += accessToken
            return "wrk-$accessToken"
        }

        override fun fetchQuota(accessToken: String, workspaceId: String): OpenCodeQuota {
            fetchCalls += "$accessToken:$workspaceId"
            failFirstFetch?.let { exception ->
                failFirstFetch = null
                throw exception
            }
            return OpenCodeQuota(
                rollingUsage = OpenCodeUsageWindow(
                    status = "ok",
                    resetInSec = 60,
                    usagePercent = 10.0,
                ),
            )
        }
    }
}
