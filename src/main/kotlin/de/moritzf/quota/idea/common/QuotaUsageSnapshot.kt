package de.moritzf.quota.idea.common

import de.moritzf.quota.shared.ProviderQuota

data class ProviderSnapshot(
    val quota: ProviderQuota?,
    val error: String?,
)

data class QuotaUsageSnapshot(
    val entries: Map<QuotaProviderType, ProviderSnapshot>,
    val accountEntries: Map<String, ProviderSnapshot> = emptyMap(),
    val accountTypes: Map<String, QuotaProviderType> = emptyMap(),
) {
    operator fun get(type: QuotaProviderType): ProviderSnapshot = entries[type] ?: EMPTY

    operator fun get(accountId: String): ProviderSnapshot = accountEntries[accountId] ?: EMPTY

    fun forAccount(accountId: String, type: QuotaProviderType?): ProviderSnapshot {
        if (accountId in accountEntries || accountId in accountTypes) {
            return this[accountId]
        }
        return type?.let { this[it] } ?: this[accountId]
    }

    fun updated(
        accountId: String,
        type: QuotaProviderType,
        snapshot: ProviderSnapshot,
    ): QuotaUsageSnapshot {
        return copy(
            entries = entries + (type to snapshot),
            accountEntries = accountEntries + (accountId to snapshot),
            accountTypes = accountTypes + (accountId to type),
        )
    }

    companion object {
        private val EMPTY = ProviderSnapshot(quota = null, error = null)
    }
}
