package de.moritzf.quota.azure

/** Azure discovery does not declare image support; these candidates must be tested by the user. */
internal fun azureVisionChoices(
    quota: AzureQuota?,
    configuredDeployments: String?,
    selection: String?,
    resourceName: String?,
): List<String> {
    val deployments = quota?.windows.orEmpty().filter { it.kind == AzureUsageWindow.DEPLOYMENT }
    val candidates =
        deployments
            .filter {
                resourceName == null || it.resourceName.equals(resourceName, ignoreCase = true)
            }
            .map { it.id } +
            quota?.models.orEmpty().filter { id -> deployments.none { it.id == id } } +
            configuredDeployments.orEmpty().split(',', ' ', '\n') +
            listOfNotNull(selection)
    return candidates
        .map { it.trim() }
        .filter { id ->
            val deployment = deployments.firstOrNull { it.id.equals(id, ignoreCase = true) }
            val model = deployment?.modelName ?: id
            AZURE_DEPLOYMENT_NAME.matches(id) &&
                id != "-" &&
                (deployment == null ||
                    resourceName == null ||
                    deployment.resourceName.equals(resourceName, ignoreCase = true)) &&
                !isAzureOcrModel(model) &&
                !model.startsWith("text-embedding-", ignoreCase = true)
        }
        .distinct()
        .sorted()
}
