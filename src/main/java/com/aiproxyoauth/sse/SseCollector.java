package com.aiproxyoauth.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.util.Json;

import java.io.IOException;
import java.io.InputStream;

public final class SseCollector {

    private SseCollector() {}

    public static JsonNode collectCompletedResponse(InputStream input) throws IOException {
        JsonNode latestResponse = null;
        JsonNode latestError = null;
        StringBuilder outputTextDeltas = new StringBuilder();
        java.util.List<JsonNode> completedItems = new java.util.ArrayList<>();

        for (ServerSentEvent event : SseParser.parse(input)) {
            if (event.data() == null || event.data().isEmpty()) {
                continue;
            }

            try {
                JsonNode parsed = Json.MAPPER.readTree(event.data());
                if (parsed == null || !parsed.isObject()) {
                    continue;
                }

                if ("error".equals(event.event())) {
                    latestError = parsed;
                    continue;
                }

                // Only accept the response object from a response.completed event to avoid
                // mistaking partial response objects in other event types for the final result.
                String eventType = parsed.path("type").asText(event.event() != null ? event.event() : "");
                if ("response.output_text.delta".equals(eventType)) {
                    String delta = parsed.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        outputTextDeltas.append(delta);
                    }
                    continue;
                }

                // With store=false the Codex backend sends response.completed with an empty
                // output array; the actual output items (messages, function calls) are only
                // delivered through response.output_item.done events. Collect them so they
                // can be substituted into the completed response below.
                if ("response.output_item.done".equals(eventType)) {
                    JsonNode item = parsed.get("item");
                    if (item != null && item.isObject()) {
                        completedItems.add(item);
                    }
                    continue;
                }

                if ("response.completed".equals(eventType)) {
                    JsonNode response = parsed.get("response");
                    if (response != null && response.isObject()) {
                        latestResponse = response;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (latestResponse != null) {
            if (!hasOutputItems(latestResponse) && !completedItems.isEmpty()) {
                ObjectNode copy = ((ObjectNode) latestResponse).deepCopy();
                ArrayNode output = Json.MAPPER.createArrayNode();
                completedItems.forEach(output::add);
                copy.set("output", output);
                latestResponse = copy;
            }
            if (!outputTextDeltas.isEmpty() && !containsOutputText(latestResponse)) {
                return appendOutputText(latestResponse, outputTextDeltas.toString());
            }
            return latestResponse;
        }

        String errorInfo = latestError != null ? " Last error: " + latestError : "";
        throw new IOException("No completed response found in SSE stream." + errorInfo);
    }

    private static boolean hasOutputItems(JsonNode response) {
        JsonNode output = response.get("output");
        return output != null && output.isArray() && !output.isEmpty();
    }

    private static boolean containsOutputText(JsonNode response) {
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return false;
        }
        for (JsonNode item : output) {
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if ("output_text".equals(part.path("type").asText()) && part.hasNonNull("text")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode appendOutputText(JsonNode response, String text) {
        ObjectNode copy = response.deepCopy();
        ArrayNode output;
        JsonNode existingOutput = copy.get("output");
        if (existingOutput != null && existingOutput.isArray()) {
            output = (ArrayNode) existingOutput;
        } else {
            output = Json.MAPPER.createArrayNode();
            copy.set("output", output);
        }

        ObjectNode message = Json.MAPPER.createObjectNode();
        message.put("type", "message");
        message.put("role", "assistant");

        ArrayNode content = Json.MAPPER.createArrayNode();
        ObjectNode textPart = Json.MAPPER.createObjectNode();
        textPart.put("type", "output_text");
        textPart.put("text", text);
        content.add(textPart);

        message.set("content", content);
        output.add(message);
        return copy;
    }
}
