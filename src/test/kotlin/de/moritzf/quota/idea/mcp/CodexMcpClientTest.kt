package de.moritzf.quota.idea.mcp

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.DefaultOutputFiles
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.DocumentLimits
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.io.TempDir

class CodexMcpClientTest {
    @Test
    fun postsWebSearchToCodexResponsesEndpointWithQuotaAuth() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.delta","delta":"search "}""",
                """{"type":"response.output_text.delta","delta":"result"}""",
                """{"type":"response.completed","response":{"id":"resp_1","web_search":{"num_requests":1},"tool_usage":{"web_search":{"num_requests":1}}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.webSearch("OpenAI news")

            assertFalse(response.isError)
            assertEquals("search result", parseObject(response.body)["output"]!!.jsonPrimitive.content)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/backend-api/codex/responses", request.path)
            assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
            assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
            assertNull(request.firstHeader("version"))
            assertEquals("openai-usage-quota-plugin", request.firstHeader("originator"))
            assertTrue(request.firstHeader("User-Agent")!!.startsWith("openai-usage-quota-plugin/"))
            assertNull(request.firstHeader("OpenAI-Beta"))
            assertEquals("text/event-stream", request.firstHeader("Accept"))

            val body = parseObject(request.body)
            assertEquals("gpt-5.6-luna", body["model"]!!.jsonPrimitive.content)
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            assertFalse(body["store"]!!.jsonPrimitive.boolean)
            assertEquals(
                "Search the web for: OpenAI news",
                body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0]
                    .jsonObject["text"]!!.jsonPrimitive.content,
            )
            val tool = body["tools"]!!.jsonArray[0].jsonObject
            assertEquals("web_search", tool["type"]!!.jsonPrimitive.content)
            assertTrue(tool["external_web_access"]!!.jsonPrimitive.boolean)
            assertEquals("medium", tool["search_context_size"]!!.jsonPrimitive.content)
            assertEquals("text", tool["search_content_types"]!!.jsonArray[0].jsonPrimitive.content)
        }
    }

    @Test
    fun postsDocumentConversionToCodexResponsesAndWritesMarkdown() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.done","text":"# Converted"}""",
                """{"type":"response.completed","response":{"id":"resp_doc"}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)
            val dir = Files.createTempDirectory("codex-doc")
            val pdf = dir.resolve("doc.pdf")
            Files.write(pdf, "%PDF-1.4".toByteArray())

            val response = client.documentToMarkdown(localFile = pdf)

            assertFalse(response.isError)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/backend-api/codex/responses", request.path)
            val body = parseObject(request.body)
            assertEquals("gpt-6-sol", body["model"]!!.jsonPrimitive.content)
            val content = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("input_file", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("doc.pdf", content[0].jsonObject["filename"]!!.jsonPrimitive.content)
            assertTrue(content[0].jsonObject["file_data"]!!.jsonPrimitive.content.startsWith("data:application/pdf;base64,"))
            assertEquals("# Converted", Files.readString(dir.resolve("doc.md")))
        }
    }

    @Test
    fun rejectsOversizedLocalDocumentBeforeUpstream() {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)
            val dir = Files.createTempDirectory("codex-doc-big")
            val pdf = dir.resolve("big.pdf")
            RandomAccessFile(pdf.toFile(), "rw").use { it.setLength(DocumentLimits.MAX_INLINE_BYTES + 1) }

            val response = client.documentToMarkdown(localFile = pdf)

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("too large"))
            assertNull(upstream.requests.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun rejectsPageRangeOutsideTheDocument() {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)
            val dir = Files.createTempDirectory("codex-doc-pages")
            val pdf = dir.resolve("one.pdf")
            org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
                doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
                doc.save(pdf.toFile())
            }

            val response = client.documentToMarkdown(localFile = pdf, pageFrom = 2, pageTo = 2)

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("out of range"))
            assertNull(upstream.requests.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun sendsImagePlaceholderInstructionsForDocumentConversion() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.done","text":"# Doc\n\n![A bar chart of Q3 revenue by region](image-p1-1.png)"}""",
                """{"type":"response.completed","response":{"id":"resp_doc"}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)
            val dir = Files.createTempDirectory("codex-doc-img")
            val pdf = dir.resolve("doc.pdf")
            Files.write(pdf, "%PDF-1.4".toByteArray())

            val response = client.documentToMarkdown(localFile = pdf)

            assertFalse(response.isError)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            val body = parseObject(request.body)
            val instructions = body["instructions"]!!.jsonPrimitive.content
            assertTrue(instructions.contains("image-p<page>-<index>.png"))
            assertTrue(instructions.contains("<!-- img page="))
            assertTrue(instructions.contains("<x0> <y0> <x1> <y1>"))
            val userText = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1]
                .jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(userText.contains("image-p<page>-<index>.png"))
            assertTrue(userText.contains("<!-- img page="))
            val markdown = Files.readString(dir.resolve("doc.md"))
            assertFalse(markdown.contains("<!-- img page="))
            assertFalse(markdown.contains("](image-p1-1.png)"))
            assertTrue(markdown.contains("**Figure.** A bar chart of Q3 revenue by region"))
        }
    }

    @Test
    fun extractsCroppedImageRegionFromLocalPdf() {
        val dir = Files.createTempDirectory("codex-doc-crop")
        val pdf = dir.resolve("fig.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle(612f, 792f))
            doc.addPage(page)
            org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.setNonStrokingColor(java.awt.Color.RED)
                // top-left quadrant in PDF user space (origin bottom-left): 612x792 page
                cs.addRect(0f, 792f / 2f, 612f / 2f, 792f / 2f)
                cs.fill()
            }
            doc.save(pdf.toFile())
        }
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.done","text":"# With Figure\n\n![red box](image-p1-1.png)\n<!-- img page=1 0.0 0.0 0.5 0.5 -->\n"}""",
                """{"type":"response.completed","response":{"id":"resp_doc"}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.documentToMarkdown(localFile = pdf)

            assertFalse(response.isError)
            val png = dir.resolve("image-p1-1.png")
            assertTrue(Files.isRegularFile(png), "cropped region image should exist")
            val image = javax.imageio.ImageIO.read(png.toFile())
            val scale = 150f / 72f
            val expectedWidth = (612 * scale / 2).toInt()
            val expectedHeight = (792 * scale / 2).toInt()
            assertTrue(kotlin.math.abs(image.width - expectedWidth) <= expectedWidth / 20 + 6, "width ${image.width} != $expectedWidth")
            assertTrue(kotlin.math.abs(image.height - expectedHeight) <= expectedHeight / 20 + 6, "height ${image.height} != $expectedHeight")
            val markdown = Files.readString(dir.resolve("fig.md"))
            assertTrue(markdown.contains("![red box](image-p1-1.png)"))
            assertFalse(markdown.contains("<!-- img"))
            val images = parseObject(response.body)["image_files"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf(png.toString()), images)
        }
    }

    @Test
    fun skipsImageExtractionWhenIncludeImagesFalse() {
        val dir = Files.createTempDirectory("codex-doc-nocrop")
        val pdf = dir.resolve("fig.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
            doc.save(pdf.toFile())
        }
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.done","text":"![fig](image-p1-1.png)\n<!-- img page=1 0.0 0.0 0.5 0.5 -->"}""",
                """{"type":"response.completed","response":{"id":"resp_doc"}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.documentToMarkdown(localFile = pdf, includeImages = false)

            assertFalse(response.isError)
            assertFalse(Files.isRegularFile(dir.resolve("image-p1-1.png")))
            val markdown = Files.readString(dir.resolve("fig.md"))
            assertFalse(markdown.contains("<!-- img"))
            assertFalse(markdown.contains("](image-p1-1.png)"))
            assertTrue(markdown.contains("**Figure.** fig"))
        }
    }

    @Test
    fun postsConfigurableWebSearchOptionsAndReturnsSourceMetadata() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.done","text":"answer with source"}""",
                """{"type":"response.output_item.done","item":{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"OpenAI docs","sources":[{"url":"https://openai.com","title":"OpenAI"}]}}}""",
                """{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"output_text","text":"answer","annotations":[{"type":"url_citation","start_index":0,"end_index":6,"url":"https://openai.com","title":"OpenAI"}]}]}}""",
                """{"type":"response.completed","response":{"id":"resp_1"}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.webSearch(
                query = "OpenAI docs",
                searchContextSize = "HIGH",
                includeSources = true,
                externalWebAccess = false,
                allowedDomains = "OpenAI.com,docs.openai.com",
                blockedDomains = "reddit.com",
            )

            assertFalse(response.isError)
            val responseBody = parseObject(response.body)
            assertEquals("answer with source", responseBody["output"]!!.jsonPrimitive.content)
            assertEquals("resp_1", responseBody["response_id"]!!.jsonPrimitive.content)
            val webSearchCall = responseBody["web_search_calls"]!!.jsonArray[0].jsonObject
            assertEquals("ws_1", webSearchCall["id"]!!.jsonPrimitive.content)
            assertEquals(
                "https://openai.com",
                webSearchCall["action"]!!.jsonObject["sources"]!!.jsonArray[0].jsonObject["url"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "https://openai.com",
                responseBody["annotations"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content,
            )

            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            val body = parseObject(request.body)
            assertEquals(
                "web_search_call.action.sources",
                body["include"]!!.jsonArray[0].jsonPrimitive.content,
            )
            val tool = body["tools"]!!.jsonArray[0].jsonObject
            assertFalse(tool["external_web_access"]!!.jsonPrimitive.boolean)
            assertEquals("high", tool["search_context_size"]!!.jsonPrimitive.content)
            val filters = tool["filters"]!!.jsonObject
            assertEquals("openai.com", filters["allowed_domains"]!!.jsonArray[0].jsonPrimitive.content)
            assertEquals("docs.openai.com", filters["allowed_domains"]!!.jsonArray[1].jsonPrimitive.content)
            assertEquals("reddit.com", filters["blocked_domains"]!!.jsonArray[0].jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsInvalidWebSearchDomainFiltersBeforeCallingUpstream() {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.webSearch(
                query = "OpenAI docs",
                allowedDomains = "https://openai.com",
            )

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("Invalid Codex web search options"))
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun postsImageGenerationToCodexResponsesEndpointWithQuotaAuth(@TempDir tempDir: Path) {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig_1","status":"generating","revised_prompt":"draw a tiny robot","result":"$TEST_PNG_BASE64"}}""",
                """{"type":"response.completed","response":{"id":"resp_1","tool_usage":{"image_gen":{"total_tokens":12}}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot", baseDirectory = tempDir)

            assertFalse(response.isError)
            val responseBody = parseObject(response.body)
            val targetFile = Path.of(responseBody["output_file"]!!.jsonPrimitive.content)
            assertEquals(tempDir.toAbsolutePath().normalize(), targetFile.parent)
            assertTrue(targetFile.fileName.toString().matches(Regex("image-[0-9a-f-]{36}\\.png")))
            assertEquals("png", responseBody["format"]!!.jsonPrimitive.content)
            assertEquals(
                "draw a tiny robot",
                responseBody["revised_prompt"]!!.jsonPrimitive.content,
            )
            assertFalse("data" in responseBody)
            assertFalse("b64_json" in responseBody.toString())
            assertTrue(Files.exists(targetFile))
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/backend-api/codex/responses", request.path)
            assertEquals("Bearer codex-token", request.firstHeader("Authorization"))
            assertEquals("account-1", request.firstHeader("chatgpt-account-id"))
            assertNull(request.firstHeader("version"))
            assertNull(request.firstHeader("OpenAI-Beta"))

            val body = parseObject(request.body)
            assertEquals("gpt-5.6-luna", body["model"]!!.jsonPrimitive.content)
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            assertEquals(
                "draw a tiny robot",
                body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0]
                    .jsonObject["text"]!!.jsonPrimitive.content,
            )
            val tool = body["tools"]!!.jsonArray[0].jsonObject
            assertEquals("image_generation", tool["type"]!!.jsonPrimitive.content)
            assertEquals("png", tool["output_format"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun defaultImageFileNamesAreUnique() {
        assertNotEquals(DefaultOutputFiles.image(), DefaultOutputFiles.image())
    }

    @Test
    fun requiresImageTargetFileWhenNoProjectDirectory() {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot")

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("targetFile"))
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun writesImageGenerationToTargetFile(@TempDir tempDir: Path) {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig_1","status":"generating","revised_prompt":"draw a tiny robot","result":"$TEST_PNG_BASE64"}}""",
                """{"type":"response.completed","response":{"id":"resp_1","tool_usage":{"image_gen":{"total_tokens":12}}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot", "robot.png", tempDir)

            assertFalse(response.isError)
            val responseBody = parseObject(response.body)
            val targetFile = tempDir.resolve("robot.png")
            assertEquals(targetFile.toString(), responseBody["output_file"]!!.jsonPrimitive.content)
            assertEquals("png", responseBody["format"]!!.jsonPrimitive.content)
            assertTrue(responseBody["bytes"]!!.jsonPrimitive.long > 0)
            assertEquals("draw a tiny robot", responseBody["revised_prompt"]!!.jsonPrimitive.content)
            assertFalse("data" in responseBody)
            assertTrue(Files.exists(targetFile))
            val signature = Files.newInputStream(targetFile).use { it.readNBytes(8).toList() }
            assertEquals(listOf(137, 80, 78, 71, 13, 10, 26, 10), signature.map { it.toInt() and 0xff })

            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/backend-api/codex/responses", request.path)
        }
    }

    @Test
    fun rejectsUnsupportedImageFileFormatBeforeCallingUpstream(@TempDir tempDir: Path) {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot", "robot.txt", tempDir)

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("Unsupported image format 'txt'"))
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun rejectsAbsoluteImageTargetBeforeCallingUpstream(@TempDir tempDir: Path) {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot", tempDir.resolve("robot.png").toString(), tempDir)

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("must be relative"))
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun rejectsImageTargetOutsideProjectBeforeCallingUpstream(@TempDir tempDir: Path) {
        TestUpstream().use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.imageGeneration("draw a tiny robot", "../robot.png", tempDir)

            assertTrue(response.isError)
            assertTrue(parseObject(response.body)["error"]!!.jsonPrimitive.content.contains("inside the project"))
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun reportsResponsesFailedEventAsError() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.failed","response":{"error":{"message":"usage limit reached"}}}""",
            ),
        ).use { upstream ->
            val client = newClient(upstream.baseUri)

            val response = client.webSearch("OpenAI news")

            assertTrue(response.isError)
            assertEquals("usage limit reached", parseObject(response.body)["error"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun refreshesTokenAndRetriesOnceAfterUpstream401() {
        TestUpstream(
            responseBody = sse(
                """{"type":"response.output_text.delta","delta":"search result"}""",
            ),
            failFirstRequests = 1,
            failStatus = 401,
        ).use { upstream ->
            val tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
            var refreshedWith: String? = null
            val client = CodexMcpClient(
                accessTokenProvider = { tokens.first() },
                accountIdProvider = { "account-1" },
                tokenRefresher = { stale ->
                    refreshedWith = stale
                    if (tokens.size > 1) tokens.removeFirst()
                    tokens.first()
                },
                httpClient = httpClient,
                upstreamBaseUri = upstream.baseUri,
            )

            val response = client.webSearch("OpenAI news")

            assertFalse(response.isError)
            assertEquals("stale-token", refreshedWith)
            val first = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("Bearer stale-token", first.firstHeader("Authorization"))
            val retry = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("Bearer fresh-token", retry.firstHeader("Authorization"))
        }
    }

    @Test
    fun postsTranscriptionToCodexAudioEndpoint() {
        TestUpstream(responseBody = """{"text":"hello"}""").use { upstream ->
            val dir = Files.createTempDirectory("codex-stt")
            val audio = dir.resolve("clip.wav")
            Files.write(audio, byteArrayOf(1, 2, 3, 4))
            val client = newClient(upstream.baseUri)

            val response = client.transcribe(localFile = audio, language = "en")

            assertFalse(response.isError)
            assertEquals("hello", parseObject(response.body)["text"]!!.jsonPrimitive.content)
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/backend-api/codex/audio/transcriptions", request.path)
            assertTrue(request.body.contains("gpt-transcribe"))
            assertTrue(request.body.contains("clip.wav"))
            assertTrue(request.body.contains("en"))
        }
    }

    @Test
    fun writesSpeechAudioFromCodexSpeechEndpoint(@TempDir tempDir: Path) {
        val audioBytes = byteArrayOf(9, 8, 7, 6)
        TestUpstream(responseBytes = audioBytes).use { upstream ->
            val client = newClient(upstream.baseUri)
            val target = "out/hi.mp3"

            val response = client.synthesize("Hello there", targetFile = target, baseDirectory = tempDir)

            assertFalse(response.isError)
            assertEquals(tempDir.resolve(target).toString(), parseObject(response.body)["output_file"]!!.jsonPrimitive.content)
            assertEquals(audioBytes.toList(), Files.readAllBytes(tempDir.resolve(target)).toList())
            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/backend-api/codex/audio/speech", request.path)
            val body = parseObject(request.body)
            assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
            assertEquals("coral", body["voice"]!!.jsonPrimitive.content)
            assertEquals("Hello there", body["input"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun reportsLoginRequiredWithoutCallingUpstream() {
        TestUpstream().use { upstream ->
            val client = CodexMcpClient(
                accessTokenProvider = { null },
                accountIdProvider = { "account-1" },
                httpClient = httpClient,
                upstreamBaseUri = upstream.baseUri,
            )

            val response = client.webSearch("OpenAI news")

            assertTrue(response.isError)
            assertEquals(
                "OpenAI login required: log in on the OpenAI settings tab, then retry.",
                parseObject(response.body)["error"]!!.jsonPrimitive.content,
            )
            assertNull(upstream.requests.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun newClient(upstreamBaseUri: URI): CodexMcpClient {
        return CodexMcpClient(
            accessTokenProvider = { "codex-token" },
            accountIdProvider = { "account-1" },
            httpClient = httpClient,
            upstreamBaseUri = upstreamBaseUri,
        )
    }

    private class TestUpstream(
        private val responseBody: String = sse("""{"type":"response.output_text.delta","delta":"ok"}"""),
        private val responseBytes: ByteArray? = null,
        private val responseStatus: Int = 200,
        private val failFirstRequests: Int = 0,
        private val failStatus: Int = 503,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val failing = requestCount.incrementAndGet() <= failFirstRequests
                val response = when {
                    failing -> "{\"detail\":\"transient upstream failure\"}".toByteArray(Charsets.UTF_8)
                    responseBytes != null -> responseBytes
                    else -> responseBody.toByteArray(Charsets.UTF_8)
                }
                exchange.responseHeaders.set("Content-Type", if (responseBytes != null) "audio/mpeg" else "text/event-stream")
                exchange.sendResponseHeaders(if (failing) failStatus else responseStatus, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/backend-api/codex")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }

    private fun parseObject(value: String) = JsonSupport.json.parseToJsonElement(value).jsonObject

    private companion object {
        const val TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        val httpClient: HttpClient = HttpClient.newHttpClient()

        fun sse(vararg payloads: String): String {
            return payloads.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }
        }
    }
}
