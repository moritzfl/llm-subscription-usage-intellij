package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.ui.popup.ClaudePopupSection
import de.moritzf.quota.idea.ui.popup.CursorPopupSection
import de.moritzf.quota.idea.ui.popup.GitHubPopupSection
import de.moritzf.quota.idea.ui.popup.KimiPopupSection
import de.moritzf.quota.idea.ui.popup.MiniMaxPopupSection
import de.moritzf.quota.idea.ui.popup.MistralPopupSection
import de.moritzf.quota.idea.ui.popup.OllamaPopupSection
import de.moritzf.quota.idea.ui.popup.OpenAiPopupSection
import de.moritzf.quota.idea.ui.popup.OpenCodePopupSection
import de.moritzf.quota.idea.ui.popup.ProviderPopupSection
import de.moritzf.quota.idea.ui.popup.SuperGrokPopupSection
import de.moritzf.quota.idea.ui.popup.ZaiPopupSection
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota
import javax.swing.Icon
import kotlin.math.roundToInt

internal enum class ProviderAuthState {
    AUTHENTICATED,
    UNAUTHENTICATED,
    UNKNOWN,
}

/**
 * Per-provider UI behavior: indicator texts, percentages, popup section, and auth state.
 * New providers add one implementation and register it on [de.moritzf.quota.idea.common.ProviderCatalog].
 */
internal interface ProviderUi {
    val type: QuotaProviderType
    val icon: Icon

    /** Label used in the popup "Updated:" row. */
    val updatedAtLabel: String get() = type.displayName

    fun tooltip(quota: ProviderQuota?, error: String?, accountId: String = type.id): String =
        buildIndicatorTooltip(type, quota, error, authState(accountId), accountTooltipName(type, accountId))

    fun barText(quota: ProviderQuota?, error: String?): String

    fun barText(quota: ProviderQuota?, error: String?, accountId: String): String = barText(quota, error)

    /** Percent for the bar indicator, or -1 when unknown. */
    fun displayPercent(quota: ProviderQuota?, error: String?): Int

    fun displayPercent(quota: ProviderQuota?, error: String?, accountId: String): Int = displayPercent(quota, error)

    /** Percent for the cake icon, or -1 for the unknown icon. */
    fun cakePercent(quota: ProviderQuota?, error: String?): Int = displayPercent(quota, error)

    fun cakePercent(quota: ProviderQuota?, error: String?, accountId: String): Int =
        displayPercent(quota, error, accountId)

    fun periodElapsedFraction(quota: ProviderQuota?, error: String?): Double?
    fun authState(): ProviderAuthState = authState(type.id)
    fun authState(accountId: String): ProviderAuthState
    fun createPopupSection(): ProviderPopupSection
}

internal fun accountTooltipName(type: QuotaProviderType, accountId: String): String? {
    val settings = runCatching { de.moritzf.quota.idea.settings.QuotaSettingsState.getInstance() }.getOrNull()
        ?: return null
    if (!settings.accountTypeHasDuplicates(type)) return null
    return settings.account(accountId)?.name?.trim()?.takeIf { it.isNotEmpty() }
}

/** Facade over [de.moritzf.quota.idea.common.ProviderCatalog] for indicator/popup UI. */
internal object ProviderUiRegistry {
    val all: Map<QuotaProviderType, ProviderUi>
        get() = de.moritzf.quota.idea.common.ProviderCatalog.defaultProviderOrder().associateWith { type ->
            de.moritzf.quota.idea.common.ProviderCatalog.get(type).ui
        }

    fun forType(type: QuotaProviderType): ProviderUi =
        de.moritzf.quota.idea.common.ProviderCatalog.get(type).ui
}

internal object OpenAiUi : ProviderUi {
    override val type = QuotaProviderType.OPEN_AI
    override val icon: Icon get() = QuotaIcons.OPENAI
    override val updatedAtLabel = "Codex"

    override fun barText(quota: ProviderQuota?, error: String?) = barText(quota, error, type.id)

    override fun barText(quota: ProviderQuota?, error: String?, accountId: String) =
        indicatorBarDisplayText(quota as? OpenAiCodexQuota, error, isLoggedIn(accountId))

    override fun displayPercent(quota: ProviderQuota?, error: String?) = displayPercent(quota, error, type.id)

    override fun displayPercent(quota: ProviderQuota?, error: String?, accountId: String) =
        indicatorDisplayPercent(quota as? OpenAiCodexQuota, error, isLoggedIn(accountId))

    override fun cakePercent(quota: ProviderQuota?, error: String?): Int = cakePercent(quota, error, type.id)

    override fun cakePercent(quota: ProviderQuota?, error: String?, accountId: String): Int {
        if (!isLoggedIn(accountId) || error != null) return -1
        val state = indicatorQuotaState(quota as? OpenAiCodexQuota) ?: return -1
        if (state.limitReached) return 100
        return state.window?.let { clampPercent(it.usedPercent.roundToInt()) } ?: -1
    }

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        openAiPeriodElapsedFraction(quota as? OpenAiCodexQuota, error)

    override fun authState(accountId: String) =
        if (isLoggedIn(accountId)) ProviderAuthState.AUTHENTICATED else ProviderAuthState.UNAUTHENTICATED

    override fun createPopupSection() = OpenAiPopupSection()

    private fun isLoggedIn(accountId: String) = QuotaAuthService.getInstance().isLoggedIn(accountId, QuotaProviderType.OPEN_AI)
}

internal object OpenCodeUi : ProviderUi {
    override val type = QuotaProviderType.OPEN_CODE
    override val icon: Icon get() = QuotaIcons.OPENCODE

    override fun barText(quota: ProviderQuota?, error: String?) =
        openCodeBarDisplayText(quota as? OpenCodeQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? OpenCodeQuota)?.let(::openCodeIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        openCodePeriodElapsedFraction(quota as? OpenCodeQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        return if (OpenCodeSessionCookieStore.forAccount(accountId).load() != null) {
            ProviderAuthState.AUTHENTICATED
        } else {
            ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = OpenCodePopupSection()
}

internal object OllamaUi : ProviderUi {
    override val type = QuotaProviderType.OLLAMA
    override val icon: Icon get() = QuotaIcons.OLLAMA

    override fun barText(quota: ProviderQuota?, error: String?) =
        ollamaBarDisplayText(quota as? OllamaQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? OllamaQuota)?.let(::ollamaIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        ollamaPeriodElapsedFraction(quota as? OllamaQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        val store = OllamaApiKeyStore.forAccount(accountId)
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            store.load() != null -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = OllamaPopupSection()
}

internal object ZaiUi : ProviderUi {
    override val type = QuotaProviderType.ZAI
    override val icon: Icon get() = QuotaIcons.ZAI

    override fun barText(quota: ProviderQuota?, error: String?) =
        zaiBarDisplayText(quota as? ZaiQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? ZaiQuota)?.let(::zaiIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        zaiPeriodElapsedFraction(quota as? ZaiQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        val store = ZaiApiKeyStore.forAccount(accountId)
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            store.load() != null -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = ZaiPopupSection()
}

internal object MiniMaxUi : ProviderUi {
    override val type = QuotaProviderType.MINIMAX
    override val icon: Icon get() = QuotaIcons.MINIMAX

    override fun barText(quota: ProviderQuota?, error: String?) =
        miniMaxBarDisplayText(quota as? MiniMaxQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? MiniMaxQuota)?.let(::miniMaxIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        miniMaxPeriodElapsedFraction(quota as? MiniMaxQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        val store = MiniMaxApiKeyStore.forAccount(accountId)
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            !store.load().isNullOrBlank() -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = MiniMaxPopupSection()
}

internal object MistralUi : ProviderUi {
    override val type = QuotaProviderType.MISTRAL
    override val icon: Icon get() = QuotaIcons.MISTRAL

    override fun barText(quota: ProviderQuota?, error: String?) =
        mistralBarDisplayText(quota as? MistralQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? MistralQuota)?.let(::mistralDisplayWindow)?.usagePercent?.roundToInt()?.let(::clampPercent) ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        mistralPeriodElapsedFraction(quota as? MistralQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        val cookies = MistralSessionCookieStore.forAccount(accountId)
        val apiKey = MistralApiKeyStore.forAccount(accountId)
        return when {
            !cookies.isLoaded() && !apiKey.isLoaded() -> ProviderAuthState.UNKNOWN
            !cookies.load().isNullOrBlank() || !apiKey.load().isNullOrBlank() -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = MistralPopupSection()
}

internal object KimiUi : ProviderUi {
    override val type = QuotaProviderType.KIMI
    override val icon: Icon get() = QuotaIcons.KIMI

    override fun barText(quota: ProviderQuota?, error: String?) =
        kimiBarDisplayText(quota as? KimiQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? KimiQuota)?.let(::kimiDisplayWindow)?.usagePercent?.roundToInt()?.let(::clampPercent) ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        kimiPeriodElapsedFraction(quota as? KimiQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        val store = KimiCredentialsStore.forAccount(accountId)
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            store.load()?.isUsable() == true -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = KimiPopupSection()
}

internal object GitHubUi : ProviderUi {
    override val type = QuotaProviderType.GITHUB
    override val icon: Icon get() = QuotaIcons.GITHUB

    override fun barText(quota: ProviderQuota?, error: String?) =
        gitHubBarDisplayText(quota as? GitHubQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? GitHubQuota)?.let(::gitHubDisplayWindow)?.usagePercent?.roundToInt()?.let(::clampPercent) ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        gitHubPeriodElapsedFraction(quota as? GitHubQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        val store = GitHubCredentialsStore.forAccount(accountId)
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            store.load()?.isUsable() == true -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = GitHubPopupSection()
}

internal object CursorUi : ProviderUi {
    override val type = QuotaProviderType.CURSOR
    override val icon: Icon get() = QuotaIcons.CURSOR

    override fun barText(quota: ProviderQuota?, error: String?) =
        cursorBarDisplayText(quota as? CursorQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? CursorQuota)?.let(::cursorIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        cursorPeriodElapsedFraction(quota as? CursorQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        val store = CursorCredentialsStore.forAccount(accountId)
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            store.hasCredentials() -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = CursorPopupSection()
}

internal object SuperGrokUi : ProviderUi {
    override val type = QuotaProviderType.SUPERGROK
    override val icon: Icon get() = QuotaIcons.SUPERGROK

    override fun barText(quota: ProviderQuota?, error: String?) =
        superGrokBarDisplayText(quota as? SuperGrokQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? SuperGrokQuota)?.let(::superGrokIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        superGrokPeriodElapsedFraction(quota as? SuperGrokQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        return if (QuotaAuthService.getInstance().isLoggedIn(accountId, QuotaProviderType.SUPERGROK)) {
            ProviderAuthState.AUTHENTICATED
        } else {
            ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = SuperGrokPopupSection()
}

internal object ClaudeUi : ProviderUi {
    override val type = QuotaProviderType.CLAUDE
    override val icon: Icon get() = QuotaIcons.CLAUDE

    override fun barText(quota: ProviderQuota?, error: String?) =
        claudeBarDisplayText(quota as? ClaudeQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? ClaudeQuota)?.let(::claudeIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        claudePeriodElapsedFraction(quota as? ClaudeQuota, error)

    override fun authState(accountId: String): ProviderAuthState {
        return if (QuotaAuthService.getInstance().isLoggedIn(accountId, QuotaProviderType.CLAUDE)) {
            ProviderAuthState.AUTHENTICATED
        } else {
            ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = ClaudePopupSection()
}
