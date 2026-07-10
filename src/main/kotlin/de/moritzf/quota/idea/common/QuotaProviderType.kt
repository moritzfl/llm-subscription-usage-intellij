package de.moritzf.quota.idea.common

enum class QuotaProviderType(val id: String, val displayName: String) {
    CLAUDE("claude", "Claude"),
    CURSOR("cursor", "Cursor"),
    GITHUB("github", "GitHub Copilot"),
    OPEN_AI("openai", "OpenAI"),
    OPEN_CODE("opencode", "OpenCode"),
    OLLAMA("ollama", "Ollama"),
    SUPERGROK("supergrok", "SuperGrok"),
    ZAI("zai", "Z.ai"),
    MINIMAX("minimax", "MiniMax"),
    KIMI("kimi", "Kimi");

    companion object {
        fun fromId(id: String): QuotaProviderType? = entries.firstOrNull { it.id == id }

        fun fromName(name: String): QuotaProviderType? {
            val normalized = name.trim().uppercase()
            return entries.firstOrNull { it.name == normalized }
        }

        fun alphabeticalOrder(): List<QuotaProviderType> = QuotaProviderRegistry.defaultProviderOrder()

        fun defaultProviderOrder(): List<QuotaProviderType> = QuotaProviderRegistry.defaultProviderOrder()

        /**
         * Preserves [storedOrder] while inserting any newly added providers:
         * alphabetically first providers go to index 0, others go immediately after
         * their alphabetical predecessor when present in the current order.
         */
        fun mergeProviderOrder(storedOrder: List<QuotaProviderType>): List<QuotaProviderType> {
            return QuotaProviderRegistry.mergeProviderOrder(storedOrder)
        }
    }
}
