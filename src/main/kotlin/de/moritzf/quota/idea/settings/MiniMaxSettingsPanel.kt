package de.moritzf.quota.idea.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.auth.MiniMaxCodeDeviceLogin
import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxQuotaClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.MiniMaxVisionClient
import java.awt.Color
import javax.swing.JComponent

internal class MiniMaxSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val regionComboBox = ComboBox(MiniMaxRegionPreference.entries.toTypedArray())
    private val apiKeyField = JBPasswordField().apply { columns = 40 }
    private val apiKey =
        storedSecret(
            listOf(apiKeyField),
            read = { MiniMaxApiKeyStore.forAccount(it).loadBlocking() },
            write = { id, value ->
                val store = MiniMaxApiKeyStore.forAccount(id)
                saveOrClear(value, store::save, store::clear)
                refreshOrClear(id, hasKey = value != null)
            },
        )
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val codeStatusLabel = JBLabel()
    private val codeLoginButton =
        ActionLink(message("settings.minimax.code.login")).apply { autoHideOnDisable = false }
    private val codeCancelButton =
        ActionLink(message("settings.login.cancel")).apply { autoHideOnDisable = false }
    private val codeLogoutButton =
        ActionLink(message("settings.login.logOut")).apply { autoHideOnDisable = false }
    private val codeDevicePanel = DeviceLoginPanel()
    private var codeAuthMessage: AuthStatusMessage? = null
    private var codeGeneration = 0L
    private var codeLoggingOut = false
    private val responseViewer = createResponseViewer()
    override val visionModelCombo = VisionModelCombo()

    init {
        codeLoginButton.addActionListener { startCodeLogin() }
        codeCancelButton.addActionListener {
            codeGeneration++
            auth()
                .abortLogin(
                    accountId(),
                    QuotaProviderType.MINIMAX,
                    message("settings.status.loginCanceled"),
                )
            codeAuthMessage =
                AuthStatusMessage(
                    message("settings.status.loginCanceled"),
                    kind = AuthStatusKind.PENDING,
                )
            updateStatus()
        }
        codeLogoutButton.addActionListener { logOutCode() }
        install(
            panel {
                row { statusCell(statusLabel) }
                row("Region:") { cell(regionComboBox) }
                row("Subscription key:") {
                    cell(apiKeyField).resizableColumn().align(AlignX.FILL)
                    cell(apiKey.checkButton { _, key -> checkKey(key) })
                }
                row("") {
                    cell(apiKey.hint)
                    cell(apiKey.checkStatus)
                }
                row(message("settings.model.vision.label")) {
                    cell(visionModelCombo.combo)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(message("settings.model.vision.help"), MAX_LINE_LENGTH_WORD_WRAP)
                    cell(
                        visionTestButton(
                            de.moritzf.quota.idea.mcp.VisionProvider.MINIMAX,
                            visionModelCombo,
                            modalityComponentProvider,
                        ),
                    )
                }
                separator()
                row("MiniMax Code:") { statusCell(codeStatusLabel) }
                row {
                    cell(codeLoginButton).gap(RightGap.SMALL)
                    cell(codeCancelButton).gap(RightGap.SMALL)
                    cell(codeLogoutButton)
                }
                row { cell(codeDevicePanel.component).align(AlignX.FILL).resizableColumn() }
                row { comment(message("settings.minimax.code.help"), MAX_LINE_LENGTH_WORD_WRAP) }
            },
            createResponseSection(responseViewer),
        )
    }

    private fun accountId() = accountKey(QuotaProviderType.MINIMAX)

    private fun auth() = QuotaAuthService.getInstance()

    override fun writeAccountFields(account: ProviderAccount) {
        super.writeAccountFields(account)
        selectedRegion()?.let { account.setExtra(ProviderAccount.EXTRA_MINIMAX_REGION, it.name) }
    }

    override fun accountFieldsModified(
        account: ProviderAccount,
        saved: ProviderAccount?,
        state: QuotaSettingsState,
    ): Boolean =
        selectedRegion() != state.miniMaxRegionFor(account.id) ||
            super.accountFieldsModified(account, saved, state)

    /** Reads the region on the EDT; the request accepts the key in either region on Auto. */
    private fun checkKey(key: String): () -> Unit {
        val regions =
            when (selectedRegion()) {
                MiniMaxRegionPreference.GLOBAL -> listOf(MiniMaxRegion.GLOBAL)
                MiniMaxRegionPreference.CN -> listOf(MiniMaxRegion.CN)
                else -> listOf(MiniMaxRegion.GLOBAL, MiniMaxRegion.CN)
            }
        return {
            val failures = regions.map { region ->
                runCatching { MiniMaxQuotaClient().fetchQuota(key, region) }.exceptionOrNull()
                    ?: return@map null
            }
            if (failures.none { it == null }) {
                throw failures.filterNotNull().let { all ->
                    all.firstOrNull { (it as? MiniMaxQuotaException)?.statusCode != 401 }
                        ?: all.first()
                }
            }
        }
    }

    private fun selectedRegion(): MiniMaxRegionPreference? =
        regionComboBox.selectedItem as? MiniMaxRegionPreference

    override fun updateFields() {
        apiKey.show(accountId())
        visionModelCombo.show(
            boundAccount?.extra(ProviderAccount.EXTRA_VISION_MODEL),
            listOf(MiniMaxVisionClient.MODEL),
        )
        regionComboBox.selectedItem =
            boundAccount
                ?.extra(ProviderAccount.EXTRA_MINIMAX_REGION)
                ?.let(MiniMaxRegionPreference::fromStorageValue)
                ?: QuotaSettingsState.getInstance()
                    .miniMaxRegionFor(accountKey(QuotaProviderType.MINIMAX))
        codeGeneration++
        codeAuthMessage = null
        codeLoggingOut = false
        updateStatus()
    }

    override fun testConnection(): ProviderTestConnection =
        ProviderTestConnection(
            checkNotNull(boundAccount).snapshot().apply {
                setExtra(
                    ProviderAccount.EXTRA_MINIMAX_REGION,
                    selectedRegion()?.name,
                )
            },
            apiKey.override(accountId()),
        )

    override fun testApiKeyDrafts(): Map<String, String> = apiKey.drafts()

    override fun updateStatus() {
        val isCurrent = accountUiGuard()
        val id = accountId()
        val store = MiniMaxApiKeyStore.forAccount(id)
        val storedKey = store.load(onLoaded = { if (isCurrent()) updateStatus() })
        val loggedIn = auth().isLoggedIn(id, QuotaProviderType.MINIMAX)
        val quota = QuotaUsageService.getInstance().getLastQuota(id) as? MiniMaxQuota
        val error = QuotaUsageService.getInstance().getLastError(id)
        statusLabel.text =
            when {
                !store.isLoaded() ->
                    statusDotHtml(message("settings.secret.loading"), AuthStatusKind.PENDING)
                storedKey.isNullOrBlank() && !loggedIn ->
                    statusDotHtml(message("settings.minimax.missing"), AuthStatusKind.DISCONNECTED)
                error != null ->
                    statusDotHtml(
                        message("settings.status.quotaError", error),
                        AuthStatusKind.DISCONNECTED,
                    )
                quota != null && quota.warnings.isNotEmpty() ->
                    statusDotHtml(
                        message(
                            "settings.status.connectedWithWarnings",
                            quota.warnings.joinToString("; "),
                        ),
                        AuthStatusKind.PENDING,
                    )
                quota?.planActive == false ->
                    statusDotHtml(
                        message("quota.minimax.status.credits"),
                        AuthStatusKind.CONNECTED,
                    )
                quota != null ->
                    statusDotHtml(message("settings.status.connected"), AuthStatusKind.CONNECTED)
                storedKey.isNullOrBlank() ->
                    statusDotHtml(message("settings.status.loggedIn"), AuthStatusKind.CONNECTED)
                else ->
                    statusDotHtml(message("settings.status.keyStored"), AuthStatusKind.CONNECTED)
            }
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
        updateCodeStatus(id, loggedIn)
    }

    private fun updateCodeStatus(id: String, loggedIn: Boolean) {
        val inProgress = auth().isLoginInProgress(id, QuotaProviderType.MINIMAX)
        val prompt = auth().deviceLoginPrompt(id, QuotaProviderType.MINIMAX)
        codeDevicePanel.show(prompt)
        val status =
            when {
                codeLoggingOut ->
                    AuthStatusMessage(
                        message("settings.status.loggingOut"),
                        kind = AuthStatusKind.PENDING,
                    )
                prompt != null ->
                    AuthStatusMessage(
                        message("settings.status.waiting"),
                        kind = AuthStatusKind.PENDING,
                    )
                codeAuthMessage != null -> codeAuthMessage!!
                !loggedIn ->
                    AuthStatusMessage(
                        message("settings.minimax.code.signedOut"),
                        kind = AuthStatusKind.DISCONNECTED,
                    )
                else ->
                    when (auth().connectionState(id, QuotaProviderType.MINIMAX)) {
                        OAuthConnectionState.RECONNECT_REQUIRED ->
                            AuthStatusMessage(
                                message("settings.status.reconnect"),
                                isError = true,
                            )
                        OAuthConnectionState.TEMPORARY_FAILURE ->
                            AuthStatusMessage(
                                message("settings.status.retrying"),
                                kind = AuthStatusKind.PENDING,
                            )
                        else -> AuthStatusMessage(message("settings.status.loggedIn"))
                    }
            }
        codeStatusLabel.text = statusDotHtml(status.text, status.kind)
        codeStatusLabel.foreground = statusLabelDefaultForeground ?: codeStatusLabel.foreground
        codeLoginButton.isEnabled = !inProgress && !codeLoggingOut
        codeCancelButton.isEnabled = inProgress && !codeLoggingOut
        codeLogoutButton.isEnabled = loggedIn && !inProgress && !codeLoggingOut
    }

    private fun startCodeLogin() {
        val id = accountId()
        // The login keeps the region it was granted in; Auto signs in globally.
        val region =
            if (selectedRegion() == MiniMaxRegionPreference.CN) MiniMaxRegion.CN
            else MiniMaxRegion.GLOBAL
        val generation = ++codeGeneration
        codeAuthMessage =
            AuthStatusMessage(
                message("settings.status.requestingCode"),
                kind = AuthStatusKind.PENDING,
            )
        auth()
            .startDeviceLoginFlow(
                id,
                QuotaProviderType.MINIMAX,
                callback = { result ->
                    if (result.success) QuotaUsageService.getInstance().refreshAsync(id)
                    onUi(id) {
                        if (codeGeneration != generation) return@onUi
                        codeAuthMessage =
                            if (result.success) null
                            else
                                AuthStatusMessage(
                                    result.message ?: message("settings.status.loginFailed"),
                                    isError = true,
                                )
                        updateStatus()
                    }
                },
                onPrompt = { prompt ->
                    onUi(id) {
                        if (codeGeneration != generation) return@onUi
                        // The URL carries the code; the panel keeps both copyable as a fallback.
                        runCatching { BrowserUtil.browse(prompt.verificationUrl) }
                        updateStatus()
                    }
                },
                operations = MiniMaxCodeDeviceLogin(region),
            )
        updateStatus()
    }

    private fun logOutCode() {
        val id = accountId()
        codeGeneration++
        codeLoggingOut = true
        codeAuthMessage = null
        updateStatus()
        ApplicationManager.getApplication().executeOnPooledThread {
            val cleared = auth().clearCredentials(id, QuotaProviderType.MINIMAX)
            if (cleared)
                refreshOrClear(
                    id,
                    hasKey = !MiniMaxApiKeyStore.forAccount(id).loadBlocking().isNullOrBlank(),
                )
            onUi(id) {
                codeLoggingOut = false
                if (!cleared) {
                    codeAuthMessage =
                        AuthStatusMessage(message("settings.status.logoutFailed"), isError = true)
                }
                updateStatus()
            }
        }
    }

    /** Off the EDT: what remains of the connection decides whether usage is read again. */
    private fun refreshOrClear(id: String, hasKey: Boolean) {
        val loggedIn = auth().hasCredentialsBlocking(id, QuotaProviderType.MINIMAX)
        if (hasKey || loggedIn) QuotaUsageService.getInstance().refreshAsync(id)
        else QuotaUsageService.getInstance().clearUsageData(id)
    }

    override fun updateResponseArea() {
        val raw =
            QuotaUsageService.getInstance()
                .getLastResponseJson(accountKey(QuotaProviderType.MINIMAX))
        val error =
            QuotaUsageService.getInstance().getLastError(accountKey(QuotaProviderType.MINIMAX))
        responseViewer.text =
            when {
                error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
                error != null -> "Error: $error"
                raw.isNullOrBlank() -> "No MiniMax response yet."
                else -> raw
            }
        responseViewer.setCaretPosition(0)
    }

    private fun onUi(id: String, action: () -> Unit) {
        ApplicationManager.getApplication()
            .invokeLater(
                { if (accountId() == id) action() },
                ModalityState.stateForComponent(modalityComponentProvider() ?: this),
            )
    }
}
