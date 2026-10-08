package de.moritzf.quota.idea

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuotaIndicatorSourceTest {
    @Test
    fun storageIdMatchesProviderIdForEveryProviderSource() {
        for (type in QuotaProviderType.entries) {
            val source = QuotaIndicatorSource.forProvider(type)
            assertEquals(type.id, source.storageId, type.name)
            assertEquals(type, source.providerType)
        }
        assertEquals(QuotaIndicatorSource.LAST_USED_ID, QuotaIndicatorSource.LAST_USED.storageId)
        assertNull(QuotaIndicatorSource.LAST_USED.providerType)
    }

    @Test
    fun fromStorageValueParsesCanonicalStorageIds() {
        for (source in QuotaIndicatorSource.entries) {
            assertEquals(source, QuotaIndicatorSource.fromStorageValue(source.storageId))
        }
    }

    @Test
    fun fromStorageValueStillAcceptsLegacyEnumNames() {
        assertEquals(QuotaIndicatorSource.OPEN_AI, QuotaIndicatorSource.fromStorageValue("OPEN_AI"))
        assertEquals(
            QuotaIndicatorSource.OPEN_CODE,
            QuotaIndicatorSource.fromStorageValue("open_code"),
        )
        assertEquals(
            QuotaIndicatorSource.LAST_USED,
            QuotaIndicatorSource.fromStorageValue("LAST_USED"),
        )
    }

    @Test
    fun fromStorageValueFallsBackToOpenAiForUnknown() {
        assertEquals(QuotaIndicatorSource.OPEN_AI, QuotaIndicatorSource.fromStorageValue(null))
        assertEquals(QuotaIndicatorSource.OPEN_AI, QuotaIndicatorSource.fromStorageValue(""))
        assertEquals(QuotaIndicatorSource.OPEN_AI, QuotaIndicatorSource.fromStorageValue("nope"))
    }
}
