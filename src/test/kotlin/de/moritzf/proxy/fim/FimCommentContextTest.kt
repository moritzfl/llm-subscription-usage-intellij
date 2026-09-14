package de.moritzf.proxy.fim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FimCommentContextTest {
    @Test
    fun detectsJavadocStarLine() {
        val comment = assertNotNull(FimCommentContext.detect("/**\n * ", "kt"))
        assertEquals("*", comment.marker)
        assertEquals("*/", comment.closer)
    }

    @Test
    fun detectsTrailingLineComment() {
        val comment = assertNotNull(FimCommentContext.detect("    foo(); // ", "kt"))
        assertEquals("//", comment.marker)
        assertNull(comment.closer)
    }

    @Test
    fun ignoresMarkersInsideStrings() {
        assertNull(FimCommentContext.detect("const glob = \"/*", "kt"))
        assertNull(FimCommentContext.detect("val url = \"http://", "kt"))
        assertNull(FimCommentContext.detect("val sql = \"SELECT 1 -- ", "kt"))
    }

    @Test
    fun ignoresCompletedBlockBeforeCode() {
        assertNull(FimCommentContext.detect("foo(); /* n */\nval x = ", "kt"))
    }

    @Test
    fun detectsUnclosedBlockOnNextLine() {
        val comment = assertNotNull(FimCommentContext.detect("foo(); /*\n", "kt"))
        assertEquals("/*", comment.marker)
        assertEquals("*/", comment.closer)
    }

    @Test
    fun pythonHashNotSlashDivision() {
        assertNull(FimCommentContext.detect("a // b", "py"))
        val comment = assertNotNull(FimCommentContext.detect("    # ", "py"))
        assertEquals("#", comment.marker)
    }

    @Test
    fun markdownStarIsNotComment() {
        assertNull(FimCommentContext.detect("# Example\n\n*", "md"))
    }
}
