package de.moritzf.quota.idea.common

import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.idea.settings.AntigravitySettingsPanel
import de.moritzf.quota.idea.settings.AzureSettingsPanel
import de.moritzf.quota.idea.ui.indicator.AntigravityUi
import de.moritzf.quota.idea.ui.indicator.AzureUi
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.mcp.UsageQuotaMcpRegistration
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.idea.settings.ClaudeSettingsPanel
import de.moritzf.quota.idea.settings.CursorSettingsPanel
import de.moritzf.quota.idea.settings.GitHubSettingsPanel
import de.moritzf.quota.idea.settings.KimiSettingsPanel
import de.moritzf.quota.idea.settings.MiniMaxSettingsPanel
import de.moritzf.quota.idea.settings.MistralSettingsPanel
import de.moritzf.quota.idea.settings.OllamaSettingsPanel
import de.moritzf.quota.idea.settings.OpenAiSettingsPanel
import de.moritzf.quota.idea.settings.OpenCodeSettingsPanel
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.ProviderSettingsPanel
import de.moritzf.quota.idea.settings.ProviderSettingsPanelContext
import de.moritzf.quota.idea.settings.SuperGrokSettingsPanel
import de.moritzf.quota.idea.settings.ZaiSettingsPanel
import de.moritzf.quota.idea.ui.indicator.ClaudeUi
import de.moritzf.quota.idea.ui.indicator.CursorUi
import de.moritzf.quota.idea.ui.indicator.GitHubUi
import de.moritzf.quota.idea.ui.indicator.KimiUi
import de.moritzf.quota.idea.ui.indicator.MiniMaxUi
import de.moritzf.quota.idea.ui.indicator.MistralUi
import de.moritzf.quota.idea.ui.indicator.OllamaUi
import de.moritzf.quota.idea.ui.indicator.OpenAiUi
import de.moritzf.quota.idea.ui.indicator.OpenCodeUi
import de.moritzf.quota.idea.ui.indicator.ProviderUi
import de.moritzf.quota.idea.ui.indicator.SuperGrokUi
import de.moritzf.quota.idea.ui.indicator.ZaiUi
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaResetSchedule
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota

internal enum class WebSearchCapability {
    NONE,
    ANSWER,
    LIST,
}

internal data class ProviderCapabilities(
    val webSearch: WebSearchCapability = WebSearchCapability.NONE,
    val imageGeneration: Boolean = false,
    val imageEdit: Boolean = false,
    val videoGeneration: Boolean = false,
    val speechToText: Boolean = false,
    val textToSpeech: Boolean = false,
    val documentToMarkdown: Boolean = false,
    val webFetch: Boolean = false,
    val subscriptionProxy: Boolean = false,
    val oauth: Boolean = false,
    val multipleAccounts: Boolean = true,
)

/**
 * Single registration row for a subscription provider.
 * New providers add one entry here (plus type/enum/docs assets as needed).
 */
internal data class ProviderDescriptor(
    val type: QuotaProviderType,
    val capabilities: ProviderCapabilities = ProviderCapabilities(),
    val quotaFactory: (de.moritzf.quota.idea.settings.ProviderAccount) -> QuotaProvider,
    val snapshotCodec: QuotaCodec<out ProviderQuota>,
    val mcpQuota: UsageQuotaMcpRegistration,
    val settingsPanelFactory: (ProviderSettingsPanelContext) -> ProviderSettingsPanel,
    val ui: ProviderUi,
    /** Blocking credential check for MCP status / background work. */
    val isQuotaConfigured: () -> Boolean,
    val isWebSearchConfigured: () -> Boolean = { false },
    val isImageGenerationConfigured: () -> Boolean = { false },
    val isVoiceConfigured: () -> Boolean = { false },
    val isDocumentConfigured: () -> Boolean = { false },
    /** Per-account blocking credential checks; default delegates to the type-level (first-account) probe. */
    val isQuotaConfiguredForAccount: (accountId: String) -> Boolean = { isQuotaConfigured() },
    val isWebSearchConfiguredForAccount: (accountId: String) -> Boolean = { isWebSearchConfigured() },
    val isImageGenerationConfiguredForAccount: (accountId: String) -> Boolean = { isImageGenerationConfigured() },
    val isVoiceConfiguredForAccount: (accountId: String) -> Boolean = { isVoiceConfigured() },
    val isDocumentConfiguredForAccount: (accountId: String) -> Boolean = { isDocumentConfigured() },
    /**
     * Proxy-credential check. The optional callback is invoked when PasswordSafe finishes an async load
     * (settings UI refresh). Null for blocking-only callers.
     */
    val isProxyConfigured: (onCredentialsLoaded: (() -> Unit)?) -> Boolean = { _ -> false },
    val webSearchMissingReason: String? = null,
    /** IDE subscription-proxy construction; null when [ProviderCapabilities.subscriptionProxy] is false. */
    val ideProxyFactory: ((IdeProxyBuildContext) -> SubscriptionProxyProvider)? = null,
) {
    val webSearchType: String?
        get() = when (capabilities.webSearch) {
            WebSearchCapability.ANSWER -> "answer"
            WebSearchCapability.LIST -> "list"
            WebSearchCapability.NONE -> null
        }
}

/**
 * Canonical provider catalog. Other registries are thin facades over this.
 */
internal object ProviderCatalog {
    val all: List<ProviderDescriptor> = listOf(
        descriptor(
            type = QuotaProviderType.ANTIGRAVITY,
            capabilities = ProviderCapabilities(multipleAccounts = false),
            quotaFactory = { AntigravityQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(AntigravityQuota.serializer()),
            mcpEmpty = "No Antigravity usage report available",
            settings = { AntigravitySettingsPanel() },
            ui = AntigravityUi,
            // CLI availability, not an assertion about the CLI's current login. Refresh verifies it.
            isQuotaConfigured = { AntigravityQuotaProvider.executableForAccount(QuotaProviderType.ANTIGRAVITY.id) != null },
            isQuotaConfiguredForAccount = { AntigravityQuotaProvider.executableForAccount(it) != null },
        ),
        descriptor(
            type = QuotaProviderType.AZURE,
            capabilities = ProviderCapabilities(documentToMarkdown = true, subscriptionProxy = true, multipleAccounts = false),
            quotaFactory = { AzureQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(AzureQuota.serializer()),
            mcpEmpty = "No Azure usage response available",
            settings = { AzureSettingsPanel() },
            ui = AzureUi,
            // CLI availability, not an assertion about the current login. Refresh verifies it.
            isQuotaConfigured = { AzureQuotaProvider.executableForAccount(QuotaProviderType.AZURE.id) != null },
            isQuotaConfiguredForAccount = { AzureQuotaProvider.executableForAccount(it) != null },
            isDocumentConfigured = { AzureQuotaProvider.isDocumentConfiguredForAccount(QuotaProviderType.AZURE.id) },
            isDocumentConfiguredForAccount = AzureQuotaProvider::isDocumentConfiguredForAccount,
            isProxyConfigured = { _ ->
                anyAccount(QuotaProviderType.AZURE) { id ->
                    val account = de.moritzf.quota.idea.settings.QuotaSettingsState.getInstance().account(id)
                    AzureQuotaProvider.executableForAccount(id) != null &&
                        (account?.extra(de.moritzf.quota.idea.settings.ProviderAccount.EXTRA_AZURE_RESOURCE) != null ||
                            account?.extra(de.moritzf.quota.idea.settings.ProviderAccount.EXTRA_AZURE_ENDPOINT) != null)
                }
            },
            ideProxyFactory = IdeProxyFactories::azure,
        ),
        descriptor(
            type = QuotaProviderType.CLAUDE,
            capabilities = ProviderCapabilities(oauth = true),
            quotaFactory = { ClaudeQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(ClaudeQuota.serializer()),
            mcpEmpty = "No Claude usage response available",
            settings = { ctx -> ClaudeSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = ClaudeUi,
            isQuotaConfigured = { oauthAccessTokenPresent(QuotaProviderType.CLAUDE) },
            isQuotaConfiguredForAccount = { oauthAccessTokenPresent(QuotaProviderType.CLAUDE, it) },
        ),
        descriptor(
            type = QuotaProviderType.CURSOR,
            quotaFactory = { CursorQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(CursorQuota.serializer()),
            mcpQuota = UsageQuotaMcpRegistration(
                emptyMessage = "No Cursor usage response available",
                json = { service, type -> service.getLastResponseJson(type)?.let(CursorQuotaClient::normalizeRawJson) },
            ),
            settings = { ctx -> CursorSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = CursorUi,
            isQuotaConfigured = {
                !CursorCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
            },
            isQuotaConfiguredForAccount = { accountId ->
                !CursorCredentialsStore.forAccount(accountId).loadBlocking()?.accessToken.isNullOrBlank()
            },
        ),
        descriptor(
            type = QuotaProviderType.GITHUB,
            capabilities = ProviderCapabilities(documentToMarkdown = true, subscriptionProxy = true),
            quotaFactory = { GitHubQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(GitHubQuota.serializer()),
            mcpEmpty = "No GitHub usage response available",
            settings = { ctx -> GitHubSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = GitHubUi,
            isQuotaConfigured = {
                !GitHubCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
            },
            isQuotaConfiguredForAccount = { accountId ->
                !GitHubCredentialsStore.forAccount(accountId).loadBlocking()?.accessToken.isNullOrBlank()
            },
            isProxyConfigured = { onLoaded ->
                anyAccount(QuotaProviderType.GITHUB) { id ->
                    GitHubCredentialsStore.forAccount(id).load(onLoaded = onLoaded)?.isUsable() == true
                }
            },
            ideProxyFactory = IdeProxyFactories::github,
        ),
        descriptor(
            type = QuotaProviderType.KIMI,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                subscriptionProxy = true,
            ),
            quotaFactory = { KimiQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(KimiQuota.serializer()),
            mcpEmpty = "No Kimi usage response available",
            settings = { ctx -> KimiSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = KimiUi,
            isQuotaConfigured = {
                KimiCredentialsStore.getInstance().loadBlocking()?.isUsable() == true
            },
            isQuotaConfiguredForAccount = { accountId ->
                KimiCredentialsStore.forAccount(accountId).loadBlocking()?.isUsable() == true
            },
            isWebSearchConfigured = {
                KimiCredentialsStore.getInstance().loadBlocking()?.isUsable() == true
            },
            isWebSearchConfiguredForAccount = { accountId ->
                KimiCredentialsStore.forAccount(accountId).loadBlocking()?.isUsable() == true
            },
            isProxyConfigured = { onLoaded ->
                anyAccount(QuotaProviderType.KIMI) { id ->
                    KimiCredentialsStore.forAccount(id).load(onLoaded = onLoaded)?.isUsable() == true
                }
            },
            webSearchMissingReason = "Kimi login required. Log in from settings.",
            ideProxyFactory = IdeProxyFactories::kimi,
        ),
        descriptor(
            type = QuotaProviderType.MINIMAX,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                imageGeneration = true,
                speechToText = true,
                textToSpeech = true,
                subscriptionProxy = true,
            ),
            quotaFactory = { MiniMaxQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(MiniMaxQuota.serializer()),
            mcpEmpty = "No MiniMax usage response available",
            settings = { ctx -> MiniMaxSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = MiniMaxUi,
            isQuotaConfigured = { !MiniMaxApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isQuotaConfiguredForAccount = { accountId ->
                !MiniMaxApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isWebSearchConfigured = { !MiniMaxApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isWebSearchConfiguredForAccount = { accountId ->
                !MiniMaxApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isProxyConfigured = { onLoaded ->
                anyAccount(QuotaProviderType.MINIMAX) { id ->
                    !MiniMaxApiKeyStore.forAccount(id).load(onLoaded = onLoaded).isNullOrBlank()
                }
            },
            webSearchMissingReason = "MiniMax API key missing. Add a MiniMax API key in settings.",
            ideProxyFactory = IdeProxyFactories::miniMax,
        ),
        descriptor(
            type = QuotaProviderType.MISTRAL,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.ANSWER,
                imageGeneration = true,
                speechToText = true,
                textToSpeech = true,
                documentToMarkdown = true,
                subscriptionProxy = true,
            ),
            quotaFactory = { MistralQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(MistralQuota.serializer()),
            mcpEmpty = "No Mistral usage response available",
            settings = { ctx -> MistralSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = MistralUi,
            isQuotaConfigured = { !MistralSessionCookieStore.getInstance().loadBlocking().isNullOrBlank() },
            isQuotaConfiguredForAccount = { accountId ->
                !MistralSessionCookieStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isWebSearchConfigured = { !MistralApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isWebSearchConfiguredForAccount = { accountId ->
                !MistralApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isImageGenerationConfigured = { !MistralApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isImageGenerationConfiguredForAccount = { accountId ->
                !MistralApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isVoiceConfigured = { !MistralApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isVoiceConfiguredForAccount = { accountId ->
                !MistralApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isDocumentConfigured = { !MistralApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isDocumentConfiguredForAccount = { accountId ->
                !MistralApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isProxyConfigured = { onLoaded ->
                anyAccount(QuotaProviderType.MISTRAL) { id ->
                    !MistralApiKeyStore.forAccount(id).load(onLoaded = onLoaded).isNullOrBlank()
                }
            },
            webSearchMissingReason = "Mistral API key missing. Add a Mistral API key in settings.",
            ideProxyFactory = IdeProxyFactories::mistral,
        ),
        descriptor(
            type = QuotaProviderType.OLLAMA,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                webFetch = true,
                subscriptionProxy = true,
            ),
            quotaFactory = { account ->
                OllamaQuotaProvider(
                    accountId = account.id,
                    monthlyResetAnchorProvider = {
                        de.moritzf.quota.idea.settings.QuotaSettingsState.getInstance()
                            .account(account.id)
                            ?.extra(ProviderAccount.EXTRA_OLLAMA_MONTHLY_RESET)
                            ?.let(OllamaResetSchedule::parseMonthlyAnchor)
                    },
                )
            },
            snapshotCodec = EnvelopeQuotaCodec(OllamaQuota.serializer()),
            mcpEmpty = "No Ollama usage response available",
            settings = { ctx -> OllamaSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = OllamaUi,
            // Quota, proxy, and web search all use the Ollama API key.
            isQuotaConfigured = { !OllamaApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isQuotaConfiguredForAccount = { accountId ->
                !OllamaApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isWebSearchConfigured = { !OllamaApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isWebSearchConfiguredForAccount = { accountId ->
                !OllamaApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isProxyConfigured = { onLoaded ->
                anyAccount(QuotaProviderType.OLLAMA) { id ->
                    !OllamaApiKeyStore.forAccount(id).load(onLoaded = onLoaded).isNullOrBlank()
                }
            },
            webSearchMissingReason = "Ollama API key missing. Add an Ollama API key in settings.",
            ideProxyFactory = IdeProxyFactories::ollama,
        ),
        descriptor(
            type = QuotaProviderType.OPEN_AI,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.ANSWER,
                imageGeneration = true,
                speechToText = true,
                textToSpeech = true,
                documentToMarkdown = true,
                subscriptionProxy = true,
                oauth = true,
            ),
            quotaFactory = { OpenAiQuotaProvider(accountId = it.id) },
            snapshotCodec = OpenAiQuotaCodec,
            mcpEmpty = "No usage response available",
            settings = { ctx -> OpenAiSettingsPanel(ctx.modalityComponentProvider) },
            ui = OpenAiUi,
            isQuotaConfigured = { oauthAccessTokenPresent(QuotaProviderType.OPEN_AI) },
            isQuotaConfiguredForAccount = { oauthAccessTokenPresent(QuotaProviderType.OPEN_AI, it) },
            isWebSearchConfigured = { oauthAccessTokenPresent(QuotaProviderType.OPEN_AI) },
            isWebSearchConfiguredForAccount = { oauthAccessTokenPresent(QuotaProviderType.OPEN_AI, it) },
            isProxyConfigured = { _ ->
                anyAccount(QuotaProviderType.OPEN_AI) { oauthAccessTokenPresent(QuotaProviderType.OPEN_AI, it) }
            },
            webSearchMissingReason = "OpenAI login required. Log in from settings.",
            ideProxyFactory = IdeProxyFactories::openAi,
        ),
        descriptor(
            type = QuotaProviderType.OPEN_CODE,
            capabilities = ProviderCapabilities(documentToMarkdown = true, subscriptionProxy = true),
            quotaFactory = { OpenCodeQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(OpenCodeQuota.serializer()),
            mcpQuota = UsageQuotaMcpRegistration(
                emptyMessage = "No OpenCode usage response available",
                json = { service, _ ->
                    val quota = service.getLastQuota(QuotaProviderType.OPEN_CODE) as? OpenCodeQuota
                    quota?.rawJson
                },
            ),
            settings = { ctx -> OpenCodeSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = OpenCodeUi,
            // One Console login covers quota and proxy.
            isQuotaConfigured = {
                OpenCodeAuthService.getInstance().loadBlocking(QuotaProviderType.OPEN_CODE.id) != null
            },
            isQuotaConfiguredForAccount = { accountId ->
                OpenCodeAuthService.getInstance().loadBlocking(accountId) != null
            },
            isProxyConfigured = { onLoaded ->
                anyAccount(QuotaProviderType.OPEN_CODE) { id ->
                    OpenCodeAuthService.getInstance().load(id, onLoaded = {
                        onLoaded?.let { com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(it) }
                    }) != null
                }
            },
            ideProxyFactory = IdeProxyFactories::openCode,
        ),
        descriptor(
            type = QuotaProviderType.SUPERGROK,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.ANSWER,
                imageGeneration = true,
                imageEdit = true,
                videoGeneration = true,
                speechToText = true,
                textToSpeech = true,
                documentToMarkdown = true,
                subscriptionProxy = true,
                oauth = true,
            ),
            quotaFactory = { SuperGrokQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(SuperGrokQuota.serializer()),
            mcpEmpty = "No SuperGrok usage response available",
            settings = { ctx -> SuperGrokSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = SuperGrokUi,
            isQuotaConfigured = { oauthAccessTokenPresent(QuotaProviderType.SUPERGROK) },
            isQuotaConfiguredForAccount = { oauthAccessTokenPresent(QuotaProviderType.SUPERGROK, it) },
            isWebSearchConfigured = { oauthAccessTokenPresent(QuotaProviderType.SUPERGROK) },
            isWebSearchConfiguredForAccount = { oauthAccessTokenPresent(QuotaProviderType.SUPERGROK, it) },
            isProxyConfigured = { _ ->
                anyAccount(QuotaProviderType.SUPERGROK) { oauthAccessTokenPresent(QuotaProviderType.SUPERGROK, it) }
            },
            webSearchMissingReason = "Grok login required. Log in from SuperGrok settings.",
            ideProxyFactory = IdeProxyFactories::superGrok,
        ),
        descriptor(
            type = QuotaProviderType.ZAI,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                imageGeneration = true,
                videoGeneration = true,
                speechToText = true,
                documentToMarkdown = true,
                webFetch = true,
                subscriptionProxy = true,
            ),
            quotaFactory = { ZaiQuotaProvider(accountId = it.id) },
            snapshotCodec = EnvelopeQuotaCodec(ZaiQuota.serializer()),
            mcpEmpty = "No Z.ai usage response available",
            settings = { ctx -> ZaiSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = ZaiUi,
            isQuotaConfigured = { !ZaiApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isQuotaConfiguredForAccount = { accountId ->
                !ZaiApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isWebSearchConfigured = { !ZaiApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isWebSearchConfiguredForAccount = { accountId ->
                !ZaiApiKeyStore.forAccount(accountId).loadBlocking().isNullOrBlank()
            },
            isProxyConfigured = { onLoaded ->
                anyAccount(QuotaProviderType.ZAI) { id ->
                    !ZaiApiKeyStore.forAccount(id).load(onLoaded = onLoaded).isNullOrBlank()
                }
            },
            webSearchMissingReason = "Z.ai API key missing. Add a Z.ai API key in settings.",
            ideProxyFactory = IdeProxyFactories::zai,
        ),
    )

    private val byType: Map<QuotaProviderType, ProviderDescriptor> = all.associateBy { it.type }

    init {
        val missing = QuotaProviderType.entries.filter { it !in byType }
        check(missing.isEmpty()) { "ProviderCatalog missing types: $missing" }
        val proxyWithoutFactory = all.filter { it.capabilities.subscriptionProxy && it.ideProxyFactory == null }
        check(proxyWithoutFactory.isEmpty()) {
            "subscriptionProxy providers missing ideProxyFactory: ${proxyWithoutFactory.map { it.type }}"
        }
        val factoryWithoutFlag = all.filter { !it.capabilities.subscriptionProxy && it.ideProxyFactory != null }
        check(factoryWithoutFlag.isEmpty()) {
            "ideProxyFactory set without subscriptionProxy: ${factoryWithoutFlag.map { it.type }}"
        }
    }

    fun get(type: QuotaProviderType): ProviderDescriptor = byType.getValue(type)

    fun getOrNull(type: QuotaProviderType): ProviderDescriptor? = byType[type]

    fun createQuotaProviders(): List<QuotaProvider> = all.map { descriptor ->
        descriptor.quotaFactory(defaultAccount(descriptor.type))
    }

    fun createAccountProviders(accounts: List<de.moritzf.quota.idea.settings.ProviderAccount>): List<QuotaProvider> {
        return accounts.mapNotNull { account ->
            val type = account.providerType() ?: return@mapNotNull null
            getOrNull(type)?.quotaFactory?.invoke(account)
        }
    }

    private fun defaultAccount(type: QuotaProviderType): de.moritzf.quota.idea.settings.ProviderAccount {
        return de.moritzf.quota.idea.settings.ProviderAccount.create(type, type.displayName, isFirstOfType = true)
    }

    fun defaultProviderOrder(): List<QuotaProviderType> = all.map { it.type }.sortedBy { it.displayName }

    fun defaultProviderOrderStorageValue(): String = defaultProviderOrder().joinToString(",") { it.id }

    fun proxySupportedProviders(): List<QuotaProviderType> =
        all.filter { it.capabilities.subscriptionProxy }.map { it.type }

    fun oauthProviders(): List<QuotaProviderType> =
        all.filter { it.capabilities.oauth }.map { it.type }

    /**
     * Builds IDE subscription-proxy providers for [enabled] types (catalog order among proxy-capable entries).
     */
    fun createIdeProxyProviders(
        context: IdeProxyBuildContext,
        enabled: Set<QuotaProviderType>,
    ): List<SubscriptionProxyProvider> {
        return all.mapNotNull { descriptor ->
            if (descriptor.type !in enabled) return@mapNotNull null
            val factory = descriptor.ideProxyFactory ?: return@mapNotNull null
            IdeProxyFactories.withProxyRateLimit(context, descriptor.type, factory(context))
        }
    }

    fun mergeProviderOrder(storedOrder: List<QuotaProviderType>): List<QuotaProviderType> {
        val allProviders = defaultProviderOrder()
        val validStored = storedOrder.filter { it in allProviders }
        if (validStored.isEmpty()) {
            return allProviders
        }

        val result = validStored.toMutableList()
        val missing = allProviders.filter { it !in result }
        for (provider in missing) {
            val providerIndex = allProviders.indexOf(provider)
            if (providerIndex == 0) {
                result.add(0, provider)
                continue
            }

            val predecessor = allProviders[providerIndex - 1]
            val insertAfter = result.indexOfLast { it == predecessor }
            val insertIndex = if (insertAfter >= 0) {
                insertAfter + 1
            } else {
                val fallback = result.indexOfLast { allProviders.indexOf(it) < providerIndex }
                if (fallback >= 0) fallback + 1 else 0
            }
            result.add(insertIndex, provider)
        }
        return result
    }

    private fun oauthAccessTokenPresent(type: QuotaProviderType): Boolean {
        return QuotaAuthService.getInstance().hasCredentialsBlocking(type)
    }

    private fun oauthAccessTokenPresent(type: QuotaProviderType, accountId: String): Boolean {
        return QuotaAuthService.getInstance().hasCredentialsBlocking(accountId, type)
    }

    private fun accountIds(type: QuotaProviderType): List<String> {
        val accounts = runCatching {
            de.moritzf.quota.idea.settings.QuotaSettingsState.getInstance().accountsOf(type)
        }.getOrNull().orEmpty()
        return if (accounts.isEmpty()) listOf(type.id) else accounts.map { it.id }
    }

    private fun anyAccount(type: QuotaProviderType, probe: (String) -> Boolean): Boolean =
        accountIds(type).any(probe)

    private fun descriptor(
        type: QuotaProviderType,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
        quotaFactory: (de.moritzf.quota.idea.settings.ProviderAccount) -> QuotaProvider,
        snapshotCodec: QuotaCodec<out ProviderQuota>,
        mcpEmpty: String? = null,
        mcpQuota: UsageQuotaMcpRegistration? = null,
        settings: (ProviderSettingsPanelContext) -> ProviderSettingsPanel,
        ui: ProviderUi,
        isQuotaConfigured: () -> Boolean,
        isWebSearchConfigured: () -> Boolean = { false },
        isImageGenerationConfigured: (() -> Boolean)? = null,
        isVoiceConfigured: (() -> Boolean)? = null,
        isDocumentConfigured: (() -> Boolean)? = null,
        isProxyConfigured: (onCredentialsLoaded: (() -> Unit)?) -> Boolean = { _ -> false },
        webSearchMissingReason: String? = null,
        ideProxyFactory: ((IdeProxyBuildContext) -> SubscriptionProxyProvider)? = null,
        isQuotaConfiguredForAccount: ((accountId: String) -> Boolean)? = null,
        isWebSearchConfiguredForAccount: ((accountId: String) -> Boolean)? = null,
        isImageGenerationConfiguredForAccount: ((accountId: String) -> Boolean)? = null,
        isVoiceConfiguredForAccount: ((accountId: String) -> Boolean)? = null,
        isDocumentConfiguredForAccount: ((accountId: String) -> Boolean)? = null,
    ): ProviderDescriptor {
        val quotaForAccount = isQuotaConfiguredForAccount ?: { isQuotaConfigured() }
        return ProviderDescriptor(
            type = type,
            capabilities = capabilities,
            quotaFactory = quotaFactory,
            snapshotCodec = snapshotCodec,
            mcpQuota = mcpQuota ?: UsageQuotaMcpRegistration(mcpEmpty ?: "No usage response available"),
            settingsPanelFactory = settings,
            ui = ui,
            isQuotaConfigured = isQuotaConfigured,
            isWebSearchConfigured = isWebSearchConfigured,
            isImageGenerationConfigured = isImageGenerationConfigured
                ?: { capabilities.imageGeneration && isQuotaConfigured() },
            isVoiceConfigured = isVoiceConfigured
                ?: { (capabilities.speechToText || capabilities.textToSpeech) && isQuotaConfigured() },
            isDocumentConfigured = isDocumentConfigured
                ?: { capabilities.documentToMarkdown && isQuotaConfigured() },
            isQuotaConfiguredForAccount = quotaForAccount,
            isWebSearchConfiguredForAccount = isWebSearchConfiguredForAccount ?: { isWebSearchConfigured() },
            isImageGenerationConfiguredForAccount = isImageGenerationConfiguredForAccount
                ?: { accountId -> capabilities.imageGeneration && quotaForAccount(accountId) },
            isVoiceConfiguredForAccount = isVoiceConfiguredForAccount
                ?: { accountId ->
                    (capabilities.speechToText || capabilities.textToSpeech) && quotaForAccount(accountId)
                },
            isDocumentConfiguredForAccount = isDocumentConfiguredForAccount
                ?: { accountId -> capabilities.documentToMarkdown && quotaForAccount(accountId) },
            isProxyConfigured = isProxyConfigured,
            webSearchMissingReason = webSearchMissingReason,
            ideProxyFactory = ideProxyFactory,
        )
    }
}
