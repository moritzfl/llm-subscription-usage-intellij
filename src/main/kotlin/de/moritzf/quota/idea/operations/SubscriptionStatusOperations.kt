package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.mcp.*
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.McpAccountToolStatus
import de.moritzf.quota.shared.McpJson

internal suspend fun SubscriptionOperations.subscription_quota(
    provider: QuotaProviderType,
    account: String? = null,
): String {
    return quotaResult(provider, account)
}

internal suspend fun SubscriptionOperations.subscription_tools_status(
    capability: de.moritzf.quota.idea.settings.AccountCapability? = null,
    model: String? = null,
): String {
    val settings = runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    val accounts = settings?.accounts.orEmpty()
    val requestedCapability = capability ?: de.moritzf.quota.idea.settings.AccountCapability.QUOTA
    val statuses =
        if (accounts.isEmpty() || settings == null) {
            ProviderCatalog.all.map { descriptor ->
                accountStatus(
                    id = descriptor.type.id,
                    type = descriptor.type,
                    name = descriptor.type.displayName,
                    label = descriptor.type.displayName,
                    isDefault = true,
                    allowFailover = false,
                    descriptor = descriptor,
                    capability = requestedCapability,
                    model = model,
                )
            }
        } else {
            accounts.mapNotNull { account ->
                val type = account.providerType() ?: return@mapNotNull null
                val descriptor = ProviderCatalog.get(type)
                accountStatus(
                    id = account.id,
                    type = type,
                    name = account.name,
                    label = settings.accountListLabel(account),
                    isDefault = account.isDefault,
                    allowFailover = account.allowFailover,
                    descriptor = descriptor,
                    capability = requestedCapability,
                    model = model,
                )
            }
        }
    return McpJson.accountToolsStatus(
        statuses,
        capability = capability?.name,
        model = model?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun SubscriptionOperations.quotaResult(
    type: QuotaProviderType,
    accountParam: String? = null,
): String {
    val account =
        try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                type,
                accountParam,
                de.moritzf.quota.idea.settings.AccountCapability.QUOTA,
            )
        } catch (exception: de.moritzf.quota.idea.settings.AccountResolveException) {
            exception.rethrowIfCancellation()
            return errorResult(exception.message ?: "Account not found")
        }
    val registration = UsageQuotaMcpRegistry.get(type)
    val usageService = QuotaUsageService.getInstance()
    usageService.refreshBlocking(account.id)

    val error = usageService.getLastError(account.id)
    if (!error.isNullOrBlank()) {
        return errorResult(error)
    }

    val payload =
        usageService.getLastResponseJson(account.id) ?: registration.json(usageService, type)
    if (payload.isNullOrBlank()) {
        return errorResult(registration.emptyMessage)
    }
    return payload
}

internal fun SubscriptionOperations.accountStatus(
    id: String,
    type: QuotaProviderType,
    name: String,
    label: String,
    isDefault: Boolean,
    allowFailover: Boolean,
    descriptor: de.moritzf.quota.idea.common.ProviderDescriptor,
    capability: de.moritzf.quota.idea.settings.AccountCapability,
    model: String?,
): McpAccountToolStatus {
    val caps = descriptor.capabilities
    val searchType = descriptor.webSearchType
    val webSearchAvailable = searchType != null && descriptor.isWebSearchConfiguredForAccount(id)
    val quotaConfigured = descriptor.isQuotaConfiguredForAccount(id)
    val reason =
        if (searchType == null) {
            "Web search is not offered for this provider."
        } else if (!webSearchAvailable) {
            descriptor.webSearchMissingReason
        } else {
            null
        }
    val quota = runCatching { QuotaUsageService.getInstance().getLastQuota(id) }.getOrNull()
    val op = de.moritzf.quota.idea.settings.OperationQuota.status(quota, capability, model)
    val now = kotlin.time.Clock.System.now()
    val snapshotAgeMs =
        op.fetchedAt?.let { fetched -> (now - fetched).inWholeMilliseconds.coerceAtLeast(0) }
    return McpAccountToolStatus(
        id = id,
        type = type.id,
        name = name,
        label = label,
        isDefault = isDefault,
        allowFailover = allowFailover,
        quotaConfigured = quotaConfigured,
        webSearchAvailable = webSearchAvailable,
        webSearchType = searchType,
        webFetchAvailable = caps.webFetch && webSearchAvailable,
        imageGenerationAvailable =
            caps.imageGeneration && descriptor.isImageGenerationConfiguredForAccount(id),
        videoGenerationAvailable = caps.videoGeneration && quotaConfigured,
        speechToTextAvailable = caps.speechToText && descriptor.isVoiceConfiguredForAccount(id),
        textToSpeechAvailable = caps.textToSpeech && descriptor.isVoiceConfiguredForAccount(id),
        documentToMarkdownAvailable =
            caps.documentToMarkdown && descriptor.isDocumentConfiguredForAccount(id),
        visionAvailable = caps.vision && descriptor.isVisionConfiguredForAccount(id),
        reason = reason,
        snapshotAgeMs = snapshotAgeMs,
        fetchedAt = op.fetchedAt?.toString(),
        quotaAvailable = if (quota == null) null else !op.exhausted,
        limitingPool = op.limitingPool,
        usagePercent = op.usagePercent,
        resetsAt = op.resetsAt?.toString(),
        exhaustionReason = op.reason,
    )
}
