package de.moritzf.quota.idea.settings

internal data class AuthStatusMessage(
    val text: String,
    val isError: Boolean = false,
    val kind: AuthStatusKind = if (isError) AuthStatusKind.DISCONNECTED else AuthStatusKind.CONNECTED,
)

internal enum class AuthStatusKind {
    CONNECTED,
    DISCONNECTED,
    PENDING,
}

internal data class QuotaSettingsAuthUiState(
    val headerText: String,
    val visibleStatusMessage: AuthStatusMessage?,
    val loginEnabled: Boolean,
    val cancelEnabled: Boolean,
    val logoutEnabled: Boolean,
) {
    companion object {
        fun create(loggedIn: Boolean, inProgress: Boolean, statusMessage: AuthStatusMessage?): QuotaSettingsAuthUiState {
            val visibleStatusMessage = statusMessage ?: if (inProgress) {
                AuthStatusMessage("Complete the login in your browser.", kind = AuthStatusKind.PENDING)
            } else if (loggedIn) {
                AuthStatusMessage("Connected", isError = false)
            } else {
                AuthStatusMessage("Not logged in", isError = true)
            }
            return QuotaSettingsAuthUiState(
                headerText = "Login",
                visibleStatusMessage = visibleStatusMessage,
                loginEnabled = !inProgress && !loggedIn,
                cancelEnabled = inProgress,
                logoutEnabled = loggedIn,
            )
        }
    }
}
