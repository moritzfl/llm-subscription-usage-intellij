package de.moritzf.quota.mistral

import de.moritzf.quota.shared.DefaultOutputFiles
import de.moritzf.quota.shared.DocumentLimits
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.MultipartFilePublisher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*

open class MistralAudioClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val transcriptionsUri: URI = TRANSCRIPTIONS_URI,
    private val speechUri: URI = SPEECH_URI,
    private val voicesUri: URI = VOICES_URI,
) {
    open fun transcribe(
        apiKey: String,
        audioUrl: String? = null,
        localFile: Path? = null,
        language: String? = null,
        diarize: Boolean = false,
        model: String = DEFAULT_TRANSCRIBE_MODEL,
    ): String {
        val token = requireApiKey(apiKey)
        val response = when {
            !audioUrl?.trim().isNullOrEmpty() ->
                sendString(
                    postJson(
                        token,
                        transcriptionsUri,
                        transcriptionJson(model, audioUrl.trim(), language, diarize)
                    )
                )

            localFile != null ->
                sendString(multipartTranscription(token, localFile, model, language, diarize))

            else ->
                throw MistralQuotaException("Provide audioUrl or a local audio file path.")
        }
        return parseOk(response, "Mistral speech-to-text failed") { McpJson.providerJsonOrRaw(it) }
    }

    open fun synthesize(
        apiKey: String,
        text: String,
        targetFile: String? = null,
        baseDirectory: Path? = null,
        voiceId: String? = null,
        refAudioFile: Path? = null,
        model: String = DEFAULT_SPEECH_MODEL,
        responseFormat: String = DEFAULT_SPEECH_FORMAT,
    ): String {
        val token = requireApiKey(apiKey)
        val input = text.trim()
        if (input.isBlank()) {
            throw MistralQuotaException("Speech text is required.")
        }
        val format = responseFormat.trim().ifBlank { DEFAULT_SPEECH_FORMAT }
        val output = resolveOutput(targetFile, baseDirectory, format)
            ?: throw MistralQuotaException("Provide targetFile so the audio is written to disk.")
        val resolvedVoice = voiceId?.trim()?.takeIf { it.isNotEmpty() } ?: firstPresetVoiceId(fetchVoices(token))
        val refAudio = readRefAudio(refAudioFile)
        if (resolvedVoice == null && refAudio == null) {
            throw MistralQuotaException("No Mistral voice available. Pass voiceId or refAudioFile.")
        }
        val body = JsonSupport.json.encodeToString(
            MistralSpeechRequestDto(
                model = model.trim().ifBlank { DEFAULT_SPEECH_MODEL },
                input = input,
                voiceId = resolvedVoice,
                refAudio = refAudio,
                responseFormat = format,
            ),
        )
        val response = sendString(postJson(token, speechUri, body))
        return parseOk(response, "Mistral text-to-speech failed") { responseBody ->
            val parsed =
                runCatching { JsonSupport.json.decodeFromString<MistralSpeechResponseDto>(responseBody) }.getOrNull()
            val encoded = parsed?.audioData?.substringAfter("base64,", parsed.audioData)?.trim().orEmpty()
            if (encoded.isEmpty()) {
                throw MistralQuotaException(
                    "Mistral text-to-speech returned no audio.",
                    response.statusCode(),
                    responseBody
                )
            }
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                ?: throw MistralQuotaException(
                    "Mistral text-to-speech returned invalid audio.",
                    response.statusCode(),
                    responseBody
                )
            val parent = output.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            Files.write(output, bytes)
            JsonSupport.json.encodeToString(MistralSpeechWriteResult(output.toString(), bytes.size.toLong()))
        }
    }

    open fun listVoices(apiKey: String): String {
        return fetchVoices(requireApiKey(apiKey))
    }

    private fun fetchVoices(token: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$voicesUri?type=preset&limit=100"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = sendString(request)
        return parseOk(response, "Mistral voice list failed") { McpJson.providerJsonOrRaw(it) }
    }

    private fun multipartTranscription(
        apiKey: String,
        path: Path,
        model: String,
        language: String?,
        diarize: Boolean,
    ): HttpRequest {
        if (!Files.isRegularFile(path)) {
            throw MistralQuotaException("Local audio file was not found.")
        }
        val boundary = "----MistralAudio${UUID.randomUUID().toString().replace("-", "")}"
        val fields = buildList {
            add("model" to model.trim().ifBlank { DEFAULT_TRANSCRIBE_MODEL })
            add("diarize" to diarize.toString())
            language?.trim()?.takeIf { it.isNotEmpty() }?.let { add("language" to it) }
        }
        return HttpRequest.newBuilder()
            .uri(transcriptionsUri)
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(MultipartFilePublisher.of(boundary, fields, path))
            .build()
    }

    private fun sendString(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun parseOk(response: HttpResponse<String>, failure: String, map: (String) -> String): String {
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, body)
        }
        if (status !in 200..299) {
            throw MistralQuotaException("$failure (HTTP $status). Try again later.", status, body)
        }
        return map(body)
    }

    companion object {
        const val DEFAULT_TRANSCRIBE_MODEL = "voxtral-mini-latest"
        const val DEFAULT_SPEECH_MODEL = "voxtral-mini-tts-2603"
        const val DEFAULT_SPEECH_FORMAT = "mp3"
        private val TRANSCRIPTIONS_URI = URI.create("https://api.mistral.ai/v1/audio/transcriptions")
        private val SPEECH_URI = URI.create("https://api.mistral.ai/v1/audio/speech")
        private val VOICES_URI = URI.create("https://api.mistral.ai/v1/audio/voices")

        fun createDefault(): MistralAudioClient = MistralAudioClient()

        internal fun transcriptionJson(model: String, fileUrl: String, language: String?, diarize: Boolean): String {
            return JsonSupport.json.encodeToString(
                MistralTranscriptionRequestDto(
                    model = model.trim().ifBlank { DEFAULT_TRANSCRIBE_MODEL },
                    fileUrl = fileUrl,
                    language = language?.trim()?.takeIf { it.isNotEmpty() },
                    diarize = diarize,
                ),
            )
        }

        internal fun firstPresetVoiceId(body: String): String? {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val items = root["items"] as? JsonArray ?: return null
            return items.firstNotNullOfOrNull { item ->
                (item as? JsonObject)?.get("id")
                    ?.let { it as? JsonPrimitive }?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }

        internal fun resolveOutput(targetFile: String?, baseDirectory: Path?, format: String): Path? {
            return DefaultOutputFiles.resolveInsideBase(targetFile, baseDirectory, DefaultOutputFiles.speech(format))
        }

        private fun requireApiKey(apiKey: String): String {
            return apiKey.trim().ifBlank {
                throw MistralQuotaException("Mistral API key missing. Add a Mistral API key in settings.")
            }
        }

        private fun readRefAudio(path: Path?): String? {
            if (path == null) return null
            if (!Files.isRegularFile(path)) {
                throw MistralQuotaException("Reference audio file was not found.")
            }
            DocumentLimits.inlineOverflowMessage(path)?.let { throw MistralQuotaException(it) }
            return Base64.getEncoder().encodeToString(Files.readAllBytes(path))
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

        private fun postJson(apiKey: String, uri: URI, body: String): HttpRequest {
            return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
    }
}

@Serializable
internal data class MistralTranscriptionRequestDto(
    val model: String,
    @SerialName("file_url") val fileUrl: String? = null,
    val language: String? = null,
    val diarize: Boolean = false,
)

@Serializable
internal data class MistralSpeechRequestDto(
    val model: String,
    val input: String,
    @SerialName("voice_id") val voiceId: String? = null,
    @SerialName("ref_audio") val refAudio: String? = null,
    @SerialName("response_format") val responseFormat: String = "mp3",
)

@Serializable
internal data class MistralSpeechResponseDto(
    @SerialName("audio_data") val audioData: String = "",
)

@Serializable
internal data class MistralSpeechWriteResult(
    @SerialName("output_file") val outputFile: String,
    val bytes: Long,
)
