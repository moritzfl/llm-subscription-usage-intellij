package de.moritzf.quota.idea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuotaSettingsAuthUiStateTest {
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
