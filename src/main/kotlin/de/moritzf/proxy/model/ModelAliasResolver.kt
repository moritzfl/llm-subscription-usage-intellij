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
    fun clampReasoningEffort(model: String?, requestedEffort: String?): String? {
        if (requestedEffort.isNullOrBlank()) {
            return null
        }
        var effort = requestedEffort.trim().lowercase(Locale.ROOT)
        val modelName = model?.lowercase(Locale.ROOT).orEmpty()
        if (isMiniModel(modelName)) {
            // Tested against the live Codex /responses endpoint: mini models accept only
            // medium/high reasoning. Keep the request-time clamp even if unsupported aliases
            // are not advertised, because clients can send stale or manual reasoning_effort values.
            return when (effort) {
                "high", "xhigh" -> "high"
                else -> "medium"
            }
        }
        if (effort == "minimal") {
            effort = "low"
        }
        if (effort == "none" && isCodexModel(modelName)) {
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
    private fun isLunaModel(modelName: String): Boolean {
        return modelName.startsWith("gpt-5.6-luna")
    }
    private fun supportsXHigh(modelName: String): Boolean {
        // Codex models.json: gpt-5.2+ and the 5.6 family accept xhigh.
        return modelName.startsWith("gpt-5.2") ||
            modelName.startsWith("gpt-5.3") ||
            modelName.startsWith("gpt-5.4") ||
            modelName.startsWith("gpt-5.5") ||
            modelName.startsWith("gpt-5.6")
    }
    private fun supportsMaxUltra(modelName: String): Boolean {
        return modelName.startsWith("gpt-5.6")
    }
    companion object {
        // Junie selects a reasoning tier by sending the model name with a "<base> (<level>)"
        // suffix. Strip the suffix back into base model + effort; the suffix is the user's
        // explicit tier choice, so it takes precedence over separate reasoning_effort.
        private val REASONING_SUFFIX = Pattern.compile(
            "^(.*?)\\s*\\((low|medium|high|xhigh|max|ultra|minimal|none)\\)$",
            Pattern.CASE_INSENSITIVE,
        )
    }
}
