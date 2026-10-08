package de.moritzf.quota.zai

import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.MultipartFilePublisher
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

open class ZaiAudioClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val transcriptionsUri: URI = TRANSCRIPTIONS_URI,
) {
    open fun transcribe(
        apiKey: String,
        audioUrl: String? = null,
        localFile: Path? = null,
        model: String = DEFAULT_MODEL,
    ): String {
        val token =
            apiKey.trim().ifBlank {
                throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
            }
        val path = localFile
        if (path == null || !Files.isRegularFile(path)) {
            if (audioUrl.isNullOrBlank()) {
                throw ZaiQuotaException("Provide a local audio file path.")
            }
            throw ZaiQuotaException("Z.ai speech-to-text requires a local audio file.")
        }
        val boundary = "----ZaiAudio${UUID.randomUUID().toString().replace("-", "")}"
        val response =
            send(
                HttpRequest.newBuilder()
                    .uri(transcriptionsUri)
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(
                        MultipartFilePublisher.of(
                            boundary,
                            listOf("model" to model.trim().ifBlank { DEFAULT_MODEL }),
                            path,
                        )
                    )
                    .build()
            )
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, body)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException(
                "Z.ai speech-to-text failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        return McpJson.providerJsonOrRaw(body)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "glm-asr-2512"
        private val TRANSCRIPTIONS_URI =
            URI.create("https://api.z.ai/api/paas/v4/audio/transcriptions")

        fun createDefault(): ZaiAudioClient = ZaiAudioClient()

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}
