package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.fim.FimModels
import de.moritzf.quota.idea.openai.AiCompletionSetupInspector
import de.moritzf.quota.idea.openai.CompletionsFimTestResult
import de.moritzf.quota.idea.openai.CompletionsFimTester
import de.moritzf.quota.idea.openai.OpenAiProxyApiKeyStore
import de.moritzf.quota.idea.openai.OpenAiProxyService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.swing.DefaultListCellRenderer
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.JToggleButton
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

internal class SubscriptionProxySettingsPanel(
    private val modalityComponentProvider: () -> JComponent? = { null },
) : BorderLayoutPanel() {
    val proxyEnabledCheckBox = JBCheckBox("Enable local subscription proxy")
    val proxyLogRequestsCheckBox = JBCheckBox("Log requests and responses to disk")
    val completionsEnabledCheckBox = JBCheckBox("Enable FIM completions endpoint (/v1/completions)")
    val completionsUseChatAdapterCheckBox = JBCheckBox("Adapt chat models via FIM adapter")
    val completionsPriorityCheckBox = JBCheckBox("Use fast/priority processing")

    private val providerCheckBoxes = QuotaSettingsState.SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS
        .associateWithTo(linkedMapOf()) { provider -> JBCheckBox(provider.displayName) }
    private val proxyPortField = JBTextField().apply {
        columns = 6
        toolTipText = "Loopback port for the local proxy server"
    }
    private val proxyApiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Local API key accepted by the subscription proxy"
    }
    private val hiddenProxyApiKeyEchoChar = proxyApiKeyField.echoChar
    private val toggleProxyApiKeyVisibilityButton = JToggleButton(AllIcons.Actions.Show).apply {
        isFocusable = false
        toolTipText = "Show API key"
        accessibleContext.accessibleName = "Show API key"
    }
    private val copyProxyBaseUrlButton = JButton("Copy Base URL", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy the proxy base URL to the clipboard"
    }
    private val copyProxyApiKeyButton = JButton("Copy", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy the API key to the clipboard"
        accessibleContext.accessibleName = "Copy API key"
    }
    private val generateProxyApiKeyButton = JButton("Generate").apply {
        toolTipText = "Generate a new API key; apply settings to save it"
        accessibleContext.accessibleName = "Generate API key"
    }
    private val showLogsButton = JButton("Show Logs").apply {
        toolTipText = "Open the request log directory"
    }
    private val proxyStatusLabel = JBLabel().apply { isVisible = false }
    private val proxyApiKeyHintLabel = JBLabel().apply { isVisible = false }
    private val providerStatusLabel = JBLabel().apply { isVisible = false }
    private val logsStatusLabel = JBLabel().apply { isVisible = false }
    private val completionsModelCombo = ComboBox<String>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when {
                    value == CompletionsConfig.FIM_ALIAS_ID -> CompletionsConfig.FIM_ALIAS_ID
                    value is String && value.isNotBlank() -> value
                    else -> "Select a model"
                }
                return label
            }
        }
        prototypeDisplayValue = "sg-grok-4.6"
    }
    private val completionsMaxTokensField = JBTextField().apply {
        columns = 6
        toolTipText = "Hard cap for FIM output tokens"
    }
    private val completionsRpmField = JBTextField().apply {
        columns = 6
        toolTipText = "Maximum FIM upstream requests per minute"
    }
    private val completionsTimeoutField = JBTextField().apply {
        columns = 6
        toolTipText = "Seconds before this plugin aborts a FIM request. JetBrains AI Completion waits ${CompletionsConfig.JETBRAINS_AI_COMPLETION_REQUEST_TIMEOUT_SECONDS}s (${CompletionsConfig.JETBRAINS_AI_COMPLETION_CONNECT_TIMEOUT_SECONDS}s to connect)."
    }
    private val fimIdeModelIdField = JBTextField(CompletionsConfig.FIM_ALIAS_ID).apply {
        isEditable = false
        columns = 24
        toolTipText = "Paste this into JetBrains AI Completion → Model. It always maps to the backend model below."
    }
    private val copyCompletionsModelButton = JButton("Copy", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy ${CompletionsConfig.FIM_ALIAS_ID} for JetBrains AI Completion → Model"
        accessibleContext.accessibleName = "Copy IDE model id"
    }
    private val testFimButton = JButton("Test FIM").apply {
        toolTipText = "Send a sample completion request through the proxy"
    }
    private val completionsHelpLabel = JBLabel(
        "<html><body width='520'>Point JetBrains AI Completion at this proxy:<br>" +
            "Settings → Tools → AI Assistant → Providers &amp; API keys → AI Completion<br>" +
            "Provider: OpenAI Compatible<br>" +
            "Base URL: Copy Base URL (no /v1)<br>" +
            "API key: Copy API Key<br>" +
            "Model: ${CompletionsConfig.FIM_ALIAS_ID}<br>" +
            "Prompt schema: Auto (our model id ${CompletionsConfig.FIM_ALIAS_ID} is recognized as (fim) Qwen). Not Zeta/Sweep.<br>" +
            "Switch the backend model here; leave the IDE model id unchanged.<br>" +
            "Type and wait for gray ghost text. Call Inline Completion often does nothing with this custom model — Find Action → Trigger Next Edit. Not Ctrl+Space.</body></html>",
    ).apply {
        foreground = JBColor.GRAY
    }
    private val completionsStatusLabel = JBLabel().apply { isVisible = false }
    private val fimSetupStatusLabel = JBLabel().apply { isVisible = false }
    private val proxyDescriptionLabel = JBLabel(
        "<html><body width='520'>Use the copied base URL and API key to configure this proxy in JetBrains AI Assistant " +
            "under Providers and API keys, or in Junie CLI as a LiteLLM proxy.</body></html>",
    ).apply {
        foreground = JBColor.GRAY
    }
    private val modelPreview = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        margin = JBUI.insets(6)
        text = "No models loaded yet."
    }

    private val proxyApiKeyLoadGeneration = AtomicLong(0)
    private val modelPreviewGeneration = AtomicLong(0)
    private val proxyStatusRefreshTimer = Timer(PROXY_STATUS_REFRESH_MILLIS) { updateProxyStatus() }.apply {
        isRepeats = true
    }
    private val copiedFeedbackTimers = HashMap<JButton, Timer>()
    private var pendingCompletionsModelId: String = ""
    private var savedProxyApiKey: String? = null
    private var proxyApiKeyLoading = false
    private var proxyApiKeyLoadError: String? = null

    init {
        copyProxyBaseUrlButton.addActionListener {
            val status = OpenAiProxyService.getInstance().status()
            val baseUrl = if (status.running) status.baseUrl else OpenAiProxyService.localBaseUrl(proxyPort())
            copyToClipboard(baseUrl)
            showCopiedFeedback(copyProxyBaseUrlButton)
        }
        copyProxyApiKeyButton.addActionListener {
            val apiKey = proxyApiKey() ?: return@addActionListener
            copyToClipboard(apiKey)
            showCopiedFeedback(copyProxyApiKeyButton)
        }
        generateProxyApiKeyButton.addActionListener {
            setProxyApiKeyText(OpenAiProxyApiKeyStore.getInstance().generateApiKeyForEditing())
        }
        toggleProxyApiKeyVisibilityButton.addActionListener {
            updateProxyApiKeyVisibility()
        }
        showLogsButton.addActionListener {
            openRequestLogs()
        }
        copyCompletionsModelButton.addActionListener {
            copyToClipboard(CompletionsConfig.FIM_ALIAS_ID)
            showCopiedFeedback(copyCompletionsModelButton)
        }
        testFimButton.addActionListener { testFim() }
        completionsEnabledCheckBox.addItemListener {
            updateProxyControlsEnabled()
            updateProxyStatus()
        }
        completionsUseChatAdapterCheckBox.addItemListener { updateProxyStatus() }
        completionsPriorityCheckBox.addItemListener { updateProxyStatus() }
        completionsModelCombo.addActionListener { updateProxyStatus() }
        onDocumentChange(completionsMaxTokensField) { updateProxyStatus() }
        onDocumentChange(completionsRpmField) { updateProxyStatus() }
        onDocumentChange(completionsTimeoutField) { updateProxyStatus() }

        proxyEnabledCheckBox.addItemListener {
            updateProxyControlsEnabled()
            updateProviderControls()
            updateProxyStatus()
        }
        proxyLogRequestsCheckBox.addItemListener { updateProxyStatus() }
        providerCheckBoxes.values.forEach { checkBox ->
            checkBox.addItemListener {
                updateProviderStatus()
                updateProxyStatus()
                refreshModelPreviewAsync()
            }
        }
        onDocumentChange(proxyPortField) { updateProxyStatus() }
        onDocumentChange(proxyApiKeyField) {
            updateProxyApiKeyHint()
            updateProxyStatus()
        }

        copyProxyBaseUrlButton.preferredSize = copyProxyBaseUrlButton.preferredSize
        copyProxyApiKeyButton.preferredSize = copyProxyApiKeyButton.preferredSize

        addToTop(panel {
            row {
                cell(proxyEnabledCheckBox)
                    .comment("Serves one OpenAI-compatible localhost API for selected subscription-backed providers.")
            }
            row {
                cell(proxyDescriptionLabel)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            indent {
                row("Port:") {
                    cell(proxyPortField).gap(RightGap.SMALL)
                    cell(copyProxyBaseUrlButton)
                }
                row("API key:") {
                    cell(proxyApiKeyField)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .gap(RightGap.SMALL)
                    cell(toggleProxyApiKeyVisibilityButton).gap(RightGap.SMALL)
                    cell(copyProxyApiKeyButton).gap(RightGap.SMALL)
                    cell(generateProxyApiKeyButton)
                }
                row {
                    cell(proxyApiKeyHintLabel)
                }
                row {
                    cell(proxyLogRequestsCheckBox)
                        .comment(
                            "Writes full request and response bodies (prompts, tool output, file contents) to disk. " +
                                "Sensitive; leave off unless debugging.",
                        )
                }
                row {
                    cell(showLogsButton).gap(RightGap.SMALL)
                    cell(logsStatusLabel)
                }
                separator()
                row("Providers:") {
                    providerCheckBoxes.values.forEach { checkBox ->
                        cell(checkBox).gap(RightGap.SMALL)
                    }
                }
                row {
                    cell(providerStatusLabel)
                }
                row {
                    cell(proxyStatusLabel)
                }
                separator()
                row {
                    cell(completionsEnabledCheckBox)
                        .comment("Off by default. Official inline completion fires often and will use subscription quota.")
                }
                indent {
                    row {
                        cell(completionsHelpLabel)
                            .resizableColumn()
                            .align(AlignX.FILL)
                    }
                    row("IDE model id:") {
                        cell(fimIdeModelIdField)
                            .gap(RightGap.SMALL)
                            .comment("Paste this into AI Completion → Model. Named like Qwen Coder so Auto picks (fim) Qwen.")
                        cell(copyCompletionsModelButton)
                    }
                    row("Backend model:") {
                        cell(completionsModelCombo)
                            .resizableColumn()
                            .align(AlignX.FILL)
                            .comment("Subscription model this plugin calls. Switch it here; keep the IDE model id as ${CompletionsConfig.FIM_ALIAS_ID}.")
                    }
                    row {
                        cell(completionsUseChatAdapterCheckBox)
                            .comment("On: translate FIM prompts to chat. Off: pass /v1/completions through for native infill models.")
                    }
                    row("Max output tokens:") {
                        cell(completionsMaxTokensField).gap(RightGap.SMALL)
                        cell(JBLabel("Max requests/min:")).gap(RightGap.SMALL)
                        cell(completionsRpmField)
                    }
                    row("Timeout (seconds):") {
                        cell(completionsTimeoutField)
                            .comment("This plugin aborts after this many seconds (default ${CompletionsConfig.DEFAULT_TIMEOUT_SECONDS}, max ${CompletionsConfig.MAX_TIMEOUT_SECONDS}). JetBrains AI Completion waits ${CompletionsConfig.JETBRAINS_AI_COMPLETION_REQUEST_TIMEOUT_SECONDS}s for the HTTP request (${CompletionsConfig.JETBRAINS_AI_COMPLETION_CONNECT_TIMEOUT_SECONDS}s to connect). A new keystroke cancels the in-flight call.")
                    }
                    row {
                        cell(completionsPriorityCheckBox)
                            .comment("Sends service_tier=priority to Grok and Codex. About 2× usage. No effect on other backend models.")
                    }
                    row {
                        cell(testFimButton).gap(RightGap.SMALL)
                        cell(completionsStatusLabel)
                    }
                    row {
                        cell(fimSetupStatusLabel)
                    }
                }
            }
        })
        addToCenter(BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            addToTop(JBLabel("Advertised models:"))
            addToCenter(JScrollPane(modelPreview).apply {
                preferredSize = Dimension(1, JBUI.scale(180))
                minimumSize = Dimension(1, JBUI.scale(120))
                border = JBUI.Borders.emptyTop(4)
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            })
        })
    }

    override fun addNotify() {
        super.addNotify()
        updateProxyStatus()
        proxyStatusRefreshTimer.start()
    }

    override fun removeNotify() {
        proxyStatusRefreshTimer.stop()
        super.removeNotify()
    }

    fun updateFields() {
        val settings = QuotaSettingsState.getInstance()
        proxyEnabledCheckBox.isSelected = settings.openAiProxyEnabled
        proxyLogRequestsCheckBox.isSelected = settings.openAiProxyLogRequests
        completionsEnabledCheckBox.isSelected = settings.proxyCompletionsEnabled
        completionsUseChatAdapterCheckBox.isSelected = settings.proxyCompletionsUseChatAdapter
        completionsPriorityCheckBox.isSelected = settings.proxyCompletionsPriorityTier
        completionsMaxTokensField.text = CompletionsConfig.clampMaxOutputTokens(settings.proxyCompletionsMaxOutputTokens).toString()
        completionsRpmField.text = CompletionsConfig.clampMaxRequestsPerMinute(settings.proxyCompletionsMaxRequestsPerMinute).toString()
        completionsTimeoutField.text = CompletionsConfig.clampTimeoutSeconds(settings.proxyCompletionsTimeoutSeconds).toString()
        pendingCompletionsModelId = settings.proxyCompletionsModelId.trim()
        proxyPortField.text = OpenAiProxyService.sanitizePort(settings.openAiProxyPort).toString()
        loadProxyApiKeyField()
        updateProviderControls()
        refreshModelPreviewAsync()
        updateProxyStatus()
    }

    fun refreshAfterApply() {
        proxyPortField.text = OpenAiProxyService.sanitizePort(QuotaSettingsState.getInstance().openAiProxyPort).toString()
        updateProviderControls()
        updateProxyApiKeyHint()
        updateProxyStatus()
        refreshModelPreviewAsync()
    }

    fun proxyPort(): Int = proxyPortOrNull() ?: OpenAiProxyService.DEFAULT_PORT

    fun isProxyPortModified(): Boolean {
        val configuredPort = OpenAiProxyService.sanitizePort(QuotaSettingsState.getInstance().openAiProxyPort)
        return proxyPortField.text.trim() != configuredPort.toString()
    }

    fun proxyApiKey(): String? = String(proxyApiKeyField.password).trim().ifBlank { null }

    fun isProxyApiKeyModified(): Boolean = proxyApiKey() != savedProxyApiKey

    fun isProxyLogRequestsModified(): Boolean =
        proxyLogRequestsCheckBox.isSelected != QuotaSettingsState.getInstance().openAiProxyLogRequests

    fun isProviderSelectionModified(): Boolean {
        val state = QuotaSettingsState.getInstance()
        return providerCheckBoxes.any { (provider, checkBox) ->
            isProviderConfigured(provider) && checkBox.isSelected != state.isSubscriptionProxyProviderEnabled(provider)
        }
    }

    fun saveProxyApiKeyBlocking() {
        val apiKey = proxyApiKey()
        OpenAiProxyApiKeyStore.getInstance().saveBlocking(apiKey)
        savedProxyApiKey = apiKey
        proxyApiKeyLoadError = null
        updateProxyApiKeyHint()
    }

    fun isCompletionsModified(): Boolean {
        val state = QuotaSettingsState.getInstance()
        return completionsEnabledCheckBox.isSelected != state.proxyCompletionsEnabled ||
            completionsUseChatAdapterCheckBox.isSelected != state.proxyCompletionsUseChatAdapter ||
            selectedCompletionsModelId() != state.proxyCompletionsModelId.trim() ||
            completionsMaxOutputTokens() != CompletionsConfig.clampMaxOutputTokens(state.proxyCompletionsMaxOutputTokens) ||
            completionsMaxRequestsPerMinute() != CompletionsConfig.clampMaxRequestsPerMinute(state.proxyCompletionsMaxRequestsPerMinute) ||
            completionsTimeoutSeconds() != CompletionsConfig.clampTimeoutSeconds(state.proxyCompletionsTimeoutSeconds) ||
            completionsPriorityCheckBox.isSelected != state.proxyCompletionsPriorityTier
    }

    fun applyCompletionsSettings(state: QuotaSettingsState) {
        state.proxyCompletionsEnabled = completionsEnabledCheckBox.isSelected
        state.proxyCompletionsUseChatAdapter = completionsUseChatAdapterCheckBox.isSelected
        state.proxyCompletionsModelId = selectedCompletionsModelId()
        state.proxyCompletionsMaxOutputTokens = completionsMaxOutputTokens()
        state.proxyCompletionsMaxRequestsPerMinute = completionsMaxRequestsPerMinute()
        state.proxyCompletionsTimeoutSeconds = completionsTimeoutSeconds()
        state.proxyCompletionsPriorityTier = completionsPriorityCheckBox.isSelected
        pendingCompletionsModelId = state.proxyCompletionsModelId
    }

    fun applyProviderSelections(state: QuotaSettingsState) {
        providerCheckBoxes.forEach { (provider, checkBox) ->
            if (isProviderConfigured(provider)) {
                state.setSubscriptionProxyProviderEnabled(provider, checkBox.isSelected)
            }
        }
    }

    private fun proxyPortOrNull(): Int? {
        val parsed = proxyPortField.text.trim().toIntOrNull() ?: return null
        return parsed.takeIf { it in 1..65535 }
    }

    private fun updateProviderControls() {
        val proxyControlsEnabled = proxyEnabledCheckBox.isSelected
        val state = QuotaSettingsState.getInstance()
        providerCheckBoxes.forEach { (provider, checkBox) ->
            val configured = isProviderConfigured(provider)
            checkBox.isEnabled = proxyControlsEnabled && configured
            checkBox.isSelected = configured && state.isSubscriptionProxyProviderEnabled(provider)
            checkBox.toolTipText = if (configured) {
                "Expose ${provider.displayName} models through the local proxy"
            } else {
                "Configure ${provider.displayName} credentials before enabling this provider"
            }
        }
        updateProviderStatus()
    }

    private fun updateProviderStatus() {
        val unconfigured = providerCheckBoxes.keys.filterNot(::isProviderConfigured)
        val selected = providerCheckBoxes.filter { (_, checkBox) -> checkBox.isSelected }.keys
        val message = when {
            selected.isNotEmpty() && unconfigured.isEmpty() -> "Selected providers: ${selected.joinToString { it.displayName }}"
            selected.isNotEmpty() -> "Selected providers: ${selected.joinToString { it.displayName }}. Log in to enable: ${unconfigured.joinToString { it.displayName }}."
            unconfigured.size == providerCheckBoxes.size -> "No providers are configured yet. Log in or add provider API keys first."
            else -> "Select at least one configured provider. Not configured: ${unconfigured.joinToString { it.displayName }}."
        }
        providerStatusLabel.text = message
        providerStatusLabel.foreground = UIUtil.getContextHelpForeground()
        providerStatusLabel.isVisible = true
    }

    private fun isProviderConfigured(provider: QuotaProviderType): Boolean {
        return de.moritzf.quota.idea.common.ProviderCatalog.get(provider)
            .isProxyConfigured(::refreshAfterCredentialsLoad)
    }

    private fun refreshAfterCredentialsLoad() {
        updateProviderControls()
        updateProxyStatus()
        refreshModelPreviewAsync()
    }

    private fun updateProxyControlsEnabled() {
        val enabled = proxyEnabledCheckBox.isSelected
        proxyPortField.isEnabled = enabled
        proxyApiKeyField.isEnabled = enabled
        toggleProxyApiKeyVisibilityButton.isEnabled = enabled
        generateProxyApiKeyButton.isEnabled = enabled
        copyProxyBaseUrlButton.isEnabled = enabled
        copyProxyApiKeyButton.isEnabled = enabled && proxyApiKey() != null
        proxyLogRequestsCheckBox.isEnabled = enabled
        showLogsButton.isEnabled = enabled || Files.isDirectory(OpenAiProxyService.getInstance().requestLogDir())
        val completionsEnabled = enabled && completionsEnabledCheckBox.isSelected
        completionsEnabledCheckBox.isEnabled = enabled
        completionsModelCombo.isEnabled = completionsEnabled
        fimIdeModelIdField.isEnabled = completionsEnabled
        completionsUseChatAdapterCheckBox.isEnabled = completionsEnabled
        completionsMaxTokensField.isEnabled = completionsEnabled
        completionsRpmField.isEnabled = completionsEnabled
        completionsTimeoutField.isEnabled = completionsEnabled
        completionsPriorityCheckBox.isEnabled =
            completionsEnabled && FimModels.supportsPriorityTier(selectedCompletionsModelId())
        copyCompletionsModelButton.isEnabled = completionsEnabled
        testFimButton.isEnabled = completionsEnabled
        updateFimSetupStatus()
    }

    fun updateProxyStatus() {
        updateProxyControlsEnabled()

        val settings = QuotaSettingsState.getInstance()
        val configuredEnabled = settings.openAiProxyEnabled
        val configuredPort = OpenAiProxyService.sanitizePort(settings.openAiProxyPort)
        val requestedPort = proxyPortOrNull()

        if (!proxyEnabledCheckBox.isSelected) {
            if (configuredEnabled) {
                setProxyStatus("Apply settings to stop the proxy", ProxyRunState.PENDING)
            } else {
                setProxyStatus("Proxy is off", ProxyRunState.OFF)
            }
            return
        }
        if (requestedPort == null) {
            setProxyStatus("Port must be a number between 1 and 65535", ProxyRunState.ERROR)
            return
        }
        if (!configuredEnabled) {
            setProxyStatus("Apply settings to start the proxy at ${OpenAiProxyService.localBaseUrl(requestedPort)}", ProxyRunState.PENDING)
            return
        }
        if (configuredPort != requestedPort || isProxyApiKeyModified() || isProxyLogRequestsModified() || isProviderSelectionModified() || isCompletionsModified()) {
            setProxyStatus("Apply settings to update the proxy configuration", ProxyRunState.PENDING)
            return
        }

        val proxyStatus = OpenAiProxyService.getInstance().status()
        when {
            proxyStatus.running -> setProxyStatus("Proxy running at ${proxyStatus.baseUrl}", ProxyRunState.RUNNING)
            proxyStatus.error != null -> setProxyStatus("Proxy failed to start: ${proxyStatus.error}", ProxyRunState.ERROR)
            else -> setProxyStatus("Proxy starting at ${proxyStatus.baseUrl}...", ProxyRunState.PENDING)
        }
    }

    private fun refreshModelPreviewAsync() {
        val generation = modelPreviewGeneration.incrementAndGet()
        modelPreview.text = "Loading advertised models..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { OpenAiProxyService.getInstance().advertisedModelsSnapshot() }
            ApplicationManager.getApplication().invokeLater({
                if (generation != modelPreviewGeneration.get()) return@invokeLater
                modelPreview.text = result.fold(
                    onSuccess = { models ->
                        refreshCompletionsModelCombo(models)
                        formatModelPreview(models)
                    },
                    onFailure = { error -> "Could not load models: ${error.message ?: error::class.java.simpleName}" },
                )
                modelPreview.setCaretPosition(0)
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun formatModelPreview(models: List<SubscriptionProxyModel>): String {
        if (models.isEmpty()) {
            return "No models advertised. Check provider selections and logins."
        }
        return models.groupBy { it.providerName }.entries.joinToString("\n\n") { (providerName, providerModels) ->
            buildString {
                append(providerName).append('\n')
                providerModels.forEach { model ->
                    append("  ").append(model.localId)
                    if (model.upstreamId != model.localId) append(" -> ").append(model.upstreamId)
                    append("  [").append(model.supportedRoutes.joinToString { it.normalizedPath }).append(']')
                    append('\n')
                }
            }.trimEnd()
        }
    }

    private fun loadProxyApiKeyField() {
        val store = OpenAiProxyApiKeyStore.getInstance()
        val cachedApiKey = store.cachedApiKey()
        if (cachedApiKey != null && proxyApiKey() == savedProxyApiKey) {
            savedProxyApiKey = cachedApiKey
            setProxyApiKeyText(cachedApiKey)
        }

        val generation = proxyApiKeyLoadGeneration.incrementAndGet()
        val fieldValueAtRequest = proxyApiKey()
        val savedApiKeyAtRequest = savedProxyApiKey
        proxyApiKeyLoading = cachedApiKey == null
        proxyApiKeyLoadError = null
        updateProxyApiKeyHint()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.loadFreshBlocking() }
            ApplicationManager.getApplication().invokeLater({
                if (generation != proxyApiKeyLoadGeneration.get()) return@invokeLater
                proxyApiKeyLoading = false
                result.fold(
                    onSuccess = { apiKey ->
                        savedProxyApiKey = apiKey
                        val currentFieldValue = proxyApiKey()
                        if (currentFieldValue == fieldValueAtRequest || currentFieldValue == savedApiKeyAtRequest) {
                            setProxyApiKeyText(apiKey)
                        }
                    },
                    onFailure = { error ->
                        if (cachedApiKey == null) {
                            proxyApiKeyLoadError =
                                "Could not load the API key from secure storage: ${error.message ?: error::class.java.simpleName}"
                        }
                    },
                )
                updateProxyApiKeyHint()
                updateProxyStatus()
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun setProxyApiKeyText(apiKey: String?) {
        proxyApiKeyField.text = apiKey.orEmpty()
    }

    private fun updateProxyApiKeyVisibility() {
        val visible = toggleProxyApiKeyVisibilityButton.isSelected
        proxyApiKeyField.echoChar = if (visible) 0.toChar() else hiddenProxyApiKeyEchoChar
        val action = if (visible) "Hide" else "Show"
        toggleProxyApiKeyVisibilityButton.toolTipText = "$action API key"
        toggleProxyApiKeyVisibilityButton.accessibleContext.accessibleName = "$action API key"
    }

    private fun updateProxyApiKeyHint() {
        val currentApiKey = proxyApiKey()
        val loadError = proxyApiKeyLoadError
        var isError = false
        val hint = when {
            proxyApiKeyLoading && currentApiKey == null -> "Loading API key from secure storage..."
            loadError != null -> {
                isError = true
                loadError
            }
            currentApiKey == null && savedProxyApiKey == null -> "No API key yet; generate one, then apply settings."
            !isProxyApiKeyModified() -> null
            currentApiKey == null -> "The API key will be removed from secure storage when settings are applied."
            else -> "New API key; apply settings to save it to secure storage."
        }
        proxyApiKeyHintLabel.foreground = if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        proxyApiKeyHintLabel.text = hint.orEmpty()
        proxyApiKeyHintLabel.isVisible = hint != null
    }

    private fun selectedCompletionsModelId(): String {
        return (completionsModelCombo.selectedItem as? String)?.trim().orEmpty()
    }

    private fun completionsMaxOutputTokens(): Int {
        val parsed = completionsMaxTokensField.text.trim().toIntOrNull()
            ?: CompletionsConfig.DEFAULT_MAX_OUTPUT_TOKENS
        return CompletionsConfig.clampMaxOutputTokens(parsed)
    }

    private fun completionsMaxRequestsPerMinute(): Int {
        val parsed = completionsRpmField.text.trim().toIntOrNull()
            ?: CompletionsConfig.DEFAULT_MAX_REQUESTS_PER_MINUTE
        return CompletionsConfig.clampMaxRequestsPerMinute(parsed)
    }

    private fun completionsTimeoutSeconds(): Int {
        val parsed = completionsTimeoutField.text.trim().toIntOrNull()
            ?: CompletionsConfig.DEFAULT_TIMEOUT_SECONDS
        return CompletionsConfig.clampTimeoutSeconds(parsed)
    }

    private fun refreshCompletionsModelCombo(models: List<SubscriptionProxyModel>) {
        val eligible = models.filter(FimModels::isEligible).map { it.localId }.distinct()
        val selected = selectedCompletionsModelId().ifBlank { pendingCompletionsModelId }
        completionsModelCombo.removeAllItems()
        eligible.forEach { completionsModelCombo.addItem(it) }
        when {
            selected.isNotBlank() && eligible.contains(selected) -> completionsModelCombo.selectedItem = selected
            selected.isNotBlank() -> {
                completionsModelCombo.addItem(selected)
                completionsModelCombo.selectedItem = selected
            }
            else -> completionsModelCombo.selectedItem = null
        }
        pendingCompletionsModelId = selectedCompletionsModelId()
    }

    private fun testFim() {
        if (isCompletionsModified() || isProxyPortModified() || isProxyApiKeyModified() ||
            isProxyLogRequestsModified() || isProviderSelectionModified() ||
            proxyEnabledCheckBox.isSelected != QuotaSettingsState.getInstance().openAiProxyEnabled
        ) {
            Messages.showErrorDialog(this, "Apply settings before testing FIM.", "Test FIM")
            return
        }
        val settings = QuotaSettingsState.getInstance()
        if (!settings.proxyCompletionsEnabled || settings.proxyCompletionsModelId.isBlank()) {
            Messages.showErrorDialog(this, "Enable FIM and select a completion model, then apply.", "Test FIM")
            return
        }
        val status = OpenAiProxyService.getInstance().status()
        if (!status.running) {
            Messages.showErrorDialog(this, "Proxy is not running.", "Test FIM")
            return
        }
        val apiKey = proxyApiKey()
        if (apiKey == null) {
            Messages.showErrorDialog(this, "Local API key is missing.", "Test FIM")
            return
        }
        testFimButton.isEnabled = false
        completionsStatusLabel.text = "Testing…"
        completionsStatusLabel.foreground = UIUtil.getContextHelpForeground()
        completionsStatusLabel.isVisible = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = CompletionsFimTester.test(status.baseUrl, apiKey)
            ApplicationManager.getApplication().invokeLater({
                completionsStatusLabel.isVisible = false
                updateProxyControlsEnabled()
                FimTestResultDialog(this, result).show()
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun updateFimSetupStatus() {
        if (!proxyEnabledCheckBox.isSelected || !completionsEnabledCheckBox.isSelected) {
            fimSetupStatusLabel.isVisible = false
            return
        }
        val status = OpenAiProxyService.getInstance().status()
        val report = AiCompletionSetupInspector.inspect(status.baseUrl, CompletionsConfig.FIM_ALIAS_ID)
        val state = when {
            report.baseUrlFound && report.modelFound -> ProxyRunState.RUNNING
            report.pluginFound -> ProxyRunState.PENDING
            else -> ProxyRunState.OFF
        }
        fimSetupStatusLabel.text =
            "<html><body width='520'><span style=\"color: ${state.colorHex}\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(report.summary)}</body></html>"
        fimSetupStatusLabel.isVisible = true
    }

    private fun openRequestLogs() {
        val logDir = OpenAiProxyService.getInstance().requestLogDir()
        if (!Files.isDirectory(logDir)) {
            setLogsStatus("No request logs yet.", false)
            return
        }
        if (!Desktop.isDesktopSupported()) {
            setLogsStatus("Open manually: $logDir", true)
            return
        }
        runCatching { Desktop.getDesktop().open(logDir.toFile()) }
            .onFailure { error -> setLogsStatus("Could not open logs: ${error.message ?: error::class.java.simpleName}", true) }
            .onSuccess { setLogsStatus("Opened ${compactPath(logDir)}", false) }
    }

    private fun setLogsStatus(text: String, error: Boolean) {
        logsStatusLabel.text = text
        logsStatusLabel.foreground = if (error) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        logsStatusLabel.isVisible = true
    }

    private fun compactPath(path: Path): String {
        val value = path.toString()
        val home = System.getProperty("user.home") ?: return value
        return if (value.startsWith(home)) "~" + value.removePrefix(home) else value
    }

    private fun showCopiedFeedback(button: JButton) {
        val runningTimer = copiedFeedbackTimers.remove(button)
        runningTimer?.stop()
        if (runningTimer == null) {
            button.putClientProperty(COPY_FEEDBACK_ORIGINAL_TEXT, button.text)
        }
        button.text = "Copied"
        button.icon = AllIcons.Actions.Checked
        val timer = Timer(COPY_FEEDBACK_MILLIS) {
            button.text = button.getClientProperty(COPY_FEEDBACK_ORIGINAL_TEXT) as? String ?: button.text
            button.icon = AllIcons.Actions.Copy
            copiedFeedbackTimers.remove(button)
        }
        timer.isRepeats = false
        copiedFeedbackTimers[button] = timer
        timer.start()
    }

    private fun onDocumentChange(field: JTextComponent, action: () -> Unit) {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = action()
            override fun removeUpdate(event: DocumentEvent) = action()
            override fun changedUpdate(event: DocumentEvent) = action()
        })
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun setProxyStatus(text: String, state: ProxyRunState) {
        proxyStatusLabel.text = "<html><span style=\"color: ${state.colorHex}\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
        proxyStatusLabel.isVisible = true
    }

    private enum class ProxyRunState(val colorHex: String) {
        RUNNING("#4CAF50"),
        OFF("#9E9E9E"),
        PENDING("#FFC107"),
        ERROR("#F44336"),
    }

    companion object {
        private const val PROXY_STATUS_REFRESH_MILLIS = 2_000
        private const val COPY_FEEDBACK_MILLIS = 1_500
        private const val COPY_FEEDBACK_ORIGINAL_TEXT = "SubscriptionProxySettingsPanel.copyFeedbackOriginalText"
    }
}

private class FimTestResultDialog(
    parent: JComponent,
    private val result: CompletionsFimTestResult,
) : DialogWrapper(parent, true) {
    init {
        title = "Test FIM"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val ok = result.ok
        val status = result.status
        val latency = result.elapsedMs?.let { "$it ms" }
        val sample = result.sample
        val insert = result.insert
        val assembled = result.assembled
        val detail = result.detail
        val icon = if (ok) AllIcons.General.InspectionsOK else AllIcons.General.Error
        return panel {
            row {
                icon(icon)
                label(status).bold()
                if (latency != null) {
                    comment(latency)
                }
            }
            if (sample != null) {
                group("Sample") {
                    row {
                        cell(codeBlock(sample))
                            .resizableColumn()
                            .align(AlignX.FILL)
                    }
                }
            }
            if (insert != null) {
                group("Inserted") {
                    row {
                        cell(codeBlock(insert))
                            .resizableColumn()
                            .align(AlignX.FILL)
                    }
                }
            }
            if (assembled != null) {
                group("Result") {
                    row {
                        cell(codeBlock(assembled))
                            .resizableColumn()
                            .align(AlignX.FILL)
                    }
                }
            }
            if (detail != null) {
                row {
                    text(QuotaUiUtil.escapeHtml(detail).replace("\n", "<br>"))
                        .resizableColumn()
                        .align(AlignX.FILL)
                }
            }
        }.apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(420))
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun codeBlock(text: String): JComponent {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(8)
        }
        return JBScrollPane(area).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }
}
