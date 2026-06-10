package com.aiproxyoauth.logging;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class RequestLogger {
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final String REDACTED = "[REDACTED]";

    private final boolean enabled;
    private final Path logDir;

    public RequestLogger(boolean enabled, Path logDir) {
        this.enabled = enabled;
        this.logDir = logDir;
    }

    public String nextRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    public void logInbound(String requestId, Context ctx, String body) {
        if (!enabled) {
            return;
        }
        ObjectNode entry = baseEntry(requestId, "inbound");
        entry.put("method", ctx.method().name());
        entry.put("path", ctx.path());
        entry.put("status", ctx.statusCode());
        entry.set("headers", redactStringHeaders(ctx.headerMap()));
        putBody(entry, body);
        write(entry, requestId, "inbound");
    }

    public void logUpstreamRequest(String requestId, String method, String path, Map<String, String> headers, String body) {
        if (!enabled) {
            return;
        }
        ObjectNode entry = baseEntry(requestId, "upstream_request");
        entry.put("method", method);
        entry.put("path", path);
        entry.set("headers", redactStringHeaders(headers));
        putBody(entry, body);
        write(entry, requestId, "upstream_request");
    }

    public void logUpstreamResponse(
            String requestId,
            int status,
            Map<String, List<String>> headers,
            String bodyPreview
    ) {
        if (!enabled) {
            return;
        }
        ObjectNode entry = baseEntry(requestId, "upstream_response");
        entry.put("status", status);
        entry.set("headers", redactListHeaders(headers));
        putBody(entry, bodyPreview);
        write(entry, requestId, "upstream_response");
    }

    public void logClientResponse(String requestId, int status, String body) {
        if (!enabled) {
            return;
        }
        ObjectNode entry = baseEntry(requestId, "client_response");
        entry.put("status", status);
        putBody(entry, body);
        write(entry, requestId, "client_response");
    }

    private static ObjectNode baseEntry(String requestId, String stage) {
        ObjectNode entry = Json.MAPPER.createObjectNode();
        entry.put("request_id", requestId);
        entry.put("timestamp", Instant.now().toString());
        entry.put("stage", stage);
        return entry;
    }

    private static ObjectNode redactStringHeaders(Map<String, String> headers) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        if (headers == null) {
            return node;
        }
        headers.forEach((name, value) -> node.put(name, isSensitiveHeader(name) ? REDACTED : value));
        return node;
    }

    private static ObjectNode redactListHeaders(Map<String, List<String>> headers) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        if (headers == null) {
            return node;
        }
        headers.forEach((name, values) -> {
            ArrayNode array = node.putArray(name);
            if (values == null) {
                return;
            }
            for (String value : values) {
                array.add(isSensitiveHeader(name) ? REDACTED : value);
            }
        });
        return node;
    }

    private static boolean isSensitiveHeader(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("authorization")
                || normalized.equals("proxy-authorization")
                || normalized.equals("x-api-key")
                || normalized.equals("openai-api-key")
                || normalized.contains("cookie")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("key");
    }

    private static void putBody(ObjectNode entry, String body) {
        BodyCapture capture = captureBody(body);
        entry.put("body", capture.body());
        entry.put("truncated", capture.truncated());
    }

    private static BodyCapture captureBody(String body) {
        if (body == null) {
            return new BodyCapture("", false);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BODY_BYTES) {
            return new BodyCapture(body, false);
        }
        return new BodyCapture(new String(bytes, 0, MAX_BODY_BYTES, StandardCharsets.UTF_8), true);
    }

    private void write(ObjectNode entry, String requestId, String stage) {
        try {
            Files.createDirectories(logDir);
            String safeRequestId = safeFilePart(requestId == null ? "unknown" : requestId);
            Path file = logDir.resolve(safeRequestId + "-" + stage + "-" + Instant.now().toEpochMilli()
                    + "-" + UUID.randomUUID() + ".json");
            Files.writeString(
                    file,
                    Json.MAPPER.writeValueAsString(entry),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            System.err.println("Warning: failed to write request log: " + e.getMessage());
        }
    }

    private static String safeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record BodyCapture(String body, boolean truncated) {
    }
}
