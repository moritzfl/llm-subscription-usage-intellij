package de.moritzf.quota.idea

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
        fun fromStorageValue(value: String?): QuotaDisplayMode {
            if (value.isNullOrBlank()) {
                return ICON_ONLY
            }

            val normalized = value.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: ICON_ONLY
        }
    }
}
