package de.moritzf.quota.idea.ui.popup

import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.compatible.compatibleEndpoint
import de.moritzf.quota.idea.openai.OpenAiProxyStatus
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import javax.swing.JPanel

/** Configuration and local server state, never an inferred upstream health check. */
internal class ConnectionOnlyPopupPanel(
    private val credentialState: (String) -> ProviderAuthState
) : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    init {
        isOpaque = false
    }

    fun update(settings: QuotaSettingsState, proxy: OpenAiProxyStatus) {
        removeAll()
        val visibleAccounts = settings.accounts.filterNot { it.hiddenFromPopup }
        if (visibleAccounts.isEmpty()) {
            add(createSeparatedBlock())
            add(JBLabel("<html>All connections are hidden<br>from this popup.</html>"))
        }
        visibleAccounts.forEach { account ->
            val type = account.providerType() ?: return@forEach
            val endpoint = compatibleEndpoint(account)
            add(createSeparatedBlock())
            add(
                createSectionTitleLabel(
                    settings.accountListLabel(account),
                    ProviderUiRegistry.forType(type).icon,
                )
            )
            add(TruncatingPopupLabel(endpoint?.baseUri?.toString() ?: "API URL needs setup"))
            val credentials =
                when (credentialState(account.id)) {
                    ProviderAuthState.AUTHENTICATED -> "API key configured"
                    ProviderAuthState.UNAUTHENTICATED -> "Connection needs setup"
                    ProviderAuthState.UNKNOWN -> "Loading saved credentials…"
                }
            add(createMutedLabel(credentials))
            add(JBLabel("<html>This API does not provide<br>quota information.</html>"))
        }
        add(createSeparatedBlock())
        add(createWindowTitleLabel("Local proxy"))
        add(
            JBLabel(
                when {
                    proxy.running -> "Running"
                    !proxy.enabled -> "Off"
                    proxy.error != null -> "Failed to start"
                    else -> "Starting…"
                }
            )
        )
        if (proxy.running) add(TruncatingPopupLabel(proxy.baseUrl))
        if (proxy.enabled && proxy.error != null) add(createWarningLabel(proxy.error))
    }
}
