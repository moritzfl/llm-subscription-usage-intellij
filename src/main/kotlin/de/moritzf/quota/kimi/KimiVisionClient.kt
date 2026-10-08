package de.moritzf.quota.kimi

import de.moritzf.quota.shared.VisionChat
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** Vision question-answering through the Kimi coding chat completions with the stored login. */
open class KimiVisionClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val chatEndpoint: URI = CHAT_ENDPOINT,
    tokenEndpoint: URI = TOKEN_ENDPOINT,
    private val deviceId: String = KimiDeviceHeaders.processDeviceId,
) {
    private val credentialRefresher = KimiCredentialRefresher(httpClient, tokenEndpoint, deviceId)

    open fun ask(
        credentials: KimiCredentials,
        imageUrl: String? = null,
        localFile: Path? = null,
        prompt: String,
        model: String,
    ): KimiVisionResult {
        val trimmedPrompt =
            prompt.trim().ifBlank { throw KimiQuotaException("Image prompt is required.") }
        val imageContent =
            VisionChat.chatImageContent(imageUrl, localFile)
                ?: throw KimiQuotaException("Provide an image URL or a local image file.")
        val selectedModel =
            model.trim().ifBlank {
                throw KimiQuotaException("Select a Kimi vision model in settings.")
            }

        var usableCredentials = credentialRefresher.refreshIfNeeded(credentials)
        var accessToken =
            usableCredentials.accessToken.ifBlank {
                throw KimiQuotaException("Kimi login required. Log in from settings.")
            }

        var response = send(chatRequest(accessToken, selectedModel, imageContent, trimmedPrompt))
        if (response.statusCode().isUnauthorized()) {
            usableCredentials =
                credentialRefresher.refresh(usableCredentials)
                    ?: throw KimiQuotaException(
                        "Session expired. Log in to Kimi again from settings.",
                        response.statusCode(),
                        response.body(),
                    )
            accessToken =
                usableCredentials.accessToken.ifBlank {
                    throw KimiQuotaException("Kimi login required. Log in from settings.")
                }
            response = send(chatRequest(accessToken, selectedModel, imageContent, trimmedPrompt))
        }
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException(
                "Session expired. Log in to Kimi again from settings.",
                status,
                body,
            )
        }
        if (status !in 200..299) {
            throw KimiQuotaException(
                "Kimi image analysis failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        val answer =
            VisionChat.chatAnswer(body)
                ?: throw KimiQuotaException("Kimi image analysis returned no output.", status, body)
        return KimiVisionResult(answer, usableCredentials)
    }

    private fun chatRequest(
        accessToken: String,
        model: String,
        imageContent: kotlinx.serialization.json.JsonObject,
        prompt: String,
    ): HttpRequest {
        val builder =
            HttpRequest.newBuilder()
                .uri(chatEndpoint)
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
        KimiDeviceHeaders.all(deviceId).forEach { (key, value) -> builder.header(key, value) }
        return builder
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    VisionChat.chatRequestJson(model, imageContent, prompt)
                )
            )
            .build()
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw KimiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KimiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun Int.isUnauthorized(): Boolean = this == 401 || this == 403

    data class KimiVisionResult(
        val answer: String,
        val credentials: KimiCredentials,
    )

    companion object {
        private val CHAT_ENDPOINT = URI.create("https://api.kimi.com/coding/v1/chat/completions")
        private val TOKEN_ENDPOINT = URI.create("https://auth.kimi.com/api/oauth/token")
        private const val USER_AGENT = "KimiCLI/1.40.0"

        fun createDefault(deviceId: String = KimiDeviceHeaders.processDeviceId): KimiVisionClient =
            KimiVisionClient(deviceId = deviceId)

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
        }
    }
}
