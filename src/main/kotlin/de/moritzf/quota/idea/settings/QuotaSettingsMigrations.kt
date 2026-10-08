package de.moritzf.quota.idea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource

/**
 * One-off cleanup of persisted data, keyed by the settings version that introduced it.
 *
 * The version describes the shape of the stored data, not the plugin release, so it behaves the
 * same in a release, a sandbox, and a build without a stamped plugin version.
 *
 * Migrations must be idempotent: settings written by a newer plugin can be read back by an older
 * one, which drops fields it does not know, so a migration can be asked to run twice.
 */
internal interface QuotaSettingsMigration {
    /** Stable identifier, used for logging only. */
    val id: String

    /** Settings version this migration belongs to; it runs for state below that version. */
    val version: Int

    fun apply(state: QuotaSettingsState)
}

/**
 * Runs pending migrations while [QuotaSettingsState] loads, before any consumer reads settings, and
 * records the reached version in [QuotaSettingsState.settingsVersion].
 */
internal object QuotaSettingsMigrations {
    /** Raise by one whenever a migration is added, and tag the new migration with that value. */
    const val CURRENT_VERSION: Int = 3

    val ALL: List<QuotaSettingsMigration> =
        listOf(
            DropOllamaSessionCookieCredentials,
            NormalizeIndicatorSourceStorageIds,
            CreateProviderAccounts,
        )

    fun run(state: QuotaSettingsState, migrations: List<QuotaSettingsMigration> = ALL) {
        var failed = false
        migrations
            .filter { it.version > state.settingsVersion }
            .sortedBy { it.version }
            .forEach { migration ->
                runCatching { migration.apply(state) }
                    .onFailure { exception ->
                        failed = true
                        LOG.warn("Settings migration '${migration.id}' failed", exception)
                    }
            }
        if (failed) return
        // Settings written by a newer plugin keep their version, so its migrations are not
        // replayed once that plugin is used again.
        state.settingsVersion = maxOf(state.settingsVersion, CURRENT_VERSION)
    }

    private val LOG = Logger.getInstance(QuotaSettingsMigrations::class.java)
}

/**
 * Ollama Cloud quota used a browser session cookie before the official usage API. The cookies are
 * no longer read, so drop them from Password Safe instead of leaving them behind.
 */
internal object DropOllamaSessionCookieCredentials : QuotaSettingsMigration {
    override val id = "drop-ollama-session-cookie-credentials"
    override val version = 1

    private val obsoleteCredentials =
        listOf(
            CredentialAttributes("Ollama Session Cookie", "ollama-session"),
            CredentialAttributes("Ollama CF Clearance", "ollama-cf"),
        )

    override fun apply(state: QuotaSettingsState) {
        val application = ApplicationManager.getApplication() ?: return
        // Password Safe can block on the OS keychain, so keep it off the settings-loading thread.
        application.executeOnPooledThread {
            obsoleteCredentials.forEach { credentialAttributes ->
                runCatching {
                    if (PasswordSafe.instance.get(credentialAttributes) != null) {
                        PasswordSafe.instance.set(credentialAttributes, null)
                    }
                }
            }
        }
    }
}

internal object NormalizeIndicatorSourceStorageIds : QuotaSettingsMigration {
    override val id = "normalize-indicator-source-storage-ids"
    override val version = 2

    override fun apply(state: QuotaSettingsState) {
        state.indicatorSource =
            QuotaIndicatorSource.fromStorageValue(state.indicatorSource).storageId
        val active = state.lastActiveSource?.trim().orEmpty()
        if (active.isEmpty()) return
        val provider =
            QuotaProviderType.fromId(active)
                ?: QuotaIndicatorSource.entries
                    .firstOrNull { it.name.equals(active, ignoreCase = true) }
                    ?.providerType
        state.lastActiveSource = provider?.id
    }
}

internal object CreateProviderAccounts : QuotaSettingsMigration {
    override val id = "create-provider-accounts"
    override val version = 3

    override fun apply(state: QuotaSettingsState) {
        if (ApplicationManager.getApplication() == null) return
        runCatching {
            PasswordSafe.instance.get(
                CredentialAttributes("LLM Subscription Usage", "migration-probe")
            )
        }
            .getOrThrow()
        apply(state, TypeHasStoredCredentials::invoke)
    }

    fun apply(state: QuotaSettingsState, hasCredentials: (QuotaProviderType) -> Boolean) {
        if (state.accounts.isNotEmpty()) return

        val configured =
            QuotaProviderType.entries
                .filter { type ->
                    hasCredentials(type) || !state.cachedQuotaJson(type).isNullOrBlank()
                }
                .toSet()
        if (configured.isEmpty()) return

        val storedTypes =
            state.providerOrder
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull(QuotaProviderType::fromId)
                .distinct()
        val ordered =
            storedTypes.filter { it in configured } +
                configured.filter { it !in storedTypes }.sortedBy { it.displayName }

        state.accounts =
            ordered
                .map { type ->
                    ProviderAccount.create(type, type.displayName, isFirstOfType = true).apply {
                        hiddenFromPopup = state.isHiddenFromPopup(type)
                        when (type) {
                            QuotaProviderType.OPEN_CODE ->
                                setExtra(
                                    ProviderAccount.EXTRA_OPENCODE_WORKSPACE,
                                    state.openCodeWorkspaceId,
                                )
                            QuotaProviderType.GITHUB ->
                                setExtra(
                                    ProviderAccount.EXTRA_GITHUB_HOST,
                                    state.githubEnterpriseHost,
                                )
                            QuotaProviderType.MINIMAX ->
                                setExtra(
                                    ProviderAccount.EXTRA_MINIMAX_REGION,
                                    state.minimaxRegionPreference,
                                )
                            else -> Unit
                        }
                    }
                }
                .toMutableList()
        state.hiddenFromQuotaPopup.clear()
    }
}
