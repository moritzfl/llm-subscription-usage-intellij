package de.moritzf.quota.idea

enum class QuotaIndicatorSource(private val displayName: String) {
    OPEN_AI("OpenAI"),
    OPEN_CODE("OpenCode"),
    LAST_USED("Last used");

    override fun toString(): String = displayName

    companion object {
        @JvmStatic
        fun fromStorageValue(value: String?): QuotaIndicatorSource {
            if (value.isNullOrBlank()) {
                return OPEN_AI
            }
            val normalized = value.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: OPEN_AI
        }
    }
}