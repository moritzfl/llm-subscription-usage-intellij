package de.moritzf.proxy.server

import de.moritzf.proxy.fim.ChatFimPromptBuilder
import de.moritzf.proxy.fim.CompletionSanitizer
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.fim.CompletionsGuard
import de.moritzf.proxy.fim.CompletionsRequest
import de.moritzf.proxy.fim.CompletionsStrategy
import de.moritzf.proxy.fim.FimContext
import de.moritzf.proxy.fim.FimModels
import de.moritzf.proxy.fim.FimPromptParser
import de.moritzf.proxy.fim.GuardDecision
import de.moritzf.proxy.fim.StreamingCompletionSanitizer
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CompletionsHandler(
    private val catalog: () -> SubscriptionModelCatalog,
    private val config: () -> CompletionsConfig,
    private val requestLogger: RequestLogger,
    private val host: String,
    private val port: Int,
    private val localApiKey: () -> String?,
    private val guard: CompletionsGuard = CompletionsGuard(),
    private val httpClient: HttpClient = HTTP_CLIENT,
) {
    suspend fun handle(ctx: ProxyCall) {
        val cfg = config()
        if (!cfg.enabled) {
            JsonHelper.toErrorResponse(ctx, "FIM completions are disabled.", 404, "not_found_error")
            return
        }
        AccessLogFields.mode(ctx, if (ctx.header(HttpHeaders.Accept)?.contains("text/event-stream") == true) "stream" else "proxy")
        val requestId = ctx.getAttribute(AccessLogFields.REQUEST_ID) ?: requestLogger.nextRequestId()
        val body = RequestValidator.parseLoggedJsonObject(ctx, requestLogger, requestId) ?: return
        val parsed = CompletionsRequest.parse(body)
        val selectedId = cfg.modelLocalId.trim()
        if (selectedId.isEmpty()) {
            JsonHelper.toErrorResponse(ctx, "No FIM completion model is configured.", 400, "invalid_request_error")
            return
        }
        val requested = parsed.model.ifBlank { cfg.aliasId }
        if (!cfg.acceptsModel(requested)) {
            JsonHelper.toErrorResponse(ctx, "Unknown proxy model: $requested", 400, "invalid_request_error")
            return
        }
        val models = catalog()
        val model = resolveSelectedModel(models, selectedId) ?: run {
            JsonHelper.toErrorResponse(ctx, "Unknown proxy model: $selectedId", 400, "invalid_request_error")
            return
        }
        if (!FimModels.isEligible(model)) {
            JsonHelper.toErrorResponse(ctx, "Model ${model.localId} cannot be used for FIM completions.", 400, "invalid_request_error")
            return
        }
        val job = coroutineContext[Job]
        val key = ctx.getAttribute(ProxyCallAttributes.KEY_FINGERPRINT) ?: "local"
        val fim = CompletionsGuard.budget(FimPromptParser.parse(parsed.prompt, parsed.suffix), cfg.maxPromptChars)
        val maxTokens = CompletionsGuard.clampMaxTokens(parsed.maxTokens, cfg)
        val temperature = (parsed.temperature ?: DEFAULT_TEMPERATURE).coerceIn(0.0, MAX_TEMPERATURE)
        val completionId = completionId(requestId)
        val created = System.currentTimeMillis() / 1000L
        val advertised = advertisedModel(cfg, parsed)
        when (
            val decision = guard.tryStart(
                key = key,
                config = cfg,
                job = job,
                fingerprint = fim.fingerprint(),
                coalesce = cfg.strategy == CompletionsStrategy.CHAT_FIM,
            )
        ) {
            is GuardDecision.Skip -> {
                LOG.debug("Skipping FIM completion: {}", decision.reason)
                writeEmptyCompletion(ctx, parsed.stream, advertised, requestId)
                return
            }
            is GuardDecision.Join -> {
                val text = try {
                    withTimeout(cfg.timeoutMillis) { decision.result.await() }
                } catch (_: TimeoutCancellationException) {
                    ""
                }
                writeCompletion(ctx, parsed.stream, advertised, requestId, text)
                return
            }
            GuardDecision.Allow -> Unit
        }
        try {
            withTimeout(cfg.timeoutMillis) {
                if (cfg.strategy == CompletionsStrategy.CHAT_FIM) {
                    handleChatFim(
                        ctx = ctx,
                        cfg = cfg,
                        model = model,
                        parsed = parsed,
                        fim = fim,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        completionId = completionId,
                        created = created,
                        advertised = advertised,
                        requestId = requestId,
                        key = key,
                        producerJob = job,
                    )
                } else {
                    handleNative(ctx, models, model, parsed, fim, maxTokens, requestId)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            LOG.debug("FIM completion timed out for {}", model.localId)
            if (!ctx.handled) writeEmptyCompletion(ctx, parsed.stream, advertised, requestId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            if (exception.isClientDisconnect()) throw exception
            LOG.debug("FIM completion failed for {}", model.localId, exception)
            if (!ctx.handled) {
                if (parsed.stream) {
                    writeEmptyCompletion(ctx, true, advertised, requestId)
                } else {
                    JsonHelper.toErrorResponse(ctx, exception.message ?: "FIM completion failed.", 502, "upstream_error")
                }
            }
        } finally {
            guard.finish(key, job)
        }
    }

    private suspend fun handleChatFim(
        ctx: ProxyCall,
        cfg: CompletionsConfig,
        model: SubscriptionProxyModel,
        parsed: CompletionsRequest,
        fim: FimContext,
        maxTokens: Int,
        temperature: Double,
        completionId: String,
        created: Long,
        advertised: String,
        requestId: String,
        key: String,
        producerJob: Job?,
    ) {
        val chatBody = chatBody(
            model.localId,
            parsed,
            fim,
            maxTokens,
            temperature,
            priorityTier = cfg.priorityTier && FimModels.supportsPriorityTier(model),
        )
        val payload = JsonHelper.encodeToString(chatBody)
        val apiKey = localApiKey()?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            JsonHelper.toErrorResponse(ctx, "Subscription proxy local API key is missing", 401, "auth_error")
            return
        }
        val request = HttpRequest.newBuilder(URI.create("http://$host:$port/v1/chat/completions"))
            .timeout(Duration.ofMillis(cfg.timeoutMillis))
            .header(HttpHeaders.Authorization, "Bearer $apiKey")
            .header(HttpHeaders.ContentType, JsonHelper.JSON_CONTENT_TYPE)
            .header(HttpHeaders.Accept, if (parsed.stream) JsonHelper.SSE_CONTENT_TYPE else JsonHelper.JSON_CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build()
        val upstream = sendAsync(request)
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode())
        if (upstream.statusCode() == 429) {
            guard.noteQuotaError(key)
            upstream.body().close()
            writeEmptyCompletion(ctx, parsed.stream, advertised, requestId)
            return
        }
        if (upstream.statusCode() !in 200..<300) {
            val errorBody = upstream.body().use { JsonHelper.readUtf8Body(it) }
            if (parsed.stream) {
                writeEmptyCompletion(ctx, true, advertised, requestId)
            } else {
                ctx.setStatus(upstream.statusCode())
                ctx.call.respondText(
                    errorBody,
                    ContentType.Application.Json,
                    HttpStatusCode.fromValue(upstream.statusCode()),
                )
                ctx.handled = true
            }
            return
        }
        guard.noteSuccess(key)
        val stops = CompletionSanitizer.effectiveStops(fim.prefix, parsed.stop, fim.languageHint)
        val sanitizer = StreamingCompletionSanitizer(
            prefix = fim.prefix,
            suffix = fim.suffix,
            stop = stops,
            languageHint = fim.languageHint,
        )
        val upstreamStream = isEventStream(upstream)
        if (parsed.stream) {
            val text = if (upstreamStream) {
                writeChatStream(ctx, upstream, sanitizer, completionId, created, advertised)
            } else {
                val raw = upstream.body().use { JsonHelper.readUtf8Body(it) }
                val assembled = sanitizer.push(chatMessageContent(raw)) + sanitizer.finish()
                writeCompletion(ctx, true, advertised, requestId, assembled)
                assembled
            }
            guard.publish(key, producerJob, text)
        } else {
            val text = if (upstreamStream) {
                collectChatStream(upstream, sanitizer)
            } else {
                val raw = upstream.body().use { JsonHelper.readUtf8Body(it) }
                sanitizer.push(chatMessageContent(raw)) + sanitizer.finish()
            }
            guard.publish(key, producerJob, text)
            JsonHelper.toJsonResponse(ctx, textCompletionJson(completionId, created, advertised, text))
        }
    }

    private suspend fun handleNative(
        ctx: ProxyCall,
        catalog: SubscriptionModelCatalog,
        model: SubscriptionProxyModel,
        parsed: CompletionsRequest,
        fim: FimContext,
        maxTokens: Int,
        requestId: String,
    ) {
        val provider = catalog.providerFor(model) ?: run {
            JsonHelper.toErrorResponse(ctx, "Provider for ${model.localId} is not configured.", 503, "configuration_error")
            return
        }
        provider.handle(
            ctx,
            SubscriptionProxyRequest(
                route = SubscriptionProxyRoute.COMPLETIONS,
                requestId = requestId,
                model = model,
                body = nativeBody(model.localId, parsed, fim, maxTokens),
            ),
        )
    }

    private fun collectChatStream(
        upstream: HttpResponse<InputStream>,
        sanitizer: StreamingCompletionSanitizer,
    ): String {
        val assembled = StringBuilder()
        upstream.body().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("data:")) continue
                val data = line.substringAfter("data:").trim()
                if (data == "[DONE]") break
                val delta = chatDeltaContent(data)
                if (delta != null) assembled.append(sanitizer.push(delta))
            }
        }
        assembled.append(sanitizer.finish())
        return assembled.toString()
    }

    private suspend fun writeChatStream(
        ctx: ProxyCall,
        upstream: HttpResponse<InputStream>,
        sanitizer: StreamingCompletionSanitizer,
        id: String,
        created: Long,
        model: String,
    ): String {
        val assembled = StringBuilder()
        JsonHelper.setSseHeaders(ctx)
        ctx.setStatus(200)
        ctx.call.respondOutputStream(ContentType.parse(JsonHelper.SSE_CONTENT_TYPE), HttpStatusCode.OK) {
            upstream.body().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("data:")) {
                        val data = line.substringAfter("data:").trim()
                        if (data == "[DONE]") {
                            assembled.append(writeFinish(ctx, this, sanitizer, id, created, model))
                            return@use
                        }
                        val delta = chatDeltaContent(data)
                        if (delta != null) {
                            val extra = sanitizer.push(delta)
                            if (extra.isNotEmpty()) {
                                assembled.append(extra)
                                writeSseData(ctx, this, textCompletionChunk(id, created, model, extra, null))
                            }
                        }
                    }
                }
                assembled.append(writeFinish(ctx, this, sanitizer, id, created, model))
            }
        }
        ctx.handled = true
        return assembled.toString()
    }

    private fun writeFinish(
        ctx: ProxyCall,
        output: OutputStream,
        sanitizer: StreamingCompletionSanitizer,
        id: String,
        created: Long,
        model: String,
    ): String {
        val extra = sanitizer.finish()
        if (extra.isNotEmpty()) {
            writeSseData(ctx, output, textCompletionChunk(id, created, model, extra, null))
        }
        writeSseData(ctx, output, textCompletionChunk(id, created, model, "", "stop"))
        val done = "data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8)
        output.write(done)
        AccessLogFields.addResponseBytes(ctx, done.size.toLong())
        output.flush()
        return extra
    }

    private suspend fun writeCompletion(
        ctx: ProxyCall,
        stream: Boolean,
        model: String,
        requestId: String,
        text: String,
    ) {
        if (text.isEmpty()) {
            writeEmptyCompletion(ctx, stream, model, requestId)
            return
        }
        val id = completionId(requestId)
        val created = System.currentTimeMillis() / 1000L
        if (stream) {
            JsonHelper.setSseHeaders(ctx)
            ctx.setStatus(200)
            ctx.call.respondOutputStream(ContentType.parse(JsonHelper.SSE_CONTENT_TYPE), HttpStatusCode.OK) {
                writeSseData(ctx, this, textCompletionChunk(id, created, model, text, null))
                writeSseData(ctx, this, textCompletionChunk(id, created, model, "", "stop"))
                val done = "data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8)
                write(done)
                AccessLogFields.addResponseBytes(ctx, done.size.toLong())
                flush()
            }
            ctx.handled = true
        } else {
            JsonHelper.toJsonResponse(ctx, textCompletionJson(id, created, model, text))
        }
    }

    private suspend fun writeEmptyCompletion(ctx: ProxyCall, stream: Boolean, model: String, requestId: String) {
        val id = completionId(requestId)
        val created = System.currentTimeMillis() / 1000L
        if (stream) {
            JsonHelper.setSseHeaders(ctx)
            ctx.setStatus(200)
            ctx.call.respondOutputStream(ContentType.parse(JsonHelper.SSE_CONTENT_TYPE), HttpStatusCode.OK) {
                writeSseData(ctx, this, textCompletionChunk(id, created, model, "", "stop"))
                val done = "data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8)
                write(done)
                AccessLogFields.addResponseBytes(ctx, done.size.toLong())
                flush()
            }
            ctx.handled = true
        } else {
            JsonHelper.toJsonResponse(ctx, textCompletionJson(id, created, model, ""))
        }
    }

    private suspend fun sendAsync(request: HttpRequest): HttpResponse<InputStream> {
        return suspendCancellableCoroutine { continuation ->
            val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            future.whenComplete { response, error ->
                if (continuation.isActive) {
                    if (error != null) continuation.resumeWithException(error)
                    else continuation.resume(response)
                } else {
                    response?.body()?.close()
                }
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(CompletionsHandler::class.java)
        private const val DEFAULT_TEMPERATURE = 0.1
        private const val MAX_TEMPERATURE = 0.2
        private val HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build()

        internal fun advertisedModel(config: CompletionsConfig, request: CompletionsRequest): String {
            return request.model.ifBlank { config.aliasId }
        }

        internal fun resolveSelectedModel(
            catalog: SubscriptionModelCatalog,
            selectedId: String,
        ): SubscriptionProxyModel? {
            return catalog.resolve(selectedId, SubscriptionProxyRoute.CHAT_COMPLETIONS)
                ?: catalog.resolve(selectedId, SubscriptionProxyRoute.RESPONSES)
                ?: catalog.resolve(selectedId, SubscriptionProxyRoute.COMPLETIONS)
                ?: catalog.models.firstOrNull { it.localId == selectedId }
        }

        internal fun nativeBody(
            modelId: String,
            request: CompletionsRequest,
            fim: FimContext,
            maxTokens: Int,
        ): JsonObject {
        val stops = CompletionSanitizer.effectiveStops(fim.prefix, request.stop, fim.languageHint)
        return buildJsonObject {
            put("model", modelId)
            put("prompt", fim.prefix)
                put("stream", request.stream)
                put("max_tokens", maxTokens)
                if (fim.suffix.isNotEmpty()) put("suffix", fim.suffix)
                request.temperature?.let { put("temperature", it.coerceIn(0.0, MAX_TEMPERATURE)) }
                if (stops.isNotEmpty()) {
                    put("stop", buildJsonArray {
                        stops.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }

        internal fun chatBody(
            modelId: String,
            request: CompletionsRequest,
            fim: FimContext,
            maxTokens: Int,
            temperature: Double,
            priorityTier: Boolean = false,
        ): JsonObject {
            val stops = CompletionSanitizer.effectiveStops(fim.prefix, request.stop, fim.languageHint)
            return buildJsonObject {
                put("model", modelId)
                put("stream", request.stream)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
                put("reasoning_effort", "low")
                if (priorityTier) put("service_tier", CompletionsConfig.SERVICE_TIER_PRIORITY)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", ChatFimPromptBuilder.SYSTEM_PROMPT)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", ChatFimPromptBuilder.userPrompt(fim))
                    })
                })
                if (stops.isNotEmpty()) {
                    put("stop", buildJsonArray {
                        stops.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }

        internal fun chatMessageContent(raw: String): String {
            val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return raw
            val choices = root["choices"] as? JsonArray ?: return ""
            val choice = choices.firstOrNull() as? JsonObject ?: return ""
            val message = choice["message"] as? JsonObject
            val content = message?.get("content") ?: choice["text"]
            return (content as? JsonPrimitive)?.contentOrNull.orEmpty()
        }

        internal fun chatDeltaContent(data: String): String? {
            val root = JsonHelper.parseToJsonElementOrNull(data) as? JsonObject ?: return null
            val choices = root["choices"] as? JsonArray ?: return null
            val choice = choices.firstOrNull() as? JsonObject ?: return null
            val delta = choice["delta"] as? JsonObject
            val content = delta?.get("content") ?: choice["text"]
            return (content as? JsonPrimitive)?.contentOrNull
        }

        internal fun textCompletionJson(
            id: String,
            created: Long,
            model: String,
            text: String,
            finishReason: String = "stop",
        ): JsonObject {
            return buildJsonObject {
                put("id", id)
                put("object", "text_completion")
                put("created", created)
                put("model", model)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("text", text)
                        put("index", 0)
                        put("finish_reason", finishReason)
                    })
                })
                put("usage", buildJsonObject {
                    put("prompt_tokens", 0)
                    put("completion_tokens", 0)
                    put("total_tokens", 0)
                })
            }
        }

        internal fun textCompletionChunk(
            id: String,
            created: Long,
            model: String,
            text: String,
            finishReason: String?,
        ): JsonObject {
            return buildJsonObject {
                put("id", id)
                put("object", "text_completion")
                put("created", created)
                put("model", model)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("text", text)
                        if (finishReason == null) {
                            put("finish_reason", kotlinx.serialization.json.JsonNull)
                        } else {
                            put("finish_reason", finishReason)
                        }
                    })
                })
            }
        }

        private fun writeSseData(ctx: ProxyCall, output: OutputStream, payload: JsonObject) {
            val line = "data: ${JsonHelper.encodeToString(payload)}\n\n"
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            output.write(bytes)
            AccessLogFields.addResponseBytes(ctx, bytes.size.toLong())
            output.flush()
        }

        private fun completionId(requestId: String): String {
            val suffix = requestId.trim().ifBlank { UUID.randomUUID().toString() }
            return if (suffix.startsWith("cmpl-")) suffix else "cmpl-$suffix"
        }

        private fun isEventStream(response: HttpResponse<InputStream>): Boolean {
            return response.headers().firstValue(HttpHeaders.ContentType).orElse("")
                .contains("text/event-stream", ignoreCase = true)
        }
    }
}
