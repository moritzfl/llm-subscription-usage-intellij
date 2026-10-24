package de.moritzf.proxy.model

import java.util.Locale
import java.util.regex.Pattern

class ModelAliasResolver {
    data class ResolvedModel(
        val model: String?,
        val reasoningEffort: String?,
    )

    fun resolve(model: String?): ResolvedModel {
        if (model == null) {
            return ResolvedModel(null, null)
        }
        val suffix = REASONING_SUFFIX.matcher(model.trim())
        if (suffix.matches()) {
            return ResolvedModel(
                suffix.group(1).trim(),
                suffix.group(2).lowercase(Locale.ROOT),
            )
        }
        return ResolvedModel(model, null)
    }

    /**
     * [chatGptBackend] adds the limits observed on the ChatGPT Codex backend (mini models, `codex`
     * slugs, and `minimal`). Other Responses upstreams, such as Copilot, Azure, or OpenCode, accept
     * the OpenAI API's levels for the same models.
     */
    fun clampReasoningEffort(
        model: String?,
        requestedEffort: String?,
        chatGptBackend: Boolean = true,
    ): String? {
        if (requestedEffort.isNullOrBlank()) {
            return null
        }
        var effort = requestedEffort.trim().lowercase(Locale.ROOT)
        val modelName = model?.lowercase(Locale.ROOT).orEmpty()
        if (chatGptBackend && isMiniModel(modelName)) {
            // Tested against the live Codex /responses endpoint: mini models accept only
            // medium/high reasoning. Keep the request-time clamp even if unsupported aliases
            // are not advertised, because clients can send stale or manual reasoning_effort values.
            return when (effort) {
                "high",
                "xhigh" -> "high"
                else -> "medium"
            }
        }
        if (!chatGptBackend) {
            apiEffortLevels(modelName)?.let {
                return ReasoningEffort.fit(effort, it)
            }
        }
        if (effort == "minimal" && (chatGptBackend || !acceptsMinimal(modelName))) {
            effort = "low"
        }
        if (effort == "none" && chatGptBackend && isCodexModel(modelName)) {
            return "low"
        }
        if ((effort == "max" || effort == "ultra") && !supportsMaxUltra(modelName)) {
            return if (supportsXHigh(modelName)) "xhigh" else "high"
        }
        if (effort == "ultra" && isLunaModel(modelName)) {
            // Luna accepts max but not ultra (codex models.json).
            return "max"
        }
        if (effort == "xhigh" && !supportsXHigh(modelName)) {
            return "high"
        }
        return effort
    }

    private fun isMiniModel(modelName: String): Boolean {
        return modelName.endsWith("-mini") || modelName.contains("codex-mini")
    }

    private fun isCodexModel(modelName: String): Boolean {
        return modelName.contains("codex")
    }

    /** The OpenAI API documents `minimal` for the original GPT-5 models only. */
    private fun acceptsMinimal(modelName: String): Boolean {
        return GPT_5_BASE_MODEL.matches(modelName)
    }

    /**
     * Reasoning levels of the OpenAI API and the OpenCode gateways, observed live on 2026-10-23:
     * Grok 4.6 rejects `none` and `max`, Muse Spark rejects `none`, and GPT-6 rejects `minimal`.
     * Null leaves the model family unknown, which keeps the conservative mapping below.
     */
    private fun apiEffortLevels(modelName: String): List<String>? {
        return when {
            GPT_6_MODEL.containsMatchIn(modelName) ->
                listOf("none", "low", "medium", "high", "xhigh", "max")
            GPT_56_MODEL.containsMatchIn(modelName) ->
                listOf("none", "low", "medium", "high", "xhigh", "max")
            GPT_52_TO_55_MODEL.containsMatchIn(modelName) ->
                listOf("none", "low", "medium", "high", "xhigh")
            modelName.startsWith("gpt-5-mini") || modelName.startsWith("gpt-5-nano") ->
                listOf("low", "medium", "high")
            GPT_5_BASE_MODEL.matches(modelName) -> listOf("minimal", "low", "medium", "high")
            modelName.startsWith("gpt-5.1") -> listOf("none", "low", "medium", "high")
            modelName.startsWith("grok-") -> listOf("low", "medium", "high", "xhigh")
            modelName.startsWith("muse-") ->
                listOf("minimal", "low", "medium", "high", "xhigh", "max")
            modelName.startsWith("mai-") -> listOf("low", "medium", "high")
            else -> null
        }
    }

    private fun isLunaModel(modelName: String): Boolean {
        return modelName.startsWith("gpt-6-luna") ||
            modelName.startsWith("gpt-5.6-luna") ||
            modelName.startsWith("gpt-reserve")
    }

    private fun supportsXHigh(modelName: String): Boolean {
        // Codex models.json: gpt-5.2+ , the 5.6 family, gpt-6, and gpt-reserve accept xhigh.
        return modelName.startsWith("gpt-5.2") ||
            modelName.startsWith("gpt-5.3") ||
            modelName.startsWith("gpt-5.4") ||
            modelName.startsWith("gpt-5.5") ||
            modelName.startsWith("gpt-5.6") ||
            modelName.startsWith("gpt-6") ||
            modelName.startsWith("gpt-reserve")
    }

    private fun supportsMaxUltra(modelName: String): Boolean {
        return modelName.startsWith("gpt-5.6") ||
            modelName.startsWith("gpt-6") ||
            modelName.startsWith("gpt-reserve")
    }

    companion object {
        private val GPT_5_BASE_MODEL = Regex("gpt-5(?:-mini|-nano)?(?:-\\d{4}-\\d{2}-\\d{2})?")
        private val GPT_6_MODEL = Regex("^gpt-[6-9](?:[.-]|$)")
        private val GPT_56_MODEL = Regex("^gpt-5\\.6(?:[.-]|$)")
        private val GPT_52_TO_55_MODEL = Regex("^gpt-5\\.[2-5](?:[.-]|$)")

        // Junie selects a reasoning tier by sending the model name with a "<base> (<level>)"
        // suffix. Strip the suffix back into base model + effort; the suffix is the user's
        // explicit tier choice, so it takes precedence over separate reasoning_effort.
        private val REASONING_SUFFIX =
            Pattern.compile(
                "^(.*?)\\s*\\((low|medium|high|xhigh|max|ultra|minimal|none)\\)$",
                Pattern.CASE_INSENSITIVE,
            )
    }
}
