package de.moritzf.quota.idea.settings

/** Captured on the EDT for one test; never installed into application settings or Password Safe. */
internal class ProviderTestConnection(
    account: ProviderAccount,
    apiKeyOverride: String? = null,
) {
    val account: ProviderAccount = account.snapshot()
    val apiKeyOverride: String? = apiKeyOverride?.trim()

    /**
     * Null means the field still represents the saved key; an empty draft deliberately clears it.
     */
    fun apiKey(loadSaved: () -> String?): String? =
        (apiKeyOverride ?: loadSaved())?.trim()?.takeIf(String::isNotEmpty)

    fun compatibleClient(): de.moritzf.quota.compatible.CompatibleMediaClient =
        de.moritzf.quota.compatible.CompatibleMediaClient(
            de.moritzf.quota.idea.compatible.compatibleEndpoint(account)
                ?: error("Enter a valid API base URL."),
            apiKey {
                de.moritzf.quota.idea.compatible.CompatibleApiKeyStore.forAccount(account.id)
                    .loadBlocking()
            } ?: error("Enter an API key."),
        )
}
