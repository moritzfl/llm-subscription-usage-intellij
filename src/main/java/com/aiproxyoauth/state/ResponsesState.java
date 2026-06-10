package com.aiproxyoauth.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded in-memory compatibility cache for Responses API replay references.
 *
 * <p>The cache resolves previous_response_id and item_reference within a single process only.
 * It is not durable storage and should be scoped by the caller before use.
 */
public class ResponsesState {

    private static final int MAX_ITEM_CACHE_SIZE = 2_000;
    private static final int MAX_RESPONSE_CACHE_SIZE = 256;

    private record CachedResponse(ArrayNode input, ArrayNode output) {}

    private final LinkedHashMap<String, JsonNode> items = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JsonNode> eldest) {
            return size() > MAX_ITEM_CACHE_SIZE;
        }
    };

    private final LinkedHashMap<String, CachedResponse> responses = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
            return size() > MAX_RESPONSE_CACHE_SIZE;
        }
    };

    public synchronized boolean requiresCachedState(JsonNode body) {
        if (body.has("previous_response_id") && body.get("previous_response_id").isTextual()) {
            return true;
        }
        JsonNode input = body.get("input");
        if (input != null && input.isArray()) {
            for (JsonNode item : input) {
                if (item.isObject()
                        && "item_reference".equals(item.path("type").asText(null))
                        && item.has("id") && item.get("id").isTextual()) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized ObjectNode expandRequestBody(ObjectNode body) {
        ObjectNode nextBody = body.deepCopy();

        String previousResponseId = null;
        if (body.has("previous_response_id") && body.get("previous_response_id").isTextual()) {
            previousResponseId = body.get("previous_response_id").asText();
        }

        CachedResponse previousHistory = previousResponseId != null
                ? responses.get(previousResponseId) : null;

        JsonNode directInput = body.get("input");
        ArrayNode expandedInput = directInput != null && directInput.isArray()
                ? expandInput((ArrayNode) directInput) : null;

        if (previousHistory != null) {
            ArrayNode combined = Json.MAPPER.createArrayNode();
            combined.addAll(previousHistory.input.deepCopy());
            combined.addAll(previousHistory.output.deepCopy());
            if (expandedInput != null) {
                combined.addAll(expandedInput);
            }
            nextBody.set("input", combined);
            nextBody.remove("previous_response_id");
            return nextBody;
        }

        if (expandedInput != null) {
            nextBody.set("input", expandedInput);
        }

        return nextBody;
    }

    public synchronized void rememberResponse(JsonNode response, JsonNode requestBody) {
        if (response == null || !response.isObject()) {
            return;
        }

        String responseId = response.has("id") && response.get("id").isTextual()
                ? response.get("id").asText() : null;

        JsonNode outputNode = response.get("output");
        ArrayNode output = Json.MAPPER.createArrayNode();
        if (outputNode != null && outputNode.isArray()) {
            for (JsonNode item : outputNode) {
                if (item.isObject()) {
                    output.add(item.deepCopy());
                    String itemId = item.has("id") && item.get("id").isTextual()
                            ? item.get("id").asText() : null;
                    if (itemId != null) {
                        items.remove(itemId);
                        items.put(itemId, item.deepCopy());
                    }
                }
            }
        }

        if (responseId != null && requestBody != null) {
            ArrayNode input = Json.MAPPER.createArrayNode();
            JsonNode inputNode = requestBody.get("input");
            if (inputNode != null && inputNode.isArray()) {
                for (JsonNode item : inputNode) {
                    input.add(item.deepCopy());
                }
            }
            responses.remove(responseId);
            responses.put(responseId, new CachedResponse(input, output));
        }
    }

    // Must be called from a synchronized context (items is not thread-safe on its own)
    private ArrayNode expandInput(ArrayNode input) {
        ArrayNode expanded = Json.MAPPER.createArrayNode();
        for (JsonNode item : input) {
            if (item.isObject()
                    && "item_reference".equals(item.path("type").asText(null))
                    && item.has("id") && item.get("id").isTextual()) {
                String id = item.get("id").asText();
                JsonNode cached = items.get(id);
                if (cached != null) {
                    expanded.add(cached.deepCopy());
                    continue;
                }
            }
            expanded.add(item.deepCopy());
        }
        return expanded;
    }
}
