package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaUsageService
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

/**
 * Common surface the settings dialog uses to drive a provider's panel.
 */
internal abstract class ProviderSettingsPanel : BorderLayoutPanel() {
    val popupVisibilityToggle = PopupVisibilityToggle()
    var boundAccountId: String = ""
    var boundAccount: ProviderAccount? = null
    private val routingHost = BorderLayoutPanel().apply {
        isOpaque = false
        isVisible = false
    }

    protected fun accountKey(type: de.moritzf.quota.idea.common.QuotaProviderType): String =
        boundAccountId.ifBlank { type.id }

    fun showRouting(component: JComponent?) {
        routingHost.removeAll()
        if (component != null) {
            routingHost.addToCenter(component)
            routingHost.isVisible = true
        } else {
            routingHost.isVisible = false
        }
        routingHost.revalidate()
        routingHost.repaint()
    }

    protected fun install(config: JComponent, response: JComponent) {
        addToTop(
            panel {
                group("Connection") {
                    row {
                        cell(config).align(AlignX.FILL).resizableColumn()
                    }
                }
            },
        )
        addToCenter(
            BorderLayoutPanel().apply {
                isOpaque = false
                addToTop(routingHost)
                addToCenter(response)
            },
        )
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width.coerceAtMost(JBUI.scale(420)), size.height)
    }
    abstract fun updateFields()
    abstract fun updateStatus()
    abstract fun updateResponseArea()

    /** Read-only view of the provider's raw quota response. */
    protected fun createResponseViewer(): JBTextArea {
        return JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            margin = JBUI.insets(6)
        }
    }

    /**
     * Scroll pane for [createResponseViewer]. The preferred height stays small on purpose: panels
     * add this to their centre, so it grows with the dialog, while a large preferred size would
     * push every settings tab to the same tall minimum.
     */
    protected fun createResponseViewerPanel(viewer: JBTextArea): JComponent {
        return object : JBScrollPane(viewer) {
            override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(1), JBUI.scale(140))
            override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(1), JBUI.scale(80))
        }.apply {
            border = JBUI.Borders.emptyTop(4)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    protected fun createResponseSection(
        viewer: JBTextArea,
        title: String = "Last quota response",
    ): JComponent {
        val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Refresh quota"
            accessibleContext.accessibleName = "Refresh quota"
            addActionListener {
                val accountId = boundAccountId
                val service = QuotaUsageService.getInstance()
                if (service.providerForAccount(accountId) == null) {
                    viewer.text = "Apply settings to refresh quota for this account."
                    viewer.caretPosition = 0
                } else {
                    service.refreshAsync(accountId, forceUpdate = true)
                }
            }
        }
        val copyButton = JButton(AllIcons.Actions.Copy).apply {
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Copy last quota response"
            accessibleContext.accessibleName = "Copy last quota response"
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(viewer.text), null)
                icon = AllIcons.Actions.Checked
                toolTipText = "Copied"
                Timer(1_500) {
                    icon = AllIcons.Actions.Copy
                    toolTipText = "Copy last quota response"
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        }
        val headerRow = BorderLayoutPanel().apply {
            isOpaque = false
            addToLeft(JBLabel(title))
            addToRight(BorderLayoutPanel().apply {
                isOpaque = false
                addToLeft(refreshButton)
                addToRight(copyButton)
            })
        }
        return BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(headerRow)
            addToCenter(createResponseViewerPanel(viewer))
        }
    }
}
