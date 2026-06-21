package de.moritzf.quota.ollama

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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OllamaWebSearchClientTest {
    @Test
    fun postsWebSearchRequestWithApiKeyAndOptions() {
        TestOllamaServer(
            responseBody = """
                {
                  "results": [
                    {
                      "title": "First",
                      "url": "https://example.test/first",
                      "content": "First result content"
                    },
                    {
                      "title": "Second",
                      "url": "https://example.test/second",
                      "content": "Second result content"
                    }
                  ]
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch("ollama-key", "  Ollama docs  ", limit = 50, includeContent = false)

            val response = parseObject(result)
            val results = response["results"]!!.jsonArray
            assertEquals(2, results.size)
            val item = results[0].jsonObject
            assertEquals("First", item["title"]!!.jsonPrimitive.content)
            assertEquals("https://example.test/first", item["url"]!!.jsonPrimitive.content)
            assertEquals("First result content", item["content"]!!.jsonPrimitive.content)

            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/web_search", request.path)
            assertEquals("Bearer ollama-key", request.firstHeader("Authorization"))
            assertEquals("application/json", request.firstHeader("Accept"))

            val requestBody = parseObject(request.body)
            assertEquals("Ollama docs", requestBody["query"]!!.jsonPrimitive.content)
            assertEquals(10, requestBody["max_results"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun includesContentWhenRequested() {
        TestOllamaServer(
            responseBody = """
                {"results":[{"title":"First","url":"https://example.test/first","content":"Full content"}]}
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch("ollama-key", "Ollama docs", includeContent = true)

            val item = parseObject(result)["results"]!!.jsonArray[0].jsonObject
            assertEquals("Full content", item["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsBlankQueriesBeforeCallingUpstream() {
        TestOllamaServer().use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<OllamaQuotaException> {
                client.webSearch("ollama-key", "   ")
            }

            assertEquals("Search query is required.", exception.message)
            assertNull(server.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun newClient(server: TestOllamaServer): OllamaWebSearchClient {
        return OllamaWebSearchClient(httpClient = httpClient, baseUri = server.baseUri)
    }

    private class TestOllamaServer(
        private val responseBody: String = "{\"results\":[]}",
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
