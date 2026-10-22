package de.moritzf.quota.minimax

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.VisionChat
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Image questions through the Token Plan's image understanding endpoint, as MiniMax's CLI uses it.
 * The endpoint has no model choice and takes the image inline as a data URI.
 */
open class MiniMaxVisionClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val globalApiHost: URI = GLOBAL_API_HOST,
    private val cnApiHost: URI = CN_API_HOST,
) {
    open fun ask(
        apiKey: String,
        region: MiniMaxRegion,
        imageUrl: String? = null,
        localFile: Path? = null,
        prompt: String,
    ): String {
        val token =
            apiKey.trim().ifBlank {
                throw MiniMaxQuotaException(
                    "MiniMax API key missing. Add a MiniMax API key in settings."
                )
            }
        val question =
            prompt.trim().ifBlank { throw MiniMaxQuotaException("Image prompt is required.") }
        val image =
            imageUrl?.trim()?.takeIf { it.startsWith("data:", ignoreCase = true) }
                ?: localFile?.let { VisionChat.imageDataUrl(it)?.first }
                ?: throw MiniMaxQuotaException(
                    if (imageUrl.isNullOrBlank() && localFile == null)
                        "Provide a local image file in localFile."
                    else "MiniMax vision needs a local image file in localFile."
                )
        val body = buildJsonObject {
            put("prompt", question)
            put("image_url", image)
        }
        val host = if (region == MiniMaxRegion.CN) cnApiHost else globalApiHost
        val request =
            HttpRequest.newBuilder()
                .uri(host.resolve(VLM_PATH))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer $token")
                .header("MM-API-Source", "Minimax-MCP")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        val response = send(request)
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw MiniMaxQuotaException(
                "Session expired. Check your MiniMax subscription key.",
                status,
                responseBody,
            )
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException(
                "MiniMax image analysis failed (HTTP $status). Try again later.",
                status,
                responseBody,
            )
        }
        return parseAnswer(responseBody)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MiniMaxQuotaException(
                "Request failed. Check your connection.",
                0,
                null,
                exception,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MiniMaxQuotaException(
                "Request failed. Check your connection.",
                0,
                null,
                exception,
            )
        }
    }

    companion object {
        /** The only choice for the vision model setting; the endpoint picks the model itself. */
        const val MODEL = "coding-plan-vlm"
        private const val VLM_PATH = "v1/coding_plan/vlm"
        private val GLOBAL_API_HOST = URI.create("https://api.minimax.io/")
        private val CN_API_HOST = URI.create("https://api.minimaxi.com/")
        private val AUTH_STATUS_CODES = setOf(1004, 2049)

        fun createDefault(): MiniMaxVisionClient = MiniMaxVisionClient()

        internal fun parseAnswer(body: String): String {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                    ?: throw MiniMaxQuotaException(
                        "MiniMax image analysis returned no output.",
                        200,
                        body,
                    )
            val baseResp = root["base_resp"] as? JsonObject
            val statusCode = (baseResp?.get("status_code") as? JsonPrimitive)?.intOrNull ?: 0
            if (statusCode != 0) {
                val message =
                    (baseResp?.get("status_msg") as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (statusCode in AUTH_STATUS_CODES) {
                    throw MiniMaxQuotaException(
                        "Session expired. Check your MiniMax subscription key.",
                        401,
                        body,
                    )
                }
                throw MiniMaxQuotaException(
                    "MiniMax image analysis failed: ${message.ifBlank { statusCode.toString() }}",
                    statusCode,
                    body,
                )
            }
            return (root["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw MiniMaxQuotaException(
                    "MiniMax image analysis returned no output.",
                    200,
                    body,
                )
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}
