package de.moritzf.quota.supergrok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SuperGrokResetCodecTest {
    @Test
    fun encodeAndParseKeepsUnexpiredToken() {
        val expiresAt = Instant.parse("2026-09-13T12:34:56Z")
        val encoded =
            SuperGrokResetCodec.encodeTokens(
                listOf(SuperGrokResetToken(tokenId = "restok_test", expiresAt = expiresAt))
            )

        val tokens =
            SuperGrokResetCodec.parseResetTokens(encoded, Instant.parse("2026-08-24T00:00:00Z"))

        assertEquals(
            listOf(SuperGrokResetToken(tokenId = "restok_test", expiresAt = expiresAt)),
            tokens,
        )
    }

    @Test
    fun parseDropsExpiredTokens() {
        val encoded =
            SuperGrokResetCodec.encodeTokens(
                listOf(
                    SuperGrokResetToken(
                        tokenId = "restok_old",
                        expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
                    )
                )
            )

        val tokens =
            SuperGrokResetCodec.parseResetTokens(encoded, Instant.parse("2026-08-24T00:00:00Z"))

        assertEquals(emptyList(), tokens)
    }

    @Test
    fun redeemRequestEncodesTokenId() {
        val frame = SuperGrokResetCodec.redeemRequestFrame("restok_test")
        val payload = frame.copyOfRange(5, frame.size)

        assertEquals(0, frame[0].toInt())
        assertEquals(
            "restok_test",
            payload
                .decodeToString()
                .filter { it.isLetterOrDigit() || it == '_' }
                .let { it.substring(it.indexOf("restok_test")) },
        )
    }
}
