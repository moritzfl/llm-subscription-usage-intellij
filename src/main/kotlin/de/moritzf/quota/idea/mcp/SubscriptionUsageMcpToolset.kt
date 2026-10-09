package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.interruptibleOperation
import de.moritzf.quota.idea.operations.*
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.mistral.MistralWebSearchClient
import de.moritzf.quota.shared.DocumentImageFormat
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.supergrok.SuperGrokWebSearchClient

/** Exposes subscription usage JSON and hosted subscription tools through IntelliJ's MCP server. */
class SubscriptionUsageMcpToolset : McpToolset {
    private val operations = SubscriptionOperations()

    @McpTool(name = "subscription_quota")
    @McpDescription(
        description =
            "Returns the latest subscription quota response JSON for the selected provider."
    )
    suspend fun subscription_quota(
        @McpDescription(
            description =
                "Provider to query. Supported providers are derived from the shared provider enum."
        )
        provider: QuotaProviderType,
        @McpDescription(
            description =
                "Optional account name or id when more than one login of this type exists."
        )
        account: String? = null,
    ): String {
        return interruptibleOperation { operations.subscription_quota(provider, account) }
    }

    @McpTool(name = "subscription_tools_status")
    @McpDescription(
        description =
            "Returns per-account status: credential presence, cached quota freshness, limiting pool, reset time, and whether a requested operation can run. Optional capability/model uses cached quota only and does not call provider APIs."
    )
    suspend fun subscription_tools_status(
        @McpDescription(
            description =
                "Optional operation to evaluate against cached quota, such as PROXY or WEB_SEARCH."
        )
        capability: de.moritzf.quota.idea.settings.AccountCapability? = null,
        @McpDescription(
            description =
                "Optional model id when availability depends on a specific pool, such as gpt-6-luna versus gpt-reserve."
        )
        model: String? = null,
    ): String {
        return interruptibleOperation { operations.subscription_tools_status(capability, model) }
    }

    @McpTool(name = "codex_web_search")
    @McpDescription(
        description =
            "Runs a Codex subscription-backed web search using the existing OpenAI login and returns the Codex JSON response."
    )
    suspend fun codex_web_search(
        @McpDescription(description = "Search query to send to Codex web search.") query: String,
        @McpDescription(
            description =
                "Search context size: low, medium, or high. Higher values can improve detailed answers but may cost more and take longer."
        )
        searchContextSize: String = "medium",
        @McpDescription(
            description =
                "Whether to request the complete sources list from the web search call when available."
        )
        includeSources: Boolean = false,
        @McpDescription(
            description =
                "Whether the hosted search tool may fetch live web content. Set false for cached/indexed results only."
        )
        externalWebAccess: Boolean = true,
        @McpDescription(
            description =
                "Optional comma-separated domains to allow, for example openai.com,example.org. Leave blank for no allow filter."
        )
        allowedDomains: String? = null,
        @McpDescription(
            description =
                "Optional comma-separated domains to block, for example reddit.com,quora.com. Leave blank for no block filter."
        )
        blockedDomains: String? = null,
    ): String {
        return interruptibleOperation {
            operations.codex_web_search(
                query,
                searchContextSize,
                includeSources,
                externalWebAccess,
                allowedDomains,
                blockedDomains,
            )
        }
    }

    @McpTool(name = "supergrok_web_search")
    @McpDescription(
        description =
            "Runs a SuperGrok/xAI web search using the existing SuperGrok login and returns normalized JSON results."
    )
    suspend fun supergrok_web_search(
        @McpDescription(description = "Search query to send to Grok web search.") query: String,
        @McpDescription(description = "xAI model to use for the Responses API web search request.")
        model: String = SuperGrokWebSearchClient.DEFAULT_MODEL,
        @McpDescription(
            description =
                "Optional comma-separated domains to allow, up to 5. Leave blank for no allow filter."
        )
        allowedDomains: String? = null,
        @McpDescription(
            description =
                "Optional comma-separated domains to exclude, up to 5. Leave blank for no exclude filter."
        )
        excludedDomains: String? = null,
        @McpDescription(
            description =
                "Maximum output tokens for the Grok answer. Values are clamped to Grok's safe local range."
        )
        maxOutputTokens: Int = SuperGrokWebSearchClient.DEFAULT_MAX_OUTPUT_TOKENS,
    ): String {
        return interruptibleOperation {
            operations.supergrok_web_search(
                query,
                model,
                allowedDomains,
                excludedDomains,
                maxOutputTokens,
            )
        }
    }

    @McpTool(name = "subscription_web_search")
    @McpDescription(
        description =
            "Runs a result-list subscription-backed web search (Kimi, Z.ai, MiniMax, or Ollama) and returns the provider JSON response."
    )
    suspend fun subscription_web_search(
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the ListSearchProvider enum."
        )
        provider: ListSearchProvider = ListSearchProvider.KIMI,
        @McpDescription(description = "Search query.") query: String,
        @McpDescription(
            description =
                "Number of search results to request. Values are clamped to the provider's supported range."
        )
        limit: Int = 5,
        @McpDescription(
            description =
                "Whether to include full result content in addition to snippets. This can substantially increase response size."
        )
        includeContent: Boolean = false,
    ): String {
        return interruptibleOperation {
            operations.subscription_web_search(provider, query, limit, includeContent)
        }
    }

    @McpTool(name = "subscription_web_fetch")
    @McpDescription(
        description =
            "Fetches a web page through a subscription-backed provider and returns the provider JSON (title, content, links). PDF conversion stays on subscription_document_to_markdown."
    )
    suspend fun subscription_web_fetch(
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the WebFetchProvider enum."
        )
        provider: WebFetchProvider = WebFetchProvider.OLLAMA,
        @McpDescription(description = "Page URL to fetch.") url: String,
    ): String {
        return interruptibleOperation { operations.subscription_web_fetch(provider, url) }
    }

    @McpTool(name = "subscription_image_edit")
    @McpDescription(
        description =
            "Edits an existing image with SuperGrok/xAI JSON image edits. Provide imageUrl or localFile. Masks are not supported. Returns a URL or writes targetFile. Never returns base64."
    )
    suspend fun subscription_image_edit(
        @McpDescription(description = "Edit prompt.") prompt: String,
        @McpDescription(description = "Provider. Only SuperGrok is supported.")
        provider: ImageEditProvider = ImageEditProvider.SUPERGROK,
        @McpDescription(description = "Public source image URL. Leave blank when localFile is set.")
        imageUrl: String? = null,
        @McpDescription(description = "Optional project-relative or absolute local source image.")
        localFile: String? = null,
        @McpDescription(
            description = "Optional mask image URL. Unsupported; the call fails if set."
        )
        maskUrl: String? = null,
        @McpDescription(description = "Optional relative project path for the edited image.")
        targetFile: String? = null,
        @McpDescription(description = "Image model id. Leave blank for the provider default.")
        model: String = "",
    ): String {
        return interruptibleOperation {
            operations.subscription_image_edit(
                prompt,
                provider,
                imageUrl,
                localFile,
                maskUrl,
                targetFile,
                model,
            )
        }
    }

    @McpTool(name = "subscription_image_generation")
    @McpDescription(
        description =
            "Generates one image through a subscription-backed provider. Without targetFile, SuperGrok, Z.ai, and MiniMax return an image URL; OpenAI/Codex and Mistral write a unique image-<uuid>.png in the project. With targetFile, the image is written to that path. Never returns base64."
    )
    suspend fun subscription_image_generation(
        @McpDescription(description = "Image prompt.") prompt: String,
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the ImageGenerationProvider enum."
        )
        provider: ImageGenerationProvider = ImageGenerationProvider.OPEN_AI,
        @McpDescription(
            description =
                "Optional relative project path for the generated image (for example out/image.png). Leave blank for a download URL, or a unique image-<uuid>.png for OpenAI/Codex and Mistral."
        )
        targetFile: String? = null,
    ): String {
        return interruptibleOperation {
            operations.subscription_image_generation(prompt, provider, targetFile)
        }
    }

    @McpTool(name = "mistral_web_search")
    @McpDescription(
        description =
            "Runs a Mistral Conversations web search using the stored Mistral API key and returns the provider JSON response."
    )
    suspend fun mistral_web_search(
        @McpDescription(description = "Search query to send to Mistral web search.") query: String,
        @McpDescription(description = "Mistral model id for the Conversations request.")
        model: String = MistralWebSearchClient.DEFAULT_MODEL,
        @McpDescription(description = "When true, use web_search_premium instead of web_search.")
        premium: Boolean = false,
    ): String {
        return interruptibleOperation { operations.mistral_web_search(query, model, premium) }
    }

    @McpTool(name = "subscription_document_to_markdown")
    @McpDescription(
        description =
            "Converts a PDF or image to markdown. Prefer a document or OCR provider (MISTRAL, ZAI, or a company model such as AZURE). Use OPEN_AI/Codex or SUPERGROK vision only when no OCR provider is available or explicitly requested. PDFBOX is local Apache PDFBox text extraction: free, no login, no OCR, no figures, and often the wrong reading order. Z.ai and Cohere split long local PDFs automatically. For large PDFs on Codex/SuperGrok/PDFBOX, use pageFrom/pageTo. With localFile, markdown defaults to <name>.md beside it. Images are never returned as base64."
    )
    suspend fun subscription_document_to_markdown(
        @McpDescription(
            description =
                "Prefer a document or OCR provider (MISTRAL, ZAI, or a company model such as AZURE), then OPEN_AI, SUPERGROK, GITHUB, or OPEN_CODE native PDF. PDFBOX extracts embedded text locally and needs no subscription; it is not OCR. Honor explicit provider requests."
        )
        provider: DocumentToMarkdownProvider = DocumentToMarkdownProvider.MISTRAL,
        @McpDescription(description = "Public document URL. Leave blank when localFile is set.")
        documentUrl: String? = null,
        @McpDescription(description = "Optional project-relative or absolute local file path.")
        localFile: String? = null,
        @McpDescription(
            description =
                "Optional markdown output path. Defaults to <localFile>.md beside the source."
        )
        outputFile: String? = null,
        @McpDescription(description = "Keep extracted images when the provider returns them.")
        includeImages: Boolean = true,
        @McpDescription(
            description =
                "OCR or vision model id. Leave blank to use the document model selected in settings. For Azure, pass a deployment name, cohere:<deployment>, or prebuilt-layout."
        )
        model: String = "",
        @McpDescription(
            description =
                "Optional 1-based first page for Codex/SuperGrok/Cohere/PDFBOX PDFs. Leave 0 for the start of the document."
        )
        pageFrom: Int = 0,
        @McpDescription(
            description =
                "Optional 1-based last page for Codex/SuperGrok/Cohere/PDFBOX PDFs. Leave 0 for the end of the document."
        )
        pageTo: Int = 0,
        @McpDescription(
            description =
                "Figure export for MISTRAL/AZURE/ZAI PDFs: SVG prefers local vector export with PNG fallback; PNG renders the original PDF; PROVIDER keeps provider images. Other providers keep their existing image export. PDFBOX ignores figure options. Fallbacks are returned in warnings; image_export reports final format counts, failed images, and diagnostics."
        )
        imageFormat: DocumentImageFormat = DocumentImageFormat.SVG,
        @McpDescription(
            description =
                "Local PNG export resolution, also for SVG fallback (72-600 DPI, default 300). This is not an OCR API request resolution."
        )
        imageDpi: Int = 300,
        @McpDescription(
            description =
                "Extra figure margin in PDF points (0-72, default 2; 72 points = 1 inch). Applies to local SVG/PNG export."
        )
        imagePaddingPoints: Double = 2.0,
    ): String {
        return interruptibleOperation {
            operations.subscription_document_to_markdown(
                provider,
                documentUrl,
                localFile,
                outputFile,
                includeImages,
                model,
                pageFrom,
                pageTo,
                imageFormat,
                imageDpi,
                imagePaddingPoints,
            )
        }
    }

    @McpTool(name = "subscription_vision")
    @McpDescription(
        description =
            "Asks a vision-capable subscription model about one image and returns {provider, model, content} with the answer. Use it when the current model cannot see images: pass the image plus what to extract or ask. Vision is off by default for every provider; select a vision model in settings (or pass model=) first. Images are accepted as a public URL or a local file, never returned as base64."
    )
    suspend fun subscription_vision(
        @McpDescription(
            description =
                "What to extract from or ask about the image, for example 'List the visible text', 'Describe the error', or 'Which element is selected?'"
        )
        prompt: String,
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the VisionProvider enum. Honor explicit provider requests."
        )
        provider: VisionProvider = VisionProvider.OPEN_AI,
        @McpDescription(
            description =
                "Public image URL. Leave blank when localFile is set. Azure, GitHub Copilot and OpenCode need a local file."
        )
        imageUrl: String? = null,
        @McpDescription(description = "Optional project-relative or absolute local image path.")
        localFile: String? = null,
        @McpDescription(
            description =
                "Vision model id. Leave blank to use the vision model selected in settings."
        )
        model: String = "",
    ): String {
        return interruptibleOperation {
            operations.subscription_vision(prompt, provider, imageUrl, localFile, model)
        }
    }

    @McpTool(name = "subscription_svg_to_png")
    @McpDescription(description = "Rasterizes a local SVG to PNG.")
    suspend fun subscription_svg_to_png(
        @McpDescription(description = "Local SVG path.") localFile: String,
        @McpDescription(description = "Optional PNG path. Defaults to <name>.png beside the SVG.")
        outputFile: String? = null,
        @McpDescription(description = "72-600. Default 300.") dpi: Int = 300,
    ): String {
        return interruptibleOperation {
            operations.subscription_svg_to_png(localFile, outputFile, dpi)
        }
    }

    @McpTool(name = "subscription_speech_to_text")
    @McpDescription(
        description =
            "Transcribes audio with a subscription-backed provider. Pass a public audioUrl or a localFile path. Returns the provider transcription JSON."
    )
    suspend fun subscription_speech_to_text(
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the SpeechToTextProvider enum."
        )
        provider: SpeechToTextProvider = SpeechToTextProvider.OPEN_AI,
        @McpDescription(description = "Public audio URL. Leave blank when localFile is set.")
        audioUrl: String? = null,
        @McpDescription(description = "Optional project-relative or absolute local audio path.")
        localFile: String? = null,
        @McpDescription(description = "Optional language hint such as en.")
        language: String? = null,
        @McpDescription(
            description = "When true, request speaker diarization if the provider supports it."
        )
        diarize: Boolean = false,
        @McpDescription(
            description = "Transcription model id. Leave blank for the provider default."
        )
        model: String = "",
    ): String {
        return interruptibleOperation {
            operations.subscription_speech_to_text(
                provider,
                audioUrl,
                localFile,
                language,
                diarize,
                model,
            )
        }
    }

    @McpTool(name = "subscription_text_to_speech")
    @McpDescription(
        description =
            "Generates speech audio with a subscription-backed provider and writes it to disk. OpenAI experimental realtime voice: model=gpt-live-1-codex, responseFormat=wav, voiceId=marin or cedar. Requires voice-session access and may paraphrase. Pass targetFile or a unique speech file is written in the project. Optional voiceId or refAudioFile selects the voice."
    )
    suspend fun subscription_text_to_speech(
        @McpDescription(description = "Text to speak.") text: String,
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the TextToSpeechProvider enum."
        )
        provider: TextToSpeechProvider = TextToSpeechProvider.OPEN_AI,
        @McpDescription(
            description =
                "Optional relative project path for the audio file (for example out/speech.mp3). Defaults to a unique speech-<uuid> file in the project."
        )
        targetFile: String? = null,
        @McpDescription(
            description =
                "Optional saved voice id. When blank, the first preset voice is used unless refAudioFile is set."
        )
        voiceId: String? = null,
        @McpDescription(description = "Optional local reference audio for one-off voice cloning.")
        refAudioFile: String? = null,
        @McpDescription(description = "Speech model id. Leave blank for the provider default.")
        model: String = "",
        @McpDescription(description = "Audio format: mp3, wav, flac, opus, or pcm.")
        responseFormat: String = "mp3",
    ): String {
        return interruptibleOperation {
            operations.subscription_text_to_speech(
                text,
                provider,
                targetFile,
                voiceId,
                refAudioFile,
                model,
                responseFormat,
            )
        }
    }

    @McpTool(name = "subscription_list_voices")
    @McpDescription(
        description =
            "Lists preset and saved voices for a subscription-backed text-to-speech provider."
    )
    suspend fun subscription_list_voices(
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the TextToSpeechProvider enum."
        )
        provider: TextToSpeechProvider = TextToSpeechProvider.OPEN_AI
    ): String {
        return interruptibleOperation { operations.subscription_list_voices(provider) }
    }

    @McpTool(name = "subscription_video_generation")
    @McpDescription(
        description =
            "Generates a video through a subscription-backed provider. SuperGrok uses Imagine; Z.ai uses CogVideoX. By default waits/polls until completion and returns the provider JSON with a download URL. Pass targetFile to download the video to disk."
    )
    suspend fun subscription_video_generation(
        @McpDescription(description = "Video prompt.") prompt: String,
        @McpDescription(
            description =
                "Provider to use. Supported providers are derived from the VideoGenerationProvider enum."
        )
        provider: VideoGenerationProvider = VideoGenerationProvider.SUPERGROK,
        @McpDescription(description = "Video model id. Leave blank for the provider default.")
        model: String = "",
        @McpDescription(
            description = "Requested video duration in seconds when the provider supports it."
        )
        duration: Int = SuperGrokImagineClient.DEFAULT_VIDEO_DURATION_SECONDS,
        @McpDescription(description = "Optional public image URL used as the starting frame.")
        imageUrl: String? = null,
        @McpDescription(
            description =
                "When true, poll until the video finishes or times out. When false, return the initial request id immediately."
        )
        waitForCompletion: Boolean = true,
        @McpDescription(description = "Maximum seconds to wait when waitForCompletion is true.")
        pollTimeoutSeconds: Int = SuperGrokImagineClient.DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
        @McpDescription(
            description =
                "Optional relative project path for the video (for example out/clip.mp4). Leave blank to return a download URL."
        )
        targetFile: String? = null,
    ): String {
        return interruptibleOperation {
            operations.subscription_video_generation(
                prompt,
                provider,
                model,
                duration,
                imageUrl,
                waitForCompletion,
                pollTimeoutSeconds,
                targetFile,
            )
        }
    }

    @McpTool(name = "supergrok_video_generation")
    @McpDescription(
        description =
            "Generates a video through SuperGrok/xAI Imagine using the existing SuperGrok login. By default waits/polls until completion and returns the final provider JSON."
    )
    suspend fun supergrok_video_generation(
        @McpDescription(description = "Video prompt to send to Grok Imagine.") prompt: String,
        @McpDescription(description = "Imagine video model id, for example grok-imagine-video.")
        model: String = SuperGrokImagineClient.DEFAULT_VIDEO_MODEL,
        @McpDescription(
            description = "Requested video duration in seconds, clamped to the local safe range."
        )
        duration: Int = SuperGrokImagineClient.DEFAULT_VIDEO_DURATION_SECONDS,
        @McpDescription(
            description =
                "Optional public image URL or data URI used as the starting frame for image-to-video."
        )
        imageUrl: String? = null,
        @McpDescription(
            description =
                "When true, poll until the video finishes or times out. When false, return the initial request_id response immediately."
        )
        waitForCompletion: Boolean = true,
        @McpDescription(description = "Maximum seconds to wait when waitForCompletion is true.")
        pollTimeoutSeconds: Int = SuperGrokImagineClient.DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
        @McpDescription(
            description =
                "Optional relative project path for the video (for example out/clip.mp4). Leave blank to return a download URL."
        )
        targetFile: String? = null,
    ): String {
        return interruptibleOperation {
            operations.supergrok_video_generation(
                prompt,
                model,
                duration,
                imageUrl,
                waitForCompletion,
                pollTimeoutSeconds,
                targetFile,
            )
        }
    }
}
