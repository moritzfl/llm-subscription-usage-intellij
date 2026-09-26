package de.moritzf.quota.azure

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Clock

internal fun interface AzureHttp {
    fun get(url: String, bearerToken: String): AzureHttpResult

    companion object {
        fun javaClient(): AzureHttp {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            return AzureHttp { url, token ->
                val response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                AzureHttpResult(response.statusCode(), response.body().orEmpty())
            }
        }
    }
}

internal data class AzureHttpResult(val status: Int, val body: String)

/**
 * Reads whatever the signed-in CLI identity can see. A 403 on quota, deployments, or models
 * becomes a warning. Identity metadata is optional as well as management-plane access.
 */
internal class AzureQuotaClient(
    private val cli: AzureCli,
    private val http: AzureHttp = AzureHttp.javaClient(),
    private val liveUsage: () -> AzureRateLimitSnapshot? = { null },
    private val clock: Clock = Clock.System,
) {
    fun fetch(config: AzureAccountConfig): AzureQuota {
        val warnings = config.warnings.toMutableList()
        val accountListRaw = try {
            cli.listAccountsJson()
        } catch (exception: AzureCliException) {
            warnings += "Could not read Azure account metadata. ${exception.message}"
            null
        }
        val accounts = accountListRaw?.let { parseAzureAccountList(it) }.orEmpty()
        val identity = accounts.firstOrNull { account ->
            if (config.subscriptionId.isNullOrBlank()) account.isDefault
            else account.subscriptionId.equals(config.subscriptionId, ignoreCase = true)
        }
        if (accountListRaw != null && identity == null) {
            warnings += "Azure CLI did not return a subscription. Run az login, then retry."
        }
        val accountElement = accountListRaw?.let {
            selectedAzureAccountElement(it, config.subscriptionId ?: identity?.subscriptionId)
        }
        val subscriptionId = config.subscriptionId ?: identity?.subscriptionId
        val target = azureInferenceTarget(config)
        val managementRoot = azureManagementRoot(target?.host)
        val managementToken = subscriptionId?.let {
            tokenOrNull(azureManagementScope(managementRoot), it, warnings, "subscription quota")
        }
        val resourcesBody = mutableListOf<String?>()
        val resources = if (managementToken != null) {
            val (body, parsed) = readResources(managementRoot, subscriptionId, managementToken, warnings)
            resourcesBody += body
            parsed
        } else {
            emptyList()
        }
        val matched = resources.filter { resource ->
            when {
                config.endpoint != null -> {
                    val resourceHost = resource.endpoint?.let { runCatching { URI(it).host }.getOrNull() }
                    resourceHost.equals(target?.host, ignoreCase = true) ||
                        resource.name.equals(target?.host?.substringBefore('.'), ignoreCase = true)
                }
                config.resourceName != null -> resource.name.equals(config.resourceName, ignoreCase = true)
                else -> true
            }
        }
        if (target != null && resources.isNotEmpty() && matched.isEmpty()) {
            warnings += "No readable Azure resource matches the configured endpoint. Named deployments can still be proxied."
        }
        val locations = buildList {
            config.location?.let(::add)
            if (isEmpty()) matched.mapNotNull { it.location }.distinct().forEach(::add)
        }.distinct().take(MAX_LOCATIONS)
        val windows = mutableListOf<AzureUsageWindow>()
        val usageBodies = linkedMapOf<String, String>()
        if (managementToken != null && locations.isEmpty()) {
            warnings += "Quota usage needs a region. Set a location, or grant access to list Cognitive Services accounts."
        }
        for (location in locations) {
            val result = managementToken?.let { get("$managementRoot/subscriptions/$subscriptionId/providers/Microsoft.CognitiveServices/locations/$location/usages?api-version=2024-10-01", it) }
            if (result == null) continue
            if (result.status == 403 || result.status == 401) {
                warnings += "Quota usage in $location is not readable with this login. Cognitive Services Usages Reader is optional; proxy still works."
                continue
            }
            if (result.status !in 200..299) {
                warnings += "Quota usage in $location returned HTTP ${result.status}."
                continue
            }
            usageBodies[location] = result.body
            val (parsed, parseWarnings) = parseAzureUsages(result.body, location)
            warnings += parseWarnings
            windows += parsed
        }
        var quotaTiersBody: String? = null
        val tier = managementToken?.let { token ->
            val result = get("$managementRoot/subscriptions/$subscriptionId/providers/Microsoft.CognitiveServices/quotaTiers?api-version=2025-10-01-preview", token)
            if (result.status in 200..299) {
                quotaTiersBody = result.body
                parseAzureQuotaTier(result.body)
            } else {
                null
            }
        }
        val models = mutableListOf<String>()
        var modelsRead = false
        val deploymentBodies = linkedMapOf<String, String>()
        for (resource in matched.take(MAX_RESOURCES)) {
            val group = resource.resourceGroup ?: continue
            val token = managementToken ?: continue
            val result = get(
                "$managementRoot/subscriptions/$subscriptionId/resourceGroups/$group/providers/Microsoft.CognitiveServices/accounts/${resource.name}/deployments?api-version=2023-05-01",
                token,
            )
            if (result.status == 403 || result.status == 401) {
                warnings += "Deployment capacity for ${resource.name} is not readable with this login."
                continue
            }
            if (result.status in 200..299) {
                deploymentBodies[resource.name] = result.body
                val deployments = parseAzureDeployments(result.body, resource)
                if (deployments == null) {
                    warnings += "Deployment list for ${resource.name} was not readable."
                    continue
                }
                windows += deployments
                if (target != null) {
                    models += deployments.map { it.id }.filter { AZURE_DEPLOYMENT_NAME.matches(it) }
                    modelsRead = true
                }
            }
        }
        var modelsBody: String? = null
        if (target != null) {
            // ARM names are deployment IDs. The data-plane catalog is a fallback when ARM is unreadable.
            val dataToken = if (modelsRead) null else
                tokenOrNull(azureScopeForUrl(target.baseUrl), subscriptionId, warnings, "the Azure OpenAI endpoint")
            if (dataToken != null) {
                val result = get(azureUpstreamUrl(target.baseUrl, "models"), dataToken)
                if (result.status in 200..299) {
                    modelsBody = result.body
                    val parsed = parseAzureModels(result.body)
                    if (parsed != null) {
                        models += parsed
                        modelsRead = true
                    } else {
                        warnings += "Model list was not readable. Named deployments can still be proxied."
                    }
                } else if (result.status == 403 || result.status == 401) {
                    warnings += "Model list is not readable with this login. Named deployments can still be proxied."
                } else {
                    warnings += "Model list returned HTTP ${result.status}."
                }
            }
        } else {
            warnings += "Set a resource name or endpoint to use the local proxy."
        }
        if (modelsRead) AzureModelCatalog.remember(catalogKey(config.subscriptionId, config), models.distinct())
        models += config.deploymentNames
        liveUsage()?.let { liveWindow(it, clock.now()) }?.let(windows::add)
        val distinctModels = models.distinct()
        return AzureQuota(
            account = AzureAccountIdentity(
                userName = identity?.userName,
                userType = identity?.userType,
                subscriptionId = subscriptionId,
                subscriptionName = identity?.subscriptionName,
                tenantId = identity?.tenantId,
                tier = tier,
            ),
            windows = windows.distinctBy { it.key },
            models = distinctModels,
            warnings = warnings.distinct(),
            modelCatalogRead = modelsRead,
            fetchedAt = clock.now(),
        ).also { quota ->
            quota.rawJson = buildAzureRawResponse(
                account = accountElement,
                resources = resourcesBody.firstOrNull(),
                usages = usageBodies,
                quotaTiers = quotaTiersBody,
                deployments = deploymentBodies,
                models = modelsBody,
            )
        }
    }

    private fun readResources(
        managementRoot: String,
        subscriptionId: String,
        token: String,
        warnings: MutableList<String>,
    ): Pair<String?, List<AzureResourceRef>> {
        val result = get("$managementRoot/subscriptions/$subscriptionId/providers/Microsoft.CognitiveServices/accounts?api-version=2023-05-01", token)
        if (result.status == 403 || result.status == 401) {
            warnings += "Azure OpenAI resources are not listable with this login. Paste a resource name or endpoint."
            return null to emptyList()
        }
        if (result.status !in 200..299) return null to emptyList()
        return result.body to parseAzureResources(result.body)
    }

    private fun tokenOrNull(scope: String, subscriptionId: String?, warnings: MutableList<String>, purpose: String): String? {
        return try {
            cli.accessToken(scope, subscriptionId).accessToken
        } catch (exception: AzureCliException) {
            warnings += "Could not get an Azure token for $purpose. ${exception.message}"
            null
        }
    }

    private fun get(url: String, token: String): AzureHttpResult = try {
        http.get(url, token)
    } catch (_: Exception) {
        AzureHttpResult(0, "")
    }

    companion object {
        private const val MAX_LOCATIONS = 8
        private const val MAX_RESOURCES = 3

        fun catalogKey(subscriptionId: String?, config: AzureAccountConfig): String =
            listOf(subscriptionId.orEmpty(), config.resourceName.orEmpty(), config.endpoint.orEmpty()).joinToString("|")
    }
}

internal object AzureModelCatalog {
    private val values = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    fun remember(key: String, models: List<String>) {
        if (key.isNotBlank()) values[key] = models
    }

    fun read(key: String): List<String> = values[key].orEmpty()
}
