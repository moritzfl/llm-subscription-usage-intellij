package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralQuotaClient
import de.moritzf.quota.mistral.proxy.MistralSubscriptionProxyProvider
import de.moritzf.quota.shared.DocumentModels
import java.awt.Color
import java.net.URI
import javax.swing.JComponent

internal class MistralSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val sessionNameField =
        JBTextField().apply {
            columns = 40
            emptyText.text = "ory_session_…"
            toolTipText = "Cookie name from admin.mistral.ai, starts with ory_session_"
        }
    private val sessionValueField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "Value of the ory_session_* cookie"
        }
    private val csrfField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "Value of the csrftoken cookie from console.mistral.ai"
        }
    private val apiKeyField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "Mistral API key for MCP search, images, and OCR"
        }
    override val documentModelCombo =
        DocumentModelCombo(MistralOcrClient.DEFAULT_MODEL, vision = false)
    private val sessionNameDraft = credentialDraft(sessionNameField)
    private val sessionValueDraft = credentialDraft(sessionValueField)
    private val csrfDraft = credentialDraft(csrfField)
    private val apiKeyDraft = credentialDraft(apiKeyField)
    override val visionModelCombo = VisionModelCombo()
    private var modelRefreshGeneration = 0
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        install(
            panel {
                row { cell(statusLabel).align(AlignX.FILL).resizableColumn() }
                row {
                    text(
                        "Quota cookies from admin.mistral.ai / console.mistral.ai → DevTools → Application → Cookies. Copy each name/value."
                    )
                }
                row("ory_session name:") {
                    cell(sessionNameField).resizableColumn().align(AlignX.FILL)
                }
                row("ory_session value:") {
                    cell(sessionValueField).resizableColumn().align(AlignX.FILL)
                }
                row("csrftoken:") { cell(csrfField).resizableColumn().align(AlignX.FILL) }
                row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
                row(message("settings.model.document.label")) {
                    cell(documentModelCombo.combo)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(message("settings.model.document.help"), MAX_LINE_LENGTH_WORD_WRAP)
                    cell(
                        documentTestButton(
                            de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider.MISTRAL,
                            { documentModelCombo.selected().orEmpty() },
                            modalityComponentProvider,
                            documentModelCombo.combo,
                        )
                    )
                }
                row(message("settings.model.vision.label")) {
                    cell(visionModelCombo.combo)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(message("settings.model.vision.help"), MAX_LINE_LENGTH_WORD_WRAP)
                    cell(
                        visionTestButton(
                            de.moritzf.quota.idea.mcp.VisionProvider.MISTRAL,
                            visionModelCombo,
                            modalityComponentProvider,
                        )
                    )
                }
                row {
                    button("Save") { saveNow() }
                    button("Clear") { clearNow() }
                }
            },
            createResponseSection(responseViewer, "Last quota response (session + API key)"),
        )
    }

    override fun updateFields() {
        loadCredentialFields()
        showDocumentModels(emptyList())
        showVisionModels(emptyList())
        updateStatus()
        refreshDocumentModels()
    }

    private fun loadCredentialFields() {
        val id = accountKey(QuotaProviderType.MISTRAL)
        val isCurrent = accountUiGuard()
        val onLoaded = {
            if (isCurrent()) {
                loadCredentialFields()
                updateStatus()
                updateResponseArea()
                refreshDocumentModels()
            }
        }
        val stored =
            MistralQuotaClient.storedSessionFields(
                MistralSessionCookieStore.forAccount(id).load(onLoaded = onLoaded)
            )
        val apiKey = MistralApiKeyStore.forAccount(id).load(onLoaded = onLoaded)
        sessionNameDraft.show(id, stored?.sessionName.orEmpty())
        sessionValueDraft.show(id, if (stored?.sessionValue.isNullOrBlank()) "" else PLACEHOLDER)
        csrfDraft.show(id, if (stored?.csrfToken.isNullOrBlank()) "" else PLACEHOLDER)
        apiKeyDraft.show(id, if (apiKey.isNullOrBlank()) "" else PLACEHOLDER)
    }

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount),
            String(apiKeyField.password).takeUnless { it == PLACEHOLDER },
        )

    override fun testApiKeyDrafts(): Map<String, String> =
        apiKeyDraft.snapshot().filterValues { it != PLACEHOLDER }

    private fun showDocumentModels(
        discovered: List<String>,
        selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL),
    ) {
        documentModelCombo.show(
            selection,
            DocumentModels.prefixedChoices(
                discovered,
                selection,
                MistralOcrClient.DEFAULT_MODEL,
                DocumentModels::isMistralOcrModel,
            ),
        )
    }

    private fun showVisionModels(
        discovered: List<String>,
        selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
    ) {
        visionModelCombo.show(selection, discovered)
    }

    private fun refreshDocumentModels() {
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val accountId = accountKey(QuotaProviderType.MISTRAL)
        val generation = ++modelRefreshGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = MistralApiKeyStore.forAccount(accountId).loadBlocking()
            val body =
                if (key.isNullOrBlank()) {
                    null
                } else {
                    DocumentModels.fetchModelBody(
                        URI.create(
                            "${MistralSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI}/models"
                        ),
                        key,
                    )
                }
            val documentModels = body?.let(DocumentModels::parseModelIds).orEmpty()
            val visionModels = body?.let(DocumentModels::parseVisionModelIds).orEmpty()
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (!isCurrent() || generation != modelRefreshGeneration) return@invokeLater
                        showDocumentModels(
                            documentModels,
                            documentModelCombo.selected()
                                ?: boundAccount?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL),
                        )
                        showVisionModels(
                            visionModels,
                            visionModelCombo.selected()
                                ?: boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
                        )
                    },
                    modality,
                )
        }
    }

    override fun updateStatus() {
        val isCurrent = accountUiGuard()
        val cookieStore =
            MistralSessionCookieStore.forAccount(accountKey(QuotaProviderType.MISTRAL))
        val cookie = cookieStore.load(onLoaded = { if (isCurrent()) updateStatus() })
        val quota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.MISTRAL))
                as? MistralQuota
        val error =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MISTRAL))
        statusLabel.text =
            when {
                !cookieStore.isLoaded() ->
                    statusDotHtml("Loading credentials...", AuthStatusKind.PENDING)
                cookie.isNullOrBlank() ->
                    statusDotHtml("Mistral session cookie missing", AuthStatusKind.DISCONNECTED)
                error != null -> statusDotHtml("Error: $error", AuthStatusKind.DISCONNECTED)
                quota != null -> statusDotHtml("Connected", AuthStatusKind.CONNECTED)
                else -> statusDotHtml("Session cookie stored securely", AuthStatusKind.CONNECTED)
            }
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val raw =
            QuotaUsageService.getInstance()
                .getLastResponseJson(accountKey(QuotaProviderType.MISTRAL))
        val error =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MISTRAL))
        responseViewer.text =
            when {
                error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
                error != null -> "Error: $error"
                raw.isNullOrBlank() -> "No Mistral response yet."
                else -> raw
            }
        responseViewer.setCaretPosition(0)
    }

    private fun saveNow() {
        val id = accountKey(QuotaProviderType.MISTRAL)
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val current =
            MistralQuotaClient.storedSessionFields(MistralSessionCookieStore.forAccount(id).load())
        val currentKey = MistralApiKeyStore.forAccount(id).load()
        val submittedSession = String(sessionValueField.password)
        val submittedCsrf = String(csrfField.password)
        val submittedKey = String(apiKeyField.password)
        val sessionName = sessionNameField.text
        val sessionValue =
            String(sessionValueField.password).let { value ->
                if (value == PLACEHOLDER) current?.sessionValue.orEmpty() else value
            }
        val csrf =
            String(csrfField.password).let { value ->
                if (value == PLACEHOLDER) current?.csrfToken.orEmpty() else value
            }
        val apiKey = String(apiKeyField.password).let { if (it == PLACEHOLDER) currentKey else it }
        setPending("Saving credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val encoded = runCatching {
                MistralQuotaClient.encodeStoredSession(sessionName, sessionValue, csrf)
            }
                .getOrElse { error ->
                    ApplicationManager.getApplication()
                        .invokeLater(
                            {
                                if (!isCurrent()) return@invokeLater
                                statusLabel.text =
                                    statusDotHtml(
                                        error.message ?: "Invalid Mistral cookies",
                                        AuthStatusKind.DISCONNECTED,
                                    )
                                statusLabel.isVisible = true
                            },
                            modality,
                        )
                    return@executeOnPooledThread
                }
            MistralSessionCookieStore.forAccount(id).save(encoded)
            MistralApiKeyStore.forAccount(id).save(apiKey)
            QuotaUsageService.getInstance().refreshAsync(id)
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        sessionNameDraft.saved(id, sessionName)
                        sessionValueDraft.saved(id, submittedSession)
                        csrfDraft.saved(id, submittedCsrf)
                        apiKeyDraft.saved(id, submittedKey)
                        if (isCurrent()) {
                            loadCredentialFields()
                            updateStatus()
                            refreshDocumentModels()
                        }
                    },
                    modality,
                )
        }
    }

    private fun clearNow() {
        val id = accountKey(QuotaProviderType.MISTRAL)
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val submitted =
            listOf(
                sessionNameField.text,
                String(sessionValueField.password),
                String(csrfField.password),
                String(apiKeyField.password),
            )
        setPending("Clearing credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MistralSessionCookieStore.forAccount(id).clear()
            MistralApiKeyStore.forAccount(id).clear()
            QuotaUsageService.getInstance().clearUsageData(id)
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        listOf(sessionNameDraft, sessionValueDraft, csrfDraft, apiKeyDraft)
                            .zip(submitted)
                            .forEach { (draft, value) -> draft.saved(id, value) }
                        if (isCurrent()) {
                            loadCredentialFields()
                            updateStatus()
                        }
                    },
                    modality,
                )
        }
    }

    private fun setPending(text: String) {
        statusLabel.text = statusDotHtml(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private companion object {
        const val PLACEHOLDER = "********"
    }
}
