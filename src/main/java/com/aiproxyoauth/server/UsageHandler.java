package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.Map;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public class UsageHandler implements Handler {

    private final UsageTracker tracker;

    public UsageHandler(UsageTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public void handle(Context ctx) {
        boolean isAdmin = Boolean.TRUE.equals(ctx.attribute("isAdmin"));
        String keyName = ctx.attribute("keyName");

        // Admin sees all keys. Regular keys see only their own. Open mode sees all.
        Map<String, UsageTracker.KeyStats> snapshot;
        if (isAdmin || keyName == null) {
            snapshot = tracker.snapshot();
        } else {
            snapshot = Map.of(keyName, tracker.snapshot().getOrDefault(keyName, new UsageTracker.KeyStats(0, 0)));
        }

        ArrayNode keys = MAPPER.createArrayNode();
        long totalPrompt = 0;
        long totalCompletion = 0;

        for (Map.Entry<String, UsageTracker.KeyStats> entry : snapshot.entrySet()) {
            UsageTracker.KeyStats stats = entry.getValue();
            ObjectNode keyEntry = MAPPER.createObjectNode();
            keyEntry.put("name", entry.getKey());
            keyEntry.put("prompt_tokens", stats.promptTokens());
            keyEntry.put("completion_tokens", stats.completionTokens());
            keyEntry.put("total_tokens", stats.totalTokens());
            keys.add(keyEntry);
            totalPrompt += stats.promptTokens();
            totalCompletion += stats.completionTokens();
        }

        ObjectNode total = MAPPER.createObjectNode();
        total.put("prompt_tokens", totalPrompt);
        total.put("completion_tokens", totalCompletion);
        total.put("total_tokens", totalPrompt + totalCompletion);

        ObjectNode root = MAPPER.createObjectNode();
        root.set("keys", keys);
        root.set("total", total);

        JsonHelper.toJsonResponse(ctx, root);
    }
}
