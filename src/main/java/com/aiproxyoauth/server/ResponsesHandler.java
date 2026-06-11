package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.CodexInstructionsProvider;
import com.aiproxyoauth.model.ModelAliasResolver;
import com.aiproxyoauth.sse.SseCollector;
import com.aiproxyoauth.state.ResponsesState;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public class ResponsesHandler implements Handler {

    private static final int MAX_REPLAY_NAMESPACES = 512;
    private static final int MAX_SSE_BOOKKEEPING_LINE_BYTES = 64 * 1024;

    private final CodexHttpClient client;
    private final ServerConfig config;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;
    private final CodexInstructionsProvider instructionsProvider;
    private final ResponsesRequestSanitizer requestSanitizer = new ResponsesRequestSanitizer();
    private final ModelAliasResolver modelAliasResolver = new ModelAliasResolver();
    private final UpstreamErrorMapper upstreamErrorMapper = new UpstreamErrorMapper();
    private final Map<String, ResponsesState> replayStates = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ResponsesState> eldest) {
                    return size() > MAX_REPLAY_NAMESPACES;
                }
            });

    public ResponsesHandler(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker) {
        this(client, config, usageTracker,
                new RequestLogger(false, java.nio.file.Path.of(config.requestLogDir())),
                new CodexInstructionsProvider(config.instructions()));
    }

    public ResponsesHandler(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker,
                            RequestLogger requestLogger, CodexInstructionsProvider instructionsProvider) {
        this.client = client;
        this.config = config;
        this.usageTracker = usageTracker;
        this.requestLogger = requestLogger;
        this.instructionsProvider = instructionsProvider;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        create(ctx);
    }

    public void create(Context ctx) throws Exception {
        String requestId = shouldUseRequestContext() ? requestId(ctx) : requestLogger.nextRequestId();
        String bodyStr = ctx.body();
        requestLogger.logInbound(requestId, ctx, bodyStr);
        JsonNode body = MAPPER.readTree(bodyStr);

        if (body == null || !body.isObject()) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.");
            return;
        }

        boolean wantsStream = body.path("stream").asBoolean(false);
        AccessLogFields.mode(ctx, wantsStream ? "stream" : "sync");

        // Expand previous_response_id and item_reference references before forwarding
        ResponsesState state = replayStateFor(ctx);
        ObjectNode expanded = state.expandRequestBody((ObjectNode) body);

        // Normalize body
        ObjectNode normalized = requestSanitizer.sanitize(normalizeBody(expanded), config.store());
        String promptCacheKey = config.forwardPromptCacheHeaders()
                ? normalized.path("prompt_cache_key").asText(null)
                : null;

        // Forward to upstream
        HttpResponse<InputStream> upstream = UpstreamRetry.withRetries(
                ctx.header("x-litellm-num-retries"),
                () -> sendUpstream(normalized, requestId, promptCacheKey));
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode());
        ctx.header("x-litellm-model-id", normalized.path("model").asText(""));

        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            try (InputStream is = upstream.body()) {
                String rawBody = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                UpstreamErrorMapper.MappedUpstreamError mapped = upstreamErrorMapper.map(upstream.statusCode(), rawBody);
                requestLogger.logUpstreamResponse(requestId, mapped.statusCode(), responseHeaders(upstream), mapped.body());
                ctx.status(mapped.statusCode());
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE);
                AccessLogFields.responseBytes(ctx, mapped.body().getBytes(StandardCharsets.UTF_8).length);
                ctx.result(mapped.body());
            }
            return;
        }

        if (wantsStream) {
            // Stream SSE directly to client
            JsonHelper.setSseHeaders(ctx);
            StreamingCompletionRecorder recorder = new StreamingCompletionRecorder(ctx, state, expanded);
            try (InputStream is = upstream.body();
                 OutputStream os = ctx.res().getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    AccessLogFields.addResponseBytes(ctx, bytesRead);
                    recorder.accept(buffer, bytesRead);
                    os.flush();
                }
            }
            recorder.finish();
        } else {
            // Collect completed response from SSE
            try (InputStream is = upstream.body()) {
                JsonNode completed = SseCollector.collectCompletedResponse(is);
                String status = completed.path("status").asText("");
                if ("failed".equals(status) || "cancelled".equals(status)) {
                    String errorMessage = completed.path("error").path("message")
                            .asText("Upstream response " + status + ".");
                    requestLogger.logClientResponse(requestId, 502, errorMessage);
                    JsonHelper.toErrorResponse(ctx, errorMessage, 502, "upstream_error");
                    return;
                }
                if (JunieCommandProtocolCompat.isJunieRequest(expanded)
                        && !JunieCommandProtocolCompat.hasFunctionCallOutput(completed)) {
                    String declaredToolName = JunieCommandProtocolCompat.declaredFallbackToolName(expanded);
                    if (declaredToolName != null) {
                        completed = JunieCommandProtocolCompat.toToolResponse(completed, declaredToolName);
                    } else if (!JunieCommandProtocolCompat.hasToolDefinitions(expanded)) {
                        // Tool-less Junie requests are the <THOUGHT>/<COMMAND> text protocol;
                        // a synthetic call to an undeclared tool would not parse as a command.
                        completed = JunieCommandProtocolCompat.wrapCompletedResponse(completed);
                    }
                    // Tools declared but no submit/answer: native protocol — pass through unchanged.
                }
                recordUsage(ctx, completed.get("usage"));
                // Best-effort same-process replay cache only; nothing is persisted locally.
                state.rememberResponse(completed, expanded);
                JsonHelper.toJsonResponse(ctx, completed);
            }
        }
    }

    private ObjectNode normalizeBody(ObjectNode body) {
        ObjectNode normalized = body.deepCopy();
        normalized.put("stream", true);
        String requestedModel = normalized.path("model").asText(ServerConfig.DEFAULT_MODEL);
        ModelAliasResolver.ResolvedModel resolvedModel = modelAliasResolver.resolve(requestedModel);
        if (resolvedModel.model() != null && !resolvedModel.model().isBlank()) {
            normalized.put("model", resolvedModel.model());
        }

        hoistSystemMessagesIntoInstructions(normalized);

        if (!normalized.has("instructions") || !normalized.get("instructions").isTextual()) {
            normalized.put("instructions", instructionsProvider.instructionsForModel(normalized.path("model").asText()));
        }

        if (!normalized.has("store")) {
            normalized.put("store", config.store());
        }

        String aliasEffort = resolvedModel.reasoningEffort();
        JsonNode reasoningNode = normalized.get("reasoning");
        ObjectNode reasoning = reasoningNode != null && reasoningNode.isObject()
                ? ((ObjectNode) reasoningNode).deepCopy()
                : MAPPER.createObjectNode();
        String requestedEffort = reasoning.path("effort").asText(aliasEffort);
        String clampedEffort = modelAliasResolver.clampReasoningEffort(normalized.path("model").asText(), requestedEffort);
        if (clampedEffort != null) {
            reasoning.put("effort", clampedEffort);
            normalized.set("reasoning", reasoning);
        }

        return normalized;
    }

    /**
     * The Codex backend rejects requests containing system-role input items
     * ("System messages are not allowed"). Clients such as Junie's Responses client
     * always send their system prompt as an input message, so move that text into
     * the `instructions` field instead.
     */
    private void hoistSystemMessagesIntoInstructions(ObjectNode normalized) {
        JsonNode input = normalized.get("input");
        if (input == null || !input.isArray()) {
            return;
        }

        StringBuilder systemTexts = new StringBuilder();
        ArrayNode filteredInput = MAPPER.createArrayNode();
        for (JsonNode item : input) {
            if (item.isObject() && isSystemMessageItem(item)) {
                String text = JunieCommandProtocolCompat.messageText(item.get("content"));
                if (!text.isEmpty()) {
                    if (!systemTexts.isEmpty()) {
                        systemTexts.append('\n');
                    }
                    systemTexts.append(text);
                }
                continue;
            }
            filteredInput.add(item);
        }

        if (systemTexts.isEmpty()) {
            return;
        }
        normalized.set("input", filteredInput);
        String existingInstructions = normalized.path("instructions").isTextual()
                ? normalized.get("instructions").asText().strip() : "";
        String combined = existingInstructions.isEmpty()
                ? systemTexts.toString()
                : existingInstructions + "\n" + systemTexts;
        normalized.put("instructions", combined);
    }

    private static boolean isSystemMessageItem(JsonNode item) {
        String role = item.path("role").asText("");
        if (!"system".equals(role) && !"developer".equals(role)) {
            return false;
        }
        String type = item.path("type").asText("message");
        return "message".equals(type);
    }

    private HttpResponse<InputStream> sendUpstream(ObjectNode normalized, String requestId, String promptCacheKey)
            throws Exception {
        String payload = MAPPER.writeValueAsString(normalized);
        if (shouldUseRequestContext()) {
            return client.request(
                    "/responses", "POST",
                    payload,
                    Map.of("Content-Type", "application/json"),
                    requestId,
                    promptCacheKey);
        }
        return client.request(
                "/responses", "POST",
                payload,
                Map.of("Content-Type", "application/json"));
    }

    private boolean shouldUseRequestContext() {
        return config.fullRequestLogging() || config.forwardPromptCacheHeaders();
    }

    private static <T> Map<String, List<String>> responseHeaders(HttpResponse<T> response) {
        return response.headers() == null ? Map.of() : response.headers().map();
    }

    private String requestId(Context ctx) {
        String requestId = ctx.attribute("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = requestLogger.nextRequestId();
            ctx.attribute("requestId", requestId);
        }
        return requestId;
    }

    private boolean recordStreamingCompletion(
            Context ctx, String eventType, String data, ResponsesState state, JsonNode expandedRequest) {
        try {
            if (data == null || data.isEmpty() || "[DONE]".equals(data)) {
                return false;
            }
            JsonNode parsed = MAPPER.readTree(data);
            if (parsed == null || !parsed.isObject()) {
                return false;
            }

            String parsedEventType = parsed.path("type").asText(eventType != null ? eventType : "");
            if (!"response.completed".equals(parsedEventType)) {
                return false;
            }

            JsonNode response = parsed.get("response");
            if (response != null && response.isObject()) {
                recordUsage(ctx, response.get("usage"));
                state.rememberResponse(response, expandedRequest);
                return true;
            }
        } catch (Exception ignored) {
            // Streaming payloads have already been forwarded; replay/usage bookkeeping is best-effort.
        }
        return false;
    }

    private void recordUsage(Context ctx, JsonNode usageNode) {
        usageTracker.record(ctx.attribute("keyName"),
                usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
    }

    private ResponsesState replayStateFor(Context ctx) {
        boolean isAdmin = Boolean.TRUE.equals(ctx.attribute("isAdmin"));
        String keyFingerprint = ctx.attribute("keyFingerprint");
        String adminKeyFingerprint = ctx.attribute("adminKeyFingerprint");
        String keyName = ctx.attribute("keyName");
        String namespace;
        if (isAdmin && adminKeyFingerprint != null) {
            namespace = "admin-fp:" + adminKeyFingerprint;
        } else if (keyFingerprint != null) {
            namespace = "key-fp:" + keyFingerprint;
        } else if (keyName != null) {
            namespace = "key:" + keyName;
        } else if (isAdmin) {
            namespace = "admin";
        } else {
            namespace = "open";
        }

        synchronized (replayStates) {
            return replayStates.computeIfAbsent(namespace, ignored -> new ResponsesState());
        }
    }

    private final class StreamingCompletionRecorder {
        private final Context ctx;
        private final ResponsesState state;
        private final JsonNode expandedRequest;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        private final List<String> dataLines = new ArrayList<>();
        private String eventType;
        private boolean recorded;
        private boolean bookkeepingDisabled;

        private StreamingCompletionRecorder(Context ctx, ResponsesState state, JsonNode expandedRequest) {
            this.ctx = ctx;
            this.state = state;
            this.expandedRequest = expandedRequest;
        }

        private void accept(byte[] buffer, int length) {
            if (bookkeepingDisabled) {
                return;
            }
            for (int i = 0; i < length; i++) {
                byte b = buffer[i];
                if (b == '\n') {
                    acceptLine(lineBuffer.toString(StandardCharsets.UTF_8));
                    lineBuffer.reset();
                } else {
                    lineBuffer.write(b);
                    if (lineBuffer.size() > MAX_SSE_BOOKKEEPING_LINE_BYTES) {
                        disableBookkeeping();
                        return;
                    }
                }
            }
        }

        private void finish() {
            if (bookkeepingDisabled) {
                return;
            }
            if (lineBuffer.size() > 0) {
                acceptLine(lineBuffer.toString(StandardCharsets.UTF_8));
                lineBuffer.reset();
            }
            if (eventType != null || !dataLines.isEmpty()) {
                dispatchEvent();
            }
        }

        private void acceptLine(String line) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                if (eventType != null || !dataLines.isEmpty()) {
                    dispatchEvent();
                }
                return;
            }
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
        }

        private void dispatchEvent() {
            if (!recorded) {
                String data = dataLines.isEmpty() ? null : String.join("\n", dataLines);
                recorded = recordStreamingCompletion(ctx, eventType, data, state, expandedRequest);
            }
            eventType = null;
            dataLines.clear();
        }

        private void disableBookkeeping() {
            bookkeepingDisabled = true;
            recorded = true;
            lineBuffer.reset();
            dataLines.clear();
            eventType = null;
        }
    }
}
