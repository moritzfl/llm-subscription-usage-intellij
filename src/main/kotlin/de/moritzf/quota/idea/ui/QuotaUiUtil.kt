package de.moritzf.quota.idea.ui

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * UI formatting helpers for timestamps and relative quota time values.
 */
object QuotaUiUtil {
    private val absoluteFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("MMM d, uuuu HH:mm", Locale.ENGLISH)

    @JvmStatic
    fun formatReset(resetsAt: Instant?): String? {
        if (resetsAt == null) {
            return null
        }

        val duration = Duration.ofMillis(resetsAt.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds())
        val remaining = formatDuration(duration)
        if (remaining != null) {
            return "Resets $remaining"
        }

        val at = formatAbsoluteInstant(resetsAt)
        return "Resets at $at"
    }

    @JvmStatic
    fun formatResetCompact(resetsAt: Instant?): String? {
        if (resetsAt == null) {
            return null
        }

        val duration = formatDuration(Duration.ofMillis(resetsAt.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds())) ?: return null
        return duration.removePrefix("in ")
    }

    @JvmStatic
    fun formatInstant(instant: Instant?): String? {
        if (instant == null) {
            return null
        }

        val ago = formatAgo(Duration.ofMillis(Clock.System.now().toEpochMilliseconds() - instant.toEpochMilliseconds()))
        return ago ?: formatAbsoluteInstant(instant)
    }

    @JvmStatic
    fun formatResetInSeconds(resetInSec: Long): String? {
        if (resetInSec <= 0) {
            return null
        }

        val duration = Duration.ofSeconds(resetInSec)
        val remaining = formatDuration(duration)
        if (remaining != null) {
            return "Resets $remaining"
        }
        return null
    }

    private fun formatDuration(duration: Duration): String? {
        if (duration.isNegative) {
            return null
        }

        val minutes = duration.toMinutes()
        if (minutes < 1) {
            return "in <1m"
        }

        val days = minutes / (60 * 24)
        val hours = (minutes % (60 * 24)) / 60
        val mins = minutes % 60
        val builder = StringBuilder("in ")
        if (days > 0) {
            builder.append(days).append('d')
        }
        if (hours > 0) {
            if (builder.length > 3) {
                builder.append(' ')
            }
            builder.append(hours).append('h')
        }
        if (mins > 0 && days == 0L) {
            if (builder.length > 3) {
                builder.append(' ')
            }
            builder.append(mins).append('m')
        }
        return builder.toString()
    }

    private fun formatAgo(duration: Duration): String? {
        if (duration.isNegative) {
            return null
        }

        val minutes = duration.toMinutes()
        if (minutes < 1) {
            return "just now"
        }

        val days = minutes / (60 * 24)
        if (days > 0) {
            return if (days == 1L) "1 day ago" else "$days days ago"
        }

        val hours = minutes / 60
        if (hours > 0) {
            return if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }

        return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
    }

    @JvmStatic
    fun formatOpenCodeBalance(balance: Long): String {
        return BigDecimal.valueOf(balance).movePointLeft(8).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    @JvmStatic
    fun escapeHtml(text: String): String {
        return buildString(text.length) {
            text.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }
    }

    private fun formatAbsoluteInstant(instant: Instant): String {
        val zonedInstant = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
        return absoluteFormatter.format(zonedInstant)
    }
}
