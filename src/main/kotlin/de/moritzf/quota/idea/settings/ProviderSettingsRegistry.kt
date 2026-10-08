package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProviderType
import java.awt.Color
import javax.swing.JComponent

internal data class ProviderSettingsPanelContext(
    val modalityComponentProvider: () -> JComponent?,
    val statusLabelDefaultForeground: Color?,
)

/** Facade over [ProviderCatalog] for settings panel factories. */
internal object ProviderSettingsRegistry {
    val all: Map<QuotaProviderType, (ProviderSettingsPanelContext) -> ProviderSettingsPanel>
        get() = ProviderCatalog.all.associate { it.type to it.settingsPanelFactory }

    fun createPanels(
        context: ProviderSettingsPanelContext
    ): LinkedHashMap<QuotaProviderType, ProviderSettingsPanel> {
        return ProviderCatalog.defaultProviderOrder().associateWithTo(linkedMapOf()) { type ->
            ProviderCatalog.get(type).settingsPanelFactory(context)
        }
    }
}
