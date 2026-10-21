package de.moritzf.quota.idea.common

import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.zai.ZaiQuotaClient
import java.net.URI
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpQuotaErrorRedactionTest {
    @Test
    fun malformedKeysNeverReachMcpFromRealHeaderValidation() {
        // Invalid headers fail before any request is sent.
        val endpoint = URI.create("https://example.invalid/")
        val http = HttpClient.newHttpClient()
        for (key in
            listOf("audit-synthetic-secret\u201d", "audit-synthetic-secret\"\ntrailing-secret")) {
            val providers =
                listOf(
                    ZaiQuotaProvider(
                        zaiClient = ZaiQuotaClient(http, endpoint),
                        apiKeyProvider = { key },
                    ),
                    OllamaQuotaProvider(
                        ollamaClient = OllamaQuotaClient(http, endpoint),
                        apiKeyProvider = { key },
                    ),
                )
            for (provider in providers) {
                provider.refresh()
                val error = assertNotNull(provider.getLastError())
                assertTrue(error.contains("invalid header value"), error)
                val result = McpJson.error(error)
                assertFalse(result.contains("audit-synthetic-secret"), result)
                assertFalse(result.contains("trailing-secret"), result)
                assertTrue(result.contains("[REDACTED]"), result)
            }
        }
    }
}
