package de.moritzf.quota.idea.settings

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService

/** Owns configuration commit and its follow-up actions; UI widgets only build drafts. */
internal class SettingsApplyCoordinator(
    private val state: QuotaSettingsState,
    private val clearSecrets: (ProviderAccount) -> Unit = AccountSecrets::clear,
    private val syncAccounts: () -> Unit = { QuotaUsageService.getInstance().syncAccounts() },
    private val clearUsage: (String) -> Unit = {
        QuotaUsageService.getInstance().clearUsageData(it)
    },
    private val refresh: (String) -> Unit = {
        QuotaUsageService.getInstance().refreshAsync(it, forceUpdate = true)
        Unit
    },
    private val publish: () -> Unit = {
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(QuotaSettingsListener.TOPIC)
            .onSettingsChanged()
        ActivityTracker.getInstance().inc()
    },
) {
    fun apply(
        draft: QuotaSettingsDraft,
        removed: List<ProviderAccount> = emptyList(),
        credentialsChanged: Boolean = false,
        saveCredentials: () -> Unit = {},
    ): Boolean {
        draft.validate()
        val before = QuotaSettingsDraft.from(state)
        val kept = draft.accounts.map { it.id }.toSet()
        val deleted =
            (removed + before.accounts.filter { it.id !in kept })
                .filter { it.id !in kept }
                .distinctBy { it.id }
        if (before == draft && !credentialsChanged && deleted.isEmpty()) return false
        if (credentialsChanged) saveCredentials()
        deleted.forEach(clearSecrets)
        draft.applyTo(state)
        deleted.forEach { clearUsage(it.id) }
        syncAccounts()
        // Invalidate workers before pruning, so late results cannot restore removed entries.
        deleted.forEach { state.dropAccountData(it.id) }
        state.pruneOrphanAccountData()
        for (account in draft.accounts) {
            val old = before.accounts.firstOrNull { it.id == account.id } ?: continue
            if (old.extras == account.extras) continue
            when (account.providerType()) {
                QuotaProviderType.AZURE,
                QuotaProviderType.ANTIGRAVITY -> {
                    clearUsage(account.id)
                    refresh(account.id)
                }
                QuotaProviderType.OLLAMA ->
                    if (
                        old.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET) !=
                            account.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET)
                    )
                        refresh(account.id)
                else -> Unit
            }
        }
        publish()
        return true
    }
}
