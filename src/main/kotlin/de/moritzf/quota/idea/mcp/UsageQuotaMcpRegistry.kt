package de.moritzf.quota.idea.mcp

import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.RawResponseRedactor

internal data class UsageQuotaMcpRegistration(
    val emptyMessage: String,
    private val json: (QuotaUsageService, QuotaProviderType) -> String? = { service, type ->
        service.getLastResponseJson(type)
    },
    private val export: (String) -> String = { it },
) {
    fun jsonForAccount(
        service: QuotaUsageService,
        type: QuotaProviderType,
        accountId: String,
    ): String? = (service.getLastResponseJson(accountId) ?: json(service, type))?.let(::exportJson)

    /** A raw provider response as MCP JSON: credentials redacted, then the provider export. */
    fun exportJson(raw: String): String =
        raw.let(RawResponseRedactor::redactText).let(export).let(McpJson::providerJson)
}

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
