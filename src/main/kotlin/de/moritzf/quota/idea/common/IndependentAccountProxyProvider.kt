package de.moritzf.quota.idea.common

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.idea.settings.ProviderAccount

/**
 * Separate endpoints advertise together but never borrow one another's URL, credentials, or quota.
 */
internal class IndependentAccountProxyProvider(
    type: QuotaProviderType,
    private val accounts: () -> List<ProviderAccount>,
    private val create: (ProviderAccount) -> SubscriptionProxyProvider,
) : SubscriptionProxyProvider {
    private data class Entry(val account: ProviderAccount, val provider: SubscriptionProxyProvider)

    private val delegates = mutableMapOf<String, Entry>()
    override val id = type.id
    override val displayName = type.displayName

    @Synchronized
    private fun current(): List<SubscriptionProxyProvider> {
        val live = accounts().map { it.snapshot() }
        delegates.keys.retainAll(live.map { it.id }.toSet())
        return live.map { account ->
            val cached = delegates[account.id]
            if (cached?.account == account) cached.provider
            else create(account).also { delegates[account.id] = Entry(account, it) }
        }
    }

    override fun isConfigured() = current().any { it.isConfigured() }

    override fun models() = current().filter { it.isConfigured() }.flatMap { it.models() }

    override fun fallbackModel(localId: String, route: SubscriptionProxyRoute) =
        current()
            .asSequence()
            .filter { it.isConfigured() }
            .mapNotNull { it.fallbackModel(localId, route) }
            .firstOrNull()

    override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        val provider =
            current().firstOrNull {
                it.isConfigured() && it.fallbackModel(request.model.localId, request.route) != null
            }
        if (provider == null) {
            JsonHelper.toErrorResponse(
                ctx,
                "The endpoint for this model is no longer configured.",
                400,
                "invalid_request_error",
            )
            return
        }
        provider.handle(ctx, request)
    }
}
