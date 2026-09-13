package de.moritzf.proxy.media

data class SpeechAudio(
    val bytes: ByteArray,
    val contentType: String,
)

interface MediaOperations {
    fun generateImageUrl(providerId: String, model: String, prompt: String): String

    fun synthesizeSpeech(
        providerId: String,
        model: String,
        input: String,
        voice: String?,
        format: String,
    ): SpeechAudio

    fun transcribe(
        providerId: String,
        model: String,
        audio: ByteArray,
        filename: String,
        language: String?,
    ): String
}

class UnsupportedMediaOperations : MediaOperations {
    override fun generateImageUrl(providerId: String, model: String, prompt: String): String {
        throw MediaOperationException("Image generation is not available for $providerId.")
    }

    override fun synthesizeSpeech(
        providerId: String,
        model: String,
        input: String,
        voice: String?,
        format: String,
    ): SpeechAudio {
        throw MediaOperationException("Speech synthesis is not available for $providerId.")
    }

    override fun transcribe(
        providerId: String,
        model: String,
        audio: ByteArray,
        filename: String,
        language: String?,
    ): String {
        throw MediaOperationException("Speech-to-text is not available for $providerId.")
    }
}

internal class MediaOperationException(message: String) : RuntimeException(message)
