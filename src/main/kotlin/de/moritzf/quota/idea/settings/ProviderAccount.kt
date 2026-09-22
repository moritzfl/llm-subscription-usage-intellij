package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import java.util.UUID

data class ProviderAccount(
    var id: String = "",
    var typeId: String = "",
    var name: String = "",
    var isDefault: Boolean = false,
    var allowFailover: Boolean = false,
    var hiddenFromPopup: Boolean = false,
    var extras: MutableMap<String, String> = mutableMapOf(),
) {
    fun providerType(): QuotaProviderType? = QuotaProviderType.fromId(typeId)

    fun extra(key: String): String? = extras[key]?.trim()?.takeIf { it.isNotEmpty() }

    fun setExtra(key: String, value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) extras.remove(key) else extras[key] = trimmed
    }

    fun snapshot(): ProviderAccount = copy(extras = extras.toMutableMap())

    companion object {
        const val EXTRA_AGY_EXECUTABLE = "agyExecutable"
        const val EXTRA_OPENCODE_WORKSPACE = "openCodeWorkspaceId"
        const val EXTRA_GITHUB_HOST = "githubEnterpriseHost"
        const val EXTRA_MINIMAX_REGION = "minimaxRegionPreference"
        const val EXTRA_OLLAMA_MONTHLY_RESET = "ollamaMonthlyResetAt"

        fun newId(): String = UUID.randomUUID().toString()

        fun create(type: QuotaProviderType, name: String, isFirstOfType: Boolean): ProviderAccount {
            return ProviderAccount(
                id = if (isFirstOfType) type.id else newId(),
                typeId = type.id,
                name = name,
                isDefault = isFirstOfType,
                allowFailover = false,
            )
        }
    }
}
