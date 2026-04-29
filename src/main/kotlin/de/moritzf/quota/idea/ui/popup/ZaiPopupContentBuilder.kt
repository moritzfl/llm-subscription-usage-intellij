package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.zai.ZaiQuota
import javax.swing.JComponent

private const val ZAI_LABEL = "Z.ai"

internal fun buildZaiPopupContent(
    quota: ZaiQuota?,
    error: String?,
    showZaiSection: Boolean,
): List<JComponent> {
    if (!showZaiSection) {
        return emptyList()
    }

    val components = mutableListOf<JComponent>()
    components.add(createSeparatedBlock())

    when {
        error != null -> {
            components.add(withVerticalInsets(createWarningLabel("Z.ai error: $error"), top = 1))
        }

        quota == null -> {
            components.add(withVerticalInsets(createSectionTitleLabel(ZAI_LABEL, QuotaIcons.ZAI), top = 0))
            components.add(createLoadingWindowBlock("Session", top = 3))
            components.add(createLoadingWindowBlock("Weekly", top = 5))
            components.add(createLoadingWindowBlock("Web searches", top = 5))
        }

        else -> {
            val label = quota.plan.takeIf { it.isNotBlank() }?.let { "$ZAI_LABEL ($it)" } ?: ZAI_LABEL
            components.add(withVerticalInsets(createSectionTitleLabel(label, QuotaIcons.ZAI), top = 0))
            quota.sessionUsage?.let {
                components.add(createZaiWindowBlock(it, "Session", top = 3))
            }
            quota.weeklyUsage?.let {
                components.add(createZaiWindowBlock(it, "Weekly", top = 5))
            }
            quota.webSearchUsage?.let {
                components.add(createZaiCountWindowBlock(it, "Web searches", top = 5))
            }
        }
    }

    return components
}
