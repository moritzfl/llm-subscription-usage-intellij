package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuotaSettingsStateTest {
    @Test
    fun subscriptionProxyProvidersDefaultToSupportedProviders() {
        val state = QuotaSettingsState()

        assertEquals(
            setOf(QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK, QuotaProviderType.GITHUB),
            state.enabledSubscriptionProxyProviders(),
        )
    }

    @Test
    fun subscriptionProxyProvidersCanAllBeDisabled() {
        val state = QuotaSettingsState()
        QuotaSettingsState.SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS.forEach { provider ->
            state.setSubscriptionProxyProviderEnabled(provider, false)
        }

        val reloaded = QuotaSettingsState()
        reloaded.loadState(state)

        assertEquals(emptySet(), reloaded.enabledSubscriptionProxyProviders())
    }

    @Test
    fun subscriptionProxyProvidersIgnoreUnsupportedAndDuplicateIds() {
        val state = QuotaSettingsState().apply {
            subscriptionProxyEnabledProviders = mutableListOf(
                QuotaProviderType.GITHUB.id,
                QuotaProviderType.KIMI.id,
                QuotaProviderType.GITHUB.id,
                QuotaProviderType.OPEN_AI.id,
            )
        }

        val reloaded = QuotaSettingsState()
        reloaded.loadState(state)

        assertTrue(reloaded.isSubscriptionProxyProviderEnabled(QuotaProviderType.GITHUB))
        assertTrue(reloaded.isSubscriptionProxyProviderEnabled(QuotaProviderType.OPEN_AI))
        assertFalse(reloaded.isSubscriptionProxyProviderEnabled(QuotaProviderType.KIMI))
        assertEquals(listOf(QuotaProviderType.GITHUB.id, QuotaProviderType.OPEN_AI.id), reloaded.subscriptionProxyEnabledProviders)
    }
}
