package de.moritzf.quota.idea.settings

import de.moritzf.proxy.model.CodexReserveHop
import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
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
import kotlin.time.Instant

internal data class OperationQuotaStatus(
    val exhausted: Boolean,
    val limitingPool: String? = null,
    val usagePercent: Double? = null,
    val resetsAt: Instant? = null,
    val reason: String? = null,
    val fetchedAt: Instant? = null,
)

internal object OperationQuota {
    fun status(
        quota: ProviderQuota?,
        capability: AccountCapability = AccountCapability.QUOTA,
        model: String? = null,
    ): OperationQuotaStatus {
        if (quota == null) {
            return OperationQuotaStatus(exhausted = false)
        }
        if (capability == AccountCapability.LIST_VOICES) {
            return OperationQuotaStatus(exhausted = false, fetchedAt = quota.fetchedAt)
        }
        return when (quota) {
            is AntigravityQuota -> fromPercentWindows(
                quota.windows.mapNotNull { window ->
                    window.usagePercent?.let { NamedWindow(window.id, it, window.resetsAt) }
                },
                quota.fetchedAt,
            )
            is OpenAiCodexQuota -> openAi(quota, model)
            is ClaudeQuota -> fromPercentWindows(
                listOfNotNull(
                    quota.fiveHourUsage?.let { NamedWindow("5-hour", it.usagePercent, it.resetsAt) },
                    quota.sevenDayUsage?.let { NamedWindow("weekly", it.usagePercent, it.resetsAt) },
                    quota.sevenDaySonnetUsage?.let { NamedWindow("sonnet", it.usagePercent, it.resetsAt) },
                    quota.sevenDayOpusUsage?.let { NamedWindow("opus", it.usagePercent, it.resetsAt) },
                    quota.routinesUsage?.let { NamedWindow("routines", it.usagePercent, it.resetsAt) },
                ) + quota.scopedLimits.map { NamedWindow(it.label.ifBlank { "scoped" }, it.usagePercent, it.resetsAt) },
                quota.fetchedAt,
            )
            is SuperGrokQuota -> {
                val window = quota.creditUsage
                if (window == null) {
                    OperationQuotaStatus(false, fetchedAt = quota.fetchedAt)
                } else {
                    val exhausted = window.isExhausted()
                    OperationQuotaStatus(
                        exhausted = exhausted,
                        limitingPool = "credits",
                        usagePercent = window.usagePercent,
                        resetsAt = window.resetsAt,
                        reason = if (exhausted) "credits exhausted" else null,
                        fetchedAt = quota.fetchedAt,
                    )
                }
            }
            is OpenCodeQuota -> fromPercentWindows(
                listOfNotNull(quota.rollingUsage, quota.weeklyUsage, quota.monthlyUsage).map { window ->
                    val name = when {
                        window === quota.rollingUsage -> "5-hour"
                        window === quota.weeklyUsage -> "weekly"
                        else -> "monthly"
                    }
                    val percent = if (window.isRateLimited) 100.0 else window.usagePercent
                    NamedWindow(name, percent, null)
                },
                quota.fetchedAt,
            )
            is OllamaQuota -> fromPercentWindows(
                listOfNotNull(
                    quota.sessionUsage?.let { NamedWindow("session", it.usagePercent, it.resetsAt) },
                    quota.weeklyUsage?.let { NamedWindow("weekly", it.usagePercent, it.resetsAt) },
                    quota.monthlyUsage?.let { NamedWindow("monthly", it.usagePercent, it.resetsAt) },
                ),
                quota.fetchedAt,
            )
            is ZaiQuota -> zai(quota, capability)
            is KimiQuota -> fromPercentWindows(
                listOfNotNull(
                    quota.sessionUsage?.let { NamedWindow("session", it.usagePercent, it.resetsAt) },
                    quota.totalUsage?.let { NamedWindow("overall", it.usagePercent, it.resetsAt) },
                ),
                quota.fetchedAt,
            )
            is MiniMaxQuota -> fromPercentWindows(
                listOfNotNull(
                    quota.sessionUsage?.let { NamedWindow("session", it.usagePercent, it.resetsAt) },
                    quota.weeklyUsage?.let { NamedWindow("weekly", it.usagePercent, it.resetsAt) },
                ),
                quota.fetchedAt,
            )
            is MistralQuota -> mistral(quota, capability)
            is GitHubQuota -> fromPercentWindows(
                quota.limitedWindows().map { NamedWindow(it.label.ifBlank { "premium" }, it.usagePercent, it.resetsAt) },
                quota.fetchedAt,
            )
            is CursorQuota -> fromPercentWindows(
                listOfNotNull(
                    quota.planUsage?.let { NamedWindow("included", it.totalPercentUsed, it.billingCycleEnd) },
                    quota.requestUsage?.usagePercent()?.let { NamedWindow("requests", it, null) },
                ),
                quota.fetchedAt,
            )
            else -> OperationQuotaStatus(false, fetchedAt = quota.fetchedAt)
        }
    }

    private fun openAi(
        quota: OpenAiCodexQuota,
        model: String?,
    ): OperationQuotaStatus {
        if (quota.isCreditsDepleted()) {
            return OperationQuotaStatus(
                exhausted = true,
                limitingPool = "credits",
                usagePercent = 100.0,
                reason = "assigned credits depleted",
                fetchedAt = quota.fetchedAt,
            )
        }
        val trimmedModel = model?.trim()?.takeIf { it.isNotEmpty() }
        val luna = trimmedModel?.let(CodexReserveHop::isLunaModel) == true
        val reserve = trimmedModel?.let(CodexReserveHop::isReserveModel) == true
        val extra = quota.extraRateLimits.minByOrNull { it.window.usedPercent }
        val rateExhausted = when {
            reserve -> extra == null || extra.window.usedPercent >= 100.0
            luna -> quota.limitReached == true && !quota.hasUnusedExtraRateLimits()
            trimmedModel != null -> quota.limitReached == true
            else -> quota.limitReached == true && !quota.hasUnusedExtraRateLimits()
        }
        if (rateExhausted) {
            val pool = when {
                reserve -> extra?.id ?: "gpt-reserve"
                luna && quota.hasUnusedExtraRateLimits() -> extra?.id
                else -> "rate_limit"
            }
            return OperationQuotaStatus(
                exhausted = true,
                limitingPool = pool ?: "rate_limit",
                usagePercent = 100.0,
                resetsAt = quota.primary?.resetsAt ?: extra?.window?.resetsAt,
                reason = "rate limit reached",
                fetchedAt = quota.fetchedAt,
            )
        }
        val windows = buildList {
            quota.primary?.let { add(NamedWindow("5-hour", it.usedPercent, it.resetsAt)) }
            quota.secondary?.let { add(NamedWindow("weekly", it.usedPercent, it.resetsAt)) }
            extra?.let { add(NamedWindow(it.id, it.window.usedPercent, it.window.resetsAt)) }
        }
        return fromPercentWindows(windows, quota.fetchedAt).copy(exhausted = false, reason = null)
    }

    private fun zai(quota: ZaiQuota, capability: AccountCapability): OperationQuotaStatus {
        if (capability == AccountCapability.WEB_SEARCH) {
            val search = quota.webSearchUsage
            return if (search == null) {
                OperationQuotaStatus(false, fetchedAt = quota.fetchedAt)
            } else {
                val exhausted = search.usagePercent >= 100.0
                OperationQuotaStatus(
                    exhausted = exhausted,
                    limitingPool = "web_search",
                    usagePercent = search.usagePercent,
                    resetsAt = search.resetsAt,
                    reason = if (exhausted) "web search exhausted" else null,
                    fetchedAt = quota.fetchedAt,
                )
            }
        }
        return fromPercentWindows(
            listOfNotNull(
                quota.sessionUsage?.let { NamedWindow("session", it.usagePercent, it.resetsAt) },
                quota.weeklyUsage?.let { NamedWindow("weekly", it.usagePercent, it.resetsAt) },
            ),
            quota.fetchedAt,
        )
    }

    private fun mistral(quota: MistralQuota, capability: AccountCapability): OperationQuotaStatus {
        val usesVibeQuota = capability == AccountCapability.QUOTA ||
            capability == AccountCapability.PROXY
        if (!usesVibeQuota) {
            return OperationQuotaStatus(
                exhausted = false,
                limitingPool = null,
                reason = "vibe quota does not apply to this operation",
                fetchedAt = quota.fetchedAt,
            )
        }
        return fromPercentWindows(
            listOfNotNull(
                quota.monthlyUsage?.let { NamedWindow("monthly", it.usagePercent, it.resetsAt) },
                quota.tokenUsage?.let { NamedWindow("tokens_per_minute", it.usagePercent, it.resetsAt) },
            ),
            quota.fetchedAt,
        )
    }

    private fun fromPercentWindows(windows: List<NamedWindow>, fetchedAt: Instant?): OperationQuotaStatus {
        if (windows.isEmpty()) {
            return OperationQuotaStatus(false, fetchedAt = fetchedAt)
        }
        val exhausted = windows.filter { it.usagePercent >= 100.0 }
        val pick = if (exhausted.isNotEmpty()) {
            exhausted.maxBy { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
        } else {
            windows.maxBy { it.usagePercent }
        }
        val isExhausted = pick.usagePercent >= 100.0
        return OperationQuotaStatus(
            exhausted = isExhausted,
            limitingPool = pick.name,
            usagePercent = pick.usagePercent,
            resetsAt = pick.resetsAt,
            reason = if (isExhausted) "${pick.name} exhausted" else null,
            fetchedAt = fetchedAt,
        )
    }

    private data class NamedWindow(
        val name: String,
        val usagePercent: Double,
        val resetsAt: Instant?,
    )
}
