package com.aiproxyoauth.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModelAliasResolver {

    // Junie selects a reasoning tier by sending the model name with a "<base> (<level>)"
    // suffix (it derives the tier menu from the advertised model list). Strip the suffix
    // back into base model + effort; the suffix is the user's explicit tier choice, so it
    // takes precedence over any separately supplied reasoning_effort.
    private static final Pattern REASONING_SUFFIX = Pattern.compile(
            "^(.*?)\\s*\\((low|medium|high|xhigh|minimal|none)\\)$",
            Pattern.CASE_INSENSITIVE
    );

    public record ResolvedModel(String model, String reasoningEffort) {}

    public ResolvedModel resolve(String model) {
        if (model == null) {
            return new ResolvedModel(null, null);
        }
        Matcher suffix = REASONING_SUFFIX.matcher(model.trim());
        if (suffix.matches()) {
            return new ResolvedModel(suffix.group(1).trim(), suffix.group(2).toLowerCase(Locale.ROOT));
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
            // Tested against the live Codex /responses endpoint: codex-mini accepts only
            // medium/high reasoning. Keep the request-time clamp even if unsupported aliases
            // are not advertised, because clients can send stale or manual reasoning_effort values.
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
        // Verified against the live Codex backend: the currently supported gpt-5.3/5.4/5.5
        // families all accept 'xhigh'. (Older families are no longer reachable on a ChatGPT
        // account, so they are not listed.)
        return modelName.startsWith("gpt-5.3")
                || modelName.startsWith("gpt-5.4")
                || modelName.startsWith("gpt-5.5");
    }
}
