package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.IdeProxyBuildContext
import de.moritzf.quota.idea.common.IdeProxyFactories
import de.moritzf.quota.idea.common.ProviderSnapshot
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageSnapshot
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCredits
import de.moritzf.quota.openai.OpenAiExtraRateLimit
import de.moritzf.quota.openai.OpenAiSpendControl
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.shared.ProviderQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountResolverTest {
    @Test
    fun snapshotUpdateKeepsOtherAccountEntries() {
        val first = ProviderSnapshot(exhaustedOpenAi(), null)
        val second = ProviderSnapshot(OpenAiCodexQuota(limitReached = false), null)
        val snapshot = QuotaUsageSnapshot(
            entries = mapOf(QuotaProviderType.OPEN_AI to first),
            accountEntries = mapOf("openai" to first),
            accountTypes = mapOf("openai" to QuotaProviderType.OPEN_AI),
        ).updated("personal", QuotaProviderType.OPEN_AI, second)

        assertEquals(first.quota, snapshot["openai"].quota)
        assertEquals(second.quota, snapshot["personal"].quota)
        assertEquals(second.quota, snapshot[QuotaProviderType.OPEN_AI].quota)
        assertEquals(first.quota, snapshot.forAccount("openai", QuotaProviderType.OPEN_AI).quota)
        assertEquals(second.quota, snapshot.forAccount("personal", QuotaProviderType.OPEN_AI).quota)
    }

    @Test
    fun accountRowDoesNotFallBackToSiblingTypeSnapshot() {
        val first = ProviderSnapshot(exhaustedOpenAi(), null)
        val snapshot = QuotaUsageSnapshot(
            entries = mapOf(QuotaProviderType.OPEN_AI to first),
            accountEntries = mapOf("openai" to first),
            accountTypes = mapOf(
                "openai" to QuotaProviderType.OPEN_AI,
                "personal" to QuotaProviderType.OPEN_AI,
            ),
        )

        assertEquals(null, snapshot.forAccount("personal", QuotaProviderType.OPEN_AI).quota)
        assertEquals(first.quota, snapshot.forAccount("openai", QuotaProviderType.OPEN_AI).quota)
    }

    @Test
    fun pinnedAccountIgnoresFailover() {
        val state = twoOpenAi()
        val exhausted = mapOf("openai" to exhaustedOpenAi())
        val resolved = AccountResolver.resolve(
            QuotaProviderType.OPEN_AI,
            accountParam = "Work",
            capability = AccountCapability.PROXY,
            settings = state,
            quotaLookup = { exhausted[it] },
        )
        assertEquals("openai", resolved.id)
    }

    @Test
    fun unpinnedSpendUsesFailoverWhenDefaultExhausted() {
        val state = twoOpenAi()
        val quotas = mapOf("openai" to exhaustedOpenAi())
        val resolved = AccountResolver.resolve(
            QuotaProviderType.OPEN_AI,
            capability = AccountCapability.PROXY,
            settings = state,
            quotaLookup = { quotas[it] },
        )
        assertEquals("personal", resolved.id)
    }

    @Test
    fun quotaCapabilityDoesNotFailover() {
        val state = twoOpenAi()
        val quotas = mapOf("openai" to exhaustedOpenAi())
        val resolved = AccountResolver.resolve(
            QuotaProviderType.OPEN_AI,
            capability = AccountCapability.QUOTA,
            settings = state,
            quotaLookup = { quotas[it] },
        )
        assertEquals("openai", resolved.id)
    }

    @Test
    fun resolveOrNullWhenNoAccounts() {
        assertEquals(null, AccountResolver.resolveOrNull(QuotaProviderType.OPEN_AI, settings = QuotaSettingsState()))
    }

    @Test
    fun missingPinListsNames() {
        val state = twoOpenAi()
        val error = assertFailsWith<AccountResolveException> {
            AccountResolver.resolve(QuotaProviderType.OPEN_AI, accountParam = "Missing", settings = state)
        }
        assertTrue(error.message!!.contains("Work"))
        assertTrue(error.message!!.contains("Personal"))
    }

    @Test
    fun rateLimitedAccountIsExhaustedUntilCleared() {
        AccountResolver.clearAllRateLimited()
        try {
            val state = twoOpenAi()
            val quotas = emptyMap<String, ProviderQuota>()
            assertFalse(AccountResolver.isExhausted(state.account("openai")!!, { quotas[it] }))
            AccountResolver.markRateLimited("openai")
            assertTrue(AccountResolver.isExhausted(state.account("openai")!!, { quotas[it] }))
            val failedOver = AccountResolver.resolve(
                QuotaProviderType.OPEN_AI,
                capability = AccountCapability.PROXY,
                settings = state,
                quotaLookup = { quotas[it] },
            )
            assertEquals("personal", failedOver.id)
            AccountResolver.clearRateLimited("openai")
            val defaultAgain = AccountResolver.resolve(
                QuotaProviderType.OPEN_AI,
                capability = AccountCapability.PROXY,
                settings = state,
                quotaLookup = { quotas[it] },
            )
            assertEquals("openai", defaultAgain.id)
        } finally {
            AccountResolver.clearAllRateLimited()
        }
    }

    @Test
    fun proxy429MarksResolvedAccountForFailover() {
        AccountResolver.clearAllRateLimited()
        try {
            val settings = twoOpenAi()
            val context = IdeProxyBuildContext(settings = settings, logRequests = false, requestLogDir = "/tmp")
            assertEquals(
                "openai",
                AccountResolver.resolve(
                    QuotaProviderType.OPEN_AI,
                    capability = AccountCapability.PROXY,
                    settings = settings,
                    quotaLookup = { null },
                ).id,
            )
            IdeProxyFactories.noteProxyRateLimit(context, QuotaProviderType.OPEN_AI, 429)
            assertEquals(
                "personal",
                AccountResolver.resolve(
                    QuotaProviderType.OPEN_AI,
                    capability = AccountCapability.PROXY,
                    settings = settings,
                    quotaLookup = { null },
                ).id,
            )
        } finally {
            AccountResolver.clearAllRateLimited()
        }
    }

    @Test
    fun openAiSpendCapIsHardStop() {
        assertTrue(AccountResolver.isHardStop(exhaustedOpenAi()))
        assertFalse(AccountResolver.isHardStop(OpenAiCodexQuota(limitReached = false)))
    }

    @Test
    fun openAiCodexLimitIsNotHardStopWhileReserveRemains() {
        assertFalse(
            AccountResolver.isHardStop(
                OpenAiCodexQuota(
                    limitReached = true,
                    extraRateLimits = listOf(
                        OpenAiExtraRateLimit("gpt-reserve", "GPT Reserve Weekly", UsageWindow(usedPercent = 0.0)),
                    ),
                ),
            ),
        )
        assertTrue(
            AccountResolver.isHardStop(
                OpenAiCodexQuota(
                    limitReached = true,
                    extraRateLimits = listOf(
                        OpenAiExtraRateLimit("gpt-reserve", "GPT Reserve Weekly", UsageWindow(usedPercent = 100.0)),
                    ),
                ),
            ),
        )
        assertTrue(AccountResolver.isHardStop(OpenAiCodexQuota(limitReached = true)))
    }

    @Test
    fun miniMaxWeeklyLimitIsHardStop() {
        assertFalse(
            AccountResolver.isHardStop(
                MiniMaxQuota(sessionUsage = MiniMaxUsageWindow(usagePercent = 40.0)),
            ),
        )
        assertTrue(
            AccountResolver.isHardStop(
                MiniMaxQuota(weeklyUsage = MiniMaxUsageWindow(usagePercent = 100.0)),
            ),
        )
        assertTrue(
            AccountResolver.isHardStop(
                MiniMaxQuota(sessionUsage = MiniMaxUsageWindow(usagePercent = 100.0)),
            ),
        )
    }

    private fun twoOpenAi(): QuotaSettingsState {
        return QuotaSettingsState().apply {
            accounts = mutableListOf(
                ProviderAccount(id = "openai", typeId = "openai", name = "Work", isDefault = true),
                ProviderAccount(id = "personal", typeId = "openai", name = "Personal", allowFailover = true),
            )
        }
    }

    private fun exhaustedOpenAi(): ProviderQuota {
        return OpenAiCodexQuota(
            limitReached = false,
            credits = OpenAiCredits(hasCredits = false),
            spendControl = OpenAiSpendControl(reached = true, individualLimit = 10.0, used = 10.0),
        )
    }
}
