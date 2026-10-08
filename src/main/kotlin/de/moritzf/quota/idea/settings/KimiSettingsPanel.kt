package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.auth.AuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.kimi.KimiAuthService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.shared.JsonSupport
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.JButton
import javax.swing.JComponent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class KimiSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val visionModelCombo = VisionModelCombo()
    private var modelRefreshGeneration = 0
    private val loginButton = createActionLink("Log In")
    private val cancelLoginButton = createActionLink("Cancel Login")
    private val logoutButton = createActionLink("Log Out")
    private val copyUrlButton =
        JButton("Copy URL", AllIcons.Actions.Copy).apply {
            isVisible = false
            toolTipText = "Copy Kimi verification URL to clipboard"
        }
    private val userCodeLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()
    private var verificationUrl: String? = null
    private var authStatusMessage: AuthStatusMessage? = null

    init {
        copyUrlButton.addActionListener {
            val url = verificationUrl
            if (!url.isNullOrBlank()) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
            }
        }

        loginButton.addActionListener {
            val authService: AuthService =
                KimiAuthService.forAccount(accountKey(QuotaProviderType.KIMI))
            if (authService.isLoggedIn()) {
                updateStatus()
                return@addActionListener
            }
            loginButton.isEnabled = false
            authStatusMessage =
                AuthStatusMessage("Opening browser...", false, AuthStatusKind.PENDING)
            updateStatus()
            authService.startLoginFlow(
                callback = { result ->
                    ApplicationManager.getApplication()
                        .invokeLater(
                            {
                                authStatusMessage =
                                    if (result.success) {
                                        AuthStatusMessage(
                                            "Connected",
                                            false,
                                            AuthStatusKind.CONNECTED,
                                        )
                                    } else {
                                        AuthStatusMessage(
                                            result.message ?: "Login failed",
                                            true,
                                            AuthStatusKind.DISCONNECTED,
                                        )
                                    }
                                loginButton.isEnabled = true
                                updateStatus()
                                if (result.success) {
                                    QuotaUsageService.getInstance()
                                        .refreshAsync(accountKey(QuotaProviderType.KIMI))
                                }
                            },
                            ModalityState.stateForComponent(modalityComponentProvider() ?: this),
                        )
                },
                onVerificationUrl = { url, userCode ->
                    ApplicationManager.getApplication()
                        .invokeLater(
                            {
                                verificationUrl = url
                                copyUrlButton.isVisible = true
                                userCodeLabel.text =
                                    if (userCode.isBlank()) "" else "Kimi code: $userCode"
                                userCodeLabel.isVisible = userCode.isNotBlank()
                                authStatusMessage =
                                    AuthStatusMessage(
                                        "Waiting for browser authorization...",
                                        false,
                                        AuthStatusKind.PENDING,
                                    )
                                updateStatus()
                            },
                            ModalityState.stateForComponent(modalityComponentProvider() ?: this),
                        )
                },
            )
            updateStatus()
        }

        cancelLoginButton.addActionListener {
            val aborted =
                (KimiAuthService.forAccount(accountKey(QuotaProviderType.KIMI)) as AuthService)
                    .abortLogin("Login canceled")
            authStatusMessage =
                AuthStatusMessage(
                    if (aborted) "Login canceled" else "No login in progress",
                    false,
                    if (aborted) AuthStatusKind.PENDING else AuthStatusKind.DISCONNECTED,
                )
            updateStatus()
        }

        logoutButton.addActionListener {
            setPending("Clearing credentials...")
            ApplicationManager.getApplication().executeOnPooledThread {
                (KimiAuthService.forAccount(accountKey(QuotaProviderType.KIMI)) as AuthService)
                    .clearCredentials()
                ApplicationManager.getApplication()
                    .invokeLater(
                        {
                            authStatusMessage =
                                AuthStatusMessage("Logged out", false, AuthStatusKind.DISCONNECTED)
                            QuotaUsageService.getInstance()
                                .clearUsageData(accountKey(QuotaProviderType.KIMI), "Not logged in")
                            updateStatus()
                        },
                        ModalityState.stateForComponent(modalityComponentProvider() ?: this),
                    )
            }
        }

        install(
            panel {
                row {
                    cell(statusLabel).gap(RightGap.SMALL)
                    cell(copyUrlButton)
                }
                row { cell(userCodeLabel) }
                row {
                    cell(loginButton).gap(RightGap.SMALL)
                    cell(cancelLoginButton).gap(RightGap.SMALL)
                    cell(logoutButton)
                }
                row("Vision model:") {
                    cell(visionModelCombo.combo)
                        .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                        .resizableColumn()
                        .comment(
                            "Kimi coding models with image input. Used by subscription_vision; '-' keeps vision off."
                        )
                    cell(
                        VisionTestButton(
                            de.moritzf.quota.idea.mcp.VisionProvider.KIMI,
                            visionModelCombo,
                            modalityComponentProvider,
                        )
                    )
                }
            },
            createResponseSection(responseViewer),
        )
    }

    override fun updateFields() {
        rememberAccount()
        KimiCredentialsStore.forAccount(accountKey(QuotaProviderType.KIMI))
            .load(onLoaded = ::refreshAfterCredentialsLoad)
        showVisionModels(emptyList())
        updateStatus()
        refreshVisionModels()
    }

    fun visionModelForStorage(): String? = visionModelCombo.storedValue()

    fun visionModelDiffers(saved: String?): Boolean = visionModelCombo.differs(saved)

    private fun showVisionModels(
        discovered: List<String>,
        selection: String? = boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
    ) {
        visionModelCombo.show(selection, (discovered + listOfNotNull(selection)).distinct())
    }

    private fun refreshVisionModels() {
        val accountId = accountKey(QuotaProviderType.KIMI)
        val generation = ++modelRefreshGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val token = KimiCredentialsStore.forAccount(accountId).loadBlocking()?.accessToken
            val discovered =
                if (token.isNullOrBlank()) emptyList()
                else runCatching { fetchKimiVisionModelIds(token) }.getOrDefault(emptyList())
            ApplicationManager.getApplication()
                .invokeLater(
                    {
                        if (
                            generation != modelRefreshGeneration ||
                                accountKey(QuotaProviderType.KIMI) != accountId
                        )
                            return@invokeLater
                        showVisionModels(
                            discovered,
                            visionModelCombo.selected()
                                ?: boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
                        )
                    },
                    ModalityState.stateForComponent(modalityComponentProvider() ?: this),
                )
        }
    }

    override fun updateStatus() {
        val store = KimiCredentialsStore.forAccount(accountKey(QuotaProviderType.KIMI))
        val credentials = store.load(onLoaded = ::refreshAfterCredentialsLoad)
        val inProgress =
            (KimiAuthService.forAccount(accountKey(QuotaProviderType.KIMI)) as AuthService)
                .isLoginInProgress()
        val quota =
            QuotaUsageService.getInstance().getLastQuota(accountKey(QuotaProviderType.KIMI))
                as? KimiQuota
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.KIMI))
        val fallbackMessage =
            when {
                !store.isLoaded() ->
                    AuthStatusMessage("Loading credentials...", false, AuthStatusKind.PENDING)
                credentials?.isUsable() != true ->
                    AuthStatusMessage("Not logged in", false, AuthStatusKind.DISCONNECTED)
                error != null ->
                    AuthStatusMessage("Error: $error", true, AuthStatusKind.DISCONNECTED)
                quota != null -> AuthStatusMessage("Connected", false, AuthStatusKind.CONNECTED)
                else ->
                    AuthStatusMessage(
                        "Credentials stored securely",
                        false,
                        AuthStatusKind.CONNECTED,
                    )
            }
        val visibleMessage =
            authStatusMessage?.takeIf {
                inProgress || it.isError || credentials?.isUsable() != true
            } ?: fallbackMessage
        statusLabel.text = formatStatusText(visibleMessage.text, visibleMessage.kind)
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
        loginButton.isEnabled = !inProgress && credentials?.isUsable() != true
        cancelLoginButton.isEnabled = inProgress
        logoutButton.isEnabled = !inProgress && credentials?.isUsable() == true
        if (!inProgress) {
            verificationUrl = null
            copyUrlButton.isVisible = false
            userCodeLabel.isVisible = false
            userCodeLabel.text = ""
        }
    }

    override fun updateResponseArea() {
        val raw =
            QuotaUsageService.getInstance().getLastResponseJson(accountKey(QuotaProviderType.KIMI))
        val error = QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.KIMI))
        responseViewer.text =
            when {
                error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
                error != null -> "Error: $error"
                raw.isNullOrBlank() -> "No Kimi response yet."
                else -> raw
            }
        responseViewer.setCaretPosition(0)
    }

    private var shownAccountId: String? = null

    private fun rememberAccount() {
        val id = accountKey(QuotaProviderType.KIMI)
        if (shownAccountId != id) {
            shownAccountId = id
            authStatusMessage = null
        }
    }

    private fun refreshAfterCredentialsLoad() {
        updateFields()
        updateResponseArea()
    }

    private fun setPending(text: String) {
        authStatusMessage = AuthStatusMessage(text, false, AuthStatusKind.PENDING)
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private fun createActionLink(text: String): ActionLink {
        return ActionLink(text).apply { autoHideOnDisable = false }
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
}

private fun fetchKimiVisionModelIds(token: String): List<String> {
    val request =
        HttpRequest.newBuilder(URI.create("https://api.kimi.com/coding/v1/models"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "KimiCLI/1.40.0")
            .GET()
            .build()
    val response =
        runCatching {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())
        }
            .getOrNull() ?: return emptyList()
    if (response.statusCode() !in 200..299) return emptyList()
    val root =
        runCatching {
            JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject
        }
            .getOrNull() ?: return emptyList()
    val data = root["data"] as? JsonArray ?: return emptyList()
    return data
        .mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val vision =
                (item["supports_image_in"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull() == true
            if (!vision) return@mapNotNull null
            (item["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        .distinct()
}
