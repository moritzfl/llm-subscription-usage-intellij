package de.moritzf.proxy.subscription

import de.moritzf.proxy.model.ReasoningEffort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fits the request's reasoning effort to [SubscriptionProxyModel.reasoningEfforts]: chat
 * `reasoning_effort` and Responses `reasoning.effort`. Unknown support leaves the body unchanged.
 */
internal fun adaptReasoningEffort(
    route: SubscriptionProxyRoute,
    levels: List<String>?,
    body: JsonObject,
): JsonObject {
    if (levels == null) return body
    return when (route) {
        SubscriptionProxyRoute.CHAT_COMPLETIONS -> {
            if ("reasoning_effort" !in body) return body
            val requested = (body["reasoning_effort"] as? JsonPrimitive)?.contentOrNull
            val level = ReasoningEffort.fit(requested, levels)
            if (level != null && level == requested) body
            else
                JsonObject(
                    body - "reasoning_effort" +
                        listOfNotNull(level?.let { "reasoning_effort" to JsonPrimitive(it) })
                )
        }
        SubscriptionProxyRoute.RESPONSES -> {
            val reasoning = body["reasoning"] as? JsonObject ?: return body
            if ("effort" !in reasoning) return body
            val requested = (reasoning["effort"] as? JsonPrimitive)?.contentOrNull
            val level = ReasoningEffort.fit(requested, levels)
            if (level != null && level == requested) return body
            val adapted =
                reasoning - "effort" + listOfNotNull(level?.let { "effort" to JsonPrimitive(it) })
            if (adapted.isEmpty()) JsonObject(body - "reasoning")
            else JsonObject(body + ("reasoning" to JsonObject(adapted)))
        }
        else -> body
    }
}
