package de.moritzf.quota.compatible

import de.moritzf.quota.shared.NativePdfDocument
import de.moritzf.quota.shared.NativePdfRoute
import de.moritzf.quota.shared.VisionChat
import de.moritzf.quota.shared.document.PdfPages
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** Explicit model selection enables image/PDF input even when /models has no capabilities. */
internal class CompatibleMediaClient(
    private val endpoint: CompatibleEndpoint,
    private val apiKey: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    fun document(model: String, source: Path, output: Path?): String {
        require(PdfPages.isPdf(source)) { "Select a readable PDF file." }
        requireModel(model)
        return NativePdfDocument.convert(NativePdfRoute.CHAT, model, source, output, ::postChat)
    }

    fun vision(
        model: String,
        prompt: String,
        source: Path? = null,
        imageUrl: String? = null,
    ): String {
        requireModel(model)
        require(prompt.isNotBlank()) { "Image prompt is required." }
        require((source != null) != !imageUrl.isNullOrBlank()) {
            "Pass one image: localFile or imageUrl."
        }
        val image =
            VisionChat.chatImageContent(imageUrl, source)
                ?: error("Select a readable image file or an http(s) image URL.")
        return checkNotNull(
            VisionChat.chatAnswer(postChat(VisionChat.chatRequestJson(model, image, prompt.trim())))
        )
    }

    private fun postChat(payload: String): String {
        val request =
            HttpRequest.newBuilder(endpoint.uri("chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", headers().getValue("Authorization"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Model request failed (HTTP ${response.statusCode()}). Check the model's image/PDF support."
        }
        check(!VisionChat.chatAnswer(response.body()).isNullOrBlank()) {
            "Provider returned no chat text. Check the model's image/PDF support."
        }
        return response.body()
    }

    private fun headers(): Map<String, String> {
        require(apiKey.isNotBlank()) { "Enter an API key." }
        return mapOf("Authorization" to "Bearer ${apiKey.trim()}")
    }

    private fun requireModel(model: String) {
        require(model.isNotBlank() && model != "-") {
            "Select a model in settings, or pass model=."
        }
    }
}
