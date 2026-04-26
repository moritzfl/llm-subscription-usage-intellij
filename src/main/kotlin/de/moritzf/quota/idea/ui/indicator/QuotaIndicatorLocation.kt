package de.moritzf.quota.idea.ui.indicator

/**
 * Preferred host for the quota indicator UI.
 */
enum class QuotaIndicatorLocation(private val displayName: String) {
    STATUS_BAR("Status bar"),
    MAIN_TOOLBAR("Main toolbar");

    override fun toString(): String = displayName

    companion object {
        @JvmStatic
        fun fromStorageValue(value: String?): QuotaIndicatorLocation {
            if (value.isNullOrBlank()) {
                return STATUS_BAR
            }

            val normalized = value.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: STATUS_BAR
        }
    }
}
