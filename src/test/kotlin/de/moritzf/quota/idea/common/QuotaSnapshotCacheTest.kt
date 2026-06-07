package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.zai.ZaiQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class QuotaSnapshotCacheTest {
    @Test
    fun preservesRawResponsesForTransientRawQuotaTypes() {
        assertEquals("ollama raw", roundTripOllama("ollama raw").rawJson)
        assertEquals("zai raw", roundTripZai("zai raw").rawJson)
        assertEquals("minimax raw", roundTripMiniMax("minimax raw").rawJson)
        assertEquals("kimi raw", roundTripKimi("kimi raw").rawJson)
        assertEquals("cursor raw", roundTripCursor("cursor raw").rawJson)
    }

    @Test
    fun decodesLegacyQuotaCacheWithoutRawResponse() {
        val legacyOllama = """{"plan":"Pro"}"""

        val decoded = QuotaSnapshotCache.decodeOllamaQuota(legacyOllama)

        assertNotNull(decoded)
        assertEquals("Pro", decoded.plan)
        assertEquals(null, decoded.rawJson)
    }

    private fun roundTripOllama(raw: String): OllamaQuota {
        val quota = OllamaQuota(plan = "Pro").apply { rawJson = raw }
        return QuotaSnapshotCache.decodeOllamaQuota(QuotaSnapshotCache.encodeOllamaQuota(quota))!!
    }

    private fun roundTripZai(raw: String): ZaiQuota {
        val quota = ZaiQuota(plan = "Pro").apply { rawJson = raw }
        return QuotaSnapshotCache.decodeZaiQuota(QuotaSnapshotCache.encodeZaiQuota(quota))!!
    }

    private fun roundTripMiniMax(raw: String): MiniMaxQuota {
        val quota = MiniMaxQuota(plan = "Pro").apply { rawJson = raw }
        return QuotaSnapshotCache.decodeMiniMaxQuota(QuotaSnapshotCache.encodeMiniMaxQuota(quota))!!
    }

    private fun roundTripKimi(raw: String): KimiQuota {
        val quota = KimiQuota(plan = "Pro").apply { rawJson = raw }
        return QuotaSnapshotCache.decodeKimiQuota(QuotaSnapshotCache.encodeKimiQuota(quota))!!
    }

    private fun roundTripCursor(raw: String): CursorQuota {
        val quota = CursorQuota(planName = "Pro").apply { rawJson = raw }
        return QuotaSnapshotCache.decodeCursorQuota(QuotaSnapshotCache.encodeCursorQuota(quota))!!
    }
}
