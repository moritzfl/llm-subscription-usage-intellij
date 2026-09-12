package de.moritzf.proxy.fim

import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FimModelsTest {
    @Test
    fun priorityTierOnlyForGrokAndCodex() {
        assertTrue(FimModels.supportsPriorityTier("sg-grok-4.6", "supergrok"))
        assertTrue(FimModels.supportsPriorityTier("oa-gpt-5.6-luna", "openai"))
        assertTrue(FimModels.supportsPriorityTier("sg-grok-4.6"))
        assertTrue(FimModels.supportsPriorityTier("oa-gpt-5.5"))
        assertFalse(FimModels.supportsPriorityTier("mi-codestral-latest", "mistral"))
        assertFalse(FimModels.supportsPriorityTier("ol-glm-5.3", "ollama"))
        assertTrue(FimModels.supportsPriorityTier(model("sg-grok-4", "supergrok")))
        assertFalse(FimModels.supportsPriorityTier(model("mi-codestral-latest", "mistral")))
    }

    private fun model(localId: String, providerId: String): SubscriptionProxyModel {
        return SubscriptionProxyModel(
            localId = localId,
            upstreamId = localId.substringAfter("-"),
            providerId = providerId,
            providerName = providerId,
            litellmProvider = providerId,
            supportedRoutes = setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
        )
    }
}
