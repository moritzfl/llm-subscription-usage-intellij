package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public class ImagesHandler implements Handler {

    private final CodexHttpClient client;
    private final RequestLogger requestLogger;
    private final UpstreamErrorMapper upstreamErrorMapper = new UpstreamErrorMapper();
    private final String upstreamPath;

    public ImagesHandler(CodexHttpClient client, RequestLogger requestLogger, String upstreamPath) {
        this.client = client;
        this.requestLogger = requestLogger;
        this.upstreamPath = upstreamPath;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String requestId = requestId(ctx);
        String bodyStr = ctx.body();
        requestLogger.logInbound(requestId, ctx, bodyStr);
        JsonNode body;
        try {
            body = RequestValidator.parseJsonObject(ctx, bodyStr);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            RequestValidator.rejectMalformedJson(ctx, e);
            return;
        }
        if (body == null) {
            return;
        }

        AccessLogFields.mode(ctx, "sync");
        HttpResponse<InputStream> upstream = UpstreamRetry.withRetries(
                ctx.header("x-litellm-num-retries"),
                () -> client.request(
                        upstreamPath,
                        "POST",
                        MAPPER.writeValueAsString(body),
                        Map.of("Content-Type", "application/json"),
                        requestId,
                        null));
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode());

        try (InputStream responseStream = upstream.body()) {
            String rawBody = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                UpstreamErrorMapper.MappedUpstreamError mapped = upstreamErrorMapper.map(upstream.statusCode(), rawBody);
                requestLogger.logUpstreamResponse(requestId, mapped.statusCode(), responseHeaders(upstream), mapped.body());
                ctx.status(mapped.statusCode());
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE);
                AccessLogFields.responseBytes(ctx, mapped.body().getBytes(StandardCharsets.UTF_8).length);
                ctx.result(mapped.body());
                return;
            }

            ctx.status(upstream.statusCode());
            ctx.contentType(responseContentType(upstream));
            AccessLogFields.responseBytes(ctx, rawBody.getBytes(StandardCharsets.UTF_8).length);
            ctx.result(rawBody);
        }
    }

    private String requestId(Context ctx) {
        String requestId = ctx.attribute(AccessLogFields.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = requestLogger.nextRequestId();
            ctx.attribute(AccessLogFields.REQUEST_ID, requestId);
        }
        return requestId;
    }

    private static <T> String responseContentType(HttpResponse<T> response) {
        return response.headers()
                .firstValue("Content-Type")
                .orElse(JsonHelper.JSON_CONTENT_TYPE);
    }

    private static <T> Map<String, java.util.List<String>> responseHeaders(HttpResponse<T> response) {
        return response.headers() == null ? Map.of() : response.headers().map();
    }
}
