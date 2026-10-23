package de.moritzf.proxy.model

import java.util.Locale
import kotlin.math.abs

/** OpenAI-style reasoning effort levels, ordered from no reasoning to the most. */
object ReasoningEffort {
    private val SCALE = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /**
     * Returns the [supported] level closest to [requested], so a client's choice is adapted to what
     * the model accepts instead of being dropped. Ties go to the lower level. Returns null for
     * values outside the scale.
     */
    fun nearest(requested: String?, supported: List<String>): String? {
        val index = requested?.let { SCALE.indexOf(it.trim().lowercase(Locale.ROOT)) } ?: -1
        if (index < 0) return null
        return supported
            .filter { it in SCALE }
            .minWithOrNull(
                compareBy<String>({ abs(SCALE.indexOf(it) - index) }, { SCALE.indexOf(it) })
            )
    }
}
