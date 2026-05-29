package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.cursor.CursorAuth
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.cursor.CursorSessionTokenParser
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import java.util.concurrent.atomic.AtomicLong

/**
 * Cursor settings tab.
 */
internal class CursorSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : BorderLayoutPanel() {
    val cursorHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val sessionCookieField = JBPasswordField().apply {
        columns = 40
        toolTipText = "WorkosCursorSessionToken from cursor.com browser cookies"
    }
    private val authSourceLabel = JBLabel()
    private val cursorStatusLabel = JBLabel().apply { isVisible = false }
    private val cursorJsonViewer = createResponseViewer()
    private val validationGeneration = AtomicLong(0)

    init {
        val cursorConfigPanel = panel {
            row {
                cell(cursorHideFromPopupCheckBox)
            }
            row {
                cell(cursorStatusLabel)
            }
            row {
                label(
                    "Paste the WorkosCursorSessionToken cookie from cursor.com (DevTools → Application → Cookies).",
                )
            }
            row("Session cookie (${CursorSessionTokenParser.COOKIE_NAME}):") {
                cell(sessionCookieField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Auth source:") {
                cell(authSourceLabel)
            }
            row {
                button("Save") {
                    val sessionCookie = String(sessionCookieField.password)
                    if (sessionCookie.isNotBlank() && sessionCookie != SESSION_COOKIE_PLACEHOLDER) {
                        CursorCredentialsStore.getInstance().saveSessionCookie(sessionCookie)
                        sessionCookieField.text = SESSION_COOKIE_PLACEHOLDER
                        setCursorPendingStatus("Validating session cookie...")
                        validateSessionCookieNow(sessionCookie)
                        QuotaUsageService.getInstance().refreshCursorAsync()
                    }
                }
                button("Clear") {
                    CursorCredentialsStore.getInstance().clearSessionCookie()
                    sessionCookieField.text = ""
                    updateCursorStatus()
                    QuotaUsageService.getInstance().clearCursorUsageData()
                }
            }
            separator()
        }

        addToTop(cursorConfigPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Last quota response:"))
                addToCenter(createResponseViewerPanel(cursorJsonViewer))
            },
        )
    }

    fun updateCursorFields() {
        val store = CursorCredentialsStore.getInstance()
        store.load(onLoaded = ::refreshAfterCredentialLoad)
        sessionCookieField.text = when {
            !store.isLoaded() -> ""
            store.hasSessionCookie() -> SESSION_COOKIE_PLACEHOLDER
            else -> ""
        }
        updateAuthSourceLabel(store)
        updateCursorStatus()
    }

    fun updateCursorStatus() {
        val store = CursorCredentialsStore.getInstance()
        store.load(onLoaded = ::refreshAfterCredentialLoad)
        val cursorQuota = QuotaUsageService.getInstance().getLastCursorQuota()
        val cursorError = QuotaUsageService.getInstance().getLastCursorError()
        updateAuthSourceLabel(store)

        when {
            !store.isLoaded() -> {
                cursorStatusLabel.text = formatStatusText("Loading credentials...", AuthStatusKind.PENDING)
            }
            !store.hasCredentials() -> {
                cursorStatusLabel.text = formatStatusText(
                    "No session cookie configured. Paste WorkosCursorSessionToken from cursor.com.",
                    AuthStatusKind.DISCONNECTED,
                )
            }
            cursorError != null -> {
                cursorStatusLabel.text = formatStatusText("Error: $cursorError", AuthStatusKind.DISCONNECTED)
            }
            cursorQuota != null -> {
                cursorStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
            }
            else -> {
                cursorStatusLabel.text = formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
            }
        }
        cursorStatusLabel.foreground = statusLabelDefaultForeground ?: cursorStatusLabel.foreground
        cursorStatusLabel.isVisible = true
    }

    private fun updateAuthSourceLabel(store: CursorCredentialsStore) {
        authSourceLabel.text = when {
            !store.isLoaded() -> "Loading..."
            store.hasSessionCookie() -> "Browser session cookie"
            else -> "Not configured"
        }
    }

    private fun validateSessionCookieNow(sessionCookie: String) {
        val accessToken = CursorSessionTokenParser.extractAccessToken(sessionCookie)
        if (accessToken.isNullOrBlank()) {
            cursorStatusLabel.text = formatStatusText(
                "Error: Could not parse WorkosCursorSessionToken cookie",
                AuthStatusKind.DISCONNECTED,
            )
            cursorStatusLabel.isVisible = true
            return
        }

        val generation = validationGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                CursorQuotaClient().fetchQuota(
                    accessToken,
                    CursorAuth(
                        accessToken = accessToken,
                        sessionCookie = sessionCookie,
                    ),
                )
            }
            ApplicationManager.getApplication().invokeLater({
                if (generation != validationGeneration.get()) {
                    return@invokeLater
                }
                result.fold(
                    onSuccess = {
                        cursorStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                        cursorStatusLabel.foreground = statusLabelDefaultForeground ?: cursorStatusLabel.foreground
                        cursorStatusLabel.isVisible = true
                    },
                    onFailure = { error ->
                        cursorStatusLabel.text = formatStatusText(
                            "Error: ${error.message ?: "Validation failed"}",
                            AuthStatusKind.DISCONNECTED,
                        )
                        cursorStatusLabel.foreground = statusLabelDefaultForeground ?: cursorStatusLabel.foreground
                        cursorStatusLabel.isVisible = true
                    },
                )
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@CursorSettingsPanel))
        }
    }

    private fun refreshAfterCredentialLoad() {
        updateCursorFields()
        updateCursorResponseArea()
    }

    private fun setCursorPendingStatus(text: String) {
        cursorStatusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        cursorStatusLabel.foreground = statusLabelDefaultForeground ?: cursorStatusLabel.foreground
        cursorStatusLabel.isVisible = true
    }

    fun updateCursorResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastCursorQuota()
        val error = QuotaUsageService.getInstance().getLastCursorError()
        val rawJson = QuotaUsageService.getInstance().getLastCursorResponseJson()

        cursorJsonViewer.text = when {
            error != null && !rawJson.isNullOrBlank() -> "Error: $error\n\n$rawJson"
            error != null -> "Error: $error"
            quota == null -> "No Cursor response yet."
            !rawJson.isNullOrBlank() -> rawJson
            else -> {
                try {
                    de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                        de.moritzf.quota.cursor.CursorQuota.serializer(),
                        quota,
                    )
                } catch (exception: Exception) {
                    "Could not serialize response: ${exception.message}"
                }
            }
        }
        cursorJsonViewer.setCaretPosition(0)
    }

    private fun createResponseViewer(): com.intellij.ui.components.JBTextArea {
        return com.intellij.ui.components.JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            margin = JBUI.insets(6)
        }
    }

    private fun createResponseViewerPanel(viewer: com.intellij.ui.components.JBTextArea): JComponent {
        return JScrollPane(viewer).apply {
            preferredSize = Dimension(1, JBUI.scale(220))
            minimumSize = Dimension(1, JBUI.scale(120))
            border = JBUI.Borders.emptyTop(4)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
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
