package de.moritzf.quota.minimax

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MiniMaxVisionClientTest {
    private class Captured(
        val host: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    @Test
    fun sendsTheLocalImageAsADataUriToTheRegionsHost() {
        val png = Files.createTempFile("minimax-vision", ".png")
        Files.write(png, PNG_BYTES)
        withUpstream(
            """{"content":"A red dot.","base_resp":{"status_code":0,"status_msg":"success"}}"""
        ) { global, cn, requests ->
            val client = MiniMaxVisionClient(globalApiHost = global, cnApiHost = cn)

            assertEquals(
                "A red dot.",
                client.ask(
                    " key ",
                    MiniMaxRegion.GLOBAL,
                    localFile = png,
                    prompt = " What is it? ",
                ),
            )
            val request = assertNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals("global", request.host)
            assertEquals("/v1/coding_plan/vlm", request.path)
            assertEquals("Bearer key", request.headers["authorization"])
            assertEquals("Minimax-MCP", request.headers["mm-api-source"])
            val body = JsonSupport.json.parseToJsonElement(request.body) as JsonObject
            assertEquals("What is it?", body["prompt"]?.jsonPrimitive?.content)
            assertTrue(
                body["image_url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"),
                request.body,
            )

            client.ask(
                "key",
                MiniMaxRegion.CN,
                imageUrl = "data:image/png;base64,AAAA",
                prompt = "x",
            )
            assertEquals("cn", assertNotNull(requests.poll(5, TimeUnit.SECONDS)).host)
        }
    }

    @Test
    fun remoteUrlsNeedALocalFileAndAreNotSent() {
        withUpstream("""{"content":"x"}""") { global, cn, requests ->
            val exception =
                assertFailsWith<MiniMaxQuotaException> {
                    MiniMaxVisionClient(globalApiHost = global, cnApiHost = cn)
                        .ask(
                            "key",
                            MiniMaxRegion.GLOBAL,
                            imageUrl = "https://example.com/a.png",
                            prompt = "x",
                        )
                }
            assertEquals("MiniMax vision needs a local image file in localFile.", exception.message)
            assertEquals(null, requests.poll(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun providerErrorsInTheBodyAreReported() {
        val auth =
            assertFailsWith<MiniMaxQuotaException> {
                MiniMaxVisionClient.parseAnswer(
                    """{"base_resp":{"status_code":2049,"status_msg":"invalid api key"}}"""
                )
            }
        assertEquals(401, auth.statusCode)
        val other =
            assertFailsWith<MiniMaxQuotaException> {
                MiniMaxVisionClient.parseAnswer(
                    """{"base_resp":{"status_code":1008,"status_msg":"insufficient balance"}}"""
                )
            }
        assertEquals("MiniMax image analysis failed: insufficient balance", other.message)
        assertFailsWith<MiniMaxQuotaException> {
            MiniMaxVisionClient.parseAnswer("""{"content":""}""")
        }
        assertFailsWith<MiniMaxQuotaException> { MiniMaxVisionClient.parseAnswer("<html>") }
    }

    private fun withUpstream(
        response: String,
        block: (global: URI, cn: URI, requests: LinkedBlockingQueue<Captured>) -> Unit,
    ) {
        val requests = LinkedBlockingQueue<Captured>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        for (host in listOf("global", "cn")) {
            server.createContext("/$host/") { exchange ->
                requests +=
                    Captured(
                        host,
                        exchange.requestURI.path.removePrefix("/$host"),
                        exchange.requestHeaders.entries.associate { (name, values) ->
                            name.lowercase() to values.first()
                        },
                        exchange.requestBody.readBytes().decodeToString(),
                    )
                val bytes = response.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            block(URI.create("$base/global/"), URI.create("$base/cn/"), requests)
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        val PNG_BYTES: ByteArray =
            java.util.Base64.getDecoder()
                .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="
                )
    }
}
