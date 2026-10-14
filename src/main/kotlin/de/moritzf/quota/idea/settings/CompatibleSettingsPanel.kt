package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.compatible.CompatibleEndpoint
import de.moritzf.quota.compatible.proxy.CompatibleSubscriptionProxyProvider
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.compatible.CompatibleApiKeyStore
import de.moritzf.quota.idea.compatible.isCompatibleConnection
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.shared.DocumentModels
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.Timer
import javax.swing.event.DocumentEvent

internal class CompatibleSettingsPanel(
    private val loadKey: (String) -> String? = {
        CompatibleApiKeyStore.forAccount(it).loadBlocking()
    },
    private val saveKey: (String, String?) -> Unit = { id, key ->
        CompatibleApiKeyStore.forAccount(id).save(key)
    },
    executor: java.util.concurrent.Executor =
        com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService(),
    dispatch: (() -> Unit) -> Unit = { javax.swing.SwingUtilities.invokeLater(it) },
) : ProviderSettingsPanel(), Disposable {
    var openProxySettings: () -> Unit = {}
    val baseUrlField =
        JBTextField().apply {
            emptyText.text = "https://api.example.com/v1"
            columns = 1
        }
    val modelIdsField = JBTextArea(3, 25)
    val apiKeyField = JBPasswordField().apply { columns = 1 }
    val status = JBLabel()
    private val keyHint = JBLabel().apply { minimumSize = java.awt.Dimension(0, 16) }
    val modelCombo = ComboBox<String>().apply { prototypeDisplayValue = "select-a-model-to-test" }
    val refreshButton =
        JButton("Refresh models", AllIcons.Actions.Refresh).apply {
            addActionListener { refreshModels() }
        }
    val testButton =
        JButton(message("modelTest.chat.title")).apply {
            toolTipText = message("modelTest.chat.tooltip")
            addActionListener { testModel() }
        }
    val visionModel = VisionModelCombo(groupUnverified = true)
    val documentModel = DocumentModelCombo(DocumentModels.OFF, vision = true)
    val visionTest =
        VisionTestButton(
            visionModel,
            { this },
            prepare = ::prepareVisionTest,
        )
    val documentTest =
        DocumentTestButton(
            { documentModel.selected().orEmpty() },
            { this },
            documentModel.combo,
            prepare = ::prepareDocumentTest,
        )
    private val budgetStatus = JBLabel().apply { minimumSize = java.awt.Dimension(0, 16) }
    private val budgetButton =
        JButton("Check key budget").apply { addActionListener { checkBudget() } }
    private val cancelButton =
        JButton("Cancel").apply {
            addActionListener {
                cancelRequests()
                setStatus("Cancelled.")
            }
        }
    private val requestTask = TestDialogTask(executor, dispatch, ownerDisposed = { inactive() })
    private val keyTask = TestDialogTask(executor, dispatch, ownerDisposed = { inactive() })
    private val refreshTimer = Timer(700) { refreshModels() }.apply { isRepeats = false }

    private data class KeyDraft(var saved: String?, var value: String?)

    private val keys = mutableMapOf<String, KeyDraft>()
    private var shownAccountId = ""
    private var updating = false
    private var disposed = false
    private var discovered = emptyList<String>()
    private var declaredVision = emptyList<String>()
    private var busy = false
    private var keyLoadError: String? = null
    private var manualGroup: com.intellij.ui.dsl.builder.CollapsibleRow? = null
    private var budgetRow: com.intellij.ui.dsl.builder.Row? = null
    private var budgetStatusRow: com.intellij.ui.dsl.builder.Row? = null

    init {
        // Let model fields shrink in narrow panes while keeping their test buttons alongside them.
        listOf(modelCombo, visionModel.combo, documentModel.combo).forEach {
            it.isUsePreferredSizeAsMinimum = false
            it.minimumSize = java.awt.Dimension(0, it.minimumSize.height)
        }
        installScrollable(
            panel {
                row("Base URL:") { cell(baseUrlField).align(AlignX.FILL).resizableColumn() }
                row { comment("API root, including /v1 when required.") }
                row("API key:") { cell(apiKeyField).align(AlignX.FILL).resizableColumn() }
                row { cell(keyHint).align(AlignX.FILL).resizableColumn() }
            },
            panel {
                group("Models") {
                    row {
                        cell(refreshButton)
                        cell(cancelButton)
                    }
                    row { cell(status).align(AlignX.FILL).resizableColumn() }
                    row("Model:") {
                        cell(modelCombo).align(AlignX.FILL).resizableColumn()
                        cell(testButton)
                    }
                    row { comment(message("modelTest.chat.tooltip")) }
                }
                group("Vision and documents") {
                    row(message("settings.model.vision.label")) {
                        cell(visionModel.combo).align(AlignX.FILL).resizableColumn()
                        cell(visionTest)
                    }
                    row { comment(message("settings.model.vision.help")) }
                    row(message("settings.model.document.label")) {
                        cell(documentModel.combo).align(AlignX.FILL).resizableColumn()
                        cell(documentModel.warning)
                        cell(documentTest)
                    }
                    row { comment(message("settings.model.document.help")) }
                    row { comment(message("settings.model.document.nativePdf.help")) }
                }
                budgetRow = row { cell(budgetButton) }
                budgetStatusRow = row { cell(budgetStatus).align(AlignX.FILL).resizableColumn() }
                manualGroup =
                    collapsibleGroup("Model IDs (optional)") {
                            row {
                                comment(
                                    "Leave empty to discover models automatically. Enter one model ID per line to use only those models, or when discovery is unavailable."
                                )
                            }
                            row {
                                cell(JBScrollPane(modelIdsField))
                                    .align(AlignX.FILL)
                                    .resizableColumn()
                            }
                        }
                        .apply { expanded = false }
                row { link("Open proxy settings") { openProxySettings() } }
                row {
                    comment(
                        "Apply this connection, then enable its provider in proxy settings for chat and code completion. LiteLLM can also report key budgets."
                    )
                }
            },
        )
        val listener =
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (updating || inactive()) return
                    if (e.document === apiKeyField.document)
                        keys[shownAccountId]?.value = currentKey()
                    requestTask.cancel()
                    busy = false
                    refreshTimer.stop()
                    discovered = emptyList()
                    declaredVision = emptyList()
                    budgetStatus.text = ""
                    updateModelChoices()
                    updateStatus()
                    val error = endpointError()
                    setStatus(
                        error ?: "Ready to load models.",
                        error != null && baseUrlField.text.isNotBlank(),
                    )
                    if (e.document === modelIdsField.document) refreshTimer.restart()
                }
            }
        baseUrlField.document.addDocumentListener(listener)
        apiKeyField.document.addDocumentListener(listener)
        modelIdsField.document.addDocumentListener(listener)
        modelCombo.addActionListener { updateButtons() }
        visionModel.combo.addActionListener { updateButtons() }
        documentModel.combo.addActionListener { updateButtons() }
        // Do not send partial URLs/keys while typing. Discover when the edited field is left.
        val focusListener =
            object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent) {
                    if (!inactive()) refreshTimer.restart()
                }
            }
        baseUrlField.addFocusListener(focusListener)
        apiKeyField.addFocusListener(focusListener)
        baseUrlField.addActionListener { refreshModels() }
        apiKeyField.addActionListener { refreshModels() }
    }

    override fun updateFields() {
        cancelRequests()
        val account = boundAccount?.takeIf { it.providerType().isCompatibleConnection() } ?: return
        shownAccountId = account.id
        updating = true
        baseUrlField.text = account.extra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL).orEmpty()
        modelIdsField.text = account.extra(ProviderAccount.EXTRA_COMPATIBLE_MODELS).orEmpty()
        apiKeyField.text = ""
        manualGroup?.expanded = modelIdsField.text.isNotBlank()
        keyLoadError = null
        discovered = emptyList()
        declaredVision = emptyList()
        visionModel.show(
            account.extra(ProviderAccount.EXTRA_VISION_MODEL),
            listOfNotNull(account.extra(ProviderAccount.EXTRA_VISION_MODEL)),
        )
        documentModel.show(
            account.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL),
            listOfNotNull(account.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)),
        )
        budgetButton.isVisible = account.providerType() == QuotaProviderType.LITELLM
        budgetStatus.isVisible = budgetButton.isVisible
        budgetRow?.visible(budgetButton.isVisible)
        budgetStatusRow?.visible(budgetButton.isVisible)
        budgetStatus.text = ""
        val cached = keys[account.id]
        apiKeyField.isEnabled = cached != null
        apiKeyField.text = cached?.value.orEmpty()
        updating = false
        updateModelChoices()
        updateStatus()
        setStatus("Enter a base URL and API key to discover models.")
        if (cached != null) {
            refreshTimer.restart()
        } else {
            keyTask.start { gen ->
                try {
                    val value = loadKey(account.id)
                    keyTask.onEdt(gen) {
                        keys[account.id] = KeyDraft(value, value)
                        updating = true
                        apiKeyField.text = value.orEmpty()
                        apiKeyField.isEnabled = true
                        updating = false
                        updateStatus()
                        refreshTimer.restart()
                    }
                } catch (failure: Exception) {
                    if (TestDialogTask.isCancellation(failure)) return@start
                    keyTask.onEdt(gen) {
                        keyLoadError =
                            "Could not load the API key from Password Safe. Reselect this account to retry."
                        updateStatus()
                        setStatus(keyLoadError!!, true)
                    }
                }
            }
        }
    }

    override fun updateStatus() {
        keyHint.text =
            when {
                keyLoadError != null -> keyLoadError
                keys[shownAccountId] == null -> "Loading API key from Password Safe…"
                keys[shownAccountId]?.let { it.saved != it.value } == true ->
                    "Apply to save the API key to Password Safe."
                currentKey() != null -> "API key stored in Password Safe."
                else -> "Enter your provider's API key."
            }
        keyHint.foreground =
            if (keyLoadError != null) UIUtil.getErrorForeground()
            else UIUtil.getContextHelpForeground()
        updateButtons()
    }

    override fun updateResponseArea() = Unit

    fun writeTo(account: ProviderAccount) {
        if (account.id != shownAccountId) return
        account.setExtra(
            ProviderAccount.EXTRA_COMPATIBLE_BASE_URL,
            baseUrlField.text.trim().trimEnd('/'),
        )
        account.setExtra(
            ProviderAccount.EXTRA_COMPATIBLE_MODELS,
            CompatibleEndpoint.parseModelIds(modelIdsField.text).joinToString("\n"),
        )
        keys[account.id]?.value = currentKey()
        account.setExtra(ProviderAccount.EXTRA_VISION_MODEL, visionModel.storedValue())
        account.setExtra(ProviderAccount.EXTRA_DOCUMENT_MODEL, documentModel.storedValue())
    }

    fun differsFrom(account: ProviderAccount): Boolean =
        baseUrlField.text.trim().trimEnd('/') !=
            account.extra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL).orEmpty() ||
            CompatibleEndpoint.parseModelIds(modelIdsField.text) !=
                CompatibleEndpoint.parseModelIds(
                    account.extra(ProviderAccount.EXTRA_COMPATIBLE_MODELS).orEmpty()
                ) ||
            visionModel.differs(account.extra(ProviderAccount.EXTRA_VISION_MODEL)) ||
            documentModel.differs(account.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL))

    fun credentialsModified(keptIds: Set<String>): Boolean = keys.any { (id, draft) ->
        id in keptIds && draft.value != draft.saved
    }

    fun saveCredentials(keptIds: Set<String>) {
        keys
            .filterKeys { it in keptIds }
            .forEach { (id, draft) ->
                if (draft.value != draft.saved) {
                    saveKey(id, draft.value)
                    draft.saved = draft.value
                }
            }
        keys.keys.retainAll(keptIds)
        updateStatus()
    }

    fun discardDrafts() {
        cancelRequests()
        keys.clear()
        updating = true
        apiKeyField.text = ""
        updating = false
    }

    fun cancelRequests() {
        requestTask.cancel()
        keyTask.cancel()
        refreshTimer.stop()
        busy = false
        updateButtons()
    }

    override fun dispose() {
        disposed = true
        discardDrafts()
        apiKeyField.text = ""
    }

    private fun inactive() =
        disposed ||
            boundAccount?.id != shownAccountId ||
            !boundAccount?.providerType().isCompatibleConnection()

    private fun currentKey() = String(apiKeyField.password).trim().takeIf(String::isNotEmpty)

    private fun provider(): CompatibleSubscriptionProxyProvider {
        val endpoint = draftEndpoint()
        val key = currentKey()
        require(key != null) { message("modelTest.enterApiKey") }
        return CompatibleSubscriptionProxyProvider(
            shownAccountId,
            boundAccount?.name.orEmpty(),
            endpoint,
            { key },
        )
    }

    private fun draftEndpoint() =
        CompatibleEndpoint.parse(baseUrlField.text, modelIdsField.text)
            .copy(
                liteLlm = boundAccount?.providerType() == QuotaProviderType.LITELLM,
                visionModel = visionModel.storedValue(),
                documentModel = documentModel.storedValue(),
            )

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount).snapshot().also(::writeTo),
            if (apiKeyField.isEnabled) currentKey().orEmpty() else null,
        )

    internal fun prepareVisionTest(model: String): VisionTestExecution =
        prepareVisionTest(
            de.moritzf.quota.idea.mcp.VisionProvider.entries.first {
                it.providerType == boundAccount?.providerType()
            },
            model,
        )

    internal fun prepareDocumentTest(model: String): DocumentTestExecution =
        prepareDocumentTest(
            de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider.entries.first {
                it.providerType == boundAccount?.providerType()
            },
            model,
        )

    private fun checkBudget() {
        val endpoint = draftEndpoint()
        val key = currentKey() ?: return
        refreshTimer.stop()
        busy = true
        setStatus("Checking LiteLLM key budget…")
        requestTask.start { gen ->
            try {
                val quota = de.moritzf.quota.litellm.LiteLlmClient(endpoint).quota(key)
                requestTask.onEdt(gen) {
                    busy = false
                    budgetStatus.text = "<html>${QuotaUiUtil.escapeHtml(quota.budgetText())}</html>"
                    setStatus("Key budget loaded. Team/user limits may also apply.")
                }
            } catch (failure: Exception) {
                if (TestDialogTask.isCancellation(failure)) return@start
                requestTask.onEdt(gen) {
                    busy = false
                    setStatus(failureMessage(failure), true)
                }
            }
        }
    }

    private fun refreshModels() {
        if (inactive() || !apiKeyField.isEnabled) return
        refreshTimer.stop()
        val provider =
            try {
                provider()
            } catch (failure: IllegalArgumentException) {
                setStatus(failure.message.orEmpty(), baseUrlField.text.isNotBlank())
                return
            }
        val manual = CompatibleEndpoint.parseModelIds(modelIdsField.text)
        if (manual.isNotEmpty()) {
            requestTask.cancel()
            updateModelChoices()
            setStatus("${manual.size} manual model IDs. Select one to test.")
            return
        }
        busy = true
        setStatus("Loading models…")
        requestTask.start { gen ->
            try {
                val details = provider.discoverModelDetails()
                val models = details.map { it.id }
                requestTask.onEdt(gen) {
                    busy = false
                    discovered = models
                    declaredVision = details.filter { it.supportsVision == true }.map { it.id }
                    if (models.isEmpty()) manualGroup?.expanded = true
                    updateModelChoices()
                    setStatus(
                        if (models.isEmpty()) "No models returned. You can enter model IDs below."
                        else "${models.size} models available."
                    )
                }
            } catch (failure: Exception) {
                if (TestDialogTask.isCancellation(failure)) return@start
                requestTask.onEdt(gen) {
                    busy = false
                    manualGroup?.expanded = true
                    setStatus(failureMessage(failure), true)
                }
            }
        }
    }

    private fun testModel() {
        refreshTimer.stop()
        val model = modelCombo.selectedItem as? String ?: return
        val provider =
            try {
                provider()
            } catch (failure: IllegalArgumentException) {
                setStatus(failure.message.orEmpty(), true)
                return
            }
        busy = true
        setStatus(message("modelTest.chat.running", model))
        requestTask.start { gen ->
            try {
                val start = System.nanoTime()
                val reply = provider.testModel(model)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                requestTask.onEdt(gen) {
                    busy = false
                    setStatus(message("modelTest.chat.success", model, elapsed, reply))
                }
            } catch (failure: Exception) {
                if (TestDialogTask.isCancellation(failure)) return@start
                requestTask.onEdt(gen) {
                    busy = false
                    setStatus(failureMessage(failure), true)
                }
            }
        }
    }

    private fun updateModelChoices() {
        val selected = modelCombo.selectedItem
        val models = CompatibleEndpoint.parseModelIds(modelIdsField.text).ifEmpty { discovered }
        modelCombo.model = DefaultComboBoxModel(models.toTypedArray())
        if (selected in models) modelCombo.selectedItem = selected
        val visionSelected = visionModel.selected()
        val documentSelected = documentModel.selected()
        val choices =
            (models + listOfNotNull(visionSelected, documentSelected))
                .filter { it != DocumentModels.OFF }
                .distinct()
        visionModel.show(
            visionSelected,
            declaredVision.filter { it in choices },
            choices.filter { it !in declaredVision },
        )
        documentModel.show(documentSelected, choices)
        updateButtons()
    }

    private fun updateButtons() {
        refreshButton.isEnabled =
            !busy && apiKeyField.isEnabled && currentKey() != null && endpointError() == null
        testButton.isEnabled = refreshButton.isEnabled && modelCombo.selectedItem != null
        modelCombo.isEnabled = !busy && modelCombo.itemCount > 0
        cancelButton.isVisible = busy
        visionTest.isEnabled =
            refreshButton.isEnabled &&
                visionModel.selected().let { !it.isNullOrBlank() && it != DocumentModels.OFF }
        documentTest.isEnabled =
            refreshButton.isEnabled &&
                documentModel.selected().let { !it.isNullOrBlank() && it != DocumentModels.OFF }
        budgetButton.isEnabled = refreshButton.isEnabled
    }

    private fun endpointError(): String? =
        try {
            CompatibleEndpoint.parse(baseUrlField.text, modelIdsField.text)
            null
        } catch (failure: IllegalArgumentException) {
            failure.message
        }

    private fun setStatus(text: String, error: Boolean = false) {
        status.text = "<html>${QuotaUiUtil.escapeHtml(text)}</html>"
        status.toolTipText = text
        status.foreground =
            if (error) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        updateButtons()
    }

    private fun failureMessage(failure: Exception): String =
        when (failure) {
            is java.net.http.HttpTimeoutException ->
                "The endpoint timed out. Check its URL or try again."
            is java.net.ConnectException ->
                "Could not connect. Check the base URL and that the server is running."
            is java.net.UnknownHostException ->
                "Could not resolve the endpoint hostname. Check the base URL."
            is IllegalStateException ->
                failure.message ?: "Connection failed. Check the base URL and API key."
            else -> "Connection failed. Check the base URL, TLS certificate, and API key."
        }
}
