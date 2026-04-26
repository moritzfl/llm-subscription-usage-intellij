package de.moritzf.quota.idea.ui.popup

import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.opencode.OpenCodeQuota
import javax.swing.JComponent

private const val OPENCODE_GO_LABEL = "OpenCode Go"

internal fun buildOpenCodePopupContent(
    quota: OpenCodeQuota?,
    error: String?,
    showOpenCodeSection: Boolean,
): List<JComponent> {
    if (!showOpenCodeSection) {
        return emptyList()
    }

    val components = mutableListOf<JComponent>()
    components.add(createSeparatedBlock())

    when {
        error != null -> {
            components.add(withVerticalInsets(createWarningLabel("OpenCode error: $error"), top = 1))
        }

        quota == null -> {
            components.add(withVerticalInsets(JBLabel("Loading OpenCode usage..."), top = 1))
        }

        else -> {
            components.add(withVerticalInsets(createSectionTitleLabel(OPENCODE_GO_LABEL, QuotaIcons.OPENCODE), top = 0))
            val balanceText = quota.availableBalance?.let(QuotaUiUtil::formatOpenCodeBalance)
            if (balanceText != null) {
                components.add(withVerticalInsets(createMutedLabel("Available balance: $$balanceText"), top = 2))
            }
            quota.rollingUsage?.let {
                components.add(createOpenCodeWindowBlock(it, "5h rolling", top = 3))
            }
            quota.weeklyUsage?.let {
                components.add(createOpenCodeWindowBlock(it, "Weekly", top = 5))
            }
            quota.monthlyUsage?.let {
                components.add(createOpenCodeWindowBlock(it, "Monthly", top = 5))
            }
        }
    }

    return components
}
