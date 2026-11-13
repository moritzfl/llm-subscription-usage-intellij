package de.moritzf.quota.idea.settings

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.cursor.CursorAuth
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.cursor.CursorSessionTokenParser
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import java.awt.Color
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
    private val sessionCookie =
        storedSecret(
            listOf(sessionCookieField),
            read = { CursorCredentialsStore.forAccount(it).loadSessionCookieBlocking() },
            write = { id, value ->
                val store = CursorCredentialsStore.forAccount(id)
                saveOrClear(value, store::saveSessionCookie, store::clearSessionCookie)
                if (value == null) QuotaUsageService.getInstance().clearUsageData(id)
                else QuotaUsageService.getInstance().refreshAsync(id)
            },
            join = { values ->
                values
                    .single()
                    .trim()
                    .ifEmpty { null }
                    ?.also {
                        require(!CursorSessionTokenParser.extractAccessToken(it).isNullOrBlank()) {
                            message("settings.cursor.invalidCookie")
                        }
                    }
            },
        )
    private val cursorStatusLabel = JBLabel().apply { isVisible = false }
    private val cursorJsonViewer = createResponseViewer()

    init {
        val cursorConfigPanel = panel {
            row { statusCell(cursorStatusLabel) }
            row {
                text(
                    "Paste the WorkosCursorSessionToken cookie from cursor.com (DevTools → Application → Cookies).",
                )
            }
            row("Session cookie (${CursorSessionTokenParser.COOKIE_NAME}):") {
                cell(sessionCookieField).resizableColumn().align(AlignX.FILL)
                cell(sessionCookie.checkButton { _, cookie -> { checkCookie(cookie) } })
            }
            row("") {
                cell(sessionCookie.hint)
                cell(sessionCookie.checkStatus)
            }
        }

        install(cursorConfigPanel, createResponseSection(cursorJsonViewer))
    }

    private fun checkCookie(cookie: String) {
        val token = checkNotNull(CursorSessionTokenParser.extractAccessToken(cookie))
        CursorQuotaClient()
            .fetchQuota(token, CursorAuth(accessToken = token, sessionCookie = cookie))
    }

    override fun updateFields() {
        sessionCookie.show(accountKey(QuotaProviderType.CURSOR))
        updateStatus()
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

        when {
            !store.isLoaded() -> {
                cursorStatusLabel.text =
                    statusDotHtml(message("settings.status.loading"), AuthStatusKind.PENDING)
            }
            !store.hasCredentials() -> {
                cursorStatusLabel.text =
                    statusDotHtml(message("settings.status.noCookie"), AuthStatusKind.DISCONNECTED)
            }
            cursorError != null -> {
                cursorStatusLabel.text =
                    statusDotHtml(
                        message("settings.status.quotaError", cursorError),
                        AuthStatusKind.DISCONNECTED,
                    )
            }
            cursorQuota != null -> {
                cursorStatusLabel.text =
                    statusDotHtml(message("settings.status.connected"), AuthStatusKind.CONNECTED)
            }
            else -> {
                cursorStatusLabel.text =
                    statusDotHtml(message("settings.status.cookieStored"), AuthStatusKind.CONNECTED)
            }
        }
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
}
