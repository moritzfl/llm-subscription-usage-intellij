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
        get() =
            ProviderCatalog.all.map {
                QuotaProviderRegistration(
                    it.type,
                    { it.quotaFactory(defaultAccount(it.type)) },
                    it.snapshotCodec,
                )
            }

    fun get(type: QuotaProviderType): QuotaProviderRegistration {
        val descriptor = ProviderCatalog.get(type)
        return QuotaProviderRegistration(
            descriptor.type,
            { descriptor.quotaFactory(defaultAccount(type)) },
            descriptor.snapshotCodec,
        )
    }

    fun getOrNull(type: QuotaProviderType): QuotaProviderRegistration? {
        val descriptor = ProviderCatalog.getOrNull(type) ?: return null
        return QuotaProviderRegistration(
            descriptor.type,
            { descriptor.quotaFactory(defaultAccount(type)) },
            descriptor.snapshotCodec,
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

    fun defaultProviderOrder(): List<QuotaProviderType> = ProviderCatalog.defaultProviderOrder()

    fun defaultProviderOrderStorageValue(): String =
        ProviderCatalog.defaultProviderOrderStorageValue()

    fun mergeProviderOrder(storedOrder: List<QuotaProviderType>): List<QuotaProviderType> =
        ProviderCatalog.mergeProviderOrder(storedOrder)
}
