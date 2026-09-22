package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.UsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class OpenAiQuotaProviderTest {
    @Test
    fun refreshKeepsLoginAndLastQuotaWhenTokenIsTemporarilyUnavailable() {
        val quota = OpenAiCodexQuota(allowed = true).apply { rawJson = "{\"ok\":true}" }
        var token: String? = "token"
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> quota },
            accessTokenProvider = { token },
            accountIdProvider = { "account-1" },
            tokenRefresher = { null },
            connectionStateProvider = { OAuthConnectionState.TEMPORARY_FAILURE },
        )

        provider.refresh()
        token = null
        provider.refresh()

        assertSame(quota, provider.getLastQuota(), "a failed refresh must not drop the last reading")
        assertTrue(provider.isLastErrorTransient())
        assertEquals(
            "OpenAI token could not be refreshed. Trying again with the next update.",
            provider.getLastError(),
        )
    }

    @Test
    fun refreshClearsDataWhenNotLoggedIn() {
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> error("must not be called") },
            accessTokenProvider = { null },
            accountIdProvider = { null },
            tokenRefresher = { null },
            connectionStateProvider = { OAuthConnectionState.LOGGED_OUT },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals("Not logged in", provider.getLastError())
    }

    @Test
    fun forceRefreshSkipsHysteresisAfterReset() {
        val resetsAt = Clock.System.now() + 1.days
        var percent = 100.0
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(limitReached = percent >= 100.0).apply {
                    primary = UsageWindow(usedPercent = percent, resetsAt = resetsAt)
                }
            },
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
        )

        provider.refresh()
        percent = 99.2
        provider.refresh()
        assertEquals(100.0, provider.getLastQuota()!!.primary!!.usedPercent)

        provider.refresh(forceUpdate = true)
        assertEquals(99.2, provider.getLastQuota()!!.primary!!.usedPercent)
    }
}
