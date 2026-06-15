package de.moritzf.proxy.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.model.ModelAliasResolver
import de.moritzf.proxy.server.JsonHelper.MAPPER

internal class ChatCompletionsRequestMapper(
    private val store: Boolean,
    private val instructionsProvider: CodexInstructionsProvider,
    private val modelAliasResolver: ModelAliasResolver,
) {
    fun build(chatBody: JsonNode, model: String, aliasReasoningEffort: String?): ObjectNode {
        val upstream: ObjectNode = MAPPER.createObjectNode()
        upstream.put("model", model)
        upstream.put("stream", true)
        upstream.put("store", store)

        // Convert messages to Responses API format.
        val input: ArrayNode = MAPPER.createArrayNode()
        val instructions = StringBuilder()
        val messages = chatBody.get("messages")
        for (msg in messages) {
            val role = msg.path("role").asText("")
            when (role) {
                "system", "developer" -> {
                    val text = extractTextContent(msg.get("content"))
                    if (!text.isEmpty()) {
                        if (!instructions.isEmpty()) instructions.append("\n")
                        instructions.append(text)
                    }
                }
                "user" -> {
                    val item: ObjectNode = MAPPER.createObjectNode()
                    item.put("type", "message")
                    item.put("role", "user")
                    val content: ArrayNode = MAPPER.createArrayNode()
                    addContentParts(content, msg.get("content"))
                    item.set<JsonNode?>("content", content)
                    input.add(item)
                }
                "assistant" -> {
                    val text = extractTextContent(msg.get("content"))
                    val toolCalls = msg.get("tool_calls")
                    if (!text.isEmpty()) {
                        val item: ObjectNode = MAPPER.createObjectNode()
                        item.put("type", "message")
                        item.put("role", "assistant")
                        val content: ArrayNode = MAPPER.createArrayNode()
                        val textPart: ObjectNode = MAPPER.createObjectNode()
                        textPart.put("type", "output_text")
                        textPart.put("text", text)
                        content.add(textPart)
                        item.set<JsonNode?>("content", content)
                        input.add(item)
                    }
                    if (toolCalls != null && toolCalls.isArray) {
                        for (tc in toolCalls) {
                            val funcCall: ObjectNode = MAPPER.createObjectNode()
                            funcCall.put("type", "function_call")
                            funcCall.put("call_id", tc.path("id").asText(""))
                            val func = tc.get("function")
                            if (func != null) {
                                funcCall.put("name", func.path("name").asText(""))
                                funcCall.put("arguments", func.path("arguments").asText("{}"))
                            }
                            input.add(funcCall)
                        }
                    }
                }
                "tool" -> {
                    val item: ObjectNode = MAPPER.createObjectNode()
                    item.put("type", "function_call_output")
                    item.put("call_id", msg.path("tool_call_id").asText(""))
                    val content = extractTextContent(msg.get("content"))
                    item.put("output", content)
                    input.add(item)
                }
            }
        }
        upstream.set<JsonNode?>("input", input)

        // Set instructions.
        var instr = instructions.toString()
        if (instr.isEmpty()) {
            instr = instructionsProvider.instructionsForModel(model)
        }
        upstream.put("instructions", instr)

        // Optional parameters.
        if (chatBody.has("temperature") && !chatBody.get("temperature").isNull) {
            upstream.set<JsonNode?>("temperature", chatBody.get("temperature"))
        }
        if (chatBody.has("top_p") && !chatBody.get("top_p").isNull) {
            upstream.set<JsonNode?>("top_p", chatBody.get("top_p"))
        }
        // max_completion_tokens (newer SDK) takes precedence over deprecated max_tokens.
        if (chatBody.has("max_completion_tokens") && !chatBody.get("max_completion_tokens").isNull) {
            upstream.put("max_output_tokens", chatBody.get("max_completion_tokens").asInt())
        } else if (chatBody.has("max_tokens") && !chatBody.get("max_tokens").isNull) {
            upstream.put("max_output_tokens", chatBody.get("max_tokens").asInt())
        }

        val tools: ArrayNode = MAPPER.createArrayNode()
        addModernTools(tools, chatBody.get("tools"))
        addLegacyFunctions(tools, chatBody.get("functions"))
        if (!tools.isEmpty) {
            upstream.set<JsonNode?>("tools", tools)
        }

        // Tool choice.
        if (chatBody.has("tool_choice") && !chatBody.get("tool_choice").isNull) {
            upstream.set<JsonNode?>("tool_choice", chatBody.get("tool_choice"))
        } else if (chatBody.has("function_call") && !chatBody.get("function_call").isNull) {
            setLegacyFunctionCallChoice(upstream, chatBody.get("function_call"))
        }

        // Structured output: chat `response_format` json_schema maps to Responses `text.format`.
        val responseFormat = chatBody.get("response_format")
        if (responseFormat != null && responseFormat.isObject
            && "json_schema" == responseFormat.path("type").asText()
        ) {
            val jsonSchema = responseFormat.get("json_schema")
            if (jsonSchema != null && jsonSchema.isObject) {
                val format: ObjectNode = MAPPER.createObjectNode()
                format.put("type", "json_schema")
                if (jsonSchema.hasNonNull("name")) {
                    format.set<JsonNode?>("name", jsonSchema.get("name"))
                }
                if (jsonSchema.hasNonNull("strict")) {
                    format.set<JsonNode?>("strict", jsonSchema.get("strict"))
                }
                if (jsonSchema.hasNonNull("schema")) {
                    format.set<JsonNode?>("schema", jsonSchema.get("schema"))
                }
                val text: ObjectNode = MAPPER.createObjectNode()
                text.set<JsonNode?>("format", format)
                upstream.set<JsonNode?>("text", text)
            }
        }

        // Reasoning effort. A tier baked into the model name (aliasReasoningEffort) is the
        // user's explicit choice and wins over a separately supplied reasoning_effort, which
        // for clients like Junie can be a stale per-model default.
        val requestedEffort = aliasReasoningEffort ?:
            if (chatBody.has("reasoning_effort") && !chatBody.get("reasoning_effort").isNull)
                chatBody.get("reasoning_effort").asText()
            else
                null
        if (requestedEffort != null) {
            val reasoning: ObjectNode = MAPPER.createObjectNode()
            reasoning.put("effort", modelAliasResolver.clampReasoningEffort(model, requestedEffort))
            upstream.set<JsonNode?>("reasoning", reasoning)
        }

        return upstream
    }

    private fun addModernTools(tools: ArrayNode, toolDefinitions: JsonNode?) {
        if (toolDefinitions == null || !toolDefinitions.isArray) {
            return
        }
        for (toolDef in toolDefinitions) {
            if ("function" != toolDef.path("type").asText()) continue
            val func = toolDef.get("function")
            if (func != null) {
                addFunctionTool(tools, func)
            }
        }
    }

    private fun addLegacyFunctions(tools: ArrayNode, functionDefinitions: JsonNode?) {
        if (functionDefinitions == null || !functionDefinitions.isArray) {
            return
        }
        for (functionDef in functionDefinitions) {
            addFunctionTool(tools, functionDef)
        }
    }

    @Suppress("RemoveExplicitTypeArguments")
    private fun addFunctionTool(tools: ArrayNode, func: JsonNode) {
        val name = func.path("name").asText("")
        if (name.isBlank()) {
            return
        }
        val tool: ObjectNode = MAPPER.createObjectNode()
        tool.put("type", "function")
        tool.put("name", name)
        if (func.has("description")) {
            tool.put("description", func.path("description").asText(""))
        }
        if (func.has("parameters")) {
            tool.set<JsonNode?>("parameters", func.get("parameters"))
        } else {
            val defaultParams: ObjectNode = MAPPER.createObjectNode()
            defaultParams.put("type", "object")
            defaultParams.set<JsonNode?>("properties", MAPPER.createObjectNode())
            defaultParams.put("additionalProperties", true)
            tool.set<JsonNode?>("parameters", defaultParams)
        }
        tools.add(tool)
    }

    private fun setLegacyFunctionCallChoice(upstream: ObjectNode, functionCall: JsonNode) {
        if (functionCall.isTextual) {
            val choice = functionCall.asText()
            if (!choice.isBlank()) {
                upstream.put("tool_choice", choice)
            }
            return
        }
        val name = functionCall.path("name").asText("")
        if (!name.isBlank()) {
            val toolChoice: ObjectNode = MAPPER.createObjectNode()
            toolChoice.put("type", "function")
            toolChoice.put("name", name)
            upstream.set<JsonNode?>("tool_choice", toolChoice)
        }
    }

    private fun extractTextContent(content: JsonNode?): String {
        if (content == null) return ""
        if (content.isTextual) return content.asText()
        if (content.isArray) {
            val sb = StringBuilder()
            for (part in content) {
                if (part.isObject && "text" == part.path("type").asText()) {
                    val text = part.path("text").asText("")
                    if (!text.isEmpty()) {
                        sb.append(text)
                    }
                }
            }
            return sb.toString()
        }
        return ""
    }

    private fun addContentParts(target: ArrayNode, content: JsonNode?) {
        if (content == null) return
        if (content.isTextual) {
            val part: ObjectNode = MAPPER.createObjectNode()
            part.put("type", "input_text")
            part.put("text", content.asText())
            target.add(part)
        } else if (content.isArray) {
            for (item in content) {
                if (item.isObject) {
                    val type = item.path("type").asText("")
                    if ("text" == type) {
                        val part: ObjectNode = MAPPER.createObjectNode()
                        part.put("type", "input_text")
                        part.put("text", item.path("text").asText(""))
                        target.add(part)
                    } else if ("image_url" == type) {
                        val part: ObjectNode = MAPPER.createObjectNode()
                        part.put("type", "input_image")
                        val imageUrl = item.get("image_url")
                        if (imageUrl != null && imageUrl.has("url")) {
                            part.put("image_url", imageUrl.path("url").asText(""))
                        }
                        if (imageUrl != null && imageUrl.has("detail")) {
                            part.put("detail", imageUrl.path("detail").asText(""))
                        }
                        target.add(part)
                    }
                }
            }
        }
    }
}
