package de.moritzf.quota.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenCodeBillingParserTest {

    @Test
    fun parseBillingInfoWithDate() {
        val body = ";0x0000027f;((self.\$R=self.\$R||{})[\"server-fn:1\"]=[],(\$R=>\$R[0]={customerID:\"cus_U99EU4pkNMZdNZ\",paymentMethodID:\"pm_1TPywQ2StuRr0lbXhXFWcCc3\",paymentMethodType:\"link\",paymentMethodLast4:null,balance:1662818620,reload:!0,reloadAmount:20,reloadAmountMin:10,reloadTrigger:5,reloadTriggerMin:5,monthlyLimit:20,monthlyUsage:337181380,timeMonthlyUsageUpdated:\$R[1]=new Date(\"2026-04-26T18:13:38.000Z\"),reloadError:null,timeReloadError:null,subscription:null,subscriptionID:null,subscriptionPlan:null,timeSubscriptionBooked:null,timeSubscriptionSelected:null,lite:\$R[2]={useBalance:!0},liteSubscriptionID:\"sub_1TNxr92StuRr0lbXL0bBoNwH\"})(\$R[\"server-fn:1\"]))"

        val info = OpenCodeQuotaClient.parseBillingInfoResponse(body)
        assertNotNull(info)
        assertEquals(1662818620L, info.balance)
    }
}
