package de.moritzf.quota.ollama

import kotlin.time.Clock
import kotlin.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Global Ollama Cloud limit reset schedule. Session and weekly windows reset at the same
 * wall-clock boundaries for every account (not per-user rolling windows), so remaining time
 * can be computed when `/api/usage` omits `resets_at`.
 *
 * Session: every 5 hours on the Unix epoch grid (`18000 - now%18000`).
 * Weekly: every Monday 00:00 UTC (`604800 - (now - 4d)%604800`).
 *
 * Monthly credit plans have no global grid. Ollama resets on the subscription-start day and
 * clock; `/api/usage` omits that stamp. [monthlyResetsAt] rolls one user-supplied occurrence
 * forward by calendar months (not a fixed 30-day window).
 */
object OllamaResetSchedule {
    const val SESSION_PERIOD_SECONDS: Long = 5L * 60L * 60L
    const val WEEKLY_PERIOD_SECONDS: Long = 7L * 24L * 60L * 60L
    /** Shift Unix epoch (Thu) so week boundaries land on Monday 00:00 UTC. */
    const val WEEKLY_EPOCH_OFFSET_SECONDS: Long = 4L * 24L * 60L * 60L

    fun sessionResetsAt(now: Instant = Clock.System.now()): Instant {
        val nowSec = now.epochSeconds
        val intoPeriod = positiveMod(nowSec, SESSION_PERIOD_SECONDS)
        val remaining = SESSION_PERIOD_SECONDS - intoPeriod
        return Instant.fromEpochSeconds(nowSec + remaining)
    }

    fun weeklyResetsAt(now: Instant = Clock.System.now()): Instant {
        val nowSec = now.epochSeconds
        val intoPeriod = positiveMod(nowSec - WEEKLY_EPOCH_OFFSET_SECONDS, WEEKLY_PERIOD_SECONDS)
        val remaining = WEEKLY_PERIOD_SECONDS - intoPeriod
        return Instant.fromEpochSeconds(nowSec + remaining)
    }

    /**
     * Next monthly reset after [now] from one known occurrence (site `data-time`, or the original
     * start). Same UTC day-of-month and clock; short months clamp the day, then later months
     * restore the original day.
     */
    fun monthlyResetsAt(anchor: Instant, now: Instant = Clock.System.now()): Instant {
        if (anchor > now) return anchor
        val zone = ZoneOffset.UTC
        val anchorOd = java.time.Instant.ofEpochSecond(anchor.epochSeconds, anchor.nanosecondsOfSecond.toLong())
            .atOffset(zone)
        val originalDay = anchorOd.dayOfMonth
        val originalTime = anchorOd.toLocalTime()
        fun at(yearMonth: YearMonth): Instant {
            val day = minOf(originalDay, yearMonth.lengthOfMonth())
            val od = yearMonth.atDay(day).atTime(originalTime).atOffset(zone)
            return Instant.fromEpochSeconds(od.toEpochSecond(), od.nano.toLong())
        }
        var yearMonth = YearMonth.from(anchorOd).plusMonths(1)
        var candidate = at(yearMonth)
        while (candidate <= now) {
            yearMonth = yearMonth.plusMonths(1)
            candidate = at(yearMonth)
        }
        return candidate
    }

    fun parseMonthlyAnchor(raw: String?): Instant? {
        val value = raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
        val javaInstant = runCatching { java.time.Instant.parse(value) }.getOrNull()
        if (javaInstant != null) {
            return Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano.toLong())
        }
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val start = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return Instant.fromEpochSeconds(start)
    }

    private fun positiveMod(value: Long, modulus: Long): Long {
        val rem = value % modulus
        return if (rem >= 0L) rem else rem + modulus
    }
}
