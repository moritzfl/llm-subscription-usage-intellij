package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLimitsTest {
    @Test
    fun allowsDocumentsUpToOneGigabyte() {
        assertNull(DocumentLimits.inlineOverflowMessage(0))
        assertNull(DocumentLimits.inlineOverflowMessage(DocumentLimits.MAX_INLINE_BYTES))
    }

    @Test
    fun rejectsDocumentsOverOneGigabyte() {
        val message =
            assertNotNull(DocumentLimits.inlineOverflowMessage(DocumentLimits.MAX_INLINE_BYTES + 1))
        assertTrue(message.contains("too large"))
        assertTrue(message.contains("1 GB"))
        assertTrue(message.contains("documentUrl"))
        assertEquals(
            DocumentLimits.MAX_INLINE_BYTES + 1,
            Regex("(\\d+) bytes").find(message)!!.groupValues[1].toLong(),
        )
    }
}
