package de.moritzf.proxy.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.nio.file.Path

class StandaloneSubscriptionProxyTest {
    @Test
    fun parsesStandaloneOptions() {
        val options = parseStandaloneSubscriptionOptions(
            arrayOf(
                "--host", "127.0.0.1",
                "--port=15555",
                "--provider", "openai,github",
                "--env-file=.env.proxy",
                "--allow-any-cors",
                "--cors-origin", "http://localhost:3000,https://client.example",
                "--log-requests",
                "--request-log-dir", "logs/proxy",
                "--list-models",
            ),
        )

        assertEquals("127.0.0.1", options.host)
        assertEquals(15555, options.port)
        assertEquals(setOf("openai", "github"), options.providers)
        assertEquals(Path.of(".env.proxy"), options.envFile)
        assertEquals(true, options.allowAnyCors)
        assertEquals(listOf("http://localhost:3000", "https://client.example"), options.corsOrigins)
        assertEquals(true, options.logRequests)
        assertEquals("logs/proxy", options.requestLogDir)
        assertEquals(true, options.listModels)
    }

    @Test
    fun rejectsInvalidPort() {
        assertFailsWith<IllegalArgumentException> {
            parseStandaloneSubscriptionOptions(arrayOf("--port", "70000"))
        }
    }

    @Test
    fun standaloneEnvReadsProvidedValues() {
        val env = StandaloneEnv.of(mapOf("SUBSCRIPTION_PROXY_API_KEY" to "local", "EMPTY" to ""))

        assertEquals("local", env.value("SUBSCRIPTION_PROXY_API_KEY"))
        assertNull(env.value("EMPTY"))
    }
}
