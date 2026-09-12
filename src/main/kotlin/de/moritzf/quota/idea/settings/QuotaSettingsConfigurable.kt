package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.CredentialStorage
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
import de.moritzf.quota.idea.ui.settings.AccountListPanel
import de.moritzf.quota.idea.ui.settings.ProviderListStatus
import de.moritzf.quota.idea.ui.settings.ProviderReorderPanel
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.shared.ProviderQuota
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.JTabbedPane

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
    private var accountListPanel: AccountListPanel? = null
    private var accountNameField: com.intellij.ui.components.JBTextField? = null
    private var routingPanel: JComponent? = null
    private var visibilitySlot: BorderLayoutPanel? = null
    private var primaryAccountCombo: ComboBox<ProviderAccount>? = null
    private var standbyCheckBox: JBCheckBox? = null
    private var updatingRouting: Boolean = false
    private var serviceCards: JPanel? = null
    private var serviceCardLayout: CardLayout? = null
    private var detailHeaderPanel: JComponent? = null
    private var detailHeaderIcon: JBLabel? = null
    private var detailHeaderName: JBLabel? = null
    private var detailHeaderReason: JBLabel? = null
    private var mcpSyncCheckBox: JBCheckBox? = null
    private var mcpServerStatusLabel: JBLabel? = null
    private var mcpServerStatusTimer: Timer? = null
    private var mcpSyncTargetsPanel: McpServerSyncTargetsPanel? = null
    private var proxySettingsPanel: SubscriptionProxySettingsPanel? = null
    private var boundDetailAccountId: String? = null

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
        mcpSyncTargetsPanel = McpServerSyncTargetsPanel(emptyList())
        mcpServerStatusLabel = JBLabel()
        proxySettingsPanel = SubscriptionProxySettingsPanel { panel ?: rootComponent }

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

        serviceCardLayout = CardLayout()
        serviceCards = object : JPanel(serviceCardLayout) {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                return Dimension(JBUI.scale(DETAIL_PREF_WIDTH), size.height)
            }

            override fun getMinimumSize(): Dimension {
                return Dimension(JBUI.scale(200), JBUI.scale(80))
            }
        }.apply {
            isOpaque = false
        }

        accountNameField = com.intellij.ui.components.JBTextField()
        primaryAccountCombo = ComboBox<ProviderAccount>().apply {
            renderer = AccountNameRenderer()
            prototypeDisplayValue = ProviderAccount(name = "OpenAI 2")
            toolTipText = "Used first for MCP tools when no account is specified, and for the local proxy."
        }
        standbyCheckBox = JBCheckBox().apply {
            toolTipText = "If this login is out of quota, MCP spend tools and the local proxy may use the other logins."
        }
        accountListPanel = AccountListPanel(
            onAccountSelected = { account -> showAccountDetail(account) },
            onAccountsChanged = {
                showAccountDetail(accountListPanel?.selectedAccount())
            },
            onBeforeRemove = { flushVisibleAccountFields() },
        )

        rebuildServiceCards()

        panel = buildSettingsPanel()
        rootComponent = panel
        showAccountDetail(accountListPanel?.selectedAccount())

        connection = ApplicationManager.getApplication().messageBus.connect()
        connection!!.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(type: QuotaProviderType, quota: ProviderQuota?, error: String?, accountId: String) {
                val providerPanel = providerPanelsByType[type] ?: return
                val currentPanel = rootComponent ?: panel ?: return
                ApplicationManager.getApplication().invokeLater({
                    val selected = accountListPanel?.selectedAccount()
                    if (selected != null && (selected.id == accountId || selected.providerType() == type)) {
                        providerPanel.updateResponseArea()
                        providerPanel.updateStatus()
                        updateDetailHeaderReason(selected)
                    }
                    accountListPanel?.refreshStatuses()
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
        mcpSyncTargetsPanel?.validationError()?.let { error ->
            throw ConfigurationException(error)
        }
        if (accountListPanel?.hasDuplicateNames() ?: QuotaSettingsState.getInstance().hasDuplicateAccountNames()) {
            throw ConfigurationException("Account names must be unique per provider and cannot be blank.")
        }
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun cancel() {
        accountListPanel?.discardPending()
        super.cancel()
    }

    override fun disposeUIResources() {
        accountListPanel?.discardPending()
        connection?.disconnect()
        connection = null
        rootComponent = null
        panel = null
        locationComboBox = null
        displayModeComboBox = null
        indicatorSourceComboBox = null
        displayModePreview = null
        accountListPanel = null
        accountNameField = null
        routingPanel = null
        visibilitySlot = null
        primaryAccountCombo = null
        standbyCheckBox = null
        updatingRouting = false
        serviceCards = null
        serviceCardLayout = null
        detailHeaderPanel = null
        detailHeaderIcon = null
        detailHeaderName = null
        detailHeaderReason = null
        updatingDisplayModeChoices = false
        providerPanelsByType.clear()
        mcpSyncCheckBox = null
        mcpServerStatusTimer?.stop()
        mcpServerStatusTimer = null
        mcpServerStatusLabel = null
        mcpSyncTargetsPanel = null
        proxySettingsPanel = null
        boundDetailAccountId = null
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
        cards.add(createEmptyDetail(), EMPTY_CARD_ID)
        val selected = accountListPanel?.selectedAccount()
        if (selected == null) {
            serviceCardLayout?.show(cards, EMPTY_CARD_ID)
        } else {
            serviceCardLayout?.show(cards, selected.typeId)
        }
    }

    private fun showAccountDetail(account: ProviderAccount?) {
        flushVisibleAccountFields()
        boundDetailAccountId = account?.id
        val type = account?.providerType()
        if (account == null || type == null) {
            providerPanelsByType.values.forEach { panel ->
                panel.boundAccountId = ""
                panel.boundAccount = null
            }
            detailHeaderPanel?.isVisible = false
            visibilitySlot?.removeAll()
            setRoutingVisible(false)
            serviceCardLayout?.show(serviceCards, EMPTY_CARD_ID)
        } else {
            detailHeaderPanel?.isVisible = true
            val iconLabel = detailHeaderIcon
            if (iconLabel != null) {
                iconLabel.icon = ProviderReorderPanel.scaleToSize(
                    ProviderUiRegistry.forType(type).icon,
                    JBUI.scale(DETAIL_HEADER_ICON_SIZE),
                    iconLabel,
                )
            }
            detailHeaderName?.text = accountListPanel?.listLabel(account)
                ?: QuotaSettingsState.getInstance().accountListLabel(account)
            accountNameField?.text = account.name
            updateRoutingControls(account)
            updateDetailHeaderReason(account)
            bindVisibilityToggle(type, account)
            bindPanelsToAccount(account)
            serviceCardLayout?.show(serviceCards, type.id)
        }
        detailHeaderPanel?.revalidate()
        serviceCards?.revalidate()
        serviceCards?.repaint()
    }

    private fun bindPanelsToAccount(account: ProviderAccount) {
        providerPanelsByType.values.forEach { panel ->
            panel.boundAccountId = account.id
            panel.boundAccount = account
        }
        providerPanelsByType[account.providerType()]?.let { panel ->
            panel.updateFields()
            panel.updateStatus()
            panel.updateResponseArea()
        }
    }

    private fun updateDetailHeaderReason(account: ProviderAccount) {
        val reason = detailHeaderReason ?: return
        val snapshot = ProviderReorderPanel.statusSnapshot(account)
        if (snapshot.status == ProviderListStatus.WARNING || snapshot.status == ProviderListStatus.ERROR) {
            reason.text = "<html>${QuotaUiUtil.escapeHtml(snapshot.explanation)}</html>"
            reason.foreground = ProviderReorderPanel.statusColor(snapshot.status)
            reason.isVisible = true
        } else {
            reason.isVisible = false
        }
    }

    private fun createDetailHeader(): JComponent {
        val icon = JBLabel()
        val name = JBLabel().apply {
            font = JBFont.h3().asBold()
        }
        val reason = JBLabel().apply {
            font = JBFont.small()
            isVisible = false
        }
        detailHeaderIcon = icon
        detailHeaderName = name
        detailHeaderReason = reason
        val titles = BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            addToTop(name)
            addToCenter(reason)
        }
        val nameField = accountNameField!!
        val combo = primaryAccountCombo!!
        combo.addActionListener {
            if (updatingRouting) return@addActionListener
            val selected = combo.selectedItem as? ProviderAccount ?: return@addActionListener
            val typeId = selected.typeId
            accountListPanel?.accounts()?.filter { it.typeId == typeId }?.forEach { sibling ->
                sibling.isDefault = sibling.id == selected.id
                if (sibling.isDefault) sibling.allowFailover = false
            }
            accountListPanel?.selectedAccount()?.let(::updateRoutingControls)
        }
        standbyCheckBox?.addActionListener {
            if (updatingRouting) return@addActionListener
            val account = accountListPanel?.selectedAccount() ?: return@addActionListener
            val enabled = standbyCheckBox?.isSelected == true
            accountListPanel?.accounts()?.filter { it.typeId == account.typeId }?.forEach { sibling ->
                sibling.allowFailover = enabled && !sibling.isDefault
            }
        }
        accountNameField?.document?.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val account = accountListPanel?.selectedAccount() ?: return
                account.name = accountNameField?.text.orEmpty()
                refreshAccountNameDisplays(account)
            }
        })
        val identity = BorderLayoutPanel().apply {
            isOpaque = false
            addToLeft(icon)
            addToCenter(titles)
        }
        val visibility = BorderLayoutPanel().apply { isOpaque = false }
        visibilitySlot = visibility
        val accountGroup = panel {
            group("Account") {
                row("Name:") {
                    cell(nameField).align(AlignX.FILL).resizableColumn()
                }
                row {
                    cell(visibility).align(AlignX.FILL).resizableColumn()
                }
            }
        }
        routingPanel = panel {
            group("MCP tools & local proxy") {
                row {
                    cell(combo)
                }
                row {
                    cell(standbyCheckBox!!)
                }
            }
        }
        setRoutingVisible(false)
        return BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(10)
            addToTop(identity)
            addToCenter(accountGroup)
        }.also { detailHeaderPanel = it }
    }

    private fun createEmptyDetail(): JComponent {
        return JBLabel("Add a provider").apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.GRAY
        }
    }

    private fun buildSettingsPanel(): DialogPanel {
        val tabs = JTabbedPane().apply {
            isOpaque = false
            addTab("Subscription Usage", buildSubscriptionUsageTab())
            addTab("MCP Synchronisation", buildMcpSyncTab())
            addTab("Proxy", proxySettingsPanel!!)
        }
        return panel {
            // Shown once for the whole dialog: the setting is an IDE-wide one, and it drops the
            // logins, session cookies, and API keys of every provider alike. `text` wraps to the
            // width the dialog already has, so the warning never widens the settings window.
            row {
                icon(AllIcons.General.Warning).align(AlignY.TOP).gap(RightGap.SMALL)
                text(CredentialStorage.MEMORY_ONLY_WARNING)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }.visible(CredentialStorage.isMemoryOnly())

            // resizableRow is what lets the tabs use the dialog height; without it every tab stays
            // at the preferred height of the largest one and the rest of the dialog stays empty.
            row {
                cell(tabs)
                    .resizableColumn()
                    .align(Align.FILL)
            }.resizableRow()

            onApply {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onApply
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onApply
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onApply
                val sanitizedDisplayMode = QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode)
                val state = QuotaSettingsState.getInstance()
                val locationChanged = selectedLocation != state.location()
                val displayModeChanged = sanitizedDisplayMode != state.displayMode()
                val sourceChanged = selectedSource != state.source()
                val selectedAccount = accountListPanel?.selectedAccount()
                val popupVisibilityChanged = selectedAccount != null &&
                    providerPanelsByType[selectedAccount.providerType()]?.popupVisibilityToggle?.isHidden != selectedAccount.hiddenFromPopup
                val miniMaxRegionChanged = selectedAccount?.providerType() == QuotaProviderType.MINIMAX &&
                    miniMaxPanel().regionComboBox.selectedItem as? MiniMaxRegionPreference != state.miniMaxRegionFor(selectedAccount.id)
                val accountsChanged = accountListPanel?.isModifiedVs(state) == true
                val normalizedMcpTargets = normalizeTargets(mcpSyncTargetsPanel?.targets().orEmpty())
                val mcpSyncChanged = mcpSyncCheckBox?.isSelected != state.syncIntellijMcpServerUrl
                val mcpTargetsChanged = normalizedMcpTargets != normalizeTargets(state.mcpServerSyncTargets)
                val proxyPanel = proxySettingsPanel ?: return@onApply
                val proxyEnabledChanged = proxyPanel.proxyEnabledCheckBox.isSelected != state.openAiProxyEnabled
                val proxyPortChanged = proxyPanel.isProxyPortModified()
                val proxyApiKeyChanged = proxyPanel.isProxyApiKeyModified()
                val proxyLogRequestsChanged = proxyPanel.isProxyLogRequestsModified()
                val proxyProviderSelectionChanged = proxyPanel.isProviderSelectionModified()
                val proxyCompletionsChanged = proxyPanel.isCompletionsModified()
                val gitHubPanel = gitHubPanel()
                val gitHubEnterpriseHostChanged = selectedAccount?.providerType() == QuotaProviderType.GITHUB &&
                    gitHubPanel.normalizedEnterpriseHostForStorage() != state.githubHostFor(selectedAccount.id)
                if (locationChanged) {
                    state.setLocation(selectedLocation)
                }
                if (displayModeChanged) {
                    state.setDisplayMode(sanitizedDisplayMode)
                }
                if (sourceChanged) {
                    state.setSource(selectedSource)
                }
                    persistAccountRouting()
                    accountListPanel?.applyPendingChanges(state)
                state.syncIntellijMcpServerUrl = mcpSyncCheckBox?.isSelected == true
                state.mcpServerSyncTargets = normalizedMcpTargets.toMutableList()
                state.openAiProxyEnabled = proxyPanel.proxyEnabledCheckBox.isSelected
                state.openAiProxyPort = OpenAiProxyService.sanitizePort(proxyPanel.proxyPort())
                state.openAiProxyLogRequests = proxyPanel.proxyLogRequestsCheckBox.isSelected
                proxyPanel.applyProviderSelections(state)
                proxyPanel.applyCompletionsSettings(state)
                if (proxyApiKeyChanged) {
                    proxyPanel.saveProxyApiKeyBlocking()
                }
                if (locationChanged || displayModeChanged || sourceChanged || popupVisibilityChanged || miniMaxRegionChanged || accountsChanged || mcpSyncChanged || mcpTargetsChanged || proxyEnabledChanged || proxyPortChanged || proxyApiKeyChanged || proxyLogRequestsChanged || proxyProviderSelectionChanged || proxyCompletionsChanged || gitHubEnterpriseHostChanged) {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(QuotaSettingsListener.TOPIC)
                        .onSettingsChanged()
                    McpServerUrlSyncService.getInstance().reloadFromSettings()
                    OpenAiProxyService.getInstance().reloadFromSettings()
                    proxyPanel.refreshAfterApply()
                    gitHubPanel.updateFields()
                    if (state.syncIntellijMcpServerUrl) {
                        McpServerUrlSyncService.getInstance().syncNowAsync()
                    }
                    ActivityTracker.getInstance().inc()
                }
            }

            onReset {
                boundDetailAccountId = null
                locationComboBox?.selectedItem = QuotaSettingsState.getInstance().location()
                updateDisplayModeChoices(QuotaSettingsState.getInstance().displayMode())
                updateDisplayModePreview()
                indicatorSourceComboBox?.selectedItem = QuotaSettingsState.getInstance().source()
                accountListPanel?.discardPending()
                accountListPanel?.reloadFromSettings()
                showAccountDetail(accountListPanel?.selectedAccount())
                mcpSyncCheckBox?.isSelected = QuotaSettingsState.getInstance().syncIntellijMcpServerUrl
                mcpSyncTargetsPanel?.setTargets(normalizeTargets(QuotaSettingsState.getInstance().mcpServerSyncTargets))
                providerPanelsByType.values.forEach { providerPanel ->
                    providerPanel.updateFields()
                    providerPanel.updateResponseArea()
                }
                accountListPanel?.refreshStatuses()
                proxySettingsPanel?.updateFields()
            }

            onIsModified {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onIsModified false
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onIsModified false
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onIsModified false
                val state = QuotaSettingsState.getInstance()
                selectedLocation != state.location() ||
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode) != state.displayMode() ||
                    selectedSource != state.source() ||
                    selectedAccountExtrasModified(state) ||
                    accountListPanel?.isModifiedVs(state) == true ||
                    accountListPanel?.hasDuplicateNames() == true ||
                    mcpSyncCheckBox?.isSelected != state.syncIntellijMcpServerUrl ||
                    normalizeTargets(mcpSyncTargetsPanel?.currentTargets().orEmpty()) != normalizeTargets(state.mcpServerSyncTargets) ||
                    proxySettingsPanel?.proxyEnabledCheckBox?.isSelected != state.openAiProxyEnabled ||
                    proxySettingsPanel?.isProxyPortModified() == true ||
                    proxySettingsPanel?.isProxyApiKeyModified() == true ||
                    proxySettingsPanel?.isProxyLogRequestsModified() == true ||
                    proxySettingsPanel?.isProviderSelectionModified() == true ||
                    proxySettingsPanel?.isCompletionsModified() == true
            }
        }.apply {
            preferredFocusedComponent = locationComboBox
        }
    }

    private fun buildSubscriptionUsageTab(): JComponent {
        val detail = BorderLayoutPanel().apply {
            background = UIUtil.getPanelBackground()
            isOpaque = true
            border = JBUI.Borders.empty(4, 12, 0, 0)
            addToTop(createDetailHeader())
            addToCenter(serviceCards!!)
        }
        val splitter = OnePixelSplitter(false, 0.32f, 0.22f, 0.5f).apply {
            firstComponent = accountListPanel
            secondComponent = detail
            setHonorComponentsMinimumSize(false)
        }
        return BorderLayoutPanel().apply {
            background = UIUtil.getPanelBackground()
            isOpaque = true
            addToTop(panel {
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
            })
            addToCenter(BorderLayoutPanel().apply {
                background = UIUtil.getPanelBackground()
                isOpaque = true
                border = JBUI.Borders.emptyTop(12)
                addToCenter(splitter)
            })
        }
    }

    private fun buildMcpSyncTab(): JComponent {
        return panel {
            row {
                cell(mcpSyncCheckBox!!)
            }

            row {
                cell(mcpSyncDescriptionLabel())
                    .resizableColumn()
                    .align(Align.FILL)
            }

            row {
                cell(mcpServerStatusLabel!!)
            }

            row {
                cell(mcpSyncTargetsPanel!!)
                    .resizableColumn()
                    .align(Align.FILL)
            }.resizableRow()
        }
    }

    private fun mcpSyncDescriptionLabel(): JBLabel {
        return JBLabel(
            "<html><body width='520'>IntelliJ's integrated MCP server can change its port from time to time. " +
                "When sync is enabled, this plugin writes the current server URL to the selected agent " +
                "configuration files on startup and after settings changes, so those agents keep pointing " +
                "at the correct IntelliJ MCP server.</body></html>",
        ).apply {
            foreground = JBColor.GRAY
        }
    }

    private fun flushVisibleAccountFields() {
        val id = boundDetailAccountId ?: return
        val account = accountListPanel?.accounts()?.firstOrNull { it.id == id } ?: return
        persistAccountFields(account)
    }

    private fun refreshAccountNameDisplays(account: ProviderAccount) {
        detailHeaderName?.text = accountListPanel?.listLabel(account)
            ?: QuotaSettingsState.getInstance().accountListLabel(account)
        accountListPanel?.refreshStatuses()
        val combo = primaryAccountCombo ?: return
        combo.revalidate()
        combo.repaint()
        combo.popup?.list?.repaint()
    }

    private fun updateRoutingControls(account: ProviderAccount) {
        val type = account.providerType()
        val siblings = accountListPanel?.accounts()?.filter { it.typeId == type?.id }.orEmpty()
        if (type == null || siblings.size <= 1) {
            setRoutingVisible(false)
            return
        }
        setRoutingVisible(true)
        updatingRouting = true
        try {
            val combo = primaryAccountCombo ?: return
            combo.removeAllItems()
            siblings.forEach(combo::addItem)
            combo.selectedItem = siblings.firstOrNull { it.isDefault } ?: siblings.first()
            standbyCheckBox?.text = "Also try my other ${type.displayName} logins if this one is exhausted"
            standbyCheckBox?.isSelected = siblings.any { !it.isDefault && it.allowFailover }
        } finally {
            updatingRouting = false
        }
    }

    private fun bindVisibilityToggle(type: QuotaProviderType, account: ProviderAccount) {
        val toggle = providerPanelsByType[type]?.popupVisibilityToggle ?: return
        toggle.isHidden = account.hiddenFromPopup
        val slot = visibilitySlot ?: return
        if (toggle.parent != slot) {
            slot.removeAll()
            slot.addToCenter(toggle)
            slot.revalidate()
            slot.repaint()
        }
    }

    private fun setRoutingVisible(visible: Boolean) {
        val routing = routingPanel
        providerPanelsByType.values.forEach { it.showRouting(null) }
        if (!visible || routing == null) {
            routing?.isVisible = false
            return
        }
        routing.isVisible = true
        val type = accountListPanel?.selectedAccount()?.providerType()
        if (type != null) {
            providerPanelsByType[type]?.showRouting(routing)
        }
    }

    private fun persistAccountRouting() {
        val selected = accountListPanel?.selectedAccount() ?: return
        selected.name = accountNameField?.text.orEmpty().trim()
        persistAccountFields(selected)
    }

    private fun persistAccountFields(account: ProviderAccount) {
        account.hiddenFromPopup = providerPanelsByType[account.providerType()]?.popupVisibilityToggle?.isHidden == true
        when (account.providerType()) {
            QuotaProviderType.GITHUB ->
                account.setExtra(
                    ProviderAccount.EXTRA_GITHUB_HOST,
                    gitHubPanel().normalizedEnterpriseHostForStorage().ifEmpty { null },
                )
            QuotaProviderType.MINIMAX ->
                (miniMaxPanel().regionComboBox.selectedItem as? MiniMaxRegionPreference)?.let {
                    account.setExtra(ProviderAccount.EXTRA_MINIMAX_REGION, it.name)
                }
            QuotaProviderType.OPEN_CODE ->
                account.setExtra(ProviderAccount.EXTRA_OPENCODE_WORKSPACE, openCodePanel().selectedWorkspaceId())
            else -> Unit
        }
    }

    private fun selectedAccountExtrasModified(state: QuotaSettingsState): Boolean {
        val selected = accountListPanel?.selectedAccount() ?: return false
        val persisted = state.account(selected.id)
        val toggle = providerPanelsByType[selected.providerType()]?.popupVisibilityToggle
        if (toggle != null && toggle.isHidden != (persisted?.hiddenFromPopup ?: selected.hiddenFromPopup)) return true
        return when (selected.providerType()) {
            QuotaProviderType.GITHUB ->
                gitHubPanel().normalizedEnterpriseHostForStorage() != state.githubHostFor(selected.id)
            QuotaProviderType.MINIMAX ->
                miniMaxPanel().regionComboBox.selectedItem as? MiniMaxRegionPreference != state.miniMaxRegionFor(selected.id)
            QuotaProviderType.OPEN_CODE ->
                openCodePanel().selectedWorkspaceId() != state.openCodeWorkspaceIdFor(selected.id)
            else -> false
        }
    }

    private fun miniMaxPanel(): MiniMaxSettingsPanel = providerPanelsByType.getValue(QuotaProviderType.MINIMAX) as MiniMaxSettingsPanel

    private fun gitHubPanel(): GitHubSettingsPanel = providerPanelsByType.getValue(QuotaProviderType.GITHUB) as GitHubSettingsPanel

    private fun openCodePanel(): OpenCodeSettingsPanel =
        providerPanelsByType.getValue(QuotaProviderType.OPEN_CODE) as OpenCodeSettingsPanel

    private fun normalizeTargets(targets: List<McpServerSyncTarget>): List<McpServerSyncTarget> {
        return targets.map { it.normalized() }
    }

    private class AccountNameRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val account = value as? ProviderAccount
            text = account?.name?.trim()?.ifEmpty { account.id } ?: ""
            return component
        }
    }

    companion object {
        private const val MCP_SERVER_STATUS_REFRESH_MILLIS = 5_000
        private const val EMPTY_CARD_ID = "empty"
        private const val DETAIL_HEADER_ICON_SIZE = 20
        private const val DETAIL_PREF_WIDTH = 420
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
