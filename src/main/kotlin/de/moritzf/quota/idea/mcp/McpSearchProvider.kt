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
}