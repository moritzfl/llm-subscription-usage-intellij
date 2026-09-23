package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import de.moritzf.quota.idea.common.ProviderSnapshot
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.util.concurrent.CompletableFuture
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Feedback belongs to one requested account and never follows unrelated quota broadcasts. */
internal class QuotaRefreshButton(
    private val refresh: (String) -> CompletableFuture<ProviderSnapshot?>,
    private val onCompleted: () -> Unit,
    feedbackMillis: Int = 1_500,
) : JButton(AllIcons.Actions.Refresh) {
    private var pending: CompletableFuture<ProviderSnapshot?>? = null
    private val resetTimer = Timer(feedbackMillis) { showIdle() }.apply { isRepeats = false }

    var accountId: String = ""
        set(value) {
            if (field == value) return
            field = value
            resetFeedback()
        }

    init {
        isOpaque = false
        isBorderPainted = false
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        showIdle()
        addActionListener { startRefresh() }
    }

    private fun startRefresh() {
        if (pending != null || accountId.isBlank()) return
        val requestedAccount = accountId
        resetTimer.stop()
        icon = spinningIcon
        disabledIcon = spinningIcon // JButton must paint the animation while repeat clicks are disabled.
        isEnabled = false
        setDescription("Refreshing quota...")
        val request = try {
            refresh(requestedAccount)
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }
        pending = request
        request.whenComplete { result, failure ->
            SwingUtilities.invokeLater {
                if (pending !== request || accountId != requestedAccount) return@invokeLater
                pending = null
                val success = failure == null && result?.quota != null && result.error.isNullOrBlank()
                disabledIcon = null
                icon = if (success) AllIcons.Actions.Checked else AllIcons.Actions.Cancel
                isEnabled = true
                setDescription(if (success) "Quota refreshed" else "Quota refresh failed")
                resetTimer.restart()
                onCompleted()
            }
        }
    }

    private fun setDescription(text: String) {
        toolTipText = text
        getAccessibleContext().accessibleName = text
    }

    private fun showIdle() {
        disabledIcon = null
        icon = AllIcons.Actions.Refresh
        isEnabled = accountId.isNotBlank()
        setDescription("Refresh quota")
    }

    private fun resetFeedback() {
        pending = null
        resetTimer.stop()
        showIdle()
    }

    override fun removeNotify() {
        resetFeedback()
        super.removeNotify()
    }

    private companion object {
        val spinningIcon: Icon by lazy {
            val base = AllIcons.Actions.Refresh
            AnimatedIcon(50, *Array(24) { frame ->
                object : Icon {
                    override fun getIconWidth(): Int = base.iconWidth
                    override fun getIconHeight(): Int = base.iconHeight

                    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
                        val rotated = graphics.create() as Graphics2D
                        try {
                            // Positive Graphics2D angles are clockwise in Y-down space. The refresh
                            // arrowheads face the other way, so advance frames backward.
                            rotated.rotate(-frame * 2.0 * Math.PI / 24, x + iconWidth / 2.0, y + iconHeight / 2.0)
                            base.paintIcon(component, rotated, x, y)
                        } finally {
                            rotated.dispose()
                        }
                    }
                }
            })
        }
    }
}
