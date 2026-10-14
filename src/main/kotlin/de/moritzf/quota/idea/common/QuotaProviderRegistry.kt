package de.moritzf.quota.idea.common

import de.moritzf.quota.shared.ProviderQuota

internal data class QuotaProviderRegistration(
    val type: QuotaProviderType,
    val providerFactory: () -> QuotaProvider,
    val snapshotCodec: QuotaCodec<out ProviderQuota>,
)

/** Facade over [ProviderCatalog] for quota factories and provider order. */
internal object QuotaProviderRegistry {
    val all: List<QuotaProviderRegistration>
        get() = ProviderCatalog.all.mapNotNull { getOrNull(it.type) }

    fun get(type: QuotaProviderType): QuotaProviderRegistration {
        return requireNotNull(getOrNull(type)) {
            "${type.displayName} does not provide quota tracking."
        }
    }

    fun getOrNull(type: QuotaProviderType): QuotaProviderRegistration? {
        val descriptor = ProviderCatalog.getOrNull(type) ?: return null
        val factory = descriptor.quotaFactory ?: return null
        return QuotaProviderRegistration(
            descriptor.type,
            { factory(defaultAccount(type)) },
            requireNotNull(descriptor.snapshotCodec),
        )
    }

    private fun defaultAccount(
        type: QuotaProviderType
    ): de.moritzf.quota.idea.settings.ProviderAccount {
        return de.moritzf.quota.idea.settings.ProviderAccount.create(
            type,
            type.displayName,
            isFirstOfType = true,
        )
    }

    fun createProviders(): List<QuotaProvider> = ProviderCatalog.createQuotaProviders()

    fun defaultProviderOrder(): List<QuotaProviderType> =
        ProviderCatalog.defaultProviderOrder().filter { ProviderCatalog.get(it).capabilities.quota }

    fun defaultProviderOrderStorageValue(): String =
        ProviderCatalog.defaultProviderOrderStorageValue()

    fun mergeProviderOrder(storedOrder: List<QuotaProviderType>): List<QuotaProviderType> =
        ProviderCatalog.mergeProviderOrder(storedOrder)
}
