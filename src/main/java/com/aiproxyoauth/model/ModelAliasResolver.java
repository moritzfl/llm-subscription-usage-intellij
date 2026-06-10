package com.aiproxyoauth.model;

import java.util.Locale;
import java.util.Map;

public final class ModelAliasResolver {

    private static final Map<String, ResolvedModel> ALIASES = Map.ofEntries(
            Map.entry("gpt-5.2-codex-low", new ResolvedModel("gpt-5.2-codex", "low")),
            Map.entry("gpt-5.2-codex-medium", new ResolvedModel("gpt-5.2-codex", "medium")),
            Map.entry("gpt-5.2-codex-high", new ResolvedModel("gpt-5.2-codex", "high")),
            Map.entry("gpt-5.2-codex-xhigh", new ResolvedModel("gpt-5.2-codex", "xhigh")),
            Map.entry("gpt-5.1-codex-max-xhigh", new ResolvedModel("gpt-5.1-codex-max", "xhigh")),
            Map.entry("gpt-5.1-none", new ResolvedModel("gpt-5.1", "none"))
    );

    public record ResolvedModel(String model, String reasoningEffort) {}

    public ResolvedModel resolve(String model) {
        if (model == null) {
            return new ResolvedModel(null, null);
        }
        ResolvedModel resolved = ALIASES.get(model);
        if (resolved != null) {
            return resolved;
        }
        return new ResolvedModel(model, null);
    }

    public String clampReasoningEffort(String model, String requestedEffort) {
        if (requestedEffort == null || requestedEffort.isBlank()) {
            return null;
        }

        String effort = requestedEffort.strip().toLowerCase(Locale.ROOT);
        String modelName = model == null ? "" : model.toLowerCase(Locale.ROOT);

        if (isCodexMini(modelName)) {
            return switch (effort) {
                case "high", "xhigh" -> "high";
                default -> "medium";
            };
        }

        if ("minimal".equals(effort)) {
            effort = "low";
        }

        if ("none".equals(effort) && isCodexModel(modelName)) {
            return "low";
        }

        if ("xhigh".equals(effort) && !supportsXHigh(modelName)) {
            return "high";
        }

        return effort;
    }

    private boolean isCodexMini(String modelName) {
        return modelName.contains("codex-mini");
    }

    private boolean isCodexModel(String modelName) {
        return modelName.contains("codex");
    }

    private boolean supportsXHigh(String modelName) {
        return modelName.equals("gpt-5.2")
                || modelName.startsWith("gpt-5.2-")
                || modelName.equals("gpt-5.1-codex-max");
    }
}
