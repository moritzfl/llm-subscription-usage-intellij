package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaBundle.message
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

/** Common surface the settings dialog uses to drive a provider's panel. */
internal abstract class ProviderSettingsPanel : BorderLayoutPanel() {
    private var accountUiGeneration = 0L
    private val credentialDrafts = mutableListOf<CredentialFieldDraft>()
    val popupVisibilityToggle = PopupVisibilityToggle()
    var boundAccountId: String = ""
        set(value) {
            if (field != value) accountUiGeneration++
            field = value
            refreshButton?.accountId = value
        }

    var boundAccount: ProviderAccount? = null

    protected fun credentialDraft(field: javax.swing.text.JTextComponent): CredentialFieldDraft =
        CredentialFieldDraft(field).also(credentialDrafts::add)

    protected fun accountUiGuard(): () -> Boolean {
        val generation = accountUiGeneration
        return { generation == accountUiGeneration }
    }

    fun discardCredentialDrafts() {
        accountUiGeneration++
        credentialDrafts.forEach(CredentialFieldDraft::discard)
    }

    private var refreshButton: QuotaRefreshButton? = null
    private val routingHost =
        BorderLayoutPanel().apply {
            isOpaque = false
            isVisible = false
        }

    protected fun accountKey(type: de.moritzf.quota.idea.common.QuotaProviderType): String =
        boundAccountId.ifBlank {
            type.id
        }

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

    fun showProxySettingsLink(openProxySettings: () -> Unit) {
        addToBottom(panel { row { link(message("settings.proxy.open")) { openProxySettings() } } })
    }

    open fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(checkNotNull(boundAccount) { message("modelTest.selectAccount") })

    /**
     * Live per-account drafts, when the panel owns their lifecycle rather than the configurable.
     */
    open fun testApiKeyDrafts(): Map<String, String>? = null

    protected fun documentTestButton(
        provider: de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider,
        selectedModel: () -> String,
        modality: () -> JComponent?,
        watch: javax.swing.JComboBox<*>,
    ): DocumentTestButton =
        DocumentTestButton(selectedModel, modality, watch) { model ->
            prepareDocumentTest(provider, model)
        }

    protected fun visionTestButton(
        provider: de.moritzf.quota.idea.mcp.VisionProvider,
        combo: VisionModelCombo,
        modality: () -> JComponent?,
    ): VisionTestButton =
        VisionTestButton(combo, modality) { model -> prepareVisionTest(provider, model) }

    internal fun prepareDocumentTest(
        provider: de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider,
        model: String,
    ): DocumentTestExecution {
        val connection = testConnection()
        require(model.isNotBlank() && model != de.moritzf.quota.shared.DocumentModels.OFF) {
            message("modelTest.document.selectModel")
        }
        return { pdf, output, progress ->
            de.moritzf.quota.idea.action.PdfDocumentConversion.convert(
                    provider,
                    pdf,
                    output,
                    false,
                    progress = progress,
                    model = model,
                    connection = connection,
                )
                .warnings
        }
    }

    internal fun prepareVisionTest(
        provider: de.moritzf.quota.idea.mcp.VisionProvider,
        model: String,
    ): VisionTestExecution {
        val connection = testConnection()
        require(model.isNotBlank() && model != de.moritzf.quota.shared.DocumentModels.OFF) {
            message("modelTest.vision.selectModel")
        }
        return { image, prompt ->
            de.moritzf.quota.idea.action.VisionImageAnalysis.analyze(
                provider,
                image,
                prompt,
                model,
                connection = connection,
            )
        }
    }

    protected fun install(config: JComponent, response: JComponent) {
        // The form can be wider than the detail pane. Scroll instead of clipping the right edge.
        val formScroll =
            object : JBScrollPane(config) {
                    // The parent fixes our height before layout discovers horizontal overflow.
                    // Reserve
                    // scrollbar space up front so it cannot take height away from the final help
                    // row.
                    override fun getPreferredSize(): Dimension =
                        Dimension(
                            JBUI.scale(280),
                            super.getPreferredSize().height +
                                horizontalScrollBar.preferredSize.height,
                        )

                    override fun getMinimumSize(): Dimension = Dimension(0, 0)
                }
                .apply {
                    border = JBUI.Borders.empty()
                    isOverlappingScrollBar = false
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                }
        addToTop(
            panel {
                group("Connection") {
                    row { cell(formScroll).align(AlignX.FILL).resizableColumn() }
                }
            }
        )
        addToCenter(
            BorderLayoutPanel().apply {
                isOpaque = false
                addToTop(routingHost)
                addToCenter(response)
            }
        )
    }

    /** Long connection forms wrap to the detail pane and scroll vertically as one surface. */
    protected fun installScrollable(config: JComponent, response: JComponent) {
        // DSL section separators can retain their previous width as a minimum after layout.
        config.minimumSize = Dimension(0, 0)
        response.minimumSize = Dimension(0, 0)
        val content =
            object : BorderLayoutPanel(), javax.swing.Scrollable {
                    override fun getPreferredScrollableViewportSize() =
                        Dimension(JBUI.scale(420), JBUI.scale(500))

                    override fun getScrollableTracksViewportWidth() = true

                    override fun getScrollableTracksViewportHeight() = false

                    override fun getScrollableUnitIncrement(
                        visibleRect: java.awt.Rectangle,
                        orientation: Int,
                        direction: Int,
                    ) = JBUI.scale(16)

                    override fun getScrollableBlockIncrement(
                        visibleRect: java.awt.Rectangle,
                        orientation: Int,
                        direction: Int,
                    ) = (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
                }
                .apply {
                    isOpaque = false
                    addToTop(
                        panel {
                            group("Connection") {
                                row { cell(config).align(AlignX.FILL).resizableColumn() }
                            }
                            row { cell(routingHost).align(AlignX.FILL).resizableColumn() }
                            row { cell(response).align(AlignX.FILL).resizableColumn() }
                        }
                    )
                }
        addToCenter(
            JBScrollPane(content).apply {
                border = JBUI.Borders.empty()
                isOverlappingScrollBar = false
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }
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

                // A compact JSON line must not set the panel's minimum width. The scroll pane
                // tracks the viewport.
                override fun getMinimumSize(): Dimension =
                    Dimension(0, super.getMinimumSize().height)

                override fun getPreferredSize(): Dimension =
                    Dimension(0, super.getPreferredSize().height)

                override fun getScrollableTracksViewportWidth(): Boolean = true
            }
            .apply {
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
                override fun getPreferredSize(): Dimension =
                    Dimension(JBUI.scale(1), JBUI.scale(140))

                override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(1), JBUI.scale(80))
            }
            .apply {
                border = JBUI.Borders.emptyTop(4)
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }
    }

    protected fun createResponseSection(
        viewer: JBTextArea,
        title: String = "Last quota response",
    ): JComponent {
        val refreshButton =
            QuotaRefreshButton(
                    refresh = { accountId ->
                        QuotaUsageService.getInstance().refreshAsync(accountId, forceUpdate = true)
                    },
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
                )
                .also {
                    it.accountId = boundAccountId
                    this.refreshButton = it
                }
        val copyButton =
            JButton(AllIcons.Actions.Copy).apply {
                isOpaque = false
                isBorderPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Copy last quota response"
                accessibleContext.accessibleName = "Copy last quota response"
                addActionListener {
                    Toolkit.getDefaultToolkit()
                        .systemClipboard
                        .setContents(StringSelection(viewer.text), null)
                    icon = AllIcons.Actions.Checked
                    toolTipText = "Copied"
                    Timer(1_500) {
                            icon = AllIcons.Actions.Copy
                            toolTipText = "Copy last quota response"
                        }
                        .apply {
                            isRepeats = false
                            start()
                        }
                }
            }
        val headerRow =
            BorderLayoutPanel().apply {
                isOpaque = false
                addToLeft(JBLabel(title))
                addToRight(
                    BorderLayoutPanel().apply {
                        isOpaque = false
                        addToLeft(refreshButton)
                        addToRight(copyButton)
                    }
                )
            }
        return BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(headerRow)
            addToCenter(createResponseViewerPanel(viewer))
        }
    }
}
