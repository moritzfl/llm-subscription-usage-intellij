package de.moritzf.quota.zai

import de.moritzf.quota.shared.JsonSupport
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Web search and page reading through the GLM Coding Plan's hosted MCP servers, which count against
 * the plan's tool limit. The REST `web_search` and `reader` APIs bill the account balance.
 */
open class ZaiWebSearchClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val mcpBaseUri: URI = DEFAULT_MCP_BASE_URI,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val sessions = ConcurrentHashMap<String, Session>()

    open fun webSearch(
        apiKey: String,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        includeContent: Boolean = false,
    ): String {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            throw ZaiQuotaException("Search query is required.")
        }
        val result =
            callTool(
                token(apiKey),
                SEARCH_SERVER,
                SEARCH_TOOLS,
                buildJsonObject {
                    put("search_query", trimmedQuery)
                    put("content_size", if (includeContent) "high" else "medium")
                    put("location", "us")
                },
                "Z.ai web search",
            )
        // The server has no result count; it applies the limit here.
        val resultLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        return (if (result is JsonArray) JsonArray(result.take(resultLimit)) else result).toString()
    }

    open fun webFetch(apiKey: String, url: String): String {
        val target = normalizeHttpUrl(url)
        return callTool(
                token(apiKey),
                READER_SERVER,
                READER_TOOLS,
                buildJsonObject {
                    put("url", target)
                    put("return_format", "markdown")
                    put("retain_images", false)
                    put("with_links_summary", true)
                },
                "Z.ai web fetch",
            )
            .toString()
    }

    private fun token(apiKey: String): String =
        apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }

    /** Calls a tool, starting a new session once when the server no longer knows the old one. */
    private fun callTool(
        apiKey: String,
        server: String,
        toolNames: List<String>,
        arguments: JsonObject,
        action: String,
    ): JsonElement {
        val key = "$server\u0000${apiKey.hashCode()}"
        repeat(2) { attempt ->
            val session = session(key, apiKey, server, toolNames, action, fresh = attempt > 0)
            val message =
                rpc(
                    apiKey,
                    server,
                    session.id,
                    "tools/call",
                    buildJsonObject {
                        put("name", session.tool)
                        put("arguments", arguments)
                    },
                    action,
                )
            val error = toolError(message)
            if (error == null) return toolResult(message, action)
            sessions.remove(key)
            if (attempt > 0 || !isSessionError(error)) {
                throw ZaiQuotaException("$action failed: $error", 200, message.toString())
            }
        }
        error("unreachable")
    }

    private fun session(
        key: String,
        apiKey: String,
        server: String,
        toolNames: List<String>,
        action: String,
        fresh: Boolean,
    ): Session {
        if (!fresh) {
            sessions[key]
                ?.takeIf { nanoTime() - it.startedAt < SESSION_TTL.toNanos() }
                ?.let {
                    return it
                }
        }
        val initialize =
            send(
                apiKey,
                server,
                null,
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "initialize")
                    putJsonObject("params") {
                        put("protocolVersion", PROTOCOL_VERSION)
                        putJsonObject("capabilities") {}
                        putJsonObject("clientInfo") {
                            put("name", "llm-subscription-usage")
                            put("version", "1")
                        }
                    }
                },
                action,
            )
        val sessionId =
            initialize.headers().firstValue("Mcp-Session-Id").orElse(null)?.trim()?.ifBlank { null }
                ?: throw ZaiQuotaException(
                    "$action failed: no MCP session.",
                    200,
                    initialize.body(),
                )
        message(initialize.body(), action)
        send(
            apiKey,
            server,
            sessionId,
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
            action,
        )
        val tools = rpc(apiKey, server, sessionId, "tools/list", JsonObject(emptyMap()), action)
        val available =
            (((tools["result"] as? JsonObject)?.get("tools")) as? JsonArray).orEmpty().mapNotNull {
                ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
            }
        val tool =
            toolNames.firstOrNull { it in available }
                ?: available.singleOrNull()
                ?: toolNames.first()
        return Session(sessionId, tool, nanoTime()).also { sessions[key] = it }
    }

    private fun rpc(
        apiKey: String,
        server: String,
        sessionId: String,
        method: String,
        params: JsonObject,
        action: String,
    ): JsonObject {
        val response =
            send(
                apiKey,
                server,
                sessionId,
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 2)
                    put("method", method)
                    put("params", params)
                },
                action,
            )
        return message(response.body(), action)
    }

    private fun send(
        apiKey: String,
        server: String,
        sessionId: String?,
        payload: JsonObject,
        action: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(mcpBaseUri.resolve("$server/mcp"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply { if (sessionId != null) header("Mcp-Session-Id", sessionId) }
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (exception: IOException) {
                throw ZaiQuotaException(
                    "Request failed. Check your connection.",
                    0,
                    null,
                    exception,
                )
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ZaiQuotaException(
                    "Request failed. Check your connection.",
                    0,
                    null,
                    exception,
                )
            }
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, body)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException("$action failed (HTTP $status). Try again later.", status, body)
        }
        accountError(body)?.let { (code, message) ->
            if (code in AUTH_CODES) {
                throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", 401, body)
            }
            throw ZaiQuotaException("$action failed: $message", status, body)
        }
        return response
    }

    private fun message(body: String, action: String): JsonObject =
        parseMessage(body)
            ?: throw ZaiQuotaException("$action failed: unreadable MCP response.", 200, body)

    private fun toolResult(message: JsonObject, action: String): JsonElement {
        val text =
            (((message["result"] as? JsonObject)?.get("content") as? JsonArray)?.firstOrNull()
                    as? JsonObject)
                ?.get("text")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw ZaiQuotaException("$action returned no result.", 200, message.toString())
        return decodeResult(text)
            ?: throw ZaiQuotaException("$action returned no JSON result.", 200, text)
    }

    private data class Session(val id: String, val tool: String, val startedAt: Long)

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 20
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val SEARCH_SERVER = "web_search_prime"
        private const val READER_SERVER = "web_reader"
        private val SEARCH_TOOLS = listOf("web_search_prime", "webSearchPrime")
        private val READER_TOOLS = listOf("webReader", "web_reader")
        private val AUTH_CODES = setOf(401, 1000, 1001)
        private val SESSION_TTL: Duration = Duration.ofMinutes(10)
        private val DEFAULT_MCP_BASE_URI = URI.create("https://api.z.ai/api/mcp/")

        /** The JSON-RPC message in a plain JSON or server-sent-events body. */
        internal fun parseMessage(body: String): JsonObject? {
            fun parse(text: String) =
                runCatching { JsonSupport.json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            parse(body.trim())?.let {
                return it
            }
            return body
                .replace("\r\n", "\n")
                .split("\n\n")
                .mapNotNull { event ->
                    event
                        .lines()
                        .filter { it.startsWith("data:") }
                        .joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
                        .takeIf { it.isNotBlank() }
                        ?.let(::parse)
                }
                .lastOrNull { "result" in it || "error" in it }
        }

        /** `{"code":1001,"msg":…,"success":false}`: the server's answer outside JSON-RPC. */
        private fun accountError(body: String): Pair<Int?, String>? {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(body.trim()) }.getOrNull()
                    as? JsonObject ?: return null
            if ((root["success"] as? JsonPrimitive)?.booleanOrNull != false) return null
            val code = (root["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val message = (root["msg"] as? JsonPrimitive)?.contentOrNull ?: "request was rejected"
            return code to message
        }

        private fun toolError(message: JsonObject): String? {
            (message["error"] as? JsonObject)?.let { error ->
                return (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
            }
            val result = message["result"] as? JsonObject ?: return null
            if ((result["isError"] as? JsonPrimitive)?.booleanOrNull != true) return null
            return ((result["content"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.get("text")
                ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "tool error"
        }

        private fun isSessionError(error: String): Boolean =
            error.contains("-401") ||
                error.contains("session", ignoreCase = true) ||
                error.contains("not found", ignoreCase = true)

        /** Tool text is JSON, sometimes encoded as a JSON string once more. */
        internal fun decodeResult(text: String): JsonElement? {
            var element: JsonElement =
                runCatching { JsonSupport.json.parseToJsonElement(text) }.getOrNull() ?: return null
            repeat(2) {
                val nested = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (nested != null) {
                    // Only structured JSON counts; plain text stays the string it was.
                    element =
                        runCatching { JsonSupport.json.parseToJsonElement(nested) }
                            .getOrNull()
                            ?.takeIf { it !is JsonPrimitive || it.isString } ?: return element
                }
            }
            return element.takeUnless { it == JsonNull }
        }

        internal fun normalizeHttpUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                throw ZaiQuotaException("URL is required.")
            }
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (uri?.host.isNullOrBlank() || (scheme != "http" && scheme != "https")) {
                throw ZaiQuotaException("URL must be http or https.")
            }
            return withScheme
        }

        fun createDefault(): ZaiWebSearchClient = ZaiWebSearchClient()

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
        }
    }
}
