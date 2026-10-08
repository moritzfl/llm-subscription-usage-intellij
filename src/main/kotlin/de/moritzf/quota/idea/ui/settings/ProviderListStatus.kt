package de.moritzf.quota.idea.ui.settings

import de.moritzf.quota.idea.ui.indicator.ProviderAuthState

internal enum class ProviderListStatus {
    OK,
    ERROR,
    WARNING,
    NEVER_CONFIGURED;

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
                ProviderListStatus.OK -> "Configured"
                ProviderListStatus.NEVER_CONFIGURED -> "Not configured"
                ProviderListStatus.ERROR -> detail.ifBlank { "Quota fetch failed" }
                ProviderListStatus.WARNING ->
                    when {
                        detail.isNotEmpty() -> "Using last good quota. $detail"
                        else -> "Configured, waiting for first quota"
                    }
            }
        }
    }
}
