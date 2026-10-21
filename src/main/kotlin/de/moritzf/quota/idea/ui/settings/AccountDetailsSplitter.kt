package de.moritzf.quota.idea.ui.settings

import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import kotlin.math.roundToInt

/**
 * Keeps the account list at its preferred (or dragged) width, but lends width to the detail pane
 * when the dialog is too narrow for both.
 */
internal class AccountDetailsSplitter(accounts: JComponent, detail: JComponent) :
    OnePixelSplitter(false) {
    private var accountsWidth = accounts.preferredSize.width
    private var fitting = false

    init {
        firstComponent = accounts
        secondComponent = detail
        setHonorComponentsMinimumSize(false)
    }

    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        super.reshape(x, y, w, h)
        val available = w - dividerWidth
        if (available <= 0) return
        val roomForAccounts = available - JBUI.scale(MIN_DETAIL_WIDTH)
        val target =
            accountsWidth
                .coerceAtMost(roomForAccounts.coerceAtLeast(JBUI.scale(MIN_ACCOUNTS_WIDTH)))
                .coerceIn(0, available)
        fitting = true
        try {
            proportion = target.toFloat() / available
        } finally {
            fitting = false
        }
    }

    /** Divider drags land here; remember the chosen width for later resizes. */
    override fun setProportion(proportion: Float) {
        super.setProportion(proportion)
        if (fitting) return
        val available = width - dividerWidth
        if (available > 0) accountsWidth = (this.proportion * available).roundToInt()
    }

    private companion object {
        const val MIN_DETAIL_WIDTH = 360
        const val MIN_ACCOUNTS_WIDTH = 160
    }
}
