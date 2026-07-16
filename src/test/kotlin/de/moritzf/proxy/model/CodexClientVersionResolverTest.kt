package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CodexClientVersionResolverTest {
    @Test
    fun fallbackSupportsAdvertisedGpt56Models() {
        assertEquals("0.139.0", CodexClientVersionResolver.FALLBACK_CODEX_CLIENT_VERSION)
    }
}
