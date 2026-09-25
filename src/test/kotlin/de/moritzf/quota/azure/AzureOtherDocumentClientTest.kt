package de.moritzf.quota.azure

import de.moritzf.quota.shared.DocumentMarkdownWriteResult
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.mistral.MistralOcrWriteResult
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage

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
            val client = AzureCohereParseClient(post = { request ->
                calls++
                assertEquals(azureCohereParseUri(config), request.uri())
                assertEquals("Bearer token", request.headers().firstValue("Authorization").orElse(null))
                AzureOcrResponse(200, """{"pages":[{"type":"markdown","markdown":{"content":"# Page"}}]}""")
            })
            val result = client.convertDocument(cli(AZURE_FOUNDRY_SCOPE), config, "receipt-parser", localFile = source)
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
            PDDocument().use { pdf -> pdf.addPage(PDPage()); pdf.save(source.toFile()) }
            assertFailsWith<AzureOcrException> {
                AzureCohereParseClient(post = { error("Unexpected request") })
                    .convertDocument(
                        cli(AZURE_FOUNDRY_SCOPE), config, "receipt-parser", localFile = source,
                        pageFrom = 2, pageTo = 2
                    )
            }
        } finally {
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun cohereAcceptsImageAndWritesMarkdown() {
        val image = Files.createTempFile("cohere-image", ".png")
        val markdown = image.resolveSibling(image.fileName.toString().removeSuffix(".png") + ".md")
        try {
            ImageIO.write(BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
            val client = AzureCohereParseClient(post = {
                AzureOcrResponse(200, """{"pages":[{"type":"markdown","markdown":{"content":"Image text"}}]}""")
            })
            client.convertDocument(cli(AZURE_FOUNDRY_SCOPE), config, "Cohere-parse-v5", localFile = image)
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
            val client = AzureCohereParseClient(post = {
                AzureOcrResponse(
                    200, """{"pages":[{"type":"markdown","markdown":{
                    "content":"![Logo](img-0)",
                    "images":[{"id":"img-0","bounding_box":{
                        "top_left_x":10,"top_left_y":10,"bottom_right_x":40,"bottom_right_y":30
                    }}]
                }}]}"""
                )
            })
            val result = JsonSupport.json.decodeFromString<MistralOcrWriteResult>(
                client.convertDocument(cli(AZURE_FOUNDRY_SCOPE), config, "Cohere-parse-v5", localFile = image))
            crop = Path.of(result.imageFiles.single())
            assertEquals("![Logo](${crop.parent.fileName}/${crop.fileName})", Files.readString(markdown))
            assertEquals(30, ImageIO.read(crop.toFile()).width)
            assertTrue(result.warnings.single().contains("original PDF unavailable"))
        } finally {
            crop?.let { Files.deleteIfExists(it); Files.deleteIfExists(it.parent) }
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
            val operation = "https://my-resource.cognitiveservices.azure.com/documentintelligence/" +
                    "documentModels/prebuilt-layout/analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
            var calls = 0
            val client = AzureDocumentIntelligenceClient(send = { request ->
                calls++
                assertEquals("Bearer token", request.headers().firstValue("Authorization").orElse(null))
                if (calls == 1) {
                    assertEquals("POST", request.method())
                    assertTrue(request.uri().toString().contains("outputContentFormat=markdown"))
                    AzureDocumentIntelligenceResponse(
                        202,
                        mapOf("Operation-Location" to operation, "Retry-After" to "1"),
                        byteArrayOf()
                    )
                } else {
                    assertEquals(operation, request.uri().toString())
                    AzureDocumentIntelligenceResponse(
                        200, emptyMap(), """
                        {"status":"succeeded","analyzeResult":{"content":"# Layout","pages":[{"pageNumber":1}]}}
                    """.trimIndent().toByteArray()
                    )
                }
            })
            val result = client.convertDocument(
                cli(AZURE_COGNITIVE_SCOPE), config, localFile = source,
                includeImages = false
            )
            assertEquals(2, calls)
            assertEquals("# Layout", Files.readString(target))
            assertEquals(1, JsonSupport.json.decodeFromString<DocumentMarkdownWriteResult>(result).pageCount)
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
            val client = AzureDocumentIntelligenceClient(send = {
                AzureDocumentIntelligenceResponse(
                    202, mapOf(
                        "Operation-Location" to
                                "https://example.com/documentintelligence/documentModels/prebuilt-layout/" +
                                "analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
                    ), byteArrayOf()
                )
            })
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
        val operation = "https://my-resource.cognitiveservices.azure.com/documentintelligence/" +
                "documentModels/prebuilt-layout/analyzeResults/11111111-1111-1111-1111-111111111111?api-version=2024-11-30"
        try {
            Files.writeString(source, "%PDF-1.4\n")
            var calls = 0
            val client = AzureDocumentIntelligenceClient(send = { request ->
                calls++
                when (calls) {
                    1 -> AzureDocumentIntelligenceResponse(
                        202,
                        mapOf("Operation-Location" to operation, "Retry-After" to "1"), byteArrayOf()
                    )

                    2 -> AzureDocumentIntelligenceResponse(
                        200, emptyMap(), """
                        {"status":"succeeded","analyzeResult":{"content":"![Figure](figures/1.0)",
                         "pages":[{"pageNumber":1}],"figures":[{"id":"1.0"}]}}
                    """.trimIndent().toByteArray()
                    )

                    else -> {
                        assertTrue(request.uri().toString().contains("/figures/1.0?api-version=2024-11-30"))
                        AzureDocumentIntelligenceResponse(
                            200, emptyMap(),
                            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                        )
                    }
                }
            })
            val result = JsonSupport.json.decodeFromString<DocumentMarkdownWriteResult>(
                client.convertDocument(cli(AZURE_COGNITIVE_SCOPE), config, localFile = source))
            figure = Path.of(result.imageFiles.single())
            assertEquals(3, calls)
            assertEquals("![Figure](${figure.parent.fileName}/${figure.fileName})", Files.readString(target))
            assertTrue(Files.exists(figure))
            assertTrue(result.warnings.isNotEmpty())
        } finally {
            figure?.let { Files.deleteIfExists(it); Files.deleteIfExists(it.parent) }
            Files.deleteIfExists(target)
            Files.deleteIfExists(source)
        }
    }

    private fun cli(scope: String) = AzureCli(Path.of("/test/az"), run = { _, args, _, _ ->
        assertEquals(scope, args[args.indexOf("--scope") + 1])
        """{"accessToken":"token","expires_on":4102444800}"""
    })
}
