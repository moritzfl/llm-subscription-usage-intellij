package de.moritzf.quota.zai

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ZaiWebSearchClientTest {
    @Test
    fun searchesThroughThePlanMcpServerWithASession() {
        TestMcpServer(
                toolText =
                    encodedTwice(
                        """[{"title":"Node.js","link":"https://nodejs.org","content":"Node.js releases","refer":"ref_1"},{"title":"Two","link":"https://two.test"},{"title":"Three","link":"https://three.test"}]"""
                    )
            )
            .use { server ->
                val client = ZaiWebSearchClient(httpClient, server.baseUri)

                val result = client.webSearch("zai-key", "  Node.js releases  ", limit = 2)

                val items = JsonSupport.json.parseToJsonElement(result).jsonArray
                assertEquals(
                    listOf("Node.js", "Two"),
                    items.map { it.jsonObject["title"]!!.jsonPrimitive.content },
                )
                assertEquals("ref_1", items[0].jsonObject["refer"]!!.jsonPrimitive.content)

                val requests = server.requests.toList()
                assertEquals(
                    listOf("initialize", "notifications/initialized", "tools/list", "tools/call"),
                    requests.map { it.method },
                )
                assertTrue(requests.all { it.path == "/api/mcp/web_search_prime/mcp" })
                assertTrue(requests.all { it.header("Authorization") == "Bearer zai-key" })
                assertTrue(
                    requests.all { it.header("Accept") == "application/json, text/event-stream" }
                )
                assertEquals(null, requests[0].header("Mcp-Session-Id"))
                assertTrue(requests.drop(1).all { it.header("Mcp-Session-Id") == "session-1" })
                val call = requests.last().params
                assertEquals("web_search_prime", call["name"]!!.jsonPrimitive.content)
                val arguments = call["arguments"]!!.jsonObject
                assertEquals("Node.js releases", arguments["search_query"]!!.jsonPrimitive.content)
                assertEquals("medium", arguments["content_size"]!!.jsonPrimitive.content)
                assertEquals("us", arguments["location"]!!.jsonPrimitive.content)
                assertTrue("count" !in arguments, "the server rejects unknown arguments")
            }
    }

    @Test
    fun reusesTheSessionAndIncludesContentWhenAsked() {
        TestMcpServer(toolText = encodedTwice("""[]""")).use { server ->
            val client = ZaiWebSearchClient(httpClient, server.baseUri)

            client.webSearch("zai-key", "first")
            client.webSearch("zai-key", "second", includeContent = true)

            val methods = server.requests.map { it.method }
            assertEquals(1, methods.count { it == "initialize" })
            assertEquals(2, methods.count { it == "tools/call" })
            assertEquals(
                "high",
                server.requests
                    .last()
                    .params["arguments"]!!
                    .jsonObject["content_size"]!!
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun startsANewSessionOnceWhenTheServerForgotIt() {
        TestMcpServer(toolText = encodedTwice("""[]"""), forgetFirstSession = true).use { server ->
            val client = ZaiWebSearchClient(httpClient, server.baseUri)

            assertEquals("[]", client.webSearch("zai-key", "query"))

            assertEquals(2, server.requests.count { it.method == "initialize" })
        }
    }

    @Test
    fun readsPagesThroughTheReaderServer() {
        TestMcpServer(
                toolName = "webReader",
                toolText =
                    encodedTwice(
                        """{"title":"Example","content":"# Example","url":"https://www.example.com"}"""
                    ),
            )
            .use { server ->
                val client = ZaiWebSearchClient(httpClient, server.baseUri)

                val result = client.webFetch("zai-key", "www.example.com")

                assertEquals(
                    "# Example",
                    JsonSupport.json
                        .parseToJsonElement(result)
                        .jsonObject["content"]!!
                        .jsonPrimitive
                        .content,
                )
                val call = server.requests.last()
                assertEquals("/api/mcp/web_reader/mcp", call.path)
                assertEquals("webReader", call.params["name"]!!.jsonPrimitive.content)
                val arguments = call.params["arguments"]!!.jsonObject
                assertEquals("https://www.example.com", arguments["url"]!!.jsonPrimitive.content)
                assertEquals("markdown", arguments["return_format"]!!.jsonPrimitive.content)
                assertEquals(false, arguments["retain_images"]!!.jsonPrimitive.boolean)
            }
    }

    @Test
    fun reportsRejectedKeysAndToolErrors() {
        TestMcpServer(
                accountError =
                    """{"code":1001,"msg":"Authentication parameter not received","success":false}"""
            )
            .use { server ->
                val error =
                    assertFailsWith<ZaiQuotaException> {
                        ZaiWebSearchClient(httpClient, server.baseUri).webSearch("bad", "query")
                    }
                assertEquals("API key invalid. Check your Z.ai API key.", error.message)
            }
        TestMcpServer(toolError = "MCP error -400: Please Enter the Correct URL Format").use {
            server ->
            val error =
                assertFailsWith<ZaiQuotaException> {
                    ZaiWebSearchClient(httpClient, server.baseUri)
                        .webFetch("zai-key", "https://example.com")
                }
            assertTrue(error.message!!.contains("Correct URL Format"), error.message)
        }
    }

    @Test
    fun rejectsBlankQueriesBeforeCallingTheServer() {
        TestMcpServer(toolText = "[]").use { server ->
            val exception =
                assertFailsWith<ZaiQuotaException> {
                    ZaiWebSearchClient(httpClient, server.baseUri).webSearch("zai-key", "   ")
                }

            assertEquals("Search query is required.", exception.message)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun parsesServerSentEventsAndDoubleEncodedText() {
        val message =
            ZaiWebSearchClient.parseMessage(
                "id:1\r\nevent:message\r\ndata:{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\r\n\r\n"
            )
        assertEquals(true, message?.get("result")?.jsonObject?.get("ok")?.jsonPrimitive?.boolean)
        assertEquals(JsonArray(emptyList()), ZaiWebSearchClient.decodeResult("\"[]\""))
        assertEquals(JsonArray(emptyList()), ZaiWebSearchClient.decodeResult(encodedTwice("[]")))
        assertEquals(JsonPrimitive("plain"), ZaiWebSearchClient.decodeResult("\"plain\""))
        assertEquals(null, ZaiWebSearchClient.decodeResult("not json"))
    }

    private fun encodedTwice(json: String): String =
        JsonPrimitive(JsonPrimitive(json).toString()).content

    private class TestMcpServer(
        private val toolName: String = "web_search_prime",
        private val toolText: String = "[]",
        private val forgetFirstSession: Boolean = false,
        private val accountError: String? = null,
        private val toolError: String? = null,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        private var sessions = 0
        private var forgotten = false
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val payload = JsonSupport.json.parseToJsonElement(body).jsonObject
                val method = payload["method"]!!.jsonPrimitive.content
                requests +=
                    CapturedRequest(
                        method,
                        exchange.requestURI.rawPath,
                        exchange.requestHeaders.mapValues { it.value.toList() },
                        payload["params"]?.jsonObject
                            ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                    )
                fun sse(result: String): String =
                    "id:1\nevent:message\ndata:{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$result}\n\n"
                val (status, contentType, response) =
                    when {
                        accountError != null -> Triple(200, "application/json", accountError)
                        method == "initialize" -> {
                            sessions++
                            exchange.responseHeaders.set("mcp-session-id", "session-$sessions")
                            Triple(
                                200,
                                "text/event-stream",
                                sse("""{"protocolVersion":"2024-11-05"}"""),
                            )
                        }
                        method == "notifications/initialized" -> Triple(202, "text/plain", "")
                        method == "tools/list" ->
                            Triple(
                                200,
                                "text/event-stream",
                                sse("""{"tools":[{"name":"$toolName"}]}"""),
                            )
                        forgetFirstSession && !forgotten -> {
                            forgotten = true
                            Triple(
                                200,
                                "text/event-stream",
                                sse(
                                    """{"content":[{"type":"text","text":"MCP error -401: Api key not found"}],"isError":true}"""
                                ),
                            )
                        }
                        toolError != null ->
                            Triple(
                                200,
                                "text/event-stream",
                                sse(
                                    """{"content":[{"type":"text","text":${JsonPrimitive(toolError)}}],"isError":true}"""
                                ),
                            )
                        else ->
                            Triple(
                                200,
                                "text/event-stream",
                                sse(
                                    """{"content":[{"type":"text","text":${JsonPrimitive(toolText)}}],"isError":false}"""
                                ),
                            )
                    }
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", contentType)
                exchange.sendResponseHeaders(
                    status,
                    if (bytes.isEmpty()) -1 else bytes.size.toLong(),
                )
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/api/mcp/")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val params: kotlinx.serialization.json.JsonObject,
    ) {
        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
    }

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
