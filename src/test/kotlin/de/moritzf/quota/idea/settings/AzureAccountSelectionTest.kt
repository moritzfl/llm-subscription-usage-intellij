package de.moritzf.quota.idea.settings

import de.moritzf.quota.azure.AzureCliAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AzureAccountSelectionTest {
    @Test
    fun pinnedSubscriptionStaysSelectedAfterRefresh() {
        val accounts = listOf(account("default-sub", default = true), account("pinned-sub"))
        assertEquals("pinned-sub", preferredAzureCliAccount(accounts, "pinned-sub")?.subscriptionId)
        assertEquals("pinned-sub", preferredAzureCliAccount(accounts, "PINNED-SUB")?.subscriptionId)
    }

    @Test
    fun blankSubscriptionShowsTheCliDefaultWithoutImplyingAPin() {
        val accounts = listOf(account("other"), account("default-sub", default = true))
        assertEquals("default-sub", preferredAzureCliAccount(accounts, null)?.subscriptionId)
        assertEquals("default-sub", preferredAzureCliAccount(accounts, "  ")?.subscriptionId)
    }

    @Test
    fun missingPinIsNotReplacedByAnotherAccount() {
        val accounts = listOf(account("default-sub", default = true), account("other"))
        assertNull(preferredAzureCliAccount(accounts, "gone"))
    }

    private fun account(id: String, default: Boolean = false) = AzureCliAccount(
        subscriptionId = id,
        subscriptionName = id,
        tenantId = null,
        userName = null,
        userType = "user",
        isDefault = default,
    )
}
