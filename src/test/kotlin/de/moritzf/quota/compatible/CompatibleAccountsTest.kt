package de.moritzf.quota.compatible

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.compatible.compatibleAccount
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaSettingsState
import kotlin.test.*

class CompatibleAccountsTest {
    @Test
    fun multipleIndependentConnectionsRequireExplicitAccountEvenWhenOneIsDefault() {
        for (type in listOf(QuotaProviderType.OPENAI_COMPATIBLE, QuotaProviderType.LITELLM)) {
            val a = ProviderAccount("first", type.id, "One", isDefault = true)
            val b = ProviderAccount("second", type.id, "Two", allowFailover = true)
            val settings = QuotaSettingsState().apply { accounts = mutableListOf(a, b) }
            assertFailsWith<IllegalStateException> { compatibleAccount(type, settings = settings) }
            assertEquals("second", compatibleAccount(type, "Two", settings).id)
            assertEquals("first", compatibleAccount(type, "first", settings).id)
            assertFailsWith<IllegalStateException> { compatibleAccount(type, "missing", settings) }
            settings.accounts = mutableListOf(b)
            assertEquals("second", compatibleAccount(type, settings = settings).id)
        }
    }
}
