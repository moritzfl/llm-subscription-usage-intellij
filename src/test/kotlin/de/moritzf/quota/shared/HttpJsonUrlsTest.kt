package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpJsonUrlsTest {
    @Test
    fun findsNestedVideoUrl() {
        assertEquals(
            "https://cdn.example/v.mp4",
            HttpJsonUrls.first(
                """{"request_id":"vid-1","video":{"url":"https://cdn.example/v.mp4"}}"""
            ),
        )
        assertEquals(
            "https://cdn.example/a.mp4",
            HttpJsonUrls.first("""{"video_url":"https://cdn.example/a.mp4"}"""),
        )
        assertNull(HttpJsonUrls.first("""{"url":"/relative"}"""))
        assertNull(HttpJsonUrls.first("not-json"))
    }
}
