package de.moritzf.quota.idea.settings

import com.sun.net.httpserver.HttpServer
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProxyTestSettingsTest {
    @Test
    fun completionUsesUnsavedEndpointKeyModelAndLimitsWithoutRunningTheConfiguredProxy() {
        val requests = CopyOnWriteArrayList<Pair<String?, String>>()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/draft/v1/chat/completions") { exchange ->
            requests +=
                exchange.requestHeaders.getFirst("Authorization") to
                    exchange.requestBody.bufferedReader().readText()
            val bytes = """{"choices":[{"message":{"content":"a + b"}}]}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()
        try {
            val saved = QuotaSettingsState().apply { proxyCompletionsEnabled = false }
            val account = saved.addAccount(QuotaProviderType.OPENAI_COMPATIBLE)
            account.setExtra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL, "https://saved.invalid/v1")
            account.setExtra(ProviderAccount.EXTRA_COMPATIBLE_MODELS, "saved-model")
            val draft = saved.state
            draft.account(account.id)!!.apply {
                setExtra(
                    ProviderAccount.EXTRA_COMPATIBLE_BASE_URL,
                    "http://127.0.0.1:${upstream.address.port}/draft/v1",
                )
                setExtra(ProviderAccount.EXTRA_COMPATIBLE_MODELS, "draft-model")
            }
            // Completion tests make direct upstream calls even while the proxy is off.
            draft.openAiProxyPort = upstream.address.port
            draft.proxyCompletionsEnabled = true
            draft.proxyCompletionsModelId = "api-${account.id}/draft-model"
            draft.proxyCompletionsMaxOutputTokens = 123
            draft.subscriptionProxyEnabledProviders =
                mutableListOf(QuotaProviderType.OPENAI_COMPATIBLE.id)
            val test = ProxyTestSettings(draft, mapOf(account.id to "unsaved-key"))
            assertEquals(
                listOf("api-${account.id}/draft-model"),
                SubscriptionModelCatalog(test.providers()).models.map { it.localId },
            )

            repeat(2) {
                val result = test.testCompletion()
                assertTrue(result.ok, result.detail ?: result.status)
                assertEquals("a + b", result.insert)
            }
            assertEquals(2, requests.size)
            requests.forEach { (auth, body) ->
                assertEquals("Bearer unsaved-key", auth)
                val json = JsonSupport.json.parseToJsonElement(body).jsonObject
                assertEquals("draft-model", json.getValue("model").jsonPrimitive.content)
                assertEquals("123", json.getValue("max_tokens").jsonPrimitive.content)
            }
            assertFalse(saved.openAiProxyEnabled)
            assertFalse(saved.proxyCompletionsEnabled)
            assertEquals(
                "https://saved.invalid/v1",
                account.extra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL),
            )
            assertEquals("saved-model", account.extra(ProviderAccount.EXTRA_COMPATIBLE_MODELS))
        } finally {
            upstream.stop(0)
        }
    }
}
