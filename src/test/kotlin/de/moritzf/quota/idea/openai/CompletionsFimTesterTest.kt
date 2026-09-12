package de.moritzf.quota.idea.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionsFimTesterTest {
    @Test
    fun readsChoicesText() {
        val raw = """{"id":"cmpl-1","object":"text_completion","choices":[{"text":"a + b","index":0}]}"""
        assertEquals("a + b", CompletionsFimTester.completionText(raw))
    }

    @Test
    fun returnsNullWhenChoicesMissing() {
        assertNull(CompletionsFimTester.completionText("{\"error\":true}"))
    }

    @Test
    fun successHasSampleInsertAndAssembledFunction() {
        val result = CompletionsFimTester.formatSuccess("a + b", elapsedMs = 842)

        assertTrue(result.ok)
        assertEquals("Fill-in looks usable", result.status)
        assertEquals(842L, result.elapsedMs)
        assertEquals("fun add(a: Int, b: Int): Int {\n    return |\n}", result.sample)
        assertEquals("a + b", result.insert)
        assertEquals("fun add(a: Int, b: Int): Int {\n    return a + b\n}", result.assembled)
    }

    @Test
    fun emptyInsertIsNotOk() {
        val result = CompletionsFimTester.formatSuccess("", elapsedMs = 12)
        assertFalse(result.ok)
        assertEquals("Empty insert", result.status)
        assertEquals(12L, result.elapsedMs)
        assertNull(result.insert)
        assertTrue(result.detail.orEmpty().contains("no insert text"))
    }
}
