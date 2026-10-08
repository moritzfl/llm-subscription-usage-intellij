package de.moritzf.quota.idea.ui.popup

import com.intellij.openapi.ui.VerticalFlowLayout
import de.moritzf.quota.shared.ProviderQuota
import javax.swing.JPanel

/** One provider's block in the quota popup. */
internal abstract class ProviderPopupSection :
    JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    var accountId: String? = null
    var accountTitle: String? = null

    abstract fun update(quota: ProviderQuota?, error: String?, visible: Boolean)

    protected fun sectionTitle(base: String, plan: String? = null): String {
        val account = accountTitle?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            account != null -> "$base ($account)"
            !plan.isNullOrBlank() -> "$base ($plan)"
            else -> base
        }
    }
}
