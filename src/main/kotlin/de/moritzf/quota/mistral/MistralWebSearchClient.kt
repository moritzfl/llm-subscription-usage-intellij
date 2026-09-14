package de.moritzf.quota.mistral

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class MistralWebSearchClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val conversationsUri: URI = CONVERSATIONS_URI,
) {
    open fun webSearch(
        apiKey: String,
        query: String,
        model: String = DEFAULT_MODEL,
        premium: Boolean = false,
    ): String {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            throw MistralQuotaException("Search query is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw MistralQuotaException("Mistral API key missing. Add a Mistral API key in settings.")
        }
        val body = JsonSupport.json.encodeToString(
            MistralConversationRequestDto(
                model = model.trim().ifBlank { DEFAULT_MODEL },
                inputs = trimmedQuery,
                tools = listOf(MistralBuiltInToolDto(if (premium) "web_search_premium" else "web_search")),
            ),
        )
        val response = send(postJson(token, conversationsUri, body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw MistralQuotaException("Mistral web search failed (HTTP $status). Try again later.", status, responseBody)
        }
        return McpJson.providerJsonOrRaw(responseBody)
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
        const val DEFAULT_MODEL = "mistral-small-latest"
        private val CONVERSATIONS_URI = URI.create("https://api.mistral.ai/v1/conversations")

        fun createDefault(): MistralWebSearchClient = MistralWebSearchClient()

        internal fun conversationRequestJson(query: String, model: String, premium: Boolean): String {
            return JsonSupport.json.encodeToString(
                MistralConversationRequestDto(
                    model = model,
                    inputs = query,
                    tools = listOf(MistralBuiltInToolDto(if (premium) "web_search_premium" else "web_search")),
                ),
            )
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

        private fun postJson(apiKey: String, uri: URI, body: String): HttpRequest {
            return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
    }
}

@Serializable
internal data class MistralConversationRequestDto(
    val model: String,
    val inputs: String,
    val tools: List<MistralBuiltInToolDto>,
)

@Serializable
internal data class MistralBuiltInToolDto(
    val type: String,
)
