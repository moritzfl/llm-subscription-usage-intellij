package de.moritzf.quota.azure

import de.moritzf.quota.shared.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class AzureLiveUsageTest {
    @Test
    fun refreshingDoesNotPostponeTheObservedReset() {
        val snapshot = assertNotNull(parseRateLimitHeaders(mapOf(
            "x-ratelimit-limit-tokens" to listOf("1000"),
            "x-ratelimit-remaining-tokens" to listOf("250"),
            "x-ratelimit-reset-tokens" to listOf("30"),
        ), "chat"))
        val now = Clock.System.now()
        val first = assertNotNull(liveWindow(snapshot, now))
        val later = assertNotNull(liveWindow(snapshot, now + 10.seconds))
        assertEquals(first.resetsAt, later.resetsAt)
        assertNull(liveWindow(snapshot, now + 31.seconds))
    }

    @Test
    fun snapshotsWithoutResetHeadersExpireInsteadOfLookingFreshForever() {
        val now = Clock.System.now()
        val snapshot = AzureRateLimitSnapshot("chat", 100.0, 0.0, null, null, null)
        val quota = AzureQuota(windows = listOf(assertNotNull(liveWindow(snapshot, now))))
        assertEquals(1, quota.currentWindows(now).size)
        assertEquals(0, quota.currentWindows(now + 61.seconds).size)
        assertNull(liveWindow(snapshot, now + 61.seconds))
    }

    @Test
    fun exhaustedRequestCounterTakesPriorityOverAvailableTokens() {
        val snapshot = AzureRateLimitSnapshot("chat", 1000.0, 950.0, 10.0, 0.0, 60, 10)
        val window = assertNotNull(liveWindow(snapshot, Clock.System.now()))
        assertEquals("requests", window.unit)
        assertEquals(100.0, window.usagePercent)
    }

    @Test
    fun usableRequestCountersSurviveIncompleteTokenCounters() {
        val now = Clock.System.now()
        val snapshot = assertNotNull(parseRateLimitHeaders(mapOf(
            "x-ratelimit-limit-tokens" to listOf("1000"),
            "x-ratelimit-limit-requests" to listOf("10"),
            "x-ratelimit-remaining-requests" to listOf("1"),
            "x-ratelimit-reset-requests" to listOf("10"),
        ), "chat"))
        val window = assertNotNull(liveWindow(snapshot, now))
        assertEquals("requests", window.unit)
        assertEquals(90.0, window.usagePercent)
        assertNotNull(window.resetsAt)
        assertNull(liveWindow(snapshot, now + 11.seconds))
    }

    @Test
    fun deploymentCapacityIsNotInventedAsTokensPerMinute() {
        val windows = assertNotNull(parseAzureDeployments("""{"value":[
            {"name":"reasoning","sku":{"name":"Standard","capacity":10},"properties":{"model":{"name":"o1"}}},
            {"name":"reserved","sku":{"name":"GlobalProvisionedManaged","capacity":50}}
        ]}"""))
        assertEquals(null, windows[0].used)
        assertEquals(null, windows[0].usagePercent)
        assertEquals(10.0, windows[0].capacity)
        assertEquals("capacity units", windows[0].unit)
        assertEquals(50.0, windows[1].capacity)
        assertEquals("PTU", windows[1].unit)
    }

    @Test
    fun rawResponseKeepsAzureBodies() {
        val json = assertNotNull(buildAzureRawResponse(
            account = JsonSupport.json.parseToJsonElement("""{"id":"sub","name":"Personal","user":{"name":"me@contoso.com"}}"""),
            deployments = mapOf("resource" to """{"value":[{"name":"gpt","sku":{"name":"Standard","capacity":3333}}]}"""),
            usages = mapOf("westeurope" to """{"value":[{"currentValue":1,"limit":2,"unit":"Count"}]}"""),
        ))
        assertTrue(json.contains("\"capacity\": 3333"))
        assertTrue(json.contains("\"currentValue\": 1"))
        assertTrue(!json.contains("\"used\""))
        assertTrue(!json.contains("\"kind\""))
    }
}
