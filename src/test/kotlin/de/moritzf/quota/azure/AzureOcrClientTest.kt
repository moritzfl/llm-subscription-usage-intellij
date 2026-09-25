package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrWriteResult
import de.moritzf.quota.mistral.MistralOcrRequestDto
import de.moritzf.quota.mistral.MistralOcrResponseDto
import de.moritzf.quota.mistral.MistralOcrPageDto
import de.moritzf.quota.mistral.MistralOcrImageDto
import de.moritzf.quota.shared.JsonSupport
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

class AzureOcrClientTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun offersOnlySupportedDocumentModelsAndPrebuiltLayoutWhenConfigured() {
        val quota = AzureQuota(
            models = listOf("Mistral-Large-3", "mistral-ocr-4-0", "mistral-document-ai-2512"),
            windows = listOf(
                AzureUsageWindow(
                    "custom-ocr", "", AzureUsageWindow.DEPLOYMENT,
                    resourceName = "my-resource", modelName = "mistral-ocr-4-0"
                ),
                AzureUsageWindow(
                    "chat", "", AzureUsageWindow.DEPLOYMENT,
                    resourceName = "my-resource", modelName = "Mistral-Large-3"
                ),
            ),
        )
        assertEquals(
            listOf("custom-ocr", "mistral-document-ai-2512", "mistral-ocr-4-0"),
            azureOcrDeployments(quota, null, null, "my-resource"),
        )
        assertEquals(emptyList(), azureOcrDeployments(null, null, null, "my-resource"))
        assertEquals(
            listOf("mistral-ocr-4-0"),
            azureOcrDeployments(null, "Mistral-Large-3, mistral-ocr-4-0", null, "my-resource")
        )
        assertEquals(
            emptyList(), azureOcrDeployments(
                AzureQuota(
                    models = listOf("mistral-ocr-chat"), windows = listOf(
                        AzureUsageWindow(
                            "mistral-ocr-chat", "", AzureUsageWindow.DEPLOYMENT,
                            resourceName = "my-resource", modelName = "Mistral-Large-3"
                        ),
                    )
                ),
                "mistral-ocr-chat", null, "my-resource",
            )
        )
        assertFalse(isAzureOcrModel("Mistral-Large-3"))
        val cohere = AzureQuota(windows = listOf(
            AzureUsageWindow("receipt-parser", "", AzureUsageWindow.DEPLOYMENT,
                resourceName = "my-resource", modelName = "Cohere-parse-v5"),
        ))
        assertEquals(
            listOf("cohere:receipt-parser", AZURE_DOCUMENT_INTELLIGENCE_LAYOUT),
            azureOcrDeployments(cohere, null, null, "my-resource", documentIntelligenceAvailable = true),
        )
        assertEquals("receipt-parser", azureOcrDeploymentId("cohere:receipt-parser"))
        assertTrue(isAzureCohereSelection("cohere:receipt-parser"))
    }

    @Test
    fun azureOcrUsesFoundryRouteAndSelectedDeployment() {
        val config = azureAccountConfig(null, "my-resource", null, null, null)
        assertEquals(
            "https://my-resource.services.ai.azure.com/providers/mistral/azure/ocr?api-version=2024-05-01-preview",
            azureOcrUri(config).toString(),
        )
        assertEquals(AZURE_FOUNDRY_SCOPE, azureScopeForUrl(azureOcrUri(config).toString()))

        val file = Files.createTempFile("azure-ocr", ".pdf")
        try {
            createPdf(file, 1)
            var calls = 0
            val client = AzureOcrClient(post = { request ->
                calls++
                assertEquals(azureOcrUri(config), request.uri())
                assertEquals("Bearer example-token", request.headers().firstValue("Authorization").orElse(null))
                val json = JsonSupport.json.parseToJsonElement(requestBody(request)).toString()
                assertTrue(json.contains("\"model\":\"mistral-ocr-4-0\""))
                assertTrue(json.contains("data:application/pdf;base64,"))
                assertTrue(json.contains("\"include_image_base64\":true"))
                AzureOcrResponse(200, """{"model":"mistral-ocr-4-0","pages":[{"markdown":"# Test"}]}""")
            })
            val cli = AzureCli(Path.of("/test/az"), run = { _, args, _, _ ->
                assertEquals(
                    listOf("account", "get-access-token", "--scope", AZURE_FOUNDRY_SCOPE, "--output", "json"),
                    args
                )
                """{"accessToken":"example-token","expires_on":4102444800}"""
            })
            val result = client.convertDocument(cli, config, "mistral-ocr-4-0", localFile = file)
            assertEquals(1, JsonSupport.json.decodeFromString<MistralOcrWriteResult>(result).pages)
            assertEquals(
                "# Test",
                Files.readString(file.resolveSibling(file.fileName.toString().removeSuffix(".pdf") + ".md"))
            )
            assertEquals(1, calls)
        } finally {
            Files.deleteIfExists(file.resolveSibling(file.fileName.toString().removeSuffix(".pdf") + ".md"))
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun otherDocumentRoutesUseTheirOwnHostsAndAudiences() {
        val config = azureAccountConfig(null, "my-resource", null, null, null)
        assertEquals("https://my-resource.services.ai.azure.com/providers/cohere/v2/parse",
            azureCohereParseUri(config).toString())
        assertEquals(AZURE_FOUNDRY_SCOPE, azureScopeForUrl(azureCohereParseUri(config).toString()))
        assertEquals("https://my-resource.cognitiveservices.azure.com/documentintelligence/" +
            "documentModels/prebuilt-layout:analyze?_overload=analyzeDocument&api-version=2024-11-30&outputContentFormat=markdown",
            azureDocumentIntelligenceUri(config).toString())
        assertEquals(AZURE_COGNITIVE_SCOPE, azureScopeForUrl(azureDocumentIntelligenceUri(config).toString()))
    }

    @Test
    fun rejectsUnsupportedDocumentWithoutSendingRequest() {
        val file = Files.createTempFile("azure-ocr-invalid", ".txt")
        try {
            Files.writeString(file, "not a document")
            val client = AzureOcrClient(post = { error("Unexpected Azure call") })
            val cli = AzureCli(Path.of("/test/az"), run = { _, _, _, _ -> error("Unexpected CLI call") })
            val exception = assertFailsWith<AzureOcrException> {
                client.convertDocument(
                    cli, azureAccountConfig(null, "my-resource", null, null, null),
                    "mistral-ocr-4-0", localFile = file
                )
            }
            assertTrue(exception.message.orEmpty().contains("PDF, PNG, or JPEG"))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun rejectsPrivateDocumentUrlBeforeSendingRequest() {
        val client = AzureOcrClient(post = { error("Unexpected Azure call") })
        val cli = AzureCli(Path.of("/test/az"), run = { _, _, _, _ -> error("Unexpected CLI call") })
        val exception = assertFailsWith<AzureOcrException> {
            client.convertDocument(
                cli, azureAccountConfig(null, "my-resource", null, null, null),
                "mistral-ocr-4-0", documentUrl = "https://localhost/document.pdf"
            )
        }
        assertTrue(exception.message.orEmpty().contains("public HTTPS URL"))
    }

    @Test
    fun imageUsesImageUrlAndWritesMarkdownBesideLocalFile() {
        val image = Files.createTempFile("azure-ocr", ".png")
        try {
            Files.write(image, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            val native = """{"pages":[{"markdown":"Picture"}],"model":"mistral-ocr-4-0"}"""
            val client = AzureOcrClient(post = { request ->
                val json = JsonSupport.json.parseToJsonElement(requestBody(request)).toString()
                assertTrue(json.contains("\"type\":\"image_url\""))
                assertTrue(json.contains("data:image/png;base64,"))
                AzureOcrResponse(200, native)
            })
            val cli = AzureCli(Path.of("/test/az"), run = { _, _, _, _ ->
                """{"accessToken":"example-token","expires_on":4102444800}"""
            })
            val result = client.convertDocument(
                cli,
                azureAccountConfig(null, "my-resource", null, null, null), "mistral-ocr-4-0",
                localFile = image
            )
            assertEquals(1, JsonSupport.json.decodeFromString<MistralOcrWriteResult>(result).pages)
            assertEquals(
                "Picture",
                Files.readString(image.resolveSibling(image.fileName.toString().removeSuffix(".png") + ".md"))
            )
        } finally {
            Files.deleteIfExists(image.resolveSibling(image.fileName.toString().removeSuffix(".png") + ".md"))
            Files.deleteIfExists(image)
        }
    }

    @Test
    fun splitsAtThirtyPagesPreservingContentOrderImagesAndProgress() {
        for (count in listOf(1, 30, 31, 61, 129)) {
            val source = directory.resolve("source-$count.pdf")
            val output = directory.resolve("result-$count.md")
            createPdf(source, count)
            val sizes = mutableListOf<Int>()
            val completed = mutableListOf<Int>()
            var nextPage = 1
            val client = AzureOcrClient(post = { request ->
                val payload = JsonSupport.json.decodeFromString<MistralOcrRequestDto>(requestBody(request))
                assertTrue(payload.includeImageBase64)
                val bytes = Base64.getDecoder().decode(payload.document.documentUrl!!.substringAfter("base64,"))
                val pages = Loader.loadPDF(bytes).use { pdf ->
                    sizes += pdf.numberOfPages
                    assertTrue(pdf.numberOfPages in 1..30)
                    val text = PDFTextStripper().getText(pdf)
                    (1..pdf.numberOfPages).map {
                        val number = nextPage++
                        assertTrue(text.contains("Page $number."), "Page resources/content lost for page $number")
                        MistralOcrPageDto("# Page $number\n![figure](img-0.png)", listOf(
                            MistralOcrImageDto("img-0.png", Base64.getEncoder().encodeToString(byteArrayOf(number.toByte()))),
                        ))
                    }
                }
                AzureOcrResponse(200, JsonSupport.json.encodeToString(MistralOcrResponseDto(pages)))
            })
            val result = JsonSupport.json.decodeFromString<MistralOcrWriteResult>(client.convertDocument(
                testCli(), azureAccountConfig(null, "test-resource", null, null, null), "mistral-ocr-4-0",
                localFile = source, outputFile = output,
                progress = { done, total, _ ->
                    if (total > 0) {
                        assertEquals(count, total)
                        completed += done
                    }
                },
            ))
            assertEquals((1..count).chunked(30).map(List<Int>::size), sizes)
            assertEquals(count, result.pages)
            assertEquals(count, result.imageFiles.distinct().size)
            assertEquals(completed.sorted(), completed)
            assertEquals(0, completed.first())
            assertEquals(count, completed.last())
            val markdown = Files.readString(output)
            val headings = Regex("(?m)^# Page (\\d+)$").findAll(markdown).map { it.groupValues[1].toInt() }.toList()
            assertEquals((1..count).toList(), headings)
            result.imageFiles.forEachIndexed { index, value ->
                val image = Path.of(value)
                assertEquals(listOf((index + 1).toByte()), Files.readAllBytes(image).toList())
                assertTrue(markdown.contains("(${image.parent.fileName}/${image.fileName})"))
            }
        }
    }

    @Test
    fun failedLaterChunkPreservesExistingOutputAndCleansPartialImages() {
        val source = directory.resolve("source.pdf")
        val output = directory.resolve("result.md")
        createPdf(source, 31)
        Files.writeString(output, "Existing document")
        val existingImage = directory.resolve("img-0.png")
        Files.writeString(existingImage, "Existing image")
        var calls = 0
        val client = AzureOcrClient(post = {
            if (++calls == 1) pageResponse(30, images = true) else AzureOcrResponse(500, """{"message":"test error"}""")
        })
        val error = assertFailsWith<AzureOcrException> {
            client.convertDocument(testCli(), azureAccountConfig(null, "test-resource", null, null, null),
                "mistral-ocr-4-0", localFile = source, outputFile = output)
        }
        assertTrue(error.message.orEmpty().contains("31–31"))
        assertEquals(2, calls)
        assertEquals("Existing document", Files.readString(output))
        assertEquals("Existing image", Files.readString(existingImage))
        Files.list(directory).use { assertEquals(3, it.count()) }
    }

    @Test
    fun cancellationAfterChunkPreventsNextRequestAndPublishing() {
        val source = directory.resolve("source.pdf")
        val output = directory.resolve("result.md")
        createPdf(source, 31)
        var calls = 0
        val client = AzureOcrClient(post = { calls++; pageResponse(30, images = true) })
        assertFailsWith<CancellationException> {
            client.convertDocument(testCli(), azureAccountConfig(null, "test-resource", null, null, null),
                "mistral-ocr-4-0", localFile = source, outputFile = output,
                progress = { completed, _, _ -> if (completed == 30) throw CancellationException() })
        }
        assertEquals(1, calls)
        assertFalse(Files.exists(output))
        Files.list(directory).use { assertEquals(listOf(source), it.toList()) }
    }

    @Test
    fun truncatedOrInvalidResponseDoesNotPublishPartialDocument() {
        val source = directory.resolve("source.pdf")
        val output = directory.resolve("result.md")
        createPdf(source, 2)
        for (response in listOf(pageResponse(1), AzureOcrResponse(200, "not-json"), pageResponse(0))) {
            assertFailsWith<AzureOcrException> {
                AzureOcrClient(post = { response }).convertDocument(testCli(),
                    azureAccountConfig(null, "test-resource", null, null, null), "mistral-ocr-4-0",
                    localFile = source, outputFile = output)
            }
            assertFalse(Files.exists(output))
            Files.list(directory).use { assertEquals(listOf(source), it.toList()) }
        }
    }

    @Test
    fun retriesUnauthorizedChunkWithoutDuplicatingPages() {
        val source = directory.resolve("source.pdf")
        val output = directory.resolve("result.md")
        createPdf(source, 31)
        var calls = 0
        val client = AzureOcrClient(post = {
            when (++calls) {
                1 -> pageResponse(30)
                2 -> AzureOcrResponse(401, "")
                else -> pageResponse(1)
            }
        })
        val result = client.convertDocument(testCli(), azureAccountConfig(null, "test-resource", null, null, null),
            "mistral-ocr-4-0", localFile = source, outputFile = output, includeImages = false)
        assertEquals(3, calls)
        assertEquals(31, JsonSupport.json.decodeFromString<MistralOcrWriteResult>(result).pages)
        assertEquals(31, Regex("Test page").findAll(Files.readString(output)).count())
    }

    private fun testCli() = AzureCli(Path.of("/test/az"), run = { _, _, _, _ ->
        """{"accessToken":"example-token","expires_on":4102444800}"""
    })

    private fun pageResponse(count: Int, images: Boolean = false): AzureOcrResponse = AzureOcrResponse(200,
        JsonSupport.json.encodeToString(MistralOcrResponseDto(List(count) {
            MistralOcrPageDto("Test page ![figure](img-0.png)", if (images) listOf(MistralOcrImageDto("img-0.png", "AQID")) else emptyList())
        })),
    )

    private fun createPdf(path: Path, pages: Int) {
        PDDocument().use { pdf ->
            repeat(pages) { index ->
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
            pdf.save(path.toFile())
        }
    }

    private fun requestBody(request: HttpRequest): String {
        val output = ByteArrayOutputStream()
        val completed = CountDownLatch(1)
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) {
                val bytes = ByteArray(item.remaining())
                item.get(bytes)
                output.write(bytes)
            }

            override fun onError(throwable: Throwable) = completed.countDown()
            override fun onComplete() = completed.countDown()
        })
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        return output.toString(Charsets.UTF_8)
    }
}
