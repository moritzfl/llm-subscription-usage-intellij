package com.aiproxyoauth.server;

import com.aiproxyoauth.model.ModelResolver;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.List;
import java.util.Map;

public class ModelsHandler implements Handler {

    private final ModelResolver modelResolver;

    public ModelsHandler(ModelResolver modelResolver) {
        this.modelResolver = modelResolver;
    }

    @Override
    public void handle(Context ctx) {
        try {
            List<String> models = modelResolver.resolveModels();
            List<Map<String, Object>> data = models.stream()
                    .map(id -> Map.<String, Object>of(
                            "id", id,
                            "object", "model",
                            "created", 0,
                            "owned_by", "codex-oauth"
                    ))
                    .toList();
            JsonHelper.toJsonResponse(ctx, Map.of("object", "list", "data", data));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to load models.";
            JsonHelper.toErrorResponse(ctx, msg, 502, "upstream_error");
        }
    }
}
