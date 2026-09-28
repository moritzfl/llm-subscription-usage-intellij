package de.moritzf.quota.ollama

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Cloud model ids and their provider-declared image capability; null means discovery failed. */
internal class OllamaVisionModels(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val baseUri: URI = URI.create("https://ollama.com/"),
) {
    fun discover(apiKey: String): Map<String, Boolean?> {
        if (apiKey.isBlank()) return emptyMap()
        val response = httpClient.send(request("api/tags", apiKey).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Ollama model discovery failed (HTTP ${response.statusCode()})." }
        val ids = JsonSupport.json.decodeFromString<Tags>(response.body()).models.map { it.name }.distinct()
        val pending = ids.associateWith { id ->
            httpClient.sendAsync(
                request("api/show", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.json.encodeToString(ShowRequest(id))))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).handle { result, error ->
                if (error != null || result.statusCode() !in 200..299) null
                else runCatching {
                    JsonSupport.json.decodeFromString<ShowResponse>(result.body()).capabilities?.contains("vision")
                }.getOrNull()
            }
        }
        return pending.mapValues { (_, result) -> result.join() }
    }

    private fun request(path: String, apiKey: String) = HttpRequest.newBuilder(baseUri.resolve(path))
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .header("Accept", "application/json")

    @Serializable
    private data class Tags(val models: List<Tag> = emptyList())
    @Serializable
    private data class Tag(val name: String)
    @Serializable
    private data class ShowRequest(val model: String)
    @Serializable
    private data class ShowResponse(val capabilities: List<String>? = null)

    companion object {
        fun choices(models: Map<String, Boolean?>, saved: String?): List<String> =
            (models.filterValues { it == true }.keys + listOfNotNull(saved?.takeUnless { models[it] == false })).distinct()
    }
}
