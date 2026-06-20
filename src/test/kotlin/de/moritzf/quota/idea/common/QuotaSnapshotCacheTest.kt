package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.openai.OpenAiUsageResponseFixtures.proliteWithAdditionalRateLimits
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuotaSnapshotCacheTest {
    @Test
    fun preservesRawResponsesForTransientRawQuotaTypes() {
        assertEquals("ollama raw", roundTrip(QuotaProviderType.OLLAMA, OllamaQuota(plan = "Pro"), "ollama raw").rawJson)
        assertEquals("zai raw", roundTrip(QuotaProviderType.ZAI, ZaiQuota(plan = "Pro"), "zai raw").rawJson)
        assertEquals("minimax raw", roundTrip(QuotaProviderType.MINIMAX, MiniMaxQuota(plan = "Pro"), "minimax raw").rawJson)
        assertEquals("kimi raw", roundTrip(QuotaProviderType.KIMI, KimiQuota(plan = "Pro"), "kimi raw").rawJson)
        assertEquals("github raw", roundTrip(QuotaProviderType.GITHUB, GitHubQuota(plan = "Copilot Pro"), "github raw").rawJson)
        assertEquals("cursor raw", roundTrip(QuotaProviderType.CURSOR, CursorQuota(planName = "Pro"), "cursor raw").rawJson)
        assertEquals("supergrok raw", roundTrip(QuotaProviderType.SUPERGROK, SuperGrokQuota(), "supergrok raw").rawJson)
    }

    @Test
    fun decodesLegacyQuotaCacheWithoutRawResponse() {
        val legacyOllama = """{"plan":"Pro"}"""

        val decoded = QuotaSnapshotCache.decode(QuotaProviderType.OLLAMA, legacyOllama) as? OllamaQuota

        assertNotNull(decoded)
        assertEquals("Pro", decoded.plan)
        assertEquals(null, decoded.rawJson)
    }

    @Test
    fun redactsSecretLikeFieldsFromPersistedRawResponses() {
        val quota = OllamaQuota(plan = "Pro")
        quota.rawJson = """
            {
              "plan": "Pro",
              "access_token": "access-secret",
              "headers": {
                "Authorization": "Bearer secret",
                "x-api-key": "api-secret"
              },
              "total_tokens": 123,
              "items": [
                {"refreshToken": "refresh-secret", "usage": 42}
              ]
            }
        """.trimIndent()

        val decoded = QuotaSnapshotCache.decode(QuotaProviderType.OLLAMA, QuotaSnapshotCache.encode(QuotaProviderType.OLLAMA, quota))!!
        val raw = assertNotNull(decoded.rawJson)

        assertFalse(raw.contains("access-secret"))
        assertFalse(raw.contains("Bearer secret"))
        assertFalse(raw.contains("api-secret"))
        assertFalse(raw.contains("refresh-secret"))
        assertTrue(raw.contains("Pro"))
        assertTrue(raw.contains("42"))
        assertTrue(raw.contains("123"))
    }

    @Test
    fun preservesOpenAiExtraRateLimits() {
        val quota = proliteWithAdditionalRateLimits()

        val decoded = QuotaSnapshotCache.decode(
            QuotaProviderType.OPEN_AI,
            QuotaSnapshotCache.encode(QuotaProviderType.OPEN_AI, quota),
        ) as de.moritzf.quota.openai.OpenAiCodexQuota

        assertEquals(2, decoded.extraRateLimits.size)
        assertEquals("codex-spark", decoded.extraRateLimits[0].id)
        assertEquals("Codex Spark 5-hour", decoded.extraRateLimits[0].title)
        assertEquals(quota.extraRateLimits[0].window.usedPercent, decoded.extraRateLimits[0].window.usedPercent)
        assertEquals(quota.extraRateLimits[0].window.windowDuration, decoded.extraRateLimits[0].window.windowDuration)
        assertEquals(quota.extraRateLimits[0].window.resetsAt, decoded.extraRateLimits[0].window.resetsAt)
    }

    private fun <Q : ProviderQuota> roundTrip(type: QuotaProviderType, quota: Q, raw: String): ProviderQuota {
        quota.rawJson = raw
        return QuotaSnapshotCache.decode(type, QuotaSnapshotCache.encode(type, quota))!!
    }
}
