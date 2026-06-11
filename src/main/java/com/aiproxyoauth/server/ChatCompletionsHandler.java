package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.CodexInstructionsProvider;
import com.aiproxyoauth.model.ModelAliasResolver;
import com.aiproxyoauth.sse.SseCollector;
import com.aiproxyoauth.sse.SseParser;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public class ChatCompletionsHandler implements Handler {

    private final CodexHttpClient client;
    private final ServerConfig config;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;
    private final CodexInstructionsProvider instructionsProvider;
    private final ModelAliasResolver modelAliasResolver = new ModelAliasResolver();
    private final UpstreamErrorMapper upstreamErrorMapper = new UpstreamErrorMapper();

    public ChatCompletionsHandler(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker) {
        this(client, config, usageTracker,
                new RequestLogger(false, java.nio.file.Path.of(config.requestLogDir())),
                new CodexInstructionsProvider(config.instructions()));
    }

    public ChatCompletionsHandler(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker,
                                  RequestLogger requestLogger, CodexInstructionsProvider instructionsProvider) {
        this.client = client;
        this.config = config;
        this.usageTracker = usageTracker;
        this.requestLogger = requestLogger;
        this.instructionsProvider = instructionsProvider;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String requestId = shouldUseRequestContext() ? requestId(ctx) : requestLogger.nextRequestId();
        String bodyStr = ctx.body();
        requestLogger.logInbound(requestId, ctx, bodyStr);
        JsonNode body = MAPPER.readTree(bodyStr);

        if (body == null || !body.isObject()) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.");
            return;
        }

        JsonNode messagesNode = body.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            JsonHelper.toErrorResponse(ctx, "`messages` must be an array.");
            return;
        }

        boolean wantsStream = body.path("stream").asBoolean(false);
        boolean junieCommandProtocol = JunieCommandProtocolCompat.isJunieRequest(body);
        String junieToolName = junieCommandProtocol ? JunieCommandProtocolCompat.fallbackToolName(body) : null;
        // Modern Junie declares native `tools` and validates that responses contain real
        // tool_calls — wrapping output in <COMMAND> text makes it reject the response.
        // The legacy <THOUGHT>/<COMMAND> text protocol only applies to requests without
        // modern tool definitions.
        boolean nativeToolProtocol = hasModernToolDefinitions(body);
        boolean junieTextProtocol = junieCommandProtocol && !nativeToolProtocol;
        String junieNativeToolName = nativeToolProtocol ? junieToolName : null;
        String junieTextToolName = junieTextProtocol ? junieToolName : null;
        boolean legacyFunctionCallProtocol = usesLegacyFunctions(body);
        AccessLogFields.mode(ctx, wantsStream ? "stream" : "sync");
        // When --models was specified, default to the first configured model.
        // "gpt-5.2" is the last-resort fallback for when no models were configured and
        // auto-discovery failed — in that case no better default is available without
        // an extra ModelResolver call. Callers can always override via the "model" field.
        String defaultModel = config.models() != null && !config.models().isEmpty()
                ? config.models().getFirst() : ServerConfig.DEFAULT_MODEL;
        String model = body.path("model").asText(defaultModel);
        ModelAliasResolver.ResolvedModel resolvedModel = modelAliasResolver.resolve(model);
        String upstreamModel = resolvedModel.model() != null ? resolvedModel.model() : model;

        // Build upstream Responses API request
        ObjectNode upstreamBody = buildUpstreamBody(body, upstreamModel, resolvedModel.reasoningEffort());
        String promptCacheKey = config.forwardPromptCacheHeaders()
                ? upstreamBody.path("prompt_cache_key").asText(null)
                : null;

        // Always stream upstream
        HttpResponse<InputStream> upstream = sendUpstream(upstreamBody, requestId, promptCacheKey);
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode());

        try (InputStream responseStream = upstream.body()) {
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                String rawBody = new String(responseStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                UpstreamErrorMapper.MappedUpstreamError mapped = upstreamErrorMapper.map(upstream.statusCode(), rawBody);
                requestLogger.logUpstreamResponse(requestId, mapped.statusCode(), responseHeaders(upstream), mapped.body());
                ctx.status(mapped.statusCode());
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE);
                AccessLogFields.responseBytes(ctx, mapped.body().getBytes(StandardCharsets.UTF_8).length);
                ctx.result(mapped.body());
                return;
            }

            if (wantsStream) {
                streamToClient(ctx, responseStream, upstreamModel, junieTextToolName, junieNativeToolName, body);
            } else {
                nonStreamToClient(ctx, responseStream, upstreamModel, junieTextProtocol, junieTextToolName,
                        junieNativeToolName, legacyFunctionCallProtocol, body);
            }
        }
    }

    private ObjectNode buildUpstreamBody(JsonNode chatBody, String model, String aliasReasoningEffort) {
        ObjectNode upstream = MAPPER.createObjectNode();
        upstream.put("model", model);
        upstream.put("stream", true);
        upstream.put("store", config.store());

        // Convert messages to Responses API format
        ArrayNode input = MAPPER.createArrayNode();
        StringBuilder instructions = new StringBuilder();

        JsonNode messages = chatBody.get("messages");
        for (JsonNode msg : messages) {
            String role = msg.path("role").asText("");
            switch (role) {
                case "system", "developer" -> {
                    String text = extractTextContent(msg.get("content"));
                    if (!text.isEmpty()) {
                        if (!instructions.isEmpty()) instructions.append("\n");
                        instructions.append(text);
                    }
                }
                case "user" -> {
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("type", "message");
                    item.put("role", "user");
                    ArrayNode content = MAPPER.createArrayNode();
                    addContentParts(content, msg.get("content"));
                    item.set("content", content);
                    input.add(item);
                }
                case "assistant" -> {
                    String text = extractTextContent(msg.get("content"));
                    JsonNode toolCalls = msg.get("tool_calls");

                    if (!text.isEmpty()) {
                        ObjectNode item = MAPPER.createObjectNode();
                        item.put("type", "message");
                        item.put("role", "assistant");
                        ArrayNode content = MAPPER.createArrayNode();
                        ObjectNode textPart = MAPPER.createObjectNode();
                        textPart.put("type", "output_text");
                        textPart.put("text", text);
                        content.add(textPart);
                        item.set("content", content);
                        input.add(item);
                    }

                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            ObjectNode funcCall = MAPPER.createObjectNode();
                            funcCall.put("type", "function_call");
                            funcCall.put("call_id", tc.path("id").asText(""));
                            JsonNode func = tc.get("function");
                            if (func != null) {
                                funcCall.put("name", func.path("name").asText(""));
                                funcCall.put("arguments", func.path("arguments").asText("{}"));
                            }
                            input.add(funcCall);
                        }
                    }
                }
                case "tool" -> {
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("type", "function_call_output");
                    item.put("call_id", msg.path("tool_call_id").asText(""));
                    String content = extractTextContent(msg.get("content"));
                    item.put("output", content);
                    input.add(item);
                }
            }
        }

        upstream.set("input", input);

        // Set instructions
        String instr = instructions.toString();
        if (instr.isEmpty()) {
            instr = instructionsProvider.instructionsForModel(model);
        }
        upstream.put("instructions", instr);

        // Optional parameters
        if (chatBody.has("temperature") && !chatBody.get("temperature").isNull()) {
            upstream.set("temperature", chatBody.get("temperature"));
        }
        if (chatBody.has("top_p") && !chatBody.get("top_p").isNull()) {
            upstream.set("top_p", chatBody.get("top_p"));
        }
        // max_completion_tokens (newer SDK) takes precedence over deprecated max_tokens
        if (chatBody.has("max_completion_tokens") && !chatBody.get("max_completion_tokens").isNull()) {
            upstream.put("max_output_tokens", chatBody.get("max_completion_tokens").asInt());
        } else if (chatBody.has("max_tokens") && !chatBody.get("max_tokens").isNull()) {
            upstream.put("max_output_tokens", chatBody.get("max_tokens").asInt());
        }

        ArrayNode tools = MAPPER.createArrayNode();
        addModernTools(tools, chatBody.get("tools"));
        addLegacyFunctions(tools, chatBody.get("functions"));
        if (!tools.isEmpty()) {
            upstream.set("tools", tools);
        }

        // Tool choice
        if (chatBody.has("tool_choice") && !chatBody.get("tool_choice").isNull()) {
            upstream.set("tool_choice", chatBody.get("tool_choice"));
        } else if (chatBody.has("function_call") && !chatBody.get("function_call").isNull()) {
            setLegacyFunctionCallChoice(upstream, chatBody.get("function_call"));
        }

        // Structured output: chat `response_format` json_schema maps to Responses `text.format`.
        JsonNode responseFormat = chatBody.get("response_format");
        if (responseFormat != null && responseFormat.isObject()
                && "json_schema".equals(responseFormat.path("type").asText())) {
            JsonNode jsonSchema = responseFormat.get("json_schema");
            if (jsonSchema != null && jsonSchema.isObject()) {
                ObjectNode format = MAPPER.createObjectNode();
                format.put("type", "json_schema");
                if (jsonSchema.hasNonNull("name")) {
                    format.set("name", jsonSchema.get("name"));
                }
                if (jsonSchema.hasNonNull("strict")) {
                    format.set("strict", jsonSchema.get("strict"));
                }
                if (jsonSchema.hasNonNull("schema")) {
                    format.set("schema", jsonSchema.get("schema"));
                }
                ObjectNode text = MAPPER.createObjectNode();
                text.set("format", format);
                upstream.set("text", text);
            }
        }

        // Reasoning effort
        if (chatBody.has("reasoning_effort") && !chatBody.get("reasoning_effort").isNull()) {
            ObjectNode reasoning = MAPPER.createObjectNode();
            reasoning.put("effort", modelAliasResolver.clampReasoningEffort(model, chatBody.get("reasoning_effort").asText()));
            upstream.set("reasoning", reasoning);
        } else if (aliasReasoningEffort != null) {
            ObjectNode reasoning = MAPPER.createObjectNode();
            reasoning.put("effort", modelAliasResolver.clampReasoningEffort(model, aliasReasoningEffort));
            upstream.set("reasoning", reasoning);
        }

        return upstream;
    }

    private void addModernTools(ArrayNode tools, JsonNode toolDefinitions) {
        if (toolDefinitions == null || !toolDefinitions.isArray()) {
            return;
        }
        for (JsonNode toolDef : toolDefinitions) {
            if (!"function".equals(toolDef.path("type").asText())) continue;
            JsonNode func = toolDef.get("function");
            if (func != null) {
                addFunctionTool(tools, func);
            }
        }
    }

    private void addLegacyFunctions(ArrayNode tools, JsonNode functionDefinitions) {
        if (functionDefinitions == null || !functionDefinitions.isArray()) {
            return;
        }
        for (JsonNode functionDef : functionDefinitions) {
            addFunctionTool(tools, functionDef);
        }
    }

    private void addFunctionTool(ArrayNode tools, JsonNode func) {
        String name = func.path("name").asText("");
        if (name.isBlank()) {
            return;
        }
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        tool.put("name", name);
        if (func.has("description")) {
            tool.put("description", func.path("description").asText(""));
        }
        if (func.has("parameters")) {
            tool.set("parameters", func.get("parameters"));
        } else {
            ObjectNode defaultParams = MAPPER.createObjectNode();
            defaultParams.put("type", "object");
            defaultParams.set("properties", MAPPER.createObjectNode());
            defaultParams.put("additionalProperties", true);
            tool.set("parameters", defaultParams);
        }
        tools.add(tool);
    }

    private void setLegacyFunctionCallChoice(ObjectNode upstream, JsonNode functionCall) {
        if (functionCall.isTextual()) {
            String choice = functionCall.asText();
            if (!choice.isBlank()) {
                upstream.put("tool_choice", choice);
            }
            return;
        }
        String name = functionCall.path("name").asText("");
        if (!name.isBlank()) {
            ObjectNode toolChoice = MAPPER.createObjectNode();
            toolChoice.put("type", "function");
            toolChoice.put("name", name);
            upstream.set("tool_choice", toolChoice);
        }
    }

    private HttpResponse<InputStream> sendUpstream(ObjectNode upstreamBody, String requestId, String promptCacheKey)
            throws Exception {
        String payload = MAPPER.writeValueAsString(upstreamBody);
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

    private boolean hasModernToolDefinitions(JsonNode body) {
        JsonNode tools = body.get("tools");
        return tools != null && tools.isArray() && !tools.isEmpty();
    }

    private boolean usesLegacyFunctions(JsonNode body) {
        JsonNode functions = body.get("functions");
        if (functions == null || !functions.isArray() || functions.isEmpty()) {
            return false;
        }
        JsonNode tools = body.get("tools");
        return tools == null || !tools.isArray() || tools.isEmpty();
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

    private void nonStreamToClient(Context ctx, InputStream upstreamBody, String model,
                                   boolean junieTextProtocol, String junieTextToolName,
                                   String junieNativeToolName,
                                   boolean legacyFunctionCallProtocol, JsonNode requestBody) throws Exception {
        JsonNode completedResponse = SseCollector.collectCompletedResponse(upstreamBody);

        String upstreamStatus = completedResponse.path("status").asText("");
        if ("failed".equals(upstreamStatus) || "cancelled".equals(upstreamStatus)) {
            String errorMessage = completedResponse.path("error").path("message")
                    .asText("Upstream response " + upstreamStatus + ".");
            requestLogger.logClientResponse(requestId(ctx), 502, errorMessage);
            JsonHelper.toErrorResponse(ctx, errorMessage, 502, "upstream_error");
            return;
        }

        String id = "chatcmpl_" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;

        ObjectNode result = MAPPER.createObjectNode();
        result.put("id", id);
        result.put("object", "chat.completion");
        result.put("created", created);
        result.put("model", model);

        ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);

        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");

        StringBuilder textContent = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        ArrayNode toolCalls = MAPPER.createArrayNode();

        JsonNode output = completedResponse.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                String type = item.path("type").asText("");
                switch (type) {
                    case "message" -> {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode part : content) {
                                if ("output_text".equals(part.path("type").asText())) {
                                    textContent.append(part.path("text").asText(""));
                                }
                            }
                        }
                    }
                    case "function_call" -> {
                        ObjectNode tc = MAPPER.createObjectNode();
                        tc.put("id", item.path("call_id").asText(""));
                        tc.put("type", "function");
                        ObjectNode func = MAPPER.createObjectNode();
                        func.put("name", item.path("name").asText(""));
                        func.put("arguments", item.path("arguments").asText("{}"));
                        tc.set("function", func);
                        toolCalls.add(tc);
                    }
                    case "reasoning" -> appendReasoningSummary(reasoningContent, item);
                }
            }
        }

        String collectedText = truncateAtStopSequence(textContent.toString(), stopSequences(requestBody));

        if (junieTextToolName != null) {
            String content = collectedText;
            if (content.isBlank() && !toolCalls.isEmpty()) {
                content = toolCallText(toolCalls, junieTextToolName);
            }
            message.put("content", JunieCommandProtocolCompat.wrapStreamingText(
                    junieTextToolName, content, requestBody));
            toolCalls.removeAll();
        } else if (!collectedText.isEmpty()) {
            message.put("content", junieTextProtocol
                    ? JunieCommandProtocolCompat.wrapPlainText(collectedText)
                    : collectedText);
        } else {
            message.putNull("content");
        }
        if (!reasoningContent.isEmpty()) {
            message.put("reasoning_content", reasoningContent.toString());
        }
        // Junie requires every assistant turn to contain a tool call. If the model
        // answered with plain text only, synthesize a call to the fallback tool
        // (submit/answer) carrying that text.
        if (junieNativeToolName != null && toolCalls.isEmpty()) {
            toolCalls.add(JunieCommandProtocolCompat.chatToolCall(junieNativeToolName, collectedText));
        }
        if (legacyFunctionCallProtocol && !message.has("function_call") && !toolCalls.isEmpty()) {
            message.set("function_call", toLegacyFunctionCall(toolCalls.get(0)));
        } else if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }

        boolean hasFunctionCall = message.has("function_call");
        String status = completedResponse.path("status").asText("");
        String finishReason = switch (status) {
            case "completed" -> hasFunctionCall ? "function_call" : toolCalls.isEmpty() ? "stop" : "tool_calls";
            case "incomplete" -> "length";
            case "failed", "cancelled" -> "stop";
            default -> hasFunctionCall ? "function_call" : toolCalls.isEmpty() ? "stop" : "tool_calls";
        };

        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        choices.add(choice);
        result.set("choices", choices);

        JsonNode usageNode = completedResponse.get("usage");
        usageTracker.record(ctx.attribute("keyName"),
                usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
        result.set("usage", JsonHelper.toUsage(usageNode));

        String responseBody = MAPPER.writeValueAsString(result);
        requestLogger.logClientResponse(requestId(ctx), 200, responseBody);
        ctx.status(200);
        ctx.contentType(JsonHelper.JSON_CONTENT_TYPE);
        ctx.result(responseBody);
    }

    private String toolCallText(ArrayNode toolCalls, String preferredToolName) {
        String fallback = "";
        for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.path("function");
            String text = JunieCommandProtocolCompat.textFromToolArguments(
                    function.path("name").asText(preferredToolName),
                    function.path("arguments").asText(""));
            if (fallback.isBlank()) {
                fallback = text;
            }
            if (preferredToolName.equals(function.path("name").asText(""))) {
                return text;
            }
        }
        return fallback;
    }

    private ObjectNode toLegacyFunctionCall(JsonNode toolCall) {
        JsonNode function = toolCall.path("function");
        ObjectNode legacyFunctionCall = MAPPER.createObjectNode();
        legacyFunctionCall.put("name", function.path("name").asText(""));
        legacyFunctionCall.put("arguments", function.path("arguments").asText("{}"));
        return legacyFunctionCall;
    }

    private void streamToClient(Context ctx, InputStream upstreamBody, String model,
                                String junieTextToolName, String junieNativeToolName,
                                JsonNode requestBody) throws Exception {
        JsonHelper.setSseHeaders(ctx);
        OutputStream os = ctx.res().getOutputStream();

        String id = "chatcmpl_" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;
        Map<String, Integer> toolIndexes = new LinkedHashMap<>();
        Set<Integer> argsEmittedIndexes = new HashSet<>();
        boolean junieStreamingTextFallback = junieTextToolName != null;
        StringBuilder junieTextBuffer = new StringBuilder();
        StringBuilder junieArgumentBuffer = new StringBuilder();
        boolean[] doneSent = {false};
        boolean[] finishSent = {false};

        // Send initial role chunk
        writeSseChunk(ctx, os, createChunk(id, created, model, createAssistantRoleDelta(), null));

        try {
            SseParser.iterateEvents(upstreamBody, event -> {
                try {
                    if (event.data() == null || event.data().isEmpty()) return;
                    if ("[DONE]".equals(event.data())) {
                        // If upstream sends [DONE] without a response.completed event (e.g. on
                        // error mid-stream), emit a synthetic finish chunk so clients don't hang
                        // waiting for a non-null finish_reason.
                        if (!finishSent[0]) {
                            writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), "stop"));
                            finishSent[0] = true;
                        }
                        byte[] doneBytes = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
                        os.write(doneBytes);
                        AccessLogFields.addResponseBytes(ctx, doneBytes.length);
                        os.flush();
                        doneSent[0] = true;
                        return;
                    }

                    JsonNode parsed = MAPPER.readTree(event.data());
                    if (parsed == null || !parsed.isObject()) return;

                    String eventType = parsed.path("type").asText(event.event() != null ? event.event() : "");

                    switch (eventType) {
                        case "response.output_text.delta" -> {
                            String delta = parsed.path("delta").asText("");
                            if (!delta.isEmpty()) {
                                if (junieStreamingTextFallback) {
                                    junieTextBuffer.append(delta);
                                } else {
                                    if (junieNativeToolName != null) {
                                        junieTextBuffer.append(delta);
                                    }
                                    writeSseChunk(ctx, os, createChunk(id, created, model,
                                            createContentDelta(delta), null));
                                }
                            }
                        }
                        case "response.output_item.added" -> {
                            JsonNode item = parsed.get("item");
                            if (item != null && "function_call".equals(item.path("type").asText())) {
                                String callId = item.path("call_id").asText("");
                                String name = item.path("name").asText("");
                                int nextIndex = (int) toolIndexes.values().stream().distinct().count();
                                toolIndexes.put(callId, nextIndex);
                                // Argument delta events reference the output item id ("fc_..."),
                                // not the call id ("call_..."); register both.
                                String itemId = item.path("id").asText("");
                                if (!itemId.isEmpty()) {
                                    toolIndexes.put(itemId, nextIndex);
                                }

                                ArrayNode tcArray = MAPPER.createArrayNode();
                                ObjectNode tc = MAPPER.createObjectNode();
                                tc.put("index", nextIndex);
                                tc.put("id", callId);
                                tc.put("type", "function");
                                ObjectNode func = MAPPER.createObjectNode();
                                func.put("name", name);
                                func.put("arguments", "");
                                tc.set("function", func);
                                tcArray.add(tc);

                                if (!junieStreamingTextFallback) {
                                    writeSseChunk(ctx, os, createChunk(id, created, model,
                                            createToolCallsDelta(tcArray), null));
                                }
                            }
                        }
                        case "response.function_call_arguments.delta" -> {
                            String callId = parsed.path("call_id").asText(
                                    parsed.path("item_id").asText(""));
                            String argDelta = parsed.path("delta").asText("");
                            Integer index = toolIndexes.get(callId);
                            if (junieStreamingTextFallback && !argDelta.isEmpty()) {
                                junieArgumentBuffer.append(argDelta);
                            } else if (index != null && !argDelta.isEmpty()) {
                                argsEmittedIndexes.add(index);
                                ArrayNode tcArray = MAPPER.createArrayNode();
                                ObjectNode tc = MAPPER.createObjectNode();
                                tc.put("index", index);
                                ObjectNode func = MAPPER.createObjectNode();
                                func.put("arguments", argDelta);
                                tc.set("function", func);
                                tcArray.add(tc);

                                writeSseChunk(ctx, os, createChunk(id, created, model,
                                        createToolCallsDelta(tcArray), null));
                            }
                        }
                        case "response.output_item.done" -> {
                            // Safety net: if no argument deltas were forwarded for this call
                            // (e.g. unexpected event ids), emit the complete arguments from the
                            // finished item so the client never sees a tool call without them.
                            JsonNode item = parsed.get("item");
                            if (item != null && "function_call".equals(item.path("type").asText())
                                    && !junieStreamingTextFallback) {
                                Integer index = toolIndexes.get(item.path("call_id").asText(""));
                                String arguments = item.path("arguments").asText("");
                                if (index != null && !arguments.isEmpty() && !argsEmittedIndexes.contains(index)) {
                                    argsEmittedIndexes.add(index);
                                    ArrayNode tcArray = MAPPER.createArrayNode();
                                    ObjectNode tc = MAPPER.createObjectNode();
                                    tc.put("index", index);
                                    ObjectNode func = MAPPER.createObjectNode();
                                    func.put("arguments", arguments);
                                    tc.set("function", func);
                                    tcArray.add(tc);

                                    writeSseChunk(ctx, os, createChunk(id, created, model,
                                            createToolCallsDelta(tcArray), null));
                                }
                            }
                        }
                        case "response.completed" -> {
                            JsonNode response = parsed.get("response");
                            String status = response != null ? response.path("status").asText("") : "";
                            String fr;
                            if (junieStreamingTextFallback) {
                                String content = truncateAtStopSequence(
                                        junieFallbackContent(response, junieTextToolName,
                                                junieTextBuffer, junieArgumentBuffer),
                                        stopSequences(requestBody));
                                String wrappedContent = JunieCommandProtocolCompat.wrapStreamingText(
                                        junieTextToolName, content, requestBody);
                                requestLogger.logClientResponse(requestId(ctx), 200, wrappedContent);
                                writeSseChunk(ctx, os, createChunk(id, created, model,
                                        createContentDelta(wrappedContent), null));
                                fr = "completed".equals(status) ? "stop" : "incomplete".equals(status) ? "length" : "stop";
                            } else if (junieNativeToolName != null && toolIndexes.isEmpty()
                                    && !"incomplete".equals(status)) {
                                // Junie requires a tool call in every assistant turn; synthesize a
                                // fallback tool call from the streamed text when the model sent none.
                                String fallbackText = completedOutputText(response);
                                if (fallbackText.isBlank()) {
                                    fallbackText = junieTextBuffer.toString();
                                }
                                fallbackText = truncateAtStopSequence(fallbackText, stopSequences(requestBody));
                                ObjectNode tc = JunieCommandProtocolCompat.chatToolCall(
                                        junieNativeToolName, fallbackText);
                                tc.put("index", 0);
                                ArrayNode tcArray = MAPPER.createArrayNode();
                                tcArray.add(tc);
                                writeSseChunk(ctx, os, createChunk(id, created, model,
                                        createToolCallsDelta(tcArray), null));
                                fr = "tool_calls";
                            } else {
                                fr = switch (status) {
                                    case "completed" -> toolIndexes.isEmpty() ? "stop" : "tool_calls";
                                    case "incomplete" -> "length";
                                    default -> "stop";
                                };
                            }

                            // Finish chunk
                            writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), fr));
                            finishSent[0] = true;

                            // Usage chunk
                            JsonNode usageNode = response != null ? response.get("usage") : null;
                            usageTracker.record(ctx.attribute("keyName"),
                                    usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                                    usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
                            ObjectNode usageChunk = MAPPER.createObjectNode();
                            usageChunk.put("id", id);
                            usageChunk.put("object", "chat.completion.chunk");
                            usageChunk.put("created", created);
                            usageChunk.put("model", model);
                            usageChunk.set("choices", MAPPER.createArrayNode());
                            usageChunk.set("usage", JsonHelper.toUsage(usageNode));
                            writeSseChunk(ctx, os, usageChunk);
                        }
                        case "response.failed", "response.cancelled" -> {
                            JsonNode response = parsed.get("response");
                            String errorMsg = response != null
                                    ? response.path("error").path("message").asText("Upstream response failed.")
                                    : "Upstream response failed.";
                            // Emit a finish chunk with "stop" so the client stream terminates cleanly,
                            // then write an error SSE event with details.
                            if (!finishSent[0]) {
                                writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), "stop"));
                                finishSent[0] = true;
                            }
                            ObjectNode errPayload = MAPPER.createObjectNode();
                            ObjectNode errObj = MAPPER.createObjectNode();
                            errObj.put("message", errorMsg);
                            errObj.put("type", "upstream_error");
                            errPayload.set("error", errObj);
                            String errLine = "event: error\ndata: " + MAPPER.writeValueAsString(errPayload) + "\n\n";
                            byte[] errorBytes = errLine.getBytes(StandardCharsets.UTF_8);
                            os.write(errorBytes);
                            AccessLogFields.addResponseBytes(ctx, errorBytes.length);
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                } catch (Exception e) {
                    System.err.println("Error processing SSE event: " + e);
                    throw new RuntimeException(e);
                }
            });
        } finally {
            // Guarantee a finish chunk + [DONE] are sent even if the upstream stream
            // ends abnormally (no [DONE] event and no response.completed).
            if (!doneSent[0]) {
                try {
                    if (!finishSent[0]) {
                        writeSseChunk(ctx, os, createChunk(id, created, model, createEmptyDelta(), "stop"));
                    }
                    byte[] doneBytes = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
                    os.write(doneBytes);
                    AccessLogFields.addResponseBytes(ctx, doneBytes.length);
                } catch (Exception ignored) {}
            }
            os.flush();
        }
    }

    private static List<String> stopSequences(JsonNode body) {
        JsonNode stop = body != null ? body.get("stop") : null;
        if (stop == null) {
            return List.of();
        }
        if (stop.isTextual() && !stop.asText().isEmpty()) {
            return List.of(stop.asText());
        }
        if (stop.isArray()) {
            List<String> sequences = new ArrayList<>();
            for (JsonNode sequence : stop) {
                if (sequence.isTextual() && !sequence.asText().isEmpty()) {
                    sequences.add(sequence.asText());
                }
            }
            return sequences;
        }
        return List.of();
    }

    /**
     * The upstream Responses API has no `stop` parameter, so emulate it client-side
     * with "stop after" semantics: everything past the first stop sequence is dropped
     * but the sequence itself is kept. Junie relies on the sequence ("</COMMAND>")
     * remaining part of the text because it only re-appends stop sequences when a
     * response carries `finish_details`, which this proxy does not emit.
     */
    private static String truncateAtStopSequence(String text, List<String> stopSequences) {
        int earliestStart = -1;
        int truncateEnd = -1;
        for (String sequence : stopSequences) {
            int start = text.indexOf(sequence);
            if (start >= 0 && (earliestStart < 0 || start < earliestStart)) {
                earliestStart = start;
                truncateEnd = start + sequence.length();
            }
        }
        return truncateEnd >= 0 && truncateEnd < text.length() ? text.substring(0, truncateEnd) : text;
    }

    private static void appendReasoningSummary(StringBuilder target, JsonNode reasoningItem) {
        JsonNode summary = reasoningItem.get("summary");
        if (summary == null || !summary.isArray()) {
            return;
        }
        for (JsonNode part : summary) {
            String text = part.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            if (!target.isEmpty()) {
                target.append('\n');
            }
            target.append(text);
        }
    }

    private String completedOutputText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        JsonNode output = response != null ? response.get("output") : null;
        if (output == null || !output.isArray()) {
            return "";
        }
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText(""))) {
                continue;
            }
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if ("output_text".equals(part.path("type").asText(""))) {
                    text.append(part.path("text").asText(""));
                }
            }
        }
        return text.toString();
    }

    private String junieFallbackContent(JsonNode response, String toolName,
                                        StringBuilder textBuffer, StringBuilder argumentBuffer) {
        String content = completedOutputText(response);
        if (content.isBlank()) {
            content = completedToolArgumentText(response, toolName);
        }
        if (content.isBlank()) {
            content = textBuffer.toString();
        }
        if (content.isBlank()) {
            content = JunieCommandProtocolCompat.textFromToolArguments(toolName, argumentBuffer.toString());
        }
        return content;
    }

    private String completedToolArgumentText(JsonNode response, String toolName) {
        JsonNode output = response != null ? response.get("output") : null;
        if (output == null || !output.isArray()) {
            return "";
        }
        String fallback = "";
        for (JsonNode item : output) {
            if (!"function_call".equals(item.path("type").asText(""))) {
                continue;
            }
            String text = JunieCommandProtocolCompat.textFromToolArguments(
                    item.path("name").asText(toolName),
                    item.path("arguments").asText(""));
            if (fallback.isBlank()) {
                fallback = text;
            }
            if (toolName.equals(item.path("name").asText(""))) {
                return text;
            }
        }
        return fallback;
    }

    private ObjectNode createChunk(String id, long created, String model,
                                    ObjectNode delta, String finishReason) {
        ObjectNode chunk = MAPPER.createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);

        ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        choices.add(choice);
        chunk.set("choices", choices);

        return chunk;
    }

    private ObjectNode createAssistantRoleDelta() {
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("role", "assistant");
        return delta;
    }

    private ObjectNode createContentDelta(String content) {
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("content", content);
        return delta;
    }

    private ObjectNode createToolCallsDelta(ArrayNode toolCalls) {
        ObjectNode delta = MAPPER.createObjectNode();
        delta.set("tool_calls", toolCalls);
        return delta;
    }

    private ObjectNode createEmptyDelta() {
        return MAPPER.createObjectNode();
    }

    private void writeSseChunk(Context ctx, OutputStream os, JsonNode data) throws Exception {
        String line = "data: " + MAPPER.writeValueAsString(data) + "\n\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        os.write(bytes);
        AccessLogFields.addResponseBytes(ctx, bytes.length);
        os.flush();
    }

    private String extractTextContent(JsonNode content) {
        if (content == null) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isObject() && "text".equals(part.path("type").asText())) {
                    String text = part.path("text").asText("");
                    if (!text.isEmpty()) {
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private void addContentParts(ArrayNode target, JsonNode content) {
        if (content == null) return;
        if (content.isTextual()) {
            ObjectNode part = MAPPER.createObjectNode();
            part.put("type", "input_text");
            part.put("text", content.asText());
            target.add(part);
        } else if (content.isArray()) {
            for (JsonNode item : content) {
                if (item.isObject()) {
                    String type = item.path("type").asText("");
                    if ("text".equals(type)) {
                        ObjectNode part = MAPPER.createObjectNode();
                        part.put("type", "input_text");
                        part.put("text", item.path("text").asText(""));
                        target.add(part);
                    } else if ("image_url".equals(type)) {
                        ObjectNode part = MAPPER.createObjectNode();
                        part.put("type", "input_image");
                        JsonNode imageUrl = item.get("image_url");
                        if (imageUrl != null && imageUrl.has("url")) {
                            part.put("url", imageUrl.path("url").asText(""));
                        }
                        // Note: the OpenAI "detail" field ("low"/"high"/"auto") is intentionally
                        // not forwarded — the upstream Responses API does not expose an equivalent
                        // image resolution parameter.
                        target.add(part);
                    }
                }
            }
        }
    }
}
