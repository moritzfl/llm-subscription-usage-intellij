package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.media.RemoteImageHop
import de.moritzf.proxy.media.SafeRemoteImageFetcher
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI chat/completions <-> Gemini generateContent bridge for OpenCode's Google provider.
 *
 * OpenCode lists Gemini models with `@opencode/ai/providers/google` and a v1beta base URL. Those
 * models are not OpenAI-compatible upstream, so the local proxy lowercases chat requests to
 * `generateContent` / `streamGenerateContent` and raises the Gemini response back to OpenAI chat.
 */
internal class GoogleGeminiChatBridge {
    private class StreamState {
        var roleSent = false
        var finished = false
        var hasToolCalls = false
        var nextToolCallIndex = 0
        var finishReason: String? = null
        var usage: JsonObject? = null
        val text = StringBuilder()
    }

    private val thoughtSignatures: MutableMap<String, String> =
        Collections.synchronizedMap(LinkedHashMap())
    private val textSignatures: MutableMap<String, String> =
        Collections.synchronizedMap(LinkedHashMap())
    private val streams = ConcurrentHashMap<String, StreamState>()
    private val remoteImageHttpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun clearStreamState(requestId: String) {
        streams.remove(requestId)
    }

    fun generateContentUri(baseUri: URI, model: String, stream: Boolean): URI {
        val path = if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
        return URI.create(baseUri.toString().trimEnd('/') + "/models/" + model + path)
    }

    fun openAiChatToGoogleBody(
        request: SubscriptionProxyRequest,
        body: JsonObject,
        configuredBody: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val model = request.model.upstreamId
        val omitCallIds = omitsFunctionCallIds(model)
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        val toolNames = toolNames(messages)
        val systemParts = mutableListOf<JsonObject>()
        val contents = mutableListOf<JsonObject>()
        var pendingToolResponses: MutableList<JsonObject>? = null

        fun flushToolResponses() {
            val parts = pendingToolResponses ?: return
            if (parts.isNotEmpty()) {
                contents += buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(parts))
                }
            }
            pendingToolResponses = null
        }

        messages.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            val role = stringField(message, "role") ?: return@forEach
            if (role != "tool" && role != "function") flushToolResponses()
            when (role) {
                "system",
                "developer" -> {
                    val text = contentText(message["content"])
                    if (text.isNotBlank()) {
                        systemParts += buildJsonObject { put("text", text) }
                    }
                }

                "user" -> {
                    val parts = contentParts(message["content"])
                    if (parts.isNotEmpty()) {
                        contents += buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(parts))
                        }
                    }
                }

                "assistant" -> {
                    val parts = mutableListOf<JsonObject>()
                    val text = contentText(message["content"])
                    if (text.isNotBlank()) {
                        parts += buildJsonObject {
                            put("text", text)
                            synchronized(textSignatures) { textSignatures[text] }
                                ?.let { put("thoughtSignature", it) }
                        }
                    }
                    val toolCalls = message["tool_calls"] as? JsonArray
                    var signed = false
                    if (toolCalls != null) {
                        toolCalls.forEach { callElement ->
                            val call = callElement as? JsonObject ?: return@forEach
                            val function = call["function"] as? JsonObject ?: return@forEach
                            val name = stringField(function, "name") ?: return@forEach
                            val id = stringField(call, "id").orEmpty()
                            val signature = thoughtSignatures[id]
                            val part = buildJsonObject {
                                putJsonObject("functionCall") {
                                    if (!omitCallIds && id.isNotBlank()) put("id", id)
                                    put("name", name)
                                    put(
                                        "args",
                                        parseToolArguments(
                                            stringField(function, "arguments").orEmpty()
                                        ),
                                    )
                                }
                                when {
                                    signature != null -> {
                                        put("thoughtSignature", signature)
                                        signed = true
                                    }

                                    requiresThoughtSignatureFallback(model) && !signed ->
                                        put(
                                            "thoughtSignature",
                                            SKIP_THOUGHT_SIGNATURE_VALIDATOR,
                                        )
                                }
                            }
                            parts += part
                        }
                    }
                    // Legacy single function_call.
                    val legacy = message["function_call"] as? JsonObject
                    if (legacy != null) {
                        val name = stringField(legacy, "name") ?: return@forEach
                        parts += buildJsonObject {
                            putJsonObject("functionCall") {
                                if (!omitCallIds) put("id", newToolCallId())
                                put("name", name)
                                put(
                                    "args",
                                    parseToolArguments(stringField(legacy, "arguments").orEmpty()),
                                )
                            }
                            if (requiresThoughtSignatureFallback(model) && !signed) {
                                put("thoughtSignature", SKIP_THOUGHT_SIGNATURE_VALIDATOR)
                            }
                        }
                    }
                    if (parts.isNotEmpty()) {
                        contents += buildJsonObject {
                            put("role", "model")
                            put("parts", JsonArray(parts))
                        }
                    }
                }

                "tool",
                "function" -> {
                    val callId =
                        stringField(message, "tool_call_id")
                            ?: stringField(message, "name").orEmpty()
                    val name = stringField(message, "name") ?: toolNames[callId] ?: "tool"
                    val media = contentMediaParts(message["content"])
                    pendingToolResponses = pendingToolResponses ?: mutableListOf()
                    pendingToolResponses!!.add(
                        buildJsonObject {
                            putJsonObject("functionResponse") {
                                if (!omitCallIds && callId.isNotBlank()) put("id", callId)
                                put("name", name)
                                putJsonObject("response") {
                                    put("name", name)
                                    put("content", contentText(message["content"]))
                                }
                                if (media.isNotEmpty()) put("parts", JsonArray(media))
                            }
                        }
                    )
                }
            }
        }
        flushToolResponses()

        val tools = openAiToolsToGemini(body)
        val toolChoice = openAiToolChoiceToGemini(body["tool_choice"] ?: body["function_call"])
        val generationConfig = generationConfig(body, configuredBody, model)
        val mapped = buildJsonObject {
            put("contents", JsonArray(contents))
            if (systemParts.isNotEmpty()) {
                putJsonObject("systemInstruction") { put("parts", JsonArray(systemParts)) }
            }
            if (tools != null) put("tools", tools)
            if (toolChoice != null && tools != null) put("toolConfig", toolChoice)
            if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
        }

        return mergeConfiguredBody(mapped, configuredBody)
    }

    fun googleResponseToOpenAiChat(request: SubscriptionProxyRequest, raw: String): String {
        val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return raw
        if (root["error"] != null) return raw
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray
        val text = geminiText(parts)
        geminiTextSignature(parts)?.let { rememberTextSignature(text, it) }
        val toolCalls = geminiToolCalls(parts)
        val finishReason = finishReason(stringField(candidate, "finishReason"), toolCalls != null)
        val usage = geminiUsage(root["usageMetadata"] as? JsonObject)
        return JsonHelper.encodeToString(
            buildJsonObject {
                put("id", "chatcmpl-${request.requestId}")
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000L)
                put("model", request.model.localId)
                putJsonArray("choices") {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            putJsonObject("message") {
                                put("role", "assistant")
                                if (text.isNotEmpty()) put("content", text)
                                else put("content", JsonNull)
                                if (toolCalls != null) put("tool_calls", toolCalls)
                            }
                            put("finish_reason", finishReason)
                        }
                    )
                }
                if (usage != null) put("usage", usage)
            }
        )
    }

    fun openAiChatSseLine(request: SubscriptionProxyRequest, line: String): String? {
        if (!line.startsWith("data:")) return line
        val rawData = line.substringAfter("data:")
        val leadingWhitespace = rawData.takeWhile { it == ' ' || it == '\t' }
        val data = rawData.drop(leadingWhitespace.length)
        if (data == "[DONE]") return ""
        val root = JsonHelper.parseToJsonElementOrNull(data) as? JsonObject ?: return ""
        if (root["error"] != null) {
            streams.remove(request.requestId)
            return "data:${leadingWhitespace}${JsonHelper.encodeToString(root)}"
        }
        val state = streams.getOrPut(request.requestId) { StreamState() }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val usage = root["usageMetadata"] as? JsonObject
        if (usage != null) state.usage = usage
        val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray
        val chunks = mutableListOf<String>()
        if (parts != null && parts.isNotEmpty() && !state.roleSent) {
            chunks += openAiChatChunk(request, state, role = "assistant")
            state.roleSent = true
        }
        parts?.forEach { element ->
            val part = element as? JsonObject ?: return@forEach
            val text = stringField(part, "text")
            val toolCall = part["functionCall"] as? JsonObject
            val signature = stringField(part, "thoughtSignature")
            if (text != null && text.isNotEmpty() && !isThought(part)) {
                state.text.append(text)
                chunks += openAiChatChunk(request, state, content = text)
            }
            if (!signature.isNullOrBlank() && state.text.isNotBlank() && toolCall == null) {
                rememberTextSignature(state.text.toString(), signature)
            }
            if (toolCall != null) {
                val name = stringField(toolCall, "name") ?: return@forEach
                val id =
                    stringField(toolCall, "id")
                        ?: "call_${UUID.randomUUID().toString().replace("-", "")}"
                if (!signature.isNullOrBlank()) rememberThoughtSignature(id, signature)
                chunks +=
                    openAiToolCallChunk(
                        request,
                        state,
                        id = id,
                        name = name,
                        arguments =
                            JsonHelper.encodeToString(
                                toolCall["args"] as? JsonObject ?: JsonObject(emptyMap())
                            ),
                    )
                state.hasToolCalls = true
            }
        }
        val finish = stringField(candidate, "finishReason")
        if (finish != null && !state.finished) {
            state.finishReason = finishReason(finish, state.hasToolCalls)
            chunks += openAiChatChunk(request, state, finishReason = state.finishReason)
            state.finished = true
        }
        if (chunks.isEmpty()) return ""
        return "data:${leadingWhitespace}" + chunks.joinToString("\n\ndata: ") { it }
    }

    fun openAiChatSseEnd(request: SubscriptionProxyRequest): String? {
        val state = streams.remove(request.requestId) ?: return "data: [DONE]\n\n"
        val chunks = mutableListOf<String>()
        if (!state.finished) {
            chunks +=
                openAiChatChunk(
                    request,
                    state,
                    finishReason =
                        state.finishReason ?: if (state.hasToolCalls) "tool_calls" else "stop",
                )
        }
        if (openAiIncludeUsage(request)) {
            geminiUsage(state.usage)?.let { usage ->
                chunks +=
                    JsonHelper.encodeToString(
                        buildJsonObject {
                            put("id", "chatcmpl-${request.requestId}")
                            put("object", "chat.completion.chunk")
                            put("created", System.currentTimeMillis() / 1000L)
                            put("model", request.model.localId)
                            put("choices", JsonArray(emptyList()))
                            put("usage", usage)
                        }
                    )
            }
        }
        chunks += "[DONE]"
        return "data: " + chunks.joinToString("\n\ndata: ") + "\n\n"
    }

    private fun openAiChatChunk(
        request: SubscriptionProxyRequest,
        state: StreamState,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): String {
        return JsonHelper.encodeToString(
            buildJsonObject {
                put("id", "chatcmpl-${request.requestId}")
                put("object", "chat.completion.chunk")
                put("created", System.currentTimeMillis() / 1000L)
                put("model", request.model.localId)
                putJsonArray("choices") {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            putJsonObject("delta") {
                                role?.let { put("role", it) }
                                content?.let { put("content", it) }
                            }
                            if (finishReason == null) put("finish_reason", JsonNull)
                            else put("finish_reason", finishReason)
                        }
                    )
                }
            }
        )
    }

    private fun openAiToolCallChunk(
        request: SubscriptionProxyRequest,
        state: StreamState,
        id: String,
        name: String,
        arguments: String,
    ): String {
        val index = state.nextToolCallIndex++
        return JsonHelper.encodeToString(
            buildJsonObject {
                put("id", "chatcmpl-${request.requestId}")
                put("object", "chat.completion.chunk")
                put("created", System.currentTimeMillis() / 1000L)
                put("model", request.model.localId)
                putJsonArray("choices") {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            putJsonObject("delta") {
                                putJsonArray("tool_calls") {
                                    add(
                                        buildJsonObject {
                                            put("index", index)
                                            put("id", id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", name)
                                                put("arguments", arguments)
                                            }
                                        }
                                    )
                                }
                            }
                            put("finish_reason", JsonNull)
                        }
                    )
                }
            }
        )
    }

    private fun contentParts(content: JsonElement?): List<JsonObject> {
        return when (content) {
            is JsonPrimitive -> {
                val text = content.contentOrNull.orEmpty()
                if (text.isEmpty()) emptyList() else listOf(buildJsonObject { put("text", text) })
            }

            is JsonArray -> content.mapNotNull { contentPart(it) }
            else -> emptyList()
        }
    }

    private fun contentMediaParts(content: JsonElement?): List<JsonObject> {
        val array = content as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val part = element as? JsonObject ?: return@mapNotNull null
            when (stringField(part, "type")) {
                "image_url",
                "input_image" -> imagePart(part)
                "file",
                "input_file" -> filePart(part)
                else -> null
            }
        }
    }

    private fun contentPart(element: JsonElement): JsonObject? {
        val part = element as? JsonObject ?: return null
        return when (stringField(part, "type")) {
            "text",
            "input_text",
            "output_text" ->
                stringField(part, "text")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { text -> buildJsonObject { put("text", text) } }

            "image_url",
            "input_image" -> imagePart(part)
            "file",
            "input_file" -> filePart(part)
            else -> stringField(part, "text")?.let { text -> buildJsonObject { put("text", text) } }
        }
    }

    private fun imagePart(part: JsonObject): JsonObject? {
        val value = part["image_url"] ?: part["image_url"]?.let { it }
        val url =
            when (value) {
                is JsonObject -> stringField(value, "url")
                is JsonPrimitive -> value.contentOrNull
                else -> stringField(part, "url")
            } ?: return null
        return dataOrRemoteMedia(url, defaultMime = "image/png")
    }

    private fun filePart(part: JsonObject): JsonObject? {
        val value = part["file"] ?: part["input_file"] ?: part
        val objectValue = value as? JsonObject ?: return null
        val data =
            stringField(objectValue, "file_data")
                ?: stringField(objectValue, "data")
                ?: stringField(objectValue, "file_url")
        val mime = stringField(objectValue, "mime_type") ?: stringField(objectValue, "media_type")
        if (data != null) return dataOrRemoteMedia(data, mime ?: "application/pdf")
        return null
    }

    private fun dataOrRemoteMedia(url: String, defaultMime: String): JsonObject? {
        if (url.startsWith("data:")) {
            val mediaType =
                url.substringAfter("data:").substringBefore(';').takeIf { it.isNotBlank() }
                    ?: defaultMime
            val data =
                url.substringAfter("base64,", missingDelimiterValue = "").takeIf { it.isNotBlank() }
                    ?: return null
            return inlineData(mediaType, data)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val fetched = fetchRemoteMedia(url) ?: return null
        return inlineData(fetched.first, Base64.getEncoder().encodeToString(fetched.second))
    }

    private fun inlineData(mimeType: String, data: String): JsonObject {
        return buildJsonObject {
            putJsonObject("inlineData") {
                put("mimeType", mimeType)
                put("data", data)
            }
        }
    }

    private fun fetchRemoteMedia(url: String): Pair<String, ByteArray>? {
        val hop =
            SafeRemoteImageFetcher.get(url, ::sendRemoteMediaHop, SafeRemoteImageFetcher::isSafeUri)
                ?: return null
        val bytes = hop.body
        if (bytes.isEmpty() || bytes.size > MAX_REMOTE_MEDIA_BYTES) return null
        val contentType =
            hop.contentType?.substringBefore(';')?.trim()?.takeIf {
                it.startsWith("image/") ||
                    it == "application/pdf" ||
                    it.startsWith("audio/") ||
                    it.startsWith("video/")
            } ?: mediaTypeFromUrl(hop.uri.toString()) ?: return null
        return contentType to bytes
    }

    private fun sendRemoteMediaHop(uri: URI): RemoteImageHop? {
        val response =
            try {
                remoteImageHttpClient.send(
                    HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "image/*,application/pdf")
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

    private fun mediaTypeFromUrl(url: String): String? {
        val path = runCatching { URI.create(url).path }.getOrNull()?.lowercase() ?: return null
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".pdf") -> "application/pdf"
            else -> null
        }
    }

    private fun openAiToolsToGemini(body: JsonObject): JsonArray? {
        val tools =
            (body["tools"] as? JsonArray)
                ?: (body["functions"] as? JsonArray)?.let { functions ->
                    buildJsonArray {
                        functions.forEach { function ->
                            val item = function as? JsonObject ?: return@forEach
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put("function", item)
                                }
                            )
                        }
                    }
                }
                ?: return null
        if (tools.isEmpty()) return null
        val declarations = buildJsonArray {
            tools.forEach { element ->
                val tool = element as? JsonObject ?: return@forEach
                val function = tool["function"] as? JsonObject ?: tool
                val name = stringField(function, "name") ?: return@forEach
                add(
                    buildJsonObject {
                        put("name", name)
                        put("description", stringField(function, "description").orEmpty())
                        put(
                            "parametersJsonSchema",
                            GeminiJsonSchema.normalize(
                                function["parameters"]
                                    ?: function["input_schema"]
                                    ?: function["parametersJsonSchema"]
                            ),
                        )
                    }
                )
            }
        }
        if (declarations.isEmpty()) return null
        return buildJsonArray { add(buildJsonObject { put("functionDeclarations", declarations) }) }
    }

    private fun openAiToolChoiceToGemini(choice: JsonElement?): JsonObject? {
        if (choice == null || choice is JsonNull) return null
        val mode: String?
        val allowed: String?
        when (choice) {
            is JsonPrimitive ->
                when (choice.contentOrNull) {
                    "auto" -> {
                        mode = "AUTO"
                        allowed = null
                    }

                    "none" -> {
                        mode = "NONE"
                        allowed = null
                    }

                    "required",
                    "any" -> {
                        mode = "ANY"
                        allowed = null
                    }

                    else -> return null
                }

            is JsonObject -> {
                val function = choice["function"] as? JsonObject
                allowed =
                    stringField(function, "name") ?: stringField(choice, "name") ?: return null
                mode = "ANY"
            }

            else -> return null
        }
        return buildJsonObject {
            putJsonObject("functionCallingConfig") {
                put("mode", mode)
                if (allowed != null) {
                    put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(allowed)) })
                }
            }
        }
    }

    private fun generationConfig(
        body: JsonObject,
        configuredBody: JsonObject,
        model: String,
    ): JsonObject {
        val configured = configuredBody["generationConfig"] as? JsonObject ?: JsonObject(emptyMap())
        val configuredThinking =
            configured["thinkingConfig"] as? JsonObject
                ?: configuredBody["thinkingConfig"] as? JsonObject
        val reasoningEffort = stringField(body, "reasoning_effort")
        val thinking =
            configuredThinking
                ?: if (
                    reasoningEffort != null &&
                        supportsThinkingLevel(reasoningEffort) &&
                        supportsThinkingLevelModel(model)
                ) {
                    buildJsonObject {
                        put("thinkingLevel", normalizeThinkingLevel(reasoningEffort))
                        put("includeThoughts", false)
                    }
                } else null
        val responseFormat = body["response_format"] as? JsonObject
        val schema =
            (responseFormat?.get("json_schema") as? JsonObject)?.let {
                it["schema"] ?: it["json_schema"]
            }
        return buildJsonObject {
            configured.forEach { (key, value) -> if (key != "thinkingConfig") put(key, value) }
            intField(body, "max_tokens")?.let { put("maxOutputTokens", it) }
                ?: intField(body, "max_completion_tokens")?.let { put("maxOutputTokens", it) }
            doubleField(body, "temperature")?.let { put("temperature", it) }
            doubleField(body, "top_p")?.let { put("topP", it) }
            intField(body, "top_k")?.let { put("topK", it) }
            doubleField(body, "presence_penalty")?.let { put("presencePenalty", it) }
            doubleField(body, "frequency_penalty")?.let { put("frequencyPenalty", it) }
            intField(body, "seed")?.let { put("seed", it) }
            stopSequences(body["stop"])
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("stopSequences", it) }
            if (responseFormat != null) {
                when (stringField(responseFormat, "type")) {
                    "json_object" -> put("responseMimeType", "application/json")
                    "json_schema" -> {
                        put("responseMimeType", "application/json")
                        schema?.let { put("responseJsonSchema", GeminiJsonSchema.normalize(it)) }
                    }
                }
            }
            thinking?.let { put("thinkingConfig", it) }
        }
    }

    private fun mergeConfiguredBody(mapped: JsonObject, configured: JsonObject): JsonObject {
        if (configured.isEmpty()) return mapped
        return buildJsonObject {
            configured.forEach { (key, value) ->
                when (key) {
                    "contents",
                    "systemInstruction",
                    "generationConfig",
                    "thinkingConfig",
                    "tools",
                    "toolConfig",
                    "model",
                    "stream" -> {}
                    else -> put(key, value)
                }
            }
            mapped.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun stopSequences(value: JsonElement?): JsonArray? {
        return when (value) {
            is JsonPrimitive ->
                value.contentOrNull?.let { buildJsonArray { add(JsonPrimitive(it)) } }
            is JsonArray -> value
            else -> null
        }
    }

    private fun geminiText(parts: JsonArray?): String {
        return parts.orEmpty().joinToString("") { element ->
            val part = element as? JsonObject ?: return@joinToString ""
            if (isThought(part)) return@joinToString ""
            stringField(part, "text").orEmpty()
        }
    }

    private fun geminiTextSignature(parts: JsonArray?): String? {
        parts ?: return null
        return parts
            .asReversed()
            .firstNotNullOfOrNull { element ->
                val part = element as? JsonObject ?: return@firstNotNullOfOrNull null
                if (part.containsKey("functionCall") || isThought(part))
                    return@firstNotNullOfOrNull null
                stringField(part, "thoughtSignature")
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun geminiToolCalls(parts: JsonArray?): JsonArray? {
        parts ?: return null
        return buildJsonArray {
            parts.forEach { element ->
                val part = element as? JsonObject ?: return@forEach
                val call = part["functionCall"] as? JsonObject ?: return@forEach
                val name = stringField(call, "name") ?: return@forEach
                val id =
                    stringField(call, "id")
                        ?: "call_${UUID.randomUUID().toString().replace("-", "")}"
                stringField(part, "thoughtSignature")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { rememberThoughtSignature(id, it) }
                add(
                    buildJsonObject {
                        put("id", id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", name)
                            put(
                                "arguments",
                                JsonHelper.encodeToString(
                                    call["args"] as? JsonObject ?: JsonObject(emptyMap())
                                ),
                            )
                        }
                    }
                )
            }
        }
            .takeIf { it.isNotEmpty() }
    }

    private fun toolNames(messages: JsonArray): Map<String, String> {
        val names = mutableMapOf<String, String>()
        messages.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            val toolCalls = message["tool_calls"] as? JsonArray ?: return@forEach
            toolCalls.forEach { callElement ->
                val call = callElement as? JsonObject ?: return@forEach
                val id = stringField(call, "id") ?: return@forEach
                val function = call["function"] as? JsonObject ?: return@forEach
                stringField(function, "name")?.let { names[id] = it }
            }
        }
        return names
    }

    private fun parseToolArguments(arguments: String): JsonElement {
        if (arguments.isBlank()) return JsonObject(emptyMap())
        return JsonHelper.parseToJsonElementOrNull(arguments) as? JsonObject
            ?: JsonObject(emptyMap())
    }

    private fun rememberThoughtSignature(id: String, signature: String) {
        synchronized(thoughtSignatures) {
            thoughtSignatures[id] = signature
            while (thoughtSignatures.size > MAX_REMEMBERED_SIGNATURES) {
                val first = thoughtSignatures.keys.firstOrNull() ?: break
                thoughtSignatures.remove(first)
            }
        }
    }

    private fun rememberTextSignature(text: String, signature: String) {
        if (text.isBlank()) return
        synchronized(textSignatures) {
            textSignatures[text] = signature
            while (textSignatures.size > MAX_REMEMBERED_SIGNATURES) {
                val first = textSignatures.keys.firstOrNull() ?: break
                textSignatures.remove(first)
            }
        }
    }

    private fun finishReason(reason: String?, hasToolCalls: Boolean): String {
        if (reason == null) return if (hasToolCalls) "tool_calls" else "stop"
        return when {
            reason == "STOP" || reason == "CONTINUATION" ->
                if (hasToolCalls) "tool_calls" else "stop"
            reason == "MAX_TOKENS" -> "length"
            reason in CONTENT_FILTER_REASONS -> "content_filter"
            else -> if (hasToolCalls) "tool_calls" else "stop"
        }
    }

    private fun geminiUsage(usage: JsonObject?): JsonObject? {
        usage ?: return null
        val prompt = intField(usage, "promptTokenCount") ?: 0
        val cached = intField(usage, "cachedContentTokenCount") ?: 0
        val visible = intField(usage, "candidatesTokenCount") ?: 0
        val thoughts = intField(usage, "thoughtsTokenCount") ?: 0
        val completion = visible + thoughts
        val total = intField(usage, "totalTokenCount") ?: (prompt + completion)
        return buildJsonObject {
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", total)
            putJsonObject("prompt_tokens_details") { put("cached_tokens", cached) }
            putJsonObject("completion_tokens_details") { put("reasoning_tokens", thoughts) }
        }
    }

    private fun isThought(part: JsonObject): Boolean {
        return (part["thought"] as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun openAiIncludeUsage(request: SubscriptionProxyRequest): Boolean {
        val streamOptions = request.body["stream_options"] as? JsonObject ?: return false
        return (streamOptions["include_usage"] as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun supportsThinkingLevel(effort: String): Boolean {
        val normalized = effort.lowercase()
        return normalized in setOf("minimal", "low", "medium", "high")
    }

    private fun supportsThinkingLevelModel(model: String): Boolean {
        val match = Regex("^gemini(?:-live)?-(\\d+)", RegexOption.IGNORE_CASE).find(model)
        return (match?.groupValues?.get(1)?.toIntOrNull() ?: 3) >= 3
    }

    private fun normalizeThinkingLevel(effort: String): String {
        return effort.lowercase().takeIf { it in setOf("minimal", "low", "medium", "high") }
            ?: "medium"
    }

    private fun contentText(content: JsonElement?): String {
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray ->
                content.joinToString("") { block ->
                    val item = block as? JsonObject ?: return@joinToString ""
                    stringField(item, "text").orEmpty()
                }

            else -> ""
        }
    }

    private fun stringField(element: JsonObject?, name: String): String? {
        return (element?.get(name) as? JsonPrimitive)?.contentOrNull
    }

    private fun intField(element: JsonObject?, name: String): Int? {
        return (element?.get(name) as? JsonPrimitive)?.intOrNull
    }

    private fun doubleField(element: JsonObject?, name: String): Double? {
        return (element?.get(name) as? JsonPrimitive)?.doubleOrNull
    }

    private fun omitsFunctionCallIds(model: String): Boolean {
        val match = Regex("^gemini(?:-live)?-(\\d+)", RegexOption.IGNORE_CASE).find(model)
        return match != null && (match.groupValues[1].toIntOrNull() ?: 0) < 3
    }

    private fun requiresThoughtSignatureFallback(model: String): Boolean {
        if (!Regex("(^|/)gemini-", RegexOption.IGNORE_CASE).containsMatchIn(model)) return false
        if (Regex("(^|/)gemini-(?:1|2)(?:[.-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(model))
            return false
        if (Regex("(^|/)gemini-pro(?:-vision)?$", RegexOption.IGNORE_CASE).containsMatchIn(model))
            return false
        return !Regex("(^|/)gemini-robotics-er-1\\.5(?:[.-]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(model)
    }

    private fun newToolCallId(): String = "call_${UUID.randomUUID().toString().replace("-", "")}"

    companion object {
        private const val SKIP_THOUGHT_SIGNATURE_VALIDATOR = "skip_thought_signature_validator"
        private const val MAX_REMEMBERED_SIGNATURES = 4096
        private const val MAX_REMOTE_MEDIA_BYTES = 20 * 1024 * 1024
        private val CONTENT_FILTER_REASONS =
            setOf(
                "IMAGE_SAFETY",
                "RECITATION",
                "SAFETY",
                "BLOCKLIST",
                "PROHIBITED_CONTENT",
                "SPII",
                "MODEL_ARMOR",
                "IMAGE_PROHIBITED_CONTENT",
                "IMAGE_RECITATION",
                "LANGUAGE",
            )
    }
}
