package de.moritzf.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeQuotaClientTest {
    @Test
    fun parsesRealSolidStartResponse() {
        val body = ";0x00000126;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={mine:!0,useBalance:!1," +
            "rollingUsage:\$R[1]={status:\"ok\",resetInSec:17999,usagePercent:1}," +
            "weeklyUsage:\$R[2]={status:\"ok\",resetInSec:545090,usagePercent:6}," +
            "monthlyUsage:\$R[3]={status:\"ok\",resetInSec:2503851,usagePercent:24}})" +
            "(\$R[\"server-fn:1\"]))"

        val quota = OpenCodeQuotaClient.parseQuotaResponse(body)

        assertTrue(quota.mine)
        assertEquals(false, quota.useBalance)

        assertEquals(1, quota.rollingUsage?.usagePercent)
        assertEquals(17999L, quota.rollingUsage?.resetInSec)
        assertEquals("ok", quota.rollingUsage?.status)

        assertEquals(6, quota.weeklyUsage?.usagePercent)
        assertEquals(545090L, quota.weeklyUsage?.resetInSec)

        assertEquals(24, quota.monthlyUsage?.usagePercent)
        assertEquals(2503851L, quota.monthlyUsage?.resetInSec)
    }

    @Test
    fun parsesRateLimitedResponse() {
        val body = ";0x00000126;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={mine:!0,useBalance:!1," +
            "rollingUsage:\$R[1]={status:\"rate-limited\",resetInSec:3600,usagePercent:100}," +
            "weeklyUsage:\$R[2]={status:\"ok\",resetInSec:100000,usagePercent:50}," +
            "monthlyUsage:\$R[3]={status:\"ok\",resetInSec:2000000,usagePercent:30}})" +
            "(\$R[\"server-fn:1\"]))"

        val quota = OpenCodeQuotaClient.parseQuotaResponse(body)
        val rollingUsage = quota.rollingUsage!!

        assertTrue(rollingUsage.isRateLimited)
        assertEquals(100, rollingUsage.usagePercent)
        assertEquals(50, quota.weeklyUsage?.usagePercent)
    }

    @Test
    fun parsesStringsContainingBracesWithoutRegexRewrite() {
        val body = ";0x00000126;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={mine:!0,useBalance:!1," +
            "rollingUsage:\$R[1]={status:\"ok {still-json}\",resetInSec:17999,usagePercent:1}," +
            "weeklyUsage:\$R[2]={status:\"ok\",resetInSec:545090,usagePercent:6}," +
            "monthlyUsage:\$R[3]={status:\"ok\",resetInSec:2503851,usagePercent:24}})" +
            "(\$R[\"server-fn:1\"]))"

        val quota = OpenCodeQuotaClient.parseQuotaResponse(body)

        assertEquals("ok {still-json}", quota.rollingUsage?.status)
        assertEquals(1, quota.rollingUsage?.usagePercent)
    }

    @Test
    fun parsesNullResponse() {
        val body = ";0x00000001;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]=null)(\$R[\"server-fn:1\"]))"

        try {
            OpenCodeQuotaClient.parseQuotaResponse(body)
            assertTrue(false, "Should have thrown for null response")
        } catch (e: OpenCodeQuotaException) {
            assertTrue(e.message?.contains("unexpected format") == true)
        }
    }

    @Test
    fun parsesBillingInfoResponse() {
        val body = ";0x00000040;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[]," +
            "(\$R=>\$R[0]={balance:1234560000,customerID:\"cus_123\"})(\$R[\"server-fn:1\"]))"

        val billingInfo = OpenCodeQuotaClient.parseBillingInfoResponse(body)

        assertEquals(1234560000L, billingInfo.balance)
    }
}
