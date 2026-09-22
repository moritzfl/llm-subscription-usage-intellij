package de.moritzf.quota.github.proxy

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** OpenAI chat/completions <-> Anthropic messages bridge for Claude on GitHub Copilot. */
internal class GitHubCopilotClaudeChatBridge(
    private val bridgeModel: (String) -> Boolean = ::isClaudeModel,
) {
    private val streamToolCallIndexes = ConcurrentHashMap<String, MutableMap<Int, Int>>()
    private val remoteImageHttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun clearStreamState(requestId: String) {
        streamToolCallIndexes.remove(requestId)
    }

    fun shouldBridge(request: SubscriptionProxyRequest): Boolean {
        return request.route == SubscriptionProxyRoute.CHAT_COMPLETIONS &&
            SubscriptionProxyRoute.ANTHROPIC_MESSAGES in request.model.supportedRoutes &&
            bridgeModel(request.model.upstreamId)
    }

    fun openAiChatToAnthropicMessagesBody(request: SubscriptionProxyRequest, body: JsonObject): JsonObject {
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        val systemParts = messages.mapNotNull { message ->
            val item = message as? JsonObject ?: return@mapNotNull null
            val role = stringField(item, "role") ?: return@mapNotNull null
            if (role == "system" || role == "developer") contentText(item["content"]) else null
        }.filter { it.isNotBlank() }.toMutableList()
        openAiResponseFormatInstruction(body["response_format"] as? JsonObject)?.let { systemParts.add(it) }
        val systemText = systemParts.joinToString("\n\n")
        val anthropicMessages = buildJsonArray {
            messages.forEach { message ->
                val item = message as? JsonObject ?: return@forEach
                val role = stringField(item, "role") ?: return@forEach
                if (role == "system" || role == "developer") return@forEach
                val mappedRole = if (role == "assistant") "assistant" else "user"
                add(buildJsonObject {
                    put("role", mappedRole)
                    put("content", openAiMessageContentToAnthropicContent(item, role))
                })
            }
        }
        return buildJsonObject {
            put("model", request.model.upstreamId)
            put("max_tokens", anthropicMaxTokens(request, body))
            if (systemText.isNotBlank()) put("system", systemText)
            put("messages", anthropicMessages)
            body["stream"]?.let { put("stream", it) }
            body["temperature"]?.let { put("temperature", it) }
            body["top_p"]?.let { put("top_p", it) }
            body["stop"]?.let { put("stop_sequences", it) }
            val toolChoice = body["tool_choice"]
            if (!isOpenAiToolChoiceNone(toolChoice)) {
                val tools = body["tools"] as? JsonArray ?: openAiFunctionsToTools(body["functions"] as? JsonArray)
                openAiToolsToAnthropicTools(tools)?.let { put("tools", it) }
                openAiToolChoiceToAnthropicToolChoice(toolChoice ?: openAiFunctionCallToToolChoice(body["function_call"]))
                    ?.let { put("tool_choice", it) }
            }
        }
    }

    private fun openAiResponseFormatInstruction(responseFormat: JsonObject?): String? {
        responseFormat ?: return null
        return when (stringField(responseFormat, "type")) {
            "json_object" -> "Respond with a valid JSON object only. Do not wrap it in Markdown fences."
            "json_schema" -> {
                val schema = responseFormat["json_schema"]?.let(JsonHelper::encodeToString).orEmpty()
                "Respond with a valid JSON object only. Do not wrap it in Markdown fences. Follow this JSON schema when possible: $schema"
            }

            else -> null
        }
    }

    private fun openAiFunctionsToTools(functions: JsonArray?): JsonArray? {
        functions ?: return null
        return buildJsonArray {
            functions.forEach { function ->
                val item = function as? JsonObject ?: return@forEach
                add(buildJsonObject {
                    put("type", "function")
                    put("function", item)
                })
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun openAiFunctionCallToToolChoice(functionCall: JsonElement?): JsonElement? {
        return when (functionCall) {
            is JsonPrimitive -> when (functionCall.contentOrNull) {
                "auto" -> JsonPrimitive("auto")
                "none" -> JsonPrimitive("none")
                else -> null
            }

            is JsonObject -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    stringField(functionCall, "name")?.let { put("name", it) }
                })
            }

            else -> null
        }
    }

    private fun openAiMessageContentToAnthropicContent(message: JsonObject, role: String): JsonElement {
        if (role == "tool") {
            return buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", stringField(message, "tool_call_id").orEmpty())
                    put("content", contentText(message["content"]))
                })
            }
        }
        val toolCalls = message["tool_calls"] as? JsonArray
        if (role != "assistant" || toolCalls == null || toolCalls.isEmpty()) {
            return openAiContentToAnthropicContent(message["content"])
        }
        return buildJsonArray {
            val text = contentText(message["content"])
            if (text.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            toolCalls.forEach { toolCall ->
                val item = toolCall as? JsonObject ?: return@forEach
                val function = item["function"] as? JsonObject ?: return@forEach
                val name = stringField(function, "name") ?: return@forEach
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", stringField(item, "id").orEmpty())
                    put("name", name)
                    put("input", parseToolArguments(stringField(function, "arguments")))
                })
            }
        }
    }

    private fun openAiToolsToAnthropicTools(tools: JsonArray?): JsonArray? {
        tools ?: return null
        return buildJsonArray {
            tools.forEach { tool ->
                val item = tool as? JsonObject ?: return@forEach
                val function = item["function"] as? JsonObject ?: return@forEach
                val name = stringField(function, "name") ?: return@forEach
                add(buildJsonObject {
                    put("name", name)
                    stringField(function, "description")?.let { put("description", it) }
                    put("input_schema", function["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun openAiToolChoiceToAnthropicToolChoice(toolChoice: JsonElement?): JsonObject? {
        return when (toolChoice) {
            is JsonPrimitive -> when (toolChoice.contentOrNull) {
                "auto" -> buildJsonObject { put("type", "auto") }
                "required" -> buildJsonObject { put("type", "any") }
                "none", null -> null
                else -> null
            }

            is JsonObject -> {
                val functionName = stringField(toolChoice["function"] as? JsonObject, "name") ?: return null
                buildJsonObject {
                    put("type", "tool")
                    put("name", functionName)
                }
            }

            else -> null
        }
    }

    private fun isOpenAiToolChoiceNone(toolChoice: JsonElement?): Boolean {
        return toolChoice is JsonPrimitive && toolChoice.contentOrNull == "none"
    }

    private fun parseToolArguments(arguments: String?): JsonElement {
        if (arguments.isNullOrBlank()) return buildJsonObject { }
        return JsonHelper.parseToJsonElementOrNull(arguments) ?: JsonPrimitive(arguments)
    }

    private fun openAiContentToAnthropicContent(content: JsonElement?): JsonElement {
        return when (content) {
            is JsonArray -> buildJsonArray {
                content.forEach { block ->
                    openAiContentBlockToAnthropic(block)?.let { add(it) }
                }
            }

            JsonNull, null -> JsonPrimitive("")
            else -> JsonPrimitive(contentText(content))
        }
    }

    private fun openAiContentBlockToAnthropic(block: JsonElement): JsonElement? {
        val item = block as? JsonObject ?: return block
        return when (stringField(item, "type")) {
            "text" -> buildJsonObject {
                put("type", "text")
                put("text", stringField(item, "text").orEmpty())
            }

            // Drop unsafe/unusable image blocks rather than forwarding raw OpenAI image_url.
            "image_url" -> openAiImageUrlToAnthropicImage(item)
            else -> item
        }
    }

    private fun openAiImageUrlToAnthropicImage(item: JsonObject): JsonObject? {
        val url = stringField(item["image_url"] as? JsonObject, "url") ?: return null
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return fetchRemoteImageUrlToAnthropicImage(url)
        }
        val dataPrefix = "data:"
        if (!url.startsWith(dataPrefix)) return null
        val mediaType = url.substringAfter(dataPrefix).substringBefore(';').takeIf { it.isNotBlank() } ?: return null
        val data = url.substringAfter("base64,", missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", mediaType)
                put("data", data)
            })
        }
    }

    private fun fetchRemoteImageUrlToAnthropicImage(url: String): JsonObject? {
        val hop = SafeRemoteImageFetcher.get(url, ::sendRemoteImageHop, ::isSafeRemoteImageUri) ?: return null
        val bytes = hop.body
        if (bytes.isEmpty() || bytes.size > GitHubCopilotProxyIds.MAX_REMOTE_IMAGE_BYTES) return null
        val mediaType = hop.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.startsWith("image/") }
            ?: mediaTypeFromImageUrl(hop.uri.toString())
            ?: mediaTypeFromImageUrl(url)
            ?: return null
        return buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", mediaType)
                put("data", Base64.getEncoder().encodeToString(bytes))
            })
        }
    }

    private fun sendRemoteImageHop(uri: URI): RemoteImageHop? {
        val response = try {
            remoteImageHttpClient.send(
                HttpRequest.newBuilder(uri)
                    .timeout(GitHubCopilotProxyIds.REMOTE_IMAGE_TIMEOUT)
                    .header("Accept", "image/*")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        } catch (_: Exception) {
            return null
        }
        return RemoteImageHop(
            statusCode = response.statusCode(),
            uri = response.uri(),
            location = response.headers().firstValue("Location").orElse(null),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
            body = response.body(),
        )
    }

    private fun mediaTypeFromImageUrl(url: String): String? {
        val path = runCatching { URI.create(url).path }.getOrNull()?.lowercase() ?: return null
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> null
        }
    }

    private fun isSafeRemoteImageUri(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized == "metadata.google.internal") {
            return false
        }
        val addresses = runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull() ?: return false
        if (addresses.isEmpty()) return false
        return addresses.none { address ->
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                isCarrierGradeNat(address) ||
                isUniqueLocalAddress(address) ||
                isMetadataAddress(address)
        }
    }

    private fun isCarrierGradeNat(address: java.net.InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 4 && bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xff) in 64..127
    }

    private fun isUniqueLocalAddress(address: java.net.InetAddress): Boolean {
        val bytes = address.address
        // fc00::/7
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun isMetadataAddress(address: java.net.InetAddress): Boolean {
        val bytes = address.address
        // 169.254.169.254 and broader link-local already covered; also block 169.254.0.0/16 explicitly above.
        return bytes.size == 4 &&
            bytes[0] == 169.toByte() &&
            bytes[1] == 254.toByte() &&
            bytes[2] == 169.toByte() &&
            bytes[3] == 254.toByte()
    }

    private fun contentText(content: JsonElement?): String {
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("") { block ->
                ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }

            else -> ""
        }
    }

    private fun anthropicMaxTokens(request: SubscriptionProxyRequest, body: JsonObject): Int {
        return intField(body, "max_tokens")
            ?: intField(body, "max_completion_tokens")
            ?: request.model.maxOutputTokens?.coerceAtMost(GitHubCopilotProxyIds.DEFAULT_ANTHROPIC_MAX_TOKENS)
            ?: GitHubCopilotProxyIds.DEFAULT_ANTHROPIC_MAX_TOKENS
    }

    fun openAiChatSseLine(request: SubscriptionProxyRequest, line: String): String? {
        if (!shouldBridge(request)) return null
        if (line.startsWith("event:")) return ""
        if (!line.startsWith("data:")) return line
        val rawData = line.substringAfter("data:")
        val leadingWhitespace = rawData.takeWhile { it == ' ' || it == '\t' }
        val data = rawData.drop(leadingWhitespace.length)
        if (data == "[DONE]") {
            streamToolCallIndexes.remove(request.requestId)
            return line
        }
        val transformed = anthropicSseDataToOpenAiChat(request, data)
        if (transformed.isEmpty()) return ""
        return "data:$leadingWhitespace$transformed"
    }

    fun anthropicMessageToOpenAiChat(request: SubscriptionProxyRequest, body: String): String {
        val root = JsonHelper.parseToJsonElementOrNull(body) as? JsonObject ?: return body
        if (root.containsKey("error")) return body
        val content = root["content"] as? JsonArray
        val text = anthropicText(content)
        val toolCalls = anthropicToolCalls(content)
        val legacyFunctionCall = legacyOpenAiFunctionCall(request, toolCalls)
        val finishReason = if (legacyFunctionCall != null) {
            "function_call"
        } else {
            openAiFinishReason(stringField(root, "stop_reason"))
        }
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", root["id"] ?: JsonPrimitive("chatcmpl-${request.requestId}"))
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        if (legacyFunctionCall != null) {
                            put("content", JsonNull)
                            put("function_call", legacyFunctionCall)
                        } else {
                            put("content", text)
                            if (toolCalls != null) put("tool_calls", toolCalls)
                        }
                    })
                    put("finish_reason", finishReason)
                })
            })
            anthropicUsageToOpenAi(root["usage"] as? JsonObject)?.let { put("usage", it) }
        })
    }

    private fun legacyOpenAiFunctionCall(request: SubscriptionProxyRequest, toolCalls: JsonArray?): JsonObject? {
        if (request.body["functions"] !is JsonArray || request.body["tools"] is JsonArray) return null
        val toolCall = toolCalls?.firstOrNull() as? JsonObject ?: return null
        return toolCall["function"] as? JsonObject
    }

    fun anthropicSseDataToOpenAiChat(request: SubscriptionProxyRequest, data: String): String {
        val root = JsonHelper.parseToJsonElementOrNull(data) as? JsonObject ?: return data
        return when (stringField(root, "type")) {
            "message_start" -> openAiChatChunk(request, role = "assistant")
            "content_block_start" -> {
                val block = root["content_block"] as? JsonObject
                if (block != null && stringField(block, "type") == "tool_use") {
                    openAiToolCallStartChunk(request, root, block)
                } else {
                    openAiChatChunk(request)
                }
            }

            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject
                val text = stringField(delta, "text").orEmpty()
                val partialJson = stringField(delta, "partial_json").orEmpty()
                when {
                    text.isNotEmpty() -> openAiChatChunk(request, content = text)
                    partialJson.isNotEmpty() -> openAiToolCallArgumentsChunk(request, root, partialJson)
                    else -> openAiChatChunk(request)
                }
            }

            "message_delta" -> {
                val delta = root["delta"] as? JsonObject
                val finishChunk = openAiChatChunk(request, finishReason = openAiFinishReason(stringField(delta, "stop_reason")))
                val usageChunk = if (openAiIncludeUsage(request)) openAiUsageChunk(request, root["usage"] as? JsonObject) else null
                if (usageChunk == null) finishChunk else "$finishChunk\n\ndata: $usageChunk"
            }

            "message_stop" -> ""
            else -> openAiChatChunk(request)
        }
    }

    private fun streamToolCallIndex(request: SubscriptionProxyRequest, blockIndex: Int): Int {
        synchronized(streamToolCallIndexes) {
            val indexes = streamToolCallIndexes.getOrPut(request.requestId) { LinkedHashMap() }
            return indexes.getOrPut(blockIndex) { indexes.size }
        }
    }

    private fun openAiToolCallStartChunk(request: SubscriptionProxyRequest, root: JsonObject, block: JsonObject): String {
        val toolCallIndex = streamToolCallIndex(request, intField(root, "index") ?: 0)
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", toolCallIndex)
                                put("id", stringField(block, "id").orEmpty())
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", stringField(block, "name").orEmpty())
                                    put("arguments", "")
                                })
                            })
                        })
                    })
                    put("finish_reason", JsonNull)
                })
            })
        })
    }

    private fun openAiToolCallArgumentsChunk(request: SubscriptionProxyRequest, root: JsonObject, partialJson: String): String {
        val toolCallIndex = streamToolCallIndex(request, intField(root, "index") ?: 0)
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", toolCallIndex)
                                put("function", buildJsonObject {
                                    put("arguments", partialJson)
                                })
                            })
                        })
                    })
                    put("finish_reason", JsonNull)
                })
            })
        })
    }

    private fun openAiChatChunk(
        request: SubscriptionProxyRequest,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): String {
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        role?.let { put("role", it) }
                        content?.let { put("content", it) }
                    })
                    if (finishReason == null) put("finish_reason", JsonNull) else put("finish_reason", finishReason)
                })
            })
        })
    }

    private fun openAiUsageChunk(request: SubscriptionProxyRequest, usage: JsonObject?): String? {
        val normalizedUsage = anthropicUsageToOpenAi(usage) ?: return null
        return JsonHelper.encodeToString(buildJsonObject {
            put("id", "chatcmpl-${request.requestId}")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", request.model.localId)
            put("choices", JsonArray(emptyList()))
            put("usage", normalizedUsage)
        })
    }

    private fun openAiIncludeUsage(request: SubscriptionProxyRequest): Boolean {
        val streamOptions = request.body["stream_options"] as? JsonObject ?: return false
        return (streamOptions["include_usage"] as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun anthropicText(content: JsonArray?): String {
        return content.orEmpty().joinToString("") { block ->
            val item = block as? JsonObject ?: return@joinToString ""
            if (stringField(item, "type") == "text") stringField(item, "text").orEmpty() else ""
        }
    }

    private fun anthropicToolCalls(content: JsonArray?): JsonArray? {
        content ?: return null
        return buildJsonArray {
            content.forEach { block ->
                val item = block as? JsonObject ?: return@forEach
                if (stringField(item, "type") != "tool_use") return@forEach
                val id = stringField(item, "id") ?: return@forEach
                val name = stringField(item, "name") ?: return@forEach
                add(buildJsonObject {
                    put("id", id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", name)
                        put("arguments", JsonHelper.encodeToString(item["input"] ?: buildJsonObject { }))
                    })
                })
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun anthropicUsageToOpenAi(usage: JsonObject?): JsonObject? {
        usage ?: return null
        val input = intField(usage, "input_tokens") ?: 0
        val output = intField(usage, "output_tokens") ?: 0
        return buildJsonObject {
            put("prompt_tokens", input)
            put("completion_tokens", output)
            put("total_tokens", input + output)
        }
    }

    private fun openAiFinishReason(reason: String?): String {
        return when (reason) {
            "end_turn", "stop_sequence", null -> "stop"
            "max_tokens" -> "length"
            "tool_use" -> "tool_calls"
            else -> reason
        }
    }
}
