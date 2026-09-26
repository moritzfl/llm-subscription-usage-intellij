package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSupportPrettyResponseTest {
    @Test
    fun prettyResponseIndentsCompactJsonAndLeavesPlainText() {
        val pretty = JsonSupport.prettyResponse("""{"id":1,"login":"moritzfl"}""")
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"login\""))
        assertEquals("No GitHub response yet.", JsonSupport.prettyResponse("No GitHub response yet."))
    }

    @Test
    fun prettyResponseKeepsAnErrorPrefix() {
        val pretty = JsonSupport.prettyResponse("Error: denied\n\n{\"ok\":false}")
        assertTrue(pretty.startsWith("Error: denied\n\n"))
        assertTrue(pretty.contains("\"ok\""))
        assertTrue(pretty.contains("\n"))
    }
}
