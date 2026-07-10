package de.moritzf.quota.idea.auth

import de.moritzf.quota.idea.common.QuotaProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeOAuthSupportTest {
    @Test
    fun anthropicConfigUsesPasteCallbackAndJsonTokenBody() {
        val config = OAuthClientConfig.forProvider(QuotaProviderType.CLAUDE)
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", config.clientId)
        assertEquals("https://claude.ai/oauth/authorize", config.authorizationEndpoint)
        assertEquals("https://platform.claude.com/v1/oauth/token", config.tokenEndpoint)
        assertEquals("https://platform.claude.com/oauth/code/callback", config.redirectUri)
        assertEquals(OAuthCallbackMode.PASTE, config.callbackMode)
        assertEquals(OAuthTokenBodyFormat.JSON, config.tokenBodyFormat)
        assertTrue(config.includeStateInCodeExchange)
        assertTrue(config.scopes.contains("user:profile"))
    }

    @Test
    fun anthropicAuthorizeUrlMatchesKnownWorkingShape() {
        val flow = OAuthLoginFlow.start(OAuthClientConfig.forProvider(QuotaProviderType.CLAUDE))
        val url = flow.authorizationUrl
        assertTrue(url.startsWith("https://claude.ai/oauth/authorize?"))
        // Claude rejects + space encoding with "Invalid request format".
        assertFalse(url.contains("+"))
        assertTrue(url.contains("code=true"))
        assertTrue(url.contains("scope=org%3Acreate_api_key%20user%3Aprofile"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("response_type=code"))
        // code=true must come before client_id (Claude Code order).
        assertTrue(url.indexOf("code=true") < url.indexOf("client_id="))
        assertTrue(url.indexOf("client_id=") < url.indexOf("response_type="))
        assertTrue(url.indexOf("redirect_uri=") < url.indexOf("scope="))
    }

    @Test
    fun parseCallbackInputAcceptsUrlCodeHashAndQuery() {
        val fromUrl = OAuthLoginFlow.parseCallbackInput(
            "https://platform.claude.com/oauth/code/callback?code=abc123&state=state456",
        )
        assertNotNull(fromUrl)
        assertEquals("abc123", fromUrl.code)
        assertEquals("state456", fromUrl.state)

        val fromHash = OAuthLoginFlow.parseCallbackInput("abc123#state456")
        assertNotNull(fromHash)
        assertEquals("abc123", fromHash.code)
        assertEquals("state456", fromHash.state)

        val fromQuery = OAuthLoginFlow.parseCallbackInput("code=abc123&state=state456")
        assertNotNull(fromQuery)
        assertEquals("abc123", fromQuery.code)
        assertEquals("state456", fromQuery.state)

        // Claude sometimes URL-encodes the hash form inside a full callback URL fragment.
        val fromEncoded = OAuthLoginFlow.parseCallbackInput(
            "https://platform.claude.com/oauth/code/callback#code%3Dabc123%26state%3Dstate456",
        )
        assertNotNull(fromEncoded)
        assertEquals("abc123", fromEncoded.code)
        assertEquals("state456", fromEncoded.state)

        assertEquals(null, OAuthLoginFlow.parseCallbackInput("not-a-callback"))
    }

    @Test
    fun claudeServiceNameIsDistinctFromOtherOauthProviders() {
        assertFalse(
            OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.CLAUDE) ==
                OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.OPEN_AI),
        )
        assertFalse(
            OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.CLAUDE) ==
                OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.SUPERGROK),
        )
    }
}
