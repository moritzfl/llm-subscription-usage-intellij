package de.moritzf.quota.minimax

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

open class MiniMaxAudioClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val globalApiHost: URI = GLOBAL_API_HOST,
    private val cnApiHost: URI = CN_API_HOST,
) {
    open fun synthesize(
        apiKey: String,
        region: MiniMaxRegion,
        text: String,
        targetFile: String?,
        baseDirectory: Path?,
        voiceId: String? = null,
        model: String = DEFAULT_SPEECH_MODEL,
        responseFormat: String = DEFAULT_FORMAT,
    ): String {
        val input = text.trim()
        if (input.isBlank()) {
            throw MiniMaxQuotaException("Speech text is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw MiniMaxQuotaException("MiniMax API key missing. Add a MiniMax API key in settings.")
        }
        val format = responseFormat.trim().ifBlank { DEFAULT_FORMAT }
        val output = resolveOutput(targetFile, baseDirectory, format)
            ?: throw MiniMaxQuotaException("Provide targetFile so the audio is written to disk.")
        val body = JsonSupport.json.encodeToString(
            MiniMaxSpeechRequestDto(
                model = model.trim().ifBlank { DEFAULT_SPEECH_MODEL },
                text = input,
                voiceSetting = MiniMaxVoiceSettingDto(voiceId = voiceId?.trim()?.ifBlank { null } ?: DEFAULT_VOICE),
                audioSetting = MiniMaxAudioSettingDto(format = format),
                outputFormat = "hex",
            ),
        )
        val response = send(postJson(token, apiHost(region).resolve(SPEECH_PATH), body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw MiniMaxQuotaException("Session expired. Check your MiniMax API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException("MiniMax text-to-speech failed (HTTP $status). Try again later.", status, responseBody)
        }
        checkBaseResp(responseBody, "MiniMax text-to-speech failed")
        val hex = audioHex(responseBody)
            ?: throw MiniMaxQuotaException("MiniMax text-to-speech returned no audio.", status, responseBody)
        val bytes = hexToBytes(hex)
        val parent = output.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(output, bytes)
        return JsonSupport.json.encodeToString(MiniMaxSpeechWriteResult(output.toString(), bytes.size.toLong()))
    }

    open fun listVoices(apiKey: String, region: MiniMaxRegion): String {
        val token = apiKey.trim().ifBlank {
            throw MiniMaxQuotaException("MiniMax API key missing. Add a MiniMax API key in settings.")
        }
        val body = JsonSupport.json.encodeToString(MiniMaxGetVoiceRequestDto())
        val response = send(postJson(token, apiHost(region).resolve(VOICE_PATH), body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw MiniMaxQuotaException("Session expired. Check your MiniMax API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException("MiniMax voice list failed (HTTP $status). Try again later.", status, responseBody)
        }
        checkBaseResp(responseBody, "MiniMax voice list failed")
        return McpJson.providerJsonOrRaw(responseBody)
    }

    open fun transcribe(
        apiKey: String,
        region: MiniMaxRegion,
        localFile: Path? = null,
        language: String? = null,
        diarize: Boolean = false,
        model: String = DEFAULT_TRANSCRIBE_MODEL,
    ): String {
        val token = apiKey.trim().ifBlank {
            throw MiniMaxQuotaException("MiniMax subscription key missing. Add a MiniMax Token Plan subscription key in settings.")
        }
        val path = localFile
        if (path == null || !Files.isRegularFile(path)) {
            throw MiniMaxQuotaException("MiniMax speech-to-text requires a local audio file.")
        }
        if (Files.size(path) > MAX_TRANSCRIBE_BYTES) {
            throw MiniMaxQuotaException("Audio file exceeds MiniMax's 50 MB speech-to-text limit.")
        }
        val boundary = "----MiniMaxAsr${UUID.randomUUID().toString().replace("-", "")}"
        val format = if (diarize) "verbose_json" else "json"
        val builder = HttpRequest.newBuilder()
            .uri(apiHost(region).resolve(TRANSCRIBE_PATH))
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Accept", "application/json")
        language?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("language", it) }
        val response = send(
            builder.POST(
                MultipartFilePublisher.of(
                    boundary,
                    listOf(
                        "model" to model.trim().ifBlank { DEFAULT_TRANSCRIBE_MODEL },
                        "response_format" to format,
                    ),
                    path,
                ),
            ).build(),
        )
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw MiniMaxQuotaException("Session expired. Check your MiniMax subscription key.", status, body)
        }
        if (status == 402) {
            throw MiniMaxQuotaException("MiniMax speech-to-text is not included for this subscription key.", status, body)
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException("MiniMax speech-to-text failed (HTTP $status). Try again later.", status, body)
        }
        return McpJson.providerJsonOrRaw(body)
    }

    private fun postJson(apiKey: String, uri: URI, body: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun apiHost(region: MiniMaxRegion): URI {
        return when (region) {
            MiniMaxRegion.GLOBAL -> globalApiHost
            MiniMaxRegion.CN -> cnApiHost
        }
    }

    companion object {
        const val DEFAULT_SPEECH_MODEL = "speech-2.6-turbo"
        const val DEFAULT_TRANSCRIBE_MODEL = "asr-1.0"
        const val DEFAULT_VOICE = "English_expressive_narrator"
        const val DEFAULT_FORMAT = "mp3"
        private const val SPEECH_PATH = "v1/t2a_v2"
        private const val VOICE_PATH = "v1/get_voice"
        private const val TRANSCRIBE_PATH = "v1/speech_to_text"
        private const val MAX_TRANSCRIBE_BYTES = 50L * 1024L * 1024L
        private val GLOBAL_API_HOST = URI.create("https://api.minimax.io/")
        private val CN_API_HOST = URI.create("https://api.minimaxi.com/")

        fun createDefault(): MiniMaxAudioClient = MiniMaxAudioClient()

        internal fun audioHex(body: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val data = root["data"] as? JsonObject ?: return null
            return (data["audio"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val cleaned = hex.trim().removePrefix("0x")
            if (cleaned.length % 2 != 0) {
                throw MiniMaxQuotaException("MiniMax audio hex was truncated.")
            }
            return ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        internal fun checkBaseResp(body: String, label: String) {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return
            val base = root["base_resp"] as? JsonObject ?: return
            val code = (base["status_code"] as? JsonPrimitive)?.intOrNull ?: 0
            if (code != 0) {
                val msg = (base["status_msg"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { code.toString() }
                throw MiniMaxQuotaException("$label: $msg", code, body)
            }
        }

        internal fun resolveOutput(targetFile: String?, baseDirectory: Path?, format: String): Path? {
            return DefaultOutputFiles.resolveInsideBase(targetFile, baseDirectory, DefaultOutputFiles.speech(format))
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}

@Serializable
internal data class MiniMaxSpeechRequestDto(
    val model: String,
    val text: String,
    @SerialName("voice_setting") val voiceSetting: MiniMaxVoiceSettingDto,
    @SerialName("audio_setting") val audioSetting: MiniMaxAudioSettingDto,
    @SerialName("output_format") val outputFormat: String,
    val stream: Boolean = false,
)

@Serializable
internal data class MiniMaxVoiceSettingDto(
    @SerialName("voice_id") val voiceId: String,
)

@Serializable
internal data class MiniMaxAudioSettingDto(
    val format: String,
)

@Serializable
internal data class MiniMaxGetVoiceRequestDto(
    @SerialName("voice_type") val voiceType: String = "system",
)

@Serializable
internal data class MiniMaxSpeechWriteResult(
    @SerialName("output_file") val outputFile: String,
    val bytes: Long,
)
