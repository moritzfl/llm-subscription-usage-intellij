package de.moritzf.quota.idea.ui.indicator

import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.settings.QuotaDisplayMode
import de.moritzf.quota.idea.ui.QuotaUiUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeUsageWindow
import de.moritzf.quota.openai.UsageWindow
import de.moritzf.quota.ollama.OllamaUsageWindow
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlinx.datetime.Instant
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants
import kotlin.math.roundToInt

internal enum class IndicatorQuotaKind {
    CODEX,
    REVIEW,
}

internal data class IndicatorQuotaState(
    val kind: IndicatorQuotaKind,
    val window: UsageWindow?,
    val limitReached: Boolean,
    val allowed: Boolean?,
)

internal data class IndicatorDisplayState(
    val percent: Int,
    val resetsAt: Instant?,
)

private data class OpenCodeIndicatorState(
    val percent: Int,
    val resetInSec: Long,
)

private data class OllamaIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
)

private data class ZaiIndicatorState(
    val percent: Int,
    val resetsAt: Instant?,
)

internal class QuotaIndicatorComponent(
    horizontalPadding: Int,
    private val onClick: (Component, QuotaIndicatorData) -> Unit,
) : BorderLayoutPanel() {
    private val statusIconLabel = createStatusIconLabel()
    private val sourceIconLabel = createSourceIconLabel()
    private val percentageComponent = QuotaPercentageIndicator()
    private var data: QuotaIndicatorData = QuotaIndicatorData.OpenAi(quota = null, error = null)
    private val clickListener = object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            onClick(this@QuotaIndicatorComponent, data)
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, horizontalPadding)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = " "
        addMouseListener(clickListener)
        statusIconLabel.addMouseListener(clickListener)
        sourceIconLabel.addMouseListener(clickListener)
        percentageComponent.addMouseListener(clickListener)
        statusIconLabel.cursor = cursor
        sourceIconLabel.cursor = cursor
        percentageComponent.cursor = cursor
    }

    fun updateUsage(data: QuotaIndicatorData, displayMode: QuotaDisplayMode) {
        this.data = data
        val tooltip = when (data) {
            is QuotaIndicatorData.OpenAi -> buildQuotaTooltipText(data.quota, data.error)
            is QuotaIndicatorData.OpenCode -> buildOpenCodeTooltipText(data.quota, data.error)
            is QuotaIndicatorData.Ollama -> buildOllamaTooltipText(data.quota, data.error)
            is QuotaIndicatorData.Zai -> buildZaiTooltipText(data.quota, data.error)
            is QuotaIndicatorData.MiniMax -> buildMiniMaxTooltipText(data.quota, data.error)
        }
        toolTipText = tooltip
        statusIconLabel.toolTipText = tooltip
        sourceIconLabel.toolTipText = tooltip
        percentageComponent.toolTipText = tooltip

        when (displayMode) {
            QuotaDisplayMode.ICON_ONLY -> {
                statusIconLabel.icon = QuotaIcons.STATUS
                showContent(statusIconLabel)
            }

            QuotaDisplayMode.CAKE_DIAGRAM -> {
                statusIconLabel.icon = scaledCakeIcon(statusIconLabel)
                showContent(statusIconLabel)
            }

            QuotaDisplayMode.PERCENTAGE_BAR -> {
                updatePercentageDisplay()
                val icon = resolveSourceIcon()
                if (icon != null) {
                    sourceIconLabel.icon = scaledSourceIcon(icon, sourceIconLabel)
                    showPercentageContent(sourceIconLabel, percentageComponent)
                } else {
                    showContent(percentageComponent)
                }
            }
        }

        revalidate()
        repaint()
    }

    override fun getToolTipText(event: MouseEvent?): String {
        return when (val currentData = data) {
            is QuotaIndicatorData.OpenAi -> buildQuotaTooltipText(currentData.quota, currentData.error)
            is QuotaIndicatorData.OpenCode -> buildOpenCodeTooltipText(currentData.quota, currentData.error)
            is QuotaIndicatorData.Ollama -> buildOllamaTooltipText(currentData.quota, currentData.error)
            is QuotaIndicatorData.Zai -> buildZaiTooltipText(currentData.quota, currentData.error)
            is QuotaIndicatorData.MiniMax -> buildMiniMaxTooltipText(currentData.quota, currentData.error)
        }
    }

    private fun showContent(component: JComponent) {
        removeAll()
        addToCenter(component)
    }

    private fun showPercentageContent(iconLabel: JComponent, percentageComponent: QuotaPercentageIndicator) {
        removeAll()
        val wrapper = BorderLayoutPanel().apply { isOpaque = false }
        wrapper.addToLeft(iconLabel)
        wrapper.addToCenter(percentageComponent)
        addToCenter(wrapper)
    }

    private fun resolveSourceIcon(): Icon? {
        return when (data) {
            is QuotaIndicatorData.OpenAi -> QuotaIcons.OPENAI
            is QuotaIndicatorData.OpenCode -> QuotaIcons.OPENCODE
            is QuotaIndicatorData.Ollama -> QuotaIcons.OLLAMA
            is QuotaIndicatorData.Zai -> QuotaIcons.ZAI
            is QuotaIndicatorData.MiniMax -> QuotaIcons.MINIMAX
        }
    }

    private fun createStatusIconLabel(): JBLabel {
        return JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
    }

    private fun createSourceIconLabel(): JBLabel {
        return JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyRight(3)
        }
    }

    private fun barDisplayText(): String {
        return when (val currentData = data) {
            is QuotaIndicatorData.OpenAi -> {
                val authService = QuotaAuthService.getInstance()
                indicatorBarDisplayText(currentData.quota, currentData.error, authService.isLoggedIn())
            }
            is QuotaIndicatorData.OpenCode -> openCodeBarDisplayText(currentData.quota, currentData.error)
            is QuotaIndicatorData.Ollama -> ollamaBarDisplayText(currentData.quota, currentData.error)
            is QuotaIndicatorData.Zai -> zaiBarDisplayText(currentData.quota, currentData.error)
            is QuotaIndicatorData.MiniMax -> miniMaxBarDisplayText(currentData.quota, currentData.error)
        }
    }

    private fun cakeIcon(): Icon {
        val currentData = data
        if (currentData is QuotaIndicatorData.OpenCode) {
            return currentData.quota?.let(::openCodeCakeIcon) ?: QuotaIcons.CAKE_UNKNOWN
        }
        if (currentData is QuotaIndicatorData.Ollama) {
            return currentData.quota?.let(::ollamaCakeIcon) ?: QuotaIcons.CAKE_UNKNOWN
        }
        if (currentData is QuotaIndicatorData.Zai) {
            return currentData.quota?.let(::zaiCakeIcon) ?: QuotaIcons.CAKE_UNKNOWN
        }
        if (currentData is QuotaIndicatorData.MiniMax) {
            return currentData.quota?.sessionUsage?.usagePercent?.roundToInt()?.let(::clampPercent)?.let(::cakeIconForPercent) ?: QuotaIcons.CAKE_UNKNOWN
        }
        val openAiData = currentData as QuotaIndicatorData.OpenAi
        val authService = QuotaAuthService.getInstance()
        if (!authService.isLoggedIn() || openAiData.error != null) {
            return QuotaIcons.CAKE_UNKNOWN
        }

        val state = indicatorQuotaState(openAiData.quota) ?: return QuotaIcons.CAKE_UNKNOWN
        if (state.limitReached) {
            return QuotaIcons.CAKE_100
        }
        if (state.allowed == false) {
            return QuotaIcons.CAKE_UNKNOWN
        }

        val percent = state.window?.let { clampPercent(it.usedPercent.roundToInt()) } ?: return QuotaIcons.CAKE_UNKNOWN
        return cakeIconForPercent(percent)
    }

    private fun openCodeCakeIcon(quota: de.moritzf.quota.opencode.OpenCodeQuota): Icon {
        val state = openCodeIndicatorState(quota)
        if (state == null) {
            return QuotaIcons.CAKE_UNKNOWN
        }
        return cakeIconForPercent(state.percent)
    }

    private fun ollamaCakeIcon(quota: de.moritzf.quota.ollama.OllamaQuota): Icon {
        val state = ollamaIndicatorState(quota)
        if (state == null) {
            return QuotaIcons.CAKE_UNKNOWN
        }
        return cakeIconForPercent(state.percent)
    }

    private fun zaiCakeIcon(quota: ZaiQuota): Icon {
        val state = zaiIndicatorState(quota)
        if (state == null) {
            return QuotaIcons.CAKE_UNKNOWN
        }
        return cakeIconForPercent(state.percent)
    }

    private fun cakeIconForPercent(percent: Int): Icon {
        if (percent >= 100) {
            return QuotaIcons.CAKE_100
        }
        if (percent <= 0) {
            return QuotaIcons.CAKE_0
        }

        val bucket = minOf(95, ((percent + 4) / 5) * 5)
        return when (bucket) {
            5 -> QuotaIcons.CAKE_5
            10 -> QuotaIcons.CAKE_10
            15 -> QuotaIcons.CAKE_15
            20 -> QuotaIcons.CAKE_20
            25 -> QuotaIcons.CAKE_25
            30 -> QuotaIcons.CAKE_30
            35 -> QuotaIcons.CAKE_35
            40 -> QuotaIcons.CAKE_40
            45 -> QuotaIcons.CAKE_45
            50 -> QuotaIcons.CAKE_50
            55 -> QuotaIcons.CAKE_55
            60 -> QuotaIcons.CAKE_60
            65 -> QuotaIcons.CAKE_65
            70 -> QuotaIcons.CAKE_70
            75 -> QuotaIcons.CAKE_75
            80 -> QuotaIcons.CAKE_80
            85 -> QuotaIcons.CAKE_85
            90 -> QuotaIcons.CAKE_90
            95 -> QuotaIcons.CAKE_95
            else -> QuotaIcons.CAKE_UNKNOWN
        }
    }

    private fun scaledCakeIcon(component: JComponent): Icon {
        return scaleIconToQuotaStatusSize(cakeIcon(), component)
    }

    private fun scaledSourceIcon(icon: Icon, component: JComponent): Icon {
        val targetSize = JBUI.scale(13)
        val iconWidth = icon.iconWidth
        val iconHeight = icon.iconHeight
        if (iconWidth <= 0 || iconHeight <= 0) {
            return icon
        }
        if (iconWidth <= targetSize && iconHeight <= targetSize) {
            return icon
        }

        val widthScale = targetSize / iconWidth.toFloat()
        val heightScale = targetSize / iconHeight.toFloat()
        return IconUtil.scale(icon, component, minOf(widthScale, heightScale))
    }

    private fun displayPercent(): Int {
        val currentData = data
        if (currentData is QuotaIndicatorData.OpenCode) {
            return currentData.quota?.let(::openCodeIndicatorState)?.percent ?: -1
        }
        if (currentData is QuotaIndicatorData.Ollama) {
            return currentData.quota?.let(::ollamaIndicatorState)?.percent ?: -1
        }
        if (currentData is QuotaIndicatorData.Zai) {
            return currentData.quota?.let(::zaiIndicatorState)?.percent ?: -1
        }
        if (currentData is QuotaIndicatorData.MiniMax) {
            return currentData.quota?.sessionUsage?.usagePercent?.roundToInt()?.let(::clampPercent) ?: -1
        }
        val openAiData = currentData as QuotaIndicatorData.OpenAi
        val authService = QuotaAuthService.getInstance()
        return indicatorDisplayPercent(openAiData.quota, openAiData.error, authService.isLoggedIn())
    }

    private fun updatePercentageDisplay() {
        val percentage = displayPercent()
        val text = barDisplayText()
        if (percentage >= 0) {
            percentageComponent.update(
                text = text,
                fraction = percentage / 100.0,
                fillColor = QuotaUsageColors.usageColor(percentage),
            )
        } else {
            percentageComponent.update(
                text = text,
                fraction = 0.0,
                fillColor = QuotaUsageColors.GRAY,
            )
        }
    }
}

internal fun buildOllamaTooltipText(quota: de.moritzf.quota.ollama.OllamaQuota?, error: String?): String {
    if (error != null) {
        return "Ollama quota: $error"
    }
    if (quota == null) {
        return "Ollama quota: loading"
    }
    val planDisplay = quota.plan.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Free"
    val session = quota.sessionUsage
    val weekly = quota.weeklyUsage
    return when {
        session == null && weekly == null -> "Ollama quota: no usage data"
        else -> {
            val parts = mutableListOf("$planDisplay plan")
            session?.let { parts.add("Session: ${clampPercent(it.usagePercent.roundToInt())}%") }
            weekly?.let { parts.add("Weekly: ${clampPercent(it.usagePercent.roundToInt())}%") }
            "Ollama quota: ${parts.joinToString(" / ")}"
        }
    }
}

internal fun ollamaBarDisplayText(quota: de.moritzf.quota.ollama.OllamaQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val state = ollamaIndicatorState(quota) ?: return "no data"
    val reset = QuotaUiUtil.formatResetCompact(state.resetsAt)
    val text = "${state.percent}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun buildZaiTooltipText(quota: ZaiQuota?, error: String?): String {
    if (error != null) {
        return "Z.ai quota: $error"
    }
    if (quota == null) {
        return "Z.ai quota: loading"
    }
    return when {
        !quota.hasUsageState() -> "Z.ai quota: no usage data"
        else -> {
            val parts = mutableListOf<String>()
            quota.plan.takeIf { it.isNotBlank() }?.let(parts::add)
            quota.sessionUsage?.let { parts.add("Session: ${clampPercent(it.usagePercent.roundToInt())}%") }
            quota.weeklyUsage?.let { parts.add("Weekly: ${clampPercent(it.usagePercent.roundToInt())}%") }
            quota.webSearchUsage?.let { parts.add("Web searches: ${it.used}/${it.limit}") }
            "Z.ai quota: ${parts.joinToString(" / ")}"
        }
    }
}

internal fun buildMiniMaxTooltipText(quota: MiniMaxQuota?, error: String?): String {
    if (error != null) return "MiniMax quota: $error"
    if (quota == null) return "MiniMax quota: loading"
    val usage = quota.sessionUsage ?: return "MiniMax quota: no usage data"
    val plan = quota.plan.ifBlank { "MiniMax Coding Plan (${quota.region})" }
    return "MiniMax quota: $plan / ${usage.used}/${usage.limit} prompts"
}

internal fun miniMaxBarDisplayText(quota: MiniMaxQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."
    val usage = quota.sessionUsage ?: return "no data"
    val reset = QuotaUiUtil.formatResetCompact(usage.resetsAt)
    val text = "${clampPercent(usage.usagePercent.roundToInt())}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun zaiBarDisplayText(quota: ZaiQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val state = zaiIndicatorState(quota) ?: return "no data"
    val reset = QuotaUiUtil.formatResetCompact(state.resetsAt)
    val text = "${state.percent}%"
    return if (reset != null) "$text • $reset" else text
}

internal fun buildQuotaTooltipText(quota: OpenAiCodexQuota?, error: String?): String {
    val authService = QuotaAuthService.getInstance()
    return buildQuotaTooltipText(quota, error, authService.isLoggedIn())
}

internal fun buildOpenCodeTooltipText(quota: OpenCodeQuota?, error: String?): String {
    if (error != null) {
        return "OpenCode quota: $error"
    }
    if (quota == null) {
        return "OpenCode quota: loading"
    }
    val window = quota.rollingUsage ?: quota.weeklyUsage ?: quota.monthlyUsage
    val availableBalance = quota.availableBalance
    return when {
        window == null -> "OpenCode quota: no usage data"
        window.isRateLimited -> "OpenCode quota: rate limited"
        availableBalance != null ->
            "OpenCode quota: ${window.usagePercent}% used, balance $${QuotaUiUtil.formatOpenCodeBalance(availableBalance)}"
        else -> "OpenCode quota: ${window.usagePercent}% used"
    }
}

internal fun openCodeBarDisplayText(quota: OpenCodeQuota?, error: String?): String {
    if (error != null) return "error"
    if (quota == null) return "loading..."

    val state = openCodeIndicatorState(quota)
    return when {
        state == null -> "no data"
        else -> {
            val reset = formatOpenCodeResetTime(state.resetInSec)
            val parts = mutableListOf("${state.percent}%")
            if (reset != null) parts.add(reset)
            parts.joinToString(" \u2022 ")
        }
    }
}

private fun formatOpenCodeResetTime(resetInSec: Long): String? {
    if (resetInSec <= 0) return null
    return QuotaUiUtil.formatCompactDuration(java.time.Duration.ofSeconds(resetInSec))
}

internal fun buildQuotaTooltipText(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): String {
    if (!loggedIn) {
        return "OpenAI usage quota: not logged in"
    }
    if (error != null) {
        return "OpenAI usage quota: $error"
    }

    val state = indicatorQuotaState(quota) ?: return "OpenAI usage quota: loading"
    val label = when (state.kind) {
        IndicatorQuotaKind.CODEX -> "OpenAI usage quota"
        IndicatorQuotaKind.REVIEW -> "OpenAI code review quota"
    }
    return when {
        state.limitReached -> "$label: limit reached"
        state.allowed == false -> "$label: usage not allowed"
        state.window == null -> "$label: available"
        else -> {
            val percent = clampPercent(state.window.usedPercent.roundToInt())
            "$label: $percent% used"
        }
    }
}

internal fun indicatorBarDisplayText(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): String {
    if (!loggedIn) {
        return "not logged in"
    }
    if (error != null) {
        return "error"
    }

    val state = indicatorQuotaState(quota) ?: return "loading..."
    if (state.allowed == false) return "not allowed"

    val display = openAiIndicatorDisplayState(quota, state) ?: return "available"
    val reset = QuotaUiUtil.formatResetCompact(display.resetsAt)
    val text = "${display.percent}%"
    return if (reset != null) "$text \u2022 $reset" else text
}

internal fun indicatorDisplayPercent(quota: OpenAiCodexQuota?, error: String?, loggedIn: Boolean): Int {
    if (!loggedIn || error != null) {
        return -1
    }

    val state = indicatorQuotaState(quota) ?: return -1
    if (state.allowed == false) return -1
    return openAiIndicatorDisplayState(quota, state)?.percent ?: -1
}

private fun openAiIndicatorDisplayState(quota: OpenAiCodexQuota?, state: IndicatorQuotaState): IndicatorDisplayState? {
    if (state.limitReached) {
        return IndicatorDisplayState(
            percent = 100,
            resetsAt = limitingWindow(quota, state.kind)?.resetsAt,
        )
    }

    val window = state.window ?: return null
    val percent = clampPercent(window.usedPercent.roundToInt())
    if (percent >= 100) {
        return IndicatorDisplayState(
            percent = 100,
            resetsAt = limitingWindow(quota, state.kind)?.resetsAt,
        )
    }

    return IndicatorDisplayState(
        percent = percent,
        resetsAt = window.resetsAt,
    )
}

private fun openCodeIndicatorState(quota: OpenCodeQuota): OpenCodeIndicatorState? {
    val windows = listOfNotNull(quota.rollingUsage, quota.weeklyUsage, quota.monthlyUsage)
    if (windows.isEmpty()) return null

    val exhausted = windows.filter { it.isExhausted() }
    if (exhausted.isNotEmpty()) {
        return OpenCodeIndicatorState(
            percent = 100,
            resetInSec = exhausted.maxOf { it.resetInSec },
        )
    }

    val window = windows.first()
    return OpenCodeIndicatorState(
        percent = clampPercent(window.usagePercent),
        resetInSec = window.resetInSec,
    )
}

private fun OpenCodeUsageWindow.isExhausted(): Boolean {
    return isRateLimited || usagePercent >= 100
}

private fun ollamaIndicatorState(quota: de.moritzf.quota.ollama.OllamaQuota): OllamaIndicatorState? {
    val windows = listOfNotNull(quota.sessionUsage, quota.weeklyUsage)
    if (windows.isEmpty()) return null

    val exhausted = windows.filter { it.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        return OllamaIndicatorState(
            percent = 100,
            resetsAt = exhausted.maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }?.resetsAt,
        )
    }

    val window = windows.first()
    return OllamaIndicatorState(
        percent = clampPercent(window.usagePercent.roundToInt()),
        resetsAt = window.resetsAt,
    )
}

private fun zaiIndicatorState(quota: ZaiQuota): ZaiIndicatorState? {
    val windows = listOfNotNull(quota.sessionUsage, quota.weeklyUsage)
    if (windows.isEmpty()) {
        return quota.webSearchUsage?.let {
            ZaiIndicatorState(
                percent = clampPercent(it.usagePercent.roundToInt()),
                resetsAt = it.resetsAt,
            )
        }
    }

    val exhausted = windows.filter { it.usagePercent >= 100.0 }
    if (exhausted.isNotEmpty()) {
        return ZaiIndicatorState(
            percent = 100,
            resetsAt = exhausted.maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }?.resetsAt,
        )
    }

    val window = windows.first()
    return ZaiIndicatorState(
        percent = clampPercent(window.usagePercent.roundToInt()),
        resetsAt = window.resetsAt,
    )
}

internal fun indicatorQuotaState(quota: OpenAiCodexQuota?): IndicatorQuotaState? {
    if (quota == null) {
        return null
    }

    val codexWindow = shortestUsageWindow(listOfNotNull(quota.primary, quota.secondary))
    if (codexWindow != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = codexWindow,
            limitReached = quota.limitReached == true || codexWindow.usedPercent >= 100.0,
            allowed = quota.allowed,
        )
    }

    val reviewWindow = shortestUsageWindow(listOfNotNull(quota.reviewPrimary, quota.reviewSecondary))
    if (isBlockedState(quota.limitReached, quota.allowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = quota.limitReached == true,
            allowed = quota.allowed,
        )
    }

    if (reviewWindow != null) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = reviewWindow,
            limitReached = quota.reviewLimitReached == true || reviewWindow.usedPercent >= 100.0,
            allowed = quota.reviewAllowed,
        )
    }

    if (isBlockedState(quota.reviewLimitReached, quota.reviewAllowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = null,
            limitReached = quota.reviewLimitReached == true,
            allowed = quota.reviewAllowed,
        )
    }

    if (hasAnyState(quota.limitReached, quota.allowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.CODEX,
            window = null,
            limitReached = quota.limitReached == true,
            allowed = quota.allowed,
        )
    }

    if (hasAnyState(quota.reviewLimitReached, quota.reviewAllowed)) {
        return IndicatorQuotaState(
            kind = IndicatorQuotaKind.REVIEW,
            window = null,
            limitReached = quota.reviewLimitReached == true,
            allowed = quota.reviewAllowed,
        )
    }

    return null
}

internal fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

private fun isBlockedState(limitReached: Boolean?, allowed: Boolean?): Boolean {
    return limitReached == true || allowed == false
}

private fun hasAnyState(limitReached: Boolean?, allowed: Boolean?): Boolean {
    return limitReached != null || allowed != null
}

private fun shortestUsageWindow(windows: List<UsageWindow>): UsageWindow? {
    if (windows.isEmpty()) return null
    return windows.minByOrNull { it.windowDuration?.toMinutes() ?: Long.MAX_VALUE }
        ?: windows.first()
}

/**
 * Returns the window that is at 100% usage and has the latest reset time.
 * Falls back to whichever window resets latest if none are explicitly at 100%.
 */
internal fun limitingWindow(
    quota: OpenAiCodexQuota?,
    kind: IndicatorQuotaKind = IndicatorQuotaKind.CODEX,
): UsageWindow? {
    val windows = when (kind) {
        IndicatorQuotaKind.CODEX -> listOfNotNull(quota?.primary, quota?.secondary)
        IndicatorQuotaKind.REVIEW -> listOfNotNull(quota?.reviewPrimary, quota?.reviewSecondary)
    }
    if (windows.isEmpty()) return null
    return windows
        .filter { it.usedPercent >= 100.0 }
        .maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
        ?: windows.maxByOrNull { it.resetsAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
}

internal fun scaleIconToQuotaStatusSize(icon: Icon, component: JComponent): Icon {
    val statusIcon = QuotaIcons.STATUS
    val targetWidth = statusIcon.iconWidth
    val targetHeight = statusIcon.iconHeight
    val iconWidth = icon.iconWidth
    val iconHeight = icon.iconHeight
    if (iconWidth <= 0 || iconHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        return icon
    }
    if (iconWidth <= targetWidth && iconHeight <= targetHeight) {
        return icon
    }

    val widthScale = targetWidth / iconWidth.toFloat()
    val heightScale = targetHeight / iconHeight.toFloat()
    return IconUtil.scale(icon, component, minOf(widthScale, heightScale))
}
