package de.moritzf.proxy.server

import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.model.ModelAliasResolver
import de.moritzf.proxy.server.AccessLogFields.addResponseBytes
import de.moritzf.proxy.server.AccessLogFields.mode
import de.moritzf.proxy.server.AccessLogFields.responseBytes
import de.moritzf.proxy.server.AccessLogFields.upstreamStatus
import de.moritzf.proxy.server.JsonHelper.errorObject
import de.moritzf.proxy.server.JsonHelper.MAPPER
import de.moritzf.proxy.server.JsonHelper.setSseHeaders
import de.moritzf.proxy.server.JsonHelper.toErrorResponse
import de.moritzf.proxy.server.JsonHelper.toUsage
import de.moritzf.proxy.server.JunieCommandProtocolCompat.chatToolCall
import de.moritzf.proxy.server.JunieCommandProtocolCompat.fallbackToolName
import de.moritzf.proxy.server.JunieCommandProtocolCompat.formatUpdateMarkup
import de.moritzf.proxy.server.JunieCommandProtocolCompat.isJunieRequest
import de.moritzf.proxy.server.JunieCommandProtocolCompat.textFromToolArguments
import de.moritzf.proxy.server.JunieCommandProtocolCompat.wrapPlainText
import de.moritzf.proxy.server.JunieCommandProtocolCompat.wrapStreamingText
import de.moritzf.proxy.server.RequestValidator.parseJsonObject
import de.moritzf.proxy.server.RequestValidator.rejectMalformedJson
import de.moritzf.proxy.server.UpstreamRetry.withRetries
import de.moritzf.proxy.sse.ServerSentEvent
import de.moritzf.proxy.sse.SseCollector.collectCompletedResponse
import de.moritzf.proxy.sse.SseParser.iterateEvents
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.javalin.http.Context
import io.javalin.http.Handler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UncheckedIOException
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.UUID
import java.util.function.Consumer

class ChatCompletionsHandler(
    private val client: CodexHttpClient,
    private val config: ServerConfig,
    private val usageTracker: UsageTracker,
    private val requestLogger: RequestLogger,
    private val instructionsProvider: CodexInstructionsProvider,
) : Handler {
    private val modelAliasResolver = ModelAliasResolver()
    private val upstreamErrorMapper = UpstreamErrorMapper()

    @Throws(Exception::class)
    override fun handle(ctx: Context) {
        val requestId = if (shouldUseRequestContext()) requestId(ctx) else requestLogger.nextRequestId()
        val bodyStr = ctx.body()
        requestLogger.logInbound(requestId, ctx, bodyStr)
        val body = try {
            parseJsonObject(ctx, bodyStr)
        } catch (e: JsonProcessingException) {
            rejectMalformedJson(ctx, e)
            return
        }
        if (body == null) {
            return
        }

        val messagesNode = body.get("messages")
        if (messagesNode == null || !messagesNode.isArray) {
            toErrorResponse(ctx, "`messages` must be an array.")
            return
        }

        val wantsStream = body.path("stream").asBoolean(false)
        val junieCommandProtocol = isJunieRequest(body)
        val junieToolName = if (junieCommandProtocol) fallbackToolName(body) else null
        // Modern Junie declares native `tools` and validates that responses contain real
        // tool_calls — wrapping output in <COMMAND> text makes it reject the response.
        // The legacy <THOUGHT>/<COMMAND> text protocol only applies to requests without
        // modern tool definitions.
        val nativeToolProtocol = hasModernToolDefinitions(body)
        val junieTextProtocol = junieCommandProtocol && !nativeToolProtocol
        val junieNativeProtocol = junieCommandProtocol && nativeToolProtocol
        val junieNativeToolName = if (nativeToolProtocol) junieToolName else null
        val junieTextToolName = if (junieTextProtocol) junieToolName else null
        val legacyFunctionCallProtocol = usesLegacyFunctions(body)
        mode(ctx, if (wantsStream) "stream" else "sync")
        // When --models was specified, default to the first configured model.
        // ServerConfig.DEFAULT_MODEL is the last-resort fallback for when no models were
        // configured and auto-discovery failed — in that case no better default is available
        // without an extra ModelResolver call. Callers can always override via the "model" field.
        val configuredModels = config.models()
        val defaultModel = if (!configuredModels.isNullOrEmpty()) {
            configuredModels.first()
        } else {
            ServerConfig.DEFAULT_MODEL
        }
        val model = body.path("model").asText(defaultModel)
        val resolvedModel = modelAliasResolver.resolve(model)
        val upstreamModel = resolvedModel.model() ?: model

        // Build upstream Responses API request
        val upstreamBody = buildUpstreamBody(body, upstreamModel, resolvedModel.reasoningEffort())
        val promptCacheKey = if (config.forwardPromptCacheHeaders())
            upstreamBody.path("prompt_cache_key").asText(null)
        else
            null

        // Always stream upstream
        val upstream = withRetries(ctx.header("x-litellm-num-retries")) {
            sendUpstream(upstreamBody, requestId, promptCacheKey)
        }
        upstreamStatus(ctx, upstream.statusCode())
        ctx.header("x-litellm-model-id", upstreamModel)

        upstream.body().use { responseStream ->
            if (upstream.statusCode() !in 200..<300) {
                val rawBody = String(responseStream.readAllBytes(), StandardCharsets.UTF_8)
                val mapped = upstreamErrorMapper.map(upstream.statusCode(), rawBody)
                requestLogger.logUpstreamResponse(
                    requestId,
                    mapped.statusCode(),
                    responseHeaders(upstream),
                    mapped.body()
                )
                ctx.status(mapped.statusCode())
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE)
                responseBytes(ctx, mapped.body().toByteArray(StandardCharsets.UTF_8).size.toLong())
                ctx.result(mapped.body())
                return
            }
            if (wantsStream) {
                streamToClient(
                    ctx, responseStream, upstreamModel, junieTextToolName, junieNativeToolName,
                    junieNativeProtocol, body
                )
            } else {
                nonStreamToClient(
                    ctx, responseStream, upstreamModel, junieTextProtocol, junieTextToolName,
                    junieNativeToolName, junieNativeProtocol, legacyFunctionCallProtocol, body
                )
            }
        }
    }

    private fun buildUpstreamBody(chatBody: JsonNode, model: String, aliasReasoningEffort: String?): ObjectNode {
        val upstream: ObjectNode = MAPPER.createObjectNode()
        upstream.put("model", model)
        upstream.put("stream", true)
        upstream.put("store", config.store())

        // Convert messages to Responses API format
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

                    if (toolCalls != null && toolCalls.isArray()) {
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

        // Set instructions
        var instr = instructions.toString()
        if (instr.isEmpty()) {
            instr = instructionsProvider.instructionsForModel(model)
        }
        upstream.put("instructions", instr)

        // Optional parameters
        if (chatBody.has("temperature") && !chatBody.get("temperature").isNull()) {
            upstream.set<JsonNode?>("temperature", chatBody.get("temperature"))
        }
        if (chatBody.has("top_p") && !chatBody.get("top_p").isNull()) {
            upstream.set<JsonNode?>("top_p", chatBody.get("top_p"))
        }
        // max_completion_tokens (newer SDK) takes precedence over deprecated max_tokens
        if (chatBody.has("max_completion_tokens") && !chatBody.get("max_completion_tokens").isNull()) {
            upstream.put("max_output_tokens", chatBody.get("max_completion_tokens").asInt())
        } else if (chatBody.has("max_tokens") && !chatBody.get("max_tokens").isNull()) {
            upstream.put("max_output_tokens", chatBody.get("max_tokens").asInt())
        }

        val tools: ArrayNode = MAPPER.createArrayNode()
        addModernTools(tools, chatBody.get("tools"))
        addLegacyFunctions(tools, chatBody.get("functions"))
        if (!tools.isEmpty()) {
            upstream.set<JsonNode?>("tools", tools)
        }

        // Tool choice
        if (chatBody.has("tool_choice") && !chatBody.get("tool_choice").isNull()) {
            upstream.set<JsonNode?>("tool_choice", chatBody.get("tool_choice"))
        } else if (chatBody.has("function_call") && !chatBody.get("function_call").isNull()) {
            setLegacyFunctionCallChoice(upstream, chatBody.get("function_call"))
        }

        // Structured output: chat `response_format` json_schema maps to Responses `text.format`.
        val responseFormat = chatBody.get("response_format")
        if (responseFormat != null && responseFormat.isObject()
            && "json_schema" == responseFormat.path("type").asText()
        ) {
            val jsonSchema = responseFormat.get("json_schema")
            if (jsonSchema != null && jsonSchema.isObject()) {
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
        val requestedEffort = if (aliasReasoningEffort != null)
            aliasReasoningEffort
        else
            (if (chatBody.has("reasoning_effort") && !chatBody.get("reasoning_effort").isNull())
                chatBody.get("reasoning_effort").asText()
            else
                null)
        if (requestedEffort != null) {
            val reasoning: ObjectNode = MAPPER.createObjectNode()
            reasoning.put("effort", modelAliasResolver.clampReasoningEffort(model, requestedEffort))
            upstream.set<JsonNode?>("reasoning", reasoning)
        }

        return upstream
    }

    private fun addModernTools(tools: ArrayNode, toolDefinitions: JsonNode?) {
        if (toolDefinitions == null || !toolDefinitions.isArray()) {
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
        if (functionDefinitions == null || !functionDefinitions.isArray()) {
            return
        }
        for (functionDef in functionDefinitions) {
            addFunctionTool(tools, functionDef)
        }
    }

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
        if (functionCall.isTextual()) {
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

    @Throws(Exception::class)
    private fun sendUpstream(
        upstreamBody: ObjectNode,
        requestId: String,
        promptCacheKey: String?
    ): HttpResponse<InputStream> {
        val payload: String = MAPPER.writeValueAsString(upstreamBody)
        if (shouldUseRequestContext()) {
            return client.request(
                "/responses", "POST",
                payload,
                mapOf("Content-Type" to "application/json"),
                requestId,
                promptCacheKey
            )
        }
        return client.request(
            "/responses", "POST",
            payload,
            mapOf("Content-Type" to "application/json")
        )
    }

    private fun shouldUseRequestContext(): Boolean {
        return config.fullRequestLogging() || config.forwardPromptCacheHeaders()
    }

    private fun hasModernToolDefinitions(body: JsonNode): Boolean {
        val tools = body.get("tools")
        return tools != null && tools.isArray() && !tools.isEmpty()
    }

    private fun usesLegacyFunctions(body: JsonNode): Boolean {
        val functions = body.get("functions")
        if (functions == null || !functions.isArray() || functions.isEmpty()) {
            return false
        }
        val tools = body.get("tools")
        return tools == null || !tools.isArray() || tools.isEmpty()
    }

    private fun requestId(ctx: Context): String {
        var requestId = ctx.attribute<String>(AccessLogFields.REQUEST_ID)
        if (requestId.isNullOrBlank()) {
            requestId = requestLogger.nextRequestId()
            ctx.attribute(AccessLogFields.REQUEST_ID, requestId)
        }
        return requestId
    }

    @Throws(Exception::class)
    private fun nonStreamToClient(
        ctx: Context, upstreamBody: InputStream, model: String,
        junieTextProtocol: Boolean, junieTextToolName: String?,
        junieNativeToolName: String?, junieNativeProtocol: Boolean,
        legacyFunctionCallProtocol: Boolean, requestBody: JsonNode
    ) {
        val completedResponse = collectCompletedResponse(upstreamBody)

        val upstreamStatus = completedResponse.path("status").asText("")
        if ("failed" == upstreamStatus || "cancelled" == upstreamStatus) {
            val errorMessage = completedResponse.path("error").path("message")
                .asText("Upstream response $upstreamStatus.")
            requestLogger.logClientResponse(requestId(ctx), 502, errorMessage)
            toErrorResponse(ctx, errorMessage, 502, "upstream_error")
            return
        }

        val id = "chatcmpl_" + UUID.randomUUID()
        val created = System.currentTimeMillis() / 1000

        val result: ObjectNode = MAPPER.createObjectNode()
        result.put("id", id)
        result.put("object", "chat.completion")
        result.put("created", created)
        result.put("model", model)

        val choices: ArrayNode = MAPPER.createArrayNode()
        val choice: ObjectNode = MAPPER.createObjectNode()
        choice.put("index", 0)

        val message: ObjectNode = MAPPER.createObjectNode()
        message.put("role", "assistant")

        val textContent = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls: ArrayNode = MAPPER.createArrayNode()

        val output = completedResponse.get("output")
        if (output != null && output.isArray()) {
            for (item in output) {
                val type = item.path("type").asText("")
                when (type) {
                    "message" -> {
                        val content = item.get("content")
                        if (content != null && content.isArray()) {
                            for (part in content) {
                                if ("output_text" == part.path("type").asText()) {
                                    textContent.append(part.path("text").asText(""))
                                }
                            }
                        }
                    }

                    "function_call" -> {
                        val tc: ObjectNode = MAPPER.createObjectNode()
                        tc.put("id", item.path("call_id").asText(""))
                        tc.put("type", "function")
                        val func: ObjectNode = MAPPER.createObjectNode()
                        func.put("name", item.path("name").asText(""))
                        func.put("arguments", item.path("arguments").asText("{}"))
                        tc.set<JsonNode?>("function", func)
                        toolCalls.add(tc)
                    }

                    "reasoning" -> appendReasoningSummary(reasoningContent, item)
                }
            }
        }

        val collectedText: String = truncateAtStopSequence(textContent.toString(), stopSequences(requestBody))

        if (junieTextToolName != null) {
            var content = collectedText
            if (content.isBlank() && !toolCalls.isEmpty()) {
                content = toolCallText(toolCalls, junieTextToolName)
            }
            message.put("content", wrapStreamingText(junieTextToolName, content))
            toolCalls.removeAll()
        } else if (collectedText.isNotEmpty()) {
            var content = collectedText
            if (junieTextProtocol) {
                content = wrapPlainText(content)
            } else if (junieNativeProtocol) {
                // Junie shows this text verbatim as the step thought; reformat the
                // <UPDATE> plan markup it asked the model for into readable text.
                content = formatUpdateMarkup(content) ?: content
            }
            message.put("content", content)
        } else {
            message.putNull("content")
        }
        if (!reasoningContent.isEmpty()) {
            message.put("reasoning_content", reasoningContent.toString())
        }
        // Junie requires every assistant turn to contain a tool call. If the model
        // answered with plain text only, synthesize a call to the fallback tool
        // (submit/answer) carrying that text.
        if (junieNativeToolName != null && toolCalls.isEmpty()) {
            toolCalls.add(chatToolCall(junieNativeToolName, collectedText))
        }
        if (legacyFunctionCallProtocol && !message.has("function_call") && !toolCalls.isEmpty()) {
            message.set<JsonNode?>("function_call", toLegacyFunctionCall(toolCalls.get(0)))
        } else if (!toolCalls.isEmpty()) {
            message.set<JsonNode?>("tool_calls", toolCalls)
        }

        val hasFunctionCall = message.has("function_call")
        val status = completedResponse.path("status").asText("")
        val finishReason = when (status) {
            "completed" -> if (hasFunctionCall) "function_call" else if (toolCalls.isEmpty()) "stop" else "tool_calls"
            "incomplete" -> "length"
            "failed", "cancelled" -> "stop"
            else -> if (hasFunctionCall) "function_call" else if (toolCalls.isEmpty()) "stop" else "tool_calls"
        }

        applyStopFinishDetails(choice, message, stopSequences(requestBody))
        choice.set<JsonNode?>("message", message)
        choice.put("finish_reason", finishReason)
        choices.add(choice)
        result.set<JsonNode?>("choices", choices)

        val usageNode = completedResponse.get("usage")
        usageTracker.record(
            ctx.attribute<String?>("keyName"),
            if (usageNode != null) usageNode.path("input_tokens").asLong(0) else 0,
            if (usageNode != null) usageNode.path("output_tokens").asLong(0) else 0
        )
        result.set<JsonNode?>("usage", toUsage(usageNode))

        val responseBody: String = MAPPER.writeValueAsString(result)
        requestLogger.logClientResponse(requestId(ctx), 200, responseBody)
        ctx.status(200)
        ctx.contentType(JsonHelper.JSON_CONTENT_TYPE)
        ctx.result(responseBody)
    }

    private fun toolCallText(toolCalls: ArrayNode, preferredToolName: String): String {
        var fallback = ""
        for (toolCall in toolCalls) {
            val function = toolCall.path("function")
            val text = textFromToolArguments(
                function.path("name").asText(preferredToolName),
                function.path("arguments").asText("")
            )
            if (fallback.isBlank()) {
                fallback = text
            }
            if (preferredToolName == function.path("name").asText("")) {
                return text
            }
        }
        return fallback
    }

    private fun toLegacyFunctionCall(toolCall: JsonNode): ObjectNode {
        val function = toolCall.path("function")
        val legacyFunctionCall: ObjectNode = MAPPER.createObjectNode()
        legacyFunctionCall.put("name", function.path("name").asText(""))
        legacyFunctionCall.put("arguments", function.path("arguments").asText("{}"))
        return legacyFunctionCall
    }

    @Throws(Exception::class)
    private fun streamToClient(
        ctx: Context, upstreamBody: InputStream, model: String,
        junieTextToolName: String?, junieNativeToolName: String?,
        junieNativeProtocol: Boolean, requestBody: JsonNode
    ) {
        setSseHeaders(ctx)
        val os: OutputStream = ctx.res().outputStream

        val id = "chatcmpl_" + UUID.randomUUID()
        val created = System.currentTimeMillis() / 1000
        val toolIndexes: MutableMap<String, Int> = LinkedHashMap()
        val argsEmittedIndexes: MutableSet<Int> = HashSet()
        val junieStreamingTextFallback = junieTextToolName != null
        val junieTextBuffer = StringBuilder()
        val junieArgumentBuffer = StringBuilder()
        val doneSent = booleanArrayOf(false)
        val finishSent = booleanArrayOf(false)

        // Send initial role chunk
        writeSseChunk(ctx, os, createChunk(id, created, model, createAssistantRoleDelta(), null))

        try {
            iterateEvents(upstreamBody, Consumer { event: ServerSentEvent ->
                try {
                    val eventData = event.data()
                    if (eventData.isNullOrEmpty()) return@Consumer
                    if ("[DONE]" == eventData) {
                        // If upstream sends [DONE] without a response.completed event (e.g. on
                        // error mid-stream), emit a synthetic finish chunk so clients don't hang
                        // waiting for a non-null finish_reason.
                        if (!finishSent[0]) {
                            writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), "stop"))
                            finishSent[0] = true
                        }
                        val doneBytes = "data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8)
                        os.write(doneBytes)
                        addResponseBytes(ctx, doneBytes.size.toLong())
                        os.flush()
                        doneSent[0] = true
                        return@Consumer
                    }

                    val parsed: JsonNode? = MAPPER.readTree(eventData)
                    if (parsed == null || !parsed.isObject) return@Consumer

                    val eventType = parsed.path("type").asText(if (event.event() != null) event.event() else "")

                    when (eventType) {
                        "response.output_text.delta" -> {
                            val delta = parsed.path("delta").asText("")
                            if (delta.isNotEmpty()) {
                                // Junie protocols never get raw text deltas: the text protocol
                                // wraps the full text at completion, and the native tool protocol
                                // reformats the <UPDATE> plan markup, which needs the whole text.
                                if (junieStreamingTextFallback || junieNativeProtocol) {
                                    junieTextBuffer.append(delta)
                                } else {
                                    writeSseChunk(
                                        ctx, os, createChunk(
                                            id, created, model,
                                            createContentDelta(delta), null
                                        )
                                    )
                                }
                            }
                        }

                        "response.output_item.added" -> {
                            val item = parsed.get("item")
                            if (item != null && "function_call" == item.path("type").asText()) {
                                val callId = item.path("call_id").asText("")
                                val name = item.path("name").asText("")
                                val nextIndex = toolIndexes.values.distinct().size
                                toolIndexes[callId] = nextIndex
                                // Argument delta events reference the output item id ("fc_..."),
                                // not the call id ("call_..."); register both.
                                val itemId = item.path("id").asText("")
                                if (itemId.isNotEmpty()) {
                                    toolIndexes[itemId] = nextIndex
                                }

                                val tcArray: ArrayNode = MAPPER.createArrayNode()
                                val tc: ObjectNode = MAPPER.createObjectNode()
                                tc.put("index", nextIndex)
                                tc.put("id", callId)
                                tc.put("type", "function")
                                val func: ObjectNode = MAPPER.createObjectNode()
                                func.put("name", name)
                                func.put("arguments", "")
                                tc.set<JsonNode?>("function", func)
                                tcArray.add(tc)

                                if (!junieStreamingTextFallback) {
                                    writeSseChunk(
                                        ctx, os, createChunk(
                                            id, created, model,
                                            createToolCallsDelta(tcArray), null
                                        )
                                    )
                                }
                            }
                        }

                        "response.function_call_arguments.delta" -> {
                            val callId = parsed.path("call_id").asText(
                                parsed.path("item_id").asText("")
                            )
                            val argDelta = parsed.path("delta").asText("")
                            val index = toolIndexes[callId]
                            if (junieStreamingTextFallback && argDelta.isNotEmpty()) {
                                junieArgumentBuffer.append(argDelta)
                            } else if (index != null && argDelta.isNotEmpty()) {
                                argsEmittedIndexes.add(index)
                                val tcArray: ArrayNode = MAPPER.createArrayNode()
                                val tc: ObjectNode = MAPPER.createObjectNode()
                                tc.put("index", index)
                                val func: ObjectNode = MAPPER.createObjectNode()
                                func.put("arguments", argDelta)
                                tc.set<JsonNode?>("function", func)
                                tcArray.add(tc)

                                writeSseChunk(
                                    ctx, os, createChunk(
                                        id, created, model,
                                        createToolCallsDelta(tcArray), null
                                    )
                                )
                            }
                        }

                        "response.output_item.done" -> {
                            // Safety net: if no argument deltas were forwarded for this call
                            // (e.g. unexpected event ids), emit the complete arguments from the
                            // finished item so the client never sees a tool call without them.
                            val item = parsed.get("item")
                            if (item != null && "function_call" == item.path("type").asText()
                                && !junieStreamingTextFallback
                            ) {
                                val index = toolIndexes[item.path("call_id").asText("")]
                                val arguments = item.path("arguments").asText("")
                                if (index != null && arguments.isNotEmpty() && !argsEmittedIndexes.contains(index)) {
                                    argsEmittedIndexes.add(index)
                                    val tcArray: ArrayNode = MAPPER.createArrayNode()
                                    val tc: ObjectNode = MAPPER.createObjectNode()
                                    tc.put("index", index)
                                    val func: ObjectNode = MAPPER.createObjectNode()
                                    func.put("arguments", arguments)
                                    tc.set<JsonNode?>("function", func)
                                    tcArray.add(tc)

                                    writeSseChunk(
                                        ctx, os, createChunk(
                                            id, created, model,
                                            createToolCallsDelta(tcArray), null
                                        )
                                    )
                                }
                            }
                        }

                        "response.completed" -> {
                            val response = parsed.get("response")
                            val status = if (response != null) response.path("status").asText("") else ""
                            val fr: String?
                            var stopFinishDetails: ObjectNode? = null
                            if (junieStreamingTextFallback) {
                                val content: String = truncateAtStopSequence(
                                    junieFallbackContent(
                                        response, junieTextToolName,
                                        junieTextBuffer, junieArgumentBuffer
                                    ),
                                    stopSequences(requestBody)
                                )
                                var wrappedContent = wrapStreamingText(
                                    junieTextToolName, content
                                )
                                val cut: StopCut? =
                                    cutAtStopSequence(wrappedContent, stopSequences(requestBody))
                                if (cut != null) {
                                    wrappedContent = cut.content
                                    stopFinishDetails = finishDetails(cut.sequence)
                                }
                                requestLogger.logClientResponse(requestId(ctx), 200, wrappedContent)
                                writeSseChunk(
                                    ctx, os, createChunk(
                                        id, created, model,
                                        createContentDelta(wrappedContent), null
                                    )
                                )
                                fr =
                                    if ("completed" == status) "stop" else if ("incomplete" == status) "length" else "stop"
                            } else if (junieNativeProtocol) {
                                var text = completedOutputText(response)
                                if (text.isBlank()) {
                                    text = junieTextBuffer.toString()
                                }
                                text = truncateAtStopSequence(text, stopSequences(requestBody))
                                // Junie shows this text verbatim as the step thought; reformat
                                // the <UPDATE> plan markup into readable text before emitting it.
                                val content = formatUpdateMarkup(text)
                                if (!content.isNullOrBlank()) {
                                    writeSseChunk(
                                        ctx, os, createChunk(
                                            id, created, model,
                                            createContentDelta(content), null
                                        )
                                    )
                                }
                                if (junieNativeToolName != null && toolIndexes.isEmpty()
                                    && ("incomplete" != status)
                                ) {
                                    // Junie requires a tool call in every assistant turn; synthesize a
                                    // fallback tool call from the streamed text when the model sent none.
                                    val tc = chatToolCall(
                                        junieNativeToolName, text
                                    )
                                    tc.put("index", 0)
                                    val tcArray: ArrayNode = MAPPER.createArrayNode()
                                    tcArray.add(tc)
                                    writeSseChunk(
                                        ctx, os, createChunk(
                                            id, created, model,
                                            createToolCallsDelta(tcArray), null
                                        )
                                    )
                                    fr = "tool_calls"
                                } else {
                                    fr = when (status) {
                                        "completed" -> if (toolIndexes.isEmpty()) "stop" else "tool_calls"
                                        "incomplete" -> "length"
                                        else -> "stop"
                                    }
                                }
                            } else {
                                fr = when (status) {
                                    "completed" -> if (toolIndexes.isEmpty()) "stop" else "tool_calls"
                                    "incomplete" -> "length"
                                    else -> "stop"
                                }
                            }

                            // Finish chunk
                            val finishChunk = createChunk(id, created, model, createEmptyDelta(), fr)
                            if (stopFinishDetails != null) {
                                (finishChunk.get("choices").get(0) as ObjectNode)
                                    .set<JsonNode?>("finish_details", stopFinishDetails)
                            }
                            writeSseChunk(ctx, os, finishChunk)
                            finishSent[0] = true

                            // Usage is tracked internally always, but the usage chunk is only
                            // emitted when the client opted in — matching OpenAI/LiteLLM
                            // `stream_options.include_usage` behavior.
                            val usageNode = if (response != null) response.get("usage") else null
                            usageTracker.record(
                                ctx.attribute<String?>("keyName"),
                                if (usageNode != null) usageNode.path("input_tokens").asLong(0) else 0,
                                if (usageNode != null) usageNode.path("output_tokens").asLong(0) else 0
                            )
                            val includeUsage = requestBody.path("stream_options")
                                .path("include_usage").asBoolean(false)
                            if (includeUsage) {
                                val usageChunk: ObjectNode = MAPPER.createObjectNode()
                                usageChunk.put("id", id)
                                usageChunk.put("object", "chat.completion.chunk")
                                usageChunk.put("created", created)
                                usageChunk.put("model", model)
                                usageChunk.set<JsonNode?>("choices", MAPPER.createArrayNode())
                                usageChunk.set<JsonNode?>("usage", toUsage(usageNode))
                                writeSseChunk(ctx, os, usageChunk)
                            }
                        }

                        "response.failed", "response.cancelled" -> {
                            val response = parsed.get("response")
                            val errorMsg = if (response != null)
                                response.path("error").path("message").asText("Upstream response failed.")
                            else
                                "Upstream response failed."
                            // Emit a finish chunk with "stop" so the client stream terminates cleanly,
                            // then write an error SSE event with details.
                            if (!finishSent[0]) {
                                writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), "stop"))
                                finishSent[0] = true
                            }
                            val errPayload: ObjectNode = MAPPER.createObjectNode()
                            errPayload.set<JsonNode?>("error", errorObject(errorMsg, "upstream_error", "502"))
                            val errLine = "event: error\ndata: " + MAPPER.writeValueAsString(errPayload) + "\n\n"
                            val errorBytes = errLine.toByteArray(StandardCharsets.UTF_8)
                            os.write(errorBytes)
                            addResponseBytes(ctx, errorBytes.size.toLong())
                        }
                    }
                } catch (e: IOException) {
                    throw UncheckedIOException(e)
                } catch (e: Exception) {
                    LOG.warn("Error processing SSE event", e)
                    throw RuntimeException(e)
                }
            })
        } finally {
            // Guarantee a finish chunk + [DONE] are sent even if the upstream stream
            // ends abnormally (no [DONE] event and no response.completed).
            if (!doneSent[0]) {
                try {
                    if (!finishSent[0]) {
                        writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), "stop"))
                    }
                    val doneBytes = "data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8)
                    os.write(doneBytes)
                    addResponseBytes(ctx, doneBytes.size.toLong())
                } catch (_: Exception) {
                }
            }
            os.flush()
        }
    }

    private data class StopCut(val content: String, val sequence: String)

    /**
     * Standard OpenAI/LiteLLM semantics exclude the fired stop sequence from the
     * returned content. Junie restores it client-side when `finish_details` names the
     * sequence (its STOP_AFTER handling), so cutting here plus emitting finish_details
     * serves standard clients and Junie alike.
     */
    private fun applyStopFinishDetails(choice: ObjectNode, message: ObjectNode, stopSequences: List<String>) {
        val contentNode = message.get("content")
        if (contentNode == null || !contentNode.isTextual()) {
            return
        }
        val cut: StopCut? = cutAtStopSequence(contentNode.asText(), stopSequences)
        if (cut == null) {
            return
        }
        message.put("content", cut.content)
        choice.set<JsonNode?>("finish_details", finishDetails(cut.sequence))
    }

    private fun finishDetails(stopSequence: String): ObjectNode {
        val finishDetails: ObjectNode = MAPPER.createObjectNode()
        finishDetails.put("type", "stop")
        finishDetails.put("stop", stopSequence)
        return finishDetails
    }

    private fun completedOutputText(response: JsonNode?): String {
        val text = StringBuilder()
        val output = if (response != null) response.get("output") else null
        if (output == null || !output.isArray()) {
            return ""
        }
        for (item in output) {
            if ("message" != item.path("type").asText("")) {
                continue
            }
            val content = item.get("content")
            if (content == null || !content.isArray()) {
                continue
            }
            for (part in content) {
                if ("output_text" == part.path("type").asText("")) {
                    text.append(part.path("text").asText(""))
                }
            }
        }
        return text.toString()
    }

    private fun junieFallbackContent(
        response: JsonNode?, toolName: String,
        textBuffer: StringBuilder, argumentBuffer: StringBuilder
    ): String {
        var content = completedOutputText(response)
        if (content.isBlank()) {
            content = completedToolArgumentText(response, toolName)
        }
        if (content.isBlank()) {
            content = textBuffer.toString()
        }
        if (content.isBlank()) {
            content = textFromToolArguments(toolName, argumentBuffer.toString())
        }
        return content
    }

    private fun completedToolArgumentText(response: JsonNode?, toolName: String): String {
        val output = if (response != null) response.get("output") else null
        if (output == null || !output.isArray()) {
            return ""
        }
        var fallback = ""
        for (item in output) {
            if ("function_call" != item.path("type").asText("")) {
                continue
            }
            val text = textFromToolArguments(
                item.path("name").asText(toolName),
                item.path("arguments").asText("")
            )
            if (fallback.isBlank()) {
                fallback = text
            }
            if (toolName == item.path("name").asText("")) {
                return text
            }
        }
        return fallback
    }

    private fun createChunk(
        id: String, created: Long, model: String,
        delta: ObjectNode, finishReason: String?
    ): ObjectNode {
        val chunk: ObjectNode = MAPPER.createObjectNode()
        chunk.put("id", id)
        chunk.put("object", "chat.completion.chunk")
        chunk.put("created", created)
        chunk.put("model", model)

        val choices: ArrayNode = MAPPER.createArrayNode()
        val choice: ObjectNode = MAPPER.createObjectNode()
        choice.put("index", 0)
        choice.set<JsonNode?>("delta", delta)
        if (finishReason != null) {
            choice.put("finish_reason", finishReason)
        } else {
            choice.putNull("finish_reason")
        }
        choices.add(choice)
        chunk.set<JsonNode?>("choices", choices)

        return chunk
    }

    private fun createAssistantRoleDelta(): ObjectNode {
        val delta: ObjectNode = MAPPER.createObjectNode()
        delta.put("role", "assistant")
        return delta
    }

    private fun createContentDelta(content: String): ObjectNode {
        val delta: ObjectNode = MAPPER.createObjectNode()
        delta.put("content", content)
        return delta
    }

    private fun createToolCallsDelta(toolCalls: ArrayNode): ObjectNode {
        val delta: ObjectNode = MAPPER.createObjectNode()
        delta.set<JsonNode?>("tool_calls", toolCalls)
        return delta
    }

    private fun createEmptyDelta(): ObjectNode {
        return MAPPER.createObjectNode()
    }

    @Throws(Exception::class)
    private fun writeSseChunk(ctx: Context, os: OutputStream, data: JsonNode) {
        val line = "data: " + MAPPER.writeValueAsString(data) + "\n\n"
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        os.write(bytes)
        addResponseBytes(ctx, bytes.size.toLong())
        os.flush()
    }

    private fun extractTextContent(content: JsonNode?): String {
        if (content == null) return ""
        if (content.isTextual()) return content.asText()
        if (content.isArray()) {
            val sb = StringBuilder()
            for (part in content) {
                if (part.isObject() && "text" == part.path("type").asText()) {
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
        if (content.isTextual()) {
            val part: ObjectNode = MAPPER.createObjectNode()
            part.put("type", "input_text")
            part.put("text", content.asText())
            target.add(part)
        } else if (content.isArray()) {
            for (item in content) {
                if (item.isObject()) {
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

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(ChatCompletionsHandler::class.java)

        private fun <T> responseHeaders(response: HttpResponse<T>): Map<String, List<String>> {
            return response.headers()?.map() ?: emptyMap()
        }

        private fun stopSequences(body: JsonNode?): List<String> {
            val stop = if (body != null) body.get("stop") else null
            if (stop == null) {
                return emptyList()
            }
            if (stop.isTextual() && !stop.asText().isEmpty()) {
                return listOf(stop.asText())
            }
            if (stop.isArray()) {
                val sequences: MutableList<String> = ArrayList<String>()
                for (sequence in stop) {
                    if (sequence.isTextual() && !sequence.asText().isEmpty()) {
                        sequences.add(sequence.asText())
                    }
                }
                return sequences
            }
            return emptyList()
        }

        /**
         * The upstream Responses API has no `stop` parameter, so emulate it client-side.
         * This inclusive variant keeps the sequence in the text; it runs before the Junie
         * protocol wrappers, which need to see the complete <COMMAND>...</COMMAND> block.
         * The final exclusive cut happens in [.applyStopFinishDetails].
         */
        private fun truncateAtStopSequence(text: String, stopSequences: List<String>): String {
            val cut: StopCut? = cutAtStopSequence(text, stopSequences)
            return if (cut != null) cut.content + cut.sequence else text
        }

        /** Returns the text before the first stop sequence plus the fired sequence, or null when none fired.  */
        private fun cutAtStopSequence(text: String, stopSequences: List<String>): StopCut? {
            var earliestStart = -1
            var firedSequence: String? = null
            for (sequence in stopSequences) {
                val start = text.indexOf(sequence)
                if (start >= 0 && (earliestStart < 0 || start < earliestStart)) {
                    earliestStart = start
                    firedSequence = sequence
                }
            }
            return if (firedSequence != null) StopCut(text.substring(0, earliestStart), firedSequence) else null
        }

        private fun appendReasoningSummary(target: StringBuilder, reasoningItem: JsonNode) {
            val summary = reasoningItem.get("summary")
            if (summary == null || !summary.isArray()) {
                return
            }
            for (part in summary) {
                val text = part.path("text").asText("")
                if (text.isBlank()) {
                    continue
                }
                if (!target.isEmpty()) {
                    target.append('\n')
                }
                target.append(text)
            }
        }
    }
}
