package de.moritzf.quota.idea.settings

import com.intellij.mock.MockApplication
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.components.JBPasswordField
import de.moritzf.quota.idea.common.AzureQuotaProvider
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.mcp.VisionProvider
import java.awt.Container
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ProviderTestConnectionTest {
    @Test
    fun completionDraftSelectionSurvivesModelRefreshAndWorksWhileProxyIsOff() = onEdt {
        val app =
            com.intellij.openapi.application.ApplicationManager.getApplication() as MockApplication
        val settings = QuotaSettingsState.getInstance()
        val keyStore = de.moritzf.quota.idea.openai.OpenAiProxyApiKeyStore()
        val proxy =
            de.moritzf.quota.idea.openai.OpenAiProxyService(
                apiKeyStore = keyStore,
                executor = java.util.concurrent.Executor { error("Must not start proxy") },
            )
        app.registerService(de.moritzf.quota.idea.openai.OpenAiProxyService::class.java, proxy)
        val panel =
            SubscriptionProxySettingsPanel(
                testSettingsProvider = { ProxyTestSettings(settings.state) }
            )
        val models =
            listOf(
                de.moritzf.proxy.subscription.SubscriptionProxyModel(
                    "draft-model",
                    "draft-model",
                    "openai-compatible",
                    "Draft",
                    "openai",
                    setOf(de.moritzf.proxy.subscription.SubscriptionProxyRoute.CHAT_COMPLETIONS),
                )
            )
        val combo = descendants(panel).filterIsInstance<javax.swing.JComboBox<*>>().single()
        panel.refreshCompletionsModelCombo(models)
        combo.selectedItem = "draft-model"
        assertTrue(
            descendants(panel)
                .filterIsInstance<javax.swing.JButton>()
                .single { it.text == "Test AI Completion" }
                .isEnabled
        )
        assertFalse(panel.proxyEnabledCheckBox.isSelected)
        assertEquals("draft-model", panel.captureTestSettings().settings.proxyCompletionsModelId)
        combo.selectedItem = "-"
        panel.refreshCompletionsModelCombo(models)
        assertEquals("-", combo.selectedItem)
        assertFalse(panel.captureTestSettings().settings.proxyCompletionsEnabled)
        proxy.dispose()
    }

    private fun descendants(component: Container): List<java.awt.Component> =
        component.components.flatMap {
            listOf(it) + if (it is Container) descendants(it) else emptyList()
        }

    @Test
    fun providerMediaTestsCaptureUnsavedKeysAndNeverFallBackWhenDraftKeyIsCleared() = onEdt {
        val panels =
            listOf(
                MistralSettingsPanel({ null }) to VisionProvider.MISTRAL,
                ZaiSettingsPanel({ null }) to VisionProvider.ZAI,
                OllamaSettingsPanel({ null }) to VisionProvider.OLLAMA,
            )
        panels.forEach { (panel, provider) ->
            val account =
                ProviderAccount("unsaved-${provider.name}", provider.providerType.id, "Draft")
            panel.boundAccount = account
            panel.boundAccountId = account.id
            val keyField = fields(panel).single { it.toolTipText?.contains("API key") == true }
            keyField.text = "unsaved-key"
            val captured = panel.testConnection()
            keyField.text = ""
            val cleared = panel.prepareVisionTest(provider, "selected-before-apply")
            keyField.text = "a-different-key"
            panel.boundAccount = ProviderAccount("sibling", provider.providerType.id, "Sibling")
            panel.boundAccountId = "sibling"
            assertEquals("unsaved-key", captured.apiKey { error("Must not read saved keys") })
            assertEquals(account.id, captured.account.id)
            val failure =
                assertFailsWith<IllegalStateException> {
                    cleared(Path.of("unused.png"), "Describe this image")
                }
            assertTrue(failure.message.orEmpty().contains("API key missing"), failure.message)
        }
    }

    @Test
    fun azureTestsKeepUnsavedTargetAndSubscriptionSnapshot() = onEdt {
        val panel = AzureSettingsPanel()
        val account = ProviderAccount("azure", QuotaProviderType.AZURE.id, "Azure")
        panel.boundAccount = account
        panel.boundAccountId = account.id
        panel.subscriptionField.text = "11111111-1111-1111-1111-111111111111"
        panel.resourceField.text = "draft-resource"
        panel.deploymentsField.text = "draft-model"
        val captured = panel.testConnection()
        panel.subscriptionField.text = "another-subscription"
        panel.resourceField.text = "another-resource"
        val config = AzureQuotaProvider.configFor(captured.account)
        assertEquals("11111111-1111-1111-1111-111111111111", config.subscriptionId)
        assertEquals("draft-resource", config.resourceName)
        assertEquals(listOf("draft-model"), config.deploymentNames)
        assertTrue(account.extras.isEmpty())
    }

    private fun fields(component: Container): List<JBPasswordField> =
        component.components.flatMap {
            when (it) {
                is JBPasswordField -> listOf(it)
                is Container -> fields(it)
                else -> emptyList()
            }
        }

    private fun onEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val disposable = Disposer.newDisposable()
            val app = MockApplication.setUp(disposable)
            val job = SupervisorJob()
            app.registerService(
                com.intellij.openapi.client.ClientSessionsManager::class.java,
                com.intellij.openapi.client.ClientSessionsManager<
                    com.intellij.openapi.client.ClientAppSession
                >(
                    CoroutineScope(job + Dispatchers.Default)
                ),
            )
            app.registerService(
                CoroutineScope::class.java,
                CoroutineScope(job + Dispatchers.Default),
            )
            app.registerService(
                ExperimentalUI::class.java,
                object : ExperimentalUI() {
                    override fun saveCurrentValueAndReapplyDefaultLaf() = Unit

                    override fun earlyInitValue() = true
                },
            )
            val settings = QuotaSettingsState()
            app.registerService(QuotaSettingsState::class.java, settings)
            val usage =
                QuotaUsageService(
                    providers = emptyList(),
                    settingsProvider = { settings },
                    updatePublisher = {},
                    scheduleOnInit = false,
                )
            app.registerService(QuotaUsageService::class.java, usage)
            try {
                block()
            } finally {
                usage.dispose()
                job.cancel()
                Disposer.dispose(disposable)
            }
        }
    }
}
