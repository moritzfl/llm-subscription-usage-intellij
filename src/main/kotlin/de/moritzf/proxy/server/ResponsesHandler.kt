package de.moritzf.proxy.server
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.model.CodexInstructionsProvider
import de.moritzf.proxy.model.ModelAliasResolver
import de.moritzf.proxy.sse.SseCollector
import de.moritzf.proxy.state.ResponsesState
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.proxy.usage.UsageTracker
import io.javalin.http.Context
import io.javalin.http.Handler
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
class ResponsesHandler : Handler {
    private val client: CodexHttpClient
    private val config: ServerConfig
    private val usageTracker: UsageTracker
    private val requestLogger: RequestLogger
    private val instructionsProvider: CodexInstructionsProvider
    private val requestSanitizer = ResponsesRequestSanitizer()
    private val modelAliasResolver = ModelAliasResolver()
    private val upstreamErrorMapper = UpstreamErrorMapper()
    private val replayStates: MutableMap<String, ResponsesState> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ResponsesState>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResponsesState>?): Boolean {
                return size > MAX_REPLAY_NAMESPACES
            }
        },
    )
    constructor(
        client: CodexHttpClient,
        config: ServerConfig,
        usageTracker: UsageTracker,
        requestLogger: RequestLogger,
        instructionsProvider: CodexInstructionsProvider,
    ) {
        this.client = client
        this.config = config
        this.usageTracker = usageTracker
        this.requestLogger = requestLogger
        this.instructionsProvider = instructionsProvider
    }

    override fun handle(ctx: Context) {
        create(ctx)
    }

    fun create(ctx: Context) {
        val requestId = if (shouldUseRequestContext()) requestId(ctx) else requestLogger.nextRequestId()
        val body = RequestValidator.parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        val wantsStream = body.booleanPath("stream", false)
        AccessLogFields.mode(ctx, if (wantsStream) "stream" else "sync")
        // The replay cache emulates previous_response_id/item_reference for store=false.
        // It is opt-in (clients like Junie always inline full history), so when disabled we
        // forward the body as-is and skip the second SSE parse it would otherwise require.
        val state = if (config.enableResponsesReplayCache) replayStateFor(ctx) else null
        val mutableBody = MutableJsonObject(body)
        val expanded = state?.expandRequestBody(mutableBody) ?: mutableBody
        val expandedJson = expanded.build()
        // Normalize body.
        val normalized = requestSanitizer.sanitize(normalizeBody(expanded), config.store)
        val promptCacheKey = if (config.forwardPromptCacheHeaders) {
            (normalized.get("prompt_cache_key") as? JsonPrimitive)?.content
        } else {
            null
        }
        // Forward to upstream.
        val upstream = UpstreamRetry.withRetries(ctx.header("x-litellm-num-retries")) {
            sendUpstream(normalized, requestId, promptCacheKey)
        }
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode())
        ctx.header("x-litellm-model-id", (normalized.get("model") as? JsonPrimitive)?.content ?: "")
        if (upstream.statusCode() !in 200..<300) {
            upstreamErrorMapper.writeResponse(ctx, requestLogger, requestId, upstream)
            return
        }
        if (wantsStream) {
            // Stream SSE directly to client. The recorder runs only when the replay cache is
            // enabled; otherwise the bytes pass straight through without a second parse.
            JsonHelper.setSseHeaders(ctx)
            val recorder = if (state != null) StreamingCompletionRecorder(ctx, state, expandedJson) else null
            upstream.body().use { stream ->
                ctx.res().outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead == -1) {
                            break
                        }
                        output.write(buffer, 0, bytesRead)
                        AccessLogFields.addResponseBytes(ctx, bytesRead.toLong())
                        recorder?.accept(buffer, bytesRead)
                        output.flush()
                    }
                }
            }
            recorder?.finish()
        } else {
            // Collect completed response from SSE.
            upstream.body().use { stream ->
                var completed = SseCollector.collectCompletedResponse(stream)
                val status = completed.stringPath("status", "")
                if (status == "failed" || status == "cancelled") {
                    val errorMessage = completed.pathOrNull("error").stringPath("message", "Upstream response $status.")
                    requestLogger.logClientResponse(requestId, 502, errorMessage)
                    JsonHelper.toErrorResponse(ctx, errorMessage, 502, "upstream_error")
                    return
                }
                if (JunieCommandProtocolCompat.isJunieRequest(expandedJson)) {
                    completed = if (JunieCommandProtocolCompat.hasFunctionCallOutput(completed)) {
                        // Native tool protocol: Junie shows message text verbatim as the step
                        // thought, so reformat the <UPDATE> plan markup into readable text.
                        JunieCommandProtocolCompat.formatUpdateMarkupInResponse(completed) ?: completed
                    } else {
                        val declaredToolName = JunieCommandProtocolCompat.declaredFallbackToolName(expandedJson)
                        if (declaredToolName != null) {
                            JunieCommandProtocolCompat.toToolResponse(completed, declaredToolName) ?: completed
                        } else if (!JunieCommandProtocolCompat.hasToolDefinitions(expandedJson)) {
                            // Tool-less Junie requests are the <THOUGHT>/<COMMAND> text protocol;
                            // a synthetic call to an undeclared tool would not parse as a command.
                            JunieCommandProtocolCompat.wrapCompletedResponse(completed) ?: completed
                        } else {
                            // Tools declared but no submit/answer: native protocol without a
                            // fallback tool; still clean the plan markup in the text.
                            JunieCommandProtocolCompat.formatUpdateMarkupInResponse(completed) ?: completed
                        }
                    }
                }
                recordUsage(ctx, completed["usage"])
                // Best-effort same-process replay cache only; nothing is persisted locally.
                state?.rememberResponse(completed, expandedJson)
                JsonHelper.toJsonResponse(ctx, completed)
            }
        }
    }
    private fun normalizeBody(body: MutableJsonObject): MutableJsonObject {
        val normalized = body.deepCopy()
        normalized.put("stream", true)
        val requestedModel = (normalized.get("model") as? JsonPrimitive)?.content ?: ServerConfig.DEFAULT_MODEL
        val resolvedModel = modelAliasResolver.resolve(requestedModel)
        if (!resolvedModel.model.isNullOrBlank()) {
            normalized.put("model", resolvedModel.model)
        }
        hoistSystemMessagesIntoInstructions(normalized)
        if (!normalized.has("instructions") || !normalized.get("instructions").isTextual()) {
            normalized.put(
                "instructions",
                instructionsProvider.instructionsForModel((normalized.get("model") as? JsonPrimitive)?.content ?: ""),
            )
        }
        if (normalized.get("store") == null) {
            normalized.put("store", config.store)
        }
        val aliasEffort = resolvedModel.reasoningEffort
        val reasoningNode = normalized.get("reasoning")
        val reasoning = if (reasoningNode is JsonObject) {
            MutableJsonObject(reasoningNode)
        } else {
            createObjectNode()
        }
        // A tier baked into the model name (aliasEffort) is the user's explicit choice and
        // wins over a separately supplied reasoning.effort.
        val requestedEffort = aliasEffort ?: (reasoning.get("effort") as? JsonPrimitive)?.content
        val clampedEffort = modelAliasResolver.clampReasoningEffort(
            (normalized.get("model") as? JsonPrimitive)?.content ?: "",
            requestedEffort,
        )
        if (clampedEffort != null) {
            reasoning.put("effort", clampedEffort)
            normalized.set("reasoning", reasoning)
        }
        return normalized
    }
    /**
     * The Codex backend rejects requests containing system-role input items
     * ("System messages are not allowed"). Clients such as Junie's Responses client
     * always send their system prompt as an input message, so move that text into
     * the `instructions` field instead.
     */
    private fun hoistSystemMessagesIntoInstructions(normalized: MutableJsonObject) {
        val input = normalized.get("input")
        if (input !is JsonArray) {
            return
        }
        val systemTexts = StringBuilder()
        val filteredInput = createArrayNode()
        for (item in input) {
            if (item is JsonObject && isSystemMessageItem(item)) {
                val text = JunieCommandProtocolCompat.messageText(item["content"])
                if (text.isNotEmpty()) {
                    if (systemTexts.isNotEmpty()) {
                        systemTexts.append('\n')
                    }
                    systemTexts.append(text)
                }
                continue
            }
            filteredInput.add(item)
        }
        if (systemTexts.isEmpty()) {
            return
        }
        normalized.set("input", filteredInput)
        val instructions = normalized.get("instructions")
        val existingInstructions = if (instructions.isTextual()) {
            instructions.text.trim()
        } else {
            ""
        }
        val combined = if (existingInstructions.isEmpty()) {
            systemTexts.toString()
        } else {
            existingInstructions + "\n" + systemTexts
        }
        normalized.put("instructions", combined)
    }
    private fun sendUpstream(
        normalized: MutableJsonObject,
        requestId: String,
        promptCacheKey: String?
    ): HttpResponse<InputStream> {
        val payload = JsonHelper.encodeToString(normalized.build())
        if (shouldUseRequestContext()) {
            return client.request(
                "/responses",
                "POST",
                payload,
                mapOf("Content-Type" to "application/json"),
                requestId,
                promptCacheKey,
            )
        }
        return client.request(
            "/responses",
            "POST",
            payload,
            mapOf("Content-Type" to "application/json"),
        )
    }
    private fun shouldUseRequestContext(): Boolean {
        return config.fullRequestLogging || config.forwardPromptCacheHeaders
    }
    private fun requestId(ctx: Context): String {
        var requestId = ctx.attribute<String>(AccessLogFields.REQUEST_ID)
        if (requestId.isNullOrBlank()) {
            requestId = requestLogger.nextRequestId()
            ctx.attribute(AccessLogFields.REQUEST_ID, requestId)
        }
        return requestId
    }
    private fun recordStreamingCompletion(
        ctx: Context,
        eventType: String?,
        data: String?,
        state: ResponsesState,
        expandedRequest: JsonElement,
    ): Boolean {
        try {
            if (data.isNullOrEmpty() || data == "[DONE]") {
                return false
            }
            val parsed = JsonHelper.parseToJsonElementOrNull(data)
            if (parsed !is JsonObject) {
                return false
            }
            val parsedEventType = parsed.stringPath("type", eventType ?: "")
            if (parsedEventType != "response.completed") {
                return false
            }
            val response = parsed["response"]
            if (response is JsonObject) {
                recordUsage(ctx, response["usage"])
                state.rememberResponse(response, expandedRequest)
                return true
            }
        } catch (_: Exception) {
            // Streaming payloads have already been forwarded; replay/usage bookkeeping is best-effort.
        }
        return false
    }
    private fun recordUsage(ctx: Context, usageNode: JsonElement?) {
        usageTracker.record(
            ctx.attribute("keyName"),
            usageNode.longPath("input_tokens", 0L),
            usageNode.longPath("output_tokens", 0L),
        )
    }
    private fun replayStateFor(ctx: Context): ResponsesState {
        val isAdmin = ctx.attribute<Boolean>("isAdmin") == true
        val keyFingerprint = ctx.attribute<String>("keyFingerprint")
        val adminKeyFingerprint = ctx.attribute<String>("adminKeyFingerprint")
        val keyName = ctx.attribute<String>("keyName")
        val namespace = if (isAdmin && adminKeyFingerprint != null) {
            "admin-fp:$adminKeyFingerprint"
        } else if (keyFingerprint != null) {
            "key-fp:$keyFingerprint"
        } else if (keyName != null) {
            "key:$keyName"
        } else if (isAdmin) {
            "admin"
        } else {
            "open"
        }
        synchronized(replayStates) {
            return replayStates.computeIfAbsent(namespace) { ResponsesState() }
        }
    }
    private inner class StreamingCompletionRecorder(
        private val ctx: Context,
        private val state: ResponsesState,
        private val expandedRequest: JsonElement,
    ) {
        private val lineBuffer = ByteArrayOutputStream()
        private val dataLines = ArrayList<String>()
        private var eventType: String? = null
        private var recorded = false
        private var bookkeepingDisabled = false
        fun accept(buffer: ByteArray, length: Int) {
            if (bookkeepingDisabled) {
                return
            }
            for (index in 0 until length) {
                val byte = buffer[index]
                if (byte == '\n'.code.toByte()) {
                    acceptLine(lineBuffer.toString(StandardCharsets.UTF_8))
                    lineBuffer.reset()
                } else {
                    lineBuffer.write(byte.toInt())
                    if (lineBuffer.size() > MAX_SSE_BOOKKEEPING_LINE_BYTES) {
                        disableBookkeeping()
                        return
                    }
                }
            }
        }
        fun finish() {
            if (bookkeepingDisabled) {
                return
            }
            if (lineBuffer.size() > 0) {
                acceptLine(lineBuffer.toString(StandardCharsets.UTF_8))
                lineBuffer.reset()
            }
            if (eventType != null || dataLines.isNotEmpty()) {
                dispatchEvent()
            }
        }
        private fun acceptLine(rawLine: String) {
            var line = rawLine
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length - 1)
            }
            if (line.isEmpty()) {
                if (eventType != null || dataLines.isNotEmpty()) {
                    dispatchEvent()
                }
                return
            }
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim()
            } else if (line.startsWith("data:")) {
                var value = line.substring(5)
                if (value.isNotEmpty() && value[0] == ' ') {
                    value = value.substring(1)
                }
                dataLines.add(value)
            }
        }
        private fun dispatchEvent() {
            if (!recorded) {
                val data = if (dataLines.isEmpty()) null else dataLines.joinToString("\n")
                recorded = recordStreamingCompletion(ctx, eventType, data, state, expandedRequest)
            }
            eventType = null
            dataLines.clear()
        }
        private fun disableBookkeeping() {
            bookkeepingDisabled = true
            recorded = true
            lineBuffer.reset()
            dataLines.clear()
            eventType = null
        }
    }
    companion object {
        private const val MAX_REPLAY_NAMESPACES = 512
        private const val MAX_SSE_BOOKKEEPING_LINE_BYTES = 64 * 1024
        private fun isSystemMessageItem(item: JsonObject): Boolean {
            val role = item.stringPath("role", "")
            if (role != "system" && role != "developer") {
                return false
            }
            val type = item.stringPath("type", "message")
            return type == "message"
        }
    }
}
