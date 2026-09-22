package de.moritzf.quota.opencode

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OpenCodeQuotaClientTest {
    private val now = Instant.parse("2026-09-20T00:00:00Z")

    @Test
    fun parsesConsoleMetersAndMonthlyPeriodEnd() {
        val quota = OpenCodeQuotaClient.parseQuotaResponse(GO_STATUS, now)
        assertEquals(25.0, quota.rollingUsage?.usagePercent)
        assertEquals(40.0, quota.weeklyUsage?.usagePercent)
        assertEquals(10.0, quota.monthlyUsage?.usagePercent)
        assertEquals(10800L, quota.rollingUsage?.resetInSec)
        assertEquals(86400L, quota.weeklyUsage?.resetInSec)
        assertEquals(2505600L, quota.monthlyUsage?.resetInSec)
    }

    @Test
    fun missingResetsAndMetersRemainAbsentAndExhaustionIsNotClamped() {
        val body = """{"access":{"meters":{"fiveHour":{"usedMicroCents":"101","limitMicroCents":"100","resetsAt":null}}}}"""
        val quota = OpenCodeQuotaClient.parseQuotaResponse(body, now)
        assertEquals(101.0, quota.rollingUsage?.usagePercent)
        assertTrue(quota.rollingUsage!!.isRateLimited)
        assertEquals(0L, quota.rollingUsage.resetInSec)
        assertNull(quota.weeklyUsage)
        assertNull(quota.monthlyUsage)
    }

    @Test
    fun nullGoResponsesStillFetchZenBalanceWithBearerAndOrganization() {
        for (go in listOf("null", """{"access":null}""")) {
            OpenCodeTestServer { request ->
                200 to if (request.path.endsWith("go/status")) go else BILLING_STATUS
            }.use { server ->
                val quota = OpenCodeQuotaClient(endpoint = server.endpoint).fetchQuota("user-token", "org_test")
                assertFalse(quota.hasUsageState())
                assertEquals(1_234_567_890L, quota.availableBalance)
                assertEquals(listOf("/console/api/go/status", "/console/api/billing/status"), server.requests.map { it.path })
                server.requests.forEach {
                    assertEquals("GET", it.method)
                    assertEquals("Bearer user-token", it.headers.getFirst("Authorization"))
                    assertEquals("org_test", it.headers.getFirst("x-org-id"))
                    assertNull(it.headers.getFirst("Cookie"))
                }
                val raw = JsonSupport.json.parseToJsonElement(quota.rawJson!!).jsonObject
                assertEquals(JsonSupport.json.parseToJsonElement(go), raw["go"])
                assertEquals(JsonSupport.json.parseToJsonElement(BILLING_STATUS), raw["billing"])
            }
        }
    }

    @Test
    fun malformedOrDeniedGoPreservesBalanceWithPartialDataWarning() {
        for ((status, go) in listOf(200 to "{}", 200 to """{"access":{}}""", 200 to "<html>login</html>", 403 to "{}")) {
            OpenCodeTestServer { request ->
                if (request.path.endsWith("go/status")) status to go else 200 to BILLING_STATUS
            }.use { server ->
                val quota = OpenCodeQuotaClient(endpoint = server.endpoint).fetchQuota("token", "wrk_test")
                assertEquals(1_234_567_890L, quota.availableBalance)
                assertFalse(quota.hasUsageState())
                assertTrue(quota.warnings.isNotEmpty())
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test
    fun malformedWindowAndResetDoNotHideOtherWindowsOrValidPercent() {
        val body = """{"unrelated":{"new":true},"access":{"endsAt":"changed","meters":{
            "fiveHour":{"usedMicroCents":"25.5","limitMicroCents":100,"resetsAt":{"changed":true}},
            "week":{"usedMicroCents":{"newShape":120},"limitMicroCents":"300"},
            "month":{"usedMicroCents":"10","limitMicroCents":"100"}}}}"""
        val quota = OpenCodeQuotaClient.parseQuotaResponse(body, now)
        assertEquals(25.5, quota.rollingUsage?.usagePercent)
        assertEquals(0L, quota.rollingUsage?.resetInSec)
        assertNull(quota.weeklyUsage)
        assertEquals(10.0, quota.monthlyUsage?.usagePercent)
        assertEquals(3, quota.warnings.size)
    }

    @Test
    fun malformedBillingPreservesAllGoWindowsAndRawResponse() {
        OpenCodeTestServer { request ->
            200 to if (request.path.endsWith("go/status")) GO_STATUS else """{"balanceMicroCents":{"new":"shape"}}"""
        }.use { server ->
            val quota = OpenCodeQuotaClient(endpoint = server.endpoint).fetchQuota("token", "org_test")
            assertEquals(25.0, quota.rollingUsage?.usagePercent)
            assertEquals(40.0, quota.weeklyUsage?.usagePercent)
            assertEquals(10.0, quota.monthlyUsage?.usagePercent)
            assertNull(quota.availableBalance)
            assertTrue(quota.warnings.single().contains("Zen balance"))
            assertTrue(quota.rawJson!!.contains("new"))
        }
    }

    @Test
    fun reportsFailureOnlyWhenNoSectionIsUsable() {
        OpenCodeTestServer { 200 to "{}" }.use { server ->
            val failure = assertFailsWith<OpenCodeQuotaException> {
                OpenCodeQuotaClient(endpoint = server.endpoint).fetchQuota("token", "org_test")
            }
            assertEquals(2, server.requests.size)
            assertTrue(failure.message!!.contains("Go usage"))
            assertTrue(failure.message!!.contains("Zen balance"))
        }
    }

    @Test
    fun optionalBillingFailurePreservesGoButUnauthorizedTriggersRefresh() {
        for (status in listOf(401, 403, 500)) {
            OpenCodeTestServer { request ->
                if (request.path.endsWith("go/status")) 200 to GO_STATUS else status to "{}"
            }.use { server ->
                val client = OpenCodeQuotaClient(endpoint = server.endpoint)
                if (status == 401) {
                    assertEquals(401, assertFailsWith<OpenCodeQuotaException> { client.fetchQuota("t", "org_test") }.statusCode)
                } else {
                    val quota = client.fetchQuota("t", "org_test")
                    assertEquals(25.0, quota.rollingUsage?.usagePercent)
                    assertNull(quota.availableBalance)
                }
            }
        }
        OpenCodeTestServer { request ->
            if (request.path.endsWith("go/status")) 200 to "null" else 403 to "{}"
        }.use { server ->
            assertEquals(403, assertFailsWith<OpenCodeQuotaException> {
                OpenCodeQuotaClient(endpoint = server.endpoint).fetchQuota("t", "org_test")
            }.statusCode)
        }
    }

    @Test
    fun organizationsUseJsonWithoutProbingEverySubscription() {
        OpenCodeTestServer { 200 to """[{"id":"wrk_old","name":"Default"},{"id":{},"name":"changed"},{"id":"org_new","name":"Team"}]""" }.use { server ->
            val workspaces = OpenCodeQuotaClient(endpoint = server.endpoint).fetchWorkspaces("user-token")
            assertEquals(listOf("wrk_old", "org_new"), workspaces.map { it.id })
            assertEquals("Default (wrk_old)", workspaces.first().toString())
            assertEquals("/console/api/orgs", server.requests.single().path)
            assertNull(server.requests.single().headers.getFirst("x-org-id"))
        }
    }

    companion object {
        const val GO_STATUS = """{"useBalance":false,"access":{"endsAt":"2026-10-19T00:00:00Z","meters":{
            "fiveHour":{"usedMicroCents":"300000000","limitMicroCents":"1200000000","resetsAt":"2026-09-20T03:00:00Z"},
            "week":{"usedMicroCents":"1200000000","limitMicroCents":"3000000000","resetsAt":"2026-09-21T00:00:00Z"},
            "month":{"usedMicroCents":"600000000","limitMicroCents":"6000000000","resetsAt":null}}}}"""
        const val BILLING_STATUS = """{"billingMode":"prepaid","mode":"pay-as-you-go","balanceMicroCents":"1234567890","availableMicroCents":"9876543210"}"""
    }
}
