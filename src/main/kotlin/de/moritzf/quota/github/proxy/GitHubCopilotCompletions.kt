package de.moritzf.quota.github.proxy

import de.moritzf.proxy.fim.CompletionSanitizer
import de.moritzf.proxy.fim.CompletionsRequest
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.AccessLogFields
import de.moritzf.proxy.server.CompletionsHandler
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.UpstreamErrorMapper
import de.moritzf.proxy.sse.SseParser
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.quota.github.GitHubQuotaException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Copilot's native prefix/suffix endpoint always streams, including for non-streaming callers. */
internal class GitHubCopilotCompletions(
    private val sessions: GitHubCopilotSessionClient,
    private val httpClient: HttpClient,
    private val requestLogger: RequestLogger,
) {
    suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        val upstream =
            try {
                runInterruptible(Dispatchers.IO) {
                    val session = sessions.session()
                    val response = send(request, session)
                    if (response.statusCode() != 401) response
                    else {
                        response.body().close()
                        send(request, sessions.session(rejectedToken = session.token))
                    }
                }
            } catch (exception: GitHubQuotaException) {
                JsonHelper.toErrorResponse(
                    ctx,
                    exception.message.orEmpty(),
                    exception.statusCode,
                    if (exception.statusCode == 401) "authentication_error" else "upstream_error",
                )
                return
            }
        upstream.body().use {
            AccessLogFields.upstreamStatus(ctx, upstream.statusCode())
            if (upstream.statusCode() !in 200..299) {
                UpstreamErrorMapper().writeResponse(ctx, requestLogger, request.requestId, upstream)
                return
            }
            if (
                !upstream
                    .headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .contains("text/event-stream", ignoreCase = true)
            ) {
                JsonHelper.toErrorResponse(
                    ctx,
                    "Copilot did not return a completion stream.",
                    502,
                    "upstream_error",
                )
                return
            }
            val id = "cmpl-${request.requestId}"
            val created = System.currentTimeMillis() / 1000
            val model = request.model.localId
            val stops = CompletionsRequest.stopSequences(request.body)
            if ((request.body["stream"] as? JsonPrimitive)?.booleanOrNull == true) {
                JsonHelper.setSseHeaders(ctx)
                ctx.setStatus(200)
                ctx.handled = true
                ctx.respondOutputStream(
                    ContentType.parse(JsonHelper.SSE_CONTENT_TYPE),
                    HttpStatusCode.OK,
                ) {
                    runInterruptible(Dispatchers.IO) {
                        val finishReason =
                            consume(upstream.body(), stops) { text ->
                                emit(
                                    ctx,
                                    JsonHelper.encodeToString(
                                        CompletionsHandler.textCompletionChunk(
                                            id,
                                            created,
                                            model,
                                            text,
                                            null,
                                        )
                                    ),
                                )
                            }
                        emit(
                            ctx,
                            JsonHelper.encodeToString(
                                CompletionsHandler.textCompletionChunk(
                                    id,
                                    created,
                                    model,
                                    "",
                                    finishReason,
                                )
                            ),
                        )
                        emit(ctx, "[DONE]")
                    }
                }
            } else {
                val text = StringBuilder()
                val finishReason =
                    runInterruptible(Dispatchers.IO) {
                        consume(upstream.body(), stops) { text.append(it) }
                    }
                JsonHelper.toJsonResponse(
                    ctx,
                    CompletionsHandler.textCompletionJson(
                        id,
                        created,
                        model,
                        text.toString(),
                        finishReason,
                    ),
                )
            }
        }
    }

    private fun send(
        request: SubscriptionProxyRequest,
        session: GitHubCopilotSessionClient.Session,
    ): HttpResponse<InputStream> {
        val model = URLEncoder.encode(request.model.upstreamId, UTF_8).replace("+", "%20")
        val path = "/v1/engines/$model/completions"
        val body = buildJsonObject {
            for (name in listOf("prompt", "suffix", "max_tokens", "temperature", "top_p")) {
                request.body[name]?.let { put(name, it) }
            }
            if ("suffix" !in request.body) put("suffix", "")
            if ("temperature" !in request.body) put("temperature", 0)
            // Copilot rejects more than four stops. Enforce the full list while consuming SSE.
            val stops = CompletionsRequest.stopSequences(request.body).distinct().take(4)
            if (stops.isNotEmpty()) put("stop", JsonArray(stops.map(::JsonPrimitive)))
            put("n", 1)
            put("stream", true)
        }
        val headers =
            githubCopilotHeaders(session.token) +
                mapOf(
                    "Content-Type" to JsonHelper.JSON_CONTENT_TYPE,
                    "Accept" to JsonHelper.SSE_CONTENT_TYPE,
                    "Openai-Intent" to "copilot-ghost",
                    "Openai-Organization" to "github-copilot",
                    "X-Request-Id" to request.requestId,
                )
        val builder =
            HttpRequest.newBuilder(URI.create(session.proxy.toString().trimEnd('/') + path))
                .timeout(Duration.ofSeconds(30))
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        val payload = JsonHelper.encodeToString(body)
        requestLogger.logUpstreamRequest(request.requestId, "POST", path, headers, payload)
        return httpClient.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
    }

    private fun consume(input: InputStream, stops: List<String>, emit: (String) -> Unit): String {
        var finishReason = "stop"
        var completed = false
        var pending = ""
        SseParser.iterateEventsWhile(input) { event ->
            val data = event.data()?.takeUnless { it.isBlank() } ?: return@iterateEventsWhile true
            if (data == "[DONE]") {
                completed = true
                return@iterateEventsWhile false
            }
            val chunk =
                JsonHelper.parseToJsonElementOrNull(data) as? JsonObject
                    ?: error("Invalid Copilot completion event.")
            if ("error" in chunk || event.event() == "error")
                error("Copilot completion stream failed.")
            val choice =
                (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return@iterateEventsWhile true
            (choice["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                pending += text
                val cut = CompletionSanitizer.cutAtStopSequence(pending, stops)
                if (cut != null) {
                    if (cut.content.isNotEmpty()) emit(cut.content)
                    pending = ""
                    completed = true
                    return@iterateEventsWhile false
                } else {
                    val stable = CompletionSanitizer.dropUnstableTail(pending, stops)
                    if (stable.isNotEmpty()) emit(stable)
                    pending = pending.substring(stable.length)
                }
            }
            stringField(choice, "finish_reason")?.let {
                finishReason = it
                completed = true
            }
            !completed
        }
        check(completed) { "Copilot completion stream ended before completion." }
        if (pending.isNotEmpty()) emit(pending)
        return finishReason
    }

    private fun OutputStream.emit(ctx: ProxyCall, data: String) {
        val bytes = "data: $data\n\n".toByteArray(UTF_8)
        write(bytes)
        AccessLogFields.addResponseBytes(ctx, bytes.size.toLong())
        flush()
    }
}
