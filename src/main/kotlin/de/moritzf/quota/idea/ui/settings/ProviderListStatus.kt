package de.moritzf.quota.idea.ui.settings

import com.intellij.ui.JBColor
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import java.awt.Color

internal enum class ProviderListStatus(val color: Color) {
    OK(Color(0x4CAF50)),
    ERROR(Color(0xF44336)),
    WARNING(Color(0xFFC107)),
    NEVER_CONFIGURED(JBColor.GRAY);

    companion object {
        fun resolve(
            auth: ProviderAuthState,
            hasQuota: Boolean,
            hasError: Boolean,
        ): ProviderListStatus {
            return when (auth) {
                ProviderAuthState.UNKNOWN,
                ProviderAuthState.UNAUTHENTICATED -> NEVER_CONFIGURED
                ProviderAuthState.AUTHENTICATED ->
                    when {
                        hasQuota && !hasError -> OK
                        hasError && hasQuota -> WARNING
                        hasError -> ERROR
                        else -> WARNING
                    }
            }
        }

        fun explain(status: ProviderListStatus, error: String?): String {
            val detail = error?.trim().orEmpty()
            return when (status) {
                OK -> "Configured"
                NEVER_CONFIGURED -> "Not configured"
                ERROR -> detail.ifBlank { "Quota fetch failed" }
                WARNING ->
                    when {
                        detail.isNotEmpty() -> "Using last good quota. $detail"
                        else -> "Configured, waiting for first quota"
                    }
            }
        }

        /** Current status of [account] from its login state and last quota refresh. */
        fun snapshot(account: ProviderAccount): ProviderListStatusSnapshot {
            val type =
                account.providerType()
                    ?: return ProviderListStatusSnapshot(
                        NEVER_CONFIGURED,
                        explain(NEVER_CONFIGURED, null),
                    )
            val auth = runCatching {
                ProviderUiRegistry.forType(type).authState(account.id)
            }
                .getOrDefault(ProviderAuthState.UNKNOWN)
            if (!ProviderCatalog.get(type).capabilities.quota) {
                return ProviderListStatusSnapshot(
                    if (auth == ProviderAuthState.AUTHENTICATED) OK else NEVER_CONFIGURED,
                    "Proxy access only; quota tracking is unavailable.",
                )
            }
            val service = runCatching { QuotaUsageService.getInstance() }.getOrNull()
            val error = service?.getLastError(account.id)
            val status =
                resolve(
                    auth = auth,
                    hasQuota = service?.getLastQuota(account.id) != null,
                    hasError = !error.isNullOrBlank(),
                )
            return ProviderListStatusSnapshot(status, explain(status, error))
        }
    }
}

internal data class ProviderListStatusSnapshot(
    val status: ProviderListStatus,
    val explanation: String,
)
