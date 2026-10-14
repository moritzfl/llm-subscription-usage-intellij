package de.moritzf.quota.litellm

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.compatible.CompatibleEndpoint
import de.moritzf.quota.compatible.proxy.CompatibleSubscriptionProxyProvider
import de.moritzf.quota.shared.JsonSupport
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.serialization.json.jsonObject

class LiteLlmClientTest {
    @Test
    fun enrichesOnlyAccessiblePublicNamesAndCachesMetadataWithTheKey() {
        val calls = CopyOnWriteArrayList<String>()
        server({ path, auth ->
            calls += "$path $auth"
            200 to
                if (path.endsWith("/models")) """{"data":[{"id":"company-chat"}]}"""
                else
                    """{"data":[{"model_name":"company-chat","model_info":{"id":"private-deployment-id","mode":"chat","supports_vision":true,"supports_pdf_input":true,"supports_function_calling":true,"max_input_tokens":123456,"input_cost_per_token":0.000001}},{"model_name":"admin-only","model_info":{"supports_vision":true}}]}"""
        }) { endpoint ->
            var key = "first"
            val provider = CompatibleSubscriptionProxyProvider("one", "Gateway", endpoint, { key })
            val model = provider.models().single()
            assertEquals("api-one/company-chat", model.localId)
            assertEquals(123456, model.maxInputTokens)
            assertEquals(true, model.supportsVision)
            assertEquals(true, model.supportsPdfInput)
            assertEquals(true, model.supportsFunctionCalling)
            assertEquals(0.000001, model.inputCostPerToken)
            assertNull(model.outputCostPerToken)
            assertEquals(model, provider.models().single())
            assertEquals(2, calls.size)
            key = "rotated"
            provider.models()
            assertEquals(4, calls.size)
            assertTrue(calls.takeLast(2).all { it.endsWith("Bearer rotated") })
        }
    }

    @Test
    fun forbiddenMetadataKeepsManualChatAndUnknownCapabilities() {
        server({ _, _ -> 403 to "secret-error" }) { endpoint ->
            val model =
                CompatibleSubscriptionProxyProvider(
                        "one",
                        "Gateway",
                        endpoint.copy(modelIds = listOf("custom")),
                        { "key" },
                    )
                    .models()
                    .single()
            assertEquals("custom", model.upstreamId)
            assertNull(model.supportsVision)
            assertNull(model.inputCostPerToken)
        }
    }

    @Test
    fun heterogeneousDeploymentsDoNotInventCapabilitiesOrPrices() {
        server({ _, _ ->
            200 to
                """{"data":[{"model_name":"chat","model_info":{"supports_vision":true,"input_cost_per_token":0.0001}},{"model_name":"chat","model_info":{"supports_vision":false,"input_cost_per_token":0.0002}}]}"""
        }) { endpoint ->
            val model =
                CompatibleSubscriptionProxyProvider(
                        "one",
                        "Gateway",
                        endpoint.copy(modelIds = listOf("chat")),
                        { "key" },
                    )
                    .models()
                    .single()
            assertNull(model.supportsVision)
            assertNull(model.inputCostPerToken)
        }
    }

    @Test
    fun deploymentWithoutMetadataKeepsSharedAliasCapabilitiesUnknown() {
        server({ _, _ ->
            200 to
                """{"data":[{"model_name":"chat","model_info":{"supports_vision":true,"max_input_tokens":123456}},{"model_name":"chat"}]}"""
        }) { endpoint ->
            val model =
                CompatibleSubscriptionProxyProvider(
                        "one",
                        "Gateway",
                        endpoint.copy(modelIds = listOf("chat")),
                        { "key" },
                    )
                    .models()
                    .single()
            assertNull(model.supportsVision)
            assertNull(model.maxInputTokens)
        }
    }

    @Test
    fun keyBudgetUsesHeaderOnlyRetainsGatewayPrefixAndNeverExportsCredentials() {
        val calls = CopyOnWriteArrayList<Pair<String, String?>>()
        server({ path, auth ->
            calls += path to auth
            200 to
                """{"key":"secret-key","info":{"token":"secret-hash","spend":12.5,"max_budget":50,"budget_reset_at":"2030-10-01T00:00:00","blocked":false,"metadata":{"api_key":"secret"},"config":{"nested_secret":"secret"}}}"""
        }) { endpoint ->
            val quota = LiteLlmClient(endpoint).quota("secret-key")
            assertEquals(listOf("/gateway%20one/key/info" to "Bearer secret-key"), calls.toList())
            assertEquals(0.25, quota.usageFraction())
            assertEquals("2030-10-01T00:00:00Z", quota.resetsAt.toString())
            assertFalse(quota.rawJson!!.contains("secret"))
            assertFalse(quota.rawJson!!.contains("token"))
            assertTrue(quota.rawJson!!.contains("\"spend\":12.5"))
        }
    }

    @Test
    fun unsetBudgetIsUnknownAndZeroBudgetOrBlockedKeyIsExhausted() {
        fun quota(info: String) =
            LiteLlmQuota.parse(JsonSupport.json.parseToJsonElement("""{"info":$info}""").jsonObject)
        assertNull(quota("""{"spend":2,"max_budget":null}""").usageFraction())
        assertEquals(1.0, quota("""{"spend":0,"max_budget":0}""").usageFraction())
        assertEquals(1.0, quota("""{"blocked":true}""").usageFraction())
        assertNull(quota("""{"spend":-1,"max_budget":25}""").usageFraction())
        assertEquals(
            0.5,
            quota(
                    """{"spend":10,"max_budget":10,"temp_budget_increase":10,"temp_budget_expiry":"2099-01-01T00:00:00Z"}"""
                )
                .usageFraction(),
        )
    }

    private fun server(
        response: (String, String?) -> Pair<Int, String>,
        block: (CompatibleEndpoint) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (status, text) =
                response(
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.getFirst("Authorization"),
                )
            val bytes = text.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(
                CompatibleEndpoint.parse("http://127.0.0.1:${server.address.port}/gateway%20one/v1")
                    .copy(liteLlm = true)
            )
        } finally {
            server.stop(0)
        }
    }
}
