package de.moritzf.quota.shared

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class NativePdfPoster(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    fun convert(
        url: URI,
        headers: Map<String, String>,
        route: NativePdfRoute,
        model: String,
        source: Path,
        output: Path?,
        extraBody: JsonObject = JsonObject(emptyMap()),
    ): String {
        return NativePdfDocument.convert(route, model, source, output) { payload ->
            val merged = if (extraBody.isEmpty()) payload else JsonObject(extraBody + JsonSupport.json.parseToJsonElement(payload).jsonObject).toString()
            val builder = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(merged))
            headers.forEach { (name, value) -> builder.header(name, value) }
            val response = try {
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (exception: IOException) {
                error("Document request failed. Check your connection.")
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                error("Document request failed. Check your connection.")
            }
            if (response.statusCode() !in 200..299) {
                error("Document request failed (HTTP ${response.statusCode()}).")
            }
            response.body()
        }
    }
}
