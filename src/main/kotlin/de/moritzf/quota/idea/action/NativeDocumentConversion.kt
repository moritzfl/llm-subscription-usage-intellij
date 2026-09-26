package de.moritzf.quota.idea.action

import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.proxy.transport.UrlResolver
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.azureInferenceTarget
import de.moritzf.quota.azure.azureNativePdfDeploymentId
import de.moritzf.quota.azure.azureScopeForUrl
import de.moritzf.quota.azure.azureUpstreamUrl
import de.moritzf.quota.github.proxy.fetchGitHubListedModels
import de.moritzf.quota.github.proxy.githubCopilotHeaders
import de.moritzf.quota.github.proxy.githubDocumentRoute
import de.moritzf.quota.idea.common.AzureQuotaProvider
import de.moritzf.quota.idea.common.IdeProxyFactories
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.opencode.proxy.OpenCodeConsoleProxy
import de.moritzf.quota.opencode.proxy.OpenCodeConsoleSession
import de.moritzf.quota.opencode.proxy.OpenCodeRequestHeaders
import de.moritzf.quota.shared.NativePdfPoster
import de.moritzf.quota.shared.NativePdfRoute
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

internal object NativeDocumentConversion {
    private val poster = NativePdfPoster()
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    fun github(accountId: String, model: String, source: Path, output: Path): String {
        val token = GitHubCredentialsStore.forAccount(accountId).loadBlocking()?.accessToken
            ?: error("GitHub Copilot login required.")
        val selected = model.trim().ifBlank { error("Select a GitHub Copilot document model in settings.") }
        val base = IdeProxyFactories.githubCopilotBaseUri(QuotaSettingsState.getInstance().githubHostFor(accountId))
        val listed = runCatching { fetchGitHubListedModels(base, token, httpClient) }.getOrDefault(emptyList())
        val route = githubDocumentRoute(selected, listed.firstOrNull { it.id == selected }?.endpoints.orEmpty())
        return poster.convert(uri(base, route), githubHeaders(token), route, selected, source, output)
    }

    fun openCode(accountId: String, model: String, source: Path, output: Path): String {
        val auth = OpenCodeAuthService.getInstance()
        val credentials = auth.credentials(accountId) ?: error("OpenCode login required.")
        val token = credentials.accessToken ?: error("OpenCode login required.")
        val organization = credentials.accountId ?: QuotaSettingsState.getInstance().openCodeWorkspaceIdFor(accountId)
        val session = OpenCodeConsoleSession(accountId, token, organization) { auth.credentials(accountId, it)?.accessToken }
        val models = OpenCodeConsoleProxy(httpClient, RequestLogger(false, Path.of("logs"))).models(session)
        val selected = model.trim().ifBlank { error("Select an OpenCode document model in settings.") }
        val match = models.firstOrNull { it.model.upstreamId == selected || it.model.localId == selected }
            ?: error("OpenCode did not list '$selected'. Refresh settings and pick a model from the list.")
        val route = when (match.nativeRoute) {
            SubscriptionProxyRoute.RESPONSES -> NativePdfRoute.RESPONSES
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES -> NativePdfRoute.ANTHROPIC
            else -> NativePdfRoute.CHAT
        }
        val headers = match.headers +
            (if (route == NativePdfRoute.ANTHROPIC) mapOf("anthropic-version" to "2023-06-01") else emptyMap()) +
            OpenCodeRequestHeaders.forRequest("lsu-document", JsonObject(emptyMap())) +
            mapOf("Authorization" to "Bearer ${session.accessToken}")
        return poster.convert(match.targetUri, headers, route, match.model.upstreamId, source, output, match.body)
    }

    fun azure(accountId: String, selection: String, source: Path, output: Path): String {
        val deployment = azureNativePdfDeploymentId(selection)
        val executable = AzureQuotaProvider.executableForAccount(accountId) ?: error("Azure CLI not found.")
        val config = AzureQuotaProvider.configForAccount(accountId)
        val target = azureInferenceTarget(config) ?: error("Azure endpoint is missing.")
        val token = AzureCli(executable).accessToken(azureScopeForUrl(target.baseUrl), config.subscriptionId).accessToken
        val url = URI.create(azureUpstreamUrl(target.baseUrl, "chat/completions"))
        return poster.convert(url, mapOf("Authorization" to "Bearer $token"), NativePdfRoute.CHAT, deployment, source, output)
    }

    private fun uri(base: URI, route: NativePdfRoute): URI {
        val path = when (route) {
            NativePdfRoute.RESPONSES -> "/responses"
            NativePdfRoute.ANTHROPIC -> "/v1/messages"
            NativePdfRoute.CHAT -> "/chat/completions"
        }
        return URI.create(UrlResolver.resolveTargetUrl(path, base.toString()))
    }

    private fun githubHeaders(token: String): Map<String, String> =
        githubCopilotHeaders(token) + mapOf("Copilot-Vision-Request" to "true")
}
