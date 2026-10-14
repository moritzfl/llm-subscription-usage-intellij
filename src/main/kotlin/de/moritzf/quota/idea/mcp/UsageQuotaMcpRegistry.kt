package de.moritzf.quota.idea.mcp

import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService

internal data class UsageQuotaMcpRegistration(
    val emptyMessage: String,
    val json: (QuotaUsageService, QuotaProviderType) -> String? = { service, type ->
        service.getLastResponseJson(type)
    },
)

/** Facade over [ProviderCatalog] for MCP quota JSON export. */
internal object UsageQuotaMcpRegistry {
    val all: Map<QuotaProviderType, UsageQuotaMcpRegistration>
        get() =
            ProviderCatalog.all
                .mapNotNull { descriptor -> descriptor.mcpQuota?.let { descriptor.type to it } }
                .toMap()

    fun get(type: QuotaProviderType): UsageQuotaMcpRegistration? =
        ProviderCatalog.get(type).mcpQuota
}
