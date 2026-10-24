package de.moritzf.proxy.model

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteReasoningCatalogTest {
    private val openRouter =
        """{"data":[
          {"id":"deepseek/deepseek-r1-0528","supported_parameters":["reasoning"],"reasoning":{"mandatory":true}},
          {"id":"anthropic/claude-sonnet-4.6","supported_parameters":["reasoning"],"reasoning":{"supported_efforts":["max","high","medium","low","none"]}},
          {"id":"meta-llama/llama-4-maverick:free","supported_parameters":["reasoning"],"reasoning":{"supported_efforts":["high"]}},
          {"id":"meta-llama/llama-4-maverick","supported_parameters":["tools"]},
          {"id":"microsoft/phi-4-reasoning-plus","supported_parameters":["reasoning","reasoning_effort"],"reasoning":{"supported_efforts":["high","medium","low"]}}
        ]}"""

    private val modelsDev =
        """{
          "moonshotai":{"models":{"kimi-k3":{"reasoning":true,"reasoning_options":[{"type":"effort","values":["low","high","max"]}]}}},
          "reseller-a":{"models":{
            "moonshotai/Kimi-K3":{"reasoning":true,"reasoning_options":[{"type":"effort","values":["none","high"]}],"canonical_model_id":"moonshotai/kimi-k3"},
            "acme/grok-x":{"reasoning":true,"reasoning_options":[{"type":"effort","values":["low","high"]}]},
            "acme/plain":{"reasoning":false}
          }},
          "reseller-b":{"models":{"grok-x":{"reasoning":true,"reasoning_options":[{"type":"effort","values":["low","high"]}]}}},
          "reseller-c":{"models":{"grok-x":{"reasoning":true,"reasoning_options":[{"type":"effort","values":["medium"]}]}}},
          "openai":{"models":{"gpt-4.1":{"reasoning":false}}}
        }"""

    @Test
    fun matchesOpenRouterNamesAcrossProvidersAndVersionSpellings() {
        val catalog = synchronous(openRouter, ReasoningCatalogs::parseOpenRouter)

        assertEquals(listOf("low", "medium", "high"), catalog.levels("Phi-4-reasoning-plus"))
        assertEquals(
            listOf("none", "low", "medium", "high", "max"),
            catalog.levels("claude-sonnet-4-6"),
        )
        // A plain entry wins over its :free variant; no reasoning parameters means none.
        assertEquals(emptyList(), catalog.levels("llama-4-maverick"))
        // Reasoning without stated levels stays unknown.
        assertNull(catalog.levels("DeepSeek-R1-0528"))
        assertNull(catalog.levels("unknown-model"))
    }

    @Test
    fun prefersTheVendorThenTheMostCommonResellerValueOnModelsDev() {
        val catalog = synchronous(modelsDev, ReasoningCatalogs::parseModelsDev)

        assertEquals(listOf("low", "high", "max"), catalog.levels("Kimi-K3"))
        assertEquals(listOf("low", "high"), catalog.levels("grok-x"))
        assertEquals(emptyList(), catalog.levels("gpt-4.1"), "the vendor marks it non-reasoning")
        assertNull(catalog.levels("plain"), "a reseller's false is not trusted")
    }

    @Test
    fun loadsInTheBackgroundAndRetriesAfterAFailure() {
        val fetches = AtomicInteger()
        var now = 0L
        val pending = mutableListOf<Runnable>()
        val catalog =
            RemoteReasoningCatalog(
                fetch = { if (fetches.incrementAndGet() == 1) null else openRouter },
                parse = ReasoningCatalogs::parseOpenRouter,
                runAsync = { pending += it },
                clock = { now },
            )

        assertNull(catalog.levels("phi-4-reasoning-plus"), "never waits for the network")
        pending.removeAt(0).run()
        assertNull(catalog.levels("phi-4-reasoning-plus"))
        assertEquals(0, pending.size, "a failed load waits before retrying")

        now += 11 * 60 * 1000L
        catalog.levels("phi-4-reasoning-plus")
        pending.removeAt(0).run()
        assertEquals(listOf("low", "medium", "high"), catalog.levels("phi-4-reasoning-plus"))
        assertEquals(2, fetches.get())
    }

    private fun synchronous(
        body: String,
        parse: (String) -> Map<String, List<String>>,
    ): RemoteReasoningCatalog =
        RemoteReasoningCatalog(fetch = { body }, parse = parse, runAsync = { it.run() }).also {
            it.levels("warm-up")
        }
}
