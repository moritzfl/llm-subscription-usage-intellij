package de.moritzf.proxy.server
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.model.ModelAliasResolver
import de.moritzf.proxy.server.AccessLogFields.addResponseBytes
import de.moritzf.proxy.server.AccessLogFields.mode
import de.moritzf.proxy.server.AccessLogFields.upstreamStatus
import de.moritzf.proxy.server.JsonHelper.errorObject
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
import de.moritzf.proxy.server.RequestValidator.parseLoggedJsonObject
import de.moritzf.proxy.server.UpstreamRetry.withRetries
import de.moritzf.proxy.sse.SseCollector.collectCompletedResponse
import de.moritzf.proxy.sse.SseParser.iterateEvents
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
class ChatCompletionsHandler(
    private val client: CodexHttpClient,
    private val config: ServerConfig,
    private val usageTracker: UsageTracker,
    private val requestLogger: RequestLogger,
    instructionsProvider: CodexInstructionsProvider,
) {
    private val modelAliasResolver = ModelAliasResolver()
    private val upstreamErrorMapper = UpstreamErrorMapper()
    private val requestMapper = ChatCompletionsRequestMapper(config.store, instructionsProvider, modelAliasResolver)
    suspend fun handle(ctx: ProxyCall) {
        val requestId = if (shouldUseRequestContext()) requestId(ctx) else requestLogger.nextRequestId()
        val body = parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        val messagesNode = body["messages"]
        if (messagesNode !is JsonArray) {
            toErrorResponse(ctx, "`messages` must be an array.")
            return
        }
        val wantsStream = body.booleanPath("stream", false)
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
        val configuredModels = config.models
        val defaultModel = if (!configuredModels.isNullOrEmpty()) {
            configuredModels.first()
        } else {
            ServerConfig.DEFAULT_MODEL
        }
        val model = body.stringPath("model", defaultModel)
        val resolvedModel = modelAliasResolver.resolve(model)
        val upstreamModel = resolvedModel.model ?: model
        // Build upstream Responses API request
        val upstreamBody = requestMapper.build(body, upstreamModel, resolvedModel.reasoningEffort)
        val promptCacheKey = if (config.forwardPromptCacheHeaders)
            upstreamBody.pathOrNull("prompt_cache_key").textOrNull
        else
            null
        // Always stream upstream
        val upstream = withContext(Dispatchers.IO) {
            withRetries(ctx.header("x-litellm-num-retries")) {
                sendUpstream(upstreamBody, requestId, promptCacheKey)
            }
        }
        upstreamStatus(ctx, upstream.statusCode())
        ctx.responseHeader("x-litellm-model-id", upstreamModel)
        if (upstream.statusCode() !in 200..<300) {
            upstreamErrorMapper.writeResponse(ctx, requestLogger, requestId, upstream)
            return
        }
        upstream.body().use { responseStream ->
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
    private fun sendUpstream(
        upstreamBody: MutableJsonObject,
        requestId: String,
        promptCacheKey: String?
    ): HttpResponse<InputStream> {
        val payload: String = JsonHelper.encodeToString(upstreamBody.build())
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
        return config.fullRequestLogging || config.forwardPromptCacheHeaders
    }
    private fun hasModernToolDefinitions(body: JsonObject): Boolean {
        val tools = body["tools"]
        return tools is JsonArray && tools.isNotEmpty()
    }
    private fun usesLegacyFunctions(body: JsonObject): Boolean {
        val functions = body["functions"]
        if (functions !is JsonArray || functions.isEmpty()) {
            return false
        }
        val tools = body["tools"]
        return tools !is JsonArray || tools.isEmpty()
    }
    private fun requestId(ctx: ProxyCall): String {
        var requestId = ctx.getAttribute(AccessLogFields.REQUEST_ID)
        if (requestId.isNullOrBlank()) {
            requestId = requestLogger.nextRequestId()
            ctx.setAttribute(AccessLogFields.REQUEST_ID, requestId)
        }
        return requestId
    }
    private suspend fun nonStreamToClient(
        ctx: ProxyCall, upstreamBody: InputStream, model: String,
        junieTextProtocol: Boolean, junieTextToolName: String?,
        junieNativeToolName: String?, junieNativeProtocol: Boolean,
        legacyFunctionCallProtocol: Boolean, requestBody: JsonObject
    ) {
        val completedResponse = collectCompletedResponse(upstreamBody)
        val upstreamStatus = completedResponse.stringPath("status", "")
        if ("failed" == upstreamStatus || "cancelled" == upstreamStatus) {
            val errorMessage = completedResponse.pathOrNull("error")
                .stringPath("message", "Upstream response $upstreamStatus.")
            requestLogger.logClientResponse(requestId(ctx), 502, errorMessage)
            toErrorResponse(ctx, errorMessage, 502, "upstream_error")
            return
        }
        val id = "chatcmpl_" + UUID.randomUUID()
        val created = System.currentTimeMillis() / 1000
        val result = createResponseEnvelope(id, created, model, "chat.completion")
        val choices = createArrayNode()
        val choice = createObjectNode()
        choice.put("index", 0)
        val message = createObjectNode()
        message.put("role", "assistant")
        val textContent = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = createArrayNode()
        val output = completedResponse["output"]
        if (output is JsonArray) {
            for (itemElement in output) {
                val item = itemElement as? JsonObject ?: continue
                val type = item.stringPath("type", "")
                when (type) {
                    "message" -> {
                        val content = item["content"]
                        if (content is JsonArray) {
                            for (partElement in content) {
                                val part = partElement as? JsonObject ?: continue
                                if ("output_text" == part.stringPath("type", "")) {
                                    textContent.append(part.stringPath("text", ""))
                                }
                            }
                        }
                    }
                    "function_call" -> {
                        val tc = createObjectNode()
                        tc.put("id", item.stringPath("call_id", ""))
                        tc.put("type", "function")
                        val func = createObjectNode()
                        func.put("name", item.stringPath("name", ""))
                        func.put("arguments", item.stringPath("arguments", "{}"))
                        tc.set("function", func)
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
            val legacyFunctionCall = toLegacyFunctionCall(toolCalls.get(0))
            if (legacyFunctionCall != null) {
                message.set("function_call", legacyFunctionCall)
            }
        } else if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls)
        }
        val hasFunctionCall = message.has("function_call")
        val status = completedResponse.stringPath("status", "")
        val finishReason = when (status) {
            "completed" -> if (hasFunctionCall) "function_call" else if (toolCalls.isEmpty()) "stop" else "tool_calls"
            "incomplete" -> "length"
            "failed", "cancelled" -> "stop"
            else -> if (hasFunctionCall) "function_call" else if (toolCalls.isEmpty()) "stop" else "tool_calls"
        }
        applyStopFinishDetails(choice, message, stopSequences(requestBody))
        choice.set("message", message)
        choice.put("finish_reason", finishReason)
        choices.add(choice)
        result.set("choices", choices)
        val usageNode = completedResponse["usage"]
        usageTracker.record(
            ctx.getAttribute(ProxyCallAttributes.KEY_NAME),
            usageNode.longPath("input_tokens", 0),
            usageNode.longPath("output_tokens", 0)
        )
        result.set("usage", toUsage(usageNode))
        val responseBody: String = JsonHelper.encodeToString(result.build())
        requestLogger.logClientResponse(requestId(ctx), 200, responseBody)
        ctx.setStatus(200)
        ctx.handled = true
        ctx.call.respondText(responseBody, ContentType.Application.Json, HttpStatusCode.OK)
    }
    private fun toolCallText(toolCalls: MutableJsonArray, preferredToolName: String): String {
        return preferredToolArgumentText(
            toolCalls.build(),
            { true },
            { it.pathOrNull("function").stringPath("name", preferredToolName) },
            { preferredToolName == it.pathOrNull("function").stringPath("name", "") },
            { it.pathOrNull("function").stringPath("arguments", "") },
        )
    }
    private fun toLegacyFunctionCall(toolCall: JsonElement?): MutableJsonObject? {
        val function = toolCall.pathOrNull("function")
        val legacyFunctionCall = createObjectNode()
        legacyFunctionCall.put("name", function.stringPath("name", ""))
        legacyFunctionCall.put("arguments", function.stringPath("arguments", "{}"))
        return legacyFunctionCall
    }

    private suspend fun streamToClient(
        ctx: ProxyCall, upstreamBody: InputStream, model: String,
        junieTextToolName: String?, junieNativeToolName: String?,
        junieNativeProtocol: Boolean, requestBody: JsonObject
    ) {
        setSseHeaders(ctx)
        ctx.handled = true
        ctx.call.respondOutputStream(ContentType.parse(JsonHelper.SSE_CONTENT_TYPE), HttpStatusCode.OK) {
            val os = this
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
                iterateEvents(upstreamBody) events@{ event ->
                try {
                    val eventData = event.data()
                    if (eventData.isNullOrEmpty()) return@events
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
                        return@events
                    }
                    val parsed = JsonHelper.parseToJsonElementOrNull(eventData) as? JsonObject ?: return@events
                    val eventType = parsed.stringPath("type", event.event() ?: "")
                    when (eventType) {
                        "response.output_text.delta" -> {
                            val delta = parsed.stringPath("delta", "")
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
                            val item = parsed["item"] as? JsonObject
                            if (item != null && "function_call" == item.stringPath("type", "")) {
                                val callId = item.stringPath("call_id", "")
                                val name = item.stringPath("name", "")
                                val nextIndex = toolIndexes.values.distinct().size
                                toolIndexes[callId] = nextIndex
                                // Argument delta events reference the output item id ("fc_..."),
                                // not the call id ("call_..."); register both.
                                val itemId = item.stringPath("id", "")
                                if (itemId.isNotEmpty()) {
                                    toolIndexes[itemId] = nextIndex
                                }
                                val tcArray = createArrayNode()
                                val tc = createObjectNode()
                                tc.put("index", nextIndex)
                                tc.put("id", callId)
                                tc.put("type", "function")
                                val func = createObjectNode()
                                func.put("name", name)
                                func.put("arguments", "")
                                tc.set("function", func)
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
                            val callId = parsed.stringPath(
                                "call_id",
                                parsed.stringPath("item_id", "")
                            )
                            val argDelta = parsed.stringPath("delta", "")
                            val index = toolIndexes[callId]
                            if (junieStreamingTextFallback && argDelta.isNotEmpty()) {
                                junieArgumentBuffer.append(argDelta)
                            } else if (index != null && argDelta.isNotEmpty()) {
                                argsEmittedIndexes.add(index)
                                writeToolArgumentsDelta(ctx, os, id, created, model, index, argDelta)
                            }
                        }
                        "response.output_item.done" -> {
                            // Safety net: if no argument deltas were forwarded for this call
                            // (e.g. unexpected event ids), emit the complete arguments from the
                            // finished item so the client never sees a tool call without them.
                            val item = parsed["item"] as? JsonObject
                            if (item != null && "function_call" == item.stringPath("type", "")
                                && !junieStreamingTextFallback
                            ) {
                                val index = toolIndexes[item.stringPath("call_id", "")]
                                val arguments = item.stringPath("arguments", "")
                                if (index != null && arguments.isNotEmpty() && !argsEmittedIndexes.contains(index)) {
                                    argsEmittedIndexes.add(index)
                                    writeToolArgumentsDelta(ctx, os, id, created, model, index, arguments)
                                }
                            }
                        }
                        "response.completed" -> {
                            val response = parsed["response"] as? JsonObject
                            val status = response.stringPath("status", "")
                            val fr: String?
                            var stopFinishDetails: MutableJsonObject? = null
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
                                    val tcArray = createArrayNode()
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
                                addFinishDetailsToFirstChoice(finishChunk, stopFinishDetails)
                            }
                            writeSseChunk(ctx, os, finishChunk)
                            finishSent[0] = true
                            // Usage is tracked internally always, but the usage chunk is only
                            // emitted when the client opted in — matching OpenAI/LiteLLM
                            // `stream_options.include_usage` behavior.
                            val usageNode = response?.get("usage")
                            usageTracker.record(
                                ctx.getAttribute(ProxyCallAttributes.KEY_NAME),
                                usageNode.longPath("input_tokens", 0),
                                usageNode.longPath("output_tokens", 0)
                            )
                            val includeUsage = requestBody.pathOrNull("stream_options")
                                .booleanPath("include_usage", false)
                            if (includeUsage) {
                                writeSseChunk(ctx, os, createUsageChunk(id, created, model, usageNode))
                            }
                        }
                        "response.failed", "response.cancelled" -> {
                            val response = parsed["response"] as? JsonObject
                            val errorMsg = response.pathOrNull("error")
                                .stringPath("message", "Upstream response failed.")
                            // Emit a finish chunk with "stop" so the client stream terminates cleanly,
                            // then write an error SSE event with details.
                            if (!finishSent[0]) {
                                writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), "stop"))
                                finishSent[0] = true
                            }
                            val errPayload = createObjectNode()
                            errPayload.set("error", errorObject(errorMsg, "upstream_error", "502"))
                            val errLine = "event: error\ndata: " + JsonHelper.encodeToString(errPayload.build()) + "\n\n"
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
            }
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
    }
    private data class StopCut(val content: String, val sequence: String)
    private fun addFinishDetailsToFirstChoice(chunk: MutableJsonObject, finishDetails: MutableJsonObject) {
        val choices = chunk.get("choices") as? JsonArray ?: return
        val firstChoice = choices.getOrNull(0) as? JsonObject ?: return
        val updatedChoice = MutableJsonObject(firstChoice)
        updatedChoice.set("finish_details", finishDetails)
        val updatedChoices = createArrayNode()
        choices.forEachIndexed { index, choice ->
            updatedChoices.add(if (index == 0) updatedChoice.build() else choice)
        }
        chunk.set("choices", updatedChoices)
    }
    /**
     * Standard OpenAI/LiteLLM semantics exclude the fired stop sequence from the
     * returned content. Junie restores it client-side when `finish_details` names the
     * sequence (its STOP_AFTER handling), so cutting here plus emitting finish_details
     * serves standard clients and Junie alike.
     */
    private fun applyStopFinishDetails(choice: MutableJsonObject, message: MutableJsonObject, stopSequences: List<String>) {
        val contentNode = message.get("content")
        if (!contentNode.isTextual()) {
            return
        }
        val cut: StopCut = cutAtStopSequence(contentNode.text, stopSequences) ?: return
        message.put("content", cut.content)
        choice.set("finish_details", finishDetails(cut.sequence))
    }
    private fun finishDetails(stopSequence: String): MutableJsonObject {
        val finishDetails = createObjectNode()
        finishDetails.put("type", "stop")
        finishDetails.put("stop", stopSequence)
        return finishDetails
    }
    private fun completedOutputText(response: JsonObject?): String {
        val text = StringBuilder()
        val output = response?.get("output")
        if (output !is JsonArray) {
            return ""
        }
        for (itemElement in output) {
            val item = itemElement as? JsonObject ?: continue
            if ("message" != item.stringPath("type", "")) {
                continue
            }
            val content = item["content"]
            if (content !is JsonArray) {
                continue
            }
            for (partElement in content) {
                val part = partElement as? JsonObject ?: continue
                if ("output_text" == part.stringPath("type", "")) {
                    text.append(part.stringPath("text", ""))
                }
            }
        }
        return text.toString()
    }
    private fun junieFallbackContent(
        response: JsonObject?, toolName: String,
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
    private fun completedToolArgumentText(response: JsonObject?, toolName: String): String {
        val output = response?.get("output")
        if (output !is JsonArray) {
            return ""
        }
        return preferredToolArgumentText(
            output,
            { "function_call" == it.stringPath("type", "") },
            { it.stringPath("name", toolName) },
            { toolName == it.stringPath("name", "") },
            { it.stringPath("arguments", "") },
        )
    }

    private fun preferredToolArgumentText(
        items: Iterable<JsonElement>,
        include: (JsonElement) -> Boolean,
        name: (JsonElement) -> String,
        isPreferred: (JsonElement) -> Boolean,
        arguments: (JsonElement) -> String,
    ): String {
        var fallback = ""
        for (item in items) {
            if (!include(item)) {
                continue
            }
            val text = textFromToolArguments(
                name(item),
                arguments(item),
            )
            if (fallback.isBlank()) {
                fallback = text
            }
            if (isPreferred(item)) {
                return text
            }
        }
        return fallback
    }
    private fun createChunk(
        id: String, created: Long, model: String,
        delta: MutableJsonObject, finishReason: String?
    ): MutableJsonObject {
        val chunk = createResponseEnvelope(id, created, model, "chat.completion.chunk")
        val choices = createArrayNode()
        val choice = createObjectNode()
        choice.put("index", 0)
        choice.set("delta", delta)
        if (finishReason != null) {
            choice.put("finish_reason", finishReason)
        } else {
            choice.putNull("finish_reason")
        }
        choices.add(choice)
        chunk.set("choices", choices)
        return chunk
    }
    private fun createAssistantRoleDelta(): MutableJsonObject {
        val delta = createObjectNode()
        delta.put("role", "assistant")
        return delta
    }
    private fun createContentDelta(content: String): MutableJsonObject {
        val delta = createObjectNode()
        delta.put("content", content)
        return delta
    }
    private fun createToolCallsDelta(toolCalls: MutableJsonArray): MutableJsonObject {
        val delta = createObjectNode()
        delta.set("tool_calls", toolCalls)
        return delta
    }
    private fun writeToolArgumentsDelta(
        ctx: ProxyCall,
        os: OutputStream,
        id: String,
        created: Long,
        model: String,
        index: Int,
        arguments: String,
    ) {
        val tcArray = createArrayNode()
        val tc = createObjectNode()
        tc.put("index", index)
        val func = createObjectNode()
        func.put("arguments", arguments)
        tc.set("function", func)
        tcArray.add(tc)
        writeSseChunk(ctx, os, createChunk(id, created, model, createToolCallsDelta(tcArray), null))
    }
    private fun createUsageChunk(id: String, created: Long, model: String, usageNode: JsonElement?): MutableJsonObject {
        val usageChunk = createResponseEnvelope(id, created, model, "chat.completion.chunk")
        usageChunk.set("choices", createArrayNode())
        usageChunk.set("usage", toUsage(usageNode))
        return usageChunk
    }
    private fun createResponseEnvelope(id: String, created: Long, model: String, objectType: String): MutableJsonObject {
        val response = createObjectNode()
        response.put("id", id)
        response.put("object", objectType)
        response.put("created", created)
        response.put("model", model)
        return response
    }
    private fun createEmptyDelta(): MutableJsonObject {
        return createObjectNode()
    }
    private fun writeSseChunk(ctx: ProxyCall, os: OutputStream, data: MutableJsonObject) {
        val line = "data: " + JsonHelper.encodeToString(data.build()) + "\n\n"
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        os.write(bytes)
        addResponseBytes(ctx, bytes.size.toLong())
        os.flush()
    }
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(ChatCompletionsHandler::class.java)
        private fun stopSequences(body: JsonObject?): List<String> {
            val stop = body?.get("stop") ?: return emptyList()
            if (stop.isTextual() && stop.text.isNotEmpty()) {
                return listOf(stop.text)
            }
            if (stop is JsonArray) {
                val sequences: MutableList<String> = ArrayList<String>()
                for (sequence in stop) {
                    if (sequence.isTextual() && sequence.text.isNotEmpty()) {
                        sequences.add(sequence.text)
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
                if (start == -1) {
                    continue
                }
                if (earliestStart == -1 || start < earliestStart) {
                    earliestStart = start
                    firedSequence = sequence
                }
            }
            return if (firedSequence != null) StopCut(text.substring(0, earliestStart), firedSequence) else null
        }
        private fun appendReasoningSummary(target: StringBuilder, reasoningItem: JsonObject) {
            val summary = reasoningItem["summary"]
            if (summary !is JsonArray) {
                return
            }
            for (partElement in summary) {
                val part = partElement as? JsonObject ?: continue
                val text = part.stringPath("text", "")
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
