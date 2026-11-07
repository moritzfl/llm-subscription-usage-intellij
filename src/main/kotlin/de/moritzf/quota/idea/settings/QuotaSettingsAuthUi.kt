package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.idea.common.QuotaBundle.message

internal data class AuthStatusMessage(
    val text: String,
    val isError: Boolean = false,
    val kind: AuthStatusKind =
        if (isError) AuthStatusKind.DISCONNECTED else AuthStatusKind.CONNECTED,
)

internal enum class AuthStatusKind(val colorHex: String) {
    CONNECTED("#4CAF50"),
    DISCONNECTED("#F44336"),
    PENDING("#FFC107"),
}

internal fun statusDotHtml(text: String, kind: AuthStatusKind): String =
    statusDotHtml(text, kind.colorHex)

internal data class QuotaSettingsAuthUiState(
    val headerText: String,
    val visibleStatusMessage: AuthStatusMessage?,
    val loginEnabled: Boolean,
    val cancelEnabled: Boolean,
    val logoutEnabled: Boolean,
) {
    companion object {
        fun create(
            loggedIn: Boolean,
            inProgress: Boolean,
            statusMessage: AuthStatusMessage?,
            connectionState: OAuthConnectionState =
                if (loggedIn) OAuthConnectionState.CONNECTED else OAuthConnectionState.LOGGED_OUT,
            quotaError: String? = null,
        ): QuotaSettingsAuthUiState {
            val reconnect = connectionState == OAuthConnectionState.RECONNECT_REQUIRED
            val visibleStatusMessage =
                when {
                    inProgress ->
                        statusMessage
                            ?: AuthStatusMessage(
                                message("settings.status.completeInBrowser"),
                                kind = AuthStatusKind.PENDING,
                            )
                    reconnect ->
                        AuthStatusMessage(
                            listOfNotNull(
                                    statusMessage?.takeIf { it.isError }?.text,
                                    message("settings.status.reconnect"),
                                )
                                .joinToString(" "),
                            isError = true,
                        )
                    connectionState == OAuthConnectionState.TEMPORARY_FAILURE ->
                        AuthStatusMessage(
                            message("settings.status.retrying"),
                            kind = AuthStatusKind.PENDING,
                        )
                    !loggedIn ->
                        statusMessage
                            ?: AuthStatusMessage(
                                message("settings.status.notLoggedIn"),
                                isError = true,
                            )
                    quotaError != null ->
                        AuthStatusMessage(
                            message("settings.status.quotaError", quotaError),
                            isError = true,
                            kind = AuthStatusKind.PENDING,
                        )
                    else -> statusMessage ?: AuthStatusMessage(message("settings.status.connected"))
                }
            return QuotaSettingsAuthUiState(
                headerText = "Login",
                visibleStatusMessage = visibleStatusMessage,
                loginEnabled = !inProgress && (!loggedIn || reconnect),
                cancelEnabled = inProgress,
                logoutEnabled = loggedIn,
            )
        }
    }
}
