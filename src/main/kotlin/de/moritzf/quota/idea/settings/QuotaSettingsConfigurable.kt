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
import de.moritzf.quota.idea.common.QuotaProviderRegistry
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerUrlSyncService
import de.moritzf.quota.idea.mcp.McpServerStatusState
import de.moritzf.quota.idea.mcp.McpServerUrlResolver
import de.moritzf.quota.idea.openai.OpenAiProxyService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.*
import de.moritzf.quota.idea.ui.settings.ProviderReorderPanel
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.shared.ProviderQuota
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 */
class QuotaSettingsConfigurable : Configurable {
    private var rootComponent: JComponent? = null
    private var panel: DialogPanel? = null
    private var connection: MessageBusConnection? = null
    private var updatingDisplayModeChoices: Boolean = false

    private val providerPanelsByType = linkedMapOf<QuotaProviderType, ProviderSettingsPanel>()

    private var locationComboBox: ComboBox<QuotaIndicatorLocation>? = null
    private var displayModeComboBox: ComboBox<QuotaDisplayMode>? = null
    private var indicatorSourceComboBox: ComboBox<QuotaIndicatorSource>? = null
    private var displayModePreview: DisplayModePreviewComponent? = null
    private var providerReorderPanel: ProviderReorderPanel? = null
    private var serviceCards: JPanel? = null
    private var serviceCardLayout: CardLayout? = null
    private var mcpSyncCheckBox: JBCheckBox? = null
    private var configureMcpSyncTargetsButton: JButton? = null
    private var mcpServerStatusLabel: JBLabel? = null
    private var mcpServerStatusTimer: Timer? = null
    private var mcpSyncTargets: MutableList<McpServerSyncTarget> = mutableListOf()

    override fun getDisplayName(): String = "LLM Subscription Usage"

    override fun createComponent(): JComponent? {
        val statusLabelDefaultForeground = UIManager.getColor("Label.foreground")

        providerPanelsByType.clear()
        val providerPanelContext = ProviderSettingsPanelContext(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )
        providerPanelsByType.putAll(ProviderSettingsRegistry.createPanels(providerPanelContext))

        locationComboBox = createIndicatorComboBox(QuotaIndicatorLocation.entries.toTypedArray())
        displayModeComboBox = createIndicatorComboBox(QuotaDisplayMode.entries.toTypedArray())
        indicatorSourceComboBox = createIndicatorComboBox(QuotaIndicatorSource.entries.toTypedArray())
        displayModePreview = DisplayModePreviewComponent()
        mcpSyncCheckBox = JBCheckBox("Sync IntelliJ MCP server URL to JSON/TOML/YAML files")
        configureMcpSyncTargetsButton = JButton("Configure Targets...").apply {
            addActionListener {
                val parent = rootComponent ?: panel ?: return@addActionListener
                val dialog = McpServerSyncTargetsDialog(parent, mcpSyncTargets)
                if (dialog.showAndGet()) {
                    mcpSyncTargets = normalizeTargets(dialog.targets()).toMutableList()
                }
            }
        }
        mcpServerStatusLabel = JBLabel()

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
            override fun onQuotaUpdated(type: QuotaProviderType, quota: ProviderQuota?, error: String?) {
                val providerPanel = providerPanelsByType[type] ?: return
                val currentPanel = rootComponent ?: panel ?: return
                ApplicationManager.getApplication().invokeLater({
                    providerPanel.updateResponseArea()
                    providerPanel.updateStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }
        })

        reset()
        startMcpServerStatusRefresh()
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
        providerPanelsByType.clear()
        mcpSyncCheckBox = null
        configureMcpSyncTargetsButton = null
        mcpServerStatusTimer?.stop()
        mcpServerStatusTimer = null
        mcpServerStatusLabel = null
        mcpSyncTargets = mutableListOf()
    }

    private fun startMcpServerStatusRefresh() {
        mcpServerStatusTimer?.stop()
        updateMcpServerStatus()
        mcpServerStatusTimer = Timer(MCP_SERVER_STATUS_REFRESH_MILLIS) {
            updateMcpServerStatus()
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun updateMcpServerStatus() {
        val label = mcpServerStatusLabel ?: return
        val status = McpServerUrlResolver.currentStatus()
        label.text = formatMcpServerStatusText(status.message, status.state)
        label.foreground = UIManager.getColor("Label.foreground") ?: label.foreground
        label.toolTipText = status.message
    }

    private fun formatMcpServerStatusText(text: String, state: McpServerStatusState): String {
        val color = when (state) {
            McpServerStatusState.RUNNING -> "#4CAF50"
            McpServerStatusState.NOT_RUNNING,
            McpServerStatusState.NOT_INSTALLED_OR_DISABLED,
            McpServerStatusState.UNAVAILABLE -> "#F44336"
        }
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
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
        providerPanelsByType.forEach { (type, panel) ->
            cards.add(panel, type.id)
        }
        val selectedProvider = providerReorderPanel?.getSelectedProvider()
            ?: QuotaSettingsState.getInstance().providerOrderList().firstOrNull()
            ?: QuotaProviderRegistry.defaultProviderOrder().first()
        serviceCardLayout?.show(cards, selectedProvider.id)
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

            row {
                cell(mcpServerStatusLabel!!)
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
                val popupVisibilityChanged = hideFromPopupCheckBoxes().any { (type, checkBox) ->
                    checkBox.isSelected != state.isHiddenFromPopup(type)
                }
                val miniMaxRegionChanged = miniMaxPanel().regionComboBox.selectedItem as? MiniMaxRegionPreference != state.miniMaxRegionPreference()
                val providerOrderChanged = providerReorderPanel?.getOrder()?.joinToString(",") { it.id } != state.providerOrder
                val normalizedMcpTargets = normalizeTargets(mcpSyncTargets)
                val mcpSyncChanged = mcpSyncCheckBox?.isSelected != state.syncIntellijMcpServerUrl
                val mcpTargetsChanged = normalizedMcpTargets != normalizeTargets(state.mcpServerSyncTargets)
                val openAiPanel = openAiPanel()
                val openAiProxyEnabledChanged = openAiPanel.openAiProxyEnabledCheckBox.isSelected != state.openAiProxyEnabled
                val openAiProxyPortChanged = openAiPanel.isProxyPortModified()
                val openAiProxyApiKeyChanged = openAiPanel.isProxyApiKeyModified()
                val openAiProxyLogRequestsChanged = openAiPanel.openAiProxyLogRequestsCheckBox.isSelected != state.openAiProxyLogRequests
                val gitHubPanel = gitHubPanel()
                val gitHubEnterpriseHostChanged = gitHubPanel.normalizedEnterpriseHostForStorage() != state.githubEnterpriseHost
                if (locationChanged) {
                    state.setLocation(selectedLocation)
                }
                if (displayModeChanged) {
                    state.setDisplayMode(sanitizedDisplayMode)
                }
                if (sourceChanged) {
                    state.setSource(selectedSource)
                }
                hideFromPopupCheckBoxes().forEach { (type, checkBox) ->
                    state.setHiddenFromPopup(type, checkBox.isSelected)
                }
                state.minimaxRegionPreference = (miniMaxPanel().regionComboBox.selectedItem as? MiniMaxRegionPreference ?: MiniMaxRegionPreference.AUTO).name
                if (providerOrderChanged) {
                    state.providerOrder = providerReorderPanel?.getOrder()?.joinToString(",") { it.id } ?: state.providerOrder
                }
                state.syncIntellijMcpServerUrl = mcpSyncCheckBox?.isSelected == true
                state.mcpServerSyncTargets = normalizedMcpTargets.toMutableList()
                state.openAiProxyEnabled = openAiPanel.openAiProxyEnabledCheckBox.isSelected
                state.openAiProxyPort = OpenAiProxyService.sanitizePort(openAiPanel.proxyPort())
                state.openAiProxyLogRequests = openAiPanel.openAiProxyLogRequestsCheckBox.isSelected
                state.githubEnterpriseHost = gitHubPanel.normalizedEnterpriseHostForStorage()
                if (openAiProxyApiKeyChanged) {
                    openAiPanel.saveProxyApiKeyBlocking()
                }
                if (locationChanged || displayModeChanged || sourceChanged || popupVisibilityChanged || miniMaxRegionChanged || providerOrderChanged || mcpSyncChanged || mcpTargetsChanged || openAiProxyEnabledChanged || openAiProxyPortChanged || openAiProxyApiKeyChanged || openAiProxyLogRequestsChanged || gitHubEnterpriseHostChanged) {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(QuotaSettingsListener.TOPIC)
                        .onSettingsChanged()
                    McpServerUrlSyncService.getInstance().reloadFromSettings()
                    OpenAiProxyService.getInstance().reloadFromSettings()
                    openAiPanel.refreshAfterApply()
                    gitHubPanel.updateFields()
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
                hideFromPopupCheckBoxes().forEach { (type, checkBox) ->
                    checkBox.isSelected = QuotaSettingsState.getInstance().isHiddenFromPopup(type)
                }
                gitHubPanel().enterpriseHostField.text = QuotaSettingsState.getInstance().githubEnterpriseHost
                miniMaxPanel().regionComboBox.selectedItem = QuotaSettingsState.getInstance().miniMaxRegionPreference()
                providerReorderPanel?.setOrder(QuotaSettingsState.getInstance().providerOrderList())
                mcpSyncCheckBox?.isSelected = QuotaSettingsState.getInstance().syncIntellijMcpServerUrl
                mcpSyncTargets = normalizeTargets(QuotaSettingsState.getInstance().mcpServerSyncTargets).toMutableList()
                providerPanelsByType.values.forEach { providerPanel ->
                    providerPanel.updateFields()
                    providerPanel.updateResponseArea()
                }
            }

            onIsModified {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onIsModified false
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onIsModified false
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onIsModified false
                val state = QuotaSettingsState.getInstance()
                selectedLocation != state.location() ||
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode) != state.displayMode() ||
                    selectedSource != state.source() ||
                    hideFromPopupCheckBoxes().any { (type, checkBox) -> checkBox.isSelected != state.isHiddenFromPopup(type) } ||
                    miniMaxPanel().regionComboBox.selectedItem as? MiniMaxRegionPreference != state.miniMaxRegionPreference() ||
                    providerReorderPanel?.getOrder()?.joinToString(",") { it.id } != state.providerOrder ||
                    mcpSyncCheckBox?.isSelected != state.syncIntellijMcpServerUrl ||
                    normalizeTargets(mcpSyncTargets) != normalizeTargets(state.mcpServerSyncTargets) ||
                    openAiPanel().openAiProxyEnabledCheckBox.isSelected != state.openAiProxyEnabled ||
                    openAiPanel().isProxyPortModified() ||
                    openAiPanel().isProxyApiKeyModified() ||
                    openAiPanel().openAiProxyLogRequestsCheckBox.isSelected != state.openAiProxyLogRequests ||
                    gitHubPanel().normalizedEnterpriseHostForStorage() != state.githubEnterpriseHost
            }
        }.apply {
            preferredFocusedComponent = locationComboBox
        }
    }

    private fun openAiPanel(): OpenAiSettingsPanel = providerPanelsByType.getValue(QuotaProviderType.OPEN_AI) as OpenAiSettingsPanel

    private fun miniMaxPanel(): MiniMaxSettingsPanel = providerPanelsByType.getValue(QuotaProviderType.MINIMAX) as MiniMaxSettingsPanel

    private fun gitHubPanel(): GitHubSettingsPanel = providerPanelsByType.getValue(QuotaProviderType.GITHUB) as GitHubSettingsPanel

    private fun hideFromPopupCheckBoxes(): Map<QuotaProviderType, JBCheckBox> {
        return providerPanelsByType.mapValues { (_, panel) -> panel.hideFromPopupCheckBox }
    }

    private fun normalizeTargets(targets: List<McpServerSyncTarget>): List<McpServerSyncTarget> {
        return targets.map { it.normalized() }
    }

    companion object {
        private const val MCP_SERVER_STATUS_REFRESH_MILLIS = 5_000
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
