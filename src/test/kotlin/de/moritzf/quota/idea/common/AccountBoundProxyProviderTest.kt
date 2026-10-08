package de.moritzf.quota.idea.common

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.subscription.SubscriptionProxyServer
import de.moritzf.quota.idea.settings.ProviderAccount
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountBoundProxyProviderTest {
    @Test
    fun selectionRemainsBoundAcrossDefaultChangeAndRateLimit() {
        val first = ProviderAccount(id = "a", typeId = "kimi")
        val second = ProviderAccount(id = "b", typeId = "kimi")
        var selected = first
        val writes = mutableListOf<String>()
        val limited = mutableListOf<String>()
        val created = mutableListOf<String>()
        val provider =
            AccountBoundProxyProvider(
                QuotaProviderType.KIMI,
                accounts = { listOf(first, second) },
                resolve = { selected },
                rateLimited = { limited += it },
                create = { account ->
                    created += account.id
                    object : SubscriptionProxyProvider {
                        override val id = "kimi"
                        override val displayName = "Kimi"

                        override fun isConfigured() = true

                        override fun models() =
                            listOf(
                                SubscriptionProxyModel(
                                    "km-test",
                                    "test",
                                    id,
                                    displayName,
                                    "openai",
                                    setOf(SubscriptionProxyRoute.CHAT_COMPLETIONS),
                                )
                            )

                        override suspend fun handle(
                            ctx: ProxyCall,
                            request: SubscriptionProxyRequest,
                        ) {
                            val loadedFrom = account.id
                            selected = second
                            writes += "$loadedFrom->${account.id}"
                            JsonHelper.toErrorResponse(ctx, "limited", 429, "rate_limit_error")
                        }
                    }
                },
            )
        val server = SubscriptionProxyServer(0, { "key" }, { listOf(provider) })
        server.start()
        val port = kotlinx.coroutines.runBlocking { server.boundPort() }
        try {
            HttpClient.newHttpClient().use { client ->
                val response =
                    client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
                            .header("Authorization", "Bearer key")
                            .header("Content-Type", "application/json")
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    """{"model":"km-test","messages":[{"role":"user","content":"hi"}]}"""
                                )
                            )
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                assertEquals(429, response.statusCode())
            }
            assertEquals(listOf("a->a"), writes)
            assertEquals(listOf("a"), limited)
            provider.models()
            provider.models()
            assertEquals(listOf("a", "b"), created)
        } finally {
            server.stop(gracePeriodMillis = 0)
        }
    }
}
