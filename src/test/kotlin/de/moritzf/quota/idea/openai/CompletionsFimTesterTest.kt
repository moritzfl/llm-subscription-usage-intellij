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
    fun reportShowsSampleCursorAndAssembledFunction() {
        val result = CompletionsFimTester.formatSuccess("a + b", elapsedMs = 842)

        assertTrue(result.ok)
        assertTrue(result.status.contains("usable"))
        assertTrue(result.report.contains("return |"))
        assertTrue(result.report.contains("Inserted (5 chars, 842 ms):"))
        assertTrue(result.report.contains("a + b"))
        assertTrue(result.report.contains("fun add(a: Int, b: Int): Int {\n    return a + b\n}"))
    }

    @Test
    fun emptyInsertIsNotOk() {
        val result = CompletionsFimTester.formatSuccess("", elapsedMs = 12)
        assertFalse(result.ok)
        assertEquals("Empty insert", result.status)
        assertTrue(result.report.contains("Inserted nothing"))
    }
}
