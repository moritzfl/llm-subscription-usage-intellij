package de.moritzf.quota.zai

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ZaiWebSearchClientTest {
    @Test
    fun postsWebSearchRequestWithApiKeyAndOptions() {
        TestZaiServer(
            responseBody = """
                {
                  "id": "search-1",
                  "request_id": "request-1",
                  "search_intent": {"query":"Kimi Code"},
                  "search_result": [
                    {
                      "title": "First",
                      "link": "https://example.test/first",
                      "content": "First result content",
                      "publish_date": "2026-06-13"
                    },
                    {
                      "title": "Second",
                      "link": "https://example.test/second",
                      "content": "Second result content"
                    }
                  ]
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch("zai-key", "  Kimi Code docs  ", limit = 1, includeContent = false)

            val response = parseObject(result)
            assertEquals("search-1", response["id"]!!.jsonPrimitive.content)
            assertEquals("request-1", response["request_id"]!!.jsonPrimitive.content)
            val results = response["search_results"]!!.jsonArray
            assertEquals(1, results.size)
            val item = results[0].jsonObject
            assertEquals("First", item["title"]!!.jsonPrimitive.content)
            assertEquals("https://example.test/first", item["url"]!!.jsonPrimitive.content)
            assertEquals("First result content", item["snippet"]!!.jsonPrimitive.content)
            assertEquals("2026-06-13", item["date"]!!.jsonPrimitive.content)
            assertTrue("content" !in item)

            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/web_search", request.path)
            assertEquals("Bearer zai-key", request.firstHeader("Authorization"))
            assertEquals("application/json", request.firstHeader("Accept"))

            val requestBody = parseObject(request.body)
            assertEquals("Kimi Code docs", requestBody["search_query"]!!.jsonPrimitive.content)
            assertEquals(1, requestBody["count"]!!.jsonPrimitive.int)
            assertEquals("low", requestBody["content_size"]!!.jsonPrimitive.content)
            assertEquals(false, requestBody["include_image"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun includesContentWhenRequested() {
        TestZaiServer(
            responseBody = """
                {"search_result":{"title":"First","link":"https://example.test/first","content":"Full content"}}
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch("zai-key", "Kimi Code docs", includeContent = true)

            val item = parseObject(result)["search_results"]!!.jsonArray[0].jsonObject
            assertEquals("Full content", item["content"]!!.jsonPrimitive.content)
            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("high", parseObject(request.body)["content_size"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsBlankQueriesBeforeCallingUpstream() {
        TestZaiServer().use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<ZaiQuotaException> {
                client.webSearch("zai-key", "   ")
            }

            assertEquals("Search query is required.", exception.message)
            assertNull(server.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun newClient(server: TestZaiServer): ZaiWebSearchClient {
        return ZaiWebSearchClient(httpClient = httpClient, baseUri = server.baseUri)
    }

    private class TestZaiServer(
        private val responseBody: String = "{\"search_result\":[]}",
        private val responseStatus: Int = 200,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(responseStatus, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }

    private fun parseObject(value: String) = JsonSupport.json.parseToJsonElement(value).jsonObject

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
