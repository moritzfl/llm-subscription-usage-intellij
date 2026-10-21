package de.moritzf.quota.litellm

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.compatible.CompatibleEndpoint
import de.moritzf.quota.compatible.proxy.CompatibleSubscriptionProxyProvider
import de.moritzf.quota.shared.JsonSupport
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
                """{"key":"secret-key","gateway_version":"1.80.0","info":{"token":"secret-hash","spend":12.5,"max_budget":50,"budget_reset_at":"2030-10-01T00:00:00","blocked":false,"models":["company-chat"],"model_spend":{"company-chat":12.5},"team_id":"engineering","metadata":{"api_key":"secret"},"config":{"nested_secret":"secret"}}}"""
        }) { endpoint ->
            val quota = LiteLlmClient(endpoint).quota("secret-key")
            assertEquals(listOf("/gateway%20one/key/info" to "Bearer secret-key"), calls.toList())
            assertEquals(0.25, quota.usageFraction())
            assertEquals("2030-10-01T00:00:00Z", quota.resetsAt.toString())
            assertFalse(quota.rawJson!!.contains("secret"))
            val raw = JsonSupport.json.parseToJsonElement(quota.rawJson!!).jsonObject
            assertEquals("[REDACTED]", raw["key"]!!.jsonPrimitive.content)
            assertEquals("[REDACTED]", raw["info"]!!.jsonObject["token"]!!.jsonPrimitive.content)
            assertEquals("[REDACTED]", raw["info"]!!.jsonObject["metadata"]!!.jsonPrimitive.content)
            assertEquals("[REDACTED]", raw["info"]!!.jsonObject["config"]!!.jsonPrimitive.content)
            assertEquals("1.80.0", raw["gateway_version"]!!.jsonPrimitive.content)
            assertEquals("engineering", raw["info"]!!.jsonObject["team_id"]!!.jsonPrimitive.content)
            assertTrue(raw["info"]!!.jsonObject.containsKey("model_spend"))
            assertTrue(raw["info"]!!.jsonObject.containsKey("models"))
            assertTrue(quota.rawJson!!.contains("\"spend\":12.5"))
        }
    }

    @Test
    fun retainsProviderJsonIncludingUnknownFieldsInsteadOfReconstructingQuota() {
        val response =
            """{"info":{"spend":3,"max_budget":10,"models":["chat"],"model_max_budget":{"chat":{"budget_limit":5,"time_period":"1d"}},"future_field":{"nested":[1,null,true]}} ,"gateway_status":"healthy"}"""
        server({ _, _ -> 200 to response }) { endpoint ->
            val quota = LiteLlmClient(endpoint).quota("test-key")
            assertEquals(
                JsonSupport.json.parseToJsonElement(response),
                JsonSupport.json.parseToJsonElement(quota.rawJson!!),
            )
        }
    }

    @Test
    fun retainsNativeErrorAndUnparseableResponsesForDiagnosticsWithoutEchoingTheKey() {
        for ((status, response) in
            listOf(
                403 to
                    """{"error":{"message":"Access denied for secret-key","code":"key_info_forbidden","details":{"required_role":"admin"}},"request_id":"request-123"}""",
                200 to "Not JSON",
                502 to "Gateway unavailable for secret-key",
            )) {
            server({ _, _ -> status to response }) { endpoint ->
                val failure =
                    assertFailsWith<LiteLlmResponseException> {
                        LiteLlmClient(endpoint).quota("secret-key")
                    }
                assertFalse(failure.rawResponse.contains("secret-key"))
                val expected = response.replace("secret-key", "[REDACTED]")
                val raw = JsonSupport.json.parseToJsonElement(failure.rawResponse)
                if (response.startsWith("{")) {
                    assertEquals(JsonSupport.json.parseToJsonElement(expected), raw)
                } else {
                    assertEquals(expected, raw.jsonObject["raw_response"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun unexpectedQuotaShapesRemainInspectableAndMalformedFieldsDoNotHideValidLimits() {
        for (response in
            listOf(
                "null",
                "[]",
                "42",
                "{}",
                """{"info":null}""",
                """{"info":[]}""",
                """{"unexpected":{"diagnostic":"no info object"}}""",
            )) {
            server({ _, _ -> 200 to response }) { endpoint ->
                val quota = LiteLlmClient(endpoint).quota("test-key")
                assertFalse(quota.hasUsageState())
                assertNull(quota.usageFraction())
                assertEquals(
                    JsonSupport.json.parseToJsonElement(response),
                    JsonSupport.json.parseToJsonElement(quota.rawJson!!),
                )
            }
        }
        val quota =
            LiteLlmQuota.parse(
                JsonSupport.json.parseToJsonElement(
                    """{"info":{"spend":{"unexpected":true},"max_budget":"50.5","blocked":[],"budget_reset_at":"bad-date","temp_budget_increase":{},"temp_budget_expiry":[]}}"""
                )
            )
        assertNull(quota.spend)
        assertEquals(50.5, quota.maxBudget)
        assertNull(quota.resetsAt)
        assertFalse(quota.blocked)
        assertTrue(quota.hasUsageState())
    }

    @Test
    fun mcpAllowsOnlyValidatedQuotaScalarsEvenForUnexpectedSensitiveFields() {
        val raw =
            """{"key":"secret-key","new_credential":"secret","info":{"spend":12.5,"max_budget":"50","blocked":false,"budget_reset_at":"2030-10-01T00:00:00","budget_duration":"30d","expires":null,"token":"secret-hash","future_field":"secret","metadata":{"secret":"secret"},"tpm_limit":{"token":"secret"},"rpm_limit":["secret"],"temp_budget_increase":"secret","temp_budget_expiry":"secret"}}"""
        assertEquals(
            JsonSupport.json.parseToJsonElement(
                """{"info":{"spend":12.5,"max_budget":"50","blocked":false,"budget_reset_at":"2030-10-01T00:00:00","budget_duration":"30d","expires":null}}"""
            ),
            JsonSupport.json.parseToJsonElement(LiteLlmQuota.safeQuotaJson(raw)),
        )
        for (response in
            listOf(
                "invalid-json",
                "null",
                "[]",
                "{}",
                """{"info":[]}""",
                """{"info":{"spend":"NaN","max_budget":"Infinity","blocked":"secret","budget_duration":"secret"}}""",
            )) {
            assertEquals("""{"info":{}}""", LiteLlmQuota.safeQuotaJson(response))
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
