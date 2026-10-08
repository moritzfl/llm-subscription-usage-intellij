package de.moritzf.quota.idea.common

import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralQuotaClient
import de.moritzf.quota.mistral.MistralUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MistralQuotaProviderTest {
    @Test
    fun refreshKeepsLastPerMinuteWindowsWhenProbeReturnsNone() {
        val tokens = MistralUsageWindow(usagePercent = 12.0, periodDurationMs = 60_000)
        val requests = MistralUsageWindow(usagePercent = 4.0, periodDurationMs = 60_000)
        var fetchCount = 0
        val provider =
            MistralQuotaProvider(
                client =
                    FakeMistralClient {
                        fetchCount++
                        if (fetchCount == 1) {
                            MistralQuota(
                                monthlyUsage = MistralUsageWindow(usagePercent = 1.0),
                                tokenUsage = tokens,
                                requestUsage = requests,
                            )
                        } else {
                            MistralQuota(monthlyUsage = MistralUsageWindow(usagePercent = 2.0))
                        }
                    },
                cookieProvider = { "ory_session_x=token" },
                apiKeyProvider = { "api-key" },
            )

        provider.refresh()
        provider.refresh()

        val quota = provider.getLastQuota()!!
        assertEquals(2.0, quota.monthlyUsage?.usagePercent)
        assertSame(tokens, quota.tokenUsage)
        assertSame(requests, quota.requestUsage)
    }

    @Test
    fun refreshReplacesPerMinuteWindowsWhenProbeReturnsThem() {
        var fetchCount = 0
        val provider =
            MistralQuotaProvider(
                client =
                    FakeMistralClient {
                        fetchCount++
                        if (fetchCount == 1) {
                            MistralQuota(
                                tokenUsage =
                                    MistralUsageWindow(
                                        usagePercent = 12.0,
                                        periodDurationMs = 60_000,
                                    ),
                                requestUsage =
                                    MistralUsageWindow(
                                        usagePercent = 4.0,
                                        periodDurationMs = 60_000,
                                    ),
                            )
                        } else {
                            MistralQuota(
                                tokenUsage =
                                    MistralUsageWindow(
                                        usagePercent = 20.0,
                                        periodDurationMs = 60_000,
                                    ),
                                requestUsage =
                                    MistralUsageWindow(
                                        usagePercent = 8.0,
                                        periodDurationMs = 60_000,
                                    ),
                            )
                        }
                    },
                cookieProvider = { "ory_session_x=token" },
                apiKeyProvider = { "api-key" },
            )

        provider.refresh()
        provider.refresh()

        val quota = provider.getLastQuota()!!
        assertEquals(20.0, quota.tokenUsage?.usagePercent)
        assertEquals(8.0, quota.requestUsage?.usagePercent)
    }

    @Test
    fun refreshDropsPerMinuteWindowsWhenApiKeyRemoved() {
        var apiKey: String? = "api-key"
        val provider =
            MistralQuotaProvider(
                client =
                    FakeMistralClient {
                        if (apiKey == null) {
                            MistralQuota(monthlyUsage = MistralUsageWindow(usagePercent = 1.0))
                        } else {
                            MistralQuota(
                                monthlyUsage = MistralUsageWindow(usagePercent = 1.0),
                                tokenUsage =
                                    MistralUsageWindow(
                                        usagePercent = 12.0,
                                        periodDurationMs = 60_000,
                                    ),
                                requestUsage =
                                    MistralUsageWindow(
                                        usagePercent = 4.0,
                                        periodDurationMs = 60_000,
                                    ),
                            )
                        }
                    },
                cookieProvider = { "ory_session_x=token" },
                apiKeyProvider = { apiKey },
            )

        provider.refresh()
        apiKey = null
        provider.refresh()

        val quota = provider.getLastQuota()!!
        assertEquals(1.0, quota.monthlyUsage?.usagePercent)
        assertNull(quota.tokenUsage)
        assertNull(quota.requestUsage)
    }

    private class FakeMistralClient(private val fetch: () -> MistralQuota) : MistralQuotaClient() {
        override fun fetchQuota(cookieHeader: String, apiKey: String?): MistralQuota = fetch()
    }
}
