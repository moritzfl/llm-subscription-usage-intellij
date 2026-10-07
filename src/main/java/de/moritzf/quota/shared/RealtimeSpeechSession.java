package de.moritzf.quota.shared;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.logging.Logging;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.audio.*;
import java.io.*;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Finite Codex v3 voice renderer. No microphone or local speaker is opened.
 * Keep the wire/transport implementation aligned with Voice Bridge. */
public final class RealtimeSpeechSession {
    public static final String MODEL = "gpt-live-1-codex";
    public static final List<String> VOICES = List.of("marin", "cedar");
    public record Call(String sdp, String id) {}
    public record Event(String type, String role, String code) {}
    public record Pcm(byte[] bytes, int rate, int channels) {}
    public interface Protocol {
        Call create(String sdp) throws Exception;
        CompletableFuture<WebSocket> connect(String callId, WebSocket.Listener listener) throws Exception;
        String speak(String text);
        Event event(String json);
    }
    @FunctionalInterface public interface Chunks { void accept(Pcm pcm) throws Exception; }
    private static final int MAX_AUDIO = 8 * 1024 * 1024;

    public static Pcm synthesize(String text, Protocol protocol, Chunks chunks) throws Exception {
        if (text.isBlank() || text.length() > 8000) throw new IOException("Use between 1 and 8000 characters for experimental realtime speech.");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        try (var peer = new Peer(protocol)) {
            Call call = protocol.create(peer.offer(deadline));
            if (!call.id().matches("(?:rtc_[A-Za-z0-9_-]{1,200}|[a-fA-F0-9-]{36})"))
                throw new IOException("Invalid realtime call identifier.");
            peer.answer(call.sdp(), deadline);
            var connection = protocol.connect(call.id(), peer.listener());
            try {
                peer.socket = await(connection, deadline);
                peer.check();
                peer.audio.clear(); peer.completed.set(0); peer.armed.set(true);
                await(peer.socket.sendText(protocol.speak(text), true), deadline);
                return peer.collect(chunks, deadline);
            } catch (Exception e) {
                peer.check();
                throw e;
            } finally {
                if (!connection.isDone()) connection.cancel(true);
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new IOException("Realtime voice connection failed.");
        } catch (Error e) {
            if (!(e instanceof LinkageError) && e.getClass() != Error.class) throw e;
            throw new IOException("Realtime speech is unavailable on this server platform.");
        }
    }

    private static <T> T await(CompletableFuture<T> task, long deadline) throws Exception {
        return task.get(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(20), deadline - System.nanoTime())), TimeUnit.NANOSECONDS);
    }

    static final class Peer implements AutoCloseable {
        final Protocol protocol;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicReference<IOException> failure = new AtomicReference<>();
        final BlockingQueue<Pcm> audio = new ArrayBlockingQueue<>(1000);
        final CompletableFuture<Void> ice = new CompletableFuture<>(), opened = new CompletableFuture<>();
        final AtomicLong completed = new AtomicLong();
        final HeadlessAudioDeviceModule module;
        final PeerConnectionFactory factory;
        final RTCPeerConnection connection;
        final AudioTrackSource source;
        final AudioTrack input;
        final RTCRtpSender sender;
        final RTCDataChannel channel;
        AudioTrack output;
        AudioTrackSink sink;
        volatile WebSocket socket;

        Peer(Protocol protocol) {
            this.protocol = protocol;
            Logging.logToDebug(Logging.Severity.NONE);
            module = new HeadlessAudioDeviceModule();
            factory = new PeerConnectionFactory(module);
            connection = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                public void onIceCandidate(RTCIceCandidate candidate) {}
                public void onIceGatheringChange(RTCIceGatheringState state) { if (state == RTCIceGatheringState.COMPLETE) ice.complete(null); }
                public void onConnectionChange(RTCPeerConnectionState state) {
                    if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.DISCONNECTED) fail("Realtime audio connection was interrupted.");
                }
                public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] streams) {
                    try {
                        if (receiver.getTrack() instanceof AudioTrack track) {
                            output = track;
                            sink = (data, bits, rate, channels, frames) -> {
                                if (closed.get() || !armed.get()) return;
                                if (bits != 16 || rate < 8000 || rate > 48000 || channels < 1 || channels > 2 || data.length != frames * channels * 2)
                                    fail("Unsupported realtime audio format.");
                                else if (!audio.offer(new Pcm(data.clone(), rate, channels))) fail("Realtime audio consumer is too slow.");
                            };
                            track.addSink(sink);
                        }
                    } finally { receiver.dispose(); }
                }
                public void onTrack(RTCRtpTransceiver transceiver) { transceiver.dispose(); }
            });
            source = factory.createAudioSource(new AudioOptions());
            input = factory.createAudioTrack("silence", source);
            sender = connection.addTrack(input, List.of("speech"));
            channel = connection.createDataChannel("oai-events", new RTCDataChannelInit());
            channel.registerObserver(new RTCDataChannelObserver() {
                public void onBufferedAmountChange(long size) {}
                public void onStateChange() { if (channel.getState() == RTCDataChannelState.OPEN) opened.complete(null); }
                public void onMessage(RTCDataChannelBuffer message) {
                    if (!message.binary && message.data.remaining() <= 65536) event(StandardCharsets.UTF_8.decode(message.data).toString());
                }
            });
        }
        void fail(String message) {
            var error = new IOException(message);
            failure.compareAndSet(null, error); ice.completeExceptionally(error); opened.completeExceptionally(error);
        }
        void check() throws IOException { if (failure.get() != null) throw failure.get(); }
        void event(String message) {
            try {
                Event event = protocol.event(message);
                if (event.type().equals("error")) fail(event.code().equals("forbidden")
                        ? "ChatGPT denied access to the experimental voice session. This login can still support text and dictation."
                        : "ChatGPT realtime voice returned an error.");
                if (armed.get() && (event.type().equals("output_audio_buffer.stopped") || event.type().equals("turn.done") && event.role().equals("assistant")))
                    completed.compareAndSet(0, System.nanoTime());
            } catch (RuntimeException e) { fail("Invalid realtime voice event."); }
        }
        String offer(long deadline) throws Exception {
            var offer = new CompletableFuture<RTCSessionDescription>();
            connection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                public void onSuccess(RTCSessionDescription sdp) { offer.complete(sdp); }
                public void onFailure(String ignored) { offer.completeExceptionally(new IOException("Could not create realtime audio offer.")); }
            });
            var set = new CompletableFuture<Void>();
            connection.setLocalDescription(await(offer, deadline), observer(set));
            await(set, deadline); await(ice, deadline); check();
            return connection.getLocalDescription().sdp;
        }
        void answer(String sdp, long deadline) throws Exception {
            var set = new CompletableFuture<Void>();
            connection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp), observer(set));
            await(set, deadline); await(opened, deadline); check();
        }
        WebSocket.Listener listener() {
            return new WebSocket.Listener() {
                final StringBuilder buffer = new StringBuilder();
                public void onOpen(WebSocket ws) { if (closed.get()) ws.abort(); else ws.request(1); }
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    if (buffer.length() + data.length() > 65536) { fail("Realtime event exceeds the size limit."); ws.abort(); return null; }
                    buffer.append(data);
                    if (last) { event(buffer.toString()); buffer.setLength(0); }
                    ws.request(1); return null;
                }
                public void onError(WebSocket ws, Throwable error) { if (!closed.get()) fail("Realtime voice control connection failed."); }
                public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                    if (!closed.get() && completed.get() == 0) fail("Realtime voice ended before speech completed.");
                    return null;
                }
            };
        }
        Pcm collect(Chunks chunks, long deadline) throws Exception {
            var result = new ByteArrayOutputStream();
            var preroll = new java.util.ArrayDeque<Pcm>();
            int rate = 0, channels = 0, prerollBytes = 0;
            long lastSignal = 0;
            while (System.nanoTime() < deadline) {
                check();
                Pcm frame = audio.poll(50, TimeUnit.MILLISECONDS);
                long now = System.nanoTime();
                if (frame != null) {
                    if (rate != 0 && (rate != frame.rate() || channels != frame.channels())) throw new IOException("Realtime audio format changed.");
                    rate = frame.rate(); channels = frame.channels();
                    boolean signal = false;
                    for (int i = 0; i + 1 < frame.bytes().length; i += 2)
                        if (Math.abs((short) ((frame.bytes()[i] & 255) | frame.bytes()[i + 1] << 8)) >= 128) { signal = true; break; }
                    if (signal) lastSignal = now;
                    if (result.size() == 0) {
                        preroll.add(frame); prerollBytes += frame.bytes().length;
                        while (preroll.size() > 1 && prerollBytes > rate * channels * 2 / 5) prerollBytes -= preroll.removeFirst().bytes().length;
                        if (!signal) continue;
                        for (var start : preroll) { result.writeBytes(start.bytes()); chunks.accept(start); }
                        preroll.clear();
                    } else {
                        if (result.size() + frame.bytes().length > MAX_AUDIO) throw new IOException("Realtime speech exceeds the audio size limit.");
                        result.writeBytes(frame.bytes()); chunks.accept(frame);
                    }
                }
                // RTP and sideband completion travel separately. Drain the playout tail before closing.
                if (completed.get() != 0 && result.size() > 0 && now - completed.get() > TimeUnit.SECONDS.toNanos(1)
                        && now - lastSignal > TimeUnit.MILLISECONDS.toNanos(400)) return new Pcm(result.toByteArray(), rate, channels);
            }
            throw new TimeoutException("Realtime speech timed out.");
        }
        public void close() {
            closed.set(true);
            if (socket != null) socket.abort();
            channel.unregisterObserver(); channel.close();
            if (output != null && sink != null) output.removeSink(sink);
            sender.dispose(); connection.close(); channel.dispose();
            try {
                // Receiver tracks are borrowed handles; closing the peer releases them.
                input.dispose(); source.dispose();
            } finally { factory.dispose(); module.dispose(); }
        }
        private static SetSessionDescriptionObserver observer(CompletableFuture<Void> result) {
            return new SetSessionDescriptionObserver() {
                public void onSuccess() { result.complete(null); }
                public void onFailure(String ignored) { result.completeExceptionally(new IOException("Realtime audio negotiation failed.")); }
            };
        }
    }
}
