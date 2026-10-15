package de.moritzf.quota.idea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.mock.MockApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBPasswordField
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.github.GitHubAuthService
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiAuthService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import java.awt.Container
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Suppress("UnstableApiUsage")
class ProviderCredentialLifecycleTest {
    @Test
    fun queuedLogoutKeepsTheAccountSelectedWhenClicked() {
        for (type in listOf(QuotaProviderType.GITHUB, QuotaProviderType.KIMI)) {
            withFixture { fixture ->
                val first = account(type)
                val second = account(type)
                try {
                    saveKey(first, "first-token")
                    saveKey(second, "second-token")
                    onEdt {
                        val panel = panel(type)
                        bind(panel, first)
                        fixture.app.background.clear()
                        descendants(panel)
                            .filterIsInstance<ActionLink>()
                            .single { it.text == "Log Out" }
                            .doClick()
                        bind(panel, second)
                    }
                    fixture.app.background.removeFirst().run()
                    fixture.drainUi()
                    assertNull(loadKey(first), type.name)
                    assertEquals("second-token", loadKey(second), type.name)
                } finally {
                    for (account in listOf(first, second)) {
                        GitHubAuthService.forgetAccount(account.id)
                        KimiAuthService.forgetAccount(account.id)
                    }
                }
            }
        }
    }

    @Test
    fun mistralSaveKeepsTheAccountSelectedWhenClicked() = withFixture { fixture ->
        val first = account(QuotaProviderType.MISTRAL)
        val second = account(QuotaProviderType.MISTRAL)
        for (account in listOf(first, second)) {
            MistralApiKeyStore.forAccount(account.id).loadBlocking()
            MistralSessionCookieStore.forAccount(account.id).loadBlocking()
        }
        MistralSessionCookieStore.forAccount(first.id)
            .save("ory_session_test=session; csrftoken=csrf")
        val panel = onEdt {
            MistralSettingsPanel({ null }).also {
                bind(it, first)
                apiKeyField(it).text = "draft-key"
                fixture.app.background.clear()
                click(it, "Save")
                bind(it, second)
                apiKeyField(it).text = "second-draft"
            }
        }
        fixture.app.background.removeFirst().run()
        fixture.drainUi()
        assertEquals("draft-key", MistralApiKeyStore.forAccount(first.id).loadBlocking())
        assertNull(MistralApiKeyStore.forAccount(second.id).loadBlocking())
        onEdt { assertEquals("second-draft", String(apiKeyField(panel).password)) }
    }

    @Test
    fun queuedClearsStayOnClickedAccountAndPreserveOtherDrafts() {
        for (type in
            listOf(QuotaProviderType.MISTRAL, QuotaProviderType.MINIMAX, QuotaProviderType.ZAI)) {
            withFixture { fixture ->
                val first = account(type)
                val second = account(type)
                saveKey(first, "first-key")
                saveKey(second, "second-key")
                val panel = onEdt {
                    panel(type).also {
                        bind(it, first)
                        apiKeyField(it).text = "first-draft"
                        fixture.app.background.clear()
                        click(it, "Clear")
                        bind(it, second)
                        apiKeyField(it).text = "second-draft"
                    }
                }
                fixture.app.background.removeFirst().run()
                fixture.drainUi()
                assertNull(loadKey(first), type.name)
                assertEquals("second-key", loadKey(second), type.name)
                onEdt {
                    assertEquals(mapOf(second.id to "second-draft"), panel.testApiKeyDrafts())
                    assertEquals("second-draft", String(apiKeyField(panel).password))
                    bind(panel, first)
                    assertEquals("", String(apiKeyField(panel).password))
                    bind(panel, second)
                    assertEquals("second-draft", String(apiKeyField(panel).password))
                }
            }
        }
    }

    @Test
    fun queuedSavePreservesEditsMadeAfterClickAndResetDiscardsDrafts() {
        for (type in
            listOf(QuotaProviderType.MISTRAL, QuotaProviderType.MINIMAX, QuotaProviderType.ZAI)) {
            withFixture { fixture ->
                val account = account(type)
                saveKey(account, "old-key")
                val panel = onEdt {
                    panel(type).also {
                        bind(it, account)
                        apiKeyField(it).text = "submitted-key"
                        fixture.app.background.clear()
                        click(it, "Save")
                        apiKeyField(it).text = "newer-draft"
                    }
                }
                fixture.app.background.removeFirst().run()
                fixture.drainUi()
                assertEquals("submitted-key", loadKey(account), type.name)
                onEdt {
                    assertEquals(mapOf(account.id to "newer-draft"), panel.testApiKeyDrafts())
                    assertEquals("newer-draft", String(apiKeyField(panel).password))
                    panel.discardCredentialDrafts()
                    panel.updateFields()
                    assertNull(panel.testConnection().apiKeyOverride)
                    assertTrue(panel.testApiKeyDrafts().orEmpty().isEmpty())
                }
            }
        }
    }

    @Test
    fun lateCredentialLoadFromPreviousAccountDoesNotReplaceCurrentDraft() = withFixture { fixture ->
        val first = account(QuotaProviderType.OLLAMA)
        val second = account(QuotaProviderType.OLLAMA)
        OllamaApiKeyStore.forAccount(second.id).loadBlocking()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.passwordSafe.beforeRead = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val panel = onEdt { OllamaSettingsPanel({ null }).also { bind(it, first) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            onEdt {
                apiKeyField(panel).text = "first-draft"
                bind(panel, second)
                apiKeyField(panel).text = "second-draft"
            }
            release.countDown()
            assertTrue(fixture.app.uiReady.tryAcquire(5, TimeUnit.SECONDS))
            fixture.drainUi()
            onEdt {
                assertEquals("second-draft", String(apiKeyField(panel).password))
                bind(panel, first)
                assertEquals("first-draft", String(apiKeyField(panel).password))
                apiKeyField(panel).text = ""
                panel.updateFields()
                assertEquals("", panel.testConnection().apiKeyOverride)
            }
        } finally {
            release.countDown()
            fixture.passwordSafe.beforeRead = {}
            OllamaApiKeyStore.forAccount(first.id).loadBlocking()
        }
    }

    @Test
    fun lateOllamaCredentialLoadPreservesEditedKeyAndModel() = withFixture { fixture ->
        val account =
            account(QuotaProviderType.OLLAMA).apply {
                setExtra(ProviderAccount.EXTRA_VISION_MODEL, "saved-vision")
            }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.passwordSafe.beforeRead = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val panel = onEdt { OllamaSettingsPanel({ null }).also { bind(it, account) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            onEdt {
                apiKeyField(panel).text = "edited-before-load"
                descendants(panel).filterIsInstance<JComboBox<*>>().single().selectedItem = "-"
                panel.monthlyResetField.text = "2026-10-01T00:00:00Z"
            }
            release.countDown()
            assertTrue(fixture.app.uiReady.tryAcquire(5, TimeUnit.SECONDS))
            fixture.drainUi()
            onEdt {
                assertEquals("edited-before-load", String(apiKeyField(panel).password))
                assertEquals(
                    "-",
                    descendants(panel).filterIsInstance<JComboBox<*>>().single().selectedItem,
                )
                assertEquals("2026-10-01T00:00:00Z", panel.monthlyResetField.text)
            }
        } finally {
            release.countDown()
            fixture.passwordSafe.beforeRead = {}
            OllamaApiKeyStore.forAccount(account.id).loadBlocking()
        }
    }

    private fun account(type: QuotaProviderType) =
        ProviderAccount(UUID.randomUUID().toString(), type.id, "Test")

    private fun panel(type: QuotaProviderType): ProviderSettingsPanel =
        when (type) {
            QuotaProviderType.GITHUB -> GitHubSettingsPanel({ null })
            QuotaProviderType.KIMI -> KimiSettingsPanel({ null })
            QuotaProviderType.MISTRAL -> MistralSettingsPanel({ null })
            QuotaProviderType.MINIMAX -> MiniMaxSettingsPanel({ null })
            QuotaProviderType.ZAI -> ZaiSettingsPanel({ null })
            else -> error("Unexpected provider: $type")
        }

    private fun saveKey(account: ProviderAccount, value: String) {
        when (account.providerType()) {
            QuotaProviderType.GITHUB ->
                GitHubCredentialsStore.forAccount(account.id)
                    .save(de.moritzf.quota.github.GitHubCredentials(accessToken = value))
            QuotaProviderType.KIMI ->
                KimiCredentialsStore.forAccount(account.id)
                    .save(de.moritzf.quota.kimi.KimiCredentials(accessToken = value))
            QuotaProviderType.MISTRAL -> {
                MistralApiKeyStore.forAccount(account.id).save(value)
                MistralSessionCookieStore.forAccount(account.id)
                    .save("ory_session_test=session; csrftoken=csrf")
            }
            QuotaProviderType.MINIMAX -> MiniMaxApiKeyStore.forAccount(account.id).save(value)
            QuotaProviderType.ZAI -> ZaiApiKeyStore.forAccount(account.id).save(value)
            else -> error("Unexpected provider: ${account.typeId}")
        }
    }

    private fun loadKey(account: ProviderAccount): String? =
        when (account.providerType()) {
            QuotaProviderType.GITHUB ->
                GitHubCredentialsStore.forAccount(account.id).loadBlocking()?.accessToken
            QuotaProviderType.KIMI ->
                KimiCredentialsStore.forAccount(account.id).loadBlocking()?.accessToken
            QuotaProviderType.MISTRAL -> MistralApiKeyStore.forAccount(account.id).loadBlocking()
            QuotaProviderType.MINIMAX -> MiniMaxApiKeyStore.forAccount(account.id).loadBlocking()
            QuotaProviderType.ZAI -> ZaiApiKeyStore.forAccount(account.id).loadBlocking()
            else -> error("Unexpected provider: ${account.typeId}")
        }

    private fun click(panel: ProviderSettingsPanel, label: String) {
        descendants(panel).filterIsInstance<JButton>().single { it.text == label }.doClick(0)
    }

    private fun bind(panel: ProviderSettingsPanel, account: ProviderAccount) {
        panel.boundAccountId = account.id
        panel.boundAccount = account
        panel.updateFields()
    }

    private fun descendants(component: Container): List<java.awt.Component> =
        component.components.flatMap {
            listOf(it) + if (it is Container) descendants(it) else emptyList()
        }

    private fun apiKeyField(panel: ProviderSettingsPanel) =
        descendants(panel).filterIsInstance<JBPasswordField>().let { fields ->
            if (fields.size == 1) fields.single()
            else fields.single { it.toolTipText?.contains("API key") == true }
        }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = onEdt { Fixture() }
        try {
            block(fixture)
        } finally {
            onEdt { fixture.close() }
        }
    }

    private class QueuedApplication(disposable: Disposable) : MockApplication(disposable) {
        val background = ArrayDeque<Runnable>()
        val ui = ConcurrentLinkedQueue<Runnable>()
        val uiReady = Semaphore(0)

        override fun executeOnPooledThread(action: Runnable): Future<*> {
            background.addLast(action)
            return CompletableFuture.completedFuture(Unit)
        }

        override fun invokeLater(runnable: Runnable) {
            ui.add(runnable)
            uiReady.release()
        }

        override fun invokeLater(runnable: Runnable, state: ModalityState) = invokeLater(runnable)
    }

    private class Fixture : AutoCloseable {
        val disposable = Disposer.newDisposable()
        val app = QueuedApplication(disposable)
        val passwordSafe = MemoryPasswordSafe()
        val job = SupervisorJob()
        val usage: QuotaUsageService

        init {
            ApplicationManager.setApplication(app, disposable)
            val scope = CoroutineScope(job + Dispatchers.Default)
            app.registerService(CoroutineScope::class.java, scope)
            app.registerService(
                com.intellij.openapi.client.ClientSessionsManager::class.java,
                com.intellij.openapi.client.ClientSessionsManager<
                    com.intellij.openapi.client.ClientAppSession
                >(
                    scope
                ),
            )
            app.registerService(
                ExperimentalUI::class.java,
                object : ExperimentalUI() {
                    override fun saveCurrentValueAndReapplyDefaultLaf() = Unit

                    override fun earlyInitValue() = true
                },
            )
            app.registerService(PasswordSafe::class.java, passwordSafe)
            val settings = QuotaSettingsState()
            app.registerService(QuotaSettingsState::class.java, settings)
            usage =
                QuotaUsageService(
                    providers = emptyList(),
                    settingsProvider = { settings },
                    updatePublisher = {},
                    scheduleOnInit = false,
                )
            app.registerService(QuotaUsageService::class.java, usage)
        }

        fun drainUi() {
            SwingUtilities.invokeAndWait { while (true) (app.ui.poll() ?: break).run() }
        }

        override fun close() {
            usage.dispose()
            job.cancel()
            Disposer.dispose(disposable)
        }
    }

    private class MemoryPasswordSafe : PasswordSafe() {
        private val values = ConcurrentHashMap<CredentialAttributes, Credentials>()
        @Volatile var beforeRead: () -> Unit = {}
        override var isRememberPasswordByDefault = false
        override val isMemoryOnly = true

        override fun get(attributes: CredentialAttributes): Credentials? {
            beforeRead()
            return values[attributes]
        }

        override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
            if (credentials == null) values.remove(attributes) else values[attributes] = credentials
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
