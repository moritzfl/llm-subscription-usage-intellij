package de.moritzf.proxy.model

import java.util.Locale

/** OpenAI-style reasoning effort levels, fitted the way LiteLLM's router does. */
object ReasoningEffort {
    /** LiteLLM's advertisement order. `none` is the off switch, not a strength. */
    val LEVELS: List<String> = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    private val STRENGTH = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /**
     * Fits [requested] to the [supported] levels like LiteLLM: the weakest supported level at least
     * as strong as the request, else the strongest, so the caller never gets less reasoning than
     * asked for. A non-`none` request is never rounded to `none`. Unlike LiteLLM, `none` on a model
     * that cannot turn reasoning off becomes its weakest level instead of an upstream error.
     * Returns null for values outside the scale.
     */
    fun fit(requested: String?, supported: List<String>): String? {
        val effort = requested?.trim()?.lowercase(Locale.ROOT) ?: return null
        val ladder = ladder(supported)
        if (effort == "none") return if ("none" in supported) "none" else ladder.firstOrNull()
        val rank = STRENGTH.indexOf(effort)
        if (rank < 0) return null
        if (ladder.isEmpty()) return if ("none" in supported) "none" else null
        return ladder.firstOrNull { STRENGTH.indexOf(it) >= rank } ?: ladder.last()
    }

    /** The least reasoning a model accepts: `none` when it can turn reasoning off. */
    fun lowest(supported: List<String>): String? =
        if ("none" in supported) "none" else ladder(supported).firstOrNull()

    private fun ladder(supported: List<String>): List<String> =
        supported.filter { it in STRENGTH }.distinct().sortedBy { STRENGTH.indexOf(it) }
}
