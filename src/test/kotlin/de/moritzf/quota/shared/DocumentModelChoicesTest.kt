package de.moritzf.quota.shared

import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.azure.AzureUsageWindow
import de.moritzf.quota.azure.azureNativePdfChoices
import de.moritzf.quota.github.proxy.githubDocumentModelIds
import de.moritzf.quota.github.proxy.githubDocumentRoute
import de.moritzf.quota.opencode.proxy.OpenCodeConsoleModel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper

class DocumentModelChoicesTest {
    @Test
    fun pdfFilterAppliesOnlyWhenTheProviderReportedIt() {
        val models = listOf("a", "b")
        assertEquals(
            listOf("a"),
            DocumentModelChoices.pdfOrAll(models, setOf("a"), setOf("a", "b")),
        )
        assertEquals(
            listOf("a", "b"),
            DocumentModelChoices.pdfOrAll(models, setOf("a"), setOf("a")),
        )
        assertEquals(models, DocumentModelChoices.pdfOrAll(models, emptySet(), emptySet()))
    }

    @Test
    fun githubUsesPdfMediaTypeAndFallsBackToEveryModel() {
        val told =
            """{"data":[{"id":"gpt-5.6-sol","capabilities":{"limits":{"vision":{"supported_media_types":["application/pdf"]}}}},{"id":"gpt-4o","capabilities":{"limits":{"vision":{"supported_media_types":["image/png"]}}}}]}"""
        assertEquals(listOf("gpt-5.6-sol"), githubDocumentModelIds(told))
        val silent = """{"data":[{"id":"alpha"},{"id":"beta"}]}"""
        assertEquals(listOf("alpha", "beta"), githubDocumentModelIds(silent))
    }

    @Test
    fun openCodeUsesLivePdfFlag() {
        val pdf = model("claude", pdf = true, known = true)
        val image = model("gpt", pdf = false, known = true)
        assertEquals(listOf("claude"), OpenCodeConsoleModel.documentModelIds(listOf(pdf, image)))
        val unknown = model("any", pdf = false, known = false)
        assertEquals(listOf("any"), OpenCodeConsoleModel.documentModelIds(listOf(unknown)))
        assertEquals(
            listOf("claude", "any"),
            OpenCodeConsoleModel.documentModelIds(listOf(pdf, unknown)),
        )
    }

    @Test
    fun azureListsGeneralPurposeDeploymentsButExcludesOcrAndEmbeddings() {
        val quota =
            AzureQuota(
                windows =
                    listOf(
                        AzureUsageWindow(
                            "mistral-ocr",
                            "",
                            AzureUsageWindow.DEPLOYMENT,
                            resourceName = "res",
                            modelName = "mistral-ocr-4",
                        ),
                        AzureUsageWindow(
                            "gpt",
                            "",
                            AzureUsageWindow.DEPLOYMENT,
                            resourceName = "res",
                            modelName = "gpt-5.5",
                        ),
                        AzureUsageWindow(
                            "search-index",
                            "",
                            AzureUsageWindow.DEPLOYMENT,
                            resourceName = "res",
                            modelName = "text-embedding-3-large",
                        ),
                        AzureUsageWindow(
                            "text-embedding-3-small",
                            "",
                            AzureUsageWindow.DEPLOYMENT,
                            resourceName = "res",
                        ),
                        AzureUsageWindow(
                            "other-gpt",
                            "",
                            AzureUsageWindow.DEPLOYMENT,
                            resourceName = "other",
                            modelName = "gpt-5.5",
                        ),
                    )
            )
        assertEquals(listOf("native:gpt"), azureNativePdfChoices(quota, "res"))
    }

    @Test
    fun helloPdfContainsTheSampleSentence() {
        val path = Files.createTempFile("hello", ".pdf")
        try {
            HelloPdf.write(path)
            Loader.loadPDF(path.toFile()).use { document ->
                val page = document.getPage(0)
                assertTrue(page.mediaBox.width > page.mediaBox.height)
                val fontName =
                    page.resources.fontNames.joinToString { page.resources.getFont(it).name }
                assertTrue(fontName.contains("LiberationSans"), fontName)
                val text = PDFTextStripper().getText(document)
                assertEquals(2, text.split(HelloPdf.TEXT).size)
                HelloPdf.TABLE.flatten().forEach { cell -> assertTrue(text.contains(cell), cell) }
                val images = page.resources.xObjectNames.map { page.resources.getXObject(it) }
                assertTrue(images.any { it is PDImageXObject })
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun visionTestUsesTheSquarePluginIconOnly() {
        val icon = HelloPdf.iconImage(256)
        assertEquals(256, icon.width)
        assertEquals(256, icon.height)
    }

    @Test
    fun nativePdfResponseTextIsReadFromChatAndResponses() {
        assertTrue(
            NativePdfDocument.requestJson(NativePdfRoute.RESPONSES, "m", "a.pdf", "data:", "QQ==")
                .contains("input_file")
        )
        assertEquals(
            NativePdfRoute.ANTHROPIC,
            githubDocumentRoute("claude-sonnet", listOf("/v1/messages", "/chat/completions")),
        )
        assertEquals(NativePdfRoute.RESPONSES, githubDocumentRoute("custom", listOf("/responses")))
        assertEquals(NativePdfRoute.CHAT, githubDocumentRoute("gpt-4.1"))
        assertEquals(
            "Hello",
            NativePdfDocument.extractText("""{"choices":[{"message":{"content":"Hello"}}]}"""),
        )
        assertEquals(
            "Hi",
            NativePdfDocument.extractText(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n"
            ),
        )
    }

    @Test
    fun visionRoutesUseImagePartsInsteadOfFiles() {
        assertTrue(
            NativePdfDocument.visionRequestJson(
                    NativePdfRoute.RESPONSES,
                    "m",
                    "?",
                    "data:image/png;base64,QQ==",
                    "QQ==",
                    "image/png",
                )
                .contains("\"input_image\"")
        )
        val chat =
            NativePdfDocument.visionRequestJson(
                NativePdfRoute.CHAT,
                "m",
                "?",
                "data:image/png;base64,QQ==",
                "QQ==",
                "image/png",
            )
        assertTrue(chat.contains("\"image_url\""))
        assertTrue(chat.contains("\"text\":\"?\""))
        val anthropic =
            NativePdfDocument.visionRequestJson(
                NativePdfRoute.ANTHROPIC,
                "m",
                "?",
                "data:image/png;base64,QQ==",
                "QQ==",
                "image/png",
            )
        assertTrue(anthropic.contains("\"type\":\"image\""))
        assertTrue(anthropic.contains("\"media_type\":\"image/png\""))
        assertTrue(anthropic.contains("\"data\":\"QQ==\""))
    }

    @Test
    fun visionAnswerExtractsChatContent() {
        val dir = java.nio.file.Files.createTempDirectory("vision-answer")
        val png = dir.resolve("pic.png")
        java.nio.file.Files.write(
            png,
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1),
        )
        val answer =
            NativePdfDocument.answer(NativePdfRoute.CHAT, "m", "?", png) { _ ->
                """{"choices":[{"message":{"content":"A dog."}}]}"""
            }
        assertEquals("A dog.", answer)
        val failed =
            kotlin.test.assertFailsWith<IllegalStateException> {
                NativePdfDocument.answer(NativePdfRoute.CHAT, "m", "?", png) { _ -> "" }
            }
        assertTrue(failed.message!!.contains("answer"))
    }

    private fun model(id: String, pdf: Boolean, known: Boolean) =
        OpenCodeConsoleModel(
            model =
                de.moritzf.proxy.subscription.SubscriptionProxyModel(
                    localId = id,
                    upstreamId = id,
                    providerId = "opencode",
                    providerName = "OpenCode",
                    litellmProvider = "opencode",
                    supportedRoutes = emptySet(),
                ),
            nativeRoute = de.moritzf.proxy.subscription.SubscriptionProxyRoute.CHAT_COMPLETIONS,
            baseUri = java.net.URI.create("https://opencode.ai/zen/v1"),
            headers = emptyMap(),
            body = kotlinx.serialization.json.JsonObject(emptyMap()),
            supportsPdf = pdf,
            pdfCapabilityKnown = known,
        )
}
