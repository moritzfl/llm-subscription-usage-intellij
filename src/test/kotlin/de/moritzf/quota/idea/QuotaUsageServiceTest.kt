package de.moritzf.quota.idea

import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenAiCodexQuotaException
import de.moritzf.quota.OpenCodeQuota
import de.moritzf.quota.OpenCodeQuotaClient
import de.moritzf.quota.OpenCodeQuotaException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuotaUsageServiceTest {
    @Test
    fun refreshStoresRawResponseFromQuotaException() {
        val rawJson = """{"unexpected":"shape"}"""
        val service = createService(
            quotaFetcher = { _, _ ->
                throw OpenAiCodexQuotaException("Usage response could not be parsed", 200, rawJson)
            },
        )

        try {
            service.refreshNowBlocking()

            assertNull(service.getLastQuota())
            assertEquals("Request failed (200)", service.getLastError())
            assertEquals(rawJson, service.getLastResponseJson())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun clearUsageDataRemovesCachedRawResponse() {
        val rawJson = """{"rate_limit":{"allowed":true}}"""
        val service = createService(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    this.rawJson = rawJson
                }
            },
        )

        try {
            service.refreshNowBlocking()
            assertEquals(rawJson, service.getLastResponseJson())

            service.clearUsageData("Not logged in")

            assertNull(service.getLastQuota())
            assertEquals("Not logged in", service.getLastError())
            assertNull(service.getLastResponseJson())
            assertNull(service.getLastOpenCodeQuota())
            assertEquals("No session cookie configured", service.getLastOpenCodeError())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun clearOpenCodeUsageDataKeepsCodexState() {
        val rawJson = """{"rate_limit":{"allowed":true}}"""
        val openCodeClient = RecordingOpenCodeQuotaClient()
        val service = createService(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    this.rawJson = rawJson
                }
            },
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
        )

        try {
            service.refreshNowBlocking()

            service.clearOpenCodeUsageData()

            assertNotNull(service.getLastQuota())
            assertEquals(rawJson, service.getLastResponseJson())
            assertNull(service.getLastOpenCodeQuota())
            assertEquals("No session cookie configured", service.getLastOpenCodeError())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun changingCookieInvalidatesWorkspaceCache() {
        val openCodeClient = RecordingOpenCodeQuotaClient()
        var cookie = "cookie-a"
        val service = createService(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { cookie },
        )

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
        val service = createService(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
        )

        try {
            service.refreshNowBlocking()

            assertEquals(2, openCodeClient.discoverCount)
            assertEquals(2, openCodeClient.fetchCalls.size)
            assertNotNull(service.getLastOpenCodeQuota())
            assertNull(service.getLastOpenCodeError())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun storedWorkspaceIdSkipsDiscovery() {
        val settings = QuotaSettingsState()
        settings.openCodeWorkspaceId = "wrk-stored"

        val openCodeClient = RecordingOpenCodeQuotaClient()
        val service = createService(
            openCodeClient = openCodeClient,
            openCodeCookieProvider = { "cookie-a" },
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

    private fun createService(
        quotaFetcher: (String, String?) -> OpenAiCodexQuota = { _, _ -> OpenAiCodexQuota() },
        openCodeClient: OpenCodeQuotaClient = RecordingOpenCodeQuotaClient(),
        openCodeCookieProvider: () -> String? = { null },
        settingsProvider: () -> QuotaSettingsState? = { null },
    ): QuotaUsageService {
        return QuotaUsageService(
            quotaFetcher = quotaFetcher,
            openCodeClient = openCodeClient,
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
            openCodeCookieProvider = openCodeCookieProvider,
            settingsProvider = settingsProvider,
            updatePublisher = { _, _, _, _ -> },
            scheduleOnInit = false,
        )
    }

    private class RecordingOpenCodeQuotaClient : OpenCodeQuotaClient() {
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
                rollingUsage = de.moritzf.quota.OpenCodeUsageWindow(
                    status = "ok",
                    resetInSec = 60,
                    usagePercent = 10,
                ),
            )
        }
    }
}
