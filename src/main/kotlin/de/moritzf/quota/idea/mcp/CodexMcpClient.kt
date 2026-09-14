package de.moritzf.quota.idea.mcp

import de.moritzf.proxy.auth.AuthRequiredException
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.server.UpstreamErrorMapper
import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import de.moritzf.quota.openai.proxy.QuotaCodexCredentialsProvider
import de.moritzf.quota.openai.proxy.pdf.PdfPages
import de.moritzf.quota.shared.DefaultOutputFiles
import de.moritzf.quota.shared.DocumentLimits
import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.MultipartFilePublisher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayInputStream
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

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

    fun webSearch(
        query: String,
        searchContextSize: String = DEFAULT_SEARCH_CONTEXT_SIZE,
        includeSources: Boolean = false,
        externalWebAccess: Boolean = true,
        allowedDomains: String? = null,
        blockedDomains: String? = null,
    ): CodexMcpResponse {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return CodexMcpResponse(errorJson("Search query is required."), true)
        }
        val options = webSearchOptions(
            searchContextSize,
            includeSources,
            externalWebAccess,
            allowedDomains,
            blockedDomains,
        ) ?: return CodexMcpResponse(
            errorJson(
                "Invalid Codex web search options. searchContextSize must be one of low, medium, high; " +
                    "allowedDomains and blockedDomains must be comma-separated domain names, up to 100 each.",
            ),
            true,
        )
        return postResponses(searchRequest(trimmedQuery, options), ::parseSearchResponse)
    }

    fun imageGeneration(
        prompt: String,
        targetFile: String? = null,
        baseDirectory: Path? = null,
    ): CodexMcpResponse {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            return CodexMcpResponse(errorJson("Image prompt is required."), true)
        }
        val outputTarget = resolveImageOutput(targetFile, baseDirectory)
            ?: return CodexMcpResponse(errorJson("Provide targetFile so the image is written to disk."), true)
        outputTarget.error?.let { return CodexMcpResponse(errorJson(it), true) }

        return postResponses(imageGenerationRequest(trimmedPrompt)) { body ->
            parseImageGenerationToFileResponse(body, outputTarget.path!!, outputTarget.format!!)
        }
    }

    fun documentToMarkdown(
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        model: String = DEFAULT_CHAT_MODEL,
        pageFrom: Int? = null,
        pageTo: Int? = null,
    ): CodexMcpResponse {
        if (documentUrl.isNullOrBlank() && localFile != null && Files.isRegularFile(localFile)) {
            DocumentLimits.inlineOverflowMessage(localFile)?.let {
                return CodexMcpResponse(errorJson(it), true)
            }
        }
        documentPdfRangeError(localFile, pageFrom, pageTo)?.let {
            return CodexMcpResponse(errorJson(it), true)
        }
        val range = documentPdfRange(localFile, pageFrom, pageTo)
        val slice = range?.takeUnless { it.isFullDocument }?.let { pages ->
            val temp = Files.createTempFile("quota-pdf-slice", ".pdf")
            if (!PdfPages.writeSlice(localFile!!, pages.from, pages.to, temp)) {
                Files.deleteIfExists(temp)
                return CodexMcpResponse(errorJson("Could not extract the requested PDF page range."), true)
            }
            temp
        }
        val sendFile = slice ?: localFile
        val fileContent = documentInput(documentUrl, sendFile)
            ?: run {
                slice?.let { Files.deleteIfExists(it) }
                return CodexMcpResponse(errorJson("Provide documentUrl or a local file path."), true)
            }
        val markdownOutput = outputFile ?: DocumentMarkdown.defaultOutput(localFile)
        return try {
            postResponses(documentRequest(fileContent, model)) { body ->
                parseDocumentResponse(
                    body,
                    markdownOutput,
                    if (includeImages) localFile else null,
                    includeImages,
                    pageOffset = range?.offset ?: 0,
                    pageCount = range?.pageCount,
                    pageFrom = range?.from,
                    pageTo = range?.to,
                )
            }
        } finally {
            slice?.let { Files.deleteIfExists(it) }
        }
    }

    fun transcribe(
        audioUrl: String? = null,
        localFile: Path? = null,
        language: String? = null,
        diarize: Boolean = false,
        model: String = DEFAULT_TRANSCRIBE_MODEL,
    ): CodexMcpResponse {
        val selectedModel = if (diarize) {
            DIARIZE_TRANSCRIBE_MODEL
        } else {
            model.trim().ifBlank { DEFAULT_TRANSCRIBE_MODEL }
        }
        val fields = transcriptionFields(selectedModel, language, diarize)
        val boundary: String
        val publisher: HttpRequest.BodyPublisher
        if (localFile != null && Files.isRegularFile(localFile)) {
            boundary = "----CodexAudio${UUID.randomUUID().toString().replace("-", "")}"
            publisher = MultipartFilePublisher.of(boundary, fields, localFile)
        } else {
            val audio = resolveAudioBytes(audioUrl, null)
                ?: return CodexMcpResponse(errorJson("Provide audioUrl or a local audio file path."), true)
            val multipart = transcriptionMultipart(audio, "audio.mp3", selectedModel, language, diarize)
            boundary = multipart.boundary
            publisher = HttpRequest.BodyPublishers.ofByteArray(multipart.body)
        }
        return try {
            val response = client.requestBytes(
                TRANSCRIPTIONS_PATH,
                "POST",
                mapOf(
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                    "Accept" to "application/json",
                ),
                publisher,
            )
            if (response.statusCode() in 200..299) {
                CodexMcpResponse(McpJson.providerJsonOrRaw(String(response.body(), Charsets.UTF_8)), false)
            } else {
                val mapped = upstreamErrorMapper.map(response.statusCode(), String(response.body(), Charsets.UTF_8))
                CodexMcpResponse(mapped.body, true)
            }
        } catch (exception: AuthRequiredException) {
            CodexMcpResponse(errorJson(exception.message ?: "OpenAI login required."), true)
        } catch (exception: Exception) {
            val message = exception.message?.takeIf { it.isNotBlank() } ?: exception::class.java.simpleName
            CodexMcpResponse(errorJson(message), true)
        }
    }

    fun synthesize(
        text: String,
        targetFile: String? = null,
        baseDirectory: Path? = null,
        voiceId: String? = null,
        model: String = DEFAULT_SPEECH_MODEL,
        responseFormat: String = DEFAULT_SPEECH_FORMAT,
    ): CodexMcpResponse {
        val input = text.trim()
        if (input.isBlank()) {
            return CodexMcpResponse(errorJson("Speech text is required."), true)
        }
        val format = responseFormat.trim().ifBlank { DEFAULT_SPEECH_FORMAT }
        val output = resolveSpeechOutput(targetFile, baseDirectory, format)
            ?: return CodexMcpResponse(errorJson("Provide targetFile so the audio is written to disk."), true)
        val body = speechRequestJson(
            input,
            voiceId?.trim()?.ifBlank { null } ?: DEFAULT_SPEECH_VOICE,
            model.trim().ifBlank { DEFAULT_SPEECH_MODEL },
            format,
        )
        return try {
            val response = client.requestBytes(
                SPEECH_PATH,
                "POST",
                body.toByteArray(Charsets.UTF_8),
                mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/octet-stream",
                ),
            )
            if (response.statusCode() !in 200..299) {
                val mapped = upstreamErrorMapper.map(response.statusCode(), String(response.body(), Charsets.UTF_8))
                return CodexMcpResponse(mapped.body, true)
            }
            val parent = output.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            Files.write(output, response.body())
            CodexMcpResponse(
                JsonSupport.json.encodeToString(CodexSpeechWriteResult(output.toString(), response.body().size.toLong())),
                false,
            )
        } catch (exception: AuthRequiredException) {
            CodexMcpResponse(errorJson(exception.message ?: "OpenAI login required."), true)
        } catch (exception: Exception) {
            val message = exception.message?.takeIf { it.isNotBlank() } ?: exception::class.java.simpleName
            CodexMcpResponse(errorJson(message), true)
        }
    }

    fun listVoices(): CodexMcpResponse {
        return CodexMcpResponse(JsonSupport.json.encodeToString(CodexVoiceList(CODEX_VOICES)), false)
    }

    private fun resolveAudioBytes(audioUrl: String?, localFile: Path?): ByteArray? {
        if (localFile != null) {
            if (!Files.isRegularFile(localFile)) {
                return null
            }
            return Files.readAllBytes(localFile)
        }
        val url = audioUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            return null
        }
        return runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                .send(
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                .takeIf { it.statusCode() in 200..299 }
                ?.body()
        }.getOrNull()
    }

    private fun postResponses(
        request: JsonObject,
        parser: (String) -> CodexMcpResponse,
    ): CodexMcpResponse {
        return try {
            val response = client.requestString(
                RESPONSES_PATH,
                "POST",
                request.toString(),
                mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "text/event-stream",
                ),
            )
            if (response.statusCode() in 200..299) {
                parser(response.body())
            } else {
                val mapped = upstreamErrorMapper.map(response.statusCode(), response.body())
                CodexMcpResponse(mapped.body, true)
            }
        } catch (exception: AuthRequiredException) {
            CodexMcpResponse(errorJson(exception.message ?: "OpenAI login required."), true)
        } catch (exception: Exception) {
            val message = exception.message?.takeIf { it.isNotBlank() } ?: exception::class.java.simpleName
            CodexMcpResponse(errorJson(message), true)
        }
    }

    private fun searchRequest(query: String, options: WebSearchOptions): JsonObject {
        return buildJsonObject {
            put("model", FAST_CHAT_MODEL)
            put("instructions", SEARCH_INSTRUCTIONS)
            putJsonArray("input") {
                add(messageInput("Search the web for: $query"))
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "web_search")
                    put("external_web_access", options.externalWebAccess)
                    put("search_context_size", options.searchContextSize)
                    putJsonArray("search_content_types") { add("text") }
                    if (options.allowedDomains.isNotEmpty() || options.blockedDomains.isNotEmpty()) {
                        put("filters", buildJsonObject {
                            if (options.allowedDomains.isNotEmpty()) {
                                putJsonArray("allowed_domains") {
                                    options.allowedDomains.forEach { add(it) }
                                }
                            }
                            if (options.blockedDomains.isNotEmpty()) {
                                putJsonArray("blocked_domains") {
                                    options.blockedDomains.forEach { add(it) }
                                }
                            }
                        })
                    }
                })
            }
            if (options.includeSources) {
                putJsonArray("include") { add("web_search_call.action.sources") }
            }
            put("store", false)
            put("stream", true)
        }
    }

    private fun imageGenerationRequest(prompt: String): JsonObject {
        return buildJsonObject {
            put("model", FAST_CHAT_MODEL)
            put("instructions", IMAGE_GENERATION_INSTRUCTIONS)
            putJsonArray("input") {
                add(messageInput(prompt))
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "image_generation")
                    put("output_format", "png")
                })
            }
            put("store", false)
            put("stream", true)
        }
    }

    private fun documentRequest(fileContent: JsonObject, model: String): JsonObject {
        return buildJsonObject {
            put("model", model.trim().ifBlank { DEFAULT_CHAT_MODEL })
            put("instructions", DOCUMENT_INSTRUCTIONS)
            putJsonArray("input") {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        add(fileContent)
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", DOCUMENT_PROMPT)
                        })
                    }
                })
            }
            put("store", false)
            put("stream", true)
        }
    }

    private fun documentInput(documentUrl: String?, localFile: Path?): JsonObject? {
        val url = documentUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            return if (isImageName(url)) {
                buildJsonObject {
                    put("type", "input_image")
                    put("image_url", url)
                }
            } else {
                buildJsonObject {
                    put("type", "input_file")
                    put("file_url", url)
                }
            }
        }
        val path = localFile ?: return null
        if (!Files.isRegularFile(path)) {
            return null
        }
        val bytes = Files.readAllBytes(path)
        val mime = mimeType(path, bytes)
        val dataUrl = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
        return if (mime.startsWith("image/")) {
            buildJsonObject {
                put("type", "input_image")
                put("image_url", dataUrl)
            }
        } else {
            buildJsonObject {
                put("type", "input_file")
                put("filename", path.fileName.toString())
                put("file_data", dataUrl)
            }
        }
    }

    private fun parseDocumentResponse(
        body: String,
        outputFile: Path?,
        sourceFile: Path?,
        includeImages: Boolean = true,
        pageOffset: Int = 0,
        pageCount: Int? = null,
        pageFrom: Int? = null,
        pageTo: Int? = null,
    ): CodexMcpResponse {
        val output = StringBuilder()
        var outputTextDone: String? = null
        for (event in responsesDataEvents(body)) {
            failedMessage(event)?.let { return CodexMcpResponse(errorJson(it), true) }
            when (event.string("type")) {
                "response.output_text.delta" -> output.append(event.string("delta").orEmpty())
                "response.output_text.done" -> outputTextDone = event.string("text")
            }
        }
        val text = outputTextDone?.takeIf { it.isNotBlank() } ?: output.toString()
        if (text.isBlank()) {
            return CodexMcpResponse(errorJson("Codex document conversion returned no output."), true)
        }
        return try {
            val applied = DocumentImageGrounding.apply(
                text,
                sourceFile,
                outputFile?.parent ?: sourceFile?.parent,
                includeImages,
                pageOffset,
            )
            CodexMcpResponse(
                DocumentMarkdown.resultJson(
                    applied.markdown,
                    outputFile,
                    applied.imageFiles,
                    pageCount,
                    pageFrom,
                    pageTo,
                ),
                false,
            )
        } catch (exception: Exception) {
            CodexMcpResponse(errorJson(exception.message ?: "Could not write markdown."), true)
        }
    }

    private fun documentPdfRange(localFile: Path?, pageFrom: Int?, pageTo: Int?): PdfPages.Range? {
        if (localFile == null || !PdfPages.isPdf(localFile)) return null
        val count = PdfPages.pageCount(localFile) ?: return null
        return PdfPages.resolve(count, pageFrom, pageTo)
    }

    private fun documentPdfRangeError(localFile: Path?, pageFrom: Int?, pageTo: Int?): String? {
        if (pageFrom == null && pageTo == null) {
            return null
        }
        if (localFile == null || !PdfPages.isPdf(localFile)) {
            return "pageFrom and pageTo require a local PDF."
        }
        val count = PdfPages.pageCount(localFile) ?: return "Could not read PDF page count."
        if (PdfPages.resolve(count, pageFrom, pageTo) == null) {
            return "pageFrom/pageTo out of range (document has $count pages)."
        }
        return null
    }

    private fun messageInput(text: String): JsonObject {
        return buildJsonObject {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
        }
    }

    private fun parseSearchResponse(body: String): CodexMcpResponse {
        val output = StringBuilder()
        var outputTextDone: String? = null
        var responseId: String? = null
        var webSearchUsage: JsonObject? = null
        var toolUsage: JsonObject? = null
        val webSearchCalls = mutableListOf<JsonObject>()
        val seenWebSearchCalls = mutableSetOf<String>()
        val annotations = mutableListOf<JsonObject>()
        val seenAnnotations = mutableSetOf<String>()

        for (event in responsesDataEvents(body)) {
            failedMessage(event)?.let { return CodexMcpResponse(errorJson(it), true) }
            when (event.string("type")) {
                "response.output_text.delta" -> output.append(event.string("delta").orEmpty())
                "response.output_text.done" -> outputTextDone = event.string("text")
                "response.output_item.done" -> collectSearchMetadata(
                    event.obj("item"),
                    webSearchCalls,
                    seenWebSearchCalls,
                    annotations,
                    seenAnnotations,
                )
                "response.completed" -> {
                    val response = event.obj("response")
                    responseId = response?.string("id") ?: responseId
                    webSearchUsage = response?.obj("web_search") ?: webSearchUsage
                    toolUsage = response?.obj("tool_usage") ?: toolUsage
                    response?.array("output")?.forEach { item ->
                        collectSearchMetadata(
                            item.asObjectOrNull(),
                            webSearchCalls,
                            seenWebSearchCalls,
                            annotations,
                            seenAnnotations,
                        )
                    }
                }
            }
        }

        val text = outputTextDone?.takeIf { it.isNotBlank() } ?: output.toString()
        if (text.isBlank()) {
            return CodexMcpResponse(errorJson("Codex web search returned no output."), true)
        }

        return CodexMcpResponse(buildJsonObject {
            put("output", text)
            responseId?.let { put("response_id", it) }
            webSearchUsage?.let { put("web_search", it) }
            toolUsage?.let { put("tool_usage", it) }
            if (webSearchCalls.isNotEmpty()) {
                putJsonArray("web_search_calls") { webSearchCalls.forEach { add(it) } }
            }
            if (annotations.isNotEmpty()) {
                putJsonArray("annotations") { annotations.forEach { add(it) } }
            }
        }.toString(), false)
    }

    private fun collectSearchMetadata(
        item: JsonObject?,
        webSearchCalls: MutableList<JsonObject>,
        seenWebSearchCalls: MutableSet<String>,
        annotations: MutableList<JsonObject>,
        seenAnnotations: MutableSet<String>,
    ) {
        when (item?.string("type")) {
            "web_search_call" -> addUniqueJsonObject(item, webSearchCalls, seenWebSearchCalls)
            "message" -> item.array("content")?.forEach { content ->
                val contentObject = content.asObjectOrNull() ?: return@forEach
                contentObject.array("annotations")?.forEach { annotation ->
                    addUniqueJsonObject(annotation.asObjectOrNull() ?: return@forEach, annotations, seenAnnotations)
                }
            }
        }
    }

    private fun addUniqueJsonObject(
        value: JsonObject,
        target: MutableList<JsonObject>,
        seen: MutableSet<String>,
    ) {
        val key = value.string("id") ?: value.toString()
        if (seen.add(key)) {
            target += value
        }
    }

    private fun webSearchOptions(
        searchContextSize: String,
        includeSources: Boolean,
        externalWebAccess: Boolean,
        allowedDomains: String?,
        blockedDomains: String?,
    ): WebSearchOptions? {
        val contextSize = searchContextSize.trim().lowercase(Locale.ROOT)
            .takeIf { it in SEARCH_CONTEXT_SIZES }
            ?: return null
        val allowed = parseDomainList(allowedDomains) ?: return null
        val blocked = parseDomainList(blockedDomains) ?: return null
        return WebSearchOptions(contextSize, includeSources, externalWebAccess, allowed, blocked)
    }

    private fun parseDomainList(rawDomains: String?): List<String>? {
        val domains = rawDomains
            ?.split(',', '\n')
            ?.map { it.trim().lowercase(Locale.ROOT).trim('.') }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (domains.size > MAX_SEARCH_FILTER_DOMAINS) {
            return null
        }
        return domains.takeIf { list -> list.all { DOMAIN_PATTERN.matches(it) } }
    }

    private fun parseImageGenerationToFileResponse(
        body: String,
        targetFile: Path,
        format: String,
    ): CodexMcpResponse {
        firstFailedMessage(body)?.let { return CodexMcpResponse(errorJson(it), true) }
        val imageResult = imageGenerationResult(body)
            ?: return CodexMcpResponse(errorJson("Codex image generation returned no image data."), true)

        val writtenBytes = writeImageFile(imageResult.b64Json, targetFile, format)
            ?: return CodexMcpResponse(errorJson("Could not write generated image as $format."), true)

        return CodexMcpResponse(buildJsonObject {
            imageResult.responseId?.let { put("response_id", it) }
            put("output_file", targetFile.toString())
            put("format", format)
            put("bytes", writtenBytes)
            imageResult.revisedPrompt?.let { put("revised_prompt", it) }
            imageResult.toolUsage?.let { put("tool_usage", it) }
        }.toString(), false)
    }

    private fun imageGenerationResult(body: String): ImageGenerationResult? {
        var responseId: String? = null
        var revisedPrompt: String? = null
        var b64Json: String? = null
        var toolUsage: JsonObject? = null

        for (event in responsesDataEvents(body)) {
            when (event.string("type")) {
                "response.output_item.done" -> {
                    val item = event.obj("item")
                    if (item?.string("type") == "image_generation_call") {
                        revisedPrompt = item.string("revised_prompt") ?: revisedPrompt
                        b64Json = item.string("result") ?: b64Json
                    }
                }
                "response.completed" -> {
                    val response = event.obj("response")
                    responseId = response?.string("id") ?: responseId
                    toolUsage = response?.obj("tool_usage") ?: toolUsage
                }
            }
        }

        val image = b64Json?.takeIf { it.isNotBlank() }
            ?: return null

        return ImageGenerationResult(responseId, revisedPrompt, image, toolUsage)
    }

    private fun firstFailedMessage(body: String): String? {
        return responsesDataEvents(body).firstNotNullOfOrNull(::failedMessage)
    }

    private fun resolveImageOutput(targetFile: String?, baseDirectory: Path?): ImageOutputTarget? {
        val trimmed = targetFile?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed == null && baseDirectory == null) {
            return null
        }
        return resolveImageOutputTarget(trimmed ?: defaultImageFileName(), baseDirectory)
    }

    private fun resolveImageOutputTarget(targetFile: String, baseDirectory: Path?): ImageOutputTarget? {
        val trimmedTarget = targetFile.trim()
        if (trimmedTarget.isBlank()) {
            return null
        }
        val path = try {
            Path.of(trimmedTarget)
        } catch (exception: InvalidPathException) {
            return ImageOutputTarget(error = exception.message ?: "Invalid image target file path.")
        }
        if (path.isAbsolute) {
            return ImageOutputTarget(error = "Image target file must be relative to the project directory.")
        }
        val base = (baseDirectory ?: Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize()
        val resolved = base.resolve(path).normalize()
        if (!resolved.startsWith(base)) {
            return ImageOutputTarget(error = "Image target file must stay inside the project directory.")
        }
        val format = resolved.fileName?.toString()
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return ImageOutputTarget(error = "Image target file must include an extension.")
        if (format !in SUPPORTED_IMAGE_FORMATS) {
            return ImageOutputTarget(
                error = "Unsupported image format '$format'. Supported formats: ${SUPPORTED_IMAGE_FORMATS.sorted().joinToString(", ")}.",
            )
        }
        return ImageOutputTarget(path = resolved, format = format)
    }

    private fun writeImageFile(b64Json: String, targetFile: Path, format: String): Long? {
        val imageBytes = runCatching { Base64.getDecoder().decode(b64Json) }.getOrNull() ?: return null
        val image = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return null
        val parent = targetFile.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        val written = ImageIO.write(image, format, targetFile.toFile())
        if (!written) {
            return null
        }
        return Files.size(targetFile)
    }

    private fun responsesDataEvents(body: String): Sequence<JsonObject> {
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotBlank() && it != "[DONE]" }
            .mapNotNull { line ->
                runCatching { JsonSupport.json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            }
    }

    private fun failedMessage(event: JsonObject): String? {
        if (event.string("type") != "response.failed") {
            return null
        }
        val error = event.obj("response")?.obj("error") ?: event.obj("error")
        return error?.string("message")
            ?: error?.string("code")
            ?: "Codex response failed."
    }

    private fun JsonObject.string(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.obj(name: String): JsonObject? {
        return this[name].asObjectOrNull()
    }

    private fun JsonObject.array(name: String): JsonArray? {
        return this[name].asArrayOrNull()
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? {
        return this as? JsonObject
    }

    private fun JsonElement?.asArrayOrNull(): JsonArray? {
        return this as? JsonArray
    }

    data class CodexMcpResponse(val body: String, val isError: Boolean)

    private data class WebSearchOptions(
        val searchContextSize: String,
        val includeSources: Boolean,
        val externalWebAccess: Boolean,
        val allowedDomains: List<String>,
        val blockedDomains: List<String>,
    )

    private data class ImageGenerationResult(
        val responseId: String?,
        val revisedPrompt: String?,
        val b64Json: String,
        val toolUsage: JsonObject?,
    )

    private data class ImageOutputTarget(
        val path: Path? = null,
        val format: String? = null,
        val error: String? = null,
    )

    companion object {
        private const val RESPONSES_PATH = "/responses"
        private const val TRANSCRIPTIONS_PATH = "/audio/transcriptions"
        private const val SPEECH_PATH = "/audio/speech"
        private const val DEFAULT_CHAT_MODEL = "gpt-5.6-sol"
        private const val FAST_CHAT_MODEL = "gpt-5.6-luna"
        const val DEFAULT_TRANSCRIBE_MODEL = "gpt-transcribe"
        const val DEFAULT_SPEECH_MODEL = "gpt-4o-mini-tts"
        const val DEFAULT_SPEECH_FORMAT = "mp3"
        const val DEFAULT_SPEECH_VOICE = "coral"
        private const val DIARIZE_TRANSCRIBE_MODEL = "gpt-4o-transcribe-diarize"
        private const val DEFAULT_SEARCH_CONTEXT_SIZE = "medium"
        private const val MAX_SEARCH_FILTER_DOMAINS = 100
        private const val SEARCH_INSTRUCTIONS = "You are a concise assistant. Use web search when needed."
        private const val IMAGE_GENERATION_INSTRUCTIONS =
            "Use the image_generation tool to satisfy image requests. Return no extra commentary."
        private const val DOCUMENT_INSTRUCTIONS = DocumentImageGrounding.INSTRUCTIONS
        private const val DOCUMENT_PROMPT = DocumentImageGrounding.PROMPT
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

        internal fun defaultImageFileName(): String = "image-${UUID.randomUUID()}.png"

        internal fun isImageName(value: String): Boolean {
            val name = value.substringAfterLast('/').substringBefore('?').lowercase(Locale.ROOT)
            return name.substringAfterLast('.', "") in IMAGE_EXTENSIONS
        }

        internal fun mimeType(path: Path, bytes: ByteArray): String {
            if (bytes.size >= 5 && bytes.decodeToString(0, 5) == "%PDF-") return "application/pdf"
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) return "image/png"
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            return when (path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
        }
        private val SEARCH_CONTEXT_SIZES = setOf("low", "medium", "high")
        private val DOMAIN_PATTERN = Regex("^[a-z0-9.-]+$")
        private val SUPPORTED_IMAGE_FORMATS = ImageIO.getWriterFormatNames()
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

        fun createDefault(): CodexMcpClient {
            return CodexMcpClient(
                accessTokenProvider = {
                    resolvedOpenAiAccount()?.let { account ->
                        QuotaAuthService.getInstance().getAccessTokenBlocking(account.id, QuotaProviderType.OPEN_AI)
                    }
                },
                accountIdProvider = {
                    resolvedOpenAiAccount()?.let { account ->
                        QuotaAuthService.getInstance().getAccountId(account.id, QuotaProviderType.OPEN_AI)
                    }
                },
                tokenRefresher = { staleToken ->
                    refreshOpenAiToken(staleToken)
                },
            )
        }

        private fun resolvedOpenAiAccount(): de.moritzf.quota.idea.settings.ProviderAccount? {
            return de.moritzf.quota.idea.settings.AccountResolver.resolveOrNull(
                QuotaProviderType.OPEN_AI,
                capability = de.moritzf.quota.idea.settings.AccountCapability.WEB_SEARCH,
            )
        }

        private fun refreshOpenAiToken(staleToken: String?): String? {
            val auth = QuotaAuthService.getInstance()
            val settings = runCatching { de.moritzf.quota.idea.settings.QuotaSettingsState.getInstance() }.getOrNull()
            val owner = staleToken?.let { token ->
                settings?.accountsOf(QuotaProviderType.OPEN_AI)?.firstOrNull { account ->
                    auth.peekAccessToken(account.id, QuotaProviderType.OPEN_AI) == token
                }
            }
            val account = owner ?: resolvedOpenAiAccount() ?: return null
            return auth.forceRefreshBlocking(account.id, QuotaProviderType.OPEN_AI, staleToken)
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
                listOf(DEFAULT_CHAT_MODEL, FAST_CHAT_MODEL),
                null,
                upstreamBaseUri.toString(),
                ServerConfig.DEFAULT_CLIENT_ID,
                null,
                null,
                "",
                false,
                emptyMap(),
                null,
                false,
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
            return McpJson.error(message)
        }

        internal fun speechRequestJson(input: String, voice: String, model: String, format: String): String {
            return buildJsonObject {
                put("model", model)
                put("input", input)
                put("voice", voice)
                put("response_format", format)
            }.toString()
        }

        internal fun transcriptionFields(
            model: String,
            language: String?,
            diarize: Boolean,
        ): List<Pair<String, String>> {
            return buildList {
                add("model" to model)
                if (diarize) add("response_format" to "diarized_json")
                language?.trim()?.takeIf { it.isNotEmpty() }?.let { add("language" to it) }
            }
        }

        internal fun transcriptionMultipart(
            fileBytes: ByteArray,
            filename: String,
            model: String,
            language: String?,
            diarize: Boolean,
        ): MultipartBody {
            val boundary = "----CodexAudio${UUID.randomUUID().toString().replace("-", "")}"
            val preamble = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"model\"\r\n\r\n").append(model).append("\r\n")
                if (diarize) {
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\ndiarized_json\r\n")
                }
                language?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"language\"\r\n\r\n").append(value).append("\r\n")
                }
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }.toByteArray()
            val closing = "\r\n--$boundary--\r\n".toByteArray()
            val payload = ByteArray(preamble.size + fileBytes.size + closing.size)
            System.arraycopy(preamble, 0, payload, 0, preamble.size)
            System.arraycopy(fileBytes, 0, payload, preamble.size, fileBytes.size)
            System.arraycopy(closing, 0, payload, preamble.size + fileBytes.size, closing.size)
            return MultipartBody(boundary, payload)
        }

        internal fun resolveSpeechOutput(targetFile: String?, baseDirectory: Path?, format: String): Path? {
            val trimmed = targetFile?.trim()?.takeIf { it.isNotBlank() }
            if (trimmed != null) {
                val path = Path.of(trimmed)
                return if (path.isAbsolute || baseDirectory == null) path.normalize() else baseDirectory.resolve(path).normalize()
            }
            return baseDirectory?.resolve(DefaultOutputFiles.speech(format))
        }

        internal val CODEX_VOICES = listOf(
            "alloy", "ash", "ballad", "coral", "echo", "fable", "nova",
            "onyx", "sage", "shimmer", "verse", "marin", "cedar",
        ).map { CodexVoice(it, it) }
    }

    internal data class MultipartBody(val boundary: String, val body: ByteArray)
}

@Serializable
internal data class CodexSpeechWriteResult(
    @SerialName("output_file") val outputFile: String,
    val bytes: Long,
)

@Serializable
internal data class CodexVoiceList(
    val voices: List<CodexVoice>,
)

@Serializable
internal data class CodexVoice(
    val id: String,
    val name: String,
)
