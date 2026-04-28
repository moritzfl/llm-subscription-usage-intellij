package de.moritzf.quota.ollama

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OllamaQuotaClientTest {
    @Test
    fun parseQuotaFromHtmlExtractsSessionAndWeeklyUsage() {
        val html = """
            <html>
            <body>
              <h2 class="text-xl font-medium">Cloud Usage</h2>
              <span class="text-xs font-normal px-2 py-0.5 rounded-full bg-neutral-100 text-neutral-600 capitalize">    pro   </span>
              <div>
                <div class="flex justify-between mb-2">
                  <span class="text-sm">Session usage</span>
                  <span class="text-sm">0.9% used</span>
                </div>
                <div class="w-full border border-1 border-neutral-200 rounded-full h-2 overflow-hidden">
                  <div class="h-full rounded-full bg-neutral-300" style="width: 0.9%"></div>
                </div>
                <div class="text-xs text-neutral-500 mt-1 local-time" data-time="2026-04-27T20:00:00Z" title="Mo. 27. Apr., 22:00">
                  Resets in 44 minutes
                </div>
              </div>
              <div>
                <div class="flex justify-between mb-2">
                  <span class="text-sm">Weekly usage</span>
                  <span class="text-sm">0.1% used</span>
                </div>
                <div class="w-full border border-1 border-neutral-200 rounded-full h-2 overflow-hidden">
                  <div class="h-full rounded-full bg-neutral-300" style="width: 0.1%"></div>
                </div>
                <div class="text-xs text-neutral-500 mt-1 local-time" data-time="2026-05-04T00:00:00Z" title="Mo. 4. Mai, 2:00">
                  Resets in 6 days
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuotaFromHtml(html)

        assertEquals("pro", quota.plan)
        val sessionUsage = assertNotNull(quota.sessionUsage)
        assertEquals(0.9, sessionUsage.usagePercent)
        val weeklyUsage = assertNotNull(quota.weeklyUsage)
        assertEquals(0.1, weeklyUsage.usagePercent)
    }

    @Test
    fun parseQuotaFromHtmlWithMissingUsageThrows() {
        val html = "<html><body><span class=\"capitalize\">free</span></body></html>"

        val exception = assertFailsWith<OllamaQuotaException> {
            OllamaQuotaClient.parseQuotaFromHtml(html)
        }
        assertEquals(200, exception.statusCode)
    }

    @Test
    fun parseQuotaMissingPlanFallsBackToEmpty() {
        val html = """
            <div>
              <span class="text-sm">Session usage</span>
              <div class="h-full rounded-full bg-neutral-300" style="width: 50%"></div>
              <div class="local-time" data-time="2026-04-27T20:00:00Z"></div>
            </div>
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuotaFromHtml(html)

        assertEquals("", quota.plan)
        val sessionUsage = assertNotNull(quota.sessionUsage)
        assertEquals(50.0, sessionUsage.usagePercent)
    }

    @Test
    fun parseQuotaMatchesExactBadgeMarkup() {
        val html = """
            <h2 class="text-xl font-medium flex items-center space-x-2">
              <span>Cloud Usage</span>
              <span class="text-xs font-normal px-2 py-0.5 rounded-full bg-neutral-100 text-neutral-600 capitalize">pro</span>
            </h2>
            <div>
              <span class="text-sm">Session usage</span>
              <div style="width: 12.5%"></div>
              <div class="local-time" data-time="2026-04-27T20:00:00Z"></div>
            </div>
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuotaFromHtml(html)

        assertEquals("pro", quota.plan)
        val sessionUsage = assertNotNull(quota.sessionUsage)
        assertEquals(12.5, sessionUsage.usagePercent)
    }

    @Test
    fun parseQuotaWithMultilineTag() {
        val html = """
            <span
              class="text-xs font-normal px-2 py-0.5 rounded-full bg-neutral-100 text-neutral-600 capitalize"
            >free</span>
            <div>
              <span class="text-sm">Session usage</span>
              <div style="width: 75%"></div>
              <div class="local-time" data-time="2026-04-27T20:00:00Z"></div>
            </div>
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuotaFromHtml(html)

        assertEquals("free", quota.plan)
        val sessionUsage = assertNotNull(quota.sessionUsage)
        assertEquals(75.0, sessionUsage.usagePercent)
    }
}
