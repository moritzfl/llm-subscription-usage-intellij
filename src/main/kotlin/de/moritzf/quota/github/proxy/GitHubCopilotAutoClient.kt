package de.moritzf.quota.github.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.quota.github.GitHubQuotaException
import de.moritzf.quota.shared.JsonSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Uses GitHub's Auto decision and its request-bound authorization for Auto-only plans. */
internal class GitHubCopilotAutoClient(
    private val accessTokenProvider: () -> String?,
    private val tokenRefresher: (String?) -> String?,
    private val httpClient: HttpClient,
    private val baseUri: URI,
) {
    fun select(body: JsonObject): Selection {
        val hasImage = containsImageInput(body)
        val messages = body["messages"] as? JsonArray
        val user =
            messages?.lastOrNull { (it as? JsonObject)?.let { stringField(it, "role") } == "user" }
                as? JsonObject
        val content = user?.get("content")
        val prompt =
            when (content) {
                is JsonPrimitive -> content.contentOrNull.orEmpty()
                is JsonArray ->
                    content
                        .mapNotNull { (it as? JsonObject)?.let { stringField(it, "text") } }
                        .joinToString("\n")
                else -> ""
            }.ifBlank { if (hasImage) "Image input" else "" }
        if (prompt.isBlank())
            throw GitHubQuotaException("Copilot Auto needs a user message to select a model.", 400)
        val payload = JsonSupport.json.encodeToString(AutoRequest(prompt, hasImage))
        var token =
            accessTokenProvider().trimmedOrNull()
                ?: throw GitHubQuotaException("GitHub Copilot login required.", 401)
        var response = send(payload, token)
        if (response.statusCode() == 401) {
            tokenRefresher(token)
                .trimmedOrNull()
                ?.takeIf { it != token }
                ?.let {
                    token = it
                    response = send(payload, token)
                }
        }
        if (response.statusCode() !in 200..299) {
            throw GitHubQuotaException(
                "Copilot Auto selection failed (HTTP ${response.statusCode()}).",
                response.statusCode(),
            )
        }
        // This response contains a credential. Never include it or decoder errors in diagnostics.
        val selection =
            try {
                JsonSupport.json.decodeFromString<AutoResponse>(response.body())
            } catch (_: Exception) {
                throw GitHubQuotaException("Copilot returned an invalid Auto selection.", 502)
            }
        if (
            selection.sessionToken.isBlank() ||
                selection.expiresAt <= System.currentTimeMillis() / 1000 ||
                stringField(selection.model, "id") == null
        ) {
            throw GitHubQuotaException("Copilot returned an invalid Auto selection.", 502)
        }
        return Selection(token, selection.sessionToken, selection.model)
    }

    private fun send(payload: String, token: String): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI.create(baseUri.toString().trimEnd('/') + "/auto"))
                .timeout(Duration.ofSeconds(10))
        githubCopilotHeaders(token).forEach { (key, value) -> builder.header(key, value) }
        builder.setHeader("X-GitHub-Api-Version", AUTO_API_VERSION)
        builder.setHeader("Content-Type", JsonHelper.JSON_CONTENT_TYPE)
        return httpClient.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    class Selection(val githubToken: String, sessionToken: String, val model: JsonObject) {
        val headers =
            mapOf(
                "Copilot-Session-Token" to sessionToken,
                "X-GitHub-Api-Version" to AUTO_API_VERSION,
            )
    }

    @Serializable
    private data class AutoRequest(
        val prompt: String,
        @SerialName("has_image") val hasImage: Boolean,
    )

    @Serializable
    private data class AutoResponse(
        @SerialName("session_token") val sessionToken: String,
        @SerialName("expires_at") val expiresAt: Long,
        @SerialName("selected_model") val model: JsonObject,
    )

    companion object {
        const val MODEL_ID = "auto"
        // Required by POST /auto; matches @vscode/copilot-api 0.5.2.
        private const val AUTO_API_VERSION = "2026-08-01"
    }
}
