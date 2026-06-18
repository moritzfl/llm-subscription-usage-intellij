package de.moritzf.quota.cursor

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Instant

class CursorQuotaClientTest {
    @Test
    fun parseQuotaExtractsPlanAndSpendLimitUsage() {
        val periodUsageJson = """
            {
              "billingCycleStart": "1768055295000",
              "billingCycleEnd": "1770733695000",
              "planUsage": {
                "totalSpend": 4500,
                "includedSpend": 2000,
                "bonusSpend": 2500,
                "limit": 2000,
                "autoPercentUsed": 50,
                "apiPercentUsed": 75,
                "totalPercentUsed": 65
              },
              "spendLimitUsage": {
                "totalSpend": 10000,
                "pooledLimit": 50000,
                "pooledUsed": 10000,
                "pooledRemaining": 40000,
                "individualUsed": 8000,
                "limitType": "team"
              },
              "displayThreshold": 200,
              "displayMessage": "You've used 65% of your plan",
              "autoModelSelectedDisplayMessage": "You've used 65% of your included total usage",
              "namedModelSelectedDisplayMessage": "You've used 75% of your included API usage"
            }
        """.trimIndent()
        val planInfoJson = """
            {
              "planInfo": {
                "planName": "Team",
                "includedAmountCents": 2000,
                "price": "$40/mo",
                "billingCycleEnd": "1770733695000"
              }
            }
        """.trimIndent()
        val profileJson = """
            {
              "membershipType": "enterprise",
              "isTeamMember": true
            }
        """.trimIndent()

        val quota = CursorQuotaClient.parseQuota(
            periodUsageJson,
            planInfoJson,
            profileJson,
            CursorAuth(accessToken = "token", email = "dev@example.com"),
        )

        assertEquals("Team", quota.planName)
        assertEquals("dev@example.com", quota.email)
        assertEquals("enterprise", quota.membershipType)
        assertEquals("You've used 65% of your plan", quota.displayMessage)
        assertEquals("You've used 65% of your included total usage", quota.autoModelDisplayMessage)
        assertEquals("You've used 75% of your included API usage", quota.apiModelDisplayMessage)

        val planUsage = assertNotNull(quota.planUsage)
        assertEquals(65.0, planUsage.totalPercentUsed)
        assertEquals(50.0, planUsage.autoPercentUsed)
        assertEquals(75.0, planUsage.apiPercentUsed)
        assertEquals(45.0, planUsage.totalSpendUsd)
        assertEquals(20.0, planUsage.limitUsd)
        assertEquals(Instant.fromEpochMilliseconds(1770733695000), planUsage.billingCycleEnd)
        assertEquals(Instant.fromEpochMilliseconds(1768055295000), planUsage.billingCycleStart)

        val spendLimit = assertNotNull(quota.spendLimit)
        assertEquals(500.0, spendLimit.pooledLimitUsd)
        assertEquals(100.0, spendLimit.pooledUsedUsd)
        assertEquals(400.0, spendLimit.pooledRemainingUsd)
        assertEquals("team", spendLimit.limitType)
        assertEquals(20.0, spendLimit.usagePercent())
    }

    @Test
    fun parseQuotaWithoutUsageThrows() {
        val periodUsageJson = """
            {
              "planUsage": {},
              "spendLimitUsage": {}
            }
        """.trimIndent()

        assertFailsWith<CursorQuotaException> {
            CursorQuotaClient.parseQuota(periodUsageJson, null, null)
        }
    }

    @Test
    fun buildRawJsonPreservesStringEmbeddedPayload() {
        val periodUsageJson = """{"displayMessage":"You've hit your usage limit","planUsage":{"totalPercentUsed":12}}"""
        val planInfoJson = """{"planInfo":{"planName":"Pro"}}"""
        val profileJson = """{"membershipType":"pro"}"""

        val rawJson = CursorQuotaClient.buildRawJson(periodUsageJson, planInfoJson, profileJson)
        val root = JsonSupport.json.parseToJsonElement(rawJson).jsonObject

        assertEquals(periodUsageJson, (root["periodUsage"] as JsonPrimitive).content)
        assertEquals(planInfoJson, (root["planInfo"] as JsonPrimitive).content)
        assertEquals(profileJson, (root["profile"] as JsonPrimitive).content)
    }

    @Test
    fun normalizeRawJsonUpgradesLegacyStringEmbeddedPayload() {
        val legacyJson = """
            {
              "periodUsage": "{\"displayMessage\":\"You've hit your usage limit\",\"planUsage\":{\"totalPercentUsed\":12}}",
              "planInfo": "{\"planInfo\":{\"planName\":\"Pro\"}}",
              "profile": "{\"membershipType\":\"pro\"}"
            }
        """.trimIndent()

        val normalized = CursorQuotaClient.normalizeRawJson(legacyJson)
        val root = JsonSupport.json.parseToJsonElement(normalized).jsonObject

        assertEquals("Pro", root["planInfo"]!!.jsonObject["planInfo"]!!.jsonObject["planName"]!!.toString().trim('"'))
        assertEquals("pro", root["profile"]!!.jsonObject["membershipType"]!!.toString().trim('"'))
    }

    @Test
    fun parseTimestampSupportsEpochMillisString() {
        val instant = CursorQuotaClient.parseTimestamp("1770733695000")
        assertEquals(Instant.fromEpochMilliseconds(1770733695000), instant)
    }
}
