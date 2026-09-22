package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.idea.settings.AuthStatusMessage
import de.moritzf.quota.idea.settings.AuthStatusKind
import de.moritzf.quota.idea.settings.QuotaSettingsAuthUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuotaSettingsAuthUiStateTest {
    @Test
    fun reconnectEnablesLoginWithoutDiscardingStoredCredentials() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true, inProgress = false, statusMessage = AuthStatusMessage("Connected"),
            connectionState = OAuthConnectionState.RECONNECT_REQUIRED,
        )
        assertTrue(uiState.loginEnabled)
        assertTrue(uiState.logoutEnabled)
        assertFalse(uiState.cancelEnabled)
        assertEquals(AuthStatusKind.DISCONNECTED, uiState.visibleStatusMessage?.kind)
        assertTrue(uiState.visibleStatusMessage!!.text.contains("Reconnect required"))
    }

    @Test
    fun temporaryRefreshFailureShowsRetainedLogin() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true, inProgress = false, statusMessage = null,
            connectionState = OAuthConnectionState.TEMPORARY_FAILURE,
            quotaError = "Unable to refresh token",
        )
        assertFalse(uiState.loginEnabled)
        assertTrue(uiState.logoutEnabled)
        assertEquals(AuthStatusKind.PENDING, uiState.visibleStatusMessage?.kind)
        assertTrue(uiState.visibleStatusMessage!!.text.contains("Login retained"))
    }

    @Test
    fun failedReconnectShowsLoginErrorAndAllowsAnotherAttempt() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true, inProgress = false,
            statusMessage = AuthStatusMessage("Token exchange failed: HTTP 400", isError = true),
            connectionState = OAuthConnectionState.RECONNECT_REQUIRED,
        )
        assertTrue(uiState.loginEnabled)
        assertTrue(uiState.visibleStatusMessage!!.text.contains("Token exchange failed: HTTP 400"))
        assertTrue(uiState.visibleStatusMessage.text.contains("Reconnect required"))
    }

    @Test
    fun quotaFailureDoesNotShowDisconnectedLogin() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true, inProgress = false, statusMessage = null, quotaError = "Rate limited",
        )
        assertEquals(AuthStatusKind.PENDING, uiState.visibleStatusMessage?.kind)
        assertEquals("Quota unavailable: Rate limited", uiState.visibleStatusMessage?.text)
        assertFalse(uiState.loginEnabled)
        assertTrue(uiState.logoutEnabled)
    }

    @Test
    fun reconnectInProgressShowsBrowserFlowRatherThanPreviousFailure() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true, inProgress = true, statusMessage = null,
            connectionState = OAuthConnectionState.RECONNECT_REQUIRED,
        )
        assertEquals(AuthStatusKind.PENDING, uiState.visibleStatusMessage?.kind)
        assertFalse(uiState.loginEnabled)
        assertTrue(uiState.cancelEnabled)
    }

    @Test
    fun createPreservesExplicitStatusMessageDuringLogin() {
        val statusMessage = AuthStatusMessage("Opening browser...")

        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = false,
            inProgress = true,
            statusMessage = statusMessage,
        )

        assertEquals("Login", uiState.headerText)
        assertEquals(statusMessage, uiState.visibleStatusMessage)
        assertFalse(uiState.loginEnabled)
        assertTrue(uiState.cancelEnabled)
        assertFalse(uiState.logoutEnabled)
    }

    @Test
    fun createProvidesGenericInProgressHintWhenNoStatusMessageExists() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = false,
            inProgress = true,
            statusMessage = null,
        )

        assertEquals("Login", uiState.headerText)
        assertEquals(
            AuthStatusMessage("Complete the login in your browser.", kind = AuthStatusKind.PENDING),
            uiState.visibleStatusMessage,
        )
    }

    @Test
    fun createShowsDisconnectedStatusWhenIdleWithoutTransientFeedback() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = false,
            inProgress = false,
            statusMessage = null,
        )

        assertEquals("Login", uiState.headerText)
        assertEquals(AuthStatusMessage("Not logged in", isError = true), uiState.visibleStatusMessage)
        assertTrue(uiState.loginEnabled)
        assertFalse(uiState.cancelEnabled)
        assertFalse(uiState.logoutEnabled)
    }

    @Test
    fun createShowsConnectedStatusWhenLoggedInWithoutTransientFeedback() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true,
            inProgress = false,
            statusMessage = null,
        )

        assertEquals("Login", uiState.headerText)
        assertEquals(AuthStatusMessage("Connected"), uiState.visibleStatusMessage)
        assertFalse(uiState.loginEnabled)
        assertFalse(uiState.cancelEnabled)
        assertTrue(uiState.logoutEnabled)
    }

    @Test
    fun createKeepsSuccessFeedbackVisibleAfterLoginCompletes() {
        val statusMessage = AuthStatusMessage("Logged in")

        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true,
            inProgress = false,
            statusMessage = statusMessage,
        )

        assertEquals("Login", uiState.headerText)
        assertEquals(statusMessage, uiState.visibleStatusMessage)
        assertFalse(uiState.loginEnabled)
        assertFalse(uiState.cancelEnabled)
        assertTrue(uiState.logoutEnabled)
    }
}
