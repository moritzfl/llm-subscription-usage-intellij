package de.moritzf.quota.idea.mcp

import de.moritzf.quota.idea.common.QuotaProviderType

/**
 * Providers that support result-list web search (numbered results with content snippets).
 * Schema generation for the MCP tool parameter derives valid values from this enum.
 */
enum class ListSearchProvider(val providerType: QuotaProviderType) {
    KIMI(QuotaProviderType.KIMI),
    ZAI(QuotaProviderType.ZAI),
    MINIMAX(QuotaProviderType.MINIMAX),
    OLLAMA(QuotaProviderType.OLLAMA),
}

/**
 * Providers that support subscription-backed image generation.
 * Schema generation for the MCP tool parameter derives valid values from this enum.
 */
enum class ImageGenerationProvider(val providerType: QuotaProviderType) {
    OPEN_AI(QuotaProviderType.OPEN_AI),
    SUPERGROK(QuotaProviderType.SUPERGROK),
    MISTRAL(QuotaProviderType.MISTRAL),
    ZAI(QuotaProviderType.ZAI),
    MINIMAX(QuotaProviderType.MINIMAX),
}

enum class SpeechToTextProvider(val providerType: QuotaProviderType) {
    OPEN_AI(QuotaProviderType.OPEN_AI),
    SUPERGROK(QuotaProviderType.SUPERGROK),
    MISTRAL(QuotaProviderType.MISTRAL),
    ZAI(QuotaProviderType.ZAI),
    MINIMAX(QuotaProviderType.MINIMAX),
}

enum class TextToSpeechProvider(val providerType: QuotaProviderType) {
    OPEN_AI(QuotaProviderType.OPEN_AI),
    SUPERGROK(QuotaProviderType.SUPERGROK),
    MISTRAL(QuotaProviderType.MISTRAL),
    MINIMAX(QuotaProviderType.MINIMAX),
}

enum class VideoGenerationProvider(val providerType: QuotaProviderType) {
    SUPERGROK(QuotaProviderType.SUPERGROK),
    ZAI(QuotaProviderType.ZAI),
}

enum class WebFetchProvider(val providerType: QuotaProviderType) {
    OLLAMA(QuotaProviderType.OLLAMA),
    ZAI(QuotaProviderType.ZAI),
}

enum class DocumentToMarkdownProvider(val providerType: QuotaProviderType) {
    MISTRAL(QuotaProviderType.MISTRAL),
    ZAI(QuotaProviderType.ZAI),
    OPEN_AI(QuotaProviderType.OPEN_AI),
    SUPERGROK(QuotaProviderType.SUPERGROK),
}
