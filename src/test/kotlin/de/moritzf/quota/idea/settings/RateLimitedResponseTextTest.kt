package de.moritzf.quota.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitedResponseTextTest {
    @Test
    fun showsTheRateLimitResponseAndTheOneBeforeIt() {
        assertEquals(
            """
            Error: Claude usage API rate limited.
            Last successful update: 12 minutes ago

            Last response (rate limited):
            {
                "error": "rate_limited"
            }

            Last response before the rate limit:
            {
                "five_hour": 40
            }
            """
                .trimIndent(),
            rateLimitedResponseText(
                "Claude usage API rate limited.",
                "Last successful update: 12 minutes ago",
                """{"error":"rate_limited"}""",
                """{"five_hour":40}""",
            ),
        )
    }

    @Test
    fun skipsTheRateLimitResponseWhenItHadNoBody() {
        assertEquals(
            """
            Error: Claude usage API rate limited.

            Last response before the rate limit:
            plain text
            """
                .trimIndent(),
            rateLimitedResponseText(
                "Claude usage API rate limited.",
                null,
                "plain text",
                "plain text",
            ),
        )
    }
}
