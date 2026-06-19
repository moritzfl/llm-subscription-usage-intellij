package de.moritzf.proxy.sse
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.MutableJsonArray
import de.moritzf.proxy.server.MutableJsonObject
import de.moritzf.proxy.server.createArrayNode
import de.moritzf.proxy.server.createObjectNode
import de.moritzf.proxy.server.hasNonNull
import de.moritzf.proxy.server.stringPath
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
object SseCollector {
    fun collectCompletedResponse(input: InputStream): JsonObject {
        var latestResponse: JsonObject? = null
        var failedResponse: JsonObject? = null
        var latestError: JsonObject? = null
        val outputTextDeltas = StringBuilder()
        val completedItems = mutableListOf<JsonObject>()
        for (event in SseParser.parse(input)) {
            val data = event.data()
            if (data.isNullOrEmpty()) {
                continue
            }
            try {
                val parsed = JsonHelper.parseToJsonElementOrNull(data) as? JsonObject ?: continue
                if (event.event() == "error") {
                    latestError = parsed
                    continue
                }
                val eventType = parsed.stringPath("type", event.event().orEmpty())
                when (eventType) {
                    "response.output_text.delta" -> {
                        val delta = parsed.stringPath("delta", "")
                        if (delta.isNotEmpty()) {
                            outputTextDeltas.append(delta)
                        }
                    }
                    "response.output_item.done" -> {
                        val item = parsed["item"]
                        if (item is JsonObject) {
                            completedItems.add(item)
                        }
                    }
                    "response.completed" -> {
                        val response = parsed["response"]
                        if (response is JsonObject) {
                            latestResponse = response
                        }
                    }
                    "response.failed", "response.cancelled" -> {
                        val response = parsed["response"]
                        if (response is JsonObject) {
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
                val copy = MutableJsonObject(completed)
                val output = createArrayNode()
                completedItems.forEach(output::add)
                copy.set("output", output)
                completed = copy.build()
            }
            if (outputTextDeltas.isNotEmpty() && !containsOutputText(completed)) {
                return appendOutputText(completed, outputTextDeltas.toString())
            }
            return completed
        }
        val errorInfo = latestError?.let { " Last error: $it" }.orEmpty()
        throw IOException("No completed response found in SSE stream.$errorInfo")
    }
    private fun hasOutputItems(response: JsonObject): Boolean {
        val output = response["output"] as? JsonArray
        return output != null && output.isNotEmpty()
    }
    private fun containsOutputText(response: JsonObject): Boolean {
        val output = response["output"] as? JsonArray ?: return false
        for (item in output) {
            val content = (item as? JsonObject)?.get("content") as? JsonArray ?: continue
            for (part in content) {
                val partObject = part as? JsonObject ?: continue
                if (partObject.stringPath("type") == "output_text" && partObject.hasNonNull("text")) {
                    return true
                }
            }
        }
        return false
    }
    private fun appendOutputText(response: JsonObject, text: String): JsonObject {
        val copy = MutableJsonObject(response)
        val existingOutput = copy.get("output")
        val output: MutableJsonArray = if (existingOutput is JsonArray) {
            MutableJsonArray(existingOutput)
        } else {
            createArrayNode()
        }
        val message = createObjectNode()
        message.put("type", "message")
        message.put("role", "assistant")
        val content = createArrayNode()
        val textPart = createObjectNode()
        textPart.put("type", "output_text")
        textPart.put("text", text)
        content.add(textPart)
        message.set("content", content)
        output.add(message)
        copy.set("output", output)
        return copy.build()
    }
}
