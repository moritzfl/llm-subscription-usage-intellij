package de.moritzf.proxy.media

import de.moritzf.proxy.subscription.SubscriptionProxyServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaProxyHandlerTest {
    @TempDir
    lateinit var logDir: Path

    @Test
    fun jsonBodyPreservesDefaultUtf8AndExplicitCharsets() = withProxy { port, operations ->
        for (charset in listOf(Charsets.UTF_8, Charsets.ISO_8859_1, Charsets.UTF_16LE)) {
            val contentType = if (charset == Charsets.UTF_8) "application/json" else "application/json; charset=${charset.name()}"
            val response = post(port, "/v1/images/generations", contentType,
                """{"model":"sg-grok-imagine-image","prompt":"Grüße aus Köln"}""".toByteArray(charset))
            assertEquals(200, response.statusCode(), response.body())
            assertEquals("Grüße aus Köln", operations.prompts.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun emptyAndMalformedJsonStillReturnBadRequest() = withProxy { port, operations ->
        for (body in listOf("", "{", "[]")) {
            val response = post(port, "/v1/images/generations", "application/json", body.toByteArray())
            assertEquals(400, response.statusCode(), response.body())
        }
        assertTrue(operations.prompts.isEmpty())
    }

    @Test
    fun multipartPreservesBinaryAudioAndFieldsFollowingFile() = withProxy { port, operations ->
        val boundary = "quota-media-test-boundary"
        val audio = ByteArray(128 * 1024) { (it % 256).toByte() }
        val body = (
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
            ).toByteArray() + audio + (
            "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\noa-gpt-transcribe" +
                "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nde" +
                "\r\n--$boundary--\r\n"
            ).toByteArray()
        val response = post(port, "/v1/audio/transcriptions", "multipart/form-data; boundary=$boundary", body)
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("transcribed"))
        val upload = assertNotNull(operations.uploads.poll(2, TimeUnit.SECONDS))
        assertEquals("openai", upload.provider)
        assertEquals("gpt-transcribe", upload.model)
        assertEquals("sample.wav", upload.filename)
        assertEquals("de", upload.language)
        assertContentEquals(audio, upload.audio)
    }

    private fun withProxy(block: (Int, RecordingOperations) -> Unit) {
        val operations = RecordingOperations()
        val server = SubscriptionProxyServer(
            port = 0,
            localApiKeyProvider = { "media-test-key" },
            providers = { emptyList() },
            requestLogDir = logDir.toString(),
            mediaOperations = operations,
        )
        try {
            server.start()
            block(runBlocking { server.boundPort() }, operations)
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, path: String, contentType: String, body: ByteArray): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer media-test-key")
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
    }

    private data class Upload(val provider: String, val model: String, val audio: ByteArray, val filename: String, val language: String?)

    private class RecordingOperations : MediaOperations by UnsupportedMediaOperations() {
        val prompts = LinkedBlockingQueue<String>()
        val uploads = LinkedBlockingQueue<Upload>()

        override fun generateImageUrl(providerId: String, model: String, prompt: String): String {
            prompts.add(prompt)
            return "https://example.com/image.png"
        }

        override fun transcribe(providerId: String, model: String, audio: ByteArray, filename: String, language: String?): String {
            uploads.add(Upload(providerId, model, audio, filename, language))
            return """{"text":"transcribed"}"""
        }
    }
}
