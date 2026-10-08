package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.mcp.*
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.minimax.MiniMaxAudioClient
import de.moritzf.quota.mistral.MistralAudioClient
import de.moritzf.quota.mistral.MistralQuotaException
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.zai.ZaiAudioClient
import de.moritzf.quota.zai.ZaiQuotaException
import de.moritzf.quota.zai.ZaiVideoClient

internal suspend fun SubscriptionOperations.subscription_image_edit(
    prompt: String,
    provider: ImageEditProvider = ImageEditProvider.SUPERGROK,
    imageUrl: String? = null,
    localFile: String? = null,
    maskUrl: String? = null,
    targetFile: String? = null,
    model: String = "",
): String {
    if (!maskUrl.isNullOrBlank()) {
        return errorResult("xAI image edits do not support masks. Send the source image only.")
    }
    return when (provider) {
        ImageEditProvider.SUPERGROK ->
            superGrokImageEdit(prompt, imageUrl, localFile, targetFile, model)
    }
}

internal suspend fun SubscriptionOperations.subscription_image_generation(
    prompt: String,
    provider: ImageGenerationProvider = ImageGenerationProvider.OPEN_AI,
    targetFile: String? = null,
): String {
    return when (provider) {
        ImageGenerationProvider.OPEN_AI ->
            codexResult(
                codexClient(AccountCapability.IMAGE_GENERATION)
                    .imageGeneration(prompt, targetFile, projectBaseDirectory())
            )

        ImageGenerationProvider.SUPERGROK -> superGrokImageGeneration(prompt, targetFile)

        ImageGenerationProvider.MISTRAL -> mistralImageGeneration(prompt, targetFile)

        ImageGenerationProvider.ZAI -> zaiImageGeneration(prompt, targetFile)

        ImageGenerationProvider.MINIMAX -> miniMaxImageGeneration(prompt, targetFile)
    }
}

internal suspend fun SubscriptionOperations.subscription_speech_to_text(
    provider: SpeechToTextProvider = SpeechToTextProvider.OPEN_AI,
    audioUrl: String? = null,
    localFile: String? = null,
    language: String? = null,
    diarize: Boolean = false,
    model: String = "",
): String {
    return when (provider) {
        SpeechToTextProvider.OPEN_AI ->
            codexResult(
                codexClient(AccountCapability.SPEECH_TO_TEXT)
                    .transcribe(
                        audioUrl,
                        resolveOptionalPath(localFile),
                        language,
                        diarize,
                        model,
                    )
            )

        SpeechToTextProvider.SUPERGROK ->
            superGrokSpeechToText(audioUrl, localFile, language, diarize)

        SpeechToTextProvider.MISTRAL ->
            mistralSpeechToText(
                audioUrl,
                localFile,
                language,
                diarize,
                model.ifBlank { MistralAudioClient.DEFAULT_TRANSCRIBE_MODEL },
            )

        SpeechToTextProvider.ZAI ->
            zaiSpeechToText(localFile, model.ifBlank { ZaiAudioClient.DEFAULT_MODEL })

        SpeechToTextProvider.MINIMAX ->
            miniMaxSpeechToText(
                localFile,
                language,
                diarize,
                model.ifBlank { MiniMaxAudioClient.DEFAULT_TRANSCRIBE_MODEL },
            )
    }
}

internal suspend fun SubscriptionOperations.subscription_text_to_speech(
    text: String,
    provider: TextToSpeechProvider = TextToSpeechProvider.OPEN_AI,
    targetFile: String? = null,
    voiceId: String? = null,
    refAudioFile: String? = null,
    model: String = "",
    responseFormat: String = "mp3",
): String {
    return when (provider) {
        TextToSpeechProvider.OPEN_AI ->
            codexResult(
                codexClient(AccountCapability.TEXT_TO_SPEECH)
                    .synthesize(
                        text,
                        targetFile,
                        projectBaseDirectory(),
                        voiceId,
                        model,
                        responseFormat,
                    )
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

internal suspend fun SubscriptionOperations.subscription_list_voices(
    provider: TextToSpeechProvider = TextToSpeechProvider.OPEN_AI
): String {
    return when (provider) {
        TextToSpeechProvider.OPEN_AI ->
            codexResult(codexClient(AccountCapability.LIST_VOICES).listVoices())
        TextToSpeechProvider.SUPERGROK -> superGrokListVoices()
        TextToSpeechProvider.MISTRAL -> mistralListVoices()
        TextToSpeechProvider.MINIMAX -> miniMaxListVoices()
    }
}

internal suspend fun SubscriptionOperations.subscription_video_generation(
    prompt: String,
    provider: VideoGenerationProvider = VideoGenerationProvider.SUPERGROK,
    model: String = "",
    duration: Int = SuperGrokImagineClient.DEFAULT_VIDEO_DURATION_SECONDS,
    imageUrl: String? = null,
    waitForCompletion: Boolean = true,
    pollTimeoutSeconds: Int = SuperGrokImagineClient.DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
    targetFile: String? = null,
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

internal suspend fun SubscriptionOperations.supergrok_video_generation(
    prompt: String,
    model: String = SuperGrokImagineClient.DEFAULT_VIDEO_MODEL,
    duration: Int = SuperGrokImagineClient.DEFAULT_VIDEO_DURATION_SECONDS,
    imageUrl: String? = null,
    waitForCompletion: Boolean = true,
    pollTimeoutSeconds: Int = SuperGrokImagineClient.DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
    targetFile: String? = null,
): String {
    return superGrokVideoGeneration(
        prompt,
        model,
        duration,
        imageUrl,
        waitForCompletion,
        pollTimeoutSeconds,
        targetFile,
    )
}

internal suspend fun SubscriptionOperations.superGrokSpeechToText(
    audioUrl: String?,
    localFile: String?,
    language: String?,
    diarize: Boolean,
): String {
    return withSuperGrokAuth("Grok speech-to-text failed.", AccountCapability.SPEECH_TO_TEXT) {
        accessToken ->
        superGrokAudioClient.transcribe(
            accessToken,
            audioUrl,
            resolveOptionalPath(localFile),
            language,
            diarize,
        )
    }
}

internal suspend fun SubscriptionOperations.superGrokTextToSpeech(
    text: String,
    targetFile: String?,
    voiceId: String?,
    language: String?,
    responseFormat: String,
): String {
    return withSuperGrokAuth("Grok text-to-speech failed.", AccountCapability.TEXT_TO_SPEECH) {
        accessToken ->
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

internal suspend fun SubscriptionOperations.superGrokListVoices(): String {
    return withSuperGrokAuth("Grok voice list failed.", AccountCapability.LIST_VOICES) { accessToken
        ->
        superGrokAudioClient.listVoices(accessToken)
    }
}

internal suspend fun SubscriptionOperations.superGrokImageGeneration(
    prompt: String,
    targetFile: String?,
): String {
    return withSuperGrokAuth("Grok image generation failed.", AccountCapability.IMAGE_GENERATION) {
        accessToken ->
        superGrokImagineClient.generateImage(
            accessToken = accessToken,
            prompt = prompt,
            targetFile = targetFile,
            baseDirectory = projectBaseDirectory(),
        )
    }
}

internal suspend fun SubscriptionOperations.superGrokImageEdit(
    prompt: String,
    imageUrl: String?,
    localFile: String?,
    targetFile: String?,
    model: String,
): String {
    return withSuperGrokAuth("Grok image edit failed.", AccountCapability.IMAGE_GENERATION) {
        accessToken ->
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

internal suspend fun SubscriptionOperations.superGrokVideoGeneration(
    prompt: String,
    model: String,
    duration: Int,
    imageUrl: String?,
    waitForCompletion: Boolean,
    pollTimeoutSeconds: Int,
    targetFile: String? = null,
): String {
    return withSuperGrokAuth("Grok video generation failed.", AccountCapability.VIDEO_GENERATION) {
        accessToken ->
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

internal suspend fun SubscriptionOperations.miniMaxImageGeneration(
    prompt: String,
    targetFile: String?,
): String {
    return withMiniMaxKey(
        "MiniMax image generation failed.",
        AccountCapability.IMAGE_GENERATION,
    ) { apiKey, region ->
        miniMaxImageClient.generateImage(
            apiKey,
            region,
            prompt,
            targetFile,
            projectBaseDirectory(),
        )
    }
}

internal suspend fun SubscriptionOperations.miniMaxTextToSpeech(
    text: String,
    targetFile: String?,
    voiceId: String?,
    model: String,
    responseFormat: String,
): String {
    return withMiniMaxKey("MiniMax text-to-speech failed.", AccountCapability.TEXT_TO_SPEECH) {
        apiKey,
        region ->
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

internal suspend fun SubscriptionOperations.miniMaxListVoices(): String {
    return withMiniMaxKey("MiniMax voice list failed.", AccountCapability.LIST_VOICES) {
        apiKey,
        region ->
        miniMaxAudioClient.listVoices(apiKey, region)
    }
}

internal suspend fun SubscriptionOperations.miniMaxSpeechToText(
    localFile: String?,
    language: String?,
    diarize: Boolean,
    model: String,
): String {
    return withMiniMaxKey("MiniMax speech-to-text failed.", AccountCapability.SPEECH_TO_TEXT) {
        apiKey,
        region ->
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

internal suspend fun SubscriptionOperations.zaiSpeechToText(
    localFile: String?,
    model: String,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.ZAI, AccountCapability.SPEECH_TO_TEXT) {
            ZaiApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
    }
    return try {
        zaiAudioClient.transcribe(
            apiKey,
            localFile = resolveOptionalPath(localFile),
            model = model,
        )
    } catch (exception: ZaiQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai speech-to-text failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai speech-to-text failed.")
    }
}

internal suspend fun SubscriptionOperations.zaiVideoGeneration(
    prompt: String,
    model: String,
    imageUrl: String?,
    waitForCompletion: Boolean,
    pollTimeoutSeconds: Int,
    targetFile: String? = null,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.ZAI, AccountCapability.VIDEO_GENERATION) {
            ZaiApiKeyStore.forAccount(it).loadBlocking()
        }
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
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai video generation failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai video generation failed.")
    }
}

internal suspend fun SubscriptionOperations.zaiImageGeneration(
    prompt: String,
    targetFile: String?,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.ZAI, AccountCapability.IMAGE_GENERATION) {
            ZaiApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
    }
    return try {
        zaiImageClient.generateImage(apiKey, prompt, targetFile, projectBaseDirectory())
    } catch (exception: ZaiQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai image generation failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai image generation failed.")
    }
}

internal suspend fun SubscriptionOperations.mistralImageGeneration(
    prompt: String,
    targetFile: String?,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.MISTRAL, AccountCapability.IMAGE_GENERATION) {
            MistralApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
    }
    return try {
        mistralImageClient.generateImage(apiKey, prompt, targetFile, projectBaseDirectory())
    } catch (exception: MistralQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral image generation failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral image generation failed.")
    }
}

internal suspend fun SubscriptionOperations.mistralSpeechToText(
    audioUrl: String?,
    localFile: String?,
    language: String?,
    diarize: Boolean,
    model: String,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.MISTRAL, AccountCapability.SPEECH_TO_TEXT) {
            MistralApiKeyStore.forAccount(it).loadBlocking()
        }
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
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral speech-to-text failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral speech-to-text failed.")
    }
}

internal suspend fun SubscriptionOperations.mistralTextToSpeech(
    text: String,
    targetFile: String?,
    voiceId: String?,
    refAudioFile: String?,
    model: String,
    responseFormat: String,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.MISTRAL, AccountCapability.TEXT_TO_SPEECH) {
            MistralApiKeyStore.forAccount(it).loadBlocking()
        }
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
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral text-to-speech failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral text-to-speech failed.")
    }
}

internal fun SubscriptionOperations.mistralListVoices(): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.MISTRAL, AccountCapability.LIST_VOICES) {
            MistralApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
    }
    return try {
        mistralAudioClient.listVoices(apiKey)
    } catch (exception: MistralQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral voice list failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral voice list failed.")
    }
}
