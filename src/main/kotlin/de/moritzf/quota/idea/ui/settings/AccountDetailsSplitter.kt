package de.moritzf.quota.idea.ui.settings

import com.intellij.openapi.ui.Splitter
import com.intellij.ui.OnePixelSplitter
import javax.swing.JComponent

internal class AccountDetailsSplitter(accounts: JComponent, detail: JComponent) :
    OnePixelSplitter(false) {
    init {
        // KEEP_FIRST_SIZE only runs when BOTH panes already have non-empty bounds.
        // Seed both before the first layout, otherwise the default 50/50 split wins.
        firstComponent = accounts.apply { size = preferredSize }
        secondComponent = detail.apply { size = preferredSize }
        dividerPositionStrategy = Splitter.DividerPositionStrategy.KEEP_FIRST_SIZE
        setHonorComponentsMinimumSize(false)
    }
}
