package de.moritzf.quota.idea.mcp

import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.JsonSupport

internal data class UsageQuotaMcpRegistration(
    val emptyMessage: String,
    val json: (QuotaUsageService, QuotaProviderType) -> String? = { service, type ->
        service.getLastResponseJson(type)
    },
)

internal object UsageQuotaMcpRegistry {
    val all: Map<QuotaProviderType, UsageQuotaMcpRegistration> = mapOf(
        QuotaProviderType.CURSOR to UsageQuotaMcpRegistration(
            emptyMessage = "No Cursor usage response available",
            json = { service, type -> service.getLastResponseJson(type)?.let(CursorQuotaClient::normalizeRawJson) },
        ),
        QuotaProviderType.GITHUB to UsageQuotaMcpRegistration("No GitHub usage response available"),
        QuotaProviderType.KIMI to UsageQuotaMcpRegistration("No Kimi usage response available"),
        QuotaProviderType.MINIMAX to UsageQuotaMcpRegistration("No MiniMax usage response available"),
        QuotaProviderType.OLLAMA to UsageQuotaMcpRegistration(
            emptyMessage = "No Ollama usage response available",
            json = { service, _ ->
                val quota = service.getLastQuota(QuotaProviderType.OLLAMA) as? OllamaQuota
                quota?.let { runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), it) }.getOrNull() }
            },
        ),
        QuotaProviderType.OPEN_AI to UsageQuotaMcpRegistration("No usage response available"),
        QuotaProviderType.OPEN_CODE to UsageQuotaMcpRegistration(
            emptyMessage = "No OpenCode usage response available",
            json = { service, _ ->
                val quota = service.getLastQuota(QuotaProviderType.OPEN_CODE) as? OpenCodeQuota
                quota?.let { runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), it) }.getOrNull() }
            },
        ),
        QuotaProviderType.SUPERGROK to UsageQuotaMcpRegistration("No SuperGrok usage response available"),
        QuotaProviderType.ZAI to UsageQuotaMcpRegistration("No Z.ai usage response available"),
    )

    fun get(type: QuotaProviderType): UsageQuotaMcpRegistration = all.getValue(type)
}
