package de.moritzf.quota.idea.openai

import de.moritzf.proxy.media.MediaOperationException
import de.moritzf.proxy.media.MediaOperations
import de.moritzf.proxy.media.OpenAiMedia
import de.moritzf.proxy.media.SpeechAudio
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.mcp.CodexMcpClient
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolveException
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.minimax.MiniMaxAudioClient
import de.moritzf.quota.minimax.MiniMaxImageClient
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.mistral.MistralAudioClient
import de.moritzf.quota.supergrok.SuperGrokAudioClient
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.zai.ZaiAudioClient
import de.moritzf.quota.zai.ZaiImageClient
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class IdeMediaOperations(
    private val superGrokImages: SuperGrokImagineClient = SuperGrokImagineClient.createDefault(),
    private val superGrokAudio: SuperGrokAudioClient = SuperGrokAudioClient.createDefault(),
    private val miniMaxImages: MiniMaxImageClient = MiniMaxImageClient.createDefault(),
    private val miniMaxAudio: MiniMaxAudioClient = MiniMaxAudioClient.createDefault(),
    private val zaiImages: ZaiImageClient = ZaiImageClient.createDefault(),
    private val zaiAudio: ZaiAudioClient = ZaiAudioClient.createDefault(),
    private val mistralAudio: MistralAudioClient = MistralAudioClient.createDefault(),
    private val codex: CodexMcpClient = CodexMcpClient.createDefault(),
) : MediaOperations {
    override fun generateImageUrl(providerId: String, model: String, prompt: String): String {
        val body = runMedia {
            when (providerId) {
                "supergrok" -> superGrokImages.generateImage(superGrokToken(), prompt, model)
                "minimax" -> miniMaxImages.generateImage(miniMaxKey(AccountCapability.IMAGE_GENERATION), miniMaxRegion(AccountCapability.IMAGE_GENERATION), prompt, model = model)
                "zai" -> zaiImages.generateImage(zaiKey(AccountCapability.IMAGE_GENERATION), prompt, model = model)
                else -> throw MediaOperationException("Image generation is not available for $providerId.")
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
                "supergrok" -> superGrokAudio.synthesizeBytes(superGrokToken(), input, voice, responseFormat = format)
                "mistral" -> withTempAudio("proxy-tts-", ".$format") { path ->
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
                "minimax" -> withTempAudio("proxy-tts-", ".$format") { path ->
                    miniMaxAudio.synthesize(
                        apiKey = miniMaxKey(AccountCapability.TEXT_TO_SPEECH),
                        region = miniMaxRegion(AccountCapability.TEXT_TO_SPEECH),
                        text = input,
                        targetFile = path.toString(),
                        baseDirectory = null,
                        voiceId = voice,
                        model = model,
                        responseFormat = format,
                    )
                    Files.readAllBytes(path)
                }
                "openai" -> withTempAudio("proxy-tts-", ".$format") { path ->
                    requireCodex(
                        codex.synthesize(
                            text = input,
                            targetFile = path.toString(),
                            voiceId = voice,
                            model = model,
                            responseFormat = format,
                        ),
                    )
                    Files.readAllBytes(path)
                }
                else -> throw MediaOperationException("Speech synthesis is not available for $providerId.")
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
                    "supergrok" -> superGrokAudio.transcribe(superGrokToken(), localFile = path, language = language)
                    "mistral" -> mistralAudio.transcribe(
                        apiKey = mistralKey(AccountCapability.SPEECH_TO_TEXT),
                        localFile = path,
                        language = language,
                        model = model,
                    )
                    "minimax" -> miniMaxAudio.transcribe(
                        apiKey = miniMaxKey(AccountCapability.SPEECH_TO_TEXT),
                        region = miniMaxRegion(AccountCapability.SPEECH_TO_TEXT),
                        localFile = path,
                        language = language,
                        model = model,
                    )
                    "zai" -> zaiAudio.transcribe(
                        apiKey = zaiKey(AccountCapability.SPEECH_TO_TEXT),
                        localFile = path,
                        model = model,
                    )
                    "openai" -> requireCodex(
                        codex.transcribe(
                            localFile = path,
                            language = language,
                            model = model,
                        ),
                    )
                    else -> throw MediaOperationException("Speech-to-text is not available for $providerId.")
                }
            }
        }
    }

    private fun superGrokToken(): String {
        val account = resolve(QuotaProviderType.SUPERGROK, AccountCapability.PROXY)
        return QuotaAuthService.getInstance().peekAccessToken(account.id, QuotaProviderType.SUPERGROK)
            ?: throw MediaOperationException("SuperGrok login required.")
    }

    private fun miniMaxKey(capability: AccountCapability): String {
        val account = resolve(QuotaProviderType.MINIMAX, capability)
        return MiniMaxApiKeyStore.forAccount(account.id).loadBlocking()
            ?: throw MediaOperationException("MiniMax subscription key missing.")
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

    private fun resolve(type: QuotaProviderType, capability: AccountCapability) = try {
        AccountResolver.resolve(type, capability = capability)
    } catch (exception: AccountResolveException) {
        throw MediaOperationException(exception.message ?: "Account not configured.")
    }

    private fun miniMaxRegion(capability: AccountCapability): MiniMaxRegion {
        val settings = QuotaSettingsState.getInstance()
        val account = AccountResolver.resolveOrNull(QuotaProviderType.MINIMAX, capability = capability)
        return when (account?.let { settings.miniMaxRegionFor(it.id) } ?: MiniMaxRegionPreference.GLOBAL) {
            MiniMaxRegionPreference.CN -> MiniMaxRegion.CN
            MiniMaxRegionPreference.GLOBAL, MiniMaxRegionPreference.AUTO -> MiniMaxRegion.GLOBAL
        }
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

    private fun <T> runMedia(block: () -> T): T {
        return try {
            block()
        } catch (exception: MediaOperationException) {
            throw exception
        } catch (exception: Exception) {
            throw MediaOperationException(exception.message ?: "Media request failed.")
        }
    }

    private fun <T> withTempAudio(prefix: String, suffix: String, block: (Path) -> T): T {
        val temp = Files.createTempFile(prefix, suffix)
        return try {
            block(temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
