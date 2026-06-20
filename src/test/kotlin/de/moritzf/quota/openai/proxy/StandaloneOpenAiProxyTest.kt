package de.moritzf.quota.openai.proxy

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneOpenAiProxyTest {
    @Test
    fun standaloneOptionsParseCorsFlags() {
        val options = parseStandaloneOptions(
            arrayOf(
                "--login",
                "--cors-origin",
                "https://client.example,http://localhost:5173",
                "--cors-url=https://other.example",
                "--allow-any-cors",
            ),
        )

        assertEquals(true, options.login)
        assertEquals(true, options.allowAnyCors)
        assertEquals(
            listOf("https://client.example", "http://localhost:5173", "https://other.example"),
            options.corsOrigins,
        )
    }

    @Test
    fun tokenResponseCapturesRefreshTokenExpiryAndAccount() {
        val before = System.currentTimeMillis()
        val credentials = credentialsFromTokenResponse(
            """
                {
                  "access_token": "access-token",
                  "refresh_token": "refresh-token",
                  "expires_in": 120,
                  "id_token": "${jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"account-1"}}""")}"
                }
            """.trimIndent(),
        )
        val after = System.currentTimeMillis()

        assertEquals("access-token", credentials.accessToken)
        assertEquals("refresh-token", credentials.refreshToken)
        assertTrue(credentials.expiresAt in (before + 120_000)..(after + 120_000))
        assertEquals("account-1", credentials.accountId)
    }

    private fun jwt(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray(Charsets.UTF_8))
        val payload = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$header.$payload.signature"
    }
}
