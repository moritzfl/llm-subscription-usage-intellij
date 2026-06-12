package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.ProviderQuota

internal data class QuotaIndicatorData(
    val type: QuotaProviderType,
    val quota: ProviderQuota?,
    val error: String?,
)
