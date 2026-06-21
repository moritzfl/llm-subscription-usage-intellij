package de.moritzf.quota.supergrok

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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SuperGrokWebSearchClientTest {
    @Test
    fun postsResponsesRequestWithWebSearchToolAndReturnsProviderJson() {
        TestGrokServer(
            responseBody = """
                {
                  "id": "resp-1",
                  "model": "grok-test",
                  "output": [
                    {
                      "type": "web_search_call",
                      "action": {
                        "type": "search",
                        "query": "xAI docs",
                        "sources": [
                          {"type":"url","url":"https://x.ai/","title":"xAI"},
                          {"type":"url","url":"https://docs.x.ai/"}
                        ]
                      }
                    },
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "xAI docs answer",
                          "annotations": [
                            {"type":"url_citation","url":"https://docs.x.ai/","title":"Docs","start_index":0,"end_index":4}
                          ]
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        ).use { server ->
            val client = newClient(server)

            val result = client.webSearch(
                accessToken = "grok-token",
                query = "  xAI docs  ",
                model = "grok-test",
                allowedDomains = "x.ai, docs.x.ai",
                maxOutputTokens = 50_000,
            )

            val response = parseObject(result)
            assertEquals("resp-1", response["id"]!!.jsonPrimitive.content)
            assertEquals("grok-test", response["model"]!!.jsonPrimitive.content)
            val output = response["output"]!!.jsonArray
            val searchAction = output[0].jsonObject["action"]!!.jsonObject
            assertEquals("xAI docs", searchAction["query"]!!.jsonPrimitive.content)
            val sources = searchAction["sources"]!!.jsonArray
            assertEquals("https://x.ai/", sources[0].jsonObject["url"]!!.jsonPrimitive.content)
            val messageContent = output[1].jsonObject["content"]!!.jsonArray[0].jsonObject
            assertEquals("xAI docs answer", messageContent["text"]!!.jsonPrimitive.content)

            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/responses", request.path)
            assertEquals("Bearer grok-token", request.firstHeader("Authorization"))
            assertEquals("application/json", request.firstHeader("Accept"))

            val requestBody = parseObject(request.body)
            assertEquals("grok-test", requestBody["model"]!!.jsonPrimitive.content)
            assertEquals(8192, requestBody["max_output_tokens"]!!.jsonPrimitive.int)
            val input = requestBody["input"]!!.jsonArray[0].jsonObject
            assertEquals("user", input["role"]!!.jsonPrimitive.content)
            assertEquals("xAI docs", input["content"]!!.jsonPrimitive.content)
            val tool = requestBody["tools"]!!.jsonArray[0].jsonObject
            assertEquals("web_search", tool["type"]!!.jsonPrimitive.content)
            val allowed = tool["filters"]!!.jsonObject["allowed_domains"]!!.jsonArray
            assertEquals("x.ai", allowed[0].jsonPrimitive.content)
            assertEquals("docs.x.ai", allowed[1].jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsBlankQueriesBeforeCallingUpstream() {
        TestGrokServer().use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<SuperGrokQuotaException> {
                client.webSearch("grok-token", "   ")
            }

            assertEquals("Search query is required.", exception.message)
            assertNull(server.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun rejectsConflictingDomainFiltersBeforeCallingUpstream() {
        TestGrokServer().use { server ->
            val client = newClient(server)

            val exception = assertFailsWith<SuperGrokQuotaException> {
                client.webSearch("grok-token", "xAI", allowedDomains = "x.ai", excludedDomains = "example.com")
            }

            assertEquals(
                "Invalid Grok web search options. allowedDomains and excludedDomains must be comma-separated " +
                    "domain names, up to 5 each, and cannot both be set.",
                exception.message,
            )
            assertNull(server.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun newClient(server: TestGrokServer): SuperGrokWebSearchClient {
        return SuperGrokWebSearchClient(httpClient = httpClient, baseUri = server.baseUri)
    }

    private class TestGrokServer(
        private val responseBody: String = "{\"output\":[]}",
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
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/")
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
