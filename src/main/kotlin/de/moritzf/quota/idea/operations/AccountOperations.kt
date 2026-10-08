package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import kotlinx.coroutines.ensureActive

/** Account selection and auth policy shared by MCP operations and proxy media adapters. */
internal class AccountOperations(
    private val resolve: (QuotaProviderType, AccountCapability) -> ProviderAccount =
        { type, capability ->
            AccountResolver.resolve(type, capability = capability).snapshot()
        },
    private val token: (String) -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(it, QuotaProviderType.SUPERGROK)
    },
    private val refresh: (String, String) -> String? = { id, stale ->
        QuotaAuthService.getInstance().forceRefreshBlocking(id, QuotaProviderType.SUPERGROK, stale)
    },
    private val miniMaxKey: (String) -> String? = {
        MiniMaxApiKeyStore.forAccount(it).loadBlocking()
    },
    private val miniMaxRegion: (String) -> MiniMaxRegionPreference = {
        QuotaSettingsState.getInstance().miniMaxRegionFor(it)
    },
    private val markLimited: (String) -> Unit = { AccountResolver.markRateLimited(it) },
) {
    suspend fun <T> withSuperGrok(capability: AccountCapability, block: suspend (String) -> T): T {
        val account = resolve(QuotaProviderType.SUPERGROK, capability)
        val accessToken =
            token(account.id)?.takeIf { it.isNotBlank() }
                ?: error("Grok login required. Log in from SuperGrok settings.")
        try {
            return block(accessToken)
        } catch (failure: SuperGrokQuotaException) {
            failure.rethrowIfCancellation()
            if (failure.statusCode == 429) markLimited(account.id)
            if (failure.statusCode != 401 && failure.statusCode != 403) throw failure
            val refreshed =
                refresh(account.id, accessToken)?.takeIf { it.isNotBlank() } ?: throw failure
            try {
                return block(refreshed)
            } catch (retry: SuperGrokQuotaException) {
                retry.rethrowIfCancellation()
                if (retry.statusCode == 429) markLimited(account.id)
                throw retry
            }
        }
    }

    suspend fun <T> withMiniMax(
        capability: AccountCapability,
        block: suspend (String, MiniMaxRegion) -> T,
    ): T {
        val account = resolve(QuotaProviderType.MINIMAX, capability)
        val key =
            miniMaxKey(account.id)?.takeIf { it.isNotBlank() }
                ?: error("MiniMax API key missing. Add a MiniMax API key in settings.")
        val regions =
            when (miniMaxRegion(account.id)) {
                MiniMaxRegionPreference.CN -> listOf(MiniMaxRegion.CN)
                MiniMaxRegionPreference.GLOBAL -> listOf(MiniMaxRegion.GLOBAL)
                MiniMaxRegionPreference.AUTO -> listOf(MiniMaxRegion.GLOBAL, MiniMaxRegion.CN)
            }
        var last: Exception? = null
        for (region in regions) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                return block(key, region)
            } catch (failure: Exception) {
                failure.rethrowIfCancellation()
                last = failure
            }
        }
        throw checkNotNull(last)
    }
}
