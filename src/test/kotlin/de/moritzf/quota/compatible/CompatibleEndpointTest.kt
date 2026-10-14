package de.moritzf.quota.compatible

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompatibleEndpointTest {
    @Test
    fun keepsCustomApiPathsAndNormalizesManualIds() {
        val endpoint =
            CompatibleEndpoint.parse(
                " https://example.com/custom%20api/v1/// ",
                "vendor/model\ncodestral, vendor/model\r\n",
            )
        assertEquals("https://example.com/custom%20api/v1", endpoint.baseUri.toString())
        assertEquals(listOf("vendor/model", "codestral"), endpoint.modelIds)
        assertEquals(
            "http://[::1]:1234/v1",
            CompatibleEndpoint.parse("http://[::1]:1234/v1").baseUri.toString(),
        )
    }

    @Test
    fun rejectsNonApiUrlsAndCredentialsInUrls() {
        for (url in
            listOf(
                "",
                "example.com/v1",
                "file:///tmp/api",
                "https://",
                "https://user:secret@example.com/v1",
                "https://example.com/v1?key=secret",
                "https://example.com/v1#fragment",
                "https://example.com:99999/v1",
                "https://example.com/v1/chat/completions/",
                "https://example.com/v1/models",
                "https://example.com/v1/responses",
            )) {
            assertFailsWith<IllegalArgumentException>(url) { CompatibleEndpoint.parse(url) }
        }
    }
}
