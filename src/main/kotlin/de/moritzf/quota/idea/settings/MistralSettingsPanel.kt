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
    private val session =
        storedSecret(
            listOf(sessionNameField, sessionValueField, csrfField),
            read = { MistralSessionCookieStore.forAccount(it).loadBlocking() },
            write = { id, value ->
                val store = MistralSessionCookieStore.forAccount(id)
                saveOrClear(value, store::save, store::clear)
                refreshUsage(id)
            },
            split = { raw ->
                val stored = MistralQuotaClient.storedSessionFields(raw)
                listOf(
                    stored?.sessionName.orEmpty(),
                    stored?.sessionValue.orEmpty(),
                    stored?.csrfToken.orEmpty(),
                )
            },
            join = { (name, value, csrf) ->
                if (listOf(name, value, csrf).all(String::isBlank)) null
                else MistralQuotaClient.encodeStoredSession(name, value, csrf)
            },
        )
    private val apiKey =
        storedSecret(
            listOf(apiKeyField),
            read = { MistralApiKeyStore.forAccount(it).loadBlocking() },
            write = { id, value ->
                val store = MistralApiKeyStore.forAccount(id)
                saveOrClear(value, store::save, store::clear)
                refreshUsage(id)
            },
        )
    override val visionModelCombo = VisionModelCombo()
    private var modelRefreshGeneration = 0
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        install(
            panel {
                row { statusCell(statusLabel) }
                row {
                    text(
                        "Quota cookies from admin.mistral.ai / console.mistral.ai → DevTools → Application → Cookies. Copy each name/value.",
                    )
                }
                row("ory_session name:") {
                    cell(sessionNameField).resizableColumn().align(AlignX.FILL)
                }
                row("ory_session value:") {
                    cell(sessionValueField).resizableColumn().align(AlignX.FILL)
                }
                row("csrftoken:") {
                    cell(csrfField).resizableColumn().align(AlignX.FILL)
                    cell(session.checkButton { _, cookie -> { checkSession(cookie) } })
                }
                row("") {
                    cell(session.hint)
                    cell(session.checkStatus)
                }
                row("API key:") {
                    cell(apiKeyField).resizableColumn().align(AlignX.FILL)
                    cell(apiKey.checkButton { _, key -> { checkApiKey(key) } })
                }
                row("") {
                    cell(apiKey.hint)
                    cell(apiKey.checkStatus)
                }
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
                        ),
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
                        ),
                    )
                }
            },
            createResponseSection(responseViewer, "Last quota response (session + API key)"),
        )
    }

    override fun updateFields() {
        val id = accountKey(QuotaProviderType.MISTRAL)
        session.show(id)
        apiKey.show(id)
        showDocumentModels(emptyList())
        showVisionModels(emptyList())
        updateStatus()
        refreshDocumentModels()
    }

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount),
            apiKey.override(accountKey(QuotaProviderType.MISTRAL)),
        )

    override fun testApiKeyDrafts(): Map<String, String> = apiKey.drafts()

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
                            "${MistralSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI}/models",
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
                    statusDotHtml(message("settings.secret.loading"), AuthStatusKind.PENDING)
                cookie.isNullOrBlank() ->
                    statusDotHtml(message("settings.status.noCookie"), AuthStatusKind.DISCONNECTED)
                error != null -> statusDotHtml("Error: $error", AuthStatusKind.DISCONNECTED)
                quota != null -> statusDotHtml("Connected", AuthStatusKind.CONNECTED)
                else ->
                    statusDotHtml(message("settings.status.cookieStored"), AuthStatusKind.CONNECTED)
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

    private fun checkSession(cookie: String) {
        if (!MistralQuotaClient().fetchQuota(cookie).hasUsageState()) {
            error(message("settings.mistral.check.noUsage"))
        }
    }

    private fun checkApiKey(key: String) {
        DocumentModels.fetchModelBody(
            URI.create("${MistralSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI}/models"),
            key,
        ) ?: error(message("settings.mistral.check.keyRejected"))
    }

    /** Off the EDT, after Apply wrote one of the account's secrets. */
    private fun refreshUsage(id: String) {
        val hasCookie = !MistralSessionCookieStore.forAccount(id).loadBlocking().isNullOrBlank()
        val hasKey = !MistralApiKeyStore.forAccount(id).loadBlocking().isNullOrBlank()
        if (hasCookie || hasKey) QuotaUsageService.getInstance().refreshAsync(id)
        else QuotaUsageService.getInstance().clearUsageData(id)
    }
}
