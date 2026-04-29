package de.moritzf.quota.zai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ZaiQuotaClientTest {
    @Test
    fun parseQuotaExtractsPlanSessionWeeklyAndWebSearches() {
        val subscriptionJson = """
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
        """.trimIndent()
        val quotaJson = """
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
                    "number": 7,
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
        """.trimIndent()

        val quota = ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)

        assertEquals("GLM Coding Max", quota.plan)
        assertEquals(15.0, assertNotNull(quota.sessionUsage).usagePercent)
        assertEquals(42.0, assertNotNull(quota.weeklyUsage).usagePercent)
        val webSearchUsage = assertNotNull(quota.webSearchUsage)
        assertEquals(1828, webSearchUsage.used)
        assertEquals(4000, webSearchUsage.limit)
        assertEquals(45.0, webSearchUsage.usagePercent)
        assertEquals(1773273600000, webSearchUsage.resetsAt?.toEpochMilliseconds())
    }

    @Test
    fun parseQuotaComputesPercentageWhenMissing() {
        val subscriptionJson = """{"success":true,"data":[]}"""
        val quotaJson = """
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
        """.trimIndent()

        val quota = ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)

        assertEquals(25.0, assertNotNull(quota.sessionUsage).usagePercent)
    }

    @Test
    fun parseQuotaReportsMissingCodingPlan() {
        val subscriptionJson = """
            {
              "code": 200,
              "msg": "Operation successful",
              "data": [],
              "success": true
            }
        """.trimIndent()
        val quotaJson = """
            {
              "code": 500,
              "msg": "当前用户不存在coding plan",
              "success": false
            }
        """.trimIndent()

        val exception = assertFailsWith<ZaiQuotaException> {
            ZaiQuotaClient.parseQuota(subscriptionJson, quotaJson)
        }

        assertEquals("No active Z.ai coding plan found.", exception.message)
        assertEquals(500, exception.statusCode)
    }
}
