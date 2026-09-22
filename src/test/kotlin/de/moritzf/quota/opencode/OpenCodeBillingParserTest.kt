package de.moritzf.quota.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenCodeBillingParserTest {
    @Test
    fun prepaidBalanceRetainsMicroCentPrecisionIncludingZeroAndDebt() {
        for (amount in listOf("0", "-125000000", "1234567890")) {
            val body = OpenCodeQuotaClientTest.BILLING_STATUS.replace("1234567890", amount)
            assertEquals(amount.toLong(), OpenCodeQuotaClient.parseBillingBalance(body))
        }
    }

    @Test
    fun availableCreditNeverReplacesMissingOrMalformedBalance() {
        for (amount in listOf("null", "true", "\"NaN\"", "\"Infinity\"", "\"1.5\"")) {
            assertNull(OpenCodeQuotaClient.parseBillingBalance(OpenCodeQuotaClientTest.BILLING_STATUS.replace("\"1234567890\"", amount)))
        }
        assertNull(OpenCodeQuotaClient.parseBillingBalance("""{"billingMode":"prepaid","mode":"pay-as-you-go","availableMicroCents":"100"}"""))
    }

    @Test
    fun missingAncillaryFieldsAndNumericRepresentationsDoNotHideWalletBalance() {
        for (body in listOf("""{"balanceMicroCents":"1234567890"}""", """{"balanceMicroCents":1234567890.0,"newField":true}""")) {
            assertEquals(1234567890L, OpenCodeQuotaClient.parseBillingBalance(body))
        }
    }

    @Test
    fun otherBillingModesAreNotPrepaidBalances() {
        for (mode in listOf("seat", "credit", "legacy")) {
            assertNull(OpenCodeQuotaClient.parseBillingBalance(OpenCodeQuotaClientTest.BILLING_STATUS.replace("prepaid", mode)))
        }
    }
}
