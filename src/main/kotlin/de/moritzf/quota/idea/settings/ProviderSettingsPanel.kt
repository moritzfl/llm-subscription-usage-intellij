package de.moritzf.quota.idea.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.components.BorderLayoutPanel

/**
 * Common surface the settings dialog uses to drive a provider's panel.
 */
internal abstract class ProviderSettingsPanel : BorderLayoutPanel() {
    abstract val hideFromPopupCheckBox: JBCheckBox
    abstract fun updateFields()
    abstract fun updateStatus()
    abstract fun updateResponseArea()
}
