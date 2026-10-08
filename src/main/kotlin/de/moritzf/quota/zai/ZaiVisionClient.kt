package de.moritzf.quota.zai

import de.moritzf.quota.shared.VisionChat
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** Vision question-answering through Z.ai GLM chat completions with the stored API key. */
open class ZaiVisionClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val chatCompletionsUri: URI = CHAT_COMPLETIONS_URI,
) {
    open fun ask(
        apiKey: String,
        imageUrl: String? = null,
        localFile: Path? = null,
        prompt: String,
        model: String,
    ): String {
        val token =
            apiKey.trim().ifBlank {
                throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
            }
        val trimmedPrompt =
            prompt.trim().ifBlank { throw ZaiQuotaException("Image prompt is required.") }
        val imageContent =
            VisionChat.chatImageContent(imageUrl, localFile)
                ?: throw ZaiQuotaException("Provide an image URL or a local image file.")
        val selectedModel =
            model.trim().ifBlank {
                throw ZaiQuotaException("Select a Z.ai vision model in settings.")
            }
        val response =
            send(
                postJson(
                    token,
                    chatCompletionsUri,
                    VisionChat.chatRequestJson(selectedModel, imageContent, trimmedPrompt),
                )
            )
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, body)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException(
                "Z.ai image analysis failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        return VisionChat.chatAnswer(body)
            ?: throw ZaiQuotaException("Z.ai image analysis returned no output.", status, body)
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
        private val CHAT_COMPLETIONS_URI =
            URI.create("https://api.z.ai/api/paas/v4/chat/completions")

        fun createDefault(): ZaiVisionClient = ZaiVisionClient()

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

        private fun postJson(apiKey: String, uri: URI, body: String): HttpRequest {
            return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
    }
}
