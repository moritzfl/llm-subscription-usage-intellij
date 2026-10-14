package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.litellm.LiteLlmQuota
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class LiteLlmPopupSection : ProviderPopupSection() {
    private val title = createSectionTitleLabel("LiteLLM", QuotaIcons.LITELLM)
    private val warning = createWarningLabel("")
    private val budget = WindowBlockPanel(3)

    init {
        isOpaque = false
        add(createSeparatedBlock())
        add(title)
        add(warning)
        add(budget)
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return
        title.text = sectionTitle("LiteLLM")
        warning.text = error.orEmpty()
        warning.isVisible = error != null
        val value = quota as? LiteLlmQuota
        if (value == null)
            budget.showUnavailable(
                "Key budget",
                if (error == null) "Waiting for key usage…" else "Unavailable",
            )
        else {
            val reset = QuotaUiUtil.formatReset(value.resetsAt)
            val info = value.budgetText() + (reset?.let { " · $it" } ?: "")
            val fraction = value.usageFraction()
            if (fraction == null) budget.showUnavailable("Key budget", info)
            else
                budget.update(
                    "Key budget",
                    info,
                    (fraction * 100).roundToInt(),
                    detailTooltip = "Virtual key budget only. Team/user limits may also apply.",
                )
        }
    }
}
