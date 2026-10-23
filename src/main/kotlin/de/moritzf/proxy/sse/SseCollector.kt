package de.moritzf.proxy.sse

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.MutableJsonArray
import de.moritzf.proxy.server.MutableJsonObject
import de.moritzf.proxy.server.createArrayNode
import de.moritzf.proxy.server.createObjectNode
import de.moritzf.proxy.server.hasNonNull
import de.moritzf.proxy.server.pathOrNull
import de.moritzf.proxy.server.stringPath
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object SseCollector {
    fun collectCompletedResponse(input: InputStream): JsonObject {
        var terminalResponse: JsonObject? = null
        var failedResponse: JsonObject? = null
        var seedResponse: JsonObject? = null
        var latestError: JsonObject? = null
        var sawDoneSentinel = false
        val outputTextDeltas = StringBuilder()
        val completedItems = mutableListOf<JsonObject>()
        for (event in SseParser.parse(input)) {
            val data = event.data()
            if (data.isNullOrEmpty()) {
                continue
            }
            if ("[DONE]" == data) {
                sawDoneSentinel = true
                continue
            }
            try {
                val parsed = JsonHelper.parseToJsonElementOrNull(data) as? JsonObject ?: continue
                val eventType = parsed.stringPath("type", event.event().orEmpty())
                if (event.event() == "error" || eventType == "error") {
                    latestError = parsed
                    continue
                }
                // Some gateways send an OpenAI error envelope without a type (or with a
                // non-response type) instead of a documented error event.
                if (
                    parsed.pathOrNull("error") is JsonObject && !eventType.startsWith("response.")
                ) {
                    latestError = parsed
                    continue
                }
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
                    // Keep the last metadata snapshot so a stream that ends without a terminal
                    // event can still be returned with its id/model.
                    "response.created",
                    "response.in_progress" -> {
                        val response = parsed["response"]
                        if (response is JsonObject) {
                            seedResponse = response
                        }
                    }
                    // response.incomplete is the documented terminal event when generation stops
                    // early (for example max_output_tokens), so it must not be treated as a
                    // truncated stream.
                    "response.completed",
                    "response.incomplete" -> {
                        val response = parsed["response"]
                        if (response is JsonObject) {
                            terminalResponse = withTerminalStatus(response, eventType)
                        }
                    }
                    "response.failed",
                    "response.cancelled" -> {
                        val response = parsed["response"]
                        if (response is JsonObject) {
                            failedResponse = response
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        val response = terminalResponse ?: failedResponse
        if (response != null) {
            return mergeCollectedOutput(response, completedItems, outputTextDeltas)
        }
        // A lone error event is terminal for the stream and carries the reason the upstream
        // aborted. Returning it as a failed response lets callers surface that reason instead of a
        // generic "no completed response" failure.
        if (latestError != null) {
            return failedResponseFromError(latestError)
        }
        // Some gateways close the stream (or send [DONE]) without a terminal response event.
        // Synthesize a response from the deltas/items so a non-streaming client still gets the
        // generated content.
        if (completedItems.isNotEmpty() || outputTextDeltas.isNotEmpty()) {
            return synthesizeResponse(
                seedResponse,
                completedItems,
                outputTextDeltas.toString(),
                sawDoneSentinel,
            )
        }
        throw IOException("No completed response found in SSE stream.")
    }

    private fun withTerminalStatus(response: JsonObject, eventType: String): JsonObject {
        if (response.hasNonNull("status")) {
            return response
        }
        val copy = MutableJsonObject(response)
        copy.put(
            "status",
            if ("response.incomplete" == eventType) "incomplete" else "completed",
        )
        return copy.build()
    }

    private fun mergeCollectedOutput(
        response: JsonObject,
        completedItems: List<JsonObject>,
        outputTextDeltas: StringBuilder,
    ): JsonObject {
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

    private fun synthesizeResponse(
        seed: JsonObject?,
        completedItems: List<JsonObject>,
        outputText: String,
        sawDoneSentinel: Boolean,
    ): JsonObject {
        val copy = MutableJsonObject(seed ?: JsonObject(emptyMap()))
        if (!copy.has("object")) {
            copy.put("object", "response")
        }
        // A gateway that closed with [DONE] considers its stream finished; without it the
        // connection ended while the response was still generating.
        copy.put("status", if (sawDoneSentinel) "completed" else "incomplete")
        val output = createArrayNode()
        completedItems.forEach(output::add)
        copy.set("output", output)
        val synthesized = copy.build()
        if (outputText.isNotEmpty() && !containsOutputText(synthesized)) {
            return appendOutputText(synthesized, outputText)
        }
        return synthesized
    }

    private fun failedResponseFromError(errorEvent: JsonObject): JsonObject {
        val message =
            errorEvent
                .stringPath("message", "")
                .ifEmpty { errorEvent.pathOrNull("error").stringPath("message", "") }
                .ifEmpty { errorEvent.toString() }
        val code =
            errorEvent
                .stringPath("code", "")
                .ifEmpty { errorEvent.pathOrNull("error").stringPath("code", "") }
                .ifEmpty { errorEvent.pathOrNull("error").stringPath("type", "") }
                .ifEmpty { "upstream_error" }
        val response = createObjectNode()
        response.put("status", "failed")
        val error = createObjectNode()
        error.put("code", code)
        error.put("message", message)
        response.set("error", error)
        return response.build()
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
                if (
                    partObject.stringPath("type") == "output_text" && partObject.hasNonNull("text")
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun appendOutputText(response: JsonObject, text: String): JsonObject {
        val copy = MutableJsonObject(response)
        val existingOutput = copy.get("output")
        val output: MutableJsonArray =
            if (existingOutput is JsonArray) {
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
