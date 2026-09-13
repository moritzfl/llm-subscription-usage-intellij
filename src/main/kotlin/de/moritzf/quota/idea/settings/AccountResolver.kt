package de.moritzf.quota.idea.settings

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.isCreditsDepleted
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota
import java.util.concurrent.ConcurrentHashMap

internal enum class AccountCapability {
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
        if (!allowsFailover(capability) || !isExhausted(default, quotaLookup)) {
            return default
        }
        val failover = accounts.firstOrNull { account ->
            !account.isDefault && account.allowFailover && !isExhausted(account, quotaLookup)
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
    ): ProviderAccount? {
        return try {
            resolve(type, accountParam, capability, settings, quotaLookup)
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
    ): Boolean {
        val stickyUntil = rateLimitedUntilMs[account.id]
        if (stickyUntil != null && stickyUntil > nowMs) {
            return true
        }
        val quota = quotaLookup(account.id) ?: return false
        return isHardStop(quota)
    }

    fun isHardStop(quota: ProviderQuota): Boolean {
        return when (quota) {
            is OpenAiCodexQuota ->
                quota.isCreditsDepleted() ||
                    (quota.limitReached == true && !quota.hasUnusedExtraRateLimits())
            is ClaudeQuota -> (
                listOfNotNull(
                    quota.fiveHourUsage,
                    quota.sevenDayUsage,
                    quota.sevenDaySonnetUsage,
                    quota.sevenDayOpusUsage,
                    quota.routinesUsage,
                ) + quota.scopedLimits
                ).any { it.usagePercent >= 100.0 }
            is SuperGrokQuota -> quota.creditUsage?.isExhausted() == true
            is OpenCodeQuota ->
                listOfNotNull(quota.rollingUsage, quota.weeklyUsage, quota.monthlyUsage)
                    .any { it.isRateLimited || it.usagePercent >= 100 }
            is OllamaQuota ->
                (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.weeklyUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.monthlyUsage?.usagePercent ?: 0.0) >= 100.0
            is ZaiQuota ->
                (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.weeklyUsage?.usagePercent ?: 0.0) >= 100.0
            is KimiQuota ->
                (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.totalUsage?.usagePercent ?: 0.0) >= 100.0
            is MiniMaxQuota ->
                (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.weeklyUsage?.usagePercent ?: 0.0) >= 100.0
            is MistralQuota ->
                (quota.monthlyUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.tokenUsage?.usagePercent ?: 0.0) >= 100.0
            is GitHubQuota -> quota.limitedWindows().any { it.usagePercent >= 100.0 }
            is CursorQuota ->
                (quota.planUsage?.totalPercentUsed ?: 0.0) >= 100.0 ||
                    (quota.requestUsage?.usagePercent() ?: 0.0) >= 100.0
            else -> false
        }
    }

    private fun allowsFailover(capability: AccountCapability): Boolean {
        return capability != AccountCapability.QUOTA &&
            capability != AccountCapability.LIST_VOICES
    }

    private fun accountNames(accounts: List<ProviderAccount>): String {
        return accounts.joinToString(", ") { it.name.ifBlank { it.id } }.ifBlank { "(none)" }
    }
}
