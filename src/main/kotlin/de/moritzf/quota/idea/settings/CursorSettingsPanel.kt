package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.cursor.CursorAuth
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.cursor.CursorSessionTokenParser
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.Color
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent

/** Cursor settings tab. */
internal class CursorSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val sessionCookieField =
        JBPasswordField().apply {
            columns = 40
            toolTipText = "WorkosCursorSessionToken from cursor.com browser cookies"
        }
    private val authSourceLabel = JBLabel()
    private val sessionCookieDraft = credentialDraft(sessionCookieField)
    private val cursorStatusLabel = JBLabel().apply { isVisible = false }
    private val cursorJsonViewer = createResponseViewer()
    private val validationGeneration = AtomicLong(0)

    init {
        val cursorConfigPanel = panel {
            row { cell(cursorStatusLabel) }
            row {
                text(
                    "Paste the WorkosCursorSessionToken cookie from cursor.com (DevTools → Application → Cookies)."
                )
            }
            row("Session cookie (${CursorSessionTokenParser.COOKIE_NAME}):") {
                cell(sessionCookieField).resizableColumn().align(AlignX.FILL)
            }
            row("Auth source:") { cell(authSourceLabel) }
            row {
                button("Save") {
                    val id = accountKey(QuotaProviderType.CURSOR)
                    val sessionCookie = String(sessionCookieField.password)
                    if (sessionCookie.isNotBlank() && sessionCookie != SESSION_COOKIE_PLACEHOLDER) {
                        CursorCredentialsStore.forAccount(accountKey(QuotaProviderType.CURSOR))
                            .saveSessionCookie(sessionCookie)
                        sessionCookieDraft.saved(id, sessionCookie)
                        sessionCookieDraft.show(id, SESSION_COOKIE_PLACEHOLDER)
                        setCursorPendingStatus()
                        validateSessionCookieNow(sessionCookie)
                        QuotaUsageService.getInstance()
                            .refreshAsync(accountKey(QuotaProviderType.CURSOR))
                    }
                }
                button("Clear") {
                    val id = accountKey(QuotaProviderType.CURSOR)
                    CursorCredentialsStore.forAccount(accountKey(QuotaProviderType.CURSOR))
                        .clearSessionCookie()
                    sessionCookieDraft.saved(id, String(sessionCookieField.password))
                    sessionCookieDraft.show(id, "")
                    validationGeneration.incrementAndGet()
                    updateStatus()
                    QuotaUsageService.getInstance()
                        .clearUsageData(accountKey(QuotaProviderType.CURSOR))
                }
            }
        }

        install(cursorConfigPanel, createResponseSection(cursorJsonViewer))
    }

    override fun updateFields() {
        loadCredentialField()
        updateStatus()
    }

    private fun loadCredentialField() {
        val id = accountKey(QuotaProviderType.CURSOR)
        val isCurrent = accountUiGuard()
        val store = CursorCredentialsStore.forAccount(id)
        store.load(
            onLoaded = {
                if (isCurrent()) {
                    loadCredentialField()
                    updateStatus()
                    updateResponseArea()
                }
            }
        )
        sessionCookieDraft.show(
            id,
            when {
                !store.isLoaded() -> ""
                store.hasSessionCookie() -> SESSION_COOKIE_PLACEHOLDER
                else -> ""
            },
        )
        updateAuthSourceLabel(store)
    }

    override fun updateStatus() {
        val isCurrent = accountUiGuard()
        val store = CursorCredentialsStore.forAccount(accountKey(QuotaProviderType.CURSOR))
        store.load(onLoaded = { if (isCurrent()) updateStatus() })
        val cursorQuota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.CURSOR))
                as? CursorQuota
        val cursorError =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.CURSOR))
        updateAuthSourceLabel(store)

        when {
            !store.isLoaded() -> {
                cursorStatusLabel.text =
                    formatStatusText("Loading credentials...", AuthStatusKind.PENDING)
            }
            !store.hasCredentials() -> {
                cursorStatusLabel.text =
                    formatStatusText(
                        "No session cookie configured. Paste WorkosCursorSessionToken from cursor.com.",
                        AuthStatusKind.DISCONNECTED,
                    )
            }
            cursorError != null -> {
                cursorStatusLabel.text =
                    formatStatusText("Error: $cursorError", AuthStatusKind.DISCONNECTED)
            }
            cursorQuota != null -> {
                cursorStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
            }
            else -> {
                cursorStatusLabel.text =
                    formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
            }
        }
        cursorStatusLabel.foreground = statusLabelDefaultForeground ?: cursorStatusLabel.foreground
        cursorStatusLabel.isVisible = true
    }

    private fun updateAuthSourceLabel(store: CursorCredentialsStore) {
        authSourceLabel.text =
            when {
                !store.isLoaded() -> "Loading..."
                store.hasSessionCookie() -> "Browser session cookie"
                else -> "Not configured"
            }
    }

    private fun validateSessionCookieNow(sessionCookie: String) {
        val isCurrent = accountUiGuard()
        val modality = ModalityState.stateForComponent(modalityComponentProvider() ?: this)
        val accessToken = CursorSessionTokenParser.extractAccessToken(sessionCookie)
        if (accessToken.isNullOrBlank()) {
            cursorStatusLabel.text =
                formatStatusText(
                    "Error: Could not parse WorkosCursorSessionToken cookie",
                    AuthStatusKind.DISCONNECTED,
                )
            cursorStatusLabel.isVisible = true
            return
        }

        val generation = validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                CursorQuotaClient()
                    .fetchQuota(
                        accessToken,
                        CursorAuth(
                            accessToken = accessToken,
                            sessionCookie = sessionCookie,
                        ),
                    )
            }
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (!isCurrent() || generation != validationGeneration.get()) {
                            return@invokeLater
                        }
                        result.fold(
                            onSuccess = {
                                cursorStatusLabel.text =
                                    formatStatusText("Connected", AuthStatusKind.CONNECTED)
                                cursorStatusLabel.foreground =
                                    statusLabelDefaultForeground ?: cursorStatusLabel.foreground
                                cursorStatusLabel.isVisible = true
                            },
                            onFailure = { error ->
                                cursorStatusLabel.text =
                                    formatStatusText(
                                        "Error: ${error.message ?: "Validation failed"}",
                                        AuthStatusKind.DISCONNECTED,
                                    )
                                cursorStatusLabel.foreground =
                                    statusLabelDefaultForeground ?: cursorStatusLabel.foreground
                                cursorStatusLabel.isVisible = true
                            },
                        )
                    },
                    modality,
                )
        }
    }

    private fun setCursorPendingStatus() {
        cursorStatusLabel.text =
            formatStatusText("Validating session cookie...", AuthStatusKind.PENDING)
        cursorStatusLabel.foreground = statusLabelDefaultForeground ?: cursorStatusLabel.foreground
        cursorStatusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val quota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.CURSOR))
                as? CursorQuota
        val error =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.CURSOR))
        val rawJson =
            QuotaUsageService.getInstance()
                .getLastResponseJson(accountKey(QuotaProviderType.CURSOR))

        cursorJsonViewer.text =
            when {
                error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
                error != null -> "Error: $error"
                quota == null -> "No Cursor response yet."
                !rawJson.isNullOrBlank() -> rawJson
                else -> {
                    try {
                        de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                            CursorQuota.serializer(),
                            quota,
                        )
                    } catch (exception: Exception) {
                        "Could not serialize response: ${exception.message}"
                    }
                }
            }
        cursorJsonViewer.setCaretPosition(0)
    }

    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color =
            when (kind) {
                AuthStatusKind.CONNECTED -> "#4CAF50"
                AuthStatusKind.DISCONNECTED -> "#F44336"
                AuthStatusKind.PENDING -> "#FFC107"
            }
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private companion object {
        private const val SESSION_COOKIE_PLACEHOLDER = "********"
    }
}
