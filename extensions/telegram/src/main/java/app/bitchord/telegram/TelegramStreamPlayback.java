package app.bitchord.telegram;

import androidx.media3.common.Player;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Uses exposed player/sink signals. READY is never reported as audible audio output. */
final class TelegramStreamPlayback {
    private static final Map<Player, Timing> players =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Timing {
        long started = -1, request, generation, underruns, rebuffers, sampled;
        boolean sink, ready, seeking;
    }

    static void watch(Player player, BooleanSupplier current) {
        Timing timing = new Timing();
        synchronized (players) {
            if (players.containsKey(player)) return;
            players.put(player, timing);
        }
        try {
            Class<?> listener =
                    Class.forName("androidx.media3.exoplayer.analytics.AnalyticsListener");
            Object proxy =
                    Proxy.newProxyInstance(
                            listener.getClassLoader(),
                            new Class<?>[] {listener},
                            (object, method, args) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    if (method.getName().equals("hashCode"))
                                        return System.identityHashCode(object);
                                    if (method.getName().equals("equals")) return object == args[0];
                                    return "Telegram stream diagnostics";
                                }
                                try {
                                    if (!current.getAsBoolean()) return null;
                                    if (method.getName().equals("onAudioUnderrun")) {
                                        timing.underruns++;
                                        emit(player, timing, "underrun", -1);
                                    } else if (method.getName()
                                            .equals("onAudioPositionAdvancing")) {
                                        emit(
                                                player,
                                                timing,
                                                timing.started < 0
                                                        ? "sink-started"
                                                        : "sink-advancing",
                                                timing.started < 0 ? -1 : now() - timing.started);
                                        timing.started = -1;
                                    }
                                } catch (RuntimeException | LinkageError ignored) {
                                    /* Optional diagnostics cannot interrupt playback. */
                                }
                                return null;
                            });
            Class.forName("androidx.media3.exoplayer.ExoPlayer")
                    .getMethod("addAnalyticsListener", listener)
                    .invoke(player, proxy);
            timing.sink = true;
        } catch (Exception | LinkageError unavailable) {
            // R8 may not expose the analytics interface; output latency stays explicitly unknown.
        }
    }

    static void activity(Player player) {
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge != null && bridge.streamV2Enabled())
            bridge.streams.coordinator.playbackActive(player.getPlayWhenReady());
        Timing timing = players.get(player);
        if (timing != null) {
            int state = player.getPlaybackState();
            if (state == Player.STATE_BUFFERING && timing.ready && !timing.seeking) {
                timing.rebuffers++;
                emit(player, timing, "rebuffer", -1);
            }
            if (state == Player.STATE_READY) {
                if (timing.started >= 0) emit(player, timing, "ready", -1);
                timing.seeking = false;
            }
            timing.ready = state == Player.STATE_READY;
            if (Diagnostics.active() && now() - timing.sampled >= 1000) {
                timing.sampled = now();
                emit(player, timing, "sample", -1);
            }
        }
    }

    static void seek(Player player) {
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge == null) return;
        long generation = bridge.audio.seekGeneration.incrementAndGet();
        if (bridge.streamV2Enabled()) {
            bridge.streams.coordinator.seek();
            generation = bridge.streams.coordinator.generation();
        }
        Timing timing = players.get(player);
        if (timing == null) return;
        timing.started = now();
        timing.generation = generation;
        timing.seeking = true;
        timing.request = bridge.audio.trace.seek(generation);
        emit(player, timing, "seek", -1);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private static void emit(Player player, Timing timing, String signal, long latency) {
        if (!Diagnostics.active()) return;
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge == null) return;
        String uri;
        try {
            androidx.media3.common.MediaItem item = player.getCurrentMediaItem();
            uri =
                    item == null || item.localConfiguration == null
                            ? ""
                            : SourceFirst.audibleUri(item.localConfiguration.uri).toString();
        } catch (RuntimeException unavailable) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        boolean confirmed = InternalAddon.reserved(uri);
        fields.put("source_scope", confirmed ? "telegram" : "unconfirmed");
        fields.put("request_id", timing.request);
        fields.put(
                "stream_generation",
                timing.started < 0
                        ? (bridge.streamV2Enabled()
                                ? bridge.streams.coordinator.generation()
                                : bridge.audio.seekGeneration.get())
                        : timing.generation);
        long file = confirmed ? bridge.audio.trace.fileRef(uri) : 0;
        if (file > 0) fields.put("file_ref", file);
        fields.put("mono_ms", now());
        fields.put("output_ms", latency);
        fields.put("output_signal", signal);
        fields.put("output_supported", timing.sink);
        fields.put("underruns", timing.underruns);
        fields.put("rebuffer_count", timing.rebuffers);
        if (signal.equals("sample")) {
            Runtime runtime = Runtime.getRuntime();
            fields.put(
                    "heapUsedMiB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            fields.put("heapMaxMiB", runtime.maxMemory() / (1024 * 1024));
            fields.put("process_cpu_ms", android.os.Process.getElapsedCpuTime());
        }
        try {
            Object buffer = Player.class.getMethod("getTotalBufferedDuration").invoke(player);
            if (buffer instanceof Long) fields.put("buffer_ms", Math.max(0, (Long) buffer));
        } catch (Exception unavailable) {
            /* Omitted means unknown, never zero. */
        }
        fields.put("stream_v2", bridge != null && bridge.streamV2Enabled());
        Diagnostics.record("telegram.stream.output", fields);
    }
}
