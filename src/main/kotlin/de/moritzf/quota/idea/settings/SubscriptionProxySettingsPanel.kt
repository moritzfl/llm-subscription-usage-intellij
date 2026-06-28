package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.openai.OpenAiProxyApiKeyStore
import de.moritzf.quota.idea.openai.OpenAiProxyService
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JComponent
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
    private val proxyDescriptionLabel = JBLabel(
        "<html><body width='720'>Use the copied base URL and API key to configure this proxy in JetBrains AI Assistant " +
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
        return when (provider) {
            QuotaProviderType.OPEN_AI,
            QuotaProviderType.SUPERGROK -> QuotaAuthService.getInstance().isLoggedIn(provider)
            QuotaProviderType.GITHUB -> GitHubCredentialsStore.getInstance()
                .load(onLoaded = ::refreshAfterCredentialsLoad)
                ?.isUsable() == true
            QuotaProviderType.KIMI -> KimiCredentialsStore.getInstance()
                .load(onLoaded = ::refreshAfterCredentialsLoad)
                ?.isUsable() == true
            QuotaProviderType.MINIMAX -> !MiniMaxApiKeyStore.getInstance()
                .load(onLoaded = ::refreshAfterCredentialsLoad)
                .isNullOrBlank()
            QuotaProviderType.OLLAMA -> !OllamaApiKeyStore.getInstance()
                .load(onLoaded = ::refreshAfterCredentialsLoad)
                .isNullOrBlank()
            QuotaProviderType.OPEN_CODE -> !OpenCodeApiKeyStore.getInstance()
                .load(onLoaded = ::refreshAfterCredentialsLoad)
                .isNullOrBlank()
            QuotaProviderType.ZAI -> !ZaiApiKeyStore.getInstance()
                .load(onLoaded = ::refreshAfterCredentialsLoad)
                .isNullOrBlank()
            else -> false
        }
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
        if (configuredPort != requestedPort || isProxyApiKeyModified() || isProxyLogRequestsModified() || isProviderSelectionModified()) {
            setProxyStatus("Apply settings to restart the proxy with the updated configuration", ProxyRunState.PENDING)
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
                    onSuccess = ::formatModelPreview,
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
