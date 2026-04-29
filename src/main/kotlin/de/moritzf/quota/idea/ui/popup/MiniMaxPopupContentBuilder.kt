package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.minimax.MiniMaxQuota
import javax.swing.JComponent

internal fun buildMiniMaxPopupContent(
    quota: MiniMaxQuota?,
    error: String?,
    showSection: Boolean,
): List<JComponent> {
    if (!showSection) return emptyList()
    val components = mutableListOf<JComponent>()
    components.add(createSeparatedBlock())
    when {
        error != null -> components.add(withVerticalInsets(createWarningLabel("MiniMax error: $error"), top = 1))
        quota == null -> {
            components.add(withVerticalInsets(createSectionTitleLabel("MiniMax", QuotaIcons.MINIMAX), top = 0))
            components.add(createLoadingWindowBlock("Session", top = 3))
        }
        else -> {
            val label = quota.plan.ifBlank { "MiniMax Coding Plan (${quota.region})" }
            components.add(withVerticalInsets(createSectionTitleLabel(label, QuotaIcons.MINIMAX), top = 0))
            quota.sessionUsage?.let { components.add(createMiniMaxWindowBlock(it, "Session", top = 3)) }
        }
    }
    return components
}
