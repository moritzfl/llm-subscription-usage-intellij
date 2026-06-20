package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.settings.CursorSettingsPanel
import de.moritzf.quota.idea.settings.GitHubSettingsPanel
import de.moritzf.quota.idea.settings.KimiSettingsPanel
import de.moritzf.quota.idea.settings.MiniMaxSettingsPanel
import de.moritzf.quota.idea.settings.OllamaSettingsPanel
import de.moritzf.quota.idea.settings.OpenAiSettingsPanel
import de.moritzf.quota.idea.settings.OpenCodeSettingsPanel
import de.moritzf.quota.idea.settings.ProviderSettingsPanel
import de.moritzf.quota.idea.settings.SuperGrokSettingsPanel
import de.moritzf.quota.idea.settings.ZaiSettingsPanel
import de.moritzf.quota.idea.ui.indicator.CursorUi
import de.moritzf.quota.idea.ui.indicator.GitHubUi
import de.moritzf.quota.idea.ui.indicator.KimiUi
import de.moritzf.quota.idea.ui.indicator.MiniMaxUi
import de.moritzf.quota.idea.ui.indicator.OllamaUi
import de.moritzf.quota.idea.ui.indicator.OpenAiUi
import de.moritzf.quota.idea.ui.indicator.OpenCodeUi
import de.moritzf.quota.idea.ui.indicator.ProviderUi
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.SuperGrokUi
import de.moritzf.quota.idea.ui.indicator.ZaiUi
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota
import java.awt.Color
import javax.swing.Icon
import javax.swing.JComponent

internal data class ProviderSettingsPanelContext(
    val modalityComponentProvider: () -> JComponent?,
    val statusLabelDefaultForeground: Color?,
)

internal data class UsageQuotaMcpRegistration(
    val emptyMessage: String,
    val json: (QuotaUsageService, QuotaProviderType) -> String? = { service, type ->
        service.getLastResponseJson(type)
    },
)

internal data class QuotaProviderRegistration(
    val type: QuotaProviderType,
    val icon: Icon,
    val providerFactory: () -> QuotaProvider,
    val snapshotCodec: QuotaCodec<out ProviderQuota>,
    val ui: ProviderUi,
    val settingsPanelFactory: (ProviderSettingsPanelContext) -> ProviderSettingsPanel,
    val usageMcp: UsageQuotaMcpRegistration,
)

internal object QuotaProviderRegistry {
    val all: List<QuotaProviderRegistration> = listOf(
        QuotaProviderRegistration(
            type = QuotaProviderType.CURSOR,
            icon = QuotaIcons.CURSOR,
            providerFactory = ::CursorQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(CursorQuota.serializer()),
            ui = CursorUi,
            settingsPanelFactory = { context ->
                CursorSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration(
                emptyMessage = "No Cursor usage response available",
                json = { service, _ -> service.getLastResponseJson(QuotaProviderType.CURSOR)?.let(de.moritzf.quota.cursor.CursorQuotaClient::normalizeRawJson) },
            ),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.GITHUB,
            icon = QuotaIcons.GITHUB,
            providerFactory = ::GitHubQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(GitHubQuota.serializer()),
            ui = GitHubUi,
            settingsPanelFactory = { context ->
                GitHubSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration("No GitHub usage response available"),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.KIMI,
            icon = QuotaIcons.KIMI,
            providerFactory = ::KimiQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(KimiQuota.serializer()),
            ui = KimiUi,
            settingsPanelFactory = { context ->
                KimiSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration("No Kimi usage response available"),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.MINIMAX,
            icon = QuotaIcons.MINIMAX,
            providerFactory = ::MiniMaxQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(MiniMaxQuota.serializer()),
            ui = MiniMaxUi,
            settingsPanelFactory = { context ->
                MiniMaxSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration("No MiniMax usage response available"),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.OLLAMA,
            icon = QuotaIcons.OLLAMA,
            providerFactory = ::OllamaQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(OllamaQuota.serializer()),
            ui = OllamaUi,
            settingsPanelFactory = { context ->
                OllamaSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration(
                emptyMessage = "No Ollama usage response available",
                json = { service, _ ->
                    val quota = service.getLastQuota(QuotaProviderType.OLLAMA) as? OllamaQuota
                    quota?.let { runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), it) }.getOrNull() }
                },
            ),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.OPEN_AI,
            icon = QuotaIcons.OPENAI,
            providerFactory = ::OpenAiQuotaProvider,
            snapshotCodec = OpenAiQuotaCodec,
            ui = OpenAiUi,
            settingsPanelFactory = { context -> OpenAiSettingsPanel(context.modalityComponentProvider) },
            usageMcp = UsageQuotaMcpRegistration("No usage response available"),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.OPEN_CODE,
            icon = QuotaIcons.OPENCODE,
            providerFactory = ::OpenCodeQuotaProvider,
            snapshotCodec = PlainQuotaCodec(OpenCodeQuota.serializer()),
            ui = OpenCodeUi,
            settingsPanelFactory = { context ->
                OpenCodeSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration(
                emptyMessage = "No OpenCode usage response available",
                json = { service, _ ->
                    val quota = service.getLastQuota(QuotaProviderType.OPEN_CODE) as? OpenCodeQuota
                    quota?.let { runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), it) }.getOrNull() }
                },
            ),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.SUPERGROK,
            icon = QuotaIcons.SUPERGROK,
            providerFactory = ::SuperGrokQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(SuperGrokQuota.serializer()),
            ui = SuperGrokUi,
            settingsPanelFactory = { context ->
                SuperGrokSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration("No SuperGrok usage response available"),
        ),
        QuotaProviderRegistration(
            type = QuotaProviderType.ZAI,
            icon = QuotaIcons.ZAI,
            providerFactory = ::ZaiQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(ZaiQuota.serializer()),
            ui = ZaiUi,
            settingsPanelFactory = { context ->
                ZaiSettingsPanel(context.modalityComponentProvider, context.statusLabelDefaultForeground)
            },
            usageMcp = UsageQuotaMcpRegistration("No Z.ai usage response available"),
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
