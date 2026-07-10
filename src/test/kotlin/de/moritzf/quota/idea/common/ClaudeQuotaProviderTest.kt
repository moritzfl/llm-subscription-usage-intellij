package de.moritzf.quota.idea.common

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeQuotaClient
import de.moritzf.quota.claude.ClaudeQuotaException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ClaudeQuotaProviderTest {
    @Test
    fun refreshStoresQuotaOnSuccess() {
        val quota = ClaudeQuota(fiveHourUsage = null, sevenDayUsage = null, rawJson = "{\"ok\":true}")
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient { quota },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertNull(provider.getLastError())
    }

    @Test
    fun refreshClearsDataWhenNotLoggedIn() {
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient { throw ClaudeQuotaException("unused") },
            tokenProvider = { null },
            tokenRefresher = { null },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals(provider.notConfiguredMessage, provider.getLastError())
    }

    @Test
    fun refreshKeepsLastQuotaOnRateLimitWhenPreviousDataExists() {
        val firstQuota = ClaudeQuota(rawJson = "{\"first\":true}")
        var fetchCount = 0
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient {
                fetchCount++
                if (fetchCount == 1) firstQuota
                else throw ClaudeQuotaException("Claude usage API rate limited. Try again later.", 429)
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertNull(provider.getLastError())

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertNull(provider.getLastError(), "rate limit should not surface as error while last quota exists")
    }

    @Test
    fun refreshSurfacesErrorWhenRateLimitHasNoPreviousData() {
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient { throw ClaudeQuotaException("Claude usage API rate limited.", 429) },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals("Claude usage API rate limited.", provider.getLastError())
    }

    @Test
    fun refreshSurfacesNonRateLimitErrorEvenWithPreviousData() {
        val firstQuota = ClaudeQuota(rawJson = "{\"first\":true}")
        var fetchCount = 0
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient {
                fetchCount++
                if (fetchCount == 1) firstQuota
                else throw ClaudeQuotaException("Claude usage request failed (HTTP 500).", 500)
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        provider.refresh()

        assertSame(firstQuota, provider.getLastQuota())
        assertEquals("Claude usage request failed (HTTP 500).", provider.getLastError())
    }

    private class FakeClaudeClient(private val fetch: () -> ClaudeQuota) : ClaudeQuotaClient() {
        override fun fetchQuota(accessToken: String?): ClaudeQuota = fetch()
    }
}