package de.moritzf.quota.idea.openai

import de.moritzf.proxy.media.MediaOperationException
import de.moritzf.proxy.media.MediaOperations
import de.moritzf.proxy.media.OpenAiMedia
import de.moritzf.proxy.media.SpeechAudio
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.mcp.CodexMcpClient
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolveException
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.minimax.MiniMaxAudioClient
import de.moritzf.quota.minimax.MiniMaxImageClient
import de.moritzf.quota.mistral.MistralAudioClient
import de.moritzf.quota.supergrok.SuperGrokAudioClient
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.zai.ZaiAudioClient
import de.moritzf.quota.zai.ZaiImageClient
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class IdeMediaOperations(
    private val accounts: de.moritzf.quota.idea.operations.AccountOperations =
        de.moritzf.quota.idea.operations.AccountOperations(),
    private val superGrokImages: SuperGrokImagineClient = SuperGrokImagineClient.createDefault(),
    private val superGrokAudio: SuperGrokAudioClient = SuperGrokAudioClient.createDefault(),
    private val miniMaxImages: MiniMaxImageClient = MiniMaxImageClient.createDefault(),
    private val miniMaxAudio: MiniMaxAudioClient = MiniMaxAudioClient.createDefault(),
    private val zaiImages: ZaiImageClient = ZaiImageClient.createDefault(),
    private val zaiAudio: ZaiAudioClient = ZaiAudioClient.createDefault(),
    private val mistralAudio: MistralAudioClient = MistralAudioClient.createDefault(),
    private val codex: (AccountCapability) -> CodexMcpClient = CodexMcpClient::createDefault,
) : MediaOperations {
    override fun generateImageUrl(providerId: String, model: String, prompt: String): String {
        val body = runMedia {
            when (providerId) {
                "supergrok" ->
                    accounts.withSuperGrok(AccountCapability.IMAGE_GENERATION) { token ->
                        superGrokImages.generateImage(token, prompt, model)
                    }
                "minimax" ->
                    accounts.withMiniMax(AccountCapability.IMAGE_GENERATION) { key, region ->
                        miniMaxImages.generateImage(
                            key,
                            region,
                            prompt,
                            model = model,
                        )
                    }
                "zai" ->
                    zaiImages.generateImage(
                        zaiKey(AccountCapability.IMAGE_GENERATION),
                        prompt,
                        model = model,
                    )
                else ->
                    throw MediaOperationException(
                        "Image generation is not available for $providerId."
                    )
            }
        }
        return OpenAiMedia.imageUrlFromProviderJson(body)
            ?: throw MediaOperationException("Provider returned no image URL.")
    }

    override fun synthesizeSpeech(
        providerId: String,
        model: String,
        input: String,
        voice: String?,
        format: String,
    ): SpeechAudio {
        val bytes = runMedia {
            when (providerId) {
                "supergrok" ->
                    accounts.withSuperGrok(AccountCapability.TEXT_TO_SPEECH) { token ->
                        superGrokAudio.synthesizeBytes(
                            token,
                            input,
                            voice,
                            responseFormat = format,
                        )
                    }
                "mistral" ->
                    withTempAudio("proxy-tts-", ".$format") { path ->
                        mistralAudio.synthesize(
                            apiKey = mistralKey(AccountCapability.TEXT_TO_SPEECH),
                            text = input,
                            targetFile = path.toString(),
                            voiceId = voice,
                            model = model,
                            responseFormat = format,
                        )
                        Files.readAllBytes(path)
                    }
                "minimax" ->
                    withTempAudio("proxy-tts-", ".$format") { path ->
                        accounts.withMiniMax(AccountCapability.TEXT_TO_SPEECH) { key, region ->
                            miniMaxAudio.synthesize(
                                apiKey = key,
                                region = region,
                                text = input,
                                targetFile = path.toString(),
                                baseDirectory = null,
                                voiceId = voice,
                                model = model,
                                responseFormat = format,
                            )
                            Files.readAllBytes(path)
                        }
                    }
                "openai" ->
                    withTempAudio("proxy-tts-", ".$format") { path ->
                        requireCodex(
                            codex(AccountCapability.TEXT_TO_SPEECH)
                                .synthesize(
                                    text = input,
                                    targetFile = path.toString(),
                                    voiceId = voice,
                                    model = model,
                                    responseFormat = format,
                                )
                        )
                        Files.readAllBytes(path)
                    }
                else ->
                    throw MediaOperationException(
                        "Speech synthesis is not available for $providerId."
                    )
            }
        }
        return SpeechAudio(bytes, OpenAiMedia.speechContentType(format))
    }

    override fun transcribe(
        providerId: String,
        model: String,
        audio: ByteArray,
        filename: String,
        language: String?,
    ): String {
        return runMedia {
            withTempAudio("proxy-stt-", "-$filename") { path ->
                Files.write(path, audio)
                when (providerId) {
                    "supergrok" ->
                        accounts.withSuperGrok(AccountCapability.SPEECH_TO_TEXT) { token ->
                            superGrokAudio.transcribe(
                                token,
                                localFile = path,
                                language = language,
                            )
                        }
                    "mistral" ->
                        mistralAudio.transcribe(
                            apiKey = mistralKey(AccountCapability.SPEECH_TO_TEXT),
                            localFile = path,
                            language = language,
                            model = model,
                        )
                    "minimax" ->
                        accounts.withMiniMax(AccountCapability.SPEECH_TO_TEXT) { key, region ->
                            miniMaxAudio.transcribe(
                                apiKey = key,
                                region = region,
                                localFile = path,
                                language = language,
                                model = model,
                            )
                        }
                    "zai" ->
                        zaiAudio.transcribe(
                            apiKey = zaiKey(AccountCapability.SPEECH_TO_TEXT),
                            localFile = path,
                            model = model,
                        )
                    "openai" ->
                        requireCodex(
                            codex(AccountCapability.SPEECH_TO_TEXT)
                                .transcribe(
                                    localFile = path,
                                    language = language,
                                    model = model,
                                )
                        )
                    else ->
                        throw MediaOperationException(
                            "Speech-to-text is not available for $providerId."
                        )
                }
            }
        }
    }

    private fun mistralKey(capability: AccountCapability): String {
        val account = resolve(QuotaProviderType.MISTRAL, capability)
        return MistralApiKeyStore.forAccount(account.id).loadBlocking()
            ?: throw MediaOperationException("Mistral API key missing.")
    }

    private fun zaiKey(capability: AccountCapability): String {
        val account = resolve(QuotaProviderType.ZAI, capability)
        return ZaiApiKeyStore.forAccount(account.id).loadBlocking()
            ?: throw MediaOperationException("Z.ai API key missing.")
    }

    private fun resolve(type: QuotaProviderType, capability: AccountCapability) =
        try {
            AccountResolver.resolve(type, capability = capability)
        } catch (exception: AccountResolveException) {
            exception.rethrowIfCancellation()
            throw MediaOperationException(exception.message ?: "Account not configured.")
        }

    private fun requireCodex(result: CodexMcpClient.CodexMcpResponse): String {
        if (!result.isError) return result.body
        throw MediaOperationException(codexErrorMessage(result.body))
    }

    private fun codexErrorMessage(body: String): String {
        val root = de.moritzf.proxy.server.JsonHelper.parseToJsonElementOrNull(body) as? JsonObject
        val error = (root?.get("error") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return error.ifEmpty { "OpenAI media request failed." }
    }

    private fun <T> runMedia(block: suspend () -> T): T {
        return try {
            runBlocking { block() }
        } catch (exception: MediaOperationException) {
            exception.rethrowIfCancellation()
            throw exception
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            throw MediaOperationException(exception.message ?: "Media request failed.")
        }
    }

    private inline fun <T> withTempAudio(prefix: String, suffix: String, block: (Path) -> T): T {
        val temp = Files.createTempFile(prefix, suffix)
        return try {
            block(temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
