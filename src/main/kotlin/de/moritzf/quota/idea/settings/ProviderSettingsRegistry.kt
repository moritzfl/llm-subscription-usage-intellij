package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderRegistry
import de.moritzf.quota.idea.common.QuotaProviderType
import java.awt.Color
import javax.swing.JComponent

internal data class ProviderSettingsPanelContext(
    val modalityComponentProvider: () -> JComponent?,
    val statusLabelDefaultForeground: Color?,
)

internal object ProviderSettingsRegistry {
    val all: Map<QuotaProviderType, (ProviderSettingsPanelContext) -> ProviderSettingsPanel> = mapOf(
        QuotaProviderType.CLAUDE to { context ->
            ClaudeSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.CURSOR to { context ->
            CursorSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.GITHUB to { context ->
            GitHubSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.KIMI to { context ->
            KimiSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.MINIMAX to { context ->
            MiniMaxSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.OLLAMA to { context ->
            OllamaSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.OPEN_AI to { context ->
            OpenAiSettingsPanel(context.modalityComponentProvider)
        },
        QuotaProviderType.OPEN_CODE to { context ->
            OpenCodeSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.SUPERGROK to { context ->
            SuperGrokSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
        QuotaProviderType.ZAI to { context ->
            ZaiSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
        },
    )

    fun createPanels(context: ProviderSettingsPanelContext): LinkedHashMap<QuotaProviderType, ProviderSettingsPanel> {
        return QuotaProviderRegistry.defaultProviderOrder().associateWithTo(linkedMapOf()) { type ->
            all.getValue(type)(context)
        }
    }
}
