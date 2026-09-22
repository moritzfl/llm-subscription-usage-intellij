package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokQuotaClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import de.moritzf.quota.supergrok.SuperGrokResetToken
import de.moritzf.quota.supergrok.SuperGrokUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SuperGrokQuotaProviderTest {
    @Test
    fun refreshKeepsLoginAndLastQuotaWhenTokenIsTemporarilyUnavailable() {
        val quota = SuperGrokQuota(
            plan = "SuperGrok",
            creditUsage = SuperGrokUsageWindow(label = "Weekly credits", usagePercent = 7.0),
            rawJson = "{\"ok\":true}",
        )
        var token: String? = "token"
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient { quota },
            tokenProvider = { token },
            tokenRefresher = { null },
            connectionStateProvider = { OAuthConnectionState.TEMPORARY_FAILURE },
        )

        provider.refresh()
        token = null
        provider.refresh()

        assertSame(quota, provider.getLastQuota(), "a failed refresh must not drop the last reading")
        assertTrue(provider.isLastErrorTransient())
        assertEquals(
            "Grok token could not be refreshed. Trying again with the next update.",
            provider.getLastError(),
        )
    }

    @Test
    fun refreshClearsDataWhenNotLoggedIn() {
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient { throw SuperGrokQuotaException("unused", 200) },
            tokenProvider = { null },
            tokenRefresher = { null },
            connectionStateProvider = { OAuthConnectionState.LOGGED_OUT },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals(provider.notConfiguredMessage, provider.getLastError())
    }

    @Test
    fun refreshKeepsLastQuotaWhenBillingPayloadIsIncomplete() {
        val firstQuota = SuperGrokQuota(
            plan = "SuperGrok",
            creditUsage = SuperGrokUsageWindow(label = "Weekly credits", usagePercent = 7.0),
            rawJson = "{\"first\":true}",
        )
        var fetchCount = 0
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient {
                fetchCount++
                if (fetchCount == 1) firstQuota
                else throw SuperGrokQuotaException("Grok billing response changed.", 200)
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertNull(provider.getLastError())

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertNull(provider.getLastError(), "incomplete billing should not surface while last quota exists")
    }

    @Test
    fun refreshKeepsLastQuotaWhenUsageFieldsMissingButParseSucceeds() {
        val reset = kotlin.time.Instant.parse("2026-07-21T16:34:03.633192+00:00")
        val firstQuota = SuperGrokQuota(
            plan = "SuperGrok",
            creditUsage = SuperGrokUsageWindow(
                label = "Weekly credits",
                usagePercent = 7.0,
                resetsAt = reset,
                reported = true,
            ),
            rawJson = "{\"first\":true}",
        )
        val incompleteQuota = SuperGrokQuota(
            plan = "SuperGrok",
            creditUsage = SuperGrokUsageWindow(
                label = "Weekly credits",
                usagePercent = 0.0,
                resetsAt = reset,
                reported = false,
            ),
            isUnifiedBilling = true,
            periodType = "USAGE_PERIOD_TYPE_WEEKLY",
            rawJson = "{\"incomplete\":true}",
        )
        var fetchCount = 0
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient {
                fetchCount++
                if (fetchCount == 1) firstQuota else incompleteQuota
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        provider.refresh()

        assertSame(firstQuota, provider.getLastQuota())
        assertEquals("{\"incomplete\":true}", provider.getLastRawJson())
        assertNull(provider.getLastError())
    }

    @Test
    fun refreshStoresInferredZeroPercentWhenNoPriorQuota() {
        val reset = kotlin.time.Instant.parse("2026-08-04T16:34:03.633192+00:00")
        val unusedQuota = SuperGrokQuota(
            plan = "SuperGrok Heavy",
            creditUsage = SuperGrokUsageWindow(
                label = "Weekly credits",
                usagePercent = 0.0,
                resetsAt = reset,
                reported = false,
            ),
            isUnifiedBilling = true,
            periodType = "USAGE_PERIOD_TYPE_WEEKLY",
            rawJson = "{\"unused\":true}",
        )
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient { unusedQuota },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()

        assertSame(unusedQuota, provider.getLastQuota())
        assertEquals(0.0, provider.getLastQuota()?.creditUsage?.usagePercent)
        assertNull(provider.getLastError())
    }

    @Test
    fun refreshAcceptsInferredZeroPercentWhenPeriodResets() {
        val firstQuota = SuperGrokQuota(
            plan = "SuperGrok Heavy",
            creditUsage = SuperGrokUsageWindow(
                label = "Weekly credits",
                usagePercent = 100.0,
                resetsAt = kotlin.time.Instant.parse("2026-07-28T16:34:03.633192+00:00"),
                reported = true,
            ),
            rawJson = "{\"first\":true}",
        )
        val newPeriodQuota = SuperGrokQuota(
            plan = "SuperGrok Heavy",
            creditUsage = SuperGrokUsageWindow(
                label = "Weekly credits",
                usagePercent = 0.0,
                resetsAt = kotlin.time.Instant.parse("2026-08-04T16:34:03.633192+00:00"),
                reported = false,
            ),
            rawJson = "{\"newPeriod\":true}",
        )
        var fetchCount = 0
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient {
                fetchCount++
                if (fetchCount == 1) firstQuota else newPeriodQuota
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        provider.refresh()

        assertSame(newPeriodQuota, provider.getLastQuota())
        assertEquals(0.0, provider.getLastQuota()?.creditUsage?.usagePercent)
        assertNull(provider.getLastError())
    }

    @Test
    fun refreshSurfacesAuthErrorsEvenWithPreviousData() {
        val firstQuota = SuperGrokQuota(rawJson = "{\"first\":true}")
        var fetchCount = 0
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient {
                fetchCount++
                if (fetchCount == 1) firstQuota
                else throw SuperGrokQuotaException("Grok auth expired.", 401)
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        provider.refresh()

        assertSame(firstQuota, provider.getLastQuota())
        assertEquals("Grok auth expired.", provider.getLastError())
    }

    @Test
    fun consumeResetUsesFirstAvailableTokenAndConsumer() {
        val quota = SuperGrokQuota(
            resetTokens = listOf(SuperGrokResetToken(tokenId = "restok_1")),
        )
        var consumed: Pair<String, String>? = null
        val provider = SuperGrokQuotaProvider(
            client = FakeSuperGrokClient { quota },
            tokenProvider = { "token" },
            tokenRefresher = { null },
            resetConsumer = { accessToken, tokenId -> consumed = accessToken to tokenId },
        )

        provider.refresh()
        provider.consumeReset(null)

        assertEquals("token" to "restok_1", consumed)
    }

    private class FakeSuperGrokClient(private val fetch: () -> SuperGrokQuota) : SuperGrokQuotaClient() {
        override fun fetchQuota(accessToken: String?): SuperGrokQuota = fetch()
    }
}
