package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.mcp.ListSearchProvider
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.MiniMaxWebSearchClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class SubscriptionOperationsTest {
    @Test
    fun searchPreservesNativeJsonAndBindsKeyAndRegionOnce() = runBlocking {
        val selected = mutableListOf<AccountCapability>()
        val calls = mutableListOf<String>()
        val raw = """{"organic":[{"title":"Provider result"}],"provider_field":42}"""
        val operations =
            SubscriptionOperations(
                accounts =
                    AccountOperations(
                        resolve = { type, capability ->
                            selected += capability
                            ProviderAccount("cn-account", type.id)
                        },
                        miniMaxKey = { "key-$it" },
                        miniMaxRegion = { MiniMaxRegionPreference.CN },
                    ),
                miniMaxSearchClient =
                    object : MiniMaxWebSearchClient() {
                        override fun webSearch(
                            apiKey: String,
                            region: MiniMaxRegion,
                            query: String,
                            limit: Int,
                            includeContent: Boolean,
                        ): String {
                            calls += "$apiKey:$region:$query:$limit:$includeContent"
                            return raw
                        }
                    },
            )
        assertEquals(
            raw,
            operations.subscription_web_search(ListSearchProvider.MINIMAX, "query", 3, true),
        )
        assertEquals(listOf(AccountCapability.WEB_SEARCH), selected)
        assertEquals(listOf("key-cn-account:CN:query:3:true"), calls)
    }

    @Test
    fun searchDoesNotConvertCancellationToJsonOrTryAnotherRegion() = runBlocking {
        var calls = 0
        val operations =
            SubscriptionOperations(
                accounts =
                    AccountOperations(
                        resolve = { type, _ -> ProviderAccount("a", type.id) },
                        miniMaxKey = { "key" },
                        miniMaxRegion = { MiniMaxRegionPreference.AUTO },
                    ),
                miniMaxSearchClient =
                    object : MiniMaxWebSearchClient() {
                        override fun webSearch(
                            apiKey: String,
                            region: MiniMaxRegion,
                            query: String,
                            limit: Int,
                            includeContent: Boolean,
                        ): String {
                            calls++
                            throw CancellationException("cancelled")
                        }
                    },
            )
        assertFailsWith<CancellationException> {
            operations.subscription_web_search(ListSearchProvider.MINIMAX, "query")
        }
        assertEquals(1, calls)
    }
}
