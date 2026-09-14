package de.moritzf.proxy.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthFileResolverTest {
    @Test
    fun explicitPathIsTheOnlyCandidate() {
        assertEquals(listOf("/tmp/plugin-oauth.json"), AuthFileResolver.resolveCandidates("/tmp/plugin-oauth.json"))
    }

    @Test
    fun blankOrMissingPathHasNoCandidates() {
        assertTrue(AuthFileResolver.resolveCandidates(null).isEmpty())
        assertTrue(AuthFileResolver.resolveCandidates("  ").isEmpty())
    }

    @Test
    fun doesNotSearchCodexOrChatgptLocalHomes() {
        val candidates = AuthFileResolver.resolveCandidates(null).joinToString()
        assertTrue(!candidates.contains(".codex"))
        assertTrue(!candidates.contains(".chatgpt-local"))
        assertTrue(!candidates.contains("auth.json"))
    }
}
