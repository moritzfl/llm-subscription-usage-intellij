package de.moritzf.quota.idea.settings

import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralUsageWindow
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiExtraRateLimit
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.zai.ZaiCountUsageWindow
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.zai.ZaiUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationQuotaTest {
    @Test
    fun zaiWebSearchUsesSearchPoolNotTokenWindows() {
        val quota = ZaiQuota(
            sessionUsage = ZaiUsageWindow(usagePercent = 100.0),
            weeklyUsage = ZaiUsageWindow(usagePercent = 100.0),
            webSearchUsage = ZaiCountUsageWindow(used = 1, limit = 10, usagePercent = 10.0),
        )

        val search = OperationQuota.status(quota, AccountCapability.WEB_SEARCH)
        assertFalse(search.exhausted)
        assertEquals("web_search", search.limitingPool)

        val proxy = OperationQuota.status(quota, AccountCapability.PROXY)
        assertTrue(proxy.exhausted)
        assertEquals("session", proxy.limitingPool)
    }

    @Test
    fun mistralMediaIgnoresVibeQuota() {
        val quota = MistralQuota(monthlyUsage = MistralUsageWindow(usagePercent = 100.0))

        assertTrue(OperationQuota.status(quota, AccountCapability.PROXY).exhausted)
        assertFalse(OperationQuota.status(quota, AccountCapability.IMAGE_GENERATION).exhausted)
        assertFalse(OperationQuota.status(quota, AccountCapability.SPEECH_TO_TEXT).exhausted)
    }

    @Test
    fun openAiNonLunaModelIsExhaustedWhenMainLimitReachedEvenWithReserve() {
        val quota = OpenAiCodexQuota(
            limitReached = true,
            extraRateLimits = listOf(
                OpenAiExtraRateLimit("gpt-reserve", "GPT Reserve Weekly", UsageWindow(usedPercent = 0.0)),
            ),
        )

        assertFalse(AccountResolver.isHardStop(quota))
        assertFalse(AccountResolver.isHardStop(quota, AccountCapability.PROXY, "gpt-6-luna"))
        assertFalse(AccountResolver.isHardStop(quota, AccountCapability.PROXY, "gpt-5.6-luna"))
        assertTrue(AccountResolver.isHardStop(quota, AccountCapability.PROXY, "gpt-6-sol"))
        assertTrue(AccountResolver.isHardStop(quota, AccountCapability.PROXY, "gpt-5.5"))
    }

    @Test
    fun miniMaxWeeklyExhaustionIsHardStop() {
        val quota = MiniMaxQuota(weeklyUsage = MiniMaxUsageWindow(usagePercent = 100.0))
        val status = OperationQuota.status(quota, AccountCapability.PROXY)
        assertTrue(status.exhausted)
        assertEquals("weekly", status.limitingPool)
    }
}
