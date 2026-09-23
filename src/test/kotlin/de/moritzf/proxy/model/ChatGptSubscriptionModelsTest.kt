package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatGptSubscriptionModelsTest {
    @Test
    fun rejectsRetiredChatGptSlugsAndKeepsCurrentOnes() {
        for (model in listOf("gpt-5.4", "gpt-5.4-mini", "gpt-5.4 (high)", "gpt-5.2", "gpt-5.3-codex", "gpt-5.5-pro")) {
            assertTrue(ChatGptSubscriptionModels.isUnsupported(model), model)
        }
        for (model in listOf("gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-reserve")) {
            assertFalse(ChatGptSubscriptionModels.isUnsupported(model), model)
        }
    }
}
