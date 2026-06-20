package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota

internal data class QuotaProviderRegistration(
    val type: QuotaProviderType,
    val providerFactory: () -> QuotaProvider,
    val snapshotCodec: QuotaCodec<out ProviderQuota>,
)

internal object QuotaProviderRegistry {
    val all: List<QuotaProviderRegistration> = listOf(
        QuotaProviderRegistration(
            type = QuotaProviderType.CURSOR,
            providerFactory = ::CursorQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(CursorQuota.serializer()),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.GITHUB,
            providerFactory = ::GitHubQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(GitHubQuota.serializer()),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.KIMI,
            providerFactory = ::KimiQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(KimiQuota.serializer()),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.MINIMAX,
            providerFactory = ::MiniMaxQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(MiniMaxQuota.serializer()),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.OLLAMA,
            providerFactory = ::OllamaQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(OllamaQuota.serializer()),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.OPEN_AI,
            providerFactory = ::OpenAiQuotaProvider,
            snapshotCodec = OpenAiQuotaCodec,
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.OPEN_CODE,
            providerFactory = ::OpenCodeQuotaProvider,
            snapshotCodec = PlainQuotaCodec(OpenCodeQuota.serializer()),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.SUPERGROK,
            providerFactory = ::SuperGrokQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(SuperGrokQuota.serializer()),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.ZAI,
            providerFactory = ::ZaiQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(ZaiQuota.serializer()),
        ),
    )

    private val byType: Map<QuotaProviderType, QuotaProviderRegistration> = all.associateBy { it.type }

    fun get(type: QuotaProviderType): QuotaProviderRegistration = byType.getValue(type)

    fun getOrNull(type: QuotaProviderType): QuotaProviderRegistration? = byType[type]

    fun createProviders(): List<QuotaProvider> = all.map { it.providerFactory() }

    fun defaultProviderOrder(): List<QuotaProviderType> = all.map { it.type }.sortedBy { it.displayName }

    fun defaultProviderOrderStorageValue(): String = defaultProviderOrder().joinToString(",") { it.id }

    fun mergeProviderOrder(storedOrder: List<QuotaProviderType>): List<QuotaProviderType> {
        val allProviders = defaultProviderOrder()
        val validStored = storedOrder.filter { it in allProviders }
        if (validStored.isEmpty()) {
            return allProviders
        }

        val result = validStored.toMutableList()
        val missing = allProviders.filter { it !in result }
        for (provider in missing) {
            val providerIndex = allProviders.indexOf(provider)
            if (providerIndex == 0) {
                result.add(0, provider)
                continue
            }

            val predecessor = allProviders[providerIndex - 1]
            val insertAfter = result.indexOfLast { it == predecessor }
            val insertIndex = if (insertAfter >= 0) {
                insertAfter + 1
            } else {
                val fallback = result.indexOfLast { allProviders.indexOf(it) < providerIndex }
                if (fallback >= 0) fallback + 1 else 0
            }
            result.add(insertIndex, provider)
        }
        return result
    }
}
