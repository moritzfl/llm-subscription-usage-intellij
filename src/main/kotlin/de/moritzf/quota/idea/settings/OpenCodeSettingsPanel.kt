package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeWorkspace
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * OpenCode Go settings tab.
 */
internal class OpenCodeSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : BorderLayoutPanel() {
    val openCodeHideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val openCodeCookieField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Session cookie from opencode.ai (extract from browser DevTools)"
    }
    private val openCodeStatusLabel = JBLabel().apply { isVisible = false }
    private val workspaceComboBox = ComboBox<OpenCodeWorkspace>().apply {
        isVisible = false
    }
    private val workspaceLabel = JBLabel("Workspace:").apply { isVisible = false }
    private val workspaceLoadingLabel = JBLabel("Loading workspaces...").apply { isVisible = false }
    private val openCodeJsonViewer = createResponseViewer()
    private var updatingWorkspaceComboBox: Boolean = false
    private var awaitingCookieLoadRefresh: Boolean = false

    init {
        workspaceComboBox.addActionListener {
            if (updatingWorkspaceComboBox) return@addActionListener
            val selected = workspaceComboBox.selectedItem as? OpenCodeWorkspace ?: return@addActionListener
            val state = QuotaSettingsState.getInstance()
            if (state.openCodeWorkspaceId != selected.id) {
                state.openCodeWorkspaceId = selected.id
                QuotaUsageService.getInstance().resetOpenCodeWorkspaceCache()
                QuotaUsageService.getInstance().refreshOpenCodeAsync()
            }
        }

        val openCodeConfigPanel = panel {
            row {
                cell(openCodeHideFromPopupCheckBox)
            }
            row {
                label("Session cookie")
            }
            row {
                cell(openCodeStatusLabel)
            }
            row("Session cookie:") {
                cell(openCodeCookieField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row {
                button("Save") {
                    val cookie = String(openCodeCookieField.password)
                    if (cookie.isNotBlank() && cookie != OPENCODE_COOKIE_PLACEHOLDER) {
                        OpenCodeSessionCookieStore.getInstance().save(cookie)
                        openCodeCookieField.text = OPENCODE_COOKIE_PLACEHOLDER
                        setOpenCodePendingStatus("Validating session cookie...")
                        loadWorkspaces(cookie)
                        QuotaUsageService.getInstance().refreshOpenCodeAsync()
                    }
                }
                button("Clear") {
                    OpenCodeSessionCookieStore.getInstance().clear()
                    openCodeCookieField.text = ""
                    QuotaSettingsState.getInstance().openCodeWorkspaceId = null
                    workspaceComboBox.removeAllItems()
                    workspaceComboBox.isVisible = false
                    workspaceLabel.isVisible = false
                    workspaceLoadingLabel.isVisible = false
                    updateOpenCodeStatus()
                    QuotaUsageService.getInstance().clearOpenCodeUsageData()
                }
            }
            row {
                cell(workspaceLoadingLabel)
            }
            row {
                cell(workspaceLabel).gap(RightGap.SMALL)
                cell(workspaceComboBox)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            separator()
            row {
                label("Extract from opencode.ai → DevTools → Storage → Cookies → \"auth\" cookie value. Valid for 1 year.")
            }
        }

        addToTop(openCodeConfigPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Last quota response:"))
                addToCenter(createResponseViewerPanel(openCodeJsonViewer))
            },
        )
    }

    fun updateOpenCodeFields() {
        val cookieStore = OpenCodeSessionCookieStore.getInstance()
        val cookie = cookieStore.load(onLoaded = ::refreshAfterCookieLoad)
        openCodeCookieField.text = if (cookie.isNullOrBlank()) "" else OPENCODE_COOKIE_PLACEHOLDER
        if (!cookie.isNullOrBlank()) {
            loadWorkspaces(cookie)
        } else if (cookieStore.isLoaded()) {
            workspaceComboBox.removeAllItems()
            workspaceComboBox.isVisible = false
            workspaceLabel.isVisible = false
            workspaceLoadingLabel.isVisible = false
        } else {
            workspaceLoadingLabel.text = "Loading session cookie..."
            workspaceLoadingLabel.isVisible = true
            workspaceLabel.isVisible = false
            workspaceComboBox.isVisible = false
        }
        updateOpenCodeStatus()
    }

    fun updateOpenCodeStatus() {
        val cookieStore = OpenCodeSessionCookieStore.getInstance()
        val cookie = cookieStore.load(onLoaded = ::refreshAfterCookieLoad)
        val openCodeQuota = QuotaUsageService.getInstance().getLastOpenCodeQuota()
        val openCodeError = QuotaUsageService.getInstance().getLastOpenCodeError()

        when {
            !cookieStore.isLoaded() -> {
                openCodeStatusLabel.text = formatStatusText("Loading session cookie...", AuthStatusKind.PENDING)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            cookie == null -> {
                openCodeStatusLabel.text = formatStatusText("No session cookie configured", AuthStatusKind.DISCONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            workspaceComboBox.isVisible && workspaceComboBox.itemCount > 0 -> {
                openCodeStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            openCodeError != null -> {
                openCodeStatusLabel.text = formatStatusText("Error: $openCodeError", AuthStatusKind.DISCONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            openCodeQuota != null -> {
                openCodeStatusLabel.text = formatStatusText("Connected", AuthStatusKind.CONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
            else -> {
                openCodeStatusLabel.text = formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
                openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
            }
        }
        openCodeStatusLabel.isVisible = true
    }

    private fun setOpenCodePendingStatus(text: String) {
        openCodeStatusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        openCodeStatusLabel.foreground = statusLabelDefaultForeground ?: openCodeStatusLabel.foreground
        openCodeStatusLabel.isVisible = true
    }

    fun updateOpenCodeResponseArea() {
        val quota = QuotaUsageService.getInstance().getLastOpenCodeQuota()
        val error = QuotaUsageService.getInstance().getLastOpenCodeError()

        openCodeJsonViewer.text = when {
            error != null -> "Error: $error"
            quota == null -> "No OpenCode response yet."
            else -> {
                try {
                    de.moritzf.quota.shared.JsonSupport.json.encodeToString(
                        de.moritzf.quota.opencode.OpenCodeQuota.serializer(),
                        quota,
                    )
                } catch (exception: Exception) {
                    "Could not serialize response: ${exception.message}"
                }
            }
        }
        openCodeJsonViewer.setCaretPosition(0)
    }

    private fun loadWorkspaces(cookie: String) {
        workspaceLoadingLabel.text = "Loading workspaces..."
        workspaceLoadingLabel.isVisible = true
        workspaceLabel.isVisible = false
        workspaceComboBox.isVisible = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = OpenCodeQuotaClient()
                val workspaces = client.fetchWorkspaces(cookie)

                ApplicationManager.getApplication().invokeLater({
                    workspaceComboBox.removeAllItems()
                    workspaces.forEach { workspaceComboBox.addItem(it) }

                    val storedId = QuotaSettingsState.getInstance().openCodeWorkspaceId
                    val preselected = workspaces.find { it.id == storedId }
                        ?: workspaces.firstOrNull { it.mine && it.hasGoSubscription }
                        ?: workspaces.firstOrNull { it.hasGoSubscription }
                        ?: workspaces.firstOrNull()

                    updatingWorkspaceComboBox = true
                    try {
                        preselected?.let {
                            workspaceComboBox.selectedItem = it
                            QuotaSettingsState.getInstance().openCodeWorkspaceId = it.id
                        }
                    } finally {
                        updatingWorkspaceComboBox = false
                    }

                    workspaceLoadingLabel.isVisible = false
                    workspaceLabel.isVisible = true
                    workspaceComboBox.isVisible = true
                    updateOpenCodeStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OpenCodeSettingsPanel))
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    workspaceComboBox.removeAllItems()
                    workspaceComboBox.isVisible = false
                    workspaceLabel.isVisible = false
                    workspaceLoadingLabel.text = "Could not load workspaces: ${e.message}"
                    workspaceLoadingLabel.isVisible = true
                    updateOpenCodeStatus()
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@OpenCodeSettingsPanel))
            }
        }
    }

    private fun refreshAfterCookieLoad() {
        if (awaitingCookieLoadRefresh) {
            return
        }
        awaitingCookieLoadRefresh = true
        ApplicationManager.getApplication().invokeLater({
            awaitingCookieLoadRefresh = false
            updateOpenCodeFields()
        }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
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
        return "<html><span style='color:$color'>●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private companion object {
        private const val OPENCODE_COOKIE_PLACEHOLDER = "********"
    }
}
