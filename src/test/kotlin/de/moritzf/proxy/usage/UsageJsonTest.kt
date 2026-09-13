package de.moritzf.proxy.usage

import de.moritzf.proxy.server.JsonHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageJsonTest {
    @Test
    fun readsOpenAiPromptAndCompletionTokens() {
        val usage = JsonHelper.parseToJsonElementOrNull("{\"prompt_tokens\":3,\"completion_tokens\":5}")
        assertEquals(3L to 5L, UsageJson.tokensFrom(usage))
    }

    @Test
    fun readsResponsesInputAndOutputTokens() {
        val usage = JsonHelper.parseToJsonElementOrNull("{\"input_tokens\":11,\"output_tokens\":2}")
        assertEquals(11L to 2L, UsageJson.tokensFrom(usage))
    }

    @Test
    fun prefersOpenAiKeysWhenBothArePresent() {
        val usage = JsonHelper.parseToJsonElementOrNull(
            "{\"prompt_tokens\":1,\"completion_tokens\":2,\"input_tokens\":9,\"output_tokens\":8}",
        )
        assertEquals(1L to 2L, UsageJson.tokensFrom(usage))
    }

    @Test
    fun ignoresEmptyUsage() {
        assertNull(UsageJson.tokensFrom(JsonHelper.parseToJsonElementOrNull("{}")))
        assertNull(UsageJson.tokensFrom(null))
    }
}
