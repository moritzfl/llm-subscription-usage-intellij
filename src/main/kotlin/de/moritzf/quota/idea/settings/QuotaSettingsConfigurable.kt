package de.moritzf.quota.idea.settings

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerUrlSyncService
import de.moritzf.quota.idea.ui.indicator.*
import de.moritzf.quota.idea.ui.settings.ProviderReorderPanel
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.kimi.KimiQuota
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.UIManager

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 */
class QuotaSettingsConfigurable : Configurable {
    private var rootComponent: JComponent? = null
    private var panel: DialogPanel? = null
    private var connection: MessageBusConnection? = null
    private var updatingDisplayModeChoices: Boolean = false

    private lateinit var cursorPanel: CursorSettingsPanel
    private lateinit var openAiPanel: OpenAiSettingsPanel
    private lateinit var kimiPanel: KimiSettingsPanel
    private lateinit var miniMaxPanel: MiniMaxSettingsPanel
    private lateinit var openCodePanel: OpenCodeSettingsPanel
    private lateinit var ollamaPanel: OllamaSettingsPanel
    private lateinit var zaiPanel: ZaiSettingsPanel

    private var locationComboBox: ComboBox<QuotaIndicatorLocation>? = null
    private var displayModeComboBox: ComboBox<QuotaDisplayMode>? = null
    private var indicatorSourceComboBox: ComboBox<QuotaIndicatorSource>? = null
    private var displayModePreview: DisplayModePreviewComponent? = null
    private var providerReorderPanel: ProviderReorderPanel? = null
    private var serviceCards: JPanel? = null
    private var serviceCardLayout: CardLayout? = null
    private var mcpSyncCheckBox: JBCheckBox? = null
    private var configureMcpSyncTargetsButton: JButton? = null
    private var mcpSyncTargets: MutableList<McpServerSyncTarget> = mutableListOf()

    override fun getDisplayName(): String = "LLM Subscription Usage"

    override fun createComponent(): JComponent? {
        val statusLabelDefaultForeground = UIManager.getColor("Label.foreground")

        cursorPanel = CursorSettingsPanel(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )
        openAiPanel = OpenAiSettingsPanel()
        kimiPanel = KimiSettingsPanel(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )
        miniMaxPanel = MiniMaxSettingsPanel(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )
        openCodePanel = OpenCodeSettingsPanel(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )
        ollamaPanel = OllamaSettingsPanel(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )
        zaiPanel = ZaiSettingsPanel(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )

        locationComboBox = createIndicatorComboBox(QuotaIndicatorLocation.entries.toTypedArray())
        displayModeComboBox = createIndicatorComboBox(QuotaDisplayMode.entries.toTypedArray())
        indicatorSourceComboBox = createIndicatorComboBox(QuotaIndicatorSource.entries.toTypedArray())
        displayModePreview = DisplayModePreviewComponent()
        mcpSyncCheckBox = JBCheckBox("Sync IntelliJ MCP server URL to JSON files")
        configureMcpSyncTargetsButton = JButton("Configure Targets...").apply {
            addActionListener {
                val parent = rootComponent ?: panel ?: return@addActionListener
                val dialog = McpServerSyncTargetsDialog(parent, mcpSyncTargets)
                if (dialog.showAndGet()) {
                    mcpSyncTargets = normalizeTargets(dialog.targets()).toMutableList()
                }
            }
        }

        locationComboBox!!.addActionListener {
            updateDisplayModeChoices()
            updateDisplayModePreview()
        }

        displayModeComboBox!!.addActionListener {
            if (updatingDisplayModeChoices) {
                return@addActionListener
            }
            updateDisplayModePreview()
        }

        panel = buildIndicatorConfigPanel()

        serviceCardLayout = CardLayout()
        serviceCards = JPanel(serviceCardLayout).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(16)
        }

        providerReorderPanel = ProviderReorderPanel(
            QuotaSettingsState.getInstance().providerOrderList(),
            onOrderChanged = { },
            onProviderSelected = { type ->
                serviceCardLayout?.show(serviceCards, type.id)
                serviceCards?.revalidate()
                serviceCards?.repaint()
            }
        )

        rebuildServiceCards()

        rootComponent = BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(panel!!)
            addToCenter(BorderLayoutPanel().apply {
                isOpaque = false
                addToTop(JSeparator())
                addToCenter(BorderLayoutPanel().apply {
                    isOpaque = false
                    addToTop(providerReorderPanel!!)
                    addToCenter(serviceCards!!)
                })
                addToBottom(JSeparator())
            })
        }

        connection = ApplicationManager.getApplication().messageBus.connect()
        connection!!.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {

            override fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    openAiPanel.updateAccountFields()
                    openAiPanel.updateResponseArea()
                    openAiPanel.updateAuthUi()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onOpenCodeQuotaUpdated(quota: OpenCodeQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onOpenCodeQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    openCodePanel.updateOpenCodeResponseArea()
                    openCodePanel.updateOpenCodeStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onOllamaQuotaUpdated(quota: OllamaQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onOllamaQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    ollamaPanel.updateOllamaResponseArea()
                    ollamaPanel.updateOllamaStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onZaiQuotaUpdated(quota: ZaiQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onZaiQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    zaiPanel.updateZaiResponseArea()
                    zaiPanel.updateZaiStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onMiniMaxQuotaUpdated(quota: MiniMaxQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onMiniMaxQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    miniMaxPanel.updateMiniMaxResponseArea()
                    miniMaxPanel.updateMiniMaxStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onKimiQuotaUpdated(quota: KimiQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onKimiQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    kimiPanel.updateKimiResponseArea()
                    kimiPanel.updateKimiStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onCursorQuotaUpdated(quota: de.moritzf.quota.cursor.CursorQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onCursorQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    cursorPanel.updateCursorResponseArea()
                    cursorPanel.updateCursorStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }
        })

        reset()
        return rootComponent
    }

    override fun isModified(): Boolean {
        return panel?.isModified() == true
    }

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        connection?.disconnect()
        connection = null
        rootComponent = null
        panel = null
        locationComboBox = null
        displayModeComboBox = null
        indicatorSourceComboBox = null
        displayModePreview = null
        providerReorderPanel = null
        serviceCards = null
        serviceCardLayout = null
        updatingDisplayModeChoices = false
        mcpSyncCheckBox = null
        configureMcpSyncTargetsButton = null
        mcpSyncTargets = mutableListOf()
    }

    private fun updateDisplayModeChoices(preferredMode: QuotaDisplayMode? = null) {
        val combo = displayModeComboBox ?: return
        val location = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return
        val selectedMode = preferredMode ?: combo.selectedItem as? QuotaDisplayMode ?: QuotaSettingsState.getInstance().displayMode()
        val sanitizedMode = QuotaDisplayMode.sanitizeFor(location, selectedMode)
        updatingDisplayModeChoices = true
        try {
            combo.removeAllItems()
            QuotaDisplayMode.supportedFor(location).forEach(combo::addItem)
            combo.selectedItem = sanitizedMode
        } finally {
            updatingDisplayModeChoices = false
        }
    }

    private fun updateDisplayModePreview() {
        val mode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return
        displayModePreview?.updateMode(mode)
    }

    private fun <T> createIndicatorComboBox(items: Array<T>): ComboBox<T> {
        return ComboBox(items).apply {
            preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
            minimumSize = preferredSize
        }
    }

    private fun rebuildServiceCards() {
        val cards = serviceCards ?: return
        cards.removeAll()
        val providerPanels = mapOf(
            QuotaProviderType.CURSOR to cursorPanel,
            QuotaProviderType.KIMI to kimiPanel,
            QuotaProviderType.MINIMAX to miniMaxPanel,
            QuotaProviderType.OLLAMA to ollamaPanel,
            QuotaProviderType.OPEN_AI to openAiPanel,
            QuotaProviderType.OPEN_CODE to openCodePanel,
            QuotaProviderType.ZAI to zaiPanel,
        )
        providerPanels.forEach { (type, panel) ->
            cards.add(panel, type.id)
        }
        serviceCardLayout?.show(cards, QuotaProviderType.CURSOR.id)
    }

    private fun buildIndicatorConfigPanel(): DialogPanel {
        return panel {
            row("Indicator location:") {
                cell(locationComboBox!!)
            }

            row("Indicator display:") {
                cell(displayModeComboBox!!)
                cell(displayModePreview!!).gap(RightGap.SMALL)
            }

            row("Indicator quota source:") {
                cell(indicatorSourceComboBox!!)
            }

            row {
                cell(mcpSyncCheckBox!!)
                cell(configureMcpSyncTargetsButton!!)
            }

            onApply {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onApply
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onApply
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onApply
                val sanitizedDisplayMode = QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode)
                val state = QuotaSettingsState.getInstance()
                val locationChanged = selectedLocation != state.location()
                val displayModeChanged = sanitizedDisplayMode != state.displayMode()
                val sourceChanged = selectedSource != state.source()
                val openAiPopupVisibilityChanged = openAiPanel.openAiHideFromPopupCheckBox.isSelected != state.hideOpenAiFromQuotaPopup
                val openCodePopupVisibilityChanged = openCodePanel.openCodeHideFromPopupCheckBox.isSelected != state.hideOpenCodeFromQuotaPopup
                val ollamaPopupVisibilityChanged = ollamaPanel.ollamaHideFromPopupCheckBox.isSelected != state.hideOllamaFromQuotaPopup
                val zaiPopupVisibilityChanged = zaiPanel.zaiHideFromPopupCheckBox.isSelected != state.hideZaiFromQuotaPopup
                val miniMaxPopupVisibilityChanged = miniMaxPanel.miniMaxHideFromPopupCheckBox.isSelected != state.hideMiniMaxFromQuotaPopup
                val kimiPopupVisibilityChanged = kimiPanel.kimiHideFromPopupCheckBox.isSelected != state.hideKimiFromQuotaPopup
                val cursorPopupVisibilityChanged = cursorPanel.cursorHideFromPopupCheckBox.isSelected != state.hideCursorFromQuotaPopup
                val miniMaxRegionChanged = miniMaxPanel.regionComboBox.selectedItem as? MiniMaxRegionPreference != state.miniMaxRegionPreference()
                val providerOrderChanged = providerReorderPanel?.getOrder()?.joinToString(",") { it.id } != state.providerOrder
                val normalizedMcpTargets = normalizeTargets(mcpSyncTargets)
                val mcpSyncChanged = mcpSyncCheckBox?.isSelected != state.syncIntellijMcpServerUrl
                val mcpTargetsChanged = normalizedMcpTargets != normalizeTargets(state.mcpServerSyncTargets)
                if (locationChanged) {
                    state.setLocation(selectedLocation)
                }
                if (displayModeChanged) {
                    state.setDisplayMode(sanitizedDisplayMode)
                }
                if (sourceChanged) {
                    state.setSource(selectedSource)
                }
                state.hideOpenAiFromQuotaPopup = openAiPanel.openAiHideFromPopupCheckBox.isSelected
                state.hideOpenCodeFromQuotaPopup = openCodePanel.openCodeHideFromPopupCheckBox.isSelected
                state.hideOllamaFromQuotaPopup = ollamaPanel.ollamaHideFromPopupCheckBox.isSelected
                state.hideZaiFromQuotaPopup = zaiPanel.zaiHideFromPopupCheckBox.isSelected
                state.hideMiniMaxFromQuotaPopup = miniMaxPanel.miniMaxHideFromPopupCheckBox.isSelected
                state.hideKimiFromQuotaPopup = kimiPanel.kimiHideFromPopupCheckBox.isSelected
                state.hideCursorFromQuotaPopup = cursorPanel.cursorHideFromPopupCheckBox.isSelected
                state.minimaxRegionPreference = (miniMaxPanel.regionComboBox.selectedItem as? MiniMaxRegionPreference ?: MiniMaxRegionPreference.AUTO).name
                if (providerOrderChanged) {
                    state.providerOrder = providerReorderPanel?.getOrder()?.joinToString(",") { it.id } ?: state.providerOrder
                }
                state.syncIntellijMcpServerUrl = mcpSyncCheckBox?.isSelected == true
                state.mcpServerSyncTargets = normalizedMcpTargets.toMutableList()
                if (locationChanged || displayModeChanged || sourceChanged || openAiPopupVisibilityChanged || openCodePopupVisibilityChanged || ollamaPopupVisibilityChanged || zaiPopupVisibilityChanged || miniMaxPopupVisibilityChanged || kimiPopupVisibilityChanged || cursorPopupVisibilityChanged || miniMaxRegionChanged || providerOrderChanged || mcpSyncChanged || mcpTargetsChanged) {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(QuotaSettingsListener.TOPIC)
                        .onSettingsChanged()
                    McpServerUrlSyncService.getInstance().reloadFromSettings()
                    if (state.syncIntellijMcpServerUrl) {
                        McpServerUrlSyncService.getInstance().syncNowAsync()
                    }
                    ActivityTracker.getInstance().inc()
                }
            }

            onReset {
                locationComboBox?.selectedItem = QuotaSettingsState.getInstance().location()
                updateDisplayModeChoices(QuotaSettingsState.getInstance().displayMode())
                updateDisplayModePreview()
                indicatorSourceComboBox?.selectedItem = QuotaSettingsState.getInstance().source()
                openAiPanel.openAiHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideOpenAiFromQuotaPopup
                openCodePanel.openCodeHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideOpenCodeFromQuotaPopup
                ollamaPanel.ollamaHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideOllamaFromQuotaPopup
                zaiPanel.zaiHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideZaiFromQuotaPopup
                miniMaxPanel.miniMaxHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideMiniMaxFromQuotaPopup
                kimiPanel.kimiHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideKimiFromQuotaPopup
                cursorPanel.cursorHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideCursorFromQuotaPopup
                miniMaxPanel.regionComboBox.selectedItem = QuotaSettingsState.getInstance().miniMaxRegionPreference()
                providerReorderPanel?.setOrder(QuotaSettingsState.getInstance().providerOrderList())
                mcpSyncCheckBox?.isSelected = QuotaSettingsState.getInstance().syncIntellijMcpServerUrl
                mcpSyncTargets = normalizeTargets(QuotaSettingsState.getInstance().mcpServerSyncTargets).toMutableList()
                openAiPanel.updateAuthUi()
                openAiPanel.updateAccountFields()
                openAiPanel.updateResponseArea()
                openCodePanel.updateOpenCodeResponseArea()
                openCodePanel.updateOpenCodeFields()
                ollamaPanel.updateOllamaFields()
                ollamaPanel.updateOllamaResponseArea()
                zaiPanel.updateZaiFields()
                zaiPanel.updateZaiResponseArea()
                miniMaxPanel.updateMiniMaxFields()
                miniMaxPanel.updateMiniMaxResponseArea()
                kimiPanel.updateKimiFields()
                kimiPanel.updateKimiResponseArea()
                cursorPanel.updateCursorFields()
                cursorPanel.updateCursorResponseArea()
            }

            onIsModified {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onIsModified false
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onIsModified false
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onIsModified false
                val state = QuotaSettingsState.getInstance()
                selectedLocation != state.location() ||
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode) != state.displayMode() ||
                    selectedSource != state.source() ||
                    openAiPanel.openAiHideFromPopupCheckBox.isSelected != state.hideOpenAiFromQuotaPopup ||
                    openCodePanel.openCodeHideFromPopupCheckBox.isSelected != state.hideOpenCodeFromQuotaPopup ||
                    ollamaPanel.ollamaHideFromPopupCheckBox.isSelected != state.hideOllamaFromQuotaPopup ||
                    zaiPanel.zaiHideFromPopupCheckBox.isSelected != state.hideZaiFromQuotaPopup ||
                    miniMaxPanel.miniMaxHideFromPopupCheckBox.isSelected != state.hideMiniMaxFromQuotaPopup ||
                    kimiPanel.kimiHideFromPopupCheckBox.isSelected != state.hideKimiFromQuotaPopup ||
                    cursorPanel.cursorHideFromPopupCheckBox.isSelected != state.hideCursorFromQuotaPopup ||
                    miniMaxPanel.regionComboBox.selectedItem as? MiniMaxRegionPreference != state.miniMaxRegionPreference() ||
                    providerReorderPanel?.getOrder()?.joinToString(",") { it.id } != state.providerOrder ||
                    mcpSyncCheckBox?.isSelected != state.syncIntellijMcpServerUrl ||
                    normalizeTargets(mcpSyncTargets) != normalizeTargets(state.mcpServerSyncTargets)
            }
        }.apply {
            preferredFocusedComponent = locationComboBox
        }
    }

    private fun normalizeTargets(targets: List<McpServerSyncTarget>): List<McpServerSyncTarget> {
        return targets.map { it.normalized() }
    }

    private class DisplayModePreviewComponent : BorderLayoutPanel() {
        private val previewIconLabel = JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        private val percentagePreview = QuotaPercentageIndicator().apply {
            update("42% • 2h 9m", 0.42, QuotaUsageColors.GREEN, periodElapsedFraction = 0.55)
        }

        init {
            isOpaque = false
            updateMode(QuotaDisplayMode.ICON_ONLY)
        }

        fun updateMode(mode: QuotaDisplayMode) {
            removeAll()
            when (mode) {
                QuotaDisplayMode.ICON_ONLY -> {
                    previewIconLabel.icon = QuotaIcons.STATUS
                    addToCenter(previewIconLabel)
                }

                QuotaDisplayMode.CAKE_DIAGRAM -> {
                    previewIconLabel.icon = scaledCakeIcon(previewIconLabel)
                    addToCenter(previewIconLabel)
                }

                QuotaDisplayMode.PERCENTAGE_BAR -> {
                    percentagePreview.update(
                        text = "42% • 2h 9m",
                        fraction = 0.42,
                        fillColor = QuotaUsageColors.GREEN,
                        periodElapsedFraction = 0.55,
                    )
                    addToCenter(percentagePreview)
                }
            }
            revalidate()
            repaint()
        }

        private fun scaledCakeIcon(component: JComponent): Icon {
            return scaleIconToQuotaStatusSize(QuotaIcons.CAKE_40, component)
        }
    }
}
