package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.common.interruptibleOperation
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AccountOperationsTest {
    @Test
    fun oauthRetryKeepsAccountAndOperationCapability() = runBlocking {
        var account = "a"
        val selected = mutableListOf<AccountCapability>()
        val refreshes = mutableListOf<String>()
        val operations =
            AccountOperations(
                resolve = { type, capability ->
                    selected += capability
                    ProviderAccount(account, type.id)
                },
                token = { "old-$it" },
                refresh = { id, stale ->
                    refreshes += "$id:$stale"
                    "new-$id"
                },
            )
        val result =
            operations.withSuperGrok(AccountCapability.IMAGE_GENERATION) { token ->
                account = "b"
                if (token.startsWith("old")) throw SuperGrokQuotaException("expired", 401)
                token
            }
        assertEquals("new-a", result)
        assertEquals(listOf("a:old-a"), refreshes)
        assertEquals(listOf(AccountCapability.IMAGE_GENERATION), selected)
    }

    @Test
    fun wrappedInterruptionNeverRetriesAnotherRegion() = runBlocking {
        var calls = 0
        val operations =
            AccountOperations(
                resolve = { type, _ -> ProviderAccount("a", type.id) },
                miniMaxKey = { "key" },
                miniMaxRegion = { MiniMaxRegionPreference.AUTO },
            )
        try {
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                operations.withMiniMax(AccountCapability.WEB_SEARCH) { _, _ ->
                    calls++
                    throw MiniMaxQuotaException("interrupted", 0, null, InterruptedException())
                }
            }
            assertEquals(1, calls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun cancelledOperationInterruptsBlockingTransport() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val interrupted = CountDownLatch(1)
        val job =
            launch(Dispatchers.Default) {
                interruptibleOperation {
                    entered.complete(Unit)
                    try {
                        CountDownLatch(1).await()
                    } catch (failure: InterruptedException) {
                        interrupted.countDown()
                        throw failure
                    }
                }
            }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
    }
}
