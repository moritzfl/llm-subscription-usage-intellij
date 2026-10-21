package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.ui.settings.AccountDetailsSplitter
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountSplitterLayoutTest {
    @Test
    fun initialLayoutKeepsAccountWidthAfterZeroSizeLayout() {
        SwingUtilities.invokeAndWait {
            val accounts = JPanel().apply { preferredSize = Dimension(260, 200) }
            val detail = JPanel()
            val splitter = AccountDetailsSplitter(accounts, detail)
            splitter.doLayout()
            for (width in listOf(1400, 1800, 900, 1800)) {
                splitter.setSize(width, 800)
                splitter.doLayout()
                assertEquals(260, accounts.width, "Accounts width at splitter width $width")
                assertEquals(width - 260 - splitter.dividerWidth, detail.width)
            }

            // The native divider updates the proportion when dragged.
            splitter.proportion = 0.25f
            splitter.doLayout()
            val chosenWidth = accounts.width
            assertEquals(450, chosenWidth)
            for (width in listOf(1200, 1600)) {
                splitter.setSize(width, 800)
                splitter.doLayout()
                assertEquals(chosenWidth, accounts.width)
            }
        }
    }

    @Test
    fun narrowDialogLendsAccountWidthToTheDetailAndGetsItBack() {
        SwingUtilities.invokeAndWait {
            val accounts = JPanel().apply { preferredSize = Dimension(260, 200) }
            val detail = JPanel()
            val splitter = AccountDetailsSplitter(accounts, detail)

            splitter.setSize(560, 800)
            splitter.doLayout()
            assertEquals(360, detail.width, "Detail keeps its minimum on a narrow dialog")
            assertEquals(560 - 360 - splitter.dividerWidth, accounts.width)

            splitter.setSize(400, 800)
            splitter.doLayout()
            assertEquals(160, accounts.width, "Account list keeps its own minimum")

            splitter.setSize(1400, 800)
            splitter.doLayout()
            assertEquals(260, accounts.width, "Wide dialog restores the account width")
        }
    }
}
