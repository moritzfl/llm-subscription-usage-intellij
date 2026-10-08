package de.moritzf.proxy.model

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.auth.CredentialsProvider
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.transport.CodexHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelResolverTest {
    @Test
    fun discoveryKeepsClientVersionQueryButUsesPluginIdentityHeaders() {
        TestUpstream().use { upstream ->
            val config =
                ServerConfig(
                    "127.0.0.1",
                    1,
                    null,
                    CODEX_VERSION,
                    upstream.baseUri.toString(),
                    ServerConfig.DEFAULT_CLIENT_ID,
                    null,
                    null,
                    "",
                    false,
                    emptyMap(),
                    null,
                )
            val credentials =
                object : CredentialsProvider {
                    override fun getAuthHeaders(): Map<String, String> =
                        mapOf(
                            "Authorization" to "Bearer test-token",
                            "chatgpt-account-id" to "account-1",
                        )
                }
            val client = CodexHttpClient(config, HttpClient.newHttpClient(), credentials)

            val models = ModelResolver(client, null, CODEX_VERSION).resolveModels()

            assertEquals(listOf("gpt-test"), models)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/backend-api/codex/models?client_version=$CODEX_VERSION", request.path)
            assertNull(request.firstHeader("version"))
            assertEquals("openai-usage-quota-plugin", request.firstHeader("originator"))
            assertTrue(request.firstHeader("User-Agent")!!.startsWith("openai-usage-quota-plugin/"))
        }
    }

    private class TestUpstream : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                requests +=
                    CapturedRequest(
                        path = exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery,
                        headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    )
                val response = """{"models":[{"slug":"gpt-test"}]}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/backend-api/codex")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val path: String,
        val headers: Map<String, List<String>>,
    ) {
        fun firstHeader(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
    }

    private companion object {
        const val CODEX_VERSION = "0.145.0"
    }
}
