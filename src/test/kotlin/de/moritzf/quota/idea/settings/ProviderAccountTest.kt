package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProviderAccountTest {
    @Test
    fun antigravityFollowsOnlyOneCliAccount() {
        val state = QuotaSettingsState()
        val account = state.addAccount(QuotaProviderType.ANTIGRAVITY)
        account.setExtra(ProviderAccount.EXTRA_AGY_EXECUTABLE, "/opt/agy")
        assertFailsWith<IllegalArgumentException> { state.addAccount(QuotaProviderType.ANTIGRAVITY) }
        assertEquals(listOf(account), state.accounts)

        val restored = QuotaSettingsState.sanitizeAccounts(listOf(account, account.copy(id = "second")))
        assertEquals(listOf(account), restored)
        assertFalse(restored.single().allowFailover)
        assertFalse(TypeHasStoredCredentials(QuotaProviderType.ANTIGRAVITY))
    }

    @Test
    fun extraGithubHostDoesNotOverwriteFirstAccount() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.GITHUB)
        val second = state.addAccount(QuotaProviderType.GITHUB)
        state.setGithubHostFor(first.id, "github.com")
        state.setGithubHostFor(second.id, "github.example.com")

        assertEquals("github.com", state.githubEnterpriseHost)
        assertEquals("github.com", state.githubHostFor(first.id))
        assertEquals("github.example.com", state.githubHostFor(second.id))
    }

    @Test
    fun extraMiniMaxRegionDoesNotOverwriteFirstAccount() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.MINIMAX)
        val second = state.addAccount(QuotaProviderType.MINIMAX)
        state.setMiniMaxRegionFor(first.id, de.moritzf.quota.minimax.MiniMaxRegionPreference.GLOBAL)
        state.setMiniMaxRegionFor(second.id, de.moritzf.quota.minimax.MiniMaxRegionPreference.CN)

        assertEquals(de.moritzf.quota.minimax.MiniMaxRegionPreference.GLOBAL, state.miniMaxRegionFor(first.id))
        assertEquals(de.moritzf.quota.minimax.MiniMaxRegionPreference.CN, state.miniMaxRegionFor(second.id))
        assertEquals(de.moritzf.quota.minimax.MiniMaxRegionPreference.GLOBAL.name, state.minimaxRegionPreference)
    }

    @Test
    fun extraAccountUsesDistinctPasswordSafeServiceName() {
        val first = AccountCredentialKeys.userName("ollama", "ollama", "ollama-api-key")
        val second = AccountCredentialKeys.userName("uuid-2", "ollama", "ollama-api-key")
        assertEquals("ollama-api-key", first)
        assertEquals("ollama-api-key-uuid-2", second)
    }

    @Test
    fun firstAccountOfTypeUsesTypeIdAndIsDefault() {
        val state = QuotaSettingsState()
        val created = state.addAccount(QuotaProviderType.OPEN_AI)

        assertEquals(QuotaProviderType.OPEN_AI.id, created.id)
        assertEquals("OpenAI", created.name)
        assertTrue(created.isDefault)
        assertFalse(created.allowFailover)
    }

    @Test
    fun secondAccountOfTypeGetsUniqueNameAndIsNotDefault() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.OPEN_AI)
        val second = state.addAccount(QuotaProviderType.OPEN_AI)

        assertEquals("OpenAI", first.name)
        assertEquals("OpenAI 2", second.name)
        assertNotEquals(first.id, second.id)
        assertTrue(first.isDefault)
        assertFalse(second.isDefault)
        assertEquals("OpenAI (OpenAI)", state.accountListLabel(first))
        assertEquals("OpenAI (OpenAI 2)", state.accountListLabel(second))
    }

    @Test
    fun sameNameOnDifferentTypesIsAllowed() {
        val state = QuotaSettingsState()
        val openAi = state.addAccount(QuotaProviderType.OPEN_AI)
        val claude = state.addAccount(QuotaProviderType.CLAUDE)
        openAi.name = "Work"
        claude.name = "Work"

        assertFalse(state.hasDuplicateAccountNames())
        assertEquals("OpenAI", state.accountDisplayName(openAi))
        assertEquals("Claude", state.accountDisplayName(claude))
    }

    @Test
    fun duplicateNameOnSameTypeBlocksApply() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.OPEN_AI)
        val second = state.addAccount(QuotaProviderType.OPEN_AI)
        second.name = first.name

        assertTrue(state.duplicateAccountName(second))
        assertTrue(state.hasDuplicateAccountNames())
    }

    @Test
    fun blankNameIsDuplicate() {
        val state = QuotaSettingsState()
        val account = state.addAccount(QuotaProviderType.OPEN_AI)
        account.name = "  "

        assertTrue(state.duplicateAccountName(account))
    }

    @Test
    fun standbyForOthersTogglesAllNonDefaultAccounts() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.OPEN_AI)
        val second = state.addAccount(QuotaProviderType.OPEN_AI)
        val third = state.addAccount(QuotaProviderType.OPEN_AI)

        assertFalse(state.standbyForOthers(QuotaProviderType.OPEN_AI))

        state.setStandbyForOthers(QuotaProviderType.OPEN_AI, true)
        assertFalse(first.allowFailover)
        assertTrue(second.allowFailover)
        assertTrue(third.allowFailover)
        assertTrue(state.standbyForOthers(QuotaProviderType.OPEN_AI))

        state.setDefaultAccount(second.id)
        assertFalse(second.allowFailover)
        assertTrue(state.standbyForOthers(QuotaProviderType.OPEN_AI))

        state.setStandbyForOthers(QuotaProviderType.OPEN_AI, false)
        assertFalse(first.allowFailover)
        assertFalse(second.allowFailover)
        assertFalse(third.allowFailover)
    }

    @Test
    fun setDefaultClearsSiblingsAndFailover() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.OPEN_AI)
        val second = state.addAccount(QuotaProviderType.OPEN_AI)
        second.allowFailover = true

        state.setDefaultAccount(second.id)

        assertFalse(first.isDefault)
        assertTrue(second.isDefault)
        assertFalse(second.allowFailover)
    }

    @Test
    fun sanitizeKeepsUnknownTypeIds() {
        val kept = QuotaSettingsState.sanitizeAccounts(
            listOf(
                ProviderAccount(id = "x", typeId = "future-provider", name = "Future"),
                ProviderAccount(id = "openai", typeId = "openai", name = "OpenAI", isDefault = true),
            ),
        )
        assertEquals(listOf("x", "openai"), kept.map { it.id })
    }

    @Test
    fun pruneDropsCacheTimestampAndLastActiveForRemovedAccount() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.OPEN_AI)
        val second = state.addAccount(QuotaProviderType.OPEN_AI)
        state.setCachedQuotaJson(second.id, "{}")
        state.lastProviderUpdates[second.id] = 1L
        state.setLastActiveAccount(second.id)

        state.removeAccount(second.id)
        state.dropAccountData(second.id)
        state.pruneOrphanAccountData()

        assertEquals(null, state.cachedQuotaJson(second.id))
        assertEquals(0L, state.lastUpdate(second.id))
        assertEquals(null, state.lastActiveAccount())
        assertEquals(first.id, state.accounts.single().id)
    }

    @Test
    fun lastUsedSourceCountsExtraAccountTimestamps() {
        val state = QuotaSettingsState()
        val openAi = state.addAccount(QuotaProviderType.OPEN_AI)
        val extra = state.addAccount(QuotaProviderType.OPEN_AI)
        val claude = state.addAccount(QuotaProviderType.CLAUDE)
        state.lastProviderUpdates[openAi.id] = 1L
        state.lastProviderUpdates[claude.id] = 5L
        state.lastProviderUpdates[extra.id] = 10L

        assertEquals(de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource.OPEN_AI, state.lastUsedSource())
    }

    @Test
    fun removeDefaultPromotesNextInList() {
        val state = QuotaSettingsState()
        val first = state.addAccount(QuotaProviderType.OPEN_AI)
        val second = state.addAccount(QuotaProviderType.OPEN_AI)
        second.allowFailover = true

        state.removeAccount(first.id)

        assertEquals(listOf(second.id), state.accounts.map { it.id })
        assertTrue(second.isDefault)
        assertFalse(second.allowFailover)
    }
}
