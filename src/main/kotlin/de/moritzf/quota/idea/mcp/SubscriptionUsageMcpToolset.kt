package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.resolveInProject
import kotlinx.coroutines.currentCoroutineContext
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.McpAccountToolStatus
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuotaException
import de.moritzf.quota.kimi.KimiWebSearchClient
import de.moritzf.quota.minimax.MiniMaxAudioClient
import de.moritzf.quota.minimax.MiniMaxImageClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.MiniMaxWebSearchClient
import de.moritzf.quota.mistral.MistralAudioClient
import de.moritzf.quota.mistral.MistralImageClient
import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralQuotaException
import de.moritzf.quota.mistral.MistralWebSearchClient
import de.moritzf.quota.ollama.OllamaQuotaException
import de.moritzf.quota.ollama.OllamaWebSearchClient
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.McpProviderToolStatus
import de.moritzf.quota.supergrok.SuperGrokAudioClient
import de.moritzf.quota.supergrok.SuperGrokDocumentClient
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import de.moritzf.quota.supergrok.SuperGrokWebSearchClient
import de.moritzf.quota.zai.ZaiAudioClient
import de.moritzf.quota.zai.ZaiImageClient
import de.moritzf.quota.zai.ZaiOcrClient
import de.moritzf.quota.zai.ZaiQuotaException
import de.moritzf.quota.zai.ZaiVideoClient
import de.moritzf.quota.zai.ZaiWebSearchClient
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Exposes subscription usage JSON and hosted subscription tools through IntelliJ's MCP server. */
class SubscriptionUsageMcpToolset(
    private val codexClient: CodexMcpClient = CodexMcpClient.createDefault(),
    private val kimiSearchClient: KimiWebSearchClient = KimiWebSearchClient.createDefault(),
    private val zaiSearchClient: ZaiWebSearchClient = ZaiWebSearchClient.createDefault(),
    private val miniMaxSearchClient: MiniMaxWebSearchClient = MiniMaxWebSearchClient.createDefault(),
    private val miniMaxImageClient: MiniMaxImageClient = MiniMaxImageClient.createDefault(),
    private val miniMaxAudioClient: MiniMaxAudioClient = MiniMaxAudioClient.createDefault(),
    private val ollamaSearchClient: OllamaWebSearchClient = OllamaWebSearchClient.createDefault(),
    private val superGrokSearchClient: SuperGrokWebSearchClient = SuperGrokWebSearchClient.createDefault(),
    private val superGrokImagineClient: SuperGrokImagineClient = SuperGrokImagineClient.createDefault(),
    private val superGrokAudioClient: SuperGrokAudioClient = SuperGrokAudioClient.createDefault(),
    private val superGrokDocumentClient: SuperGrokDocumentClient = SuperGrokDocumentClient.createDefault(),
    private val mistralSearchClient: MistralWebSearchClient = MistralWebSearchClient.createDefault(),
    private val mistralImageClient: MistralImageClient = MistralImageClient.createDefault(),
    private val mistralOcrClient: MistralOcrClient = MistralOcrClient.createDefault(),
    private val mistralAudioClient: MistralAudioClient = MistralAudioClient.createDefault(),
    private val zaiOcrClient: ZaiOcrClient = ZaiOcrClient.createDefault(),
    private val zaiImageClient: ZaiImageClient = ZaiImageClient.createDefault(),
    private val zaiAudioClient: ZaiAudioClient = ZaiAudioClient.createDefault(),
    private val zaiVideoClient: ZaiVideoClient = ZaiVideoClient.createDefault(),
) : McpToolset {
    @McpTool(name = "subscription_quota")
    @McpDescription(description = "Returns the latest subscription quota response JSON for the selected provider.")
    suspend fun subscription_quota(
        @McpDescription(description = "Provider to query. Supported providers are derived from the shared provider enum.") provider: QuotaProviderType,
        @McpDescription(description = "Optional account name or id when more than one login of this type exists.") account: String? = null,
    ): String {
        return quotaResult(provider, account)
    }

    @McpTool(name = "subscription_tools_status")
    @McpDescription(description = "Returns per-account status: credential presence, cached quota freshness, limiting pool, reset time, and whether a requested operation can run. Optional capability/model uses cached quota only and does not call provider APIs.")
    suspend fun subscription_tools_status(
        @McpDescription(description = "Optional operation to evaluate against cached quota, such as PROXY or WEB_SEARCH.") capability: de.moritzf.quota.idea.settings.AccountCapability? = null,
        @McpDescription(description = "Optional model id when availability depends on a specific pool, such as gpt-5.6-luna versus gpt-reserve.") model: String? = null,
    ): String {
        val settings = runCatching { QuotaSettingsState.getInstance() }.getOrNull()
        val accounts = settings?.accounts.orEmpty()
        val requestedCapability = capability ?: de.moritzf.quota.idea.settings.AccountCapability.QUOTA
        val statuses = if (accounts.isEmpty() || settings == null) {
            ProviderCatalog.all.map { descriptor ->
                accountStatus(
                    id = descriptor.type.id,
                    type = descriptor.type,
                    name = descriptor.type.displayName,
                    label = descriptor.type.displayName,
                    isDefault = true,
                    allowFailover = false,
                    descriptor = descriptor,
                    capability = requestedCapability,
                    model = model,
                )
            }
        } else {
            accounts.mapNotNull { account ->
                val type = account.providerType() ?: return@mapNotNull null
                val descriptor = ProviderCatalog.get(type)
                accountStatus(
                    id = account.id,
                    type = type,
                    name = account.name,
                    label = settings.accountListLabel(account),
                    isDefault = account.isDefault,
                    allowFailover = account.allowFailover,
                    descriptor = descriptor,
                    capability = requestedCapability,
                    model = model,
                )
            }
        }
        return McpJson.accountToolsStatus(
            statuses,
            capability = capability?.name,
            model = model?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    @McpTool(name = "codex_web_search")
    @McpDescription(description = "Runs a Codex subscription-backed web search using the existing OpenAI login and returns the Codex JSON response.")
    suspend fun codex_web_search(
        @McpDescription(description = "Search query to send to Codex web search.") query: String,
        @McpDescription(description = "Search context size: low, medium, or high. Higher values can improve detailed answers but may cost more and take longer.") searchContextSize: String = "medium",
        @McpDescription(description = "Whether to request the complete sources list from the web search call when available.") includeSources: Boolean = false,
        @McpDescription(description = "Whether the hosted search tool may fetch live web content. Set false for cached/indexed results only.") externalWebAccess: Boolean = true,
        @McpDescription(description = "Optional comma-separated domains to allow, for example openai.com,example.org. Leave blank for no allow filter.") allowedDomains: String? = null,
        @McpDescription(description = "Optional comma-separated domains to block, for example reddit.com,quora.com. Leave blank for no block filter.") blockedDomains: String? = null,
    ): String {
        val response = codexClient.webSearch(
            query,
            searchContextSize,
            includeSources,
            externalWebAccess,
            allowedDomains,
            blockedDomains,
        )
        return if (response.isError) searchError(extractErrorMessage(response.body)) else response.body
    }

    @McpTool(name = "supergrok_web_search")
    @McpDescription(description = "Runs a SuperGrok/xAI web search using the existing SuperGrok login and returns normalized JSON results.")
    suspend fun supergrok_web_search(
        @McpDescription(description = "Search query to send to Grok web search.") query: String,
        @McpDescription(description = "xAI model to use for the Responses API web search request.") model: String = SuperGrokWebSearchClient.DEFAULT_MODEL,
        @McpDescription(description = "Optional comma-separated domains to allow, up to 5. Leave blank for no allow filter.") allowedDomains: String? = null,
        @McpDescription(description = "Optional comma-separated domains to exclude, up to 5. Leave blank for no exclude filter.") excludedDomains: String? = null,
        @McpDescription(description = "Maximum output tokens for the Grok answer. Values are clamped to Grok's safe local range.") maxOutputTokens: Int = SuperGrokWebSearchClient.DEFAULT_MAX_OUTPUT_TOKENS,
    ): String {
        return supergrokWebSearch(query, model, allowedDomains, excludedDomains, maxOutputTokens)
    }

    @McpTool(name = "subscription_web_search")
    @McpDescription(description = "Runs a result-list subscription-backed web search (Kimi, Z.ai, MiniMax, or Ollama) and returns the provider JSON response.")
    suspend fun subscription_web_search(
        @McpDescription(description = "Provider to use. Supported providers are derived from the ListSearchProvider enum.") provider: ListSearchProvider = ListSearchProvider.KIMI,
        @McpDescription(description = "Search query.") query: String,
        @McpDescription(description = "Number of search results to request. Values are clamped to the provider's supported range.") limit: Int = 5,
        @McpDescription(description = "Whether to include full result content in addition to snippets. This can substantially increase response size.") includeContent: Boolean = false,
    ): String {
        return when (provider) {
            ListSearchProvider.KIMI -> kimiWebSearch(query, limit, includeContent)
            ListSearchProvider.ZAI -> zaiWebSearch(query, limit, includeContent)
            ListSearchProvider.MINIMAX -> miniMaxWebSearch(query, limit, includeContent)
            ListSearchProvider.OLLAMA -> ollamaWebSearch(query, limit, includeContent)
        }
    }

    @McpTool(name = "subscription_web_fetch")
    @McpDescription(description = "Fetches a web page through a subscription-backed provider and returns the provider JSON (title, content, links). PDF conversion stays on subscription_document_to_markdown.")
    suspend fun subscription_web_fetch(
        @McpDescription(description = "Provider to use. Supported providers are derived from the WebFetchProvider enum.") provider: WebFetchProvider = WebFetchProvider.OLLAMA,
        @McpDescription(description = "Page URL to fetch.") url: String,
    ): String {
        return when (provider) {
            WebFetchProvider.OLLAMA -> ollamaWebFetch(url)
            WebFetchProvider.ZAI -> zaiWebFetch(url)
        }
    }

    @McpTool(name = "subscription_image_edit")
    @McpDescription(description = "Edits an existing image with SuperGrok/xAI JSON image edits. Provide imageUrl or localFile. Masks are not supported. Returns a URL or writes targetFile. Never returns base64.")
    suspend fun subscription_image_edit(
        @McpDescription(description = "Edit prompt.") prompt: String,
        @McpDescription(description = "Provider. Only SuperGrok is supported.") provider: ImageEditProvider = ImageEditProvider.SUPERGROK,
        @McpDescription(description = "Public source image URL. Leave blank when localFile is set.") imageUrl: String? = null,
        @McpDescription(description = "Optional project-relative or absolute local source image.") localFile: String? = null,
        @McpDescription(description = "Optional mask image URL. Unsupported; the call fails if set.") maskUrl: String? = null,
        @McpDescription(description = "Optional relative project path for the edited image.") targetFile: String? = null,
        @McpDescription(description = "Image model id. Leave blank for the provider default.") model: String = "",
    ): String {
        if (!maskUrl.isNullOrBlank()) {
            return errorResult("xAI image edits do not support masks. Send the source image only.")
        }
        return when (provider) {
            ImageEditProvider.SUPERGROK -> superGrokImageEdit(prompt, imageUrl, localFile, targetFile, model)
        }
    }

    @McpTool(name = "subscription_image_generation")
    @McpDescription(description = "Generates one image through a subscription-backed provider. Without targetFile, SuperGrok, Z.ai, and MiniMax return an image URL; OpenAI/Codex and Mistral write a unique image-<uuid>.png in the project. With targetFile, the image is written to that path. Never returns base64.")
    suspend fun subscription_image_generation(
        @McpDescription(description = "Image prompt.") prompt: String,
        @McpDescription(description = "Provider to use. Supported providers are derived from the ImageGenerationProvider enum.") provider: ImageGenerationProvider = ImageGenerationProvider.OPEN_AI,
        @McpDescription(description = "Optional relative project path for the generated image (for example out/image.png). Leave blank for a download URL, or a unique image-<uuid>.png for OpenAI/Codex and Mistral.") targetFile: String? = null,
    ): String {
        return when (provider) {
            ImageGenerationProvider.OPEN_AI ->
                codexResult(codexClient.imageGeneration(prompt, targetFile, projectBaseDirectory()))

            ImageGenerationProvider.SUPERGROK ->
                superGrokImageGeneration(prompt, targetFile)

            ImageGenerationProvider.MISTRAL ->
                mistralImageGeneration(prompt, targetFile)

            ImageGenerationProvider.ZAI ->
                zaiImageGeneration(prompt, targetFile)

            ImageGenerationProvider.MINIMAX ->
                miniMaxImageGeneration(prompt, targetFile)
        }
    }

    @McpTool(name = "mistral_web_search")
    @McpDescription(description = "Runs a Mistral Conversations web search using the stored Mistral API key and returns the provider JSON response.")
    suspend fun mistral_web_search(
        @McpDescription(description = "Search query to send to Mistral web search.") query: String,
        @McpDescription(description = "Mistral model id for the Conversations request.") model: String = MistralWebSearchClient.DEFAULT_MODEL,
        @McpDescription(description = "When true, use web_search_premium instead of web_search.") premium: Boolean = false,
    ): String {
        return mistralWebSearch(query, model, premium)
    }

    @McpTool(name = "subscription_document_to_markdown")
    @McpDescription(description = "Converts a PDF or image to markdown. Mistral and Z.ai use dedicated OCR and extract embedded images to disk. OpenAI/Codex and SuperGrok send the original document to their vision models and, for a local PDF with includeImages=true, render cropped image regions to disk as image-p<page>-<index>.png. For large PDFs on Codex/SuperGrok, pass 1-based pageFrom/pageTo to convert a slice (the whole file is one model request; overflow fails). The result includes page_count so you can walk remaining pages. If outputFile is omitted and localFile is set, markdown is written beside the source as <name>.md. Images are never returned as base64.")
    suspend fun subscription_document_to_markdown(
        @McpDescription(description = "Provider to use. Supported providers are derived from the DocumentToMarkdownProvider enum.") provider: DocumentToMarkdownProvider = DocumentToMarkdownProvider.MISTRAL,
        @McpDescription(description = "Public document URL. Leave blank when localFile is set.") documentUrl: String? = null,
        @McpDescription(description = "Optional project-relative or absolute local file path.") localFile: String? = null,
        @McpDescription(description = "Optional markdown output path. Defaults to <localFile>.md beside the source.") outputFile: String? = null,
        @McpDescription(description = "Keep extracted images when the provider returns them.") includeImages: Boolean = true,
        @McpDescription(description = "OCR model id. Leave blank for the provider default.") model: String = "",
        @McpDescription(description = "Optional 1-based first page for Codex/SuperGrok local PDFs. Leave 0 for the start of the document.") pageFrom: Int = 0,
        @McpDescription(description = "Optional 1-based last page for Codex/SuperGrok local PDFs. Leave 0 for the end of the document.") pageTo: Int = 0,
    ): String {
        return when (provider) {
            DocumentToMarkdownProvider.MISTRAL ->
                mistralDocumentToMarkdown(
                    documentUrl,
                    localFile,
                    outputFile,
                    includeImages,
                    model.ifBlank { MistralOcrClient.DEFAULT_MODEL },
                )

            DocumentToMarkdownProvider.ZAI ->
                zaiDocumentToMarkdown(
                    documentUrl,
                    localFile,
                    outputFile,
                    includeImages,
                    model.ifBlank { ZaiOcrClient.DEFAULT_MODEL },
                )

            DocumentToMarkdownProvider.OPEN_AI ->
                codexResult(
                    codexClient.documentToMarkdown(
                        documentUrl,
                        resolveOptionalPath(localFile),
                        resolveOptionalPath(outputFile),
                        includeImages,
                        model,
                        pageFrom.takeIf { it > 0 },
                        pageTo.takeIf { it > 0 },
                    ),
                )

            DocumentToMarkdownProvider.SUPERGROK ->
                superGrokDocumentToMarkdown(
                    documentUrl,
                    localFile,
                    outputFile,
                    includeImages,
                    model.ifBlank { SuperGrokDocumentClient.DEFAULT_MODEL },
                    pageFrom.takeIf { it > 0 },
                    pageTo.takeIf { it > 0 },
                )
        }
    }

    @McpTool(name = "subscription_speech_to_text")
    @McpDescription(description = "Transcribes audio with a subscription-backed provider. Pass a public audioUrl or a localFile path. Returns the provider transcription JSON.")
    suspend fun subscription_speech_to_text(
        @McpDescription(description = "Provider to use. Supported providers are derived from the SpeechToTextProvider enum.") provider: SpeechToTextProvider = SpeechToTextProvider.OPEN_AI,
        @McpDescription(description = "Public audio URL. Leave blank when localFile is set.") audioUrl: String? = null,
        @McpDescription(description = "Optional project-relative or absolute local audio path.") localFile: String? = null,
        @McpDescription(description = "Optional language hint such as en.") language: String? = null,
        @McpDescription(description = "When true, request speaker diarization if the provider supports it.") diarize: Boolean = false,
        @McpDescription(description = "Transcription model id. Leave blank for the provider default.") model: String = "",
    ): String {
        return when (provider) {
            SpeechToTextProvider.OPEN_AI ->
                codexResult(codexClient.transcribe(audioUrl, resolveOptionalPath(localFile), language, diarize, model))

            SpeechToTextProvider.SUPERGROK ->
                superGrokSpeechToText(audioUrl, localFile, language, diarize)

            SpeechToTextProvider.MISTRAL ->
                mistralSpeechToText(
                    audioUrl,
                    localFile,
                    language,
                    diarize,
                    model.ifBlank { MistralAudioClient.DEFAULT_TRANSCRIBE_MODEL })

            SpeechToTextProvider.ZAI ->
                zaiSpeechToText(localFile, model.ifBlank { ZaiAudioClient.DEFAULT_MODEL })

            SpeechToTextProvider.MINIMAX ->
                miniMaxSpeechToText(localFile, language, diarize, model.ifBlank { MiniMaxAudioClient.DEFAULT_TRANSCRIBE_MODEL })
        }
    }

    @McpTool(name = "subscription_text_to_speech")
    @McpDescription(description = "Generates speech audio with a subscription-backed provider and writes it to disk. Pass targetFile or a unique speech-<uuid>.mp3 is written in the project. Optional voiceId or refAudioFile selects the voice.")
    suspend fun subscription_text_to_speech(
        @McpDescription(description = "Text to speak.") text: String,
        @McpDescription(description = "Provider to use. Supported providers are derived from the TextToSpeechProvider enum.") provider: TextToSpeechProvider = TextToSpeechProvider.OPEN_AI,
        @McpDescription(description = "Optional relative project path for the audio file (for example out/speech.mp3). Defaults to a unique speech-<uuid> file in the project.") targetFile: String? = null,
        @McpDescription(description = "Optional saved voice id. When blank, the first preset voice is used unless refAudioFile is set.") voiceId: String? = null,
        @McpDescription(description = "Optional local reference audio for one-off voice cloning.") refAudioFile: String? = null,
        @McpDescription(description = "Speech model id. Leave blank for the provider default.") model: String = "",
        @McpDescription(description = "Audio format: mp3, wav, flac, opus, or pcm.") responseFormat: String = "mp3",
    ): String {
        return when (provider) {
            TextToSpeechProvider.OPEN_AI ->
                codexResult(
                    codexClient.synthesize(
                        text,
                        targetFile,
                        projectBaseDirectory(),
                        voiceId,
                        model,
                        responseFormat,
                    ),
                )

            TextToSpeechProvider.SUPERGROK ->
                superGrokTextToSpeech(text, targetFile, voiceId, language = null, responseFormat)

            TextToSpeechProvider.MISTRAL ->
                mistralTextToSpeech(
                    text,
                    targetFile,
                    voiceId,
                    refAudioFile,
                    model.ifBlank { MistralAudioClient.DEFAULT_SPEECH_MODEL },
                    responseFormat,
                )

            TextToSpeechProvider.MINIMAX ->
                miniMaxTextToSpeech(
                    text,
                    targetFile,
                    voiceId,
                    model.ifBlank { MiniMaxAudioClient.DEFAULT_SPEECH_MODEL },
                    responseFormat,
                )
        }
    }

    @McpTool(name = "subscription_list_voices")
    @McpDescription(description = "Lists preset and saved voices for a subscription-backed text-to-speech provider.")
    suspend fun subscription_list_voices(
        @McpDescription(description = "Provider to use. Supported providers are derived from the TextToSpeechProvider enum.") provider: TextToSpeechProvider = TextToSpeechProvider.OPEN_AI,
    ): String {
        return when (provider) {
            TextToSpeechProvider.OPEN_AI -> codexResult(codexClient.listVoices())
            TextToSpeechProvider.SUPERGROK -> superGrokListVoices()
            TextToSpeechProvider.MISTRAL -> mistralListVoices()
            TextToSpeechProvider.MINIMAX -> miniMaxListVoices()
        }
    }

    @McpTool(name = "subscription_video_generation")
    @McpDescription(description = "Generates a video through a subscription-backed provider. SuperGrok uses Imagine; Z.ai uses CogVideoX. By default waits/polls until completion and returns the provider JSON with a download URL. Pass targetFile to download the video to disk.")
    suspend fun subscription_video_generation(
        @McpDescription(description = "Video prompt.") prompt: String,
        @McpDescription(description = "Provider to use. Supported providers are derived from the VideoGenerationProvider enum.") provider: VideoGenerationProvider = VideoGenerationProvider.SUPERGROK,
        @McpDescription(description = "Video model id. Leave blank for the provider default.") model: String = "",
        @McpDescription(description = "Requested video duration in seconds when the provider supports it.") duration: Int = SuperGrokImagineClient.DEFAULT_VIDEO_DURATION_SECONDS,
        @McpDescription(description = "Optional public image URL used as the starting frame.") imageUrl: String? = null,
        @McpDescription(description = "When true, poll until the video finishes or times out. When false, return the initial request id immediately.") waitForCompletion: Boolean = true,
        @McpDescription(description = "Maximum seconds to wait when waitForCompletion is true.") pollTimeoutSeconds: Int = SuperGrokImagineClient.DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
        @McpDescription(description = "Optional relative project path for the video (for example out/clip.mp4). Leave blank to return a download URL.") targetFile: String? = null,
    ): String {
        return when (provider) {
            VideoGenerationProvider.SUPERGROK ->
                superGrokVideoGeneration(
                    prompt,
                    model.ifBlank { SuperGrokImagineClient.DEFAULT_VIDEO_MODEL },
                    duration,
                    imageUrl,
                    waitForCompletion,
                    pollTimeoutSeconds,
                    targetFile,
                )

            VideoGenerationProvider.ZAI ->
                zaiVideoGeneration(
                    prompt,
                    model.ifBlank { ZaiVideoClient.DEFAULT_MODEL },
                    imageUrl,
                    waitForCompletion,
                    pollTimeoutSeconds,
                    targetFile,
                )
        }
    }

    @McpTool(name = "supergrok_video_generation")
    @McpDescription(description = "Generates a video through SuperGrok/xAI Imagine using the existing SuperGrok login. By default waits/polls until completion and returns the final provider JSON.")
    suspend fun supergrok_video_generation(
        @McpDescription(description = "Video prompt to send to Grok Imagine.") prompt: String,
        @McpDescription(description = "Imagine video model id, for example grok-imagine-video.") model: String = SuperGrokImagineClient.DEFAULT_VIDEO_MODEL,
        @McpDescription(description = "Requested video duration in seconds, clamped to the local safe range.") duration: Int = SuperGrokImagineClient.DEFAULT_VIDEO_DURATION_SECONDS,
        @McpDescription(description = "Optional public image URL or data URI used as the starting frame for image-to-video.") imageUrl: String? = null,
        @McpDescription(description = "When true, poll until the video finishes or times out. When false, return the initial request_id response immediately.") waitForCompletion: Boolean = true,
        @McpDescription(description = "Maximum seconds to wait when waitForCompletion is true.") pollTimeoutSeconds: Int = SuperGrokImagineClient.DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
        @McpDescription(description = "Optional relative project path for the video (for example out/clip.mp4). Leave blank to return a download URL.") targetFile: String? = null,
    ): String {
        return superGrokVideoGeneration(
            prompt,
            model,
            duration,
            imageUrl,
            waitForCompletion,
            pollTimeoutSeconds,
            targetFile
        )
    }

    private suspend fun quotaResult(type: QuotaProviderType, accountParam: String? = null): String {
        val account = try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                type,
                accountParam,
                de.moritzf.quota.idea.settings.AccountCapability.QUOTA,
            )
        } catch (exception: de.moritzf.quota.idea.settings.AccountResolveException) {
            return errorResult(exception.message ?: "Account not found")
        }
        val registration = UsageQuotaMcpRegistry.get(type)
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(account.id)

        val error = usageService.getLastError(account.id)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val payload = usageService.getLastResponseJson(account.id) ?: registration.json(usageService, type)
        if (payload.isNullOrBlank()) {
            return errorResult(registration.emptyMessage)
        }
        return payload
    }

    private fun accountStatus(
        id: String,
        type: QuotaProviderType,
        name: String,
        label: String,
        isDefault: Boolean,
        allowFailover: Boolean,
        descriptor: de.moritzf.quota.idea.common.ProviderDescriptor,
        capability: de.moritzf.quota.idea.settings.AccountCapability,
        model: String?,
    ): de.moritzf.quota.shared.McpAccountToolStatus {
        val caps = descriptor.capabilities
        val searchType = descriptor.webSearchType
        val webSearchAvailable = searchType != null && descriptor.isWebSearchConfiguredForAccount(id)
        val quotaConfigured = descriptor.isQuotaConfiguredForAccount(id)
        val reason = if (searchType == null) {
            "Web search is not offered for this provider."
        } else if (!webSearchAvailable) {
            descriptor.webSearchMissingReason
        } else {
            null
        }
        val quota = runCatching { QuotaUsageService.getInstance().getLastQuota(id) }.getOrNull()
        val op = de.moritzf.quota.idea.settings.OperationQuota.status(quota, capability, model)
        val now = kotlin.time.Clock.System.now()
        val snapshotAgeMs = op.fetchedAt?.let { fetched ->
            (now - fetched).inWholeMilliseconds.coerceAtLeast(0)
        }
        return de.moritzf.quota.shared.McpAccountToolStatus(
            id = id,
            type = type.id,
            name = name,
            label = label,
            isDefault = isDefault,
            allowFailover = allowFailover,
            quotaConfigured = quotaConfigured,
            webSearchAvailable = webSearchAvailable,
            webSearchType = searchType,
            webFetchAvailable = caps.webFetch && webSearchAvailable,
            imageGenerationAvailable = caps.imageGeneration && descriptor.isImageGenerationConfiguredForAccount(id),
            videoGenerationAvailable = caps.videoGeneration && quotaConfigured,
            speechToTextAvailable = caps.speechToText && descriptor.isVoiceConfiguredForAccount(id),
            textToSpeechAvailable = caps.textToSpeech && descriptor.isVoiceConfiguredForAccount(id),
            documentToMarkdownAvailable = caps.documentToMarkdown && descriptor.isDocumentConfiguredForAccount(id),
            reason = reason,
            snapshotAgeMs = snapshotAgeMs,
            fetchedAt = op.fetchedAt?.toString(),
            quotaAvailable = if (quota == null) null else !op.exhausted,
            limitingPool = op.limitingPool,
            usagePercent = op.usagePercent,
            resetsAt = op.resetsAt?.toString(),
            exhaustionReason = op.reason,
        )
    }

    private fun codexResult(response: CodexMcpClient.CodexMcpResponse): String {
        return response.body
    }

    private suspend fun supergrokWebSearch(
        query: String,
        model: String,
        allowedDomains: String?,
        blockedDomains: String?,
        maxOutputTokens: Int,
    ): String {
        return withSuperGrokAuth("Grok web search failed.") { accessToken ->
            superGrokSearchClient.webSearch(accessToken, query, model, allowedDomains, blockedDomains, maxOutputTokens)
        }
    }

    private suspend fun superGrokSpeechToText(
        audioUrl: String?,
        localFile: String?,
        language: String?,
        diarize: Boolean,
    ): String {
        return withSuperGrokAuth("Grok speech-to-text failed.") { accessToken ->
            superGrokAudioClient.transcribe(accessToken, audioUrl, resolveOptionalPath(localFile), language, diarize)
        }
    }

    private suspend fun superGrokTextToSpeech(
        text: String,
        targetFile: String?,
        voiceId: String?,
        language: String?,
        responseFormat: String,
    ): String {
        return withSuperGrokAuth("Grok text-to-speech failed.") { accessToken ->
            superGrokAudioClient.synthesize(
                accessToken,
                text,
                targetFile,
                projectBaseDirectory(),
                voiceId,
                language,
                responseFormat,
            )
        }
    }

    private suspend fun superGrokListVoices(): String {
        return withSuperGrokAuth("Grok voice list failed.") { accessToken ->
            superGrokAudioClient.listVoices(accessToken)
        }
    }

    private suspend fun superGrokDocumentToMarkdown(
        documentUrl: String?,
        localFile: String?,
        outputFile: String?,
        includeImages: Boolean,
        model: String,
        pageFrom: Int? = null,
        pageTo: Int? = null,
    ): String {
        return withSuperGrokAuth("Grok document conversion failed.") { accessToken ->
            superGrokDocumentClient.convertDocument(
                accessToken = accessToken,
                documentUrl = documentUrl,
                localFile = resolveOptionalPath(localFile),
                outputFile = resolveOptionalPath(outputFile),
                includeImages = includeImages,
                model = model,
                pageFrom = pageFrom,
                pageTo = pageTo,
            )
        }
    }

    private suspend fun superGrokImageGeneration(prompt: String, targetFile: String?): String {
        return withSuperGrokAuth("Grok image generation failed.") { accessToken ->
            superGrokImagineClient.generateImage(
                accessToken = accessToken,
                prompt = prompt,
                targetFile = targetFile,
                baseDirectory = projectBaseDirectory(),
            )
        }
    }

    private suspend fun superGrokImageEdit(
        prompt: String,
        imageUrl: String?,
        localFile: String?,
        targetFile: String?,
        model: String,
    ): String {
        return withSuperGrokAuth("Grok image edit failed.") { accessToken ->
            superGrokImagineClient.editImage(
                accessToken = accessToken,
                prompt = prompt,
                imageUrl = imageUrl,
                localFile = resolveOptionalPath(localFile),
                model = model,
                targetFile = targetFile,
                baseDirectory = projectBaseDirectory(),
            )
        }
    }

    private suspend fun superGrokVideoGeneration(
        prompt: String,
        model: String,
        duration: Int,
        imageUrl: String?,
        waitForCompletion: Boolean,
        pollTimeoutSeconds: Int,
        targetFile: String? = null,
    ): String {
        return withSuperGrokAuth("Grok video generation failed.") { accessToken ->
            superGrokImagineClient.generateVideo(
                accessToken = accessToken,
                prompt = prompt,
                model = model,
                duration = duration,
                imageUrl = imageUrl,
                waitForCompletion = waitForCompletion,
                pollTimeoutSeconds = pollTimeoutSeconds,
                targetFile = targetFile,
                baseDirectory = projectBaseDirectory(),
            )
        }
    }

    private suspend fun withSuperGrokAuth(failureLabel: String, block: suspend (String) -> String): String {
        val authService = QuotaAuthService.getInstance()
        val account = try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                QuotaProviderType.SUPERGROK,
                capability = de.moritzf.quota.idea.settings.AccountCapability.WEB_SEARCH,
            )
        } catch (exception: de.moritzf.quota.idea.settings.AccountResolveException) {
            return searchError(exception.message ?: "Grok login required. Log in from SuperGrok settings.")
        }
        val token = authService.getAccessTokenBlocking(account.id, QuotaProviderType.SUPERGROK)
        if (token.isNullOrBlank()) {
            return searchError("Grok login required. Log in from SuperGrok settings.")
        }
        return try {
            block(token)
        } catch (exception: SuperGrokQuotaException) {
            noteSpendRateLimit(account.id, exception.statusCode)
            if (exception.statusCode == 401 || exception.statusCode == 403) {
                val refreshed = authService.forceRefreshBlocking(account.id, QuotaProviderType.SUPERGROK, token)
                if (!refreshed.isNullOrBlank()) {
                    return try {
                        block(refreshed)
                    } catch (retryException: SuperGrokQuotaException) {
                        noteSpendRateLimit(account.id, retryException.statusCode)
                        searchError(retryException.message ?: failureLabel)
                    } catch (retryException: Exception) {
                        searchError(retryException.message ?: failureLabel)
                    }
                }
            }
            searchError(exception.message ?: failureLabel)
        } catch (exception: Exception) {
            searchError(exception.message ?: failureLabel)
        }
    }

    private suspend fun kimiWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val account = try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                QuotaProviderType.KIMI,
                capability = de.moritzf.quota.idea.settings.AccountCapability.WEB_SEARCH,
            )
        } catch (exception: de.moritzf.quota.idea.settings.AccountResolveException) {
            return searchError(exception.message ?: "Kimi login required. Log in from settings.")
        }
        val store = KimiCredentialsStore.forAccount(account.id)
        val credentials = store.loadBlocking()
        if (credentials?.isUsable() != true) {
            return searchError("Kimi login required. Log in from settings.")
        }
        return try {
            val result = kimiSearchClient.webSearch(credentials, query, limit, includeContent)
            if (result.credentials != credentials) {
                store.save(result.credentials)
            }
            result.body
        } catch (exception: KimiQuotaException) {
            noteSpendRateLimit(account.id, exception.statusCode)
            searchError(exception.message ?: "Kimi web search failed.")
        } catch (exception: Exception) {
            searchError(exception.message ?: "Kimi web search failed.")
        }
    }

    private suspend fun zaiWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = resolvedApiKey(QuotaProviderType.ZAI) { ZaiApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return searchError("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiSearchClient.webSearch(apiKey, query, limit, includeContent)
        } catch (exception: ZaiQuotaException) {
            searchError(exception.message ?: "Z.ai web search failed.")
        } catch (exception: Exception) {
            searchError(exception.message ?: "Z.ai web search failed.")
        }
    }

    private suspend fun miniMaxWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = resolvedApiKey(QuotaProviderType.MINIMAX) { MiniMaxApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return searchError("MiniMax API key missing. Add a MiniMax API key in settings.")
        }
        var lastException: Exception? = null
        for (region in miniMaxSearchRegions()) {
            try {
                return miniMaxSearchClient.webSearch(apiKey, region, query, limit, includeContent)
            } catch (exception: MiniMaxQuotaException) {
                lastException = exception
            } catch (exception: Exception) {
                lastException = exception
            }
        }
        return searchError(lastException?.message ?: "MiniMax web search failed.")
    }

    private suspend fun mistralWebSearch(query: String, model: String, premium: Boolean): String {
        val apiKey = resolvedApiKey(QuotaProviderType.MISTRAL) { MistralApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return searchError("Mistral API key missing. Add a Mistral API key in settings.")
        }
        return try {
            mistralSearchClient.webSearch(apiKey, query, model, premium)
        } catch (exception: MistralQuotaException) {
            searchError(exception.message ?: "Mistral web search failed.")
        } catch (exception: Exception) {
            searchError(exception.message ?: "Mistral web search failed.")
        }
    }

    private suspend fun miniMaxImageGeneration(prompt: String, targetFile: String?): String {
        return withMiniMaxKey("MiniMax image generation failed.") { apiKey, region ->
            miniMaxImageClient.generateImage(apiKey, region, prompt, targetFile, projectBaseDirectory())
        }
    }

    private suspend fun miniMaxTextToSpeech(
        text: String,
        targetFile: String?,
        voiceId: String?,
        model: String,
        responseFormat: String,
    ): String {
        return withMiniMaxKey("MiniMax text-to-speech failed.") { apiKey, region ->
            miniMaxAudioClient.synthesize(
                apiKey,
                region,
                text,
                targetFile,
                projectBaseDirectory(),
                voiceId,
                model,
                responseFormat,
            )
        }
    }

    private suspend fun miniMaxListVoices(): String {
        return withMiniMaxKey("MiniMax voice list failed.") { apiKey, region ->
            miniMaxAudioClient.listVoices(apiKey, region)
        }
    }

    private suspend fun miniMaxSpeechToText(
        localFile: String?,
        language: String?,
        diarize: Boolean,
        model: String,
    ): String {
        return withMiniMaxKey("MiniMax speech-to-text failed.") { apiKey, region ->
            miniMaxAudioClient.transcribe(
                apiKey,
                region,
                resolveOptionalPath(localFile),
                language,
                diarize,
                model,
            )
        }
    }

    private suspend fun zaiSpeechToText(localFile: String?, model: String): String {
        val apiKey = resolvedApiKey(QuotaProviderType.ZAI) { ZaiApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiAudioClient.transcribe(apiKey, localFile = resolveOptionalPath(localFile), model = model)
        } catch (exception: ZaiQuotaException) {
            errorResult(exception.message ?: "Z.ai speech-to-text failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Z.ai speech-to-text failed.")
        }
    }

    private suspend fun zaiVideoGeneration(
        prompt: String,
        model: String,
        imageUrl: String?,
        waitForCompletion: Boolean,
        pollTimeoutSeconds: Int,
        targetFile: String? = null,
    ): String {
        val apiKey = resolvedApiKey(QuotaProviderType.ZAI) { ZaiApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiVideoClient.generateVideo(
                apiKey,
                prompt,
                model,
                imageUrl,
                waitForCompletion,
                pollTimeoutSeconds,
                targetFile,
                projectBaseDirectory(),
            )
        } catch (exception: ZaiQuotaException) {
            errorResult(exception.message ?: "Z.ai video generation failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Z.ai video generation failed.")
        }
    }

    private suspend fun withMiniMaxKey(failureLabel: String, block: suspend (String, MiniMaxRegion) -> String): String {
        val apiKey = resolvedApiKey(QuotaProviderType.MINIMAX) { MiniMaxApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("MiniMax API key missing. Add a MiniMax API key in settings.")
        }
        var lastException: Exception? = null
        for (region in miniMaxSearchRegions()) {
            try {
                return block(apiKey, region)
            } catch (exception: MiniMaxQuotaException) {
                lastException = exception
            } catch (exception: Exception) {
                lastException = exception
            }
        }
        return errorResult(lastException?.message ?: failureLabel)
    }

    private suspend fun zaiImageGeneration(prompt: String, targetFile: String?): String {
        val apiKey = resolvedApiKey(QuotaProviderType.ZAI) { ZaiApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiImageClient.generateImage(apiKey, prompt, targetFile, projectBaseDirectory())
        } catch (exception: ZaiQuotaException) {
            errorResult(exception.message ?: "Z.ai image generation failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Z.ai image generation failed.")
        }
    }

    private suspend fun mistralImageGeneration(prompt: String, targetFile: String?): String {
        val apiKey = resolvedApiKey(QuotaProviderType.MISTRAL) { MistralApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
        }
        return try {
            mistralImageClient.generateImage(apiKey, prompt, targetFile, projectBaseDirectory())
        } catch (exception: MistralQuotaException) {
            errorResult(exception.message ?: "Mistral image generation failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Mistral image generation failed.")
        }
    }

    private suspend fun mistralDocumentToMarkdown(
        documentUrl: String?,
        localFile: String?,
        outputFile: String?,
        includeImages: Boolean,
        model: String,
    ): String {
        val apiKey = resolvedApiKey(
            QuotaProviderType.MISTRAL,
            de.moritzf.quota.idea.settings.AccountCapability.DOCUMENT_TO_MARKDOWN,
        ) { MistralApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
        }
        return try {
            mistralOcrClient.convertDocument(
                apiKey = apiKey,
                documentUrl = documentUrl,
                localFile = resolveOptionalPath(localFile),
                outputFile = resolveOptionalPath(outputFile),
                includeImages = includeImages,
                model = model,
            )
        } catch (exception: MistralQuotaException) {
            errorResult(exception.message ?: "Mistral OCR failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Mistral OCR failed.")
        }
    }

    private suspend fun zaiDocumentToMarkdown(
        documentUrl: String?,
        localFile: String?,
        outputFile: String?,
        includeImages: Boolean,
        model: String,
    ): String {
        val apiKey = resolvedApiKey(QuotaProviderType.ZAI) { ZaiApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiOcrClient.convertDocument(
                apiKey = apiKey,
                documentUrl = documentUrl,
                localFile = resolveOptionalPath(localFile),
                outputFile = resolveOptionalPath(outputFile),
                includeImages = includeImages,
                model = model,
            )
        } catch (exception: ZaiQuotaException) {
            errorResult(exception.message ?: "Z.ai OCR failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Z.ai OCR failed.")
        }
    }

    private suspend fun mistralSpeechToText(
        audioUrl: String?,
        localFile: String?,
        language: String?,
        diarize: Boolean,
        model: String,
    ): String {
        val apiKey = resolvedApiKey(QuotaProviderType.MISTRAL) { MistralApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
        }
        return try {
            mistralAudioClient.transcribe(
                apiKey = apiKey,
                audioUrl = audioUrl,
                localFile = resolveOptionalPath(localFile),
                language = language,
                diarize = diarize,
                model = model,
            )
        } catch (exception: MistralQuotaException) {
            errorResult(exception.message ?: "Mistral speech-to-text failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Mistral speech-to-text failed.")
        }
    }

    private suspend fun mistralTextToSpeech(
        text: String,
        targetFile: String?,
        voiceId: String?,
        refAudioFile: String?,
        model: String,
        responseFormat: String,
    ): String {
        val apiKey = resolvedApiKey(QuotaProviderType.MISTRAL) { MistralApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
        }
        return try {
            mistralAudioClient.synthesize(
                apiKey = apiKey,
                text = text,
                targetFile = targetFile,
                baseDirectory = projectBaseDirectory(),
                voiceId = voiceId,
                refAudioFile = resolveOptionalPath(refAudioFile),
                model = model,
                responseFormat = responseFormat,
            )
        } catch (exception: MistralQuotaException) {
            errorResult(exception.message ?: "Mistral text-to-speech failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Mistral text-to-speech failed.")
        }
    }

    private suspend fun mistralListVoices(): String {
        val apiKey = resolvedApiKey(QuotaProviderType.MISTRAL) { MistralApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
        }
        return try {
            mistralAudioClient.listVoices(apiKey)
        } catch (exception: MistralQuotaException) {
            errorResult(exception.message ?: "Mistral voice list failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Mistral voice list failed.")
        }
    }

    private suspend fun resolveOptionalPath(value: String?): Path? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val path = Path.of(trimmed)
        if (path.isAbsolute) return path.normalize()
        val project = currentCoroutineContext().projectOrNull ?: return path.normalize()
        return project.resolveInProject(trimmed, throwWhenOutside = false)
    }

    private suspend fun ollamaWebFetch(url: String): String {
        val apiKey = resolvedApiKey(QuotaProviderType.OLLAMA) { OllamaApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Ollama API key missing. Add an Ollama API key in settings.")
        }
        return try {
            ollamaSearchClient.webFetch(apiKey, url)
        } catch (exception: OllamaQuotaException) {
            errorResult(exception.message ?: "Ollama web fetch failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Ollama web fetch failed.")
        }
    }

    private suspend fun zaiWebFetch(url: String): String {
        val apiKey = resolvedApiKey(QuotaProviderType.ZAI) { ZaiApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiSearchClient.webFetch(apiKey, url)
        } catch (exception: ZaiQuotaException) {
            errorResult(exception.message ?: "Z.ai web fetch failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Z.ai web fetch failed.")
        }
    }

    private suspend fun ollamaWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = resolvedApiKey(QuotaProviderType.OLLAMA) { OllamaApiKeyStore.forAccount(it).loadBlocking() }
        if (apiKey.isNullOrBlank()) {
            return searchError("Ollama API key missing. Add an Ollama API key in settings.")
        }
        return try {
            ollamaSearchClient.webSearch(apiKey, query, limit, includeContent)
        } catch (exception: OllamaQuotaException) {
            searchError(exception.message ?: "Ollama web search failed.")
        } catch (exception: Exception) {
            searchError(exception.message ?: "Ollama web search failed.")
        }
    }

    private fun noteSpendRateLimit(accountId: String, statusCode: Int?) {
        if (statusCode == 429) {
            de.moritzf.quota.idea.settings.AccountResolver.markRateLimited(accountId)
        }
    }

    private fun resolvedApiKey(
        type: QuotaProviderType,
        capability: de.moritzf.quota.idea.settings.AccountCapability =
            de.moritzf.quota.idea.settings.AccountCapability.WEB_SEARCH,
        load: (String) -> String?,
    ): String? {
        val account = try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                type,
                capability = capability,
            )
        } catch (_: de.moritzf.quota.idea.settings.AccountResolveException) {
            return null
        }
        return load(account.id)
    }

    private suspend fun projectBaseDirectory(): Path? {
        return currentCoroutineContext().projectOrNull?.projectDirectory
    }

    private fun miniMaxSearchRegions(): List<MiniMaxRegion> {
        val settings = runCatching { QuotaSettingsState.getInstance() }.getOrNull()
        val accountId = runCatching {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                QuotaProviderType.MINIMAX,
                capability = de.moritzf.quota.idea.settings.AccountCapability.WEB_SEARCH,
            ).id
        }.getOrNull()
        val preference = when {
            settings == null -> MiniMaxRegionPreference.AUTO
            accountId != null -> settings.miniMaxRegionFor(accountId)
            else -> settings.miniMaxRegionPreference()
        }
        return when (preference) {
            MiniMaxRegionPreference.GLOBAL -> listOf(MiniMaxRegion.GLOBAL)
            MiniMaxRegionPreference.CN -> listOf(MiniMaxRegion.CN)
            MiniMaxRegionPreference.AUTO -> listOf(MiniMaxRegion.GLOBAL, MiniMaxRegion.CN)
        }
    }

    private suspend fun searchError(message: String): String {
        val settings = runCatching { QuotaSettingsState.getInstance() }.getOrNull()
        val available = mutableListOf<String>()
        for (descriptor in ProviderCatalog.all) {
            val accounts = settings?.accountsOf(descriptor.type).orEmpty()
            val configured = if (accounts.isEmpty()) {
                descriptor.isWebSearchConfigured()
            } else {
                accounts.any { descriptor.isWebSearchConfiguredForAccount(it.id) }
            }
            if (configured) {
                available.add(descriptor.type.displayName)
            }
        }
        val hint = if (available.isEmpty()) {
            " No search providers are currently configured."
        } else {
            " Currently configured search providers: ${available.joinToString(", ")}."
        }
        return errorResult(message + hint)
    }

    private fun extractErrorMessage(body: String): String {
        val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val message = (root?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return message ?: body
    }

    private fun errorResult(errorMessage: String): String {
        return McpJson.error(errorMessage)
    }
}
