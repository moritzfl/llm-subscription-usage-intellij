package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.shared.ProviderQuota
import java.util.concurrent.ConcurrentHashMap

enum class AccountCapability {
    QUOTA,
    WEB_SEARCH,
    IMAGE_GENERATION,
    VIDEO_GENERATION,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    LIST_VOICES,
    DOCUMENT_TO_MARKDOWN,
    PROXY,
}

internal class AccountResolveException(message: String) : IllegalStateException(message)

internal object AccountResolver {
    private val rateLimitedUntilMs = ConcurrentHashMap<String, Long>()
    private const val RATE_LIMIT_STICKY_MS = 5 * 60 * 1000L

    fun markRateLimited(accountId: String, nowMs: Long = System.currentTimeMillis()) {
        rateLimitedUntilMs[accountId] = nowMs + RATE_LIMIT_STICKY_MS
    }

    fun clearRateLimited(accountId: String) {
        rateLimitedUntilMs.remove(accountId)
    }

    fun clearAllRateLimited() {
        rateLimitedUntilMs.clear()
    }

    fun resolve(
        type: QuotaProviderType,
        accountParam: String? = null,
        capability: AccountCapability = AccountCapability.QUOTA,
        settings: QuotaSettingsState = QuotaSettingsState.getInstance(),
        quotaLookup: (String) -> ProviderQuota? = { id ->
            runCatching { QuotaUsageService.getInstance().getLastQuota(id) }.getOrNull()
        },
        model: String? = null,
    ): ProviderAccount {
        val accounts = settings.accountsOf(type)
        val pinned = accountParam?.trim()?.takeIf { it.isNotEmpty() }
        if (pinned != null) {
            return accounts.firstOrNull { it.id == pinned || it.name.equals(pinned, ignoreCase = true) }
                ?: throw AccountResolveException(
                    "No ${type.displayName} account named '$pinned'. Available: ${accountNames(accounts)}",
                )
        }
        if (accounts.isEmpty()) {
            throw AccountResolveException("No ${type.displayName} account configured.")
        }
        if (accounts.size == 1) {
            return accounts.first()
        }
        val default = settings.defaultAccount(type)
            ?: throw AccountResolveException(
                "Multiple ${type.displayName} accounts; set Default or pass account=. Available: ${accountNames(accounts)}",
            )
        if (!allowsFailover(capability) || !isExhausted(default, quotaLookup, capability = capability, model = model)) {
            return default
        }
        val failover = accounts.firstOrNull { account ->
            !account.isDefault && account.allowFailover &&
                !isExhausted(account, quotaLookup, capability = capability, model = model)
        }
        return failover ?: default
    }

    fun resolveOrNull(
        type: QuotaProviderType,
        accountParam: String? = null,
        capability: AccountCapability = AccountCapability.QUOTA,
        settings: QuotaSettingsState = QuotaSettingsState.getInstance(),
        quotaLookup: (String) -> ProviderQuota? = { id ->
            runCatching { QuotaUsageService.getInstance().getLastQuota(id) }.getOrNull()
        },
        model: String? = null,
    ): ProviderAccount? {
        return try {
            resolve(type, accountParam, capability, settings, quotaLookup, model)
        } catch (_: AccountResolveException) {
            null
        }
    }

    fun isExhausted(
        account: ProviderAccount,
        quotaLookup: (String) -> ProviderQuota? = { id ->
            runCatching { QuotaUsageService.getInstance().getLastQuota(id) }.getOrNull()
        },
        nowMs: Long = System.currentTimeMillis(),
        capability: AccountCapability = AccountCapability.QUOTA,
        model: String? = null,
    ): Boolean {
        val stickyUntil = rateLimitedUntilMs[account.id]
        if (stickyUntil != null && stickyUntil > nowMs) {
            return true
        }
        val quota = quotaLookup(account.id) ?: return false
        return isHardStop(quota, capability, model)
    }

    fun isHardStop(
        quota: ProviderQuota,
        capability: AccountCapability = AccountCapability.QUOTA,
        model: String? = null,
    ): Boolean {
        return OperationQuota.status(quota, capability, model).exhausted
    }

    private fun allowsFailover(capability: AccountCapability): Boolean {
        return capability != AccountCapability.QUOTA &&
            capability != AccountCapability.LIST_VOICES
    }

    private fun accountNames(accounts: List<ProviderAccount>): String {
        return accounts.joinToString(", ") { it.name.ifBlank { it.id } }.ifBlank { "(none)" }
    }
}
