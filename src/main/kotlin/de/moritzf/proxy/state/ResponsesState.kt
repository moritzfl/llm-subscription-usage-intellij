package de.moritzf.proxy.state
import de.moritzf.proxy.server.MutableJsonArray
import de.moritzf.proxy.server.MutableJsonObject
import de.moritzf.proxy.server.createArrayNode
import de.moritzf.proxy.server.isTextual
import de.moritzf.proxy.server.stringPathOrNull
import de.moritzf.proxy.server.textOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
/**
 * Bounded in-memory compatibility cache for Responses API replay references.
 *
 * The cache resolves previous_response_id and item_reference within a single process only.
 * It is not durable storage and should be scoped by the caller before use.
 */
class ResponsesState {
    private data class CachedResponse(
        val input: JsonArray,
        val output: JsonArray,
    )
    private val items = object : LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?): Boolean {
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
    fun requiresCachedState(body: JsonObject): Boolean {
        if (body["previous_response_id"].isTextual()) {
            return true
        }
        val input = body["input"]
        if (input is JsonArray) {
            for (item in input) {
                val itemObject = item as? JsonObject ?: continue
                if (itemObject.stringPathOrNull("type") == "item_reference" &&
                    itemObject["id"].isTextual()
                ) {
                    return true
                }
            }
        }
        return false
    }
    @Synchronized
    fun expandRequestBody(body: MutableJsonObject): MutableJsonObject {
        val nextBody = body.deepCopy()
        var previousResponseId: String? = null
        if (body.get("previous_response_id").isTextual()) {
            previousResponseId = body.get("previous_response_id").textOrNull
        }
        val previousHistory = if (previousResponseId != null) responses[previousResponseId] else null
        val directInput = body.get("input")
        val expandedInput = if (directInput is JsonArray) {
            expandInput(directInput)
        } else {
            null
        }
        if (previousHistory != null) {
            val combined = createArrayNode()
            combined.addAll(previousHistory.input.asIterable())
            combined.addAll(previousHistory.output.asIterable())
            if (expandedInput != null) {
                combined.addAll(expandedInput)
            }
            nextBody.set("input", combined)
            nextBody.remove("previous_response_id")
            return nextBody
        }
        if (expandedInput != null) {
            nextBody.set("input", expandedInput)
        }
        return nextBody
    }
    @Synchronized
    fun rememberResponse(response: JsonElement?, requestBody: JsonElement?) {
        val responseObject = response as? JsonObject ?: return
        val responseId = responseObject["id"].textOrNull
        val outputNode = responseObject["output"]
        val output = createArrayNode()
        if (outputNode is JsonArray) {
            for (item in outputNode) {
                val itemObject = item as? JsonObject ?: continue
                output.add(itemObject)
                val itemId = itemObject["id"].textOrNull
                if (itemId != null) {
                    items.remove(itemId)
                    items[itemId] = itemObject
                }
            }
        }
        if (responseId != null && requestBody != null) {
            val input = createArrayNode()
            val requestObject = requestBody as? JsonObject
            val inputNode = requestObject?.get("input")
            if (inputNode is JsonArray) {
                input.addAll(inputNode.asIterable())
            }
            responses.remove(responseId)
            responses[responseId] = CachedResponse(input.build(), output.build())
        }
    }
    // Must be called from a synchronized context (items is not thread-safe on its own).
    private fun expandInput(input: JsonArray): MutableJsonArray {
        val expanded = createArrayNode()
        for (item in input) {
            val itemObject = item as? JsonObject
            if (itemObject != null &&
                itemObject.stringPathOrNull("type") == "item_reference" &&
                itemObject["id"].isTextual()
            ) {
                val id = itemObject["id"].textOrNull.orEmpty()
                val cached = items[id]
                if (cached != null) {
                    expanded.add(cached)
                    continue
                }
            }
            expanded.add(item)
        }
        return expanded
    }
    companion object {
        private const val MAX_ITEM_CACHE_SIZE = 2_000
        private const val MAX_RESPONSE_CACHE_SIZE = 256
    }
}
