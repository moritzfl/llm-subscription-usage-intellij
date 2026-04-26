package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.QuotaTokenUtil
import org.intellij.lang.annotations.Language
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for extracting account metadata from OAuth/JWT tokens.
 */
class QuotaTokenUtilTest {
    @Test
    fun extractChatGptAccountIdReturnsTrimmedAccountIdWhenPresent() {
        @Language("JSON")
        val payload = """
            {
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "  account-123  "
              }
            }
        """.trimIndent()

        val token = buildToken(payload)

        assertEquals("account-123", QuotaTokenUtil.extractChatGptAccountId(token))
    }

    @Test
    fun extractChatGptAccountIdReturnsNullWhenClaimIsMissing() {
        @Language("JSON")
        val payload = """
            {
              "sub": "user-1"
            }
        """.trimIndent()

        val token = buildToken(payload)

        assertNull(QuotaTokenUtil.extractChatGptAccountId(token))
    }

    @Test
    fun extractChatGptAccountIdReturnsNullForMalformedToken() {
        assertNull(QuotaTokenUtil.extractChatGptAccountId("not-a-jwt"))
    }

    @Test
    fun extractChatGptAccountIdReturnsNullForBlankToken() {
        assertNull(QuotaTokenUtil.extractChatGptAccountId("   "))
    }

    private fun buildToken(@Language("JSON") payloadJson: String): String {
        @Language("JSON")
        val headerJson = """
            {"alg":"none","typ":"JWT"}
        """.trimIndent()
        return "${base64Url(headerJson)}.${base64Url(payloadJson)}.signature"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64Url(value: String): String {
        return Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(value.toByteArray(Charsets.UTF_8))
    }
}
