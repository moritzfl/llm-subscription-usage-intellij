package de.moritzf.quota.github.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubCopilotClaudeChatBridgeTest {
    private val bridge = GitHubCopilotClaudeChatBridge()

    @Test
    fun returnsThinkingBlocksAsReasoningContent() {
        val response =
            """{"id":"msg_1","type":"message","role":"assistant","content":[
                {"type":"thinking","thinking":"17*23 is 391.","signature":"sig"},
                {"type":"text","text":"391"}
            ],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":9}}"""

        val message =
            parse(bridge.anthropicMessageToOpenAiChat(request(), response))["choices"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonObject

        assertEquals("391", message["content"]!!.jsonPrimitive.content)
        assertEquals("17*23 is 391.", message["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun omitsReasoningContentWithoutThinking() {
        val response =
            """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"391"}],"stop_reason":"end_turn"}"""

        val message =
            parse(bridge.anthropicMessageToOpenAiChat(request(), response))["choices"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonObject

        assertTrue("reasoning_content" !in message)
    }

    @Test
    fun streamsThinkingDeltasAsReasoningContent() {
        val chunk =
            bridge.anthropicSseDataToOpenAiChat(
                request(),
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}""",
            )
        val delta = parse(chunk)["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject

        assertEquals("think", delta["reasoning_content"]!!.jsonPrimitive.content)
        assertTrue("content" !in delta)

        val signature =
            bridge.anthropicSseDataToOpenAiChat(
                request(),
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            )
        val empty = parse(signature)["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject
        assertTrue(empty.isEmpty(), empty.toString())
    }

    private fun request(): SubscriptionProxyRequest =
        SubscriptionProxyRequest(
            SubscriptionProxyRoute.CHAT_COMPLETIONS,
            "req-claude",
            SubscriptionProxyModel(
                localId = "gh-claude-test",
                upstreamId = "claude-test",
                providerId = "github",
                providerName = "GitHub Copilot",
                litellmProvider = "github_copilot",
                supportedRoutes =
                    setOf(
                        SubscriptionProxyRoute.CHAT_COMPLETIONS,
                        SubscriptionProxyRoute.ANTHROPIC_MESSAGES,
                    ),
            ),
            parse("""{"model":"gh-claude-test","messages":[]}"""),
        )

    private fun parse(json: String): JsonObject =
        JsonHelper.parseToJsonElementOrNull(json) as JsonObject
}
