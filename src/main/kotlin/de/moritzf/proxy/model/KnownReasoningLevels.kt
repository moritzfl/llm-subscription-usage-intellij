package de.moritzf.proxy.model

import java.util.Locale

/**
 * Reasoning levels of well-known model families, for upstreams that do not report them (Azure
 * deployments, OpenCode, Ollama) and for advertising them. Sources (2026-10-24): OpenRouter's
 * `/api/v1/models` `reasoning.supported_efforts`, LiteLLM's model map, and live OpenCode Go and
 * Ollama probes. Null means unknown: the client's value is forwarded unchanged. An empty list means
 * the model rejects `reasoning_effort`.
 */
object KnownReasoningLevels {
    private val NONE_TO_MAX = listOf("none", "low", "medium", "high", "xhigh", "max")
    private val NONE_TO_XHIGH = listOf("none", "low", "medium", "high", "xhigh")
    private val NONE_TO_HIGH = listOf("none", "low", "medium", "high")
    private val LOW_TO_MAX = listOf("low", "medium", "high", "xhigh", "max")
    private val LOW_TO_XHIGH = listOf("low", "medium", "high", "xhigh")
    private val LOW_TO_HIGH = listOf("low", "medium", "high")
    private val MINIMAL_TO_HIGH = listOf("minimal", "low", "medium", "high")

    private val RULES: List<Pair<Regex, List<String>>> =
        listOf(
            // OpenAI
            rule("gpt-6\\.1-sol|gpt-6-astra", LOW_TO_MAX),
            rule("gpt-[6-9]|gpt-5\\.6", NONE_TO_MAX),
            rule("gpt-5-pro", listOf("high")),
            rule("gpt-5\\.[2-9]-pro", listOf("medium", "high", "xhigh")),
            rule("gpt-5\\.2-codex|gpt-5\\.1-codex-max", LOW_TO_XHIGH),
            rule("gpt-5\\.1-codex", LOW_TO_HIGH),
            rule("gpt-5\\.[2-5]", NONE_TO_XHIGH),
            rule("gpt-5\\.1", NONE_TO_HIGH),
            rule("gpt-5(?:-mini|-nano)?(?:-\\d{4}-\\d{2}-\\d{2})?$", MINIMAL_TO_HIGH),
            rule("gpt-oss|o[134](?:-|$)", LOW_TO_HIGH),
            // xAI
            rule("grok-4\\.3", NONE_TO_HIGH),
            rule("grok-4\\.5", LOW_TO_HIGH),
            rule("grok-4\\.[6-9]|grok-[5-9]", LOW_TO_XHIGH),
            // Anthropic: effort arrived with 4.6, xhigh with 4.7.
            rule("claude-[a-z]+-4[.-]6", listOf("low", "medium", "high", "max")),
            rule("claude-[a-z]+-(?:4[.-][7-9]|[5-9])", LOW_TO_MAX),
            // Google
            rule("gemini-3-flash|gemini-3\\.1-flash-lite", listOf("none") + MINIMAL_TO_HIGH),
            rule("gemini-3\\.[56]-flash", MINIMAL_TO_HIGH),
            rule("gemini-3\\.1-pro|gemini-3\\.[7-9]|gemini-[4-9]", LOW_TO_HIGH),
            // Open-weight families
            rule("glm-5\\.3", listOf("low", "high", "max")),
            rule("glm-5\\.2", listOf("none", "high", "xhigh")),
            rule(
                "deepseek-v4\\.1|deepseek-v4-flash-0731|deepseek-v4-pro-0813|deepseek-v4-flash-vision",
                listOf("none", "low", "high", "max"),
            ),
            rule("deepseek-v4-(?:flash|pro)", listOf("none", "high", "xhigh")),
            rule("kimi-k3", listOf("none", "low", "high", "max")),
            rule("kimi-k2\\.7", emptyList()),
            rule("minimax-m3\\.1", LOW_TO_MAX),
            // OpenCode Go probe: thinking switches off with thinking.type disabled.
            rule("minimax-m3", NONE_TO_MAX),
            rule("qwen3\\.8-max", listOf("minimal", "low", "medium", "high", "xhigh")),
            rule("qwen3\\.8-27b", listOf("none", "low", "medium", "xhigh")),
            rule("qwen3\\.8-flash", NONE_TO_MAX),
            rule("muse-spark", listOf("minimal", "low", "medium", "high", "xhigh", "max")),
            rule("mai-", LOW_TO_HIGH),
            rule("mistral-(?:medium-3[.-]5|large-4|small-2603)", listOf("none", "high")),
        )

    fun forModel(modelId: String?): List<String>? {
        val id = normalize(modelId ?: return null)
        if (id.isEmpty()) return null
        return RULES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(id) }?.second
    }

    /**
     * Lowercase, without provider or account prefixes, Ollama `:tag` separators, or Azure-style
     * underscores, so deployment names like `GPT-5.4-mini` or `deepseek-v4-pro:0813` match.
     */
    internal fun normalize(modelId: String): String =
        modelId
            .trim()
            .lowercase(Locale.ROOT)
            .substringAfterLast('/')
            .replace(':', '-')
            .replace('_', '-')

    private fun rule(pattern: String, levels: List<String>) = Regex("^(?:$pattern)") to levels
}
