package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import java.util.concurrent.Executor
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibleSettingsPanelTest {
    @Test
    fun liteLlmResponseUsesSelectedAccountAndSanitizedDraftBudgetWithoutLeakingOldResults() =
        onEdt {
            val requests = mutableListOf<Pair<String, String?>>()
            var status = 200
            val server =
                com.sun.net.httpserver.HttpServer.create(
                    java.net.InetSocketAddress("127.0.0.1", 0),
                    0,
                )
            server.createContext("/") { exchange ->
                requests +=
                    exchange.requestURI.path to exchange.requestHeaders.getFirst("Authorization")
                val bytes =
                    (if (status == 200)
                            """{"key":"secret-key","request_id":"budget-123","info":{"spend":12.5,"max_budget":50,"models":["company-chat"],"token":"secret-hash","metadata":{"api_key":"secret"}}}"""
                        else
                            """{"error":{"code":"key_info_forbidden","message":"No budget access"}}""")
                        .toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val accounts =
                listOf("a", "b").map { id ->
                    ProviderAccount(id, QuotaProviderType.LITELLM.id, id).apply {
                        setExtra(
                            ProviderAccount.EXTRA_COMPATIBLE_BASE_URL,
                            "http://127.0.0.1:${server.address.port}/v1",
                        )
                    }
                }
            val settings = QuotaSettingsState().apply { this.accounts = accounts.toMutableList() }
            val app =
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    as com.intellij.mock.MockApplication
            app.registerService(QuotaSettingsState::class.java, settings)
            val usage =
                de.moritzf.quota.idea.common.QuotaUsageService(
                    providers =
                        accounts.mapIndexed { index, account ->
                            object : de.moritzf.quota.idea.common.QuotaProvider {
                                override val type = QuotaProviderType.LITELLM
                                override val accountId = account.id

                                override fun refresh() = Unit

                                override fun clearData(error: String?) = Unit

                                override fun getLastRawJson() =
                                    """{"diagnostic":"gateway-details","info":{"spend":${index + 1},"future_credential":"secret-for-LLM"}}"""
                            }
                        },
                    settingsProvider = { settings },
                    updatePublisher = {},
                    scheduleOnInit = false,
                )
            app.registerService(de.moritzf.quota.idea.common.QuotaUsageService::class.java, usage)
            val panel =
                CompatibleSettingsPanel(
                    { "saved-key" },
                    { _, _ -> error("Must not save drafts") },
                    Executor { it.run() },
                    { it() },
                )
            fun descendants(root: java.awt.Container): Sequence<java.awt.Component> =
                root.components.asSequence().flatMap {
                    sequenceOf(it) +
                        if (it is java.awt.Container) descendants(it) else emptySequence()
                }
            val viewer =
                descendants(panel).filterIsInstance<javax.swing.JTextArea>().single {
                    !it.isEditable
                }
            val budget =
                descendants(panel).filterIsInstance<javax.swing.JButton>().single {
                    it.text == "Check key budget"
                }
            fun select(account: ProviderAccount) {
                panel.boundAccountId = account.id
                panel.boundAccount = account
                panel.updateFields()
                panel.updateResponseArea()
            }
            try {
                select(accounts.first())
                assertTrue(viewer.text.contains("\"spend\": 1"))
                assertTrue(viewer.text.contains("gateway-details"))
                val mcp =
                    checkNotNull(
                        de.moritzf.quota.idea.mcp.UsageQuotaMcpRegistry.get(
                            QuotaProviderType.LITELLM
                        )
                    )
                assertEquals(
                    """{"info":{"spend":2}}""",
                    mcp.jsonForAccount(usage, QuotaProviderType.LITELLM, "b"),
                )
                assertEquals(
                    """{"info":{"spend":1}}""",
                    mcp.jsonForAccount(usage, QuotaProviderType.LITELLM, "missing"),
                )
                panel.apiKeyField.text = "draft-key"
                panel.updateResponseArea()
                assertFalse(viewer.text.contains("\"spend\""))
                budget.doClick(0)
                assertEquals(
                    listOf<Pair<String, String?>>("/key/info" to "Bearer draft-key"),
                    requests,
                )
                assertTrue(viewer.text.contains("\"spend\": 12.5"))
                assertFalse(viewer.text.contains("secret"))
                assertTrue(viewer.text.contains("\"token\": \"[REDACTED]\""))
                assertTrue(viewer.text.contains("budget-123"))
                assertTrue(viewer.text.contains("company-chat"))
                panel.updateResponseArea()
                assertTrue(
                    viewer.text.contains("\"spend\": 12.5"),
                    "Saved quota must not replace draft results",
                )
                panel.baseUrlField.text = "http://127.0.0.1:${server.address.port}/draft/v1"
                assertFalse(viewer.text.contains("\"spend\""))
                select(accounts.last())
                assertTrue(viewer.text.contains("\"spend\": 2"))
                assertFalse(viewer.text.contains("12.5"))
                status = 403
                budget.doClick(0)
                assertTrue(viewer.text.contains("HTTP 403"))
                assertTrue(viewer.text.contains("key_info_forbidden"))
                assertFalse(viewer.text.contains("secret"))
                assertFalse(viewer.text.contains("\"spend\""))
            } finally {
                panel.dispose()
                usage.dispose()
                server.stop(0)
            }
        }

    @Test
    fun mediaSelectionsAreOptInStayInDraftsAndTestsCaptureUnsavedConnectionValues() = onEdt {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String?, String>>()
        val server =
            com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests +=
                Triple(
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.bufferedReader().readText(),
                )
            val bytes = """{"choices":[{"message":{"content":"# Hello"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val panel =
            CompatibleSettingsPanel(
                { "saved-key" },
                { _, _ -> error("Must not save during tests") },
                Executor { it.run() },
                { it() },
            )
        val image = java.nio.file.Files.createTempFile("compatible-image-", ".png")
        val pdf = java.nio.file.Files.createTempFile("compatible-document-", ".pdf")
        val output = java.nio.file.Files.createTempFile("compatible-document-", ".md")
        try {
            val row = account("a")
            panel.boundAccount = row
            panel.updateFields()
            assertEquals("-", panel.visionModel.selected())
            assertEquals("-", panel.documentModel.selected())
            assertFalse(panel.visionTest.isEnabled)
            panel.baseUrlField.text = "http://127.0.0.1:${server.address.port}/draft/v1"
            panel.apiKeyField.text = "draft-key"
            panel.modelIdsField.text = "image-model\npdf-model"
            panel.visionModel.combo.selectedItem = "image-model"
            panel.documentModel.combo.selectedItem = "pdf-model"
            assertTrue(panel.differsFrom(row))
            val vision = panel.prepareVisionTest("image-model")
            val document = panel.prepareDocumentTest("pdf-model")
            panel.writeTo(row)
            panel.boundAccount = account("b")
            panel.updateFields()
            assertEquals("-", panel.visionModel.selected())
            panel.boundAccount = row
            panel.updateFields()
            assertEquals("image-model", panel.visionModel.selected())
            assertEquals("pdf-model", panel.documentModel.selected())
            panel.apiKeyField.text = "later-key"
            panel.baseUrlField.text = "https://unused.example/v1"
            java.nio.file.Files.write(
                image,
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10),
            )
            de.moritzf.quota.shared.HelloPdf.write(pdf)
            assertEquals("# Hello", vision(image, "Read the image"))
            document(pdf, output, de.moritzf.quota.shared.DocumentConversionProgress.NONE)
            assertEquals("# Hello", java.nio.file.Files.readString(output))
            assertEquals(2, requests.size)
            assertTrue(
                requests.all {
                    it.first == "/draft/v1/chat/completions" && it.second == "Bearer draft-key"
                }
            )
            assertTrue(requests.first().third.contains("data:image/png;base64,"))
            assertTrue(requests.last().third.contains("data:application/pdf;base64,"))
            assertTrue(requests.last().third.contains("\"model\":\"pdf-model\""))
        } finally {
            panel.dispose()
            server.stop(0)
            listOf(image, pdf, output).forEach(java.nio.file.Files::deleteIfExists)
        }
    }

    @Test
    fun narrowAccountPaneKeepsConnectionAndModelControlsVisible() = onEdt {
        val panel =
            CompatibleSettingsPanel({ "example-key" }, { _, _ -> }, Executor { it.run() }, { it() })
        try {
            panel.boundAccount =
                account("a").apply {
                    setExtra(
                        ProviderAccount.EXTRA_COMPATIBLE_MODELS,
                        "vendor/chat-model\ncodestral-latest",
                    )
                }
            panel.updateFields()
            panel.status.text =
                "<html>Discovery unavailable. Enter model IDs below, then select a model to test.</html>"
            panel.setSize(420, 650)
            fun layout(component: java.awt.Container) {
                component.doLayout()
                component.components.filterIsInstance<java.awt.Container>().forEach(::layout)
            }
            repeat(2) { layout(panel) }
            val scroll = panel.components.filterIsInstance<javax.swing.JScrollPane>().single()
            val content = scroll.viewport.view as javax.swing.JComponent
            fun reveal(control: javax.swing.JComponent) {
                content.scrollRectToVisible(
                    SwingUtilities.convertRectangle(
                            control,
                            java.awt.Rectangle(0, 0, control.width, control.height),
                            content,
                        )
                        .apply {
                            x = 0
                            width = scroll.viewport.width
                        }
                )
            }
            for (control in
                listOf(
                    panel.baseUrlField,
                    panel.apiKeyField,
                    panel.modelCombo,
                    panel.testButton,
                    panel.modelIdsField,
                    panel.visionModel.combo,
                    panel.documentModel.combo,
                    panel.visionTest,
                    panel.documentTest,
                )) {
                reveal(control)
                val origin = SwingUtilities.convertPoint(control, 0, 0, scroll.viewport)
                assertTrue(control.width > 0 && control.height > 0)
                assertTrue(
                    origin.x >= 0 && origin.x + control.width <= scroll.viewport.width,
                    "${control.javaClass.simpleName} clipped horizontally: $origin ${control.size}",
                )
                assertTrue(
                    origin.y >= 0 && origin.y + control.height <= scroll.viewport.height,
                    "${control.javaClass.simpleName} clipped vertically: $origin ${control.size}",
                )
            }
            for ((combo, button) in
                listOf(
                    panel.modelCombo to panel.testButton,
                    panel.visionModel.combo to panel.visionTest,
                    panel.documentModel.combo to panel.documentTest,
                )) {
                val comboBounds =
                    SwingUtilities.convertRectangle(combo.parent, combo.bounds, content)
                val buttonBounds =
                    SwingUtilities.convertRectangle(button.parent, button.bounds, content)
                assertTrue(
                    buttonBounds.centerX > comboBounds.maxX,
                    "${button.text} must sit beside its model picker",
                )
                assertTrue(
                    kotlin.math.abs(buttonBounds.centerY - comboBounds.centerY) <= 1,
                    "${button.text} must share its model picker's row",
                )
                assertTrue(
                    button.width >= button.preferredSize.width,
                    "${button.text} label must remain fully visible",
                )
            }
            scroll.viewport.viewPosition = java.awt.Point(0, 0)
            val image =
                java.awt.image.BufferedImage(
                    panel.width,
                    panel.height,
                    java.awt.image.BufferedImage.TYPE_INT_RGB,
                )
            val graphics = image.createGraphics()
            graphics.color = panel.background
            graphics.fillRect(0, 0, panel.width, panel.height)
            panel.printAll(graphics)
            graphics.dispose()
            val output = java.nio.file.Path.of("build/reports/ui/compatible-settings.png")
            java.nio.file.Files.createDirectories(output.parent)
            javax.imageio.ImageIO.write(image, "png", output.toFile())
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun accountSwitchKeepsUnsavedFieldsAndKeysUntilApplyAndCancelDiscardsThem() = onEdt {
        val workers = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        val saved = mutableMapOf("a" to "saved-a", "b" to "saved-b")
        val panel =
            CompatibleSettingsPanel(
                { saved[it] },
                { id, key -> if (key == null) saved.remove(id) else saved[id] = key },
                Executor { workers += it },
                { ui += it },
            )
        fun load(account: ProviderAccount) {
            panel.boundAccount = account
            panel.updateFields()
            while (workers.isNotEmpty()) workers.removeFirst().run()
            while (ui.isNotEmpty()) ui.removeFirst().invoke()
        }
        try {
            val a = account("a")
            val b = account("b")
            load(a)
            panel.apiKeyField.text = "draft-a"
            panel.baseUrlField.text = "https://changed.example/v1"
            panel.modelIdsField.text = "custom-model"
            panel.writeTo(a)
            load(b)
            assertEquals("saved-b", String(panel.apiKeyField.password))
            panel.apiKeyField.text = "draft-b"
            panel.writeTo(b)
            load(a)
            assertEquals("draft-a", String(panel.apiKeyField.password))
            assertEquals("https://changed.example/v1", panel.baseUrlField.text)
            assertEquals("custom-model", panel.modelCombo.selectedItem)
            assertTrue(
                panel.testButton.isEnabled,
                "Manual model selection should immediately enable testing",
            )
            assertTrue(panel.credentialsModified(setOf("a", "b")))
            assertEquals(mapOf("a" to "saved-a", "b" to "saved-b"), saved)
            panel.saveCredentials(setOf("a"))
            assertEquals(mapOf("a" to "draft-a", "b" to "saved-b"), saved)
            assertFalse(panel.credentialsModified(setOf("a")))
            panel.apiKeyField.text = "cancelled"
            panel.discardDrafts()
            load(a)
            assertEquals("draft-a", String(panel.apiKeyField.password))
            assertFalse(panel.credentialsModified(setOf("a")))
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun lateKeyLoadCannotOverwriteAnotherAccountOrResetAndInvalidUrlsDisableActions() = onEdt {
        val workers = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        val panel =
            CompatibleSettingsPanel(
                { "key-$it" },
                { _, _ -> error("Unexpected save") },
                Executor { workers += it },
                { ui += it },
            )
        try {
            panel.boundAccount = account("a")
            panel.updateFields()
            workers.removeFirst().run()
            panel.boundAccount = account("b")
            panel.updateFields()
            ui.removeFirst().invoke()
            assertEquals("", String(panel.apiKeyField.password))
            assertFalse(panel.apiKeyField.isEnabled)
            workers.removeFirst().run()
            ui.removeFirst().invoke()
            assertEquals("key-b", String(panel.apiKeyField.password))
            panel.modelIdsField.text = "chat-model"
            assertTrue(panel.testButton.isEnabled)
            panel.baseUrlField.text = "https://example.com/v1/chat/completions"
            assertFalse(panel.testButton.isEnabled)
            assertFalse(panel.refreshButton.isEnabled)
            assertTrue(panel.status.text.contains("API root"))
            panel.discardDrafts()
            panel.updateFields()
            workers.removeFirst().run()
            panel.discardDrafts()
            ui.removeFirst().invoke()
            assertEquals("", String(panel.apiKeyField.password))
        } finally {
            panel.dispose()
        }
    }

    private fun account(id: String) =
        ProviderAccount(id, QuotaProviderType.OPENAI_COMPATIBLE.id, id).apply {
            setExtra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL, "https://example.com/v1")
        }

    private fun onEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val disposable = com.intellij.openapi.util.Disposer.newDisposable()
            val app = com.intellij.mock.MockApplication.setUp(disposable)
            val job = kotlinx.coroutines.SupervisorJob()
            app.registerService(
                kotlinx.coroutines.CoroutineScope::class.java,
                kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default),
            )
            app.registerService(
                com.intellij.ui.ExperimentalUI::class.java,
                object : com.intellij.ui.ExperimentalUI() {
                    override fun saveCurrentValueAndReapplyDefaultLaf() = Unit

                    override fun earlyInitValue() = true
                },
            )
            app.registerService(
                com.intellij.openapi.actionSystem.ActionManager::class.java,
                FormActionManager(),
            )
            try {
                block()
            } finally {
                job.cancel()
                com.intellij.openapi.util.Disposer.dispose(disposable)
            }
        }
    }

    /** Collapsible groups look up Expand/Collapse shortcuts, even in a headless form test. */
    private class FormActionManager : com.intellij.openapi.actionSystem.ActionManager() {
        private val action =
            object : com.intellij.openapi.actionSystem.AnAction() {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) =
                    Unit
            }

        override fun getAction(id: String) = action

        override fun getActionOrStub(id: String) = action

        override fun getId(action: com.intellij.openapi.actionSystem.AnAction): String? = null

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getActionIds(prefix: String) = emptyArray<String>()

        override fun getActionIdList(prefix: String) = emptyList<String>()

        override fun isGroup(id: String) = false

        override fun getKeyboardShortcut(
            id: String
        ): com.intellij.openapi.actionSystem.KeyboardShortcut? = null

        override fun addTimerListener(listener: com.intellij.openapi.actionSystem.TimerListener) =
            Unit

        override fun removeTimerListener(
            listener: com.intellij.openapi.actionSystem.TimerListener
        ) = Unit

        override fun registerAction(
            id: String,
            action: com.intellij.openapi.actionSystem.AnAction,
        ) = Unit

        override fun registerAction(
            id: String,
            action: com.intellij.openapi.actionSystem.AnAction,
            pluginId: com.intellij.openapi.extensions.PluginId?,
        ) = Unit

        override fun unregisterAction(id: String) = Unit

        override fun replaceAction(id: String, action: com.intellij.openapi.actionSystem.AnAction) =
            Unit

        override fun createActionPopupMenu(
            place: String,
            group: com.intellij.openapi.actionSystem.ActionGroup,
        ): com.intellij.openapi.actionSystem.ActionPopupMenu = error("Not used")

        override fun createActionToolbar(
            place: String,
            group: com.intellij.openapi.actionSystem.ActionGroup,
            horizontal: Boolean,
        ): com.intellij.openapi.actionSystem.ActionToolbar = error("Not used")

        override fun tryToExecute(
            action: com.intellij.openapi.actionSystem.AnAction,
            inputEvent: java.awt.event.InputEvent?,
            contextComponent: java.awt.Component?,
            place: String?,
            now: Boolean,
        ): com.intellij.openapi.util.ActionCallback = error("Not used")
    }
}
