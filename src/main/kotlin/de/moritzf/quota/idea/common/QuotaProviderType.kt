package de.moritzf.quota.idea.common

enum class QuotaProviderType(val id: String, val displayName: String) {
    CURSOR("cursor", "Cursor"),
    GEMINI("gemini", "Gemini"),
    OPEN_AI("openai", "OpenAI"),
    OPEN_CODE("opencode", "OpenCode"),
    OLLAMA("ollama", "Ollama"),
    ZAI("zai", "Z.ai"),
    MINIMAX("minimax", "MiniMax"),
    KIMI("kimi", "Kimi");

    companion object {
        fun fromId(id: String): QuotaProviderType? = entries.firstOrNull { it.id == id }

        fun fromName(name: String): QuotaProviderType? {
            val normalized = name.trim().uppercase()
            return entries.firstOrNull { it.name == normalized }
        }

        fun alphabeticalOrder(): List<QuotaProviderType> = entries.sortedBy { it.displayName }

        fun defaultProviderOrder(): List<QuotaProviderType> = alphabeticalOrder()

        /**
         * Preserves [storedOrder] while inserting any newly added providers:
         * alphabetically first providers go to index 0, others go immediately after
         * their alphabetical predecessor when present in the current order.
         */
        fun mergeProviderOrder(storedOrder: List<QuotaProviderType>): List<QuotaProviderType> {
            val allProviders = alphabeticalOrder()
            val validStored = storedOrder.filter { it in allProviders }
            if (validStored.isEmpty()) {
                return allProviders
            }

            val result = validStored.toMutableList()
            val missing = allProviders.filter { it !in result }
            for (provider in missing) {
                val providerIndex = allProviders.indexOf(provider)
                if (providerIndex == 0) {
                    result.add(0, provider)
                    continue
                }

                val predecessor = allProviders[providerIndex - 1]
                val insertAfter = result.indexOfLast { it == predecessor }
                val insertIndex = if (insertAfter >= 0) {
                    insertAfter + 1
                } else {
                    val fallback = result.indexOfLast { allProviders.indexOf(it) < providerIndex }
                    if (fallback >= 0) fallback + 1 else 0
                }
                result.add(insertIndex, provider)
            }
            return result
        }
    }
}
