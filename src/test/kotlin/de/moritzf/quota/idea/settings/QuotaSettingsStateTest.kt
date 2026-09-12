package de.moritzf.quota.idea.settings

import de.moritzf.proxy.fim.CompletionsConfig
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
            QuotaSettingsState.SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS.toSet(),
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
                QuotaProviderType.CURSOR.id,
                QuotaProviderType.GITHUB.id,
                QuotaProviderType.OPEN_AI.id,
            )
        }

        val reloaded = QuotaSettingsState()
        reloaded.loadState(state)

        assertTrue(reloaded.isSubscriptionProxyProviderEnabled(QuotaProviderType.GITHUB))
        assertTrue(reloaded.isSubscriptionProxyProviderEnabled(QuotaProviderType.OPEN_AI))
        assertFalse(reloaded.isSubscriptionProxyProviderEnabled(QuotaProviderType.CURSOR))
        assertEquals(listOf(QuotaProviderType.GITHUB.id, QuotaProviderType.OPEN_AI.id), reloaded.subscriptionProxyEnabledProviders)
    }

    @Test
    fun completionsStayDisabledUntilProxyAndFimAreEnabled() {
        val state = QuotaSettingsState().apply {
            proxyCompletionsEnabled = true
            proxyCompletionsModelId = " oa-gpt-5.5 "
            proxyCompletionsMaxOutputTokens = 9_999
            proxyCompletionsMaxRequestsPerMinute = 0
            proxyCompletionsTimeoutSeconds = 1
            proxyCompletionsPriorityTier = true
        }
        val reloaded = QuotaSettingsState()
        reloaded.loadState(state)

        assertFalse(reloaded.completionsConfig().enabled)
        assertEquals("oa-gpt-5.5", reloaded.proxyCompletionsModelId)
        assertEquals(CompletionsConfig.MAX_OUTPUT_TOKENS, reloaded.proxyCompletionsMaxOutputTokens)
        assertEquals(CompletionsConfig.MIN_REQUESTS_PER_MINUTE, reloaded.proxyCompletionsMaxRequestsPerMinute)
        assertEquals(CompletionsConfig.MIN_TIMEOUT_SECONDS, reloaded.proxyCompletionsTimeoutSeconds)
        assertEquals(CompletionsConfig.timeoutMillis(CompletionsConfig.MIN_TIMEOUT_SECONDS), reloaded.completionsConfig().timeoutMillis)
        assertTrue(reloaded.proxyCompletionsPriorityTier)
        assertTrue(reloaded.completionsConfig().priorityTier)

        reloaded.openAiProxyEnabled = true
        assertTrue(reloaded.completionsConfig().enabled)
        assertEquals("oa-gpt-5.5", reloaded.completionsConfig().modelLocalId)
        assertTrue(reloaded.completionsConfig().acceptsModel(CompletionsConfig.FIM_ALIAS_ID))
    }
}
