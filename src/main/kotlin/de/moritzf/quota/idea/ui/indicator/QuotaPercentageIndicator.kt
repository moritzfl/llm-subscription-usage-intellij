package de.moritzf.quota.idea.ui.indicator

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.SwingConstants
import kotlin.math.roundToInt

internal object QuotaUsageColors {
    val GREEN: Color = JBColor(Color(144, 238, 144), Color(60, 140, 60))
    val YELLOW: Color = JBColor(Color(255, 149, 55), Color(180, 160, 50))
    val RED: Color = JBColor(Color(255, 182, 182), Color(180, 70, 70))
    val GRAY: Color = JBColor(Gray._208, Gray._85)

    fun usageColor(percent: Int): Color {
        return when {
            percent >= 90 -> RED
            percent >= 70 -> YELLOW
            else -> GREEN
        }
    }
}

internal class QuotaPercentageIndicator(
    private val minWidth: Int = 110,
    private val usageBarHeight: Int = JBUI.scale(5),
    private val periodBarHeight: Int = JBUI.scale(5),
) : NonOpaquePanel(VerticalLayout(JBUI.scale(1), 0)) {
    private val textLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyLeft(2)
        font = font.deriveFont(font.size2D - 1f)
    }
    private val usageBar = CompactProgressBar(minWidth - 8, usageBarHeight)
    private val periodBar = CompactProgressBar(minWidth - 8, periodBarHeight, isPeriodTrack = true)

    init {
        add(textLabel)
        add(usageBar)
        add(periodBar)
        periodBar.isVisible = false
    }

    fun update(
        text: String,
        fraction: Double,
        fillColor: Color,
        textColor: Color = DEFAULT_TEXT_COLOR,
        periodElapsedFraction: Double? = null,
    ) {
        textLabel.text = text
        textLabel.foreground = textColor
        usageBar.setFraction(fraction)
        usageBar.fillColor = fillColor

        val showPeriod = periodElapsedFraction != null
        periodBar.isVisible = showPeriod
        if (showPeriod) {
            periodBar.setFraction(periodElapsedFraction)
            periodBar.fillColor = PERIOD_ELAPSED_FILL
        }

        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val textSize = textLabel.preferredSize
        val width = maxOf(textSize.width + 4, minWidth)
        val barBlockHeight = usageBar.preferredSize.height +
            if (periodBar.isVisible) JBUI.scale(1) + periodBar.preferredSize.height else 0
        val height = textSize.height + 1 + barBlockHeight
        return Dimension(width, height)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    private class CompactProgressBar(
        private val barWidth: Int,
        height: Int,
        private val isPeriodTrack: Boolean = false,
    ) : JBPanel<CompactProgressBar>(null) {
        var fillColor: Color = QuotaUsageColors.GREEN
            set(value) {
                field = value
                repaint()
            }

        private var fraction: Double = 0.0

        init {
            isOpaque = false
            preferredSize = Dimension(barWidth, height)
            minimumSize = Dimension(barWidth, height)
            maximumSize = Dimension(Int.MAX_VALUE, height)
        }

        fun setFraction(value: Double) {
            fraction = value.coerceIn(0.0, 1.0)
            revalidate()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val width = width.coerceAtLeast(1)
            val height = height.coerceAtLeast(1)
            val arc = height
            val graphics = g.create() as Graphics2D
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            graphics.color = if (isPeriodTrack) PERIOD_TRACK_BACKGROUND else DEFAULT_PROGRESS_BACKGROUND
            graphics.fillRoundRect(0, 0, width, height, arc, arc)

            val fillWidth = when {
                fraction <= 0.0 -> 0
                fraction >= 1.0 -> width
                else -> maxOf(arc, (width * fraction).roundToInt())
            }
            if (fillWidth > 0) {
                graphics.color = fillColor
                graphics.fillRoundRect(0, 0, fillWidth, height, arc, arc)
            }

            graphics.color = TRACK_BORDER
            graphics.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            graphics.dispose()
        }

        override fun doLayout() = Unit
    }

    private companion object {
        private val DEFAULT_PROGRESS_BACKGROUND = JBColor(Gray._220, Gray._95)
        private val PERIOD_TRACK_BACKGROUND = JBColor(Color(228, 233, 240), Color(52, 58, 68))
        private val PERIOD_ELAPSED_FILL = JBColor(Color(148, 176, 214), Color(88, 112, 148))
        private val TRACK_BORDER = JBColor(Color(190, 198, 210, 120), Color(90, 98, 112, 140))
        private val DEFAULT_TEXT_COLOR = JBColor(Gray._60, Gray._210)
    }
}
