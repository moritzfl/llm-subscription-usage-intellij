package de.moritzf.quota.minimax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MiniMaxImageClientTest {
    @Test
    fun firstImageUrlReadsDataArray() {
        assertEquals(
            "https://cdn.example.com/a.png",
            MiniMaxImageClient.firstImageUrl(
                """{"data":{"image_urls":["https://cdn.example.com/a.png"]},"base_resp":{"status_code":0}}"""
            ),
        )
    }

    @Test
    fun checkBaseRespThrowsOnProviderError() {
        assertFailsWith<MiniMaxQuotaException> {
            MiniMaxImageClient.checkBaseResp(
                """{"base_resp":{"status_code":1004,"status_msg":"auth"}}"""
            )
        }
    }
}
