package de.moritzf.proxy.model

import java.util.Locale

/**
 * Codex with a ChatGPT account rejects these slugs. They are not advertised, and requests
 * must fail locally instead of being forwarded.
 */
internal object ChatGptSubscriptionModels {
    private val UNSUPPORTED_PREFIXES = listOf(
        "gpt-5.4",
        "gpt-5.2",
        "gpt-5.3-codex",
        "gpt-5.5-pro",
    )

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
