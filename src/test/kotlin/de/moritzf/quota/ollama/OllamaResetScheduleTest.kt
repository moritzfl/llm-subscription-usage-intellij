package de.moritzf.quota.ollama

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OllamaResetScheduleTest {
    @Test
    fun sessionResetsOnFiveHourUnixGrid() {
        // 2026-08-10T05:30:42Z → next boundary 10:00:00Z
        val now = Instant.parse("2026-08-10T05:30:42Z")
        assertEquals(Instant.parse("2026-08-10T10:00:00Z"), OllamaResetSchedule.sessionResetsAt(now))
    }

    @Test
    fun sessionOnBoundaryPointsToNextPeriod() {
        val boundary = Instant.parse("2026-08-10T10:00:00Z")
        assertEquals(Instant.parse("2026-08-10T15:00:00Z"), OllamaResetSchedule.sessionResetsAt(boundary))
    }

    @Test
    fun weeklyResetsMondayUtc() {
        // Sunday 2026-08-16 → next Monday 00:00 UTC
        val sunday = Instant.parse("2026-08-16T12:00:00Z")
        assertEquals(Instant.parse("2026-08-17T00:00:00Z"), OllamaResetSchedule.weeklyResetsAt(sunday))

        // Monday just after reset → following Monday
        val monday = Instant.parse("2026-08-17T00:00:00Z")
        assertEquals(Instant.parse("2026-08-24T00:00:00Z"), OllamaResetSchedule.weeklyResetsAt(monday))
    }

    @Test
    fun monthlyKeepsFutureAnchor() {
        val anchor = Instant.parse("2026-10-02T18:08:50Z")
        val now = Instant.parse("2026-09-19T09:00:00Z")
        assertEquals(anchor, OllamaResetSchedule.monthlyResetsAt(anchor, now))
    }

    @Test
    fun monthlyRollsForwardAfterAnchor() {
        val anchor = Instant.parse("2026-10-02T18:08:50Z")
        val now = Instant.parse("2026-10-02T18:08:50Z")
        assertEquals(Instant.parse("2026-11-02T18:08:50Z"), OllamaResetSchedule.monthlyResetsAt(anchor, now))
        assertEquals(
            Instant.parse("2026-11-02T18:08:50Z"),
            OllamaResetSchedule.monthlyResetsAt(anchor, Instant.parse("2026-10-15T00:00:00Z")),
        )
    }

    @Test
    fun monthlyClampsShortMonthsThenRestoresDay() {
        val jan31 = Instant.parse("2026-01-31T18:08:50Z")
        assertEquals(
            Instant.parse("2026-02-28T18:08:50Z"),
            OllamaResetSchedule.monthlyResetsAt(jan31, Instant.parse("2026-01-31T18:08:50Z")),
        )
        assertEquals(
            Instant.parse("2026-03-31T18:08:50Z"),
            OllamaResetSchedule.monthlyResetsAt(jan31, Instant.parse("2026-02-28T18:08:50Z")),
        )
    }

    @Test
    fun parseMonthlyAnchorAcceptsIsoAndDateOnly() {
        assertEquals(
            Instant.parse("2026-10-02T18:08:50Z"),
            OllamaResetSchedule.parseMonthlyAnchor(" 2026-10-02T18:08:50Z "),
        )
        assertEquals(
            Instant.parse("2026-10-02T00:00:00Z"),
            OllamaResetSchedule.parseMonthlyAnchor("2026-10-02"),
        )
        assertEquals(null, OllamaResetSchedule.parseMonthlyAnchor("the 2nd"))
        assertEquals(null, OllamaResetSchedule.parseMonthlyAnchor(""))
    }

    @Test
    fun matchesShellRemainderFormulas() {
        val now = Instant.parse("2026-08-10T05:30:42Z")
        val nowSec = now.epochSeconds

        val sessionRemaining = OllamaResetSchedule.sessionResetsAt(now).epochSeconds - nowSec
        assertEquals(18_000L - (nowSec % 18_000L), sessionRemaining)

        val weeklyRemaining = OllamaResetSchedule.weeklyResetsAt(now).epochSeconds - nowSec
        val shifted = nowSec - 4L * 86_400L
        assertEquals(604_800L - (shifted % 604_800L), weeklyRemaining)
    }
}
