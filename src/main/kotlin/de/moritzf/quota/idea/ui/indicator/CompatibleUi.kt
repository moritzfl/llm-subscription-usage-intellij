package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.compatible.CompatibleApiKeyStore
import de.moritzf.quota.idea.compatible.compatibleEndpoint
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.ProviderQuota

internal object CompatibleUi : ProviderUi {
    override val type = QuotaProviderType.OPENAI_COMPATIBLE
    override val icon
        get() = QuotaIcons.COMPATIBLE

    override fun barText(quota: ProviderQuota?, error: String?) = "—"

    override fun displayPercent(quota: ProviderQuota?, error: String?) = -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?): Double? = null

    override fun createPopupSection() = null

    override fun authState(accountId: String): ProviderAuthState {
        val store = CompatibleApiKeyStore.forAccount(accountId)
        val key = store.load()
        return when {
            !store.isLoaded -> ProviderAuthState.UNKNOWN
            key != null &&
                compatibleEndpoint(QuotaSettingsState.getInstance().account(accountId)) != null ->
                ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }
}
