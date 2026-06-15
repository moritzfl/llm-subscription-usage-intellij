package de.moritzf.proxy.sse
import de.moritzf.proxy.util.Json
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.io.InputStream
object SseCollector {
    fun collectCompletedResponse(input: InputStream): JsonNode {
        var latestResponse: JsonNode? = null
        var failedResponse: JsonNode? = null
        var latestError: JsonNode? = null
        val outputTextDeltas = StringBuilder()
        val completedItems = mutableListOf<JsonNode>()
        for (event in SseParser.parse(input)) {
            val data = event.data()
            if (data.isNullOrEmpty()) {
                continue
            }
            try {
                val parsed = Json.MAPPER.readTree(data)
                if (parsed == null || !parsed.isObject) {
                    continue
                }
                if (event.event() == "error") {
                    latestError = parsed
                    continue
                }
                val eventType = parsed.path("type").asText(event.event().orEmpty())
                when (eventType) {
                    "response.output_text.delta" -> {
                        val delta = parsed.path("delta").asText("")
                        if (delta.isNotEmpty()) {
                            outputTextDeltas.append(delta)
                        }
                    }
                    "response.output_item.done" -> {
                        val item = parsed.get("item")
                        if (item != null && item.isObject) {
                            completedItems.add(item)
                        }
                    }
                    "response.completed" -> {
                        val response = parsed.get("response")
                        if (response != null && response.isObject) {
                            latestResponse = response
                        }
                    }
                    "response.failed", "response.cancelled" -> {
                        val response = parsed.get("response")
                        if (response != null && response.isObject) {
                            failedResponse = response
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (latestResponse == null) {
            latestResponse = failedResponse
        }
        val response = latestResponse
        if (response != null) {
            var completed = response
            if (!hasOutputItems(completed) && completedItems.isNotEmpty()) {
                val copy = completed.deepCopy<ObjectNode>()
                val output = Json.MAPPER.createArrayNode()
                completedItems.forEach(output::add)
                copy.set<ArrayNode>("output", output)
                completed = copy
            }
            if (outputTextDeltas.isNotEmpty() && !containsOutputText(completed)) {
                return appendOutputText(completed, outputTextDeltas.toString())
            }
            return completed
        }
        val errorInfo = latestError?.let { " Last error: $it" }.orEmpty()
        throw IOException("No completed response found in SSE stream.$errorInfo")
    }
    private fun hasOutputItems(response: JsonNode): Boolean {
        val output = response.get("output")
        return output != null && output.isArray && !output.isEmpty
    }
    private fun containsOutputText(response: JsonNode): Boolean {
        val output = response.get("output")
        if (output == null || !output.isArray) {
            return false
        }
        for (item in output) {
            val content = item.get("content")
            if (content == null || !content.isArray) {
                continue
            }
            for (part in content) {
                if (part.path("type").asText() == "output_text" && part.hasNonNull("text")) {
                    return true
                }
            }
        }
        return false
    }
    private fun appendOutputText(response: JsonNode, text: String): JsonNode {
        val copy = response.deepCopy<ObjectNode>()
        val existingOutput = copy.get("output")
        val output = if (existingOutput != null && existingOutput.isArray) {
            existingOutput as ArrayNode
        } else {
            Json.MAPPER.createArrayNode().also { copy.set<ArrayNode>("output", it) }
        }
        val message = Json.MAPPER.createObjectNode()
        message.put("type", "message")
        message.put("role", "assistant")
        val content = Json.MAPPER.createArrayNode()
        val textPart = Json.MAPPER.createObjectNode()
        textPart.put("type", "output_text")
        textPart.put("text", text)
        content.add(textPart)
        message.set<ArrayNode>("content", content)
        output.add(message)
        return copy
    }
}
