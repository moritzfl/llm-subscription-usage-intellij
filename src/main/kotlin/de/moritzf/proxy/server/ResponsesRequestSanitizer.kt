package de.moritzf.proxy.server

import de.moritzf.proxy.util.Json.MAPPER
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

class ResponsesRequestSanitizer {
    fun sanitize(body: ObjectNode, defaultStore: Boolean): ObjectNode {
        val sanitized = body.deepCopy()
        val store = if (sanitized.has("store")) sanitized.path("store").asBoolean(false) else defaultStore
        if (store) {
            return sanitized
        }

        val input = sanitized.get("input")
        if (input == null || !input.isArray) {
            return sanitized
        }

        val validCallIds = collectCallIds(input)
        val sanitizedInput = MAPPER.createArrayNode()
        for (item in input) {
            if (!item.isObject) {
                sanitizedInput.add(item.deepCopy<JsonNode>())
                continue
            }

            val type = item.path("type").asText("")
            if (type == "item_reference") {
                continue
            }

            val copy = item.deepCopy<ObjectNode>()
            copy.remove("id")
            if (isToolOutput(type) && !validCallIds.contains(copy.path("call_id").asText(""))) {
                sanitizedInput.add(toAssistantMessage(copy))
            } else {
                sanitizedInput.add(copy)
            }
        }
        sanitized.set<JsonNode>("input", sanitizedInput)
        return sanitized
    }

    private fun collectCallIds(input: JsonNode): Set<String> {
        val callIds = mutableSetOf<String>()
        for (item in input) {
            if (!item.isObject) {
                continue
            }
            val type = item.path("type").asText("")
            if (isToolCall(type)) {
                val callId = item.path("call_id").asText("")
                if (callId.isNotBlank()) {
                    callIds += callId
                }
            }
        }
        return callIds
    }

    private fun toAssistantMessage(outputItem: ObjectNode): ObjectNode {
        val callId = outputItem.path("call_id").asText("")
        var output = extractOutputText(outputItem)
        val truncated = output.length > MAX_CONVERTED_OUTPUT_CHARS
        if (truncated) {
            output = output.substring(0, MAX_CONVERTED_OUTPUT_CHARS)
        }

        val message = MAPPER.createObjectNode()
        message.put("type", "message")
        message.put("role", "assistant")
        val suffix = if (truncated) "\n[truncated]" else ""
        message.put("content", "[Previous read result; call_id=$callId]: $output$suffix")
        return message
    }

    private fun extractOutputText(outputItem: ObjectNode): String {
        val output = outputItem.get("output")
        if (output != null && !output.isMissingNode && !output.isNull) {
            return nodeToText(output)
        }

        val content = outputItem.get("content")
        if (content != null && !content.isMissingNode && !content.isNull) {
            return nodeToText(content)
        }

        return buildString {
            appendField(this, outputItem, "stdout")
            appendField(this, outputItem, "stderr")
        }
    }

    private fun appendField(target: StringBuilder, node: ObjectNode, fieldName: String) {
        val value = node.path(fieldName).asText("")
        if (value.isEmpty()) {
            return
        }
        if (target.isNotEmpty()) {
            target.append('\n')
        }
        target.append(value)
    }

    private fun nodeToText(node: JsonNode): String {
        if (node.isTextual) {
            return node.asText()
        }
        return try {
            MAPPER.writeValueAsString(node)
        } catch (_: JsonProcessingException) {
            node.asText("")
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
