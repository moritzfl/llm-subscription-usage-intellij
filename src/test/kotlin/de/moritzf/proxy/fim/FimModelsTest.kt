package de.moritzf.proxy.fim

import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FimModelsTest {
    @Test
    fun nativeFimIdsAreCodestralAndFimNotChatOrAudio() {
        assertTrue(FimModels.isNativeFimId("mi-codestral-latest"))
        assertTrue(FimModels.isNativeFimId("mistral-code-fim-latest"))
        assertTrue(FimModels.isNativeFimId("codestral-latest"))
        assertFalse(FimModels.isNativeFimId("qwen2.5-coder"))
        assertFalse(FimModels.isNativeFimId("mistral-small-latest"))
        assertFalse(FimModels.isNativeFimId("voxtral-mini-tts-2603"))
        assertFalse(FimModels.isNativeFimId("codestral-embed"))
        assertFalse(FimModels.isNativeFimId(model("sg-grok-4.6", "supergrok")))
        assertTrue(FimModels.isNativeFimId(model("mi-codestral-latest", "mistral")))
    }

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

    @Test
    fun fimReasoningEffortOnlyForGrokAndCodexReasoningModels() {
        assertEquals("low", FimModels.fimReasoningEffort("sg-grok-4.6", "grok-4.6", "supergrok"))
        assertEquals("low", FimModels.fimReasoningEffort("oa-gpt-6-astra", "gpt-6-astra", "openai"))
        assertNull(FimModels.fimReasoningEffort("sg-grok-4.20-0309-non-reasoning", "grok-4.20-0309-non-reasoning", "supergrok"))
        assertNull(FimModels.fimReasoningEffort("mi-mistral-small-latest", "mistral-small-latest", "mistral"))
        assertNull(FimModels.fimReasoningEffort("ol-kimi-k3", "kimi-k3", "ollama"))
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
