package de.moritzf.quota.idea.openai

import de.moritzf.proxy.media.MediaOperationException
import de.moritzf.proxy.media.MediaOperations
import de.moritzf.proxy.media.OpenAiMedia
import de.moritzf.proxy.media.SpeechAudio
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolveException
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.minimax.MiniMaxImageClient
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.supergrok.SuperGrokAudioClient
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.zai.ZaiImageClient
import java.nio.file.Files

internal class IdeMediaOperations(
    private val superGrokImages: SuperGrokImagineClient = SuperGrokImagineClient.createDefault(),
    private val superGrokAudio: SuperGrokAudioClient = SuperGrokAudioClient.createDefault(),
    private val miniMaxImages: MiniMaxImageClient = MiniMaxImageClient.createDefault(),
    private val zaiImages: ZaiImageClient = ZaiImageClient.createDefault(),
) : MediaOperations {
    override fun generateImageUrl(providerId: String, model: String, prompt: String): String {
        val body = when (providerId) {
            "supergrok" -> superGrokImages.generateImage(superGrokToken(), prompt, model)
            "minimax" -> miniMaxImages.generateImage(miniMaxKey(), miniMaxRegion(), prompt, model = model)
            "zai" -> zaiImages.generateImage(zaiKey(), prompt, model = model)
            else -> throw MediaOperationException("Image generation is not available for $providerId.")
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
        if (providerId != "supergrok") {
            throw MediaOperationException("Speech synthesis is not available for $providerId.")
        }
        val bytes = superGrokAudio.synthesizeBytes(superGrokToken(), input, voice, responseFormat = format)
        return SpeechAudio(bytes, OpenAiMedia.speechContentType(format))
    }

    override fun transcribe(
        providerId: String,
        model: String,
        audio: ByteArray,
        filename: String,
        language: String?,
    ): String {
        if (providerId != "supergrok") {
            throw MediaOperationException("Speech-to-text is not available for $providerId.")
        }
        val temp = Files.createTempFile("proxy-stt-", "-$filename")
        try {
            Files.write(temp, audio)
            return superGrokAudio.transcribe(superGrokToken(), localFile = temp, language = language)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun superGrokToken(): String {
        val account = resolve(QuotaProviderType.SUPERGROK, AccountCapability.PROXY)
        return QuotaAuthService.getInstance().peekAccessToken(account.id, QuotaProviderType.SUPERGROK)
            ?: throw MediaOperationException("SuperGrok login required.")
    }

    private fun miniMaxKey(): String {
        val account = resolve(QuotaProviderType.MINIMAX, AccountCapability.IMAGE_GENERATION)
        return MiniMaxApiKeyStore.forAccount(account.id).loadBlocking()
            ?: throw MediaOperationException("MiniMax subscription key missing.")
    }

    private fun zaiKey(): String {
        val account = resolve(QuotaProviderType.ZAI, AccountCapability.IMAGE_GENERATION)
        return ZaiApiKeyStore.forAccount(account.id).loadBlocking()
            ?: throw MediaOperationException("Z.ai API key missing.")
    }

    private fun resolve(type: QuotaProviderType, capability: AccountCapability) = try {
        AccountResolver.resolve(type, capability = capability)
    } catch (exception: AccountResolveException) {
        throw MediaOperationException(exception.message ?: "Account not configured.")
    }

    private fun miniMaxRegion(): MiniMaxRegion {
        val settings = QuotaSettingsState.getInstance()
        val account = AccountResolver.resolveOrNull(QuotaProviderType.MINIMAX, capability = AccountCapability.IMAGE_GENERATION)
        return when (account?.let { settings.miniMaxRegionFor(it.id) } ?: MiniMaxRegionPreference.GLOBAL) {
            MiniMaxRegionPreference.CN -> MiniMaxRegion.CN
            MiniMaxRegionPreference.GLOBAL, MiniMaxRegionPreference.AUTO -> MiniMaxRegion.GLOBAL
        }
    }
}
