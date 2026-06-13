package de.moritzf.quota.minimax

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MiniMaxWebSearchClientTest {
    @Test
    fun postsWebSearchRequestWithApiKeyAndOptions() {
        TestMiniMaxServer(
            responseBody = """
                {
                  "organic": [
                    {
                      "title": "First",
                      "link": "https://example.test/first",
                      "snippet": "First result",
                      "date": "2026-06-13"
                    },
                    {
                      "title": "Second",
                      "link": "https://example.test/second",
                      "snippet": "Second result"
                    }
                  ],
                  "related_searches": [{"query":"Kimi docs"}],
                  "base_resp": {"status_code": 0, "status_msg": ""}
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch("minimax-key", MiniMaxRegion.GLOBAL, "  Kimi Code docs  ", limit = 1)

            val response = parseObject(result)
            assertEquals("GLOBAL", response["region"]!!.jsonPrimitive.content)
            val results = response["search_results"]!!.jsonArray
            assertEquals(1, results.size)
            val item = results[0].jsonObject
            assertEquals("First", item["title"]!!.jsonPrimitive.content)
            assertEquals("https://example.test/first", item["url"]!!.jsonPrimitive.content)
            assertEquals("First result", item["snippet"]!!.jsonPrimitive.content)
            assertEquals("2026-06-13", item["date"]!!.jsonPrimitive.content)
            assertEquals("Kimi docs", response["related_searches"]!!.jsonArray[0].jsonObject["query"]!!.jsonPrimitive.content)

            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/v1/coding_plan/search", request.path)
            assertEquals("Bearer minimax-key", request.firstHeader("Authorization"))
            assertEquals("Minimax-MCP", request.firstHeader("MM-API-Source"))
            assertEquals("Kimi Code docs", parseObject(request.body)["q"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun reportsApiErrorsFromBaseResponse() {
        TestMiniMaxServer(
            responseBody = """
                {"base_resp":{"status_code":1004,"status_msg":"invalid api key"}}
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<MiniMaxQuotaException> {
                client.webSearch("minimax-key", MiniMaxRegion.GLOBAL, "Kimi Code docs")
            }

            assertEquals("MiniMax web search failed: invalid api key", exception.message)
            assertEquals(1004, exception.statusCode)
        }
    }

    @Test
    fun rejectsBlankQueriesBeforeCallingUpstream() {
        TestMiniMaxServer().use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<MiniMaxQuotaException> {
                client.webSearch("minimax-key", MiniMaxRegion.GLOBAL, "   ")
            }

            assertEquals("Search query is required.", exception.message)
            assertNull(server.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun newClient(server: TestMiniMaxServer): MiniMaxWebSearchClient {
        return MiniMaxWebSearchClient(
            httpClient = httpClient,
            globalApiHost = server.baseUri,
            cnApiHost = server.baseUri,
        )
    }

    private class TestMiniMaxServer(
        private val responseBody: String = "{\"organic\":[],\"base_resp\":{\"status_code\":0}}",
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
