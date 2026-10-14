package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.popup.LiteLlmPopupSection
import de.moritzf.quota.litellm.LiteLlmQuota
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal object LiteLlmUi : ProviderUi {
    override val type = QuotaProviderType.LITELLM
    override val icon
        get() = QuotaIcons.LITELLM

    override fun barText(quota: ProviderQuota?, error: String?) =
        quota?.usageFraction()?.let { "${(it * 100).roundToInt()}%" } ?: "—"

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        quota?.usageFraction()?.let { (it * 100).roundToInt() } ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?): Double? = null

    override fun authState(accountId: String) = CompatibleUi.authState(accountId)

    override fun tooltip(quota: ProviderQuota?, error: String?, accountId: String): String =
        "LiteLLM: ${error ?: (quota as? LiteLlmQuota)?.budgetText() ?: "Key budget unavailable"}"

    override fun createPopupSection() = LiteLlmPopupSection()
}
