package de.moritzf.quota.idea.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
