package de.moritzf.quota.antigravity

import de.moritzf.quota.shared.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject

class AntigravityQuotaTest {
    @Test
    fun parsesNativeReportAndPreservesIndependentModelGroups() {
        val quota = parseAntigravityQuota(USAGE_REPORT)

        assertEquals(2, quota.windows.size)
        assertEquals("3p-weekly", quota.primaryWindow()?.id)
        assertEquals(0.75, quota.usageFraction())
        assertEquals(
            mapOf("Gemini Models/gemini-weekly" to 0.25, "Claude and GPT models/3p-weekly" to 0.75),
            quota.activityWindows(),
        )
        assertEquals(Instant.parse("2026-09-29T06:14:59Z"), quota.windows.first().resetsAt)
        assertTrue(quota.hasUsageState())
        assertTrue(quota.warnings.isEmpty())
        assertNotNull(quota.fetchedAt)
        assertEquals(USAGE_REPORT, quota.rawJson)
    }

    @Test
    fun changedGroupsAndFieldsDoNotDiscardUsableSiblings() {
        val quota =
            parseAntigravityQuota(
                report(
                    """
            null,
            {"name":"changed","buckets":{}},
            {"name":"Gemini","buckets":[
                null, {"remaining_fraction":0.1},
                {"id":"weekly","remaining_fraction":"0.75","reset_time":"not a time"},
                {"id":"daily","remaining_fraction":{},"reset_time":"2026-09-23T00:00:00Z"},
                {"id":"future","remaining_fraction":0.1,"disabled":{}},
                {"id":"disabled","remaining_fraction":0,"disabled":true}
            ]}
        """
                )
            )

        assertEquals(4, quota.windows.size)
        assertEquals(0.25, quota.usageFraction())
        assertNull(quota.windows[0].resetsAt)
        assertNull(quota.windows[1].usagePercent)
        assertNotNull(quota.windows[1].resetsAt)
        assertNull(quota.windows[2].usagePercent)
        assertTrue(quota.windows[3].disabled)
        assertNull(quota.windows[3].usagePercent)
        assertEquals(mapOf("Gemini/weekly" to 0.25), quota.activityWindows())
        assertTrue(quota.warnings.size >= 4)
    }

    @Test
    fun missingOrInvalidRemainingQuotaNeverBecomesZeroUsage() {
        for (remaining in
            listOf("null", "{}", "[]", "true", "-0.1", "1.1", "\"NaN\"", "\"Infinity\"")) {
            val quota =
                parseAntigravityQuota(
                    report(
                        """
                {"buckets":[{"id":"unknown","remaining_fraction":$remaining}]}
            """
                    )
                )
            assertNull(quota.usageFraction(), remaining)
            assertNull(quota.primaryWindow(), remaining)
            assertTrue(quota.activityWindows().isEmpty(), remaining)
        }
        val quota = parseAntigravityQuota(report("""{"buckets":[{"id":"missing"}]}"""))
        assertNull(quota.usageFraction())
        assertFalse(quota.windows.single().disabled)
    }

    @Test
    fun remainingEndpointsRepresentExhaustedAndUnusedQuota() {
        val full =
            parseAntigravityQuota(
                report("""{"buckets":[{"id":"weekly","remaining_fraction":0}]}""")
            )
        val unused =
            parseAntigravityQuota(
                report("""{"buckets":[{"id":"weekly","remaining_fraction":1}]}""")
            )
        assertEquals(1.0, full.usageFraction())
        assertEquals(0.0, unused.usageFraction())
    }

    @Test
    fun refusesErrorsAndModelResponseTextAsQuotaData() {
        for (raw in
            listOf(
                "not json",
                "null",
                "[]",
                "{}",
                USAGE_REPORT.replace("SUCCESS", "ERROR"),
                USAGE_REPORT.replace("\"name\":\"usage\"", "\"name\":\"other\""),
                """{"status":"SUCCESS","response":{"groups":[{"buckets":[{"id":"fake","remaining_fraction":1}]}]}}""",
                report(""),
                report("null,{\"buckets\":[]}"),
            )) {
            assertFailsWith<AntigravityQuotaException> { parseAntigravityQuota(raw) }
        }
    }

    @Test
    fun readsRemainingCreditsOnlyFromTheStructuredCreditsReport() {
        assertEquals(1250L, parseAntigravityCredits(CREDITS_REPORT))
        assertEquals(0L, parseAntigravityCredits(CREDITS_REPORT.replace("1250", "0")))
        for (raw in
            listOf(
                "not json",
                CREDITS_REPORT.replace("SUCCESS", "ERROR"),
                CREDITS_REPORT.replace("\"name\":\"credits\"", "\"name\":\"usage\""),
                CREDITS_REPORT.replace("1250", "null"),
                CREDITS_REPORT.replace("1250", "-3"),
                CREDITS_REPORT.replace("\"remaining_credits\":1250,", ""),
            )) {
            assertNull(parseAntigravityCredits(raw), raw)
        }
    }

    @Test
    fun rawResponseNamesTheUsageAndCreditsReports() {
        val both =
            JsonSupport.json
                .parseToJsonElement(antigravityRawResponse(USAGE_REPORT, CREDITS_REPORT))
                .jsonObject
        val usageOnly =
            JsonSupport.json
                .parseToJsonElement(antigravityRawResponse(USAGE_REPORT, "not json"))
                .jsonObject

        assertEquals(setOf("usage", "credits"), both.keys)
        assertEquals(JsonSupport.json.parseToJsonElement(CREDITS_REPORT), both["credits"])
        assertEquals(setOf("usage"), usageOnly.keys)
        assertEquals(
            setOf("usage"),
            JsonSupport.json
                .parseToJsonElement(antigravityRawResponse(USAGE_REPORT, null))
                .jsonObject
                .keys,
        )
    }

    private fun report(groups: String) =
        """{"status":"SUCCESS","command":{"name":"usage","data":{"groups":[$groups]}}}"""
}

// Credits envelope as printed by agy 1.4.5, with the upgrade link kept generic.
internal const val CREDITS_REPORT =
    """{"conversation_id":"","status":"SUCCESS","response":"Remaining credits\t1250\n","duration_seconds":0,"num_turns":0,"usage":{"total_tokens":0},"command":{"name":"credits","data":{"remaining_credits":1250,"upgrade_uri":"https://antigravity.google/g1-upgrade"}}}"""

// Native command envelope; no credentials or conversation identifiers.
internal val USAGE_REPORT =
    """
    {
      "status":"SUCCESS",
      "response":"Human-readable output is not the quota API.",
      "num_turns":0,
      "usage":{"total_tokens":0},
      "command":{"name":"usage","data":{"groups":[
        {"name":"Gemini Models","buckets":[
          {"id":"gemini-weekly","name":"Weekly Limit Remaining","window":"weekly","remaining_fraction":0.75,"reset_time":"2026-09-29T06:14:59Z"}
        ]},
        {"name":"Claude and GPT models","buckets":[
          {"id":"3p-weekly","name":"Weekly Limit Remaining","window":"weekly","remaining_fraction":0.25,"reset_time":"2026-09-29T06:14:59Z"}
        ]}
      ]}}
    }
    """
        .trimIndent()
