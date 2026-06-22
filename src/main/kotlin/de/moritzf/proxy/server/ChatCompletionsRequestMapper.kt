package de.moritzf.proxy.server

import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.model.ModelAliasResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal class ChatCompletionsRequestMapper(
    private val store: Boolean,
    private val instructionsProvider: CodexInstructionsProvider,
    private val modelAliasResolver: ModelAliasResolver,
) {
    fun build(chatBody: JsonObject, model: String, aliasReasoningEffort: String?): MutableJsonObject {
        val upstream = createObjectNode()
        upstream.put("model", model)
        upstream.put("stream", true)
        upstream.put("store", store)

        // Convert messages to Responses API format.
        val input = createArrayNode()
        val instructions = StringBuilder()
        val messages = chatBody["messages"] as? JsonArray ?: JsonArray(emptyList())
        for (messageElement in messages) {
            val msg = messageElement as? JsonObject ?: continue
            val role = msg.stringPath("role", "")
            when (role) {
                "system", "developer" -> {
                    val text = extractTextContent(msg["content"])
                    if (text.isNotEmpty()) {
                        if (instructions.isNotEmpty()) instructions.append("\n")
                        instructions.append(text)
                    }
                }
                "user" -> {
                    val item = createObjectNode()
                    item.put("type", "message")
                    item.put("role", "user")
                    val content = createArrayNode()
                    addContentParts(content, msg["content"])
                    item.set("content", content)
                    input.add(item)
                }
                "assistant" -> {
                    val text = extractTextContent(msg["content"])
                    val toolCalls = msg["tool_calls"]
                    if (text.isNotEmpty()) {
                        val item = createObjectNode()
                        item.put("type", "message")
                        item.put("role", "assistant")
                        val content = createArrayNode()
                        val textPart = createObjectNode()
                        textPart.put("type", "output_text")
                        textPart.put("text", text)
                        content.add(textPart)
                        item.set("content", content)
                        input.add(item)
                    }
                    if (toolCalls is JsonArray) {
                        for (toolCallElement in toolCalls) {
                            val tc = toolCallElement as? JsonObject ?: continue
                            val funcCall = createObjectNode()
                            funcCall.put("type", "function_call")
                            funcCall.put("call_id", tc.stringPath("id", ""))
                            val func = tc["function"] as? JsonObject
                            if (func != null) {
                                funcCall.put("name", func.stringPath("name", ""))
                                funcCall.put("arguments", func.stringPath("arguments", "{}"))
                            }
                            input.add(funcCall)
                        }
                    }
                }
                "tool" -> {
                    val item = createObjectNode()
                    item.put("type", "function_call_output")
                    item.put("call_id", msg.stringPath("tool_call_id", ""))
                    val content = extractTextContent(msg["content"])
                    item.put("output", content)
                    input.add(item)
                }
            }
        }
        upstream.set("input", input)

        // Set instructions.
        var instr = instructions.toString()
        if (instr.isEmpty()) {
            instr = instructionsProvider.instructionsForModel(model)
        }
        upstream.put("instructions", instr)

        // Optional parameters.
        chatBody["temperature"]?.takeUnless { it is JsonNull }?.let { upstream.set("temperature", it) }
        chatBody["top_p"]?.takeUnless { it is JsonNull }?.let { upstream.set("top_p", it) }
        // max_completion_tokens (newer SDK) takes precedence over deprecated max_tokens.
        val maxCompletionTokens = (chatBody["max_completion_tokens"] as? JsonPrimitive)?.intOrNull
        val maxTokens = (chatBody["max_tokens"] as? JsonPrimitive)?.intOrNull
        when {
            maxCompletionTokens != null -> upstream.put("max_output_tokens", maxCompletionTokens)
            maxTokens != null -> upstream.put("max_output_tokens", maxTokens)
        }

        val tools = createArrayNode()
        addModernTools(tools, chatBody["tools"])
        addLegacyFunctions(tools, chatBody["functions"])
        if (!tools.isEmpty()) {
            upstream.set("tools", tools)
        }

        // Tool choice.
        chatBody["tool_choice"]?.takeUnless { it is JsonNull }?.let {
            setToolChoice(upstream, it)
        } ?: chatBody["function_call"]?.takeUnless { it is JsonNull }?.let {
            setLegacyFunctionCallChoice(upstream, it)
        }

        // Structured output: chat `response_format` json_schema maps to Responses `text.format`.
        val responseFormat = chatBody["response_format"] as? JsonObject
        if (responseFormat != null && responseFormat.stringPath("type") == "json_schema") {
            val jsonSchema = responseFormat["json_schema"] as? JsonObject
            if (jsonSchema != null) {
                val format = createObjectNode()
                format.put("type", "json_schema")
                if (jsonSchema.hasNonNull("name")) {
                    format.set("name", jsonSchema["name"]!!)
                }
                if (jsonSchema.hasNonNull("strict")) {
                    format.set("strict", jsonSchema["strict"]!!)
                }
                if (jsonSchema.hasNonNull("schema")) {
                    format.set("schema", jsonSchema["schema"]!!)
                }
                val text = createObjectNode()
                text.set("format", format)
                upstream.set("text", text)
            }
        }

        // Reasoning effort. A tier baked into the model name (aliasReasoningEffort) is the
        // user's explicit choice and wins over a separately supplied reasoning_effort, which
        // for clients like Junie can be a stale per-model default.
        val requestedEffort = aliasReasoningEffort ?:
            chatBody["reasoning_effort"]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.content }
        if (requestedEffort != null) {
            val reasoning = createObjectNode()
            reasoning.put("effort", modelAliasResolver.clampReasoningEffort(model, requestedEffort))
            upstream.set("reasoning", reasoning)
        }

        return upstream
    }

    private fun addModernTools(tools: MutableJsonArray, toolDefinitions: JsonElement?) {
        val definitions = toolDefinitions as? JsonArray ?: return
        for (toolDefElement in definitions) {
            val toolDef = toolDefElement as? JsonObject ?: continue
            if (toolDef.stringPath("type") != "function") continue
            val func = toolDef["function"] as? JsonObject
            if (func != null) {
                addFunctionTool(tools, func)
            }
        }
    }

    private fun addLegacyFunctions(tools: MutableJsonArray, functionDefinitions: JsonElement?) {
        val definitions = functionDefinitions as? JsonArray ?: return
        for (functionDefElement in definitions) {
            val functionDef = functionDefElement as? JsonObject ?: continue
            addFunctionTool(tools, functionDef)
        }
    }

    private fun addFunctionTool(tools: MutableJsonArray, func: JsonObject) {
        val name = func.stringPath("name", "")
        if (name.isBlank()) {
            return
        }
        val tool = createObjectNode()
        tool.put("type", "function")
        tool.put("name", name)
        if (func.containsKey("description")) {
            tool.put("description", func.stringPath("description", ""))
        }
        if (func.containsKey("parameters")) {
            tool.set("parameters", func["parameters"]!!)
        } else {
            val defaultParams = createObjectNode()
            defaultParams.put("type", "object")
            defaultParams.set("properties", createObjectNode())
            defaultParams.put("additionalProperties", true)
            tool.set("parameters", defaultParams)
        }
        tools.add(tool)
    }

    private fun setLegacyFunctionCallChoice(upstream: MutableJsonObject, functionCall: JsonElement) {
        if (functionCall.isTextual()) {
            val choice = functionCall.text
            if (choice.isNotBlank()) {
                upstream.put("tool_choice", choice)
            }
            return
        }
        val functionCallObject = functionCall as? JsonObject ?: return
        val name = functionCallObject.stringPath("name", "")
        if (name.isNotBlank()) {
            val toolChoice = createObjectNode()
            toolChoice.put("type", "function")
            toolChoice.put("name", name)
            upstream.set("tool_choice", toolChoice)
        }
    }

    private fun setToolChoice(upstream: MutableJsonObject, toolChoice: JsonElement) {
        if (toolChoice.isTextual()) {
            val choice = toolChoice.text
            if (choice.isNotBlank()) {
                upstream.put("tool_choice", choice)
            }
            return
        }
        val toolChoiceObject = toolChoice as? JsonObject ?: return
        if (toolChoiceObject.stringPath("type") == "function") {
            val function = toolChoiceObject["function"] as? JsonObject
            val name = function?.stringPath("name", "") ?: toolChoiceObject.stringPath("name", "")
            if (name.isNotBlank()) {
                val upstreamChoice = createObjectNode()
                upstreamChoice.put("type", "function")
                upstreamChoice.put("name", name)
                upstream.set("tool_choice", upstreamChoice)
                return
            }
        }
        upstream.set("tool_choice", toolChoice)
    }

    private fun extractTextContent(content: JsonElement?): String {
        if (content == null) return ""
        if (content.isTextual()) return content.text
        if (content is JsonArray) {
            val sb = StringBuilder()
            for (partElement in content) {
                val part = partElement as? JsonObject ?: continue
                if (part.stringPath("type") == "text") {
                    val text = part.stringPath("text", "")
                    if (text.isNotEmpty()) {
                        sb.append(text)
                    }
                }
            }
            return sb.toString()
        }
        return ""
    }

    private fun addContentParts(target: MutableJsonArray, content: JsonElement?) {
        if (content == null) return
        if (content.isTextual()) {
            val part = createObjectNode()
            part.put("type", "input_text")
            part.put("text", content.text)
            target.add(part)
        } else if (content is JsonArray) {
            for (itemElement in content) {
                val item = itemElement as? JsonObject ?: continue
                val type = item.stringPath("type", "")
                if (type == "text") {
                    val part = createObjectNode()
                    part.put("type", "input_text")
                    part.put("text", item.stringPath("text", ""))
                    target.add(part)
                } else if (type == "image_url") {
                    val part = createObjectNode()
                    part.put("type", "input_image")
                    val imageUrl = item["image_url"] as? JsonObject
                    if (imageUrl != null && imageUrl.containsKey("url")) {
                        part.put("image_url", imageUrl.stringPath("url", ""))
                    }
                    if (imageUrl != null && imageUrl.containsKey("detail")) {
                        part.put("detail", imageUrl.stringPath("detail", ""))
                    }
                    target.add(part)
                }
            }
        }
    }
}
