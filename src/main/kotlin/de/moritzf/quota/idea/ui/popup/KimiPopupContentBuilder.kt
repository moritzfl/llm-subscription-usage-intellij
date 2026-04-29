package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.kimi.KimiQuota
import javax.swing.JComponent

internal fun buildKimiPopupContent(
    quota: KimiQuota?,
    error: String?,
    showSection: Boolean,
): List<JComponent> {
    if (!showSection) return emptyList()
    val components = mutableListOf<JComponent>()
    components.add(createSeparatedBlock())
    when {
        error != null -> components.add(withVerticalInsets(createWarningLabel("Kimi error: $error"), top = 1))
        quota == null -> {
            components.add(withVerticalInsets(createSectionTitleLabel("Kimi Code", QuotaIcons.KIMI), top = 0))
            components.add(createLoadingWindowBlock("Session", top = 3))
        }
        else -> {
            components.add(withVerticalInsets(createSectionTitleLabel(quota.plan.ifBlank { "Kimi Code" }, QuotaIcons.KIMI), top = 0))
            quota.sessionUsage?.let { components.add(createKimiWindowBlock(it, "Session", top = 3)) }
            quota.totalUsage?.let { components.add(createKimiWindowBlock(it, "Overall", top = 5)) }
        }
    }
    return components
}
