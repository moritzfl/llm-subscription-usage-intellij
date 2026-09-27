package de.moritzf.quota.mistral

import de.moritzf.quota.shared.VisionChat
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** Vision question-answering through Mistral chat completions (Pixtral models) with the stored API key. */
open class MistralVisionClient(
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
        val token = apiKey.trim().ifBlank {
            throw MistralQuotaException("Mistral API key missing. Add a Mistral API key in settings.")
        }
        val trimmedPrompt = prompt.trim().ifBlank { throw MistralQuotaException("Image prompt is required.") }
        val imageContent = VisionChat.chatImageContent(imageUrl, localFile)
            ?: throw MistralQuotaException("Provide an image URL or a local image file.")
        val selectedModel = model.trim().ifBlank { throw MistralQuotaException("Select a Mistral vision model in settings.") }
        val response = send(postJson(token, chatCompletionsUri, VisionChat.chatRequestJson(selectedModel, imageContent, trimmedPrompt)))
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("API key invalid. Check your Mistral API key.", status, body)
        }
        if (status !in 200..299) {
            throw MistralQuotaException("Mistral image analysis failed (HTTP $status). Try again later.", status, body)
        }
        return VisionChat.chatAnswer(body)
            ?: throw MistralQuotaException("Mistral image analysis returned no output.", status, body)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        private val CHAT_COMPLETIONS_URI = URI.create("https://api.mistral.ai/v1/chat/completions")

        fun createDefault(): MistralVisionClient = MistralVisionClient()

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
