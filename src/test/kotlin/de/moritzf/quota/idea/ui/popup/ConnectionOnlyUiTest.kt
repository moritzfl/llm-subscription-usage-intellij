package de.moritzf.quota.idea.ui.popup

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.mock.MockApplication
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import de.moritzf.quota.idea.common.ProviderSnapshot
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageSnapshot
import de.moritzf.quota.idea.compatible.CompatibleApiKeyStore
import de.moritzf.quota.idea.openai.OpenAiProxyStatus
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaDisplayMode
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorComponent
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorData
import de.moritzf.quota.litellm.LiteLlmQuota
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionOnlyUiTest {
    @Test
    fun everyIndicatorStyleUsesClickableNeutralIconWithoutQuota() = onEdt { settings ->
        val account = addConnection(settings)
        var clicks = 0
        val indicator =
            QuotaIndicatorComponent(4) { _, data ->
                assertEquals(account.id, data.accountId)
                clicks++
            }
        val data = QuotaIndicatorData(QuotaProviderType.OPENAI_COMPATIBLE, null, null, account.id)
        QuotaDisplayMode.entries.forEach { mode ->
            indicator.updateUsage(data, mode)
            val icon = assertIs<JBLabel>(indicator.components.single())
            assertSame(QuotaIcons.STATUS, icon.icon)
            assertEquals("API connection configured · Quota tracking unsupported", icon.toolTipText)
            assertEquals(icon.toolTipText, indicator.getToolTipText(null))
            val click = MouseEvent(icon, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false)
            icon.mouseListeners.forEach { it.mouseClicked(click) }
        }
        assertEquals(QuotaDisplayMode.entries.size, clicks)

        CompatibleApiKeyStore.forAccount(account.id).clear()
        indicator.updateUsage(data, QuotaDisplayMode.PERCENTAGE_BAR)
        assertTrue(indicator.toolTipText.contains("needs setup"))
        assertFalse(indicator.toolTipText.contains("not logged in", ignoreCase = true))

        indicator.updateUsage(
            QuotaIndicatorData(QuotaProviderType.LITELLM, LiteLlmQuota(25.0, 100.0), null),
            QuotaDisplayMode.PERCENTAGE_BAR,
        )
        assertFalse(indicator.components.single() is JBLabel)
        assertTrue(indicator.toolTipText.contains("key budget used"))
    }

    @Test
    fun apiOnlyPopupShowsConnectionsAndActualLocalProxyState() = onEdt { settings ->
        addConnection(settings, "Work")
        addConnection(settings, "Personal")
        var proxy = OpenAiProxyStatus(false, false, "http://127.0.0.1:14621", null)
        var settingsOpened = false
        val panel =
            QuotaPopupContentPanel(proxyStatusProvider = { proxy }) { settingsOpened = true }
        panel.update(QuotaUsageSnapshot(emptyMap()))
        val labels = texts(panel)
        assertTrue("API Connections" in labels)
        assertTrue(labels.any { it.contains("(Work)") })
        assertTrue(labels.any { it.contains("(Personal)") })
        assertEquals(2, labels.count { it == "API key configured" })
        assertTrue(labels.any { it.contains("This API does not provide") })
        assertTrue("Off" in labels)
        assertNoQuotaUi(panel)
        visibleComponents(panel).filterIsInstance<ActionLink>().single().doClick()
        assertTrue(settingsOpened)

        proxy = proxy.copy(enabled = true)
        panel.update(QuotaUsageSnapshot(emptyMap()))
        assertTrue("Starting…" in texts(panel))
        proxy = proxy.copy(error = "Address already in use")
        panel.update(QuotaUsageSnapshot(emptyMap()))
        assertTrue("Failed to start" in texts(panel))
        assertTrue("Address already in use" in texts(panel))
        proxy = proxy.copy(running = true, error = null)
        panel.update(QuotaUsageSnapshot(emptyMap()))
        assertTrue("Running" in texts(panel))
        assertTrue(proxy.baseUrl in texts(panel))
        assertNoQuotaUi(panel)
        render(panel, "connection-only-popup")
    }

    @Test
    fun hidingConnectionsAndMissingCredentialsDoNotBecomeLoginOrQuotaErrors() = onEdt { settings ->
        val account = addConnection(settings)
        CompatibleApiKeyStore.forAccount(account.id).clear()
        val panel =
            QuotaPopupContentPanel(
                proxyStatusProvider = {
                    OpenAiProxyStatus(false, false, "http://127.0.0.1:14621", null)
                }
            ) {}
        panel.update(QuotaUsageSnapshot(emptyMap()))
        assertTrue("Connection needs setup" in texts(panel))
        assertNoQuotaUi(panel)

        account.hiddenFromPopup = true
        panel.update(QuotaUsageSnapshot(emptyMap()))
        assertTrue(texts(panel).any { it.contains("All connections are hidden") })
        assertFalse(texts(panel).any { it.contains("api.example.com") })
        assertNoQuotaUi(panel)
    }

    @Test
    fun mixedPopupKeepsLiteLlmBudgetAndUnavailableBudgetDistinctFromUnsupportedQuota() =
        onEdt { settings ->
            addConnection(settings)
            val panel =
                QuotaPopupContentPanel(
                    proxyStatusProvider = {
                        OpenAiProxyStatus(false, false, "http://127.0.0.1:14621", null)
                    }
                ) {}
            panel.update(QuotaUsageSnapshot(emptyMap()))
            val liteLlm = addConnection(settings, type = QuotaProviderType.LITELLM)
            panel.update(
                QuotaUsageSnapshot(
                    mapOf(
                        QuotaProviderType.LITELLM to
                            ProviderSnapshot(LiteLlmQuota(25.0, 100.0), null)
                    )
                )
            )
            assertFalse(panel.isConnectionOnly)
            assertTrue("Subscription Usage" in texts(panel))
            assertTrue(texts(panel).any { it.contains("key budget used") })
            assertTrue("Updated:" in texts(panel))
            assertTrue(visibleComponents(panel).any { it is JProgressBar })
            panel.update(
                QuotaUsageSnapshot(
                    mapOf(
                        QuotaProviderType.LITELLM to
                            ProviderSnapshot(null, "Key budget unavailable")
                    )
                )
            )
            assertTrue("Key budget unavailable" in texts(panel))
            assertFalse(texts(panel).any { it.contains("This API does not provide") })

            settings.removeAccount(liteLlm.id)
            panel.update(QuotaUsageSnapshot(emptyMap()))
            assertTrue(panel.isConnectionOnly)
            assertNoQuotaUi(panel)
        }

    private fun assertNoQuotaUi(panel: JComponent) {
        assertFalse(visibleComponents(panel).any { it is JProgressBar })
        assertFalse(texts(panel).any { it.contains("Not logged in") || it == "Updated:" })
    }

    private fun addConnection(
        settings: QuotaSettingsState,
        name: String = "Gateway",
        type: QuotaProviderType = QuotaProviderType.OPENAI_COMPATIBLE,
    ): ProviderAccount =
        settings.addAccount(type).apply {
            id = ProviderAccount.newId()
            this.name = name
            setExtra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL, "https://api.example.com/v1")
            CompatibleApiKeyStore.forAccount(id).save("test-key")
        }

    private fun visibleComponents(component: Component): List<Component> =
        if (!component.isVisible) emptyList()
        else
            listOf(component) +
                (component as? Container)?.components.orEmpty().flatMap(::visibleComponents)

    private fun texts(panel: JComponent): List<String> =
        visibleComponents(panel).filterIsInstance<JBLabel>().map { it.text.orEmpty() }

    private fun render(panel: JComponent, name: String) {
        panel.size = panel.preferredSize
        fun layout(component: Container) {
            component.doLayout()
            component.components.filterIsInstance<Container>().forEach(::layout)
        }
        repeat(2) { layout(panel) }
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = javax.swing.UIManager.getColor("Panel.background")
                graphics.fillRect(0, 0, image.width, image.height)
                panel.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }
        val file = File("build/reports/ui/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun onEdt(block: (QuotaSettingsState) -> Unit) {
        SwingUtilities.invokeAndWait {
            val disposable = Disposer.newDisposable()
            val app = MockApplication.setUp(disposable)
            val settings = QuotaSettingsState()
            app.registerService(QuotaSettingsState::class.java, settings)
            app.registerService(PasswordSafe::class.java, MemoryPasswordSafe())
            try {
                block(settings)
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    private class MemoryPasswordSafe : PasswordSafe() {
        private val values = mutableMapOf<CredentialAttributes, Credentials?>()
        override var isRememberPasswordByDefault = false
        override val isMemoryOnly = true

        override fun get(attributes: CredentialAttributes): Credentials? = values[attributes]

        override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
            values[attributes] = credentials
        }

        override fun set(
            attributes: CredentialAttributes,
            credentials: Credentials?,
            memoryOnly: Boolean,
        ) = set(attributes, credentials)

        override fun isPasswordStoredOnlyInMemory(
            attributes: CredentialAttributes,
            credentials: Credentials,
        ) = true
    }
}
