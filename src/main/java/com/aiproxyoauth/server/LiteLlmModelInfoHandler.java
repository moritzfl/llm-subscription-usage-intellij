package com.aiproxyoauth.server;

import com.aiproxyoauth.model.ModelResolver;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LiteLlmModelInfoHandler implements Handler {

    private final ModelResolver modelResolver;

    public LiteLlmModelInfoHandler(ModelResolver modelResolver) {
        this.modelResolver = modelResolver;
    }

    @Override
    public void handle(Context ctx) {
        try {
            List<String> models = modelResolver.resolveModels();
            List<Map<String, Object>> data = models.stream()
                    .map(LiteLlmModelInfoHandler::modelInfo)
                    .toList();
            JsonHelper.toJsonResponse(ctx, Map.of("data", data));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to load model info.";
            JsonHelper.toErrorResponse(ctx, msg, 502, "upstream_error");
        }
    }

    private static Map<String, Object> modelInfo(String id) {
        Map<String, Object> litellmParams = new LinkedHashMap<>();
        litellmParams.put("model", id);

        Map<String, Object> modelInfo = new LinkedHashMap<>();
        modelInfo.put("id", id);
        modelInfo.put("mode", "chat");
        modelInfo.put("litellm_provider", "openai-codex");
        modelInfo.put("supports_function_calling", true);
        modelInfo.put("supports_parallel_function_calling", false);
        modelInfo.put("supports_tool_choice", true);
        modelInfo.put("supports_vision", true);
        modelInfo.put("supports_prompt_caching", true);
        // Context limits of the GPT-5 family behind the Codex backend; clients such as
        // Junie size their prompts from these fields.
        modelInfo.put("max_input_tokens", 272000);
        modelInfo.put("max_output_tokens", 128000);
        modelInfo.put("max_tokens", 128000);
        modelInfo.put("input_cost_per_token", 0.0);
        modelInfo.put("output_cost_per_token", 0.0);
        modelInfo.put("cache_read_input_token_cost", 0.0);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("model_name", id);
        item.put("litellm_params", litellmParams);
        item.put("model_info", modelInfo);
        return item;
    }
}
