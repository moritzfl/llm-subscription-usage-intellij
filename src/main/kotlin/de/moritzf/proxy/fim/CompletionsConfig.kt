package de.moritzf.proxy.fim

data class CompletionsConfig(
    val enabled: Boolean = false,
    val modelLocalId: String = "",
    val useChatAdapter: Boolean = true,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val maxRequestsPerMinute: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
    val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS,
    val aliasId: String = FIM_ALIAS_ID,
) {
    val strategy: CompletionsStrategy
        get() = if (useChatAdapter) CompletionsStrategy.CHAT_FIM else CompletionsStrategy.NATIVE_FIM

    fun acceptsModel(requested: String): Boolean {
        val id = requested.trim()
        if (id.isEmpty()) return true
        return id == aliasId || id == modelLocalId.trim()
    }

    companion object {
        const val FIM_ALIAS_ID = "qwen2.5-coder"
        const val DEFAULT_MAX_OUTPUT_TOKENS = 128
        const val MIN_OUTPUT_TOKENS = 16
        const val MAX_OUTPUT_TOKENS = 512
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 20
        const val MIN_REQUESTS_PER_MINUTE = 1
        const val MAX_REQUESTS_PER_MINUTE = 120
        const val DEFAULT_MIN_INTERVAL_MILLIS = 500L
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_MAX_PROMPT_CHARS = 16_000
        val DISABLED = CompletionsConfig()

        fun clampMaxOutputTokens(value: Int): Int = value.coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)

        fun clampMaxRequestsPerMinute(value: Int): Int =
            value.coerceIn(MIN_REQUESTS_PER_MINUTE, MAX_REQUESTS_PER_MINUTE)
    }
}
