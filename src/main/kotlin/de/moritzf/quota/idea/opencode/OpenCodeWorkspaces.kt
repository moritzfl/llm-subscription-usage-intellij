package de.moritzf.quota.idea.opencode

import java.util.concurrent.ConcurrentHashMap

/**
 * The OpenCode organization each account uses. A browser-scoped login wins, then the one chosen in
 * settings; otherwise quota refreshes discover one and it is remembered here, in memory, because
 * only the settings dialog's Apply writes settings.
 */
internal object OpenCodeWorkspaces {
    private val discovered = ConcurrentHashMap<String, String>()

    fun remember(accountId: String, workspaceId: String) {
        discovered[accountId] = workspaceId
    }

    fun forget(accountId: String) {
        discovered.remove(accountId)
    }

    fun discovered(accountId: String): String? = discovered[accountId]

    fun resolve(accountId: String, loginOrganization: String?, chosen: String?): String? =
        loginOrganization?.takeIf { it.isNotBlank() }
            ?: chosen?.takeIf { it.isNotBlank() }
            ?: discovered[accountId]
}
