package de.moritzf.quota.idea.compatible

import de.moritzf.quota.compatible.CompatibleEndpoint
import de.moritzf.quota.compatible.CompatibleMediaClient
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaSettingsState

internal fun QuotaProviderType?.isCompatibleConnection() =
    this == QuotaProviderType.OPENAI_COMPATIBLE || this == QuotaProviderType.LITELLM

internal fun compatibleEndpoint(account: ProviderAccount?): CompatibleEndpoint? = account?.let {
    runCatching {
        CompatibleEndpoint.parse(
                it.extra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL).orEmpty(),
                it.extra(ProviderAccount.EXTRA_COMPATIBLE_MODELS).orEmpty(),
            )
            .copy(
                liteLlm = it.providerType() == QuotaProviderType.LITELLM,
                visionModel = it.extra(ProviderAccount.EXTRA_VISION_MODEL),
                documentModel = it.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL),
            )
    }
        .getOrNull()
}

internal fun compatibleConfigured(accountId: String): Boolean =
    compatibleEndpoint(QuotaSettingsState.getInstance().account(accountId)) != null &&
        CompatibleApiKeyStore.forAccount(accountId).loadBlocking() != null

/** Resolve exactly once; never fall over to an unrelated endpoint when an operation fails. */
internal fun compatibleAccount(
    type: QuotaProviderType,
    account: String? = null,
    settings: QuotaSettingsState = QuotaSettingsState.getInstance(),
): ProviderAccount {
    require(type.isCompatibleConnection())
    val rows = settings.accountsOf(type)
    if (account.isNullOrBlank() && rows.size > 1)
        error(
            "Multiple ${type.displayName} connections. Pass account= with a connection name or ID: ${rows.joinToString { it.name }}"
        )
    return AccountResolver.resolve(type, account, AccountCapability.QUOTA, settings).snapshot()
}

internal fun compatibleMediaClient(account: ProviderAccount): CompatibleMediaClient {
    val key =
        CompatibleApiKeyStore.forAccount(account.id).loadBlocking()
            ?: error("Enter an API key for ${account.name} in settings.")
    val settings = QuotaSettingsState.getInstance()
    val endpoint = compatibleEndpoint(account) ?: error("Enter a valid API base URL in settings.")
    synchronized(settings) {
        check(endpoint == compatibleEndpoint(settings.account(account.id))) {
            "Connection changed. Retry the request."
        }
        check(key == CompatibleApiKeyStore.forAccount(account.id).load()) {
            "API key changed. Retry the request."
        }
    }
    return CompatibleMediaClient(endpoint, key)
}
