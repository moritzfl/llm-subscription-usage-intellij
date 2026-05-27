package de.moritzf.quota.idea.ui.popup

import de.moritzf.quota.gemini.GeminiQuota
import de.moritzf.quota.gemini.GeminiBucket
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.ui.JBUI
import javax.swing.JPanel
import kotlin.math.roundToInt

internal class GeminiPopupSection : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val separator = createSeparatedBlock()
    private val warningLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel("Gemini", QuotaIcons.GEMINI).apply { border = JBUI.Borders.emptyTop(0) }
    private val bucketPanels = mutableListOf<WindowBlockPanel>()

    init {
        isOpaque = false
        add(separator)
        add(warningLabel)
        add(titleLabel)
        for (i in 0 until 8) {
            val panel = WindowBlockPanel(3)
            bucketPanels.add(panel)
            add(panel)
        }
        hideAll()
    }

    fun update(quota: GeminiQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                warningLabel.isVisible = true
                warningLabel.text = "Gemini error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = "Gemini"
                bucketPanels[0].showLoading("Loading...")
                bucketPanels[0].isVisible = true
            }
            else -> {
                warningLabel.isVisible = false
                titleLabel.isVisible = true
                val plan = quota.planType?.let { " (" + it + ")" } ?: ""
                titleLabel.text = "Gemini" + plan

                val displayBuckets = aggregateBuckets(quota.buckets)
                bucketPanels.forEachIndexed { index, panel ->
                    if (index < displayBuckets.size) {
                        val (label, bucket) = displayBuckets[index]
                        val fraction = bucket.remainingFraction ?: 1.0
                        val percentUsed = clampPercent((100.0 * (1.0 - fraction)).roundToInt())
                        val reset = bucket.resetTime?.let { 
                             QuotaUiUtil.formatResetCompact(runCatching { kotlinx.datetime.Instant.parse(it) }.getOrNull())
                        }
                        val info = if (reset != null) percentUsed.toString() + "% used - " + reset else percentUsed.toString() + "% used"
                        panel.update(label, info, percentUsed)
                        panel.isVisible = true
                    } else {
                        panel.isVisible = false
                    }
                }
            }
        }
    }

    private fun aggregateBuckets(buckets: List<GeminiBucket>): List<Pair<String, GeminiBucket>> {
        val groups = mutableMapOf<String, MutableList<GeminiBucket>>()
        
        buckets.forEach { bucket ->
            // Models sharing usage and reset time belong to the same internal bucket
            val key = (bucket.remainingFraction ?: 1.0).toString() + "|" + (bucket.resetTime ?: "")
            groups.getOrPut(key) { mutableListOf() }.add(bucket)
        }
        
        return groups.map { (key, bucketList) ->
            val modelIds = bucketList.map { it.modelId.lowercase() }
            
            val label = when {
                modelIds.all { it.contains("lite") } -> "Gemini Flash (Lite)"
                modelIds.all { it.contains("pro") } -> "Gemini Pro"
                modelIds.all { it.contains("flash") } -> "Gemini Flash"
                else -> {
                    val displayNames = bucketList.map { it.modelId.substringAfterLast("/") }
                    if (displayNames.size > 2) "Other models" else displayNames.joinToString(", ")
                }
            }
            label to bucketList[0]
        }.sortedBy { it.second.remainingFraction ?: 1.0 }
    }

    private fun hideAll() {
        warningLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        bucketPanels.forEach { it.isVisible = false }
    }
}
