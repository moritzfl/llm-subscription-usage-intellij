package de.moritzf.quota.idea.common

import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.supergrok.SuperGrokQuotaClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import de.moritzf.quota.supergrok.SuperGrokUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SuperGrokQuotaProviderTest {
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
        val firstQuota = SuperGrokQuota(
            plan = "SuperGrok",
            creditUsage = SuperGrokUsageWindow(label = "Weekly credits", usagePercent = 7.0),
            rawJson = "{\"first\":true}",
        )
        val incompleteQuota = SuperGrokQuota(
            plan = "SuperGrok",
            creditUsage = null,
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

    private class FakeSuperGrokClient(private val fetch: () -> SuperGrokQuota) : SuperGrokQuotaClient() {
        override fun fetchQuota(accessToken: String?): SuperGrokQuota = fetch()
    }
}
