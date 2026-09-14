package de.moritzf.proxy.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

object LiteLlmRequestSanitizer {
    private val CHAT_COMPLETION_KEYS = setOf(
        "model",
        "messages",
        "temperature",
        "top_p",
        "n",
        "stream",
        "stream_options",
        "stop",
        "max_tokens",
        "max_completion_tokens",
        "tools",
        "tool_choice",
        "functions",
        "function_call",
        "response_format",
        "parallel_tool_calls",
        "presence_penalty",
        "frequency_penalty",
        "reasoning_effort",
        "verbosity",
        "prompt_cache_key",
        "service_tier",
        "store",
        "logprobs",
        "top_logprobs",
        "prediction",
    )

    fun sanitize(route: SubscriptionProxyRoute, body: JsonObject): JsonObject {
        val dropRequested = (body["drop_params"] as? JsonPrimitive)?.booleanOrNull == true
        if (!dropRequested && "drop_params" !in body) {
            return body
        }
        if (!dropRequested || route != SubscriptionProxyRoute.CHAT_COMPLETIONS) {
            return omitKeys(body, setOf("drop_params"))
        }
        return omitKeys(body) { it !in CHAT_COMPLETION_KEYS }
    }

    private fun omitKeys(body: JsonObject, keys: Set<String>): JsonObject {
        return omitKeys(body) { it in keys }
    }

    private fun omitKeys(body: JsonObject, drop: (String) -> Boolean): JsonObject {
        if (body.keys.none(drop)) {
            return body
        }
        return buildJsonObject {
            body.forEach { (key, value) ->
                if (!drop(key)) put(key, value)
            }
        }
    }
}
