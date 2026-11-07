package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.common.QuotaBundle.message
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.QuotaPeriodDurations
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.idea.ui.indicator.formatMiniMaxCredits
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

internal class MiniMaxPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel =
        createSectionTitleLabel("MiniMax", QuotaIcons.MINIMAX).apply {
            border = JBUI.Borders.emptyTop(0)
        }
    private val sessionBlock = WindowBlockPanel(3)
    private val weeklyBlock = WindowBlockPanel(5)
    private val videoBlock = WindowBlockPanel(5)
    private val videoWeeklyBlock = WindowBlockPanel(5)
    private val creditsBlock = WindowBlockPanel(5)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(sessionBlock)
        add(weeklyBlock)
        add(videoBlock)
        add(videoWeeklyBlock)
        add(creditsBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? MiniMaxQuota, error, visible)
    }

    private fun updateContent(quota: MiniMaxQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "MiniMax error: $error"
                hideContent()
            }
            quota?.planActive == false -> {
                hideAll()
                showWarnings(quota)
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("MiniMax", message("quota.minimax.credits"))
                sessionBlock.showUnavailable(
                    message("quota.minimax.noPlan.title"),
                    message("quota.minimax.noPlan.info"),
                    message("quota.minimax.noPlan.detail").takeIf { quota.credits == null },
                )
                showCredits(quota)
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = sectionTitle("MiniMax")
                sessionBlock.showLoading("Session")
                weeklyBlock.showLoading("Weekly")
            }
            else -> {
                val limitReached =
                    (quota.sessionUsage?.usagePercent ?: 0.0) >= 100.0 ||
                        (quota.weeklyUsage?.usagePercent ?: 0.0) >= 100.0
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "MiniMax limit reached"
                } else {
                    showWarnings(quota)
                }

                titleLabel.isVisible = true
                titleLabel.text =
                    sectionTitle(
                        "MiniMax",
                        quota.plan.ifBlank { "MiniMax Token Plan (${quota.region})" },
                    )
                quota.sessionUsage?.let {
                    sessionBlock.updateMiniMax(it, "Session", QuotaPeriodDurations.ROLLING_5H)
                } ?: sessionBlock.clear()
                val boost =
                    quota.weeklyBoost?.let {
                        message("quota.minimax.weekly.boost", (it * 100).roundToInt())
                    }
                when {
                    quota.weeklyUsage != null ->
                        weeklyBlock.updateMiniMax(
                            quota.weeklyUsage,
                            "Weekly",
                            QuotaPeriodDurations.WEEKLY,
                            boost,
                        )
                    quota.weeklyUnlimited ->
                        weeklyBlock.showUnavailable(
                            message("quota.minimax.weekly.title"),
                            message("quota.minimax.unlimited"),
                        )
                    else -> weeklyBlock.clear()
                }
                quota.videoUsage?.let {
                    videoBlock.updateMiniMax(
                        it,
                        "Video",
                        QuotaPeriodDurations.ROLLING_5H,
                        prefix = message("quota.minimax.video"),
                    )
                } ?: videoBlock.clear()
                quota.videoWeeklyUsage?.let {
                    videoWeeklyBlock.updateMiniMax(
                        it,
                        "Video",
                        QuotaPeriodDurations.WEEKLY,
                        prefix = message("quota.minimax.video"),
                    )
                } ?: videoWeeklyBlock.clear()
                showCredits(quota)
            }
        }
    }

    private fun showWarnings(quota: MiniMaxQuota) {
        if (quota.warnings.isEmpty()) return
        errorLabel.isVisible = true
        errorLabel.text = quota.warnings.joinToString("; ")
    }

    private fun showCredits(quota: MiniMaxQuota) {
        val credits = quota.credits ?: return creditsBlock.clear()
        val parts =
            listOfNotNull(
                credits.purchased?.let {
                    message("quota.minimax.credits.purchased", formatMiniMaxCredits(it))
                },
                credits.free?.let {
                    message("quota.minimax.credits.free", formatMiniMaxCredits(it))
                },
            )
        creditsBlock.showUnavailable(
            message("quota.minimax.credits"),
            message("quota.minimax.credits.left", formatMiniMaxCredits(credits.remaining)),
            parts.takeIf { it.isNotEmpty() }?.joinToString(" \u2022 "),
        )
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        sessionBlock.isVisible = false
        weeklyBlock.isVisible = false
        videoBlock.isVisible = false
        videoWeeklyBlock.isVisible = false
        creditsBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateMiniMax(
        window: MiniMaxUsageWindow,
        label: String,
        period: java.time.Duration,
        note: String? = null,
        prefix: String? = null,
    ) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (note != null) info += " ($note)"
        if (resetText != null) {
            info += " - $resetText"
        } else {
            QuotaUiUtil.formatCompactDuration(window.periodDuration ?: period)?.let {
                info += " ($it)"
            }
        }
        val title = describeDurationLimitLabel(window.periodDuration ?: period, label)
        update(listOfNotNull(prefix, title).joinToString(" "), info, percent)
    }
}
