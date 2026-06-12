package de.moritzf.quota.idea.ui.popup

import com.intellij.openapi.ui.VerticalFlowLayout
import de.moritzf.quota.shared.ProviderQuota
import javax.swing.JPanel

/**
 * One provider's block in the quota popup.
 */
internal abstract class ProviderPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    abstract fun update(quota: ProviderQuota?, error: String?, visible: Boolean)
}
