package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.DefaultOutputFiles
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.MultipartFilePublisher
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

open class SuperGrokAudioClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    open fun transcribe(
        accessToken: String,
        audioUrl: String? = null,
        localFile: Path? = null,
        language: String? = null,
        diarize: Boolean = false,
    ): String {
        val token = requireToken(accessToken)
        val url = audioUrl?.trim().orEmpty()
        val hasFile = localFile != null
        if (!hasFile && url.isEmpty()) {
            throw SuperGrokQuotaException("Provide audioUrl or a local audio file path.")
        }
        if (hasFile && !Files.isRegularFile(localFile)) {
            throw SuperGrokQuotaException("Local audio file was not found.")
        }
        val request = multipartStt(token, localFile, url.takeIf { it.isNotEmpty() }, language, diarize)
        return parseOk(sendString(request), "Grok speech-to-text failed") { McpJson.providerJsonOrRaw(it) }
    }

    open fun synthesize(
        accessToken: String,
        text: String,
        targetFile: String? = null,
        baseDirectory: Path? = null,
        voiceId: String? = null,
        language: String? = null,
        responseFormat: String = DEFAULT_SPEECH_FORMAT,
    ): String {
        val token = requireToken(accessToken)
        val input = text.trim()
        if (input.isBlank()) {
            throw SuperGrokQuotaException("Speech text is required.")
        }
        val format = responseFormat.trim().ifBlank { DEFAULT_SPEECH_FORMAT }
        val bytes = requestSpeechBytes(token, input, voiceId, language, format)
        val output = resolveSpeechOutput(targetFile, baseDirectory, format)
            ?: throw SuperGrokQuotaException("Provide targetFile so the audio is written to disk.")
        val parent = output.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(output, bytes)
        return JsonSupport.json.encodeToString(GrokSpeechWriteResult(output.toString(), bytes.size.toLong()))
    }

    open fun synthesizeBytes(
        accessToken: String,
        text: String,
        voiceId: String? = null,
        language: String? = null,
        responseFormat: String = DEFAULT_SPEECH_FORMAT,
    ): ByteArray {
        val token = requireToken(accessToken)
        val input = text.trim()
        if (input.isBlank()) {
            throw SuperGrokQuotaException("Speech text is required.")
        }
        val format = responseFormat.trim().ifBlank { DEFAULT_SPEECH_FORMAT }
        return requestSpeechBytes(token, input, voiceId, language, format)
    }

    private fun requestSpeechBytes(
        token: String,
        input: String,
        voiceId: String?,
        language: String?,
        format: String,
    ): ByteArray {
        val body = JsonSupport.json.encodeToString(
            GrokTtsRequestDto(
                text = input,
                voiceId = voiceId?.trim()?.ifBlank { null } ?: DEFAULT_VOICE,
                language = language?.trim()?.ifBlank { null } ?: DEFAULT_LANGUAGE,
                outputFormat = GrokTtsOutputFormatDto(codec = format),
            ),
        )
        val request = HttpRequest.newBuilder()
            .uri(baseUri.resolve(TTS_PATH))
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return sendBytes(request, "Grok text-to-speech failed")
    }

    open fun listVoices(accessToken: String): String {
        val token = requireToken(accessToken)
        val request = HttpRequest.newBuilder()
            .uri(baseUri.resolve(VOICES_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        return parseOk(sendString(request), "Grok voice list failed") { McpJson.providerJsonOrRaw(it) }
    }

    private fun multipartStt(
        token: String,
        localFile: Path?,
        url: String?,
        language: String?,
        diarize: Boolean,
    ): HttpRequest {
        val boundary = "----GrokAudio${UUID.randomUUID().toString().replace("-", "")}"
        val fields = buildList {
            language?.trim()?.takeIf { it.isNotEmpty() }?.let { add("language" to it) }
            if (diarize) add("diarize" to "true")
            url?.let { add("url" to it) }
        }
        return HttpRequest.newBuilder()
            .uri(baseUri.resolve(STT_PATH))
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("User-Agent", USER_AGENT)
            .POST(MultipartFilePublisher.of(boundary, fields, localFile))
            .build()
    }

    private fun sendString(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw SuperGrokQuotaException("Grok voice request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException("Grok voice request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun sendBytes(request: HttpRequest, failure: String): ByteArray {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (exception: IOException) {
            throw SuperGrokQuotaException("Grok voice request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException("Grok voice request failed. Check your connection.", 0, null, exception)
        }
        val status = response.statusCode()
        if (status == 401 || status == 403) {
            throw SuperGrokQuotaException("Grok auth expired. Log in to SuperGrok again from settings.", status)
        }
        if (status !in 200..299) {
            throw SuperGrokQuotaException("$failure (HTTP $status). Try again later.", status)
        }
        return response.body()
    }

    private fun parseOk(response: HttpResponse<String>, failure: String, map: (String) -> String): String {
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw SuperGrokQuotaException("Grok auth expired. Log in to SuperGrok again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw SuperGrokQuotaException("$failure (HTTP $status). Try again later.", status, body)
        }
        return map(body)
    }

    private fun requireToken(accessToken: String): String {
        return accessToken.trim().ifBlank {
            throw SuperGrokQuotaException("Grok login required. Log in from SuperGrok settings.")
        }
    }

    companion object {
        const val DEFAULT_VOICE = "eve"
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_SPEECH_FORMAT = "mp3"
        private const val STT_PATH = "stt"
        private const val TTS_PATH = "tts"
        private const val VOICES_PATH = "tts/voices"
        private const val USER_AGENT = "openai-usage-quota-intellij"
        private val DEFAULT_BASE_URI = URI.create("https://api.x.ai/v1/")

        fun createDefault(): SuperGrokAudioClient = SuperGrokAudioClient()

        internal fun resolveSpeechOutput(targetFile: String?, baseDirectory: Path?, format: String): Path? {
            return DefaultOutputFiles.resolveInsideBase(targetFile, baseDirectory, DefaultOutputFiles.speech(format))
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}

@Serializable
internal data class GrokTtsRequestDto(
    val text: String,
    @SerialName("voice_id") val voiceId: String,
    val language: String,
    @SerialName("output_format") val outputFormat: GrokTtsOutputFormatDto? = null,
)

@Serializable
internal data class GrokTtsOutputFormatDto(
    val codec: String,
)

@Serializable
internal data class GrokSpeechWriteResult(
    @SerialName("output_file") val outputFile: String,
    val bytes: Long,
)
