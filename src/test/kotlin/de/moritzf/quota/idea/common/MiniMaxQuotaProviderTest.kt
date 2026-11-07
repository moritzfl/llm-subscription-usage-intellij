package de.moritzf.quota.idea.common

import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxQuotaClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MiniMaxQuotaProviderTest {
    @Test
    fun aCreditsOnlyAccountIsStoredWithoutTryingTheOtherRegion() {
        val requested = mutableListOf<MiniMaxRegion>()
        val provider =
            provider(
                FakeMiniMaxClient { region ->
                    requested += region
                    MiniMaxQuota(region = region, planActive = false, rawJson = "{}")
                },
            )

        provider.refresh()

        assertEquals(listOf(MiniMaxRegion.GLOBAL), requested)
        assertFalse(provider.getLastQuota()!!.planActive)
        assertNull(provider.getLastError())
    }

    @Test
    fun theOtherRegionRejectingTheKeyDoesNotHideTheRealFailure() {
        val provider =
            provider(
                FakeMiniMaxClient { region ->
                    when (region) {
                        MiniMaxRegion.GLOBAL ->
                            throw MiniMaxQuotaException("Request failed (HTTP 503).", 503, "global")
                        MiniMaxRegion.CN ->
                            throw MiniMaxQuotaException(
                                "Session expired. Check your MiniMax subscription key.",
                                401,
                                "cn",
                            )
                    }
                },
            )

        provider.refresh()

        assertEquals("Request failed (HTTP 503).", provider.getLastError())
        assertEquals("global", provider.getLastRawJson())
    }

    @Test
    fun aKeyBothRegionsRejectIsReportedAsRejected() {
        val provider =
            provider(
                FakeMiniMaxClient {
                    throw MiniMaxQuotaException(
                        "Session expired. Check your MiniMax subscription key.",
                        401,
                    )
                },
            )

        provider.refresh()

        assertEquals(
            "Session expired. Check your MiniMax subscription key.",
            provider.getLastError(),
        )
    }

    private fun provider(client: MiniMaxQuotaClient) =
        MiniMaxQuotaProvider(
            client = client,
            settingsProvider = { null },
            apiKeyProvider = { "key" },
        )

    private class FakeMiniMaxClient(private val fetch: (MiniMaxRegion) -> MiniMaxQuota) :
        MiniMaxQuotaClient() {
        override fun fetchQuota(apiKey: String, region: MiniMaxRegion): MiniMaxQuota = fetch(region)
    }
}
