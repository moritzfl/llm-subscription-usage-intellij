package de.moritzf.proxy.model

import java.util.Locale

/** Model catalog and constraints for Codex with a ChatGPT account, not the public OpenAI API. */
internal object ChatGptSubscriptionModels {
    const val DEFAULT_MODEL = "gpt-6.2-sol"

    // Visible base IDs in Codex's bundled models-manager/models.json, in picker priority order.
    // Reserve is a ChatGPT quota fallback, not an entry in the bundled catalog.
    val advertised =
        listOf(
            DEFAULT_MODEL,
            "gpt-6.2-astra",
            "gpt-6.1-terra",
            "gpt-6.1-sol",
            "gpt-6-astra",
            "gpt-6-sol",
            "gpt-6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-reserve",
            "gpt-5.5",
        )

    // These slugs are not advertised and fail locally instead of being forwarded.
    private val UNSUPPORTED_PREFIXES =
        listOf(
            "gpt-5.4",
            "gpt-5.2",
            "gpt-5.3-codex",
            "gpt-5.5-pro",
        )

    private val GPT_6_2_EFFORTS = listOf("low", "medium", "high", "xhigh", "max", "ultra")

    // Use the bundled picker defaults, not the larger opt-in window or public API limits.
    fun maxInputTokens(model: String): Int =
        when (baseName(model)) {
            DEFAULT_MODEL,
            "gpt-6.1-sol",
            "gpt-6.1-terra" -> 500_000
            else -> 272_000
        }

    /** Exact Codex levels for GPT-6.2. Other models keep their existing compatibility rules. */
    fun reasoningEfforts(model: String): List<String>? =
        when (baseName(model)) {
            DEFAULT_MODEL,
            "gpt-6.2-astra" -> GPT_6_2_EFFORTS
            else -> null
        }

    fun isUnsupported(model: String?): Boolean {
        val name = baseName(model)
        if (name.isEmpty()) return false
        return UNSUPPORTED_PREFIXES.any { prefix -> name == prefix || name.startsWith("$prefix-") }
    }

    fun unsupportedMessage(model: String?): String {
        val name = baseName(model).ifEmpty { "model" }
        return "The '$name' model is not supported when using Codex with a ChatGPT account."
    }

    private fun baseName(model: String?): String {
        return model?.trim()?.lowercase(Locale.ROOT)?.substringBefore(" (")?.trim().orEmpty()
    }
}
