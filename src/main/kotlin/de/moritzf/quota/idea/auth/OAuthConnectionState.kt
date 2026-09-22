package de.moritzf.quota.idea.auth

/** Stored credentials are retained for both failure states. Only an explicit logout removes them. */
enum class OAuthConnectionState {
    LOGGED_OUT,
    CONNECTED,
    TEMPORARY_FAILURE,
    RECONNECT_REQUIRED,
}
