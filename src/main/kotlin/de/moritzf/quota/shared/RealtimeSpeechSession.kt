package de.moritzf.quota.shared

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.audio.*
import java.io.IOException
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Finite Codex v3 voice renderer. No microphone or local speaker is opened. */
object RealtimeSpeechSession {
    const val MODEL = "gpt-live-1-codex"
    val VOICES = listOf("marin", "cedar")

    data class Call(val sdp: String, val id: String)

    data class Event(val type: String, val role: String, val code: String)

    class Pcm(val bytes: ByteArray, val rate: Int, val channels: Int)

    interface Protocol {
        fun create(sdp: String): Call

        fun connect(callId: String, listener: WebSocket.Listener): CompletableFuture<WebSocket>

        fun speak(text: String): String

        fun event(json: String): Event
    }

    fun interface Chunks {
        fun accept(pcm: Pcm)
    }

    private val CALL_ID = Regex("(?:rtc_[A-Za-z0-9_-]{1,200}|[a-fA-F0-9-]{36})")

    fun synthesize(text: String, protocol: Protocol, chunks: Chunks): Pcm {
        if (text.isBlank() || text.length > 8000) {
            throw IOException("Use between 1 and 8000 characters for experimental realtime speech.")
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        try {
            Peer(protocol).use { peer ->
                val call = protocol.create(peer.offer(deadline))
                if (!CALL_ID.matches(call.id))
                    throw IOException("Invalid realtime call identifier.")
                peer.answer(call.sdp, deadline)
                val connection = protocol.connect(call.id, peer.listener())
                try {
                    val socket = await(connection, deadline)
                    peer.attachSocket(socket)
                    peer.check()
                    peer.audio.clear()
                    peer.completed.set(0)
                    peer.armed.set(true)
                    await(socket.sendText(protocol.speak(text), true), deadline)
                    return peer.collect(chunks, deadline)
                } catch (failure: Exception) {
                    if (failure is InterruptedException || failure is CancellationException)
                        throw failure
                    peer.check()
                    throw failure
                } finally {
                    if (!connection.isDone) connection.cancel(true)
                }
            }
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is Exception) throw cause
            throw IOException("Realtime voice connection failed.")
        } catch (failure: Error) {
            if (failure !is LinkageError && failure.javaClass != Error::class.java) throw failure
            throw IOException("Realtime speech is unavailable on this server platform.")
        }
    }

    private fun <T> await(task: CompletableFuture<T>, deadline: Long): T =
        task.get(
            (deadline - System.nanoTime()).coerceIn(1, TimeUnit.SECONDS.toNanos(20)),
            TimeUnit.NANOSECONDS,
        )

    private class Peer(private val protocol: Protocol) : AutoCloseable {
        val armed = AtomicBoolean()
        private val failure = AtomicReference<IOException?>()
        val audio = ArrayBlockingQueue<Pcm>(1000)
        private val ice = CompletableFuture<Void>()
        private val opened = CompletableFuture<Void>()
        val completed = AtomicLong()
        private lateinit var module: HeadlessAudioDeviceModule
        private lateinit var factory: PeerConnectionFactory
        private lateinit var connection: RTCPeerConnection
        private lateinit var source: AudioTrackSource
        private lateinit var input: AudioTrack
        private lateinit var sender: RTCRtpSender
        private lateinit var channel: RTCDataChannel
        private var output: AudioTrack? = null
        private var sink: AudioTrackSink? = null
        private var socket: WebSocket? = null
        private val resources =
            RealtimeSpeechResources(
                { socket?.abort() },
                { if (::channel.isInitialized) channel.unregisterObserver() },
                { if (::channel.isInitialized) channel.close() },
                { sink?.let { output?.removeSink(it) } },
                { if (::sender.isInitialized) sender.dispose() },
                { if (::connection.isInitialized) connection.close() },
                { if (::channel.isInitialized) channel.dispose() },
                // Receiver tracks are borrowed handles; closing the peer releases them.
                { if (::input.isInitialized) input.dispose() },
                { if (::source.isInitialized) source.dispose() },
                { if (::factory.isInitialized) factory.dispose() },
                { if (::module.isInitialized) module.dispose() },
            )
        private val closed
            get() = resources.closed

        init {
            resources.initialize {
                Logging.logToDebug(Logging.Severity.NONE)
                module = HeadlessAudioDeviceModule()
                factory = PeerConnectionFactory(module)
                connection =
                    factory.createPeerConnection(
                        RTCConfiguration(),
                        object : PeerConnectionObserver {
                            override fun onIceCandidate(candidate: RTCIceCandidate) {}

                            override fun onIceGatheringChange(state: RTCIceGatheringState) {
                                if (state == RTCIceGatheringState.COMPLETE) ice.complete(null)
                            }

                            override fun onConnectionChange(state: RTCPeerConnectionState) {
                                if (
                                    state == RTCPeerConnectionState.FAILED ||
                                        state == RTCPeerConnectionState.DISCONNECTED
                                ) {
                                    fail("Realtime audio connection was interrupted.")
                                }
                            }

                            override fun onAddTrack(
                                receiver: RTCRtpReceiver,
                                streams: Array<MediaStream>,
                            ) {
                                try {
                                    val track = receiver.track
                                    if (track is AudioTrack) {
                                        output = track
                                        val trackSink =
                                            AudioTrackSink { data, bits, rate, channels, frames ->
                                                if (closed.get() || !armed.get())
                                                    return@AudioTrackSink
                                                if (
                                                    bits != 16 ||
                                                        rate !in 8000..48000 ||
                                                        channels !in 1..2 ||
                                                        data.size != frames * channels * 2
                                                ) {
                                                    fail("Unsupported realtime audio format.")
                                                } else if (
                                                    !audio.offer(Pcm(data.copyOf(), rate, channels))
                                                ) {
                                                    fail("Realtime audio consumer is too slow.")
                                                }
                                            }
                                        sink = trackSink
                                        track.addSink(trackSink)
                                    }
                                } finally {
                                    receiver.dispose()
                                }
                            }

                            override fun onTrack(transceiver: RTCRtpTransceiver) {
                                transceiver.dispose()
                            }
                        },
                    )
                source = factory.createAudioSource(AudioOptions())
                input = factory.createAudioTrack("silence", source)
                sender = connection.addTrack(input, listOf("speech"))
                channel = connection.createDataChannel("oai-events", RTCDataChannelInit())
                channel.registerObserver(
                    object : RTCDataChannelObserver {
                        override fun onBufferedAmountChange(size: Long) {}

                        override fun onStateChange() {
                            if (channel.state == RTCDataChannelState.OPEN) opened.complete(null)
                        }

                        override fun onMessage(message: RTCDataChannelBuffer) {
                            if (!message.binary && message.data.remaining() <= 65536) {
                                event(StandardCharsets.UTF_8.decode(message.data).toString())
                            }
                        }
                    }
                )
            }
        }

        private fun fail(message: String) {
            val error = IOException(message)
            failure.compareAndSet(null, error)
            ice.completeExceptionally(error)
            opened.completeExceptionally(error)
        }

        fun check() {
            failure.get()?.let { throw it }
        }

        private fun event(message: String) {
            try {
                val event = protocol.event(message)
                if (event.type == "error") {
                    fail(
                        if (event.code == "forbidden") {
                            "ChatGPT denied access to the experimental voice session. This login can still support text and dictation."
                        } else {
                            "ChatGPT realtime voice returned an error."
                        }
                    )
                }
                if (
                    armed.get() &&
                        (event.type == "output_audio_buffer.stopped" ||
                            event.type == "turn.done" && event.role == "assistant")
                ) {
                    completed.compareAndSet(0, System.nanoTime())
                }
            } catch (_: RuntimeException) {
                fail("Invalid realtime voice event.")
            }
        }

        fun offer(deadline: Long): String {
            val offer = CompletableFuture<RTCSessionDescription>()
            connection.createOffer(
                RTCOfferOptions(),
                object : CreateSessionDescriptionObserver {
                    override fun onSuccess(sdp: RTCSessionDescription) {
                        offer.complete(sdp)
                    }

                    override fun onFailure(error: String) {
                        offer.completeExceptionally(
                            IOException("Could not create realtime audio offer.")
                        )
                    }
                },
            )
            val set = CompletableFuture<Void>()
            connection.setLocalDescription(await(offer, deadline), observer(set))
            await(set, deadline)
            await(ice, deadline)
            check()
            return connection.localDescription.sdp
        }

        fun answer(sdp: String, deadline: Long) {
            val set = CompletableFuture<Void>()
            connection.setRemoteDescription(
                RTCSessionDescription(RTCSdpType.ANSWER, sdp),
                observer(set),
            )
            await(set, deadline)
            await(opened, deadline)
            check()
        }

        fun listener(): WebSocket.Listener =
            object : WebSocket.Listener {
                private val buffer = StringBuilder()

                override fun onOpen(ws: WebSocket) {
                    attachSocket(ws)
                    if (closed.get()) ws.abort() else ws.request(1)
                }

                override fun onText(
                    ws: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*>? {
                    if (buffer.length + data.length > 65536) {
                        fail("Realtime event exceeds the size limit.")
                        ws.abort()
                        return null
                    }
                    buffer.append(data)
                    if (last) {
                        event(buffer.toString())
                        buffer.setLength(0)
                    }
                    ws.request(1)
                    return null
                }

                override fun onError(ws: WebSocket, error: Throwable) {
                    if (!closed.get()) fail("Realtime voice control connection failed.")
                }

                override fun onClose(
                    ws: WebSocket,
                    status: Int,
                    reason: String,
                ): CompletionStage<*>? {
                    if (!closed.get() && completed.get() == 0L) {
                        fail("Realtime voice ended before speech completed.")
                    }
                    return null
                }
            }

        fun collect(chunks: Chunks, deadline: Long): Pcm =
            collectRealtimeSpeechAudio(
                chunks,
                deadline,
                ::check,
                completed::get,
                { audio.poll(50, TimeUnit.MILLISECONDS) },
            )

        @Synchronized
        fun attachSocket(ws: WebSocket) {
            if (closed.get()) ws.abort() else socket = ws
        }

        @Synchronized
        override fun close() {
            resources.close()
        }

        private fun observer(result: CompletableFuture<Void>): SetSessionDescriptionObserver =
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    result.complete(null)
                }

                override fun onFailure(error: String) {
                    result.completeExceptionally(IOException("Realtime audio negotiation failed."))
                }
            }
    }
}
