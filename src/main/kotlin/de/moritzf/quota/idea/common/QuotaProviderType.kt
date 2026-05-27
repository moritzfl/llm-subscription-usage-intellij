package de.moritzf.quota.idea.common

enum class QuotaProviderType(val id: String, val displayName: String) {
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
    }
}
