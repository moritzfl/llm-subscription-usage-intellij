package de.moritzf.quota.idea.common

import de.moritzf.quota.shared.ProviderQuota

data class ProviderSnapshot(
    val quota: ProviderQuota?,
    val error: String?,
)

data class QuotaUsageSnapshot(
    val entries: Map<QuotaProviderType, ProviderSnapshot>,
) {
    operator fun get(type: QuotaProviderType): ProviderSnapshot = entries[type] ?: EMPTY

    companion object {
        private val EMPTY = ProviderSnapshot(quota = null, error = null)
    }
}
