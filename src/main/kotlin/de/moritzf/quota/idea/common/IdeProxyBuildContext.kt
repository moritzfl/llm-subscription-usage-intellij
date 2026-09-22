package de.moritzf.quota.idea.common

import de.moritzf.proxy.server.AccessLogFields
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.quota.azure.proxy.AzureSubscriptionProxyProvider
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.proxy.GitHubCopilotSubscriptionProxyProvider
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.opencode.proxy.OpenCodeConsoleSession
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolveException
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.proxy.KimiSubscriptionProxyProvider
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.proxy.MiniMaxSubscriptionProxyProvider
import de.moritzf.quota.mistral.proxy.MistralSubscriptionProxyProvider
import de.moritzf.quota.ollama.proxy.OllamaSubscriptionProxyProvider
import de.moritzf.quota.openai.proxy.OpenAiCodexSubscriptionProxyProvider
import de.moritzf.quota.opencode.proxy.OpenCodeZenSubscriptionProxyProvider
import de.moritzf.quota.supergrok.proxy.SuperGrokSubscriptionProxyProvider
import de.moritzf.quota.zai.proxy.ZaiSubscriptionProxyProvider
import java.net.URI

/** Dependencies needed to construct IDE subscription-proxy providers. */
internal data class IdeProxyBuildContext(
    val settings: QuotaSettingsState,
    val logRequests: Boolean,
    val requestLogDir: String,
    val authService: () -> QuotaAuthService = { QuotaAuthService.getInstance() },
    val githubCredentials: () -> GitHubCredentialsStore = { GitHubCredentialsStore.getInstance() },
    val kimiCredentials: () -> KimiCredentialsStore = { KimiCredentialsStore.getInstance() },
    val miniMaxApiKey: () -> MiniMaxApiKeyStore = { MiniMaxApiKeyStore.getInstance() },
    val mistralApiKey: () -> MistralApiKeyStore = { MistralApiKeyStore.getInstance() },
    val ollamaApiKey: () -> OllamaApiKeyStore = { OllamaApiKeyStore.getInstance() },
    val openCodeAuth: () -> OpenCodeAuthService = { OpenCodeAuthService.getInstance() },
    val zaiApiKey: () -> ZaiApiKeyStore = { ZaiApiKeyStore.getInstance() },
)

internal object IdeProxyFactories {
    fun openAi(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OpenAiCodexSubscriptionProxyProvider(
            accessTokenProvider = {
                resolvedAccount(ctx, QuotaProviderType.OPEN_AI)?.let { account ->
                    ctx.authService().getAccessTokenBlocking(account.id, QuotaProviderType.OPEN_AI)
                }
            },
            accountIdProvider = {
                resolvedAccount(ctx, QuotaProviderType.OPEN_AI)?.let { account ->
                    ctx.authService().getAccountId(account.id, QuotaProviderType.OPEN_AI)
                }
            },
            // Upstream 401s route back to the IDE auth service, which owns refresh
            // and persistence; the stale token lets it dedupe concurrent refreshes.
            tokenRefresher = { staleToken ->
                refreshToken(ctx, QuotaProviderType.OPEN_AI, staleToken)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun superGrok(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return SuperGrokSubscriptionProxyProvider(
            accessTokenProvider = {
                resolvedAccount(ctx, QuotaProviderType.SUPERGROK)?.let { account ->
                    ctx.authService().getAccessTokenBlocking(account.id, QuotaProviderType.SUPERGROK)
                }
            },
            tokenRefresher = { staleToken ->
                refreshToken(ctx, QuotaProviderType.SUPERGROK, staleToken)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun azure(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return AzureSubscriptionProxyProvider(
            configProvider = {
                val account = resolvedAccount(ctx, QuotaProviderType.AZURE) ?: return@AzureSubscriptionProxyProvider null
                AzureSubscriptionProxyProvider.AzureProxyConfig(
                    accountId = account.id,
                    executable = AzureQuotaProvider.executableForAccount(account.id),
                    account = AzureQuotaProvider.configForAccount(account.id),
                )
            },
            accountKey = resolvedAccount(ctx, QuotaProviderType.AZURE)?.id ?: QuotaProviderType.AZURE.id,
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun github(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return GitHubCopilotSubscriptionProxyProvider(
            accessTokenProvider = {
                resolvedAccount(ctx, QuotaProviderType.GITHUB)?.let { account ->
                    GitHubCredentialsStore.forAccount(account.id).loadBlocking()?.accessToken
                }
            },
            upstreamBaseUri = githubCopilotBaseUri(
                ctx.settings.githubHostFor(
                    resolvedAccount(ctx, QuotaProviderType.GITHUB)?.id ?: QuotaProviderType.GITHUB.id,
                ),
            ),
            persistentModelCacheProvider = {
                ctx.settings.subscriptionProxyModelCatalogJson(GitHubCopilotSubscriptionProxyProvider.ID)
            },
            persistentModelCacheSaver = { json ->
                ctx.settings.setSubscriptionProxyModelCatalogJson(GitHubCopilotSubscriptionProxyProvider.ID, json)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun kimi(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return KimiSubscriptionProxyProvider(
            credentialsProvider = {
                resolvedAccount(ctx, QuotaProviderType.KIMI)?.let { account ->
                    KimiCredentialsStore.forAccount(account.id).loadBlocking()
                }
            },
            credentialsSaver = { credentials ->
                resolvedAccount(ctx, QuotaProviderType.KIMI)?.let { account ->
                    KimiCredentialsStore.forAccount(account.id).save(credentials)
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun miniMax(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return MiniMaxSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(ctx, QuotaProviderType.MINIMAX)?.let { account ->
                    MiniMaxApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            regionProvider = {
                val account = resolvedAccount(ctx, QuotaProviderType.MINIMAX)
                miniMaxProxyRegion(ctx.settings.miniMaxRegionFor(account?.id ?: QuotaProviderType.MINIMAX.id))
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun mistral(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return MistralSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(ctx, QuotaProviderType.MISTRAL)?.let { account ->
                    MistralApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun ollama(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OllamaSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(ctx, QuotaProviderType.OLLAMA)?.let { account ->
                    OllamaApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun openCode(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OpenCodeZenSubscriptionProxyProvider(
            consoleSessionProvider = {
                resolvedAccount(ctx, QuotaProviderType.OPEN_CODE)?.let { account ->
                    val auth = ctx.openCodeAuth()
                    auth.credentials(account.id)?.let { credentials ->
                        val organization = credentials.accountId ?: ctx.settings.openCodeWorkspaceIdFor(account.id)
                        OpenCodeConsoleSession(account.id, checkNotNull(credentials.accessToken), organization) { stale ->
                            auth.credentials(account.id, stale)?.takeIf { it.accountId == credentials.accountId }?.accessToken
                        }
                    }
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun zai(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return ZaiSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(ctx, QuotaProviderType.ZAI)?.let { account ->
                    ZaiApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun withProxyRateLimit(
        ctx: IdeProxyBuildContext,
        type: QuotaProviderType,
        provider: SubscriptionProxyProvider,
    ): SubscriptionProxyProvider {
        return RateLimitedSubscriptionProxyProvider(provider, type, ctx)
    }

    fun noteProxyRateLimit(ctx: IdeProxyBuildContext, type: QuotaProviderType, status: Int) {
        if (status != 429) return
        resolvedAccount(ctx, type)?.id?.let(AccountResolver::markRateLimited)
    }

    private fun resolvedAccount(
        ctx: IdeProxyBuildContext,
        type: QuotaProviderType,
    ): de.moritzf.quota.idea.settings.ProviderAccount? {
        if (ctx.settings.accountsOf(type).isEmpty()) return null
        return try {
            AccountResolver.resolve(type, capability = AccountCapability.PROXY, settings = ctx.settings)
        } catch (exception: AccountResolveException) {
            LOG.warn("Could not resolve ${type.displayName} account for the local proxy: ${exception.message}")
            null
        }
    }

    private fun refreshToken(
        ctx: IdeProxyBuildContext,
        type: QuotaProviderType,
        staleToken: String?,
    ): String? {
        val auth = ctx.authService()
        val owner = staleToken?.let { token ->
            ctx.settings.accountsOf(type).firstOrNull { account ->
                auth.peekAccessToken(account.id, type) == token
            }
        }
        val account = owner ?: resolvedAccount(ctx, type) ?: return null
        return auth.forceRefreshBlocking(account.id, type, staleToken)
    }

    fun githubCopilotBaseUri(enterpriseHost: String): URI {
        val host = GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHost)
        if (host == "github.com") return GitHubCopilotSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI
        return URI.create("https://copilot-api.$host")
    }

    fun miniMaxProxyRegion(preference: MiniMaxRegionPreference): MiniMaxRegion {
        return when (preference) {
            MiniMaxRegionPreference.CN -> MiniMaxRegion.CN
            MiniMaxRegionPreference.GLOBAL,
            MiniMaxRegionPreference.AUTO -> MiniMaxRegion.GLOBAL
        }
    }

    private val LOG = Logger.getInstance(IdeProxyFactories::class.java)
}

private class RateLimitedSubscriptionProxyProvider(
    private val delegate: SubscriptionProxyProvider,
    private val type: QuotaProviderType,
    private val context: IdeProxyBuildContext,
) : SubscriptionProxyProvider by delegate {
    override suspend fun handle(ctx: ProxyCall, request: SubscriptionProxyRequest) {
        delegate.handle(ctx, request)
        val status = ctx.getAttribute(AccessLogFields.UPSTREAM_STATUS) ?: ctx.responseStatus()
        if (status == 429) {
            IdeProxyFactories.noteProxyRateLimit(context, type, status)
        }
    }
}
