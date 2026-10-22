package de.moritzf.quota.zai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZaiQuotaClientTest {
    @Test
    fun parseQuotaExtractsCreditPlanWindowsWithoutMonthlyMcpBudget() {
        // Current CREDIT_LIMIT contract, also used by Z.ai's dashboard since the July 2026
        // migration.
        val quota =
            ZaiQuotaClient.parseQuota(
                """{"success":true,"data":[]}""",
                """{"success":true,"data":{"limits":[
              {"type":"CREDIT_LIMIT","unit":6,"number":1,"usage":60000,"currentValue":15000,"remaining":45000,"percentage":25,"nextResetTime":1787649214999},
              {"type":"CREDIT_LIMIT","unit":3,"number":5,"usage":12000,"currentValue":3000,"remaining":9000}
            ],"level":"pro"}}""",
            )

        val session = assertNotNull(quota.sessionUsage)
        assertEquals(25.0, session.usagePercent)
        assertEquals(5 * 3_600_000L, session.periodDurationMs)
        val weekly = assertNotNull(quota.weeklyUsage)
        assertEquals(25.0, weekly.usagePercent)
        assertEquals(7 * 86_400_000L, weekly.periodDurationMs)
        assertEquals(1787649214999, weekly.resetsAt?.toEpochMilliseconds())
        assertNull(quota.webSearchUsage)
    }

    @Test
    fun parseQuotaExtractsPlanSessionWeeklyAndWebSearches() {
        val subscriptionJson =
            """
            {
              "code": 200,
              "success": true,
              "data": [
                {
                  "productName": "GLM Coding Max",
                  "status": "VALID",
                  "nextRenewTime": "2026-03-12"
                }
              ]
            }
            """
                .trimIndent()
        val quotaJson =
            """
            {
              "code": 200,
              "success": true,
              "data": {
                "limits": [
                  {
                    "type": "TOKENS_LIMIT",
                    "unit": 3,
                    "number": 5,
                    "usage": 800000000,
                    "currentValue": 127694464,
                    "percentage": 15,
                    "nextResetTime": 1770648402389
                  },
                  {
                    "type": "TOKENS_LIMIT",
                    "unit": 6,
                    "number": 1,
                    "usage": 1000000000,
                    "currentValue": 420000000,
                    "percentage": 42,
                    "nextResetTime": 1770734802389
                  },
                  {
                    "type": "TIME_LIMIT",
                    "unit": 5,
                    "number": 1,
                    "usage": 4000,
                    "currentValue": 1828,
                    "percentage": 45
                  }
                ]
              }
            }
            """
                .trimIndent()

        val quota = ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)

        assertEquals("GLM Coding Max", quota.plan)
        val sessionUsage = assertNotNull(quota.sessionUsage)
        assertEquals(15.0, sessionUsage.usagePercent)
        assertEquals(5 * 3_600_000L, sessionUsage.periodDurationMs)
        val weeklyUsage = assertNotNull(quota.weeklyUsage)
        assertEquals(42.0, weeklyUsage.usagePercent)
        assertEquals(7 * 86_400_000L, weeklyUsage.periodDurationMs)
        val webSearchUsage = assertNotNull(quota.webSearchUsage)
        assertEquals(1828, webSearchUsage.used)
        assertEquals(4000, webSearchUsage.limit)
        assertEquals(45.0, webSearchUsage.usagePercent)
        assertEquals(30 * 86_400_000L, webSearchUsage.periodDurationMs)
        assertEquals(1773273600000, webSearchUsage.resetsAt?.toEpochMilliseconds())
    }

    @Test
    fun toolLimitUsesItsOwnResetAndCountsCallsPerTool() {
        val quotaJson =
            """
            {"code":200,"success":true,"data":{"level":"pro","limits":[
             {"type":"TIME_LIMIT","unit":5,"number":1,"usage":1000,"currentValue":12,"remaining":988,
              "percentage":1,"nextResetTime":1786489348996,"usageDetails":[
               {"modelCode":"search-prime","usage":9},{"modelCode":"web-reader","usage":3},
               {"modelCode":"zread","usage":0},{"usage":4},{"modelCode":"","usage":1}]},
             {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":42,"nextResetTime":1786493397235}]}}
            """
                .trimIndent()

        val quota =
            ZaiQuotaClient.parseQuota(
                """{"success":true,"data":[{"productName":"GLM Coding Pro","status":"VALID","nextRenewTime":"2026-11-01"}]}""",
                quotaJson,
            )
        val withoutSubscription =
            ZaiQuotaClient.parseQuota("""{"success":true,"data":[]}""", quotaJson)

        val tools = assertNotNull(quota.webSearchUsage)
        assertEquals(1786489348996, tools.resetsAt?.toEpochMilliseconds())
        assertEquals(mapOf("search-prime" to 9L, "web-reader" to 3L, "zread" to 0L), tools.tools)
        assertEquals(5 * 3_600_000L, quota.sessionUsage?.periodDurationMs)
        assertEquals("GLM Coding Pro", quota.plan)
        assertEquals("Pro", withoutSubscription.plan)
    }

    @Test
    fun parseQuotaMapsUnitOneAsDaysAndSortsTokenWindows() {
        val subscriptionJson = """{"success":true,"data":[]}"""
        val quotaJson =
            """
            {
              "success": true,
              "data": {
                "limits": [
                  {
                    "type": "TOKENS_LIMIT",
                    "unit": 1,
                    "number": 2,
                    "usage": 100,
                    "currentValue": 20,
                    "percentage": 20
                  },
                  {
                    "type": "TOKENS_LIMIT",
                    "unit": 3,
                    "number": 5,
                    "usage": 100,
                    "currentValue": 10,
                    "percentage": 10
                  }
                ]
              }
            }
            """
                .trimIndent()

        val quota = ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)

        assertEquals(5 * 3_600_000L, assertNotNull(quota.sessionUsage).periodDurationMs)
        assertEquals(2 * 86_400_000L, assertNotNull(quota.weeklyUsage).periodDurationMs)
    }

    @Test
    fun parseQuotaComputesPercentageWhenMissing() {
        val subscriptionJson = """{"success":true,"data":[]}"""
        val quotaJson =
            """
            {
              "success": true,
              "data": {
                "limits": [
                  {
                    "type": "TOKENS_LIMIT",
                    "unit": 3,
                    "number": 5,
                    "usage": 200,
                    "currentValue": 50
                  }
                ]
              }
            }
            """
                .trimIndent()

        val quota = ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)

        assertEquals(25.0, assertNotNull(quota.sessionUsage).usagePercent)
    }

    @Test
    fun parseQuotaKeepsUsageWhenSubscriptionDocumentIsMalformed() {
        val subscriptionJson = """{"data": "reshaped-to-a-string"}"""
        val quotaJson =
            """
            {
              "success": true,
              "data": {
                "limits": [
                  {
                    "type": "TOKENS_LIMIT",
                    "unit": 3,
                    "number": 5,
                    "usage": 200,
                    "currentValue": 50
                  }
                ]
              }
            }
            """
                .trimIndent()

        val quota = ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)

        assertEquals(25.0, assertNotNull(quota.sessionUsage).usagePercent)
        assertEquals("", quota.plan)
    }

    @Test
    fun parseQuotaReportsMissingCodingPlan() {
        val subscriptionJson =
            """
            {
              "code": 200,
              "msg": "Operation successful",
              "data": [],
              "success": true
            }
            """
                .trimIndent()
        val quotaJson =
            """
            {
              "code": 500,
              "msg": "当前用户不存在coding plan",
              "success": false
            }
            """
                .trimIndent()

        val exception =
            assertFailsWith<ZaiQuotaException> {
                ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)
            }

        assertEquals("No active Z.ai coding plan found.", exception.message)
        assertEquals(500, exception.statusCode)
    }
}
