package de.moritzf.quota.idea.mcp

import de.moritzf.proxy.transport.CodexHttpClient
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.RealtimeSpeechSession
import java.io.IOException
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicitly experimental, finite conversational voice rendering; WAV only. */
internal class CodexRealtimeSpeech(private val client: CodexHttpClient) {
    fun synthesize(text: String, voice: String): ByteArray {
        require(voice in RealtimeSpeechSession.VOICES) {
            "Experimental realtime speech supports marin or cedar."
        }
        val sessionId = UUID.randomUUID().toString()
        val threadId = UUID.randomUUID().toString()
        val protocol =
            object : RealtimeSpeechSession.Protocol {
                override fun create(sdp: String): RealtimeSpeechSession.Call {
                    val body = buildJsonObject {
                        put("sdp", sdp)
                        putJsonObject("session") {
                            put("model", RealtimeSpeechSession.MODEL)
                            put(
                                "instructions",
                                "Stay silent until a speakable item arrives. Speak only the supplied quoted text, verbatim, without a preface, response, extra words or tool calls.",
                            )
                            putJsonObject("audio") {
                                putJsonObject("output") { put("voice", voice) }
                            }
                            putJsonObject("delegation") {
                                put("type", "client")
                                put("ack_filler", false)
                            }
                        }
                    }
                    val response =
                        client.requestBytes(
                            "/realtime/calls?intent=quicksilver&architecture=avas",
                            "POST",
                            body.toString().toByteArray(),
                            mapOf(
                                "Content-Type" to "application/json",
                                "Accept" to "application/sdp",
                                "openai-alpha" to "quicksilver=v2",
                                "session-id" to sessionId,
                                "thread-id" to threadId,
                            ),
                        )
                    if (response.statusCode() !in 200..299)
                        throw IOException(
                            "ChatGPT realtime call failed (HTTP ${response.statusCode()})."
                        )
                    return RealtimeSpeechSession.Call(
                        response.body().toString(Charsets.UTF_8),
                        response
                            .headers()
                            .firstValue("Location")
                            .orElse("")
                            .substringAfterLast('/'),
                    )
                }

                override fun connect(callId: String, listener: WebSocket.Listener) =
                    client.realtimeSocket(callId, listener)

                override fun speak(text: String): String = buildJsonObject {
                    put("type", "session.context.append")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            put(
                                "text",
                                "Vocalize only the following quoted data, with no acknowledgement or extra words: ${JsonPrimitive(text)}",
                            )
                        }
                    }
                }
                    .toString()

                override fun event(json: String): RealtimeSpeechSession.Event {
                    val root = JsonSupport.json.parseToJsonElement(json).jsonObject
                    return RealtimeSpeechSession.Event(
                        root["type"]?.jsonPrimitive?.content.orEmpty(),
                        root["turn"]?.jsonObject?.get("role")?.jsonPrimitive?.content.orEmpty(),
                        root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content.orEmpty(),
                    )
                }
            }
        val pcm = RealtimeSpeechSession.synthesize(text, protocol) {}
        return ByteBuffer.allocate(44 + pcm.bytes().size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(36 + pcm.bytes().size)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(pcm.channels().toShort())
            .putInt(pcm.rate())
            .putInt(pcm.rate() * pcm.channels() * 2)
            .putShort((pcm.channels() * 2).toShort())
            .putShort(16)
            .put("data".toByteArray())
            .putInt(pcm.bytes().size)
            .put(pcm.bytes())
            .array()
    }
}
