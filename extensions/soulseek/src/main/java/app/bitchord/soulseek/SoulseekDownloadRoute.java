package app.bitchord.soulseek;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/** Binds an explicit Addons download to the selected peer result, without a title re-search. */
public final class SoulseekDownloadRoute {
    private static final String PREFIX = "src:soulseek-native::";

    private SoulseekDownloadRoute() {}

    public static boolean ownsTrack(String videoId) {
        return videoId != null && videoId.startsWith(PREFIX);
    }

    public static Object routeOrNull(String videoId) throws IOException {
        if (!ownsTrack(videoId)) return null;
        String id = videoId.substring(PREFIX.length());
        if (!id.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid Soulseek download reference.");
        try {
            Object config =
                    Class.forName("com.music.bitchord.data.sources.SourceRegistry")
                            .getMethod("config", String.class)
                            .invoke(null, "soulseek-native");
            if (config == null
                    || !Boolean.TRUE.equals(
                            config.getClass().getMethod("getEnabled").invoke(config)))
                throw new IOException("Enable Unofficial Soulseek before downloading this result.");

            SoulseekBridge bridge = SoulseekBridge.get();
            String session = bridge.sessionTag;
            SoulseekTrack track = bridge.track(id);
            String url = SoulseekAddon.ORIGIN + session + "/audio/" + id;
            if (!session.equals(bridge.sessionTag))
                throw new IOException("Soulseek session changed. Search again.");

            Class<?> routeType = Class.forName("com.music.bitchord.download.Downloads$Route");
            Class<?> manifestType = Class.forName("com.google.android.gms.tasks.zzr");
            Constructor<?> constructor =
                    routeType.getConstructor(
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            manifestType,
                            Function3.class);
            Object route =
                    constructor.newInstance(
                            track.format,
                            track.mime,
                            "Soulseek " + track.format.toUpperCase(java.util.Locale.ROOT),
                            track.format.toUpperCase(java.util.Locale.ROOT),
                            null,
                            new Writer(url));
            Field taggable = routeType.getDeclaredField("taggable");
            taggable.setAccessible(true);
            taggable.setBoolean(route, false);
            return route;
        } catch (IOException error) {
            throw error;
        } catch (InvocationTargetException error) {
            throw new IOException(
                    "The selected Soulseek download could not be prepared.", error.getCause());
        } catch (ReflectiveOperationException | LinkageError | SecurityException error) {
            throw new IOException(
                    "Soulseek downloads are unavailable in this BitChord version.", error);
        }
    }

    public static final class Writer extends SuspendLambda
            implements Function3<OutputStream, Function2<Long, Long, ?>, Continuation<?>, Object> {
        private final String url;

        Writer(String url) {
            super(3, null);
            this.url = url;
        }

        @Override
        public Object invoke(
                OutputStream sink,
                Function2<Long, Long, ?> progress,
                Continuation<?> continuation) {
            if (sink == null || progress == null || continuation == null)
                throw new IllegalArgumentException(
                        "Download writer requires an output and job context.");
            try {
                SoulseekDownloads.fetch(url, sink, progress, continuation.getContext());
                return Unit.INSTANCE;
            } catch (IOException error) {
                throw new UncheckedIOException("Soulseek download did not complete.", error);
            }
        }

        @Override
        protected Object invokeSuspend(Object result) {
            throw new IllegalStateException("The download writer does not suspend.");
        }
    }
}
