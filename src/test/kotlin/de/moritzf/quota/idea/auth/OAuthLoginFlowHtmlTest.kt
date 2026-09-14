package de.moritzf.quota.idea.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthLoginFlowHtmlTest {
    @Test
    fun failurePageEscapesIdpErrorHtml() {
        val html = OAuthLoginFlow.buildHtmlResponse(
            "Authentication Failed",
            "Authentication failed: <script>alert(1)</script> 100%",
            false,
        )
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("100%"))
    }
}
