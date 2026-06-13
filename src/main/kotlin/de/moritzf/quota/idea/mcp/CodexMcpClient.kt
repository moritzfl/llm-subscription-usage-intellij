package de.moritzf.quota.idea.mcp

import com.aiproxyoauth.auth.AuthRequiredException
import com.aiproxyoauth.config.ServerConfig
import com.aiproxyoauth.server.UpstreamErrorMapper
import com.aiproxyoauth.transport.CodexHttpClient
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import de.moritzf.quota.openai.proxy.QuotaCodexCredentialsProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

class CodexMcpClient(
    accessTokenProvider: () -> String?,
    accountIdProvider: () -> String?,
    tokenRefresher: (staleAccessToken: String?) -> String? = { null },
    httpClient: HttpClient = defaultHttpClient(),
    upstreamBaseUri: URI = OpenAiProxyServer.DEFAULT_UPSTREAM_BASE_URI,
) {
    private val upstreamErrorMapper = UpstreamErrorMapper()
    private val client = CodexHttpClient(
        serverConfig(upstreamBaseUri),
        httpClient,
        QuotaCodexCredentialsProvider(accessTokenProvider, accountIdProvider, tokenRefresher),
    )

    fun webSearch(query: String): CodexMcpResponse {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return CodexMcpResponse(errorJson("Search query is required."), true)
        }
        return postJson("/alpha/search", searchRequest(trimmedQuery).toString())
    }

    fun imageGeneration(prompt: String): CodexMcpResponse {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            return CodexMcpResponse(errorJson("Image prompt is required."), true)
        }
        return postJson("/images/generations", imageGenerationRequest(trimmedPrompt).toString())
    }

    private fun postJson(path: String, body: String): CodexMcpResponse {
        return try {
            val response = client.requestString(path, "POST", body, mapOf("Content-Type" to "application/json"))
            if (response.statusCode() in 200..299) {
                CodexMcpResponse(response.body(), false)
            } else {
                val mapped = upstreamErrorMapper.map(response.statusCode(), response.body())
                CodexMcpResponse(mapped.body(), true)
            }
        } catch (exception: AuthRequiredException) {
            CodexMcpResponse(errorJson(exception.message ?: "OpenAI login required."), true)
        } catch (exception: Exception) {
            val message = exception.message?.takeIf { it.isNotBlank() } ?: exception::class.java.simpleName
            CodexMcpResponse(errorJson(message), true)
        }
    }

    private fun searchRequest(query: String): JsonObject {
        return buildJsonObject {
            put("id", "mcp-search-${UUID.randomUUID()}")
            put("model", SEARCH_MODEL)
            put("input", "Search the web for: $query")
            putJsonObject("commands") {
                putJsonArray("search_query") {
                    add(buildJsonObject { put("q", query) })
                }
                put("response_length", "medium")
            }
            putJsonObject("settings") {
                putJsonArray("allowed_callers") { add("direct") }
                put("external_web_access", true)
            }
        }
    }

    private fun imageGenerationRequest(prompt: String): JsonObject {
        return buildJsonObject {
            put("prompt", prompt)
            put("model", IMAGE_MODEL)
            put("n", 1)
        }
    }

    data class CodexMcpResponse(val body: String, val isError: Boolean)

    companion object {
        private const val SEARCH_MODEL = "gpt-5.5"
        private const val IMAGE_MODEL = "gpt-image-2"

        fun createDefault(): CodexMcpClient {
            return CodexMcpClient(
                accessTokenProvider = { QuotaAuthService.getInstance().getAccessTokenBlocking(QuotaProviderType.OPEN_AI) },
                accountIdProvider = { QuotaAuthService.getInstance().getAccountId(QuotaProviderType.OPEN_AI) },
                tokenRefresher = { staleToken ->
                    QuotaAuthService.getInstance().forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleToken)
                },
            )
        }

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }

        private fun serverConfig(upstreamBaseUri: URI): ServerConfig {
            return ServerConfig(
                "127.0.0.1",
                1,
                listOf(SEARCH_MODEL),
                null,
                upstreamBaseUri.toString(),
                ServerConfig.DEFAULT_CLIENT_ID,
                null,
                null,
                "",
                false,
                emptyMap(),
                null,
                true,
                emptyList(),
                false,
                null,
                false,
                ServerConfig.DEFAULT_CODEX_INSTRUCTIONS_MODE,
                null,
                false,
                false,
            )
        }

        private fun errorJson(message: String): String {
            return buildJsonObject { put("error", message) }.toString()
        }
    }
}
