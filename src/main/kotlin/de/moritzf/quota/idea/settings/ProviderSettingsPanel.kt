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
import de.moritzf.quota.shared.JsonSupport
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
        set(value) {
            field = value
            refreshButton?.accountId = value
        }
    var boundAccount: ProviderAccount? = null
    private var refreshButton: QuotaRefreshButton? = null
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
        // The form can be wider than the detail pane. Scroll instead of clipping the right edge.
        val formScroll = object : JBScrollPane(config) {
            override fun getPreferredSize(): Dimension =
                Dimension(JBUI.scale(280), super.getPreferredSize().height)
            override fun getMinimumSize(): Dimension = Dimension(0, 0)
        }.apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
        addToTop(
            panel {
                group("Connection") {
                    row {
                        cell(formScroll).align(AlignX.FILL).resizableColumn()
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
        return object : JBTextArea() {
            override fun setText(text: String?) {
                super.setText(JsonSupport.prettyResponse(text))
            }

            // A compact JSON line must not set the panel's minimum width. The scroll pane tracks the viewport.
            override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)

            override fun getPreferredSize(): Dimension = Dimension(0, super.getPreferredSize().height)

            override fun getScrollableTracksViewportWidth(): Boolean = true
        }.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            columns = 1
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
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    protected fun createResponseSection(
        viewer: JBTextArea,
        title: String = "Last quota response",
    ): JComponent {
        val refreshButton = QuotaRefreshButton(
            refresh = { accountId -> QuotaUsageService.getInstance().refreshAsync(accountId, forceUpdate = true) },
            onCompleted = {
                val service = QuotaUsageService.getInstance()
                if (service.providerForAccount(boundAccountId) == null) {
                    viewer.text = "Apply settings to refresh quota for this account."
                    viewer.caretPosition = 0
                } else {
                    updateResponseArea()
                    updateStatus()
                }
            },
        ).also {
            it.accountId = boundAccountId
            this.refreshButton = it
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
