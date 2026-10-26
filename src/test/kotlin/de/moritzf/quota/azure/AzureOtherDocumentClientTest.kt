package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrWriteResult
import de.moritzf.quota.shared.DocumentMarkdownWriteResult
import de.moritzf.quota.shared.JsonSupport
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.io.TempDir

class AzureOtherDocumentClientTest {
    private val config = azureAccountConfig(null, "my-resource", null, null, null)

    @Test
    fun cohereRendersPdfPageAndWritesMarkdown() {
        val source = Files.createTempFile("cohere-parse", ".pdf")
        val target = source.resolveSibling(source.fileName.toString().removeSuffix(".pdf") + ".md")
        try {
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                pdf.save(source.toFile())
            }
            var calls = 0
            val client =
                AzureCohereParseClient(
                    post = { request ->
                        calls++
                        assertEquals(azureCohereParseUri(config), request.uri())
                        assertEquals(
                            "Bearer token",
                            request.headers().firstValue("Authorization").orElse(null),
                        )
                        AzureOcrResponse(
                            200,
                            """{"pages":[{"type":"markdown","markdown":{"content":"# Page"}}]}""",
                        )
                    }
                )
            val result =
                client.convertDocument(
                    cli(AZURE_FOUNDRY_SCOPE),
                    config,
                    "receipt-parser",
                    localFile = source,
                )
            assertEquals(1, calls)
            assertEquals("# Page", Files.readString(target))
            assertTrue(result.contains("\"pages\": 1"))
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun cohereRejectsPdfPageRangeOutsideDocumentWithoutSending() {
        val source = Files.createTempFile("cohere-range", ".pdf")
        try {
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                pdf.save(source.toFile())
            }
            assertFailsWith<AzureOcrException> {
                AzureCohereParseClient(post = { error("Unexpected request") })
                    .convertDocument(
                        cli(AZURE_FOUNDRY_SCOPE),
                        config,
                        "receipt-parser",
                        localFile = source,
                        pageFrom = 2,
                        pageTo = 2,
                    )
            }
        } finally {
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun cohereConvertsEveryPageInsteadOfStoppingAtTwenty() {
        val source = Files.createTempFile("cohere-long", ".pdf")
        val target = source.resolveSibling(source.fileName.toString().removeSuffix(".pdf") + ".md")
        try {
            PDDocument().use { pdf ->
                repeat(21) { pdf.addPage(PDPage()) }
                pdf.save(source.toFile())
            }
            var calls = 0
            val client =
                AzureCohereParseClient(
                    post = {
                        calls++
                        AzureOcrResponse(
                            200,
                            """{"pages":[{"markdown":{"content":"# Page $calls"}}]}""",
                        )
                    }
                )
            client.convertDocument(
                cli(AZURE_FOUNDRY_SCOPE),
                config,
                "receipt-parser",
                localFile = source,
            )
            assertEquals(21, calls)
            assertEquals(
                (1..21).joinToString("\n\n") { "# Page $it" },
                Files.readString(target).trim(),
            )
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun cohereCancellationAfterPagePreventsNextRequestAndPublishing() {
        val source = Files.createTempFile("cohere-cancel", ".pdf")
        val target = source.resolveSibling(source.fileName.toString().removeSuffix(".pdf") + ".md")
        try {
            PDDocument().use { pdf ->
                repeat(3) { pdf.addPage(PDPage()) }
                pdf.save(source.toFile())
            }
            var calls = 0
            val client =
                AzureCohereParseClient(
                    post = {
                        calls++
                        AzureOcrResponse(200, """{"pages":[{"markdown":{"content":"Page"}}]}""")
                    }
                )
            assertFailsWith<CancellationException> {
                client.convertDocument(
                    cli(AZURE_FOUNDRY_SCOPE),
                    config,
                    "receipt-parser",
                    localFile = source,
                    outputFile = target,
                    progress = { completed, _, _ ->
                        if (completed == 1) throw CancellationException()
                    },
                )
            }
            assertEquals(1, calls)
            assertFalse(Files.exists(target))
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun cohereAcceptsImageAndWritesMarkdown() {
        val image = Files.createTempFile("cohere-image", ".png")
        val markdown = image.resolveSibling(image.fileName.toString().removeSuffix(".png") + ".md")
        try {
            ImageIO.write(BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
            val client =
                AzureCohereParseClient(
                    post = {
                        AzureOcrResponse(
                            200,
                            """{"pages":[{"type":"markdown","markdown":{"content":"Image text"}}]}""",
                        )
                    }
                )
            client.convertDocument(
                cli(AZURE_FOUNDRY_SCOPE),
                config,
                "Cohere-parse-v5",
                localFile = image,
            )
            assertEquals("Image text", Files.readString(markdown))
        } finally {
            Files.deleteIfExists(markdown)
            Files.deleteIfExists(image)
        }
    }

    @Test
    fun cohereWritesGroundedImageCropsBesideMarkdown() {
        val image = Files.createTempFile("cohere-figures", ".png")
        val markdown = image.resolveSibling(image.fileName.toString().removeSuffix(".png") + ".md")
        var crop: Path? = null
        try {
            ImageIO.write(BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
            val client =
                AzureCohereParseClient(
                    post = {
                        AzureOcrResponse(
                            200,
                            """{"pages":[{"type":"markdown","markdown":{
                    "content":"![Logo](img-0)",
                    "images":[{"id":"img-0","bounding_box":{
                        "top_left_x":10,"top_left_y":10,"bottom_right_x":40,"bottom_right_y":30
                    }}]
                }}]}""",
                        )
                    }
                )
            val result =
                JsonSupport.json.decodeFromString<MistralOcrWriteResult>(
                    client.convertDocument(
                        cli(AZURE_FOUNDRY_SCOPE),
                        config,
                        "Cohere-parse-v5",
                        localFile = image,
                    )
                )
            crop = Path.of(result.imageFiles.single())
            assertEquals(
                "![Logo](${crop.parent.fileName}/${crop.fileName})",
                Files.readString(markdown),
            )
            assertEquals(30, ImageIO.read(crop.toFile()).width)
            assertTrue(result.warnings.single().contains("original PDF unavailable"))
        } finally {
            crop?.let {
                Files.deleteIfExists(it)
                Files.deleteIfExists(it.parent)
            }
            Files.deleteIfExists(markdown)
            Files.deleteIfExists(image)
        }
    }

    @Test
    fun documentIntelligencePollsAndWritesNativeMarkdown() {
        val source = Files.createTempFile("azure-di", ".pdf")
        val target = source.resolveSibling(source.fileName.toString().removeSuffix(".pdf") + ".md")
        try {
            Files.writeString(source, "%PDF-1.4\n")
            val operation =
                "https://my-resource.cognitiveservices.azure.com/documentintelligence/" +
                    "documentModels/prebuilt-layout/analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
            var calls = 0
            val client =
                AzureDocumentIntelligenceClient(
                    send = { request ->
                        calls++
                        assertEquals(
                            "Bearer token",
                            request.headers().firstValue("Authorization").orElse(null),
                        )
                        if (calls == 1) {
                            assertEquals("POST", request.method())
                            assertTrue(
                                request.uri().toString().contains("outputContentFormat=markdown")
                            )
                            AzureDocumentIntelligenceResponse(
                                202,
                                mapOf("Operation-Location" to operation, "Retry-After" to "1"),
                                byteArrayOf(),
                            )
                        } else {
                            assertEquals(operation, request.uri().toString())
                            AzureDocumentIntelligenceResponse(
                                200,
                                emptyMap(),
                                """
                                {"status":"succeeded","analyzeResult":{"content":"# Layout","pages":[{"pageNumber":1}]}}
                                """
                                    .trimIndent()
                                    .toByteArray(),
                            )
                        }
                    }
                )
            val result =
                client.convertDocument(
                    cli(AZURE_COGNITIVE_SCOPE),
                    config,
                    localFile = source,
                    includeImages = false,
                )
            assertEquals(2, calls)
            assertEquals("# Layout", Files.readString(target))
            assertEquals(
                1,
                JsonSupport.json.decodeFromString<DocumentMarkdownWriteResult>(result).pageCount,
            )
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun documentIntelligenceRejectsCrossHostOperationLocation() {
        val source = Files.createTempFile("azure-di-invalid", ".pdf")
        try {
            Files.writeString(source, "%PDF-1.4\n")
            val client =
                AzureDocumentIntelligenceClient(
                    send = {
                        AzureDocumentIntelligenceResponse(
                            202,
                            mapOf(
                                "Operation-Location" to
                                    "https://example.com/documentintelligence/documentModels/prebuilt-layout/" +
                                        "analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
                            ),
                            byteArrayOf(),
                        )
                    }
                )
            assertFailsWith<AzureOcrException> {
                client.convertDocument(cli(AZURE_COGNITIVE_SCOPE), config, localFile = source)
            }
        } finally {
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun documentIntelligenceDownloadsFiguresAndFixesMarkdownLinks() {
        val source = Files.createTempFile("azure-di-figures", ".pdf")
        val target = source.resolveSibling(source.fileName.toString().removeSuffix(".pdf") + ".md")
        var figure: Path? = null
        val operation =
            "https://my-resource.cognitiveservices.azure.com/documentintelligence/" +
                "documentModels/prebuilt-layout/analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
        try {
            Files.writeString(source, "%PDF-1.4\n")
            var calls = 0
            val client =
                AzureDocumentIntelligenceClient(
                    send = { request ->
                        calls++
                        when (calls) {
                            1 ->
                                AzureDocumentIntelligenceResponse(
                                    202,
                                    mapOf("Operation-Location" to operation, "Retry-After" to "1"),
                                    byteArrayOf(),
                                )

                            2 ->
                                AzureDocumentIntelligenceResponse(
                                    200,
                                    emptyMap(),
                                    """
                                    {"status":"succeeded","analyzeResult":{"content":"![Figure](figures/1.0)",
                                     "pages":[{"pageNumber":1}],"figures":[{"id":"1.0"}]}}
                                    """
                                        .trimIndent()
                                        .toByteArray(),
                                )

                            else -> {
                                assertTrue(
                                    request
                                        .uri()
                                        .toString()
                                        .contains("/figures/1.0?api-version=2024-11-30")
                                )
                                AzureDocumentIntelligenceResponse(
                                    200,
                                    emptyMap(),
                                    byteArrayOf(
                                        0x89.toByte(),
                                        0x50,
                                        0x4e,
                                        0x47,
                                        0x0d,
                                        0x0a,
                                        0x1a,
                                        0x0a,
                                    ),
                                )
                            }
                        }
                    }
                )
            val result =
                JsonSupport.json.decodeFromString<DocumentMarkdownWriteResult>(
                    client.convertDocument(cli(AZURE_COGNITIVE_SCOPE), config, localFile = source)
                )
            figure = Path.of(result.imageFiles.single())
            assertEquals(3, calls)
            assertEquals(
                "![Figure](${figure.parent.fileName}/${figure.fileName})",
                Files.readString(target),
            )
            assertTrue(Files.exists(figure))
            assertTrue(result.warnings.isNotEmpty())
        } finally {
            figure?.let {
                Files.deleteIfExists(it)
                Files.deleteIfExists(it.parent)
            }
            Files.deleteIfExists(target)
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun documentIntelligenceIsolatesFailingPageAndSendsItAsImage(@TempDir directory: Path) {
        val source = directory.resolve("source.pdf")
        PDDocument().use { pdf ->
            repeat(3) { index ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(40f, 700f)
                    content.showText("Page ${index + 1}.")
                    content.endText()
                }
            }
            pdf.save(source.toFile())
        }
        val operation =
            "https://my-resource.cognitiveservices.azure.com/documentintelligence/" +
                "documentModels/prebuilt-layout/analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
        val analyses = mutableListOf<String>()
        val client =
            AzureDocumentIntelligenceClient(
                send = { request ->
                    if (request.method() == "POST") {
                        val bytes =
                            Base64.getDecoder()
                                .decode(
                                    JsonSupport.json
                                        .decodeFromString<AzureDocumentAnalyzeRequest>(
                                            requestBody(request)
                                        )
                                        .base64Source
                                )
                        analyses +=
                            if (ImageIO.read(bytes.inputStream()) != null) "image"
                            else
                                Loader.loadPDF(bytes).use { pdf ->
                                    Regex("Page (\\d+)\\.")
                                        .findAll(PDFTextStripper().getText(pdf))
                                        .joinToString(",") { it.groupValues[1] }
                                }
                        AzureDocumentIntelligenceResponse(
                            202,
                            mapOf("Operation-Location" to operation, "Retry-After" to "1"),
                            byteArrayOf(),
                        )
                    } else {
                        val analysis = analyses.last()
                        val body =
                            if ("2" in analysis.split(',')) """{"status":"failed"}"""
                            else {
                                val title =
                                    if (analysis == "image") "Image page" else "Page $analysis"
                                """{"status":"succeeded","analyzeResult":{"content":"# $title","pages":[{"pageNumber":1}]}}"""
                            }
                        AzureDocumentIntelligenceResponse(200, emptyMap(), body.toByteArray())
                    }
                }
            )

        val result =
            JsonSupport.json.decodeFromString<DocumentMarkdownWriteResult>(
                client.convertDocument(
                    cli(AZURE_COGNITIVE_SCOPE),
                    config,
                    localFile = source,
                    includeImages = false,
                )
            )

        assertEquals(listOf("1,2,3", "1", "2", "image", "3"), analyses)
        assertEquals(3, result.pageCount)
        assertEquals(
            "# Page 1\n\n# Image page\n\n# Page 3",
            Files.readString(directory.resolve("source.md")),
        )
    }

    private fun requestBody(request: HttpRequest): String {
        val output = ByteArrayOutputStream()
        val completed = CountDownLatch(1)
        request
            .bodyPublisher()
            .orElseThrow()
            .subscribe(
                object : Flow.Subscriber<ByteBuffer> {
                    override fun onSubscribe(subscription: Flow.Subscription) =
                        subscription.request(Long.MAX_VALUE)

                    override fun onNext(item: ByteBuffer) {
                        val bytes = ByteArray(item.remaining())
                        item.get(bytes)
                        output.write(bytes)
                    }

                    override fun onError(throwable: Throwable) = completed.countDown()

                    override fun onComplete() = completed.countDown()
                }
            )
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        return output.toString(Charsets.UTF_8)
    }

    private fun cli(scope: String) =
        AzureCli(
            Path.of("/test/az"),
            run = { _, args, _, _ ->
                assertEquals(scope, args[args.indexOf("--scope") + 1])
                """{"accessToken":"token","expires_on":4102444800}"""
            },
        )
}
