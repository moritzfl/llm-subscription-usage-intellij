package de.moritzf.quota.supergrok

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SuperGrokImagineClientTest {
    @Test
    fun postsImageGenerationsWithSingleUrlImageByDefault() {
        TestGrokServer(
            responseBody = """{"created":1,"data":[{"url":"https://cdn.example/cat.png"}]}""",
        ).use { server ->
            val client = SuperGrokImagineClient(httpClient = httpClient, baseUri = server.baseUri)

            val result = client.generateImage(
                accessToken = "grok-token",
                prompt = "  a cat in space  ",
            )

            assertTrue(result.contains("https://cdn.example/cat.png"))

            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/images/generations", request.path)
            assertEquals("Bearer grok-token", request.firstHeader("Authorization"))
            val body = parseObject(request.body)
            assertEquals(SuperGrokImagineClient.DEFAULT_IMAGE_MODEL, body["model"]!!.jsonPrimitive.content)
            assertEquals("a cat in space", body["prompt"]!!.jsonPrimitive.content)
            assertEquals(1, body["n"]!!.jsonPrimitive.int)
            assertEquals("url", body["response_format"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun writesImageToRelativeProjectPathByDownloadingUrl() {
        val png = tinyPng()
        TestGrokServer(files = mapOf("/cat.png" to png)).use { server ->
            server.responseBody = """{"created":1,"data":[{"url":"${server.baseUri}cat.png"}]}"""
            val client = SuperGrokImagineClient(httpClient = httpClient, baseUri = server.baseUri)
            val projectDir = Files.createTempDirectory("supergrok-imagine-test")
            try {
                val result = client.generateImage(
                    accessToken = "grok-token",
                    prompt = "logo",
                    targetFile = "out/logo.png",
                    baseDirectory = projectDir,
                )
                val response = parseObject(result)
                val output = response["output_file"]!!.jsonPrimitive.content
                assertTrue(output.endsWith("out/logo.png") || output.endsWith("out\\logo.png"))
                val written = projectDir.resolve("out/logo.png")
                assertTrue(Files.exists(written))
                assertEquals(png.toList(), Files.readAllBytes(written).toList())
                assertTrue(response["bytes"]!!.jsonPrimitive.int > 0)

                val generate = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
                val body = parseObject(generate.body)
                assertEquals("url", body["response_format"]!!.jsonPrimitive.content)
                assertEquals(1, body["n"]!!.jsonPrimitive.int)
                val download = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
                assertEquals("GET", download.method)
                assertEquals("/cat.png", download.path)
            } finally {
                projectDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun rejectsBlankImagePromptBeforeNetwork() {
        TestGrokServer().use { server ->
            val client = SuperGrokImagineClient(httpClient = httpClient, baseUri = server.baseUri)
            val exception = assertFailsWith<SuperGrokQuotaException> {
                client.generateImage("token", "   ")
            }
            assertEquals("Image prompt is required.", exception.message)
            assertNull(server.requests.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun editImagePostsJsonNotMultipart() {
        TestGrokServer(
            responseBody = """{"data":[{"url":"https://imgen.x.ai/edit.png"}]}""",
        ).use { server ->
            val client = SuperGrokImagineClient(httpClient = httpClient, baseUri = server.baseUri)
            val result = client.editImage("token", "make it a sketch", imageUrl = "https://example.test/in.png")
            assertTrue(result.contains("imgen.x.ai"))
            val request = assertNotNull(server.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/images/edits", request.path)
            val payload = JsonSupport.json.parseToJsonElement(request.body).jsonObject
            assertEquals("https://example.test/in.png", payload["image"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertEquals("image_url", payload["image"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun editImageRejectsMissingSource() {
        val exception = assertFailsWith<SuperGrokQuotaException> {
            SuperGrokImagineClient.sourceImageUrl(null, null)
        }
        assertEquals("Provide imageUrl or a local image path.", exception.message)
    }

    @Test
    fun videoGenerationPollsUntilDone() {
        MultiResponseGrokServer(
            postBody = """{"request_id":"vid-1","status":"pending"}""",
            getBodies = listOf(
                """{"request_id":"vid-1","status":"pending"}""",
                """{"request_id":"vid-1","status":"done","video":{"url":"https://cdn.example/v.mp4"}}""",
            ),
        ).use { server ->
            val client = SuperGrokImagineClient(
                httpClient = httpClient,
                baseUri = server.baseUri,
                sleeper = { /* no sleep in tests */ },
            )
            val result = client.generateVideo(
                accessToken = "grok-token",
                prompt = "waterfall pan",
                model = "grok-imagine-video",
                duration = 12,
                imageUrl = "https://example.com/still.png",
                waitForCompletion = true,
                pollTimeoutSeconds = 30,
            )
            assertTrue(result.contains("cdn.example/v.mp4"))
            assertTrue(server.requests.any { it.method == "POST" && it.path.endsWith("/videos/generations") })
            assertTrue(server.requests.any { it.method == "GET" && it.path.contains("/videos/vid-1") })
            val post = server.requests.first { it.method == "POST" }
            val body = parseObject(post.body)
            assertEquals("grok-imagine-video", body["model"]!!.jsonPrimitive.content)
            assertEquals("waterfall pan", body["prompt"]!!.jsonPrimitive.content)
            assertEquals(12, body["duration"]!!.jsonPrimitive.int)
            assertEquals("https://example.com/still.png", body["image"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun writesVideoToTargetFileByDownloadingUrl() {
        val video = byteArrayOf(0, 1, 2, 3)
        TestGrokServer(files = mapOf("/v.mp4" to video)).use { server ->
            server.responseBody = """{"request_id":"vid-1","status":"done","video":{"url":"${server.baseUri}v.mp4"}}"""
            val client = SuperGrokImagineClient(
                httpClient = httpClient,
                baseUri = server.baseUri,
                sleeper = { },
            )
            val dir = Files.createTempDirectory("grok-vid")
            try {
                val result = client.generateVideo(
                    accessToken = "grok-token",
                    prompt = "clip",
                    targetFile = "out/clip.mp4",
                    baseDirectory = dir,
                )
                val written = parseObject(result)
                val output = dir.resolve("out/clip.mp4")
                assertEquals(output.toString(), written["output_file"]!!.jsonPrimitive.content)
                assertEquals(video.toList(), Files.readAllBytes(output).toList())
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun videoGenerationCanReturnRequestImmediately() {
        MultiResponseGrokServer(
            postBody = """{"request_id":"vid-9","status":"pending"}""",
            getBodies = emptyList(),
        ).use { server ->
            val client = SuperGrokImagineClient(httpClient = httpClient, baseUri = server.baseUri)
            val result = client.generateVideo(
                accessToken = "token",
                prompt = "clip",
                waitForCompletion = false,
            )
            assertTrue(result.contains("vid-9"))
            assertEquals(1, server.requests.size)
            assertEquals("POST", server.requests.single().method)
        }
    }

    private fun tinyPng(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0xFF0000)
        image.setRGB(1, 1, 0x00FF00)
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private fun parseObject(value: String) = JsonSupport.json.parseToJsonElement(value).jsonObject

    private class TestGrokServer(
        var responseBody: String = """{"data":[]}""",
        private val files: Map<String, ByteArray> = emptyMap(),
        private val responseStatus: Int = 200,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val path = exchange.requestURI.rawPath
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = path,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val file = files[path]
                if (file != null) {
                    exchange.responseHeaders.set("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, file.size.toLong())
                    exchange.responseBody.use { output -> output.write(file) }
                    return@createContext
                }
                val response = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(responseStatus, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private class MultiResponseGrokServer(
        private val postBody: String,
        private val getBodies: List<String>,
    ) : AutoCloseable {
        val requests = mutableListOf<CapturedRequest>()
        private val getIndex = AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val captured = CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                synchronized(requests) { requests += captured }
                val responseText = when (exchange.requestMethod) {
                    "POST" -> postBody
                    "GET" -> {
                        val idx = getIndex.getAndIncrement().coerceAtMost(getBodies.lastIndex.coerceAtLeast(0))
                        getBodies.getOrElse(idx) { getBodies.lastOrNull() ?: """{"status":"pending"}""" }
                    }
                    else -> """{"error":"method"}"""
                }
                val response = responseText.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { output -> output.write(response) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/")
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

    private companion object {
        val httpClient: HttpClient = HttpClient.newHttpClient()
    }
}
