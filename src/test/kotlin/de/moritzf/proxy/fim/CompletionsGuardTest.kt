package de.moritzf.proxy.fim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

class CompletionsGuardTest {
    @Test
    fun skipsWhenDisabled() {
        val guard = CompletionsGuard()
        val decision = guard.tryStart("k", CompletionsConfig(enabled = false, modelLocalId = "oa-gpt-5.5"), Job())
        assertIs<GuardDecision.Skip>(decision)
        assertEquals("disabled", decision.reason)
    }

    @Test
    fun skipsWhenNoModel() {
        val guard = CompletionsGuard()
        val decision = guard.tryStart("k", CompletionsConfig(enabled = true, modelLocalId = ""), Job())
        assertIs<GuardDecision.Skip>(decision)
    }

    @Test
    fun clampsMaxTokens() {
        val config = CompletionsConfig(maxOutputTokens = 128)
        assertEquals(128, CompletionsGuard.clampMaxTokens(10_000, config))
        assertEquals(64, CompletionsGuard.clampMaxTokens(64, config))
        assertEquals(128, CompletionsGuard.clampMaxTokens(null, config))
    }

    @Test
    fun budgetsExtraFilesThenPrefix() {
        val ctx = FimContext(
            schema = FimSchema.QWEN,
            prefix = "ABCDEFGHIJ",
            suffix = "XYZ",
            extraFiles = listOf(FimFileSlice("big.kt", "x".repeat(50))),
        )

        val budgeted = CompletionsGuard.budget(ctx, maxChars = 8)

        assertTrue(budgeted.extraFiles.isEmpty())
        assertEquals("XYZ".take(8 / 3), budgeted.suffix)
        assertTrue(budgeted.prefix.length <= 8)
        assertTrue(budgeted.prefix.isEmpty() || "ABCDEFGHIJ".endsWith(budgeted.prefix))
    }

    @Test
    fun enforcesMinInterval() {
        var now = 1_000L
        val guard = CompletionsGuard { now }
        val config = CompletionsConfig(
            enabled = true,
            modelLocalId = "oa-gpt-5.5",
            minIntervalMillis = 500,
            maxRequestsPerMinute = 20,
        )
        assertIs<GuardDecision.Allow>(guard.tryStart("k", config, Job(), fingerprint = "a"))
        now = 1_200L
        val skip = guard.tryStart("k", config, Job(), fingerprint = "b")
        assertIs<GuardDecision.Skip>(skip)
        assertEquals("min-interval", skip.reason)
    }

    @Test
    fun joinsInFlightSameFingerprint() {
        var now = 1_000L
        val guard = CompletionsGuard { now }
        val config = CompletionsConfig(
            enabled = true,
            modelLocalId = "oa-gpt-5.5",
            minIntervalMillis = 500,
            maxRequestsPerMinute = 20,
        )
        val producer = Job()
        assertIs<GuardDecision.Allow>(guard.tryStart("k", config, producer, fingerprint = "hole"))
        now = 1_200L
        val joined = guard.tryStart("k", config, Job(), fingerprint = "hole")
        assertIs<GuardDecision.Join>(joined)
        assertTrue(producer.isActive)
        guard.publish("k", producer, "return a + b")
        assertEquals("return a + b", runBlocking { joined.result.await() })
    }

    @Test
    fun cancelsInFlightOnDifferentFingerprint() {
        var now = 1_000L
        val guard = CompletionsGuard { now }
        val config = CompletionsConfig(
            enabled = true,
            modelLocalId = "oa-gpt-5.5",
            minIntervalMillis = 500,
            maxRequestsPerMinute = 20,
        )
        val producer = Job()
        assertIs<GuardDecision.Allow>(guard.tryStart("k", config, producer, fingerprint = "old"))
        now = 2_000L
        assertIs<GuardDecision.Allow>(guard.tryStart("k", config, Job(), fingerprint = "new"))
        assertTrue(producer.isCancelled)
    }
}
