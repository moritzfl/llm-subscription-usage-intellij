package de.moritzf.quota.idea.ui.settings

import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderListStatusTest {
    @Test
    fun neverConfiguredWhenUnauthenticatedWithoutError() {
        assertEquals(
            ProviderListStatus.NEVER_CONFIGURED,
            ProviderListStatus.resolve(
                ProviderAuthState.UNAUTHENTICATED,
                hasQuota = false,
                hasError = false,
            ),
        )
    }

    @Test
    fun loggedOutIsNeverConfiguredEvenWithLeftoverError() {
        assertEquals(
            ProviderListStatus.NEVER_CONFIGURED,
            ProviderListStatus.resolve(
                ProviderAuthState.UNAUTHENTICATED,
                hasQuota = false,
                hasError = true,
            ),
        )
        assertEquals(
            ProviderListStatus.NEVER_CONFIGURED,
            ProviderListStatus.resolve(
                ProviderAuthState.UNAUTHENTICATED,
                hasQuota = true,
                hasError = true,
            ),
        )
    }

    @Test
    fun warningExplainsLastGoodQuota() {
        assertEquals(
            "Using last good quota. workspace unavailable",
            ProviderListStatus.explain(ProviderListStatus.WARNING, "workspace unavailable"),
        )
    }

    @Test
    fun warningWithoutErrorWaitsForFirstQuota() {
        assertEquals(
            "Configured, waiting for first quota",
            ProviderListStatus.explain(ProviderListStatus.WARNING, null),
        )
    }

    @Test
    fun okWhenAuthenticatedWithQuotaAndNoError() {
        assertEquals(
            ProviderListStatus.OK,
            ProviderListStatus.resolve(
                ProviderAuthState.AUTHENTICATED,
                hasQuota = true,
                hasError = false,
            ),
        )
    }

    @Test
    fun warningWhenAuthenticatedWithStaleQuota() {
        assertEquals(
            ProviderListStatus.WARNING,
            ProviderListStatus.resolve(
                ProviderAuthState.AUTHENTICATED,
                hasQuota = true,
                hasError = true,
            ),
        )
    }

    @Test
    fun errorWhenAuthenticatedWithErrorAndNoQuota() {
        assertEquals(
            ProviderListStatus.ERROR,
            ProviderListStatus.resolve(
                ProviderAuthState.AUTHENTICATED,
                hasQuota = false,
                hasError = true,
            ),
        )
    }

    @Test
    fun warningWhenAuthenticatedWithoutQuotaYet() {
        assertEquals(
            ProviderListStatus.WARNING,
            ProviderListStatus.resolve(
                ProviderAuthState.AUTHENTICATED,
                hasQuota = false,
                hasError = false,
            ),
        )
    }

    @Test
    fun unknownAuthIsNeverConfigured() {
        assertEquals(
            ProviderListStatus.NEVER_CONFIGURED,
            ProviderListStatus.resolve(ProviderAuthState.UNKNOWN, hasQuota = true, hasError = true),
        )
    }
}
