package de.moritzf.proxy.state

import de.moritzf.proxy.util.Json
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Bounded in-memory compatibility cache for Responses API replay references.
 *
 * The cache resolves previous_response_id and item_reference within a single process only.
 * It is not durable storage and should be scoped by the caller before use.
 */
class ResponsesState {
    private data class CachedResponse(
        val input: ArrayNode,
        val output: ArrayNode,
    )

    private val items = object : LinkedHashMap<String, JsonNode>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonNode>?): Boolean {
            return size > MAX_ITEM_CACHE_SIZE
        }
    }

    private val responses = object : LinkedHashMap<String, CachedResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResponse>?): Boolean {
            return size > MAX_RESPONSE_CACHE_SIZE
        }
    }

    @Synchronized
    @Suppress("unused")
    fun requiresCachedState(body: JsonNode): Boolean {
        if (body.has("previous_response_id") && body.get("previous_response_id").isTextual) {
            return true
        }
        val input = body.get("input")
        if (input != null && input.isArray) {
            for (item in input) {
                if (item.isObject &&
                    item.path("type").asText(null) == "item_reference" &&
                    item.has("id") && item.get("id").isTextual
                ) {
                    return true
                }
            }
        }
        return false
    }

    @Synchronized
    fun expandRequestBody(body: ObjectNode): ObjectNode {
        val nextBody: ObjectNode = body.deepCopy()

        var previousResponseId: String? = null
        if (body.has("previous_response_id") && body.get("previous_response_id").isTextual) {
            previousResponseId = body.get("previous_response_id").asText()
        }

        val previousHistory = if (previousResponseId != null) responses[previousResponseId] else null

        val directInput = body.get("input")
        val expandedInput = if (directInput != null && directInput.isArray) {
            expandInput(directInput as ArrayNode)
        } else {
            null
        }

        if (previousHistory != null) {
            val combined = Json.MAPPER.createArrayNode()
            combined.addAll(previousHistory.input.deepCopy())
            combined.addAll(previousHistory.output.deepCopy())
            if (expandedInput != null) {
                combined.addAll(expandedInput)
            }
            nextBody.set<ArrayNode>("input", combined)
            nextBody.remove("previous_response_id")
            return nextBody
        }

        if (expandedInput != null) {
            nextBody.set<ArrayNode>("input", expandedInput)
        }

        return nextBody
    }

    @Synchronized
    fun rememberResponse(response: JsonNode?, requestBody: JsonNode?) {
        if (response == null || !response.isObject) {
            return
        }

        val responseId = if (response.has("id") && response.get("id").isTextual) {
            response.get("id").asText()
        } else {
            null
        }

        val outputNode = response.get("output")
        val output = Json.MAPPER.createArrayNode()
        if (outputNode != null && outputNode.isArray) {
            for (item in outputNode) {
                if (item.isObject) {
                    output.add(item.deepCopy())
                    val itemId = if (item.has("id") && item.get("id").isTextual) {
                        item.get("id").asText()
                    } else {
                        null
                    }
                    if (itemId != null) {
                        items.remove(itemId)
                        items[itemId] = item.deepCopy()
                    }
                }
            }
        }

        if (responseId != null && requestBody != null) {
            val input = Json.MAPPER.createArrayNode()
            val inputNode = requestBody.get("input")
            if (inputNode != null && inputNode.isArray) {
                for (item in inputNode) {
                    input.add(item.deepCopy())
                }
            }
            responses.remove(responseId)
            responses[responseId] = CachedResponse(input, output)
        }
    }

    // Must be called from a synchronized context (items is not thread-safe on its own).
    private fun expandInput(input: ArrayNode): ArrayNode {
        val expanded = Json.MAPPER.createArrayNode()
        for (item in input) {
            if (item.isObject &&
                item.path("type").asText(null) == "item_reference" &&
                item.has("id") && item.get("id").isTextual
            ) {
                val id = item.get("id").asText()
                val cached = items[id]
                if (cached != null) {
                    expanded.add(cached.deepCopy())
                    continue
                }
            }
            expanded.add(item.deepCopy())
        }
        return expanded
    }

    companion object {
        private const val MAX_ITEM_CACHE_SIZE = 2_000
        private const val MAX_RESPONSE_CACHE_SIZE = 256
    }
}
