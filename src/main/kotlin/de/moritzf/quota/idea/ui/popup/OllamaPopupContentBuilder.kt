package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.ollama.OllamaQuota
import javax.swing.JComponent

private const val OLLAMA_LABEL = "Ollama Cloud"

internal fun buildOllamaPopupContent(
    quota: OllamaQuota?,
    error: String?,
    showOllamaSection: Boolean,
): List<JComponent> {
    if (!showOllamaSection) {
        return emptyList()
    }

    val components = mutableListOf<JComponent>()
    components.add(createSeparatedBlock())

    when {
        error != null -> {
            components.add(withVerticalInsets(createWarningLabel("Ollama error: $error"), top = 1))
        }

        quota == null -> {
            components.add(withVerticalInsets(createSectionTitleLabel(OLLAMA_LABEL, QuotaIcons.OLLAMA), top = 0))
            components.add(createLoadingWindowBlock("Session", top = 3))
            components.add(createLoadingWindowBlock("Weekly", top = 5))
        }

        else -> {
            val planTitle = quota.plan.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            val label = if (planTitle != null) "$OLLAMA_LABEL ($planTitle)" else OLLAMA_LABEL
            components.add(withVerticalInsets(createSectionTitleLabel(label, QuotaIcons.OLLAMA), top = 0))
            quota.sessionUsage?.let {
                components.add(createOllamaWindowBlock(it, "Session", top = 3))
            }
            quota.weeklyUsage?.let {
                components.add(createOllamaWindowBlock(it, "Weekly", top = 5))
            }
        }
    }

    return components
}
