package de.moritzf.proxy.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

class ResponsesRequestSanitizer {
    fun sanitize(body: MutableJsonObject, defaultStore: Boolean): MutableJsonObject {
        val sanitized = body.deepCopy()
        val store = if (sanitized.get("store") != null) {
            (sanitized.get("store") as? JsonPrimitive)?.booleanOrNull ?: false
        } else {
            defaultStore
        }
        if (store) {
            return sanitized
        }
        val input = sanitized.get("input")
        if (input !is JsonArray) {
            return sanitized
        }
        val validCallIds = collectCallIds(input)
        val sanitizedInput = createArrayNode()
        for (item in input) {
            val itemObject = item as? JsonObject
            if (itemObject == null) {
                sanitizedInput.add(item)
                continue
            }
            val type = itemObject.stringPath("type", "")
            if (type == "item_reference") {
                continue
            }
            val copy = MutableJsonObject(itemObject)
            copy.remove("id")
            if (isToolOutput(type) && !validCallIds.contains(copy.get("call_id").textOrNull.orEmpty())) {
                sanitizedInput.add(toAssistantMessage(copy))
            } else {
                sanitizedInput.add(copy)
            }
        }
        sanitized.set("input", sanitizedInput)
        return sanitized
    }

    private fun collectCallIds(input: JsonArray): Set<String> {
        val callIds = mutableSetOf<String>()
        for (item in input) {
            val itemObject = item as? JsonObject ?: continue
            val type = itemObject.stringPath("type", "")
            if (isToolCall(type)) {
                val callId = itemObject.stringPath("call_id", "")
                if (callId.isNotBlank()) {
                    callIds += callId
                }
            }
        }
        return callIds
    }

    private fun toAssistantMessage(outputItem: MutableJsonObject): MutableJsonObject {
        val callId = outputItem.get("call_id").textOrNull.orEmpty()
        var output = extractOutputText(outputItem)
        val truncated = output.length > MAX_CONVERTED_OUTPUT_CHARS
        if (truncated) {
            output = output.substring(0, MAX_CONVERTED_OUTPUT_CHARS)
        }
        val message = createObjectNode()
        message.put("type", "message")
        message.put("role", "assistant")
        val suffix = if (truncated) "\n[truncated]" else ""
        message.put("content", "[Previous read result; call_id=$callId]: $output$suffix")
        return message
    }

    private fun extractOutputText(outputItem: MutableJsonObject): String {
        val output = outputItem.get("output")
        if (output != null && output !is JsonNull) {
            return nodeToText(output)
        }
        val content = outputItem.get("content")
        if (content != null && content !is JsonNull) {
            return nodeToText(content)
        }
        return buildString {
            appendField(this, outputItem, "stdout")
            appendField(this, outputItem, "stderr")
        }
    }

    private fun appendField(target: StringBuilder, node: MutableJsonObject, fieldName: String) {
        val value = (node.get(fieldName) as? JsonPrimitive)?.content.orEmpty()
        if (value.isEmpty()) {
            return
        }
        if (target.isNotEmpty()) {
            target.append('\n')
        }
        target.append(value)
    }

    private fun nodeToText(node: JsonElement): String {
        if (node.isTextual()) {
            return node.text
        }
        return try {
            JsonHelper.encodeToString(node)
        } catch (_: Exception) {
            node.text
        }
    }

    companion object {
        private const val MAX_CONVERTED_OUTPUT_CHARS = 16 * 1024
        private fun isToolCall(type: String): Boolean {
            return type == "function_call" || type == "custom_tool_call" || type == "local_shell_call"
        }

        private fun isToolOutput(type: String): Boolean {
            return type == "function_call_output" ||
                type == "custom_tool_call_output" ||
                type == "local_shell_call_output"
        }
    }
}
