package de.moritzf.quota.idea.common

import de.moritzf.proxy.server.AccessLogFields
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.ProviderAccount

/** Keeps provider caches warm while binding credentials and configuration to one account. */
internal class AccountBoundProxyProvider(
    private val type: QuotaProviderType,
    private val accounts: () -> List<ProviderAccount>,
    private val resolve: (String?) -> ProviderAccount?,
    private val create: (ProviderAccount) -> SubscriptionProxyProvider,
    private val rateLimited: (String) -> Unit = { AccountResolver.markRateLimited(it) },
) : SubscriptionProxyProvider {
    private data class Entry(val account: ProviderAccount, val provider: SubscriptionProxyProvider)

    private val delegates = mutableMapOf<String, Entry>()
    override val id = type.id
    override val displayName = type.displayName

    @Synchronized
    private fun selected(model: String? = null): Entry? {
        val liveIds = accounts().map { it.id }.toSet()
        delegates.keys.retainAll(liveIds)
        val account = resolve(model)?.snapshot() ?: return null
        val cached = delegates[account.id]
        if (cached != null && cached.account == account) return cached
        return Entry(account, create(account)).also { delegates[account.id] = it }
    }

    override fun isConfigured() = selected()?.provider?.isConfigured() == true

    override fun models() = selected()?.provider?.models().orEmpty()

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute) =
        selected(localId)?.provider?.fallbackModel(localId, route)

    override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        val entry = selected(request.model.upstreamId)
        if (entry == null) {
            JsonHelper.toErrorResponse(
                ctx,
                "No ${type.displayName} account configured.",
                503,
                "configuration_error",
            )
            return
        }
        entry.provider.handle(ctx, request)
        val status = ctx.getAttribute(AccessLogFields.UPSTREAM_STATUS) ?: ctx.responseStatus()
        if (status == 429) rateLimited(entry.account.id)
    }
}
