package de.moritzf.quota.github.proxy

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeRemoteImageFetcherTest {
    @Test
    fun returnsSuccessfulHopWhenUriStaysSafe() {
        val png = byteArrayOf(1, 2, 3)
        val hops = ArrayList<URI>()
        val result = SafeRemoteImageFetcher.get(
            "https://cdn.example/a.png",
            send = { uri ->
                hops += uri
                RemoteImageHop(200, uri, null, "image/png", png)
            },
            isSafe = { true },
        )

        assertEquals(listOf(URI.create("https://cdn.example/a.png")), hops)
        assertEquals(png.toList(), result?.body?.toList())
    }

    @Test
    fun doesNotSendWhenInitialUriIsUnsafe() {
        val hops = ArrayList<URI>()
        val result = SafeRemoteImageFetcher.get(
            "http://169.254.169.254/latest/meta-data/",
            send = { uri ->
                hops += uri
                RemoteImageHop(200, uri, null, "text/plain", byteArrayOf(1))
            },
            isSafe = { false },
        )

        assertNull(result)
        assertTrue(hops.isEmpty())
    }

    @Test
    fun doesNotFollowRedirectToUnsafeUri() {
        val hops = ArrayList<URI>()
        val result = SafeRemoteImageFetcher.get(
            "https://cdn.example/a.png",
            send = { uri ->
                hops += uri
                RemoteImageHop(
                    302,
                    uri,
                    "http://169.254.169.254/latest/meta-data/",
                    null,
                    byteArrayOf(),
                )
            },
            isSafe = { uri -> uri.host == "cdn.example" },
        )

        assertNull(result)
        assertEquals(listOf(URI.create("https://cdn.example/a.png")), hops)
    }

    @Test
    fun followsSafeRedirectThenReturnsBody() {
        val png = byteArrayOf(9, 8, 7)
        val hops = ArrayList<URI>()
        val result = SafeRemoteImageFetcher.get(
            "https://cdn.example/a.png",
            send = { uri ->
                hops += uri
                if (uri.path == "/a.png") {
                    RemoteImageHop(302, uri, "/b.png", null, byteArrayOf())
                } else {
                    RemoteImageHop(200, uri, null, "image/png", png)
                }
            },
            isSafe = { uri -> uri.host == "cdn.example" },
        )

        assertEquals(
            listOf(URI.create("https://cdn.example/a.png"), URI.create("https://cdn.example/b.png")),
            hops,
        )
        assertEquals(png.toList(), result?.body?.toList())
    }
}
