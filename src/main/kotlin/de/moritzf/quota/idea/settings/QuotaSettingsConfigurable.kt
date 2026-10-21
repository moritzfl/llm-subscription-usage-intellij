package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
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
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.mcp.McpServerStatusState
import de.moritzf.quota.idea.mcp.McpServerSyncTarget
import de.moritzf.quota.idea.mcp.McpServerUrlResolver
import de.moritzf.quota.idea.openai.OpenAiProxyService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.*
import de.moritzf.quota.idea.ui.settings.AccountDetailsSplitter
import de.moritzf.quota.idea.ui.settings.AccountListPanel
import de.moritzf.quota.idea.ui.settings.ProviderListStatus
import de.moritzf.quota.idea.ui.settings.ProviderReorderPanel
import de.moritzf.quota.shared.ProviderQuota
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 *
 * [Configurable.NoScroll]: the tabs scroll individually. A dialog-wide scroll pane sizes every tab
 * to the tallest and widest one.
 */
class QuotaSettingsConfigurable : Configurable, Configurable.NoScroll {
    private var rootComponent: JComponent? = null
    private var panel: DialogPanel? = null
    private var connection: MessageBusConnection? = null
    private var updatingDisplayModeChoices: Boolean = false

    private val providerPanelsByType = linkedMapOf<QuotaProviderType, ProviderSettingsPanel>()
    private val testApiKeyDrafts = mutableMapOf<String, String>()

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

    override fun getDisplayName(): String = message("settings.displayName")

    override fun createComponent(): JComponent? {
        val statusLabelDefaultForeground = UIManager.getColor("Label.foreground")

        providerPanelsByType.clear()
        val providerPanelContext =
            ProviderSettingsPanelContext(
                modalityComponentProvider = { panel ?: rootComponent },
                statusLabelDefaultForeground = statusLabelDefaultForeground,
            )
        providerPanelsByType.putAll(ProviderSettingsRegistry.createPanels(providerPanelContext))

        locationComboBox = createIndicatorComboBox(QuotaIndicatorLocation.entries.toTypedArray())
        displayModeComboBox = createIndicatorComboBox(QuotaDisplayMode.entries.toTypedArray())
        indicatorSourceComboBox =
            createIndicatorComboBox(QuotaIndicatorSource.entries.toTypedArray())
        displayModePreview = DisplayModePreviewComponent()
        mcpSyncCheckBox = JBCheckBox("Sync IntelliJ MCP server URL to JSON/TOML/YAML files")
        mcpSyncTargetsPanel = McpServerSyncTargetsPanel(emptyList())
        mcpServerStatusLabel = JBLabel()
        proxySettingsPanel =
            SubscriptionProxySettingsPanel(testSettingsProvider = ::captureProxyTestSettings) {
                panel ?: rootComponent
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

        serviceCardLayout = CardLayout()
        serviceCards =
            object : JPanel(serviceCardLayout) {
                    override fun getPreferredSize(): Dimension {
                        val size = super.getPreferredSize()
                        return Dimension(JBUI.scale(DETAIL_PREF_WIDTH), size.height)
                    }

                    override fun getMinimumSize(): Dimension {
                        return Dimension(JBUI.scale(200), JBUI.scale(80))
                    }
                }
                .apply { isOpaque = false }

        accountNameField = com.intellij.ui.components.JBTextField()
        primaryAccountCombo =
            ComboBox<ProviderAccount>().apply {
                renderer = AccountNameRenderer()
                prototypeDisplayValue = ProviderAccount(name = "firstname.lastname@example.com")
                toolTipText =
                    "Used first for MCP tools when no account is specified, and for the local proxy."
            }
        standbyCheckBox =
            JBCheckBox().apply {
                toolTipText =
                    "If this login is out of quota, MCP spend tools and the local proxy may use the other logins."
            }
        accountListPanel =
            AccountListPanel(
                onAccountSelected = { account -> showAccountDetail(account) },
                onAccountsChanged = { showAccountDetail(accountListPanel?.selectedAccount()) },
                onBeforeRemove = { flushVisibleAccountFields() },
            )

        rebuildServiceCards()

        panel = buildSettingsPanel()
        rootComponent = panel
        showAccountDetail(accountListPanel?.selectedAccount())

        connection = ApplicationManager.getApplication().messageBus.connect()
        connection!!.subscribe(
            QuotaUsageListener.TOPIC,
            object : QuotaUsageListener {
                override fun onQuotaUpdated(
                    type: QuotaProviderType,
                    quota: ProviderQuota?,
                    error: String?,
                    accountId: String,
                ) {
                    val providerPanel = providerPanelsByType[type] ?: return
                    val currentPanel = rootComponent ?: panel ?: return
                    ApplicationManager.getApplication()
                        .invokeLater(
                            {
                                val selected = accountListPanel?.selectedAccount()
                                if (
                                    selected != null &&
                                        (selected.id == accountId ||
                                            selected.providerType() == type)
                                ) {
                                    providerPanel.updateResponseArea()
                                    providerPanel.updateStatus()
                                    updateDetailHeaderReason(selected)
                                }
                                accountListPanel?.refreshStatuses()
                            },
                            ModalityState.stateForComponent(currentPanel),
                        )
                }
            },
        )

        reset()
        startMcpServerStatusRefresh()
        return rootComponent
    }

    override fun isModified(): Boolean {
        return panel?.isModified() == true
    }

    override fun apply() {
        persistAccountRouting()
        mcpSyncTargetsPanel?.validationError()?.let { error -> throw ConfigurationException(error) }
        if (
            accountListPanel?.hasDuplicateNames()
                ?: QuotaSettingsState.getInstance().hasDuplicateAccountNames()
        ) {
            throw ConfigurationException(
                "Account names must be unique per provider and cannot be blank."
            )
        }
        panel?.apply()
    }

    override fun reset() {
        testApiKeyDrafts.clear()
        providerPanelsByType.values.forEach { it.discardCredentialDrafts() }
        panel?.reset()
    }

    override fun cancel() {
        providerPanelsByType.values.forEach { it.discardCredentialDrafts() }
        compatiblePanels().forEach { it.discardDrafts() }
        accountListPanel?.discardPending()
        super.cancel()
    }

    override fun disposeUIResources() {
        testApiKeyDrafts.clear()
        providerPanelsByType.values.forEach { it.discardCredentialDrafts() }
        compatiblePanels().forEach { it.dispose() }
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
        mcpServerStatusTimer =
            Timer(MCP_SERVER_STATUS_REFRESH_MILLIS) { updateMcpServerStatus() }
                .apply {
                    isRepeats = true
                    start()
                }
    }

    private fun updateMcpServerStatus() {
        val label = mcpServerStatusLabel ?: return
        val status = McpServerUrlResolver.currentStatus()
        label.text =
            statusDotHtml(
                status.message,
                if (status.state == McpServerStatusState.RUNNING) AuthStatusKind.CONNECTED
                else AuthStatusKind.DISCONNECTED,
            )
        label.foreground = UIManager.getColor("Label.foreground") ?: label.foreground
        label.toolTipText = status.message
    }

    private fun updateDisplayModeChoices(preferredMode: QuotaDisplayMode? = null) {
        val combo = displayModeComboBox ?: return
        val location = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return
        val selectedMode =
            preferredMode
                ?: combo.selectedItem as? QuotaDisplayMode
                ?: QuotaSettingsState.getInstance().displayMode()
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
        providerPanelsByType.forEach { (type, panel) -> cards.add(panel, type.id) }
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
        compatiblePanels().forEach { it.cancelRequests() }
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
                iconLabel.icon =
                    ProviderReorderPanel.scaleToSize(
                        ProviderUiRegistry.forType(type).icon,
                        JBUI.scale(DETAIL_HEADER_ICON_SIZE),
                        iconLabel,
                    )
            }
            detailHeaderName?.text =
                accountListPanel?.listLabel(account)
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
        if (
            snapshot.status == ProviderListStatus.WARNING ||
                snapshot.status == ProviderListStatus.ERROR
        ) {
            reason.text = "<html>${QuotaUiUtil.escapeHtml(snapshot.explanation)}</html>"
            reason.foreground = ProviderReorderPanel.statusColor(snapshot.status)
            reason.isVisible = true
        } else {
            reason.isVisible = false
        }
    }

    private fun createDetailHeader(): JComponent {
        val icon = JBLabel()
        val name = JBLabel().apply { font = JBFont.h3().asBold() }
        val reason =
            JBLabel().apply {
                font = JBFont.small()
                isVisible = false
            }
        detailHeaderIcon = icon
        detailHeaderName = name
        detailHeaderReason = reason
        val titles =
            BorderLayoutPanel().apply {
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
            accountListPanel
                ?.accounts()
                ?.filter { it.typeId == typeId }
                ?.forEach { sibling ->
                    sibling.isDefault = sibling.id == selected.id
                    if (sibling.isDefault) sibling.allowFailover = false
                }
            accountListPanel?.selectedAccount()?.let(::updateRoutingControls)
        }
        standbyCheckBox?.addActionListener {
            if (updatingRouting) return@addActionListener
            val account = accountListPanel?.selectedAccount() ?: return@addActionListener
            val enabled = standbyCheckBox?.isSelected == true
            accountListPanel
                ?.accounts()
                ?.filter { it.typeId == account.typeId }
                ?.forEach { sibling -> sibling.allowFailover = enabled && !sibling.isDefault }
        }
        accountNameField
            ?.document
            ?.addDocumentListener(
                object : com.intellij.ui.DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) {
                        val account = accountListPanel?.selectedAccount() ?: return
                        account.name = accountNameField?.text.orEmpty()
                        refreshAccountNameDisplays(account)
                    }
                }
            )
        val identity =
            BorderLayoutPanel().apply {
                isOpaque = false
                addToLeft(icon)
                addToCenter(titles)
            }
        val visibility = BorderLayoutPanel().apply { isOpaque = false }
        visibilitySlot = visibility
        val accountGroup = panel {
            group("Account") {
                row("Name:") { cell(nameField).align(AlignX.FILL).resizableColumn() }
                row { cell(visibility).align(AlignX.FILL).resizableColumn() }
            }
        }
        routingPanel = panel {
            group(message("settings.integrations.group")) {
                row { cell(combo).align(AlignX.FILL).resizableColumn() }
                row { cell(standbyCheckBox!!) }
            }
        }
        setRoutingVisible(false)
        return BorderLayoutPanel()
            .apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(10)
                addToTop(identity)
                addToCenter(accountGroup)
            }
            .also { detailHeaderPanel = it }
    }

    private fun createEmptyDetail(): JComponent {
        return JBLabel("Add a provider").apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.GRAY
        }
    }

    private fun buildSettingsPanel(): DialogPanel {
        val proxyTab = verticalScrollPane(proxySettingsPanel!!)
        val tabs =
            JTabbedPane().apply {
                isOpaque = false
                addTab("Subscription Usage", buildSubscriptionUsageTab())
                addTab("MCP Synchronisation", verticalScrollPane(buildMcpSyncTab()))
                addTab("Proxy", proxyTab)
                addChangeListener {
                    if (selectedComponent === proxyTab)
                        proxySettingsPanel?.refreshDraftConnections()
                }
            }
        providerPanelsByType.forEach { (type, panel) ->
            if (ProviderCatalog.get(type).capabilities.subscriptionProxy) {
                panel.showProxySettingsLink { tabs.selectedComponent = proxyTab }
            }
        }
        return panel {
            // Shown once for the whole dialog: the setting is an IDE-wide one, and it drops the
            // logins, session cookies, and API keys of every provider alike. `text` wraps to the
            // width the dialog already has, so the warning never widens the settings window.
            row {
                    icon(AllIcons.General.Warning).align(AlignY.TOP).gap(RightGap.SMALL)
                    text(CredentialStorage.MEMORY_ONLY_WARNING).resizableColumn().align(AlignX.FILL)
                }
                .visible(CredentialStorage.isMemoryOnly())

            // resizableRow is what lets the tabs use the dialog height; without it every tab stays
            // at the preferred height of the largest one and the rest of the dialog stays empty.
            row {
                cell(tabs).resizableColumn().align(Align.FILL)
            }
                .resizableRow()

            onApply {
                val selectedLocation =
                    locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onApply
                val selectedDisplayMode =
                    displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onApply
                val selectedSource =
                    indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onApply
                val sanitizedDisplayMode =
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode)
                val state = QuotaSettingsState.getInstance()
                val draft = state.getState()
                val normalizedMcpTargets =
                    normalizeTargets(mcpSyncTargetsPanel?.targets().orEmpty())
                val proxyPanel = proxySettingsPanel ?: return@onApply
                val proxyApiKeyChanged = proxyPanel.isProxyApiKeyModified()
                draft.setLocation(selectedLocation)
                draft.setDisplayMode(sanitizedDisplayMode)
                draft.setSource(selectedSource)
                persistAccountRouting()
                draft.accounts =
                    accountListPanel?.accounts().orEmpty().map { it.snapshot() }.toMutableList()
                val keptIds = draft.accounts.map { it.id }.toSet()
                draft.syncIntellijMcpServerUrl = mcpSyncCheckBox?.isSelected == true
                draft.mcpServerSyncTargets = normalizedMcpTargets.toMutableList()
                draft.openAiProxyEnabled = proxyPanel.proxyEnabledCheckBox.isSelected
                draft.openAiProxyPort = OpenAiProxyService.sanitizePort(proxyPanel.proxyPort())
                draft.openAiProxyLogRequests = proxyPanel.proxyLogRequestsCheckBox.isSelected
                proxyPanel.applyProviderSelections(draft)
                proxyPanel.applyCompletionsSettings(draft)
                try {
                    SettingsApplyCoordinator(state)
                        .apply(
                            QuotaSettingsDraft.from(draft),
                            removed = accountListPanel?.removedAccounts().orEmpty(),
                            credentialsChanged =
                                proxyApiKeyChanged ||
                                    compatiblePanels().any { it.credentialsModified(keptIds) },
                            saveCredentials = {
                                try {
                                    proxyPanel.saveProxyApiKeyBlocking()
                                    compatiblePanels().forEach { it.saveCredentials(keptIds) }
                                } catch (failure: Exception) {
                                    failure.rethrowIfCancellation()
                                    throw ConfigurationException(
                                        "Could not save credentials to Password Safe. Please retry Apply."
                                    )
                                }
                            },
                        )
                } catch (failure: IllegalArgumentException) {
                    throw ConfigurationException(failure.message ?: "Invalid settings")
                }
                accountListPanel?.acceptPendingChanges()
                proxyPanel.refreshAfterApply()
                providerPanelsByType[QuotaProviderType.GITHUB]?.updateFields()
                providerPanelsByType[QuotaProviderType.OLLAMA]?.updateFields()
            }

            onReset {
                boundDetailAccountId = null
                compatiblePanels().forEach { it.discardDrafts() }
                locationComboBox?.selectedItem = QuotaSettingsState.getInstance().location()
                updateDisplayModeChoices(QuotaSettingsState.getInstance().displayMode())
                updateDisplayModePreview()
                indicatorSourceComboBox?.selectedItem = QuotaSettingsState.getInstance().source()
                accountListPanel?.discardPending()
                accountListPanel?.reloadFromSettings()
                showAccountDetail(accountListPanel?.selectedAccount())
                mcpSyncCheckBox?.isSelected =
                    QuotaSettingsState.getInstance().syncIntellijMcpServerUrl
                mcpSyncTargetsPanel?.setTargets(
                    normalizeTargets(QuotaSettingsState.getInstance().mcpServerSyncTargets)
                )
                providerPanelsByType.values.forEach { providerPanel ->
                    providerPanel.updateFields()
                    providerPanel.updateResponseArea()
                }
                accountListPanel?.refreshStatuses()
                proxySettingsPanel?.updateFields()
            }

            onIsModified {
                val selectedLocation =
                    locationComboBox?.selectedItem as? QuotaIndicatorLocation
                        ?: return@onIsModified false
                val selectedDisplayMode =
                    displayModeComboBox?.selectedItem as? QuotaDisplayMode
                        ?: return@onIsModified false
                val selectedSource =
                    indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource
                        ?: return@onIsModified false
                val state = QuotaSettingsState.getInstance()
                selectedLocation != state.location() ||
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode) !=
                        state.displayMode() ||
                    selectedSource != state.source() ||
                    compatiblePanels().any {
                        it.credentialsModified(
                            accountListPanel?.accounts().orEmpty().map { it.id }.toSet()
                        )
                    } ||
                    selectedAccountExtrasModified(state) ||
                    accountListPanel?.isModifiedVs(state) == true ||
                    accountListPanel?.hasDuplicateNames() == true ||
                    mcpSyncCheckBox?.isSelected != state.syncIntellijMcpServerUrl ||
                    normalizeTargets(mcpSyncTargetsPanel?.currentTargets().orEmpty()) !=
                        normalizeTargets(state.mcpServerSyncTargets) ||
                    proxySettingsPanel?.proxyEnabledCheckBox?.isSelected !=
                        state.openAiProxyEnabled ||
                    proxySettingsPanel?.isProxyPortModified() == true ||
                    proxySettingsPanel?.isProxyApiKeyModified() == true ||
                    proxySettingsPanel?.isProxyLogRequestsModified() == true ||
                    proxySettingsPanel?.isProviderSelectionModified() == true ||
                    proxySettingsPanel?.isCompletionsModified() == true
            }
        }
            .apply { preferredFocusedComponent = locationComboBox }
    }

    private fun buildSubscriptionUsageTab(): JComponent {
        val detail =
            BorderLayoutPanel().apply {
                background = UIUtil.getPanelBackground()
                isOpaque = true
                border = JBUI.Borders.empty(4, 12, 0, 0)
                addToTop(createDetailHeader())
                addToCenter(serviceCards!!)
            }
        val splitter = AccountDetailsSplitter(accountListPanel!!, detail)
        return BorderLayoutPanel().apply {
            background = UIUtil.getPanelBackground()
            isOpaque = true
            addToTop(
                panel {
                    row("Indicator location:") { cell(locationComboBox!!) }

                    row("Indicator display:") {
                        cell(displayModeComboBox!!)
                        cell(displayModePreview!!).gap(RightGap.SMALL)
                    }

                    row("Indicator quota source:") { cell(indicatorSourceComboBox!!) }
                }
            )
            addToCenter(
                BorderLayoutPanel().apply {
                    background = UIUtil.getPanelBackground()
                    isOpaque = true
                    border = JBUI.Borders.emptyTop(12)
                    addToCenter(splitter)
                }
            )
        }
    }

    private fun buildMcpSyncTab(): JComponent {
        return panel {
            row { cell(mcpSyncCheckBox!!) }

            row {
                comment(
                    "IntelliJ's integrated MCP server can change its port from time to time. " +
                        "When sync is enabled, this plugin writes the current server URL to the selected agent " +
                        "configuration files on startup and after settings changes, so those agents keep pointing " +
                        "at the correct IntelliJ MCP server."
                )
            }

            row { cell(mcpServerStatusLabel!!).resizableColumn().align(AlignX.FILL) }

            row {
                comment(
                    "Use dot paths like mcpServers.jetbrains.url for JSON/TOML/YAML, or JSON Pointer paths like /mcpServers/jetbrains/url."
                )
            }

            row {
                cell(mcpSyncTargetsPanel!!).resizableColumn().align(Align.FILL)
            }
                .resizableRow()
        }
    }

    private fun flushVisibleAccountFields() {
        val id = boundDetailAccountId ?: return
        val account = accountListPanel?.accounts()?.firstOrNull { it.id == id } ?: return
        persistAccountFields(account)
    }

    private fun refreshAccountNameDisplays(account: ProviderAccount) {
        detailHeaderName?.text =
            accountListPanel?.listLabel(account)
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
        if (
            type == null ||
                siblings.size <= 1 ||
                de.moritzf.quota.idea.common.ProviderCatalog.get(type)
                    .capabilities
                    .independentProxyAccounts
        ) {
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
            standbyCheckBox?.text =
                "Also try my other ${type.displayName} logins if this one is exhausted"
            standbyCheckBox?.isSelected = siblings.any { !it.isDefault && it.allowFailover }
        } finally {
            updatingRouting = false
        }
    }

    private fun bindVisibilityToggle(type: QuotaProviderType, account: ProviderAccount) {
        if (!de.moritzf.quota.idea.common.ProviderCatalog.get(type).capabilities.quota) {
            visibilitySlot?.removeAll()
            visibilitySlot?.revalidate()
            visibilitySlot?.repaint()
            return
        }
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
        val panel = providerPanelsByType[account.providerType()]
        if (panel != null && panel.boundAccountId == account.id) {
            val key = panel.testConnection().apiKeyOverride
            if (key == null) testApiKeyDrafts.remove(account.id)
            else testApiKeyDrafts[account.id] = key
        }
        account.hiddenFromPopup = panel?.popupVisibilityToggle?.isHidden == true
        panel?.writeAccountFields(account)
    }

    private fun selectedAccountExtrasModified(state: QuotaSettingsState): Boolean {
        val selected = accountListPanel?.selectedAccount() ?: return false
        val panel = providerPanelsByType[selected.providerType()] ?: return false
        val persisted = state.account(selected.id)
        return panel.popupVisibilityToggle.isHidden !=
            (persisted?.hiddenFromPopup ?: selected.hiddenFromPopup) ||
            panel.accountFieldsModified(selected, persisted, state)
    }

    private fun captureProxyTestSettings(): ProxyTestSettings {
        persistAccountRouting()
        val draft = QuotaSettingsState.getInstance().state
        draft.accounts =
            (accountListPanel?.accounts() ?: draft.accounts).map { it.snapshot() }.toMutableList()
        draft.syncLegacyAccountFields()
        val ids = draft.accounts.map { it.id }.toSet()
        val keys = testApiKeyDrafts.filterKeys { it in ids }.toMutableMap()
        providerPanelsByType.forEach { (type, panel) ->
            val liveDrafts = panel.testApiKeyDrafts() ?: return@forEach
            draft.accounts.filter { it.providerType() == type }.forEach { keys.remove(it.id) }
            keys.putAll(liveDrafts.filterKeys { it in ids })
        }
        return ProxyTestSettings(draft, keys)
    }

    private fun compatiblePanels(): List<CompatibleSettingsPanel> =
        providerPanelsByType.values.filterIsInstance<CompatibleSettingsPanel>()

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
            val component =
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
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
        private val previewIconLabel =
            JBLabel().apply {
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            }
        private val percentagePreview =
            QuotaPercentageIndicator().apply {
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
