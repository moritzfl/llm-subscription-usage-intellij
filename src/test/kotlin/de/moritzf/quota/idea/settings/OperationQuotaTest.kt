package de.moritzf.quota.idea.settings

import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.cursor.CursorPlanUsage
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorRequestUsage
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.github.GitHubUsageWindow
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.kimi.KimiUsageWindow
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
    fun copilotNativeCompletionsIgnoreChatCreditsButHonorCompletionQuota() {
        val quota =
            GitHubQuota(
                premiumInteractions = GitHubUsageWindow("AI credits", usagePercent = 100.0),
                chat = GitHubUsageWindow("Chat", usagePercent = 100.0),
                completions = GitHubUsageWindow("Completions", unlimited = true),
            )
        assertFalse(
            OperationQuota.status(
                    quota,
                    AccountCapability.PROXY,
                    proxyRoute = SubscriptionProxyRoute.COMPLETIONS,
                )
                .exhausted
        )
        assertTrue(
            OperationQuota.status(
                    quota,
                    AccountCapability.PROXY,
                    proxyRoute = SubscriptionProxyRoute.CHAT_COMPLETIONS,
                )
                .exhausted
        )
        val free =
            quota.copy(
                premiumInteractions = null,
                chat = GitHubUsageWindow("Chat", usagePercent = 0.0),
                completions = GitHubUsageWindow("Completions", usagePercent = 100.0),
            )
        val status =
            OperationQuota.status(
                free,
                AccountCapability.PROXY,
                proxyRoute = SubscriptionProxyRoute.COMPLETIONS,
            )
        assertTrue(status.exhausted)
        assertEquals("Completions", status.limitingPool)
        assertFalse(
            OperationQuota.status(
                    free,
                    AccountCapability.PROXY,
                    proxyRoute = SubscriptionProxyRoute.CHAT_COMPLETIONS,
                )
                .exhausted
        )
    }

    @Test
    fun cursorKeepsAccountAvailableUntilBothModelPoolsAreExhausted() {
        val quota =
            CursorQuota(
                planUsage =
                    CursorPlanUsage(
                        autoPercentUsed = 100.0,
                        apiPercentUsed = 25.0,
                        totalPercentUsed = 100.0,
                    ),
                requestUsage = CursorRequestUsage(used = 500, limit = 500),
            )
        val status = OperationQuota.status(quota, AccountCapability.PROXY)
        assertFalse(status.exhausted)
        assertEquals("Other Models", status.limitingPool)
        assertEquals(25.0, status.usagePercent)

        val exhausted = quota.copy(planUsage = quota.planUsage!!.copy(apiPercentUsed = 100.0))
        assertTrue(OperationQuota.status(exhausted, AccountCapability.PROXY).exhausted)
    }

    @Test
    fun openCodeGoLimitBillsTheBalanceWhileCreditLasts() {
        val limited =
            de.moritzf.quota.opencode.OpenCodeQuota(
                rollingUsage =
                    de.moritzf.quota.opencode.OpenCodeUsageWindow("rate-limited", 60, 100.0),
                weeklyUsage = de.moritzf.quota.opencode.OpenCodeUsageWindow(usagePercent = 40.0),
                useBalance = true,
                availableCredit = 5_000_000,
            )

        val billed = OperationQuota.status(limited)
        assertFalse(billed.exhausted)
        assertEquals("5-hour", billed.limitingPool)
        assertTrue(OperationQuota.status(limited.copy(availableCredit = 0)).exhausted)
        assertTrue(OperationQuota.status(limited.copy(useBalance = false)).exhausted)
    }

    @Test
    fun kimiMonthlyExhaustionIsHardStopEvenWithSessionRemaining() {
        val quota =
            KimiQuota(
                sessionUsage = KimiUsageWindow(usagePercent = 25.0),
                monthlyUsage = KimiUsageWindow(usagePercent = 100.0),
            )
        val status = OperationQuota.status(quota, AccountCapability.PROXY)

        assertTrue(status.exhausted)
        assertEquals("monthly", status.limitingPool)
        assertTrue(AccountResolver.isHardStop(quota, AccountCapability.PROXY))
    }

    @Test
    fun ollamaPurchasedCreditsKeepTheAccountAvailableAfterIncludedCredits() {
        val monthly = de.moritzf.quota.ollama.OllamaUsageWindow(usagePercent = 100.0)
        val withCredits =
            de.moritzf.quota.ollama.OllamaQuota(monthlyUsage = monthly, purchasedBalanceUsd = 5.0)
        val without =
            de.moritzf.quota.ollama.OllamaQuota(monthlyUsage = monthly, purchasedBalanceUsd = 0.0)

        assertFalse(OperationQuota.status(withCredits, AccountCapability.PROXY).exhausted)
        assertTrue(OperationQuota.status(without, AccountCapability.PROXY).exhausted)
    }

    @Test
    fun copilotAdditionalBudgetKeepsIndividualPlansAvailable() {
        fun quota(plan: String, overageUsed: Double = 10.0) =
            de.moritzf.quota.github.GitHubQuota(
                plan = plan,
                premiumInteractions =
                    de.moritzf.quota.github.GitHubUsageWindow(
                        label = "AI credits",
                        usagePercent = 100.0,
                        overagePermitted = true,
                        overageUsed = overageUsed,
                        overageLimit = 1000.0,
                    ),
            )

        assertFalse(OperationQuota.status(quota("Copilot Pro"), AccountCapability.PROXY).exhausted)
        assertTrue(
            OperationQuota.status(quota("Copilot Pro", 1000.0), AccountCapability.PROXY).exhausted
        )
        assertTrue(
            OperationQuota.status(quota("Copilot Business"), AccountCapability.PROXY).exhausted,
            "organizations stop at the included amount",
        )
    }

    @Test
    fun zaiWebSearchUsesSearchPoolNotTokenWindows() {
        val quota =
            ZaiQuota(
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
        val quota =
            OpenAiCodexQuota(
                limitReached = true,
                extraRateLimits =
                    listOf(
                        OpenAiExtraRateLimit(
                            "gpt-reserve",
                            "GPT Reserve Weekly",
                            UsageWindow(usedPercent = 0.0),
                        )
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
