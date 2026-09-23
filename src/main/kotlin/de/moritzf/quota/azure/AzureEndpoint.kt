package de.moritzf.quota.azure

import java.net.URI

internal const val AZURE_COGNITIVE_SCOPE = "https://cognitiveservices.azure.com/.default"
internal const val AZURE_FOUNDRY_SCOPE = "https://ai.azure.com/.default"
internal const val AZURE_MANAGEMENT_ROOT = "https://management.azure.com"

private val SUBSCRIPTION_ID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)
private val RESOURCE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,62}$")
private val LOCATION = Regex("^[a-z0-9]{1,32}$")
internal val AZURE_DEPLOYMENT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

internal data class AzureAccountConfig(
    val subscriptionId: String? = null,
    val resourceName: String? = null,
    val endpoint: String? = null,
    val location: String? = null,
    val deploymentNames: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

internal data class AzureInferenceTarget(
    val baseUrl: String,
    val host: String,
)

/** Documented OpenAI v1 route, or the endpoint the user pasted when it is already an Azure host. */
internal fun azureAccountConfig(
    subscriptionId: String?,
    resourceName: String?,
    endpoint: String?,
    location: String?,
    deploymentNames: String?,
): AzureAccountConfig {
    val warnings = mutableListOf<String>()
    val subscription = subscriptionId?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (!SUBSCRIPTION_ID.matches(it)) warnings += "Subscription id was ignored because it is not a GUID."
    }?.takeIf { SUBSCRIPTION_ID.matches(it) }
    val resource = resourceName?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (!RESOURCE_NAME.matches(it)) warnings += "Resource name was ignored because it is not an Azure resource name."
    }?.takeIf { RESOURCE_NAME.matches(it) }
    val normalizedEndpoint = endpoint?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        normalizeAzureEndpoint(raw) ?: run {
            warnings += "Endpoint was ignored. Use an https Azure OpenAI, Cognitive Services, or Foundry host."
            null
        }
    }
    val normalizedLocation = location?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.also {
        if (!LOCATION.matches(it)) warnings += "Location was ignored. Use a region id such as eastus."
    }?.takeIf { LOCATION.matches(it) }
    val deployments = deploymentNames.orEmpty()
        .split(',', ' ', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    val accepted = deployments.filter { AZURE_DEPLOYMENT_NAME.matches(it) }
    if (accepted.size != deployments.size) warnings += "A deployment name was ignored because it is not a deployment id."
    return AzureAccountConfig(subscription, resource, normalizedEndpoint, normalizedLocation, accepted, warnings)
}

internal fun azureInferenceTarget(config: AzureAccountConfig): AzureInferenceTarget? {
    config.endpoint?.let { return AzureInferenceTarget(it, URI(it).host.lowercase()) }
    val resource = config.resourceName ?: return null
    val base = "https://$resource.openai.azure.com/openai/v1"
    return AzureInferenceTarget(base, "$resource.openai.azure.com")
}

internal fun normalizeAzureEndpoint(raw: String): String? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host?.lowercase()?.trim('.') ?: return null
    if (!isAllowedAzureHost(host) || uri.userInfo != null) return null
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val apiPath = when {
        path.contains("/openai") -> if (path.endsWith("/openai")) "$path/v1" else path
        path.isNotEmpty() && path != "/" -> path
        else -> "/openai/v1"
    }
    return "https://$host$apiPath"
}

internal fun isAllowedAzureHost(host: String): Boolean {
    val name = host.lowercase()
    return name.endsWith(".openai.azure.com") ||
        name.endsWith(".cognitiveservices.azure.com") ||
        name.endsWith(".services.ai.azure.com") ||
        name.endsWith(".openai.azure.us") ||
        name.endsWith(".cognitiveservices.azure.us") ||
        name.endsWith(".openai.azure.cn") ||
        name.endsWith(".cognitiveservices.azure.cn")
}

/**
 * OpenCode picks the Entra audience from the request host. Foundry inference uses
 * `https://ai.azure.com`; `/models` on that host, and every other Azure OpenAI host, uses Cognitive Services.
 */
internal fun azureScopeForUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host?.lowercase().orEmpty()
    val path = uri?.rawPath.orEmpty()
    if (host.endsWith(".azure.us")) return "https://cognitiveservices.azure.us/.default"
    if (host.endsWith(".azure.cn")) return "https://cognitiveservices.azure.cn/.default"
    if (host.endsWith(".services.ai.azure.com") && !path.startsWith("/models")) return AZURE_FOUNDRY_SCOPE
    return AZURE_COGNITIVE_SCOPE
}

internal fun azureManagementRoot(endpointHost: String?): String {
    val host = endpointHost?.lowercase().orEmpty()
    return when {
        host.endsWith(".azure.us") || host.endsWith(".usgovcloudapi.net") -> "https://management.usgovcloudapi.net"
        host.endsWith(".azure.cn") || host.endsWith(".chinacloudapi.cn") -> "https://management.chinacloudapi.cn"
        else -> AZURE_MANAGEMENT_ROOT
    }
}

internal fun azureManagementScope(root: String): String = "$root/.default"

internal fun azureUpstreamUrl(baseUrl: String, upstreamPath: String): String {
    val root = baseUrl.trimEnd('/')
    val path = upstreamPath.trimStart('/')
    val url = "$root/$path"
    // GA /v1 rejects the query. Older non-v1 routes still need it.
    if (pathSegments(url).any { it == "v1" }) return url
    return "$url?api-version=v1"
}

private fun pathSegments(url: String): List<String> {
    val path = runCatching { URI(url).rawPath }.getOrNull() ?: url.substringBefore('?')
    return path.split('/').filter { it.isNotEmpty() }
}
