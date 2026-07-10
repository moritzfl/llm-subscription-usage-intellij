package de.moritzf.quota.idea.ui.indicator

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderRegistry
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.ui.popup.ClaudePopupSection
import de.moritzf.quota.idea.ui.popup.CursorPopupSection
import de.moritzf.quota.idea.ui.popup.GitHubPopupSection
import de.moritzf.quota.idea.ui.popup.KimiPopupSection
import de.moritzf.quota.idea.ui.popup.MiniMaxPopupSection
import de.moritzf.quota.idea.ui.popup.OllamaPopupSection
import de.moritzf.quota.idea.ui.popup.OpenAiPopupSection
import de.moritzf.quota.idea.ui.popup.OpenCodePopupSection
import de.moritzf.quota.idea.ui.popup.ProviderPopupSection
import de.moritzf.quota.idea.ui.popup.SuperGrokPopupSection
import de.moritzf.quota.idea.ui.popup.ZaiPopupSection
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
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
 * New providers add one implementation and register it in [ProviderUiRegistry].
 */
internal interface ProviderUi {
    val type: QuotaProviderType
    val icon: Icon

    /** Label used in the popup "Updated:" row. */
    val updatedAtLabel: String get() = type.displayName

    fun tooltip(quota: ProviderQuota?, error: String?): String
    fun barText(quota: ProviderQuota?, error: String?): String

    /** Percent for the bar indicator, or -1 when unknown. */
    fun displayPercent(quota: ProviderQuota?, error: String?): Int

    /** Percent for the cake icon, or -1 for the unknown icon. */
    fun cakePercent(quota: ProviderQuota?, error: String?): Int = displayPercent(quota, error)

    fun periodElapsedFraction(quota: ProviderQuota?, error: String?): Double?
    fun authState(): ProviderAuthState
    fun createPopupSection(): ProviderPopupSection
}

internal object ProviderUiRegistry {
    private val byType: Map<QuotaProviderType, ProviderUi> = listOf(
        ClaudeUi,
        CursorUi,
        GitHubUi,
        KimiUi,
        MiniMaxUi,
        OllamaUi,
        OpenAiUi,
        OpenCodeUi,
        SuperGrokUi,
        ZaiUi,
    ).associateBy { it.type }

    val all: Map<QuotaProviderType, ProviderUi> = QuotaProviderRegistry.defaultProviderOrder().associateWith { type ->
        byType.getValue(type)
    }

    fun forType(type: QuotaProviderType): ProviderUi = all.getValue(type)
}

internal object OpenAiUi : ProviderUi {
    override val type = QuotaProviderType.OPEN_AI
    override val icon: Icon get() = QuotaIcons.OPENAI
    override val updatedAtLabel = "Codex"

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildQuotaTooltipText(quota as? OpenAiCodexQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        indicatorBarDisplayText(quota as? OpenAiCodexQuota, error, isLoggedIn())

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        indicatorDisplayPercent(quota as? OpenAiCodexQuota, error, isLoggedIn())

    override fun cakePercent(quota: ProviderQuota?, error: String?): Int {
        if (!isLoggedIn() || error != null) return -1
        val state = indicatorQuotaState(quota as? OpenAiCodexQuota) ?: return -1
        if (state.limitReached) return 100
        return state.window?.let { clampPercent(it.usedPercent.roundToInt()) } ?: -1
    }

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        openAiPeriodElapsedFraction(quota as? OpenAiCodexQuota, error)

    override fun authState() =
        if (isLoggedIn()) ProviderAuthState.AUTHENTICATED else ProviderAuthState.UNAUTHENTICATED

    override fun createPopupSection() = OpenAiPopupSection()

    private fun isLoggedIn() = QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.OPEN_AI)
}

internal object OpenCodeUi : ProviderUi {
    override val type = QuotaProviderType.OPEN_CODE
    override val icon: Icon get() = QuotaIcons.OPENCODE

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildOpenCodeTooltipText(quota as? OpenCodeQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        openCodeBarDisplayText(quota as? OpenCodeQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? OpenCodeQuota)?.let(::openCodeIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        openCodePeriodElapsedFraction(quota as? OpenCodeQuota, error)

    override fun authState(): ProviderAuthState {
        return if (OpenCodeSessionCookieStore.getInstance().load() != null) {
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

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildOllamaTooltipText(quota as? OllamaQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        ollamaBarDisplayText(quota as? OllamaQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? OllamaQuota)?.let(::ollamaIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        ollamaPeriodElapsedFraction(quota as? OllamaQuota, error)

    override fun authState(): ProviderAuthState {
        val store = OllamaSessionCookieStore.getInstance()
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            store.loadSessionCookie() != null -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = OllamaPopupSection()
}

internal object ZaiUi : ProviderUi {
    override val type = QuotaProviderType.ZAI
    override val icon: Icon get() = QuotaIcons.ZAI

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildZaiTooltipText(quota as? ZaiQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        zaiBarDisplayText(quota as? ZaiQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? ZaiQuota)?.let(::zaiIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        zaiPeriodElapsedFraction(quota as? ZaiQuota, error)

    override fun authState(): ProviderAuthState {
        val store = ZaiApiKeyStore.getInstance()
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

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildMiniMaxTooltipText(quota as? MiniMaxQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        miniMaxBarDisplayText(quota as? MiniMaxQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? MiniMaxQuota)?.sessionUsage?.usagePercent?.roundToInt()?.let(::clampPercent) ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        miniMaxPeriodElapsedFraction(quota as? MiniMaxQuota, error)

    override fun authState(): ProviderAuthState {
        val store = MiniMaxApiKeyStore.getInstance()
        return when {
            !store.isLoaded() -> ProviderAuthState.UNKNOWN
            !store.load().isNullOrBlank() -> ProviderAuthState.AUTHENTICATED
            else -> ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = MiniMaxPopupSection()
}

internal object KimiUi : ProviderUi {
    override val type = QuotaProviderType.KIMI
    override val icon: Icon get() = QuotaIcons.KIMI

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildKimiTooltipText(quota as? KimiQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        kimiBarDisplayText(quota as? KimiQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? KimiQuota)?.let(::kimiDisplayWindow)?.usagePercent?.roundToInt()?.let(::clampPercent) ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        kimiPeriodElapsedFraction(quota as? KimiQuota, error)

    override fun authState(): ProviderAuthState {
        val store = KimiCredentialsStore.getInstance()
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

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildGitHubTooltipText(quota as? GitHubQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        gitHubBarDisplayText(quota as? GitHubQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? GitHubQuota)?.let(::gitHubDisplayWindow)?.usagePercent?.roundToInt()?.let(::clampPercent) ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        gitHubPeriodElapsedFraction(quota as? GitHubQuota, error)

    override fun authState(): ProviderAuthState {
        val store = GitHubCredentialsStore.getInstance()
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

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildCursorTooltipText(quota as? CursorQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        cursorBarDisplayText(quota as? CursorQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? CursorQuota)?.let(::cursorIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        cursorPeriodElapsedFraction(quota as? CursorQuota, error)

    override fun authState(): ProviderAuthState {
        val store = CursorCredentialsStore.getInstance()
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

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildSuperGrokTooltipText(quota as? SuperGrokQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        superGrokBarDisplayText(quota as? SuperGrokQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? SuperGrokQuota)?.let(::superGrokIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        superGrokPeriodElapsedFraction(quota as? SuperGrokQuota, error)

    override fun authState(): ProviderAuthState {
        return if (QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.SUPERGROK)) {
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

    override fun tooltip(quota: ProviderQuota?, error: String?) =
        buildClaudeTooltipText(quota as? ClaudeQuota, error)

    override fun barText(quota: ProviderQuota?, error: String?) =
        claudeBarDisplayText(quota as? ClaudeQuota, error)

    override fun displayPercent(quota: ProviderQuota?, error: String?) =
        (quota as? ClaudeQuota)?.let(::claudeIndicatorState)?.percent ?: -1

    override fun periodElapsedFraction(quota: ProviderQuota?, error: String?) =
        claudePeriodElapsedFraction(quota as? ClaudeQuota, error)

    override fun authState(): ProviderAuthState {
        return if (QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.CLAUDE)) {
            ProviderAuthState.AUTHENTICATED
        } else {
            ProviderAuthState.UNAUTHENTICATED
        }
    }

    override fun createPopupSection() = ClaudePopupSection()
}
