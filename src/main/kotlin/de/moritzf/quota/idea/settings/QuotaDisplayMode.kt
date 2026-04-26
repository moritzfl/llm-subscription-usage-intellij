package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation

/**
 * Rendering mode for the quota indicator.
 */
enum class QuotaDisplayMode(private val displayName: String) {
    ICON_ONLY("Icon only"),
    PERCENTAGE_BAR("Percentage bar"),
    CAKE_DIAGRAM("Cake diagram");

    override fun toString(): String = displayName

    companion object {
        @JvmStatic
        fun supportedFor(location: QuotaIndicatorLocation): List<QuotaDisplayMode> {
            return when (location) {
                QuotaIndicatorLocation.STATUS_BAR -> entries
                QuotaIndicatorLocation.MAIN_TOOLBAR -> listOf(ICON_ONLY, CAKE_DIAGRAM)
            }
        }

        @JvmStatic
        fun sanitizeFor(location: QuotaIndicatorLocation, displayMode: QuotaDisplayMode): QuotaDisplayMode {
            return if (displayMode in supportedFor(location)) displayMode else ICON_ONLY
        }

        @JvmStatic
        fun fromStorageValue(value: String?): QuotaDisplayMode {
            if (value.isNullOrBlank()) {
                return ICON_ONLY
            }

            val normalized = value.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: ICON_ONLY
        }
    }
}
