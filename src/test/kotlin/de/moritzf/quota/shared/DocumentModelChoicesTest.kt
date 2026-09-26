package de.moritzf.quota.shared

import de.moritzf.quota.azure.azureNativePdfChoices
import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.azure.AzureUsageWindow
import de.moritzf.quota.github.proxy.githubDocumentModelIds
import de.moritzf.quota.github.proxy.githubDocumentRoute
import de.moritzf.quota.shared.NativePdfRoute
import de.moritzf.quota.opencode.proxy.OpenCodeConsoleModel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.Loader

class DocumentModelChoicesTest {
    @Test
    fun pdfFilterAppliesOnlyWhenTheProviderReportedIt() {
        val models = listOf("a", "b")
        assertEquals(listOf("a"), DocumentModelChoices.pdfOrAll(models, setOf("a"), setOf("a", "b")))
        assertEquals(listOf("a", "b"), DocumentModelChoices.pdfOrAll(models, setOf("a"), setOf("a")))
        assertEquals(models, DocumentModelChoices.pdfOrAll(models, emptySet(), emptySet()))
    }

    @Test
    fun githubUsesPdfMediaTypeAndFallsBackToEveryModel() {
        val told = """{"data":[{"id":"gpt-5.6-sol","capabilities":{"limits":{"vision":{"supported_media_types":["application/pdf"]}}}},{"id":"gpt-4o","capabilities":{"limits":{"vision":{"supported_media_types":["image/png"]}}}}]}"""
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
        assertEquals(listOf("claude", "any"), OpenCodeConsoleModel.documentModelIds(listOf(pdf, unknown)))
    }

    @Test
    fun azureListsOtherDeploymentsAsNativePdfAndDoesNotAutoSelectThem() {
        val quota = AzureQuota(windows = listOf(
            AzureUsageWindow("mistral-ocr", "", AzureUsageWindow.DEPLOYMENT, resourceName = "res", modelName = "mistral-ocr-4"),
            AzureUsageWindow("gpt", "", AzureUsageWindow.DEPLOYMENT, resourceName = "res", modelName = "gpt-5.5"),
        ))
        assertEquals(listOf("native:gpt"), azureNativePdfChoices(quota, "res"))
    }

    @Test
    fun helloPdfContainsTheSampleSentence() {
        val path = Files.createTempFile("hello", ".pdf")
        try {
            HelloPdf.write(path)
            Loader.loadPDF(path.toFile()).use { document ->
                val box = document.getPage(0).mediaBox
                assertTrue(box.width > box.height)
                val text = PDFTextStripper().getText(document)
                assertTrue(text.contains(HelloPdf.TEXT))
                assertTrue(text.split(HelloPdf.TEXT).size > 3)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun nativePdfResponseTextIsReadFromChatAndResponses() {
        assertTrue(NativePdfDocument.requestJson(NativePdfRoute.RESPONSES, "m", "a.pdf", "data:", "QQ==").contains("input_file"))
        assertEquals(NativePdfRoute.ANTHROPIC, githubDocumentRoute("claude-sonnet", listOf("/v1/messages", "/chat/completions")))
        assertEquals(NativePdfRoute.RESPONSES, githubDocumentRoute("custom", listOf("/responses")))
        assertEquals(NativePdfRoute.CHAT, githubDocumentRoute("gpt-4.1"))
        assertEquals("Hello", NativePdfDocument.extractText("""{"choices":[{"message":{"content":"Hello"}}]}"""))
        assertEquals("Hi", NativePdfDocument.extractText("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n"))
    }

    private fun model(id: String, pdf: Boolean, known: Boolean) = OpenCodeConsoleModel(
        model = de.moritzf.proxy.subscription.SubscriptionProxyModel(
            localId = id, upstreamId = id, providerId = "opencode", providerName = "OpenCode",
            litellmProvider = "opencode", supportedRoutes = emptySet(),
        ),
        nativeRoute = de.moritzf.proxy.subscription.SubscriptionProxyRoute.CHAT_COMPLETIONS,
        baseUri = java.net.URI.create("https://opencode.ai/zen/v1"),
        headers = emptyMap(),
        body = kotlinx.serialization.json.JsonObject(emptyMap()),
        supportsPdf = pdf,
        pdfCapabilityKnown = known,
    )
}
