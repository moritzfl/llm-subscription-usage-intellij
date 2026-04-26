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
import javax.swing.SwingConstants
import kotlin.math.roundToInt

internal object QuotaUsageColors {
    val GREEN: Color = JBColor(Color(144, 238, 144), Color(60, 140, 60))
    val YELLOW: Color = JBColor(Color(255, 245, 157), Color(180, 160, 50))
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
    private val barHeight: Int = 4,
) : NonOpaquePanel(VerticalLayout(1, 0)) {
    private val textLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyLeft(2)
        font = font.deriveFont(font.size2D - 1f)
    }
    private val progressBar = CompactUsageBar(minWidth - 8, barHeight)

    init {
        add(textLabel)
        add(progressBar)
    }

    fun update(text: String, fraction: Double, fillColor: Color, textColor: Color = DEFAULT_TEXT_COLOR) {
        textLabel.text = text
        textLabel.foreground = textColor
        progressBar.setFraction(fraction)
        progressBar.fillColor = fillColor
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val textSize = textLabel.preferredSize
        val width = maxOf(textSize.width + 4, minWidth)
        val height = textSize.height + 1 + progressBar.preferredSize.height
        return Dimension(width, height)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    private class CompactUsageBar(width: Int, height: Int) : JBPanel<CompactUsageBar>(null) {
        var fillColor: Color = QuotaUsageColors.GREEN
            set(value) {
                field = value
                fillPanel.background = value
                repaint()
            }

        private var fraction: Double = 0.0
        private val fillPanel = JBPanel<JBPanel<*>>(null).apply {
            isOpaque = true
            background = fillColor
        }

        init {
            isOpaque = true
            background = DEFAULT_PROGRESS_BACKGROUND
            preferredSize = Dimension(width, height)
            minimumSize = Dimension(width, height)
            maximumSize = Dimension(Int.MAX_VALUE, height)
            add(fillPanel)
        }

        fun setFraction(value: Double) {
            fraction = value.coerceIn(0.0, 1.0)
            revalidate()
            repaint()
        }

        override fun doLayout() {
            val fillWidth = when {
                fraction <= 0.0 -> 0
                fraction >= 1.0 -> width
                else -> maxOf(4, (width * fraction).roundToInt())
            }
            fillPanel.setBounds(0, 0, fillWidth, height)
        }
    }

    private companion object {
        private val DEFAULT_PROGRESS_BACKGROUND = JBColor(Gray._220, Gray._95)
        private val DEFAULT_TEXT_COLOR = JBColor(Gray._60, Gray._210)
    }
}
