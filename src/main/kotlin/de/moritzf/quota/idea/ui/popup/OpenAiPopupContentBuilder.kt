package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.openai.OpenAiCodexQuota
import javax.swing.JComponent

internal fun buildOpenAiPopupContent(
    quota: OpenAiCodexQuota?,
    error: String?,
    showCodexSection: Boolean,
    hasReviewData: Boolean,
): List<JComponent> {
    if (!showCodexSection) {
        return emptyList()
    }

    val components = mutableListOf<JComponent>()

    when {
        error != null -> {
            components.add(withVerticalInsets(createWarningLabel("Codex error: $error"), top = 1))
        }

        quota == null -> {
            components.add(withVerticalInsets(javax.swing.JLabel("Loading Codex usage..."), top = 1))
        }

        else -> {
            val limitWarning = getLimitWarning(quota)
            if (limitWarning != null) {
                components.add(withVerticalInsets(createWarningLabel(limitWarning), top = 1))
                components.add(createSeparatedBlock())
            }

            if (quota.primary != null || quota.secondary != null) {
                components.add(withVerticalInsets(createSectionTitleLabel("Codex", QuotaIcons.OPENAI), top = 0))
                val planLabel = quota.planType?.toDisplayLabel()
                if (!planLabel.isNullOrBlank()) {
                    components.add(withVerticalInsets(createMutedLabel("OpenAI Plan: $planLabel"), top = 2))
                }
                quota.primary?.let { components.add(createWindowBlock(it, "Primary", top = 3)) }
                quota.secondary?.let { components.add(createWindowBlock(it, "Secondary", top = 5)) }
            }

            if (hasReviewData) {
                components.add(createSeparatedBlock())
                components.add(withVerticalInsets(createSectionTitleLabel("Code Review", QuotaIcons.OPENAI), top = 0))
                quota.reviewPrimary?.let { components.add(createWindowBlock(it, "Primary", top = 3)) }
                quota.reviewSecondary?.let { components.add(createWindowBlock(it, "Secondary", top = 5)) }
            }
        }
    }

    return components
}
