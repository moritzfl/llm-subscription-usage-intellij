package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.auth.OAuthConnectionState

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
                                "Complete the login in your browser.",
                                kind = AuthStatusKind.PENDING,
                            )
                    reconnect ->
                        AuthStatusMessage(
                            listOfNotNull(
                                    statusMessage?.takeIf { it.isError }?.text,
                                    "Reconnect required. Log in again to renew this login.",
                                )
                                .joinToString(" "),
                            isError = true,
                        )
                    connectionState == OAuthConnectionState.TEMPORARY_FAILURE ->
                        AuthStatusMessage(
                            "Login retained. Temporarily unable to refresh; retrying automatically.",
                            kind = AuthStatusKind.PENDING,
                        )
                    !loggedIn -> statusMessage ?: AuthStatusMessage("Not logged in", isError = true)
                    quotaError != null ->
                        AuthStatusMessage(
                            "Quota unavailable: $quotaError",
                            isError = true,
                            kind = AuthStatusKind.PENDING,
                        )
                    else -> statusMessage ?: AuthStatusMessage("Connected")
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
