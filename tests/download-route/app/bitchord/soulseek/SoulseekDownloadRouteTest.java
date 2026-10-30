package app.bitchord.soulseek;

import com.music.bitchord.data.sources.SourceRegistry;
import com.music.bitchord.download.Downloads;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

public final class SoulseekDownloadRouteTest {
    private static int checks;
    private static final String ID = String.join("", java.util.Collections.nCopies(64, "a"));
    private static final String KEY = "src:soulseek-native::" + ID;

    private interface Action {
        void run() throws Exception;
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void rejects(Class<? extends Throwable> kind, Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected " + kind.getSimpleName());
        } catch (Throwable error) {
            check(kind.isInstance(error), "Expected " + kind.getSimpleName() + ", got " + error);
        }
    }

    private static final class Context implements CoroutineContext {
        public <E extends Element> E get(Key<E> key) {
            return null;
        }

        public <R> R fold(R initial, Function2<? super R, ? super Element, ? extends R> operation) {
            return initial;
        }

        public CoroutineContext minusKey(Key<?> key) {
            return this;
        }

        public CoroutineContext plus(CoroutineContext context) {
            return this;
        }
    }

    private static final class ContinuationFixture implements Continuation<Object> {
        final CoroutineContext context = new Context();

        public CoroutineContext getContext() {
            return context;
        }

        public void resumeWith(Object value) {
            throw new AssertionError("Writer must not resume caller");
        }
    }

    public static void main(String[] ignored) throws Exception {
        check(SoulseekDownloadRoute.routeOrNull(null) == null, "Null is not a source pin");
        check(SoulseekDownloadRoute.routeOrNull("youtube-id") == null, "YouTube untouched");
        check(
                SoulseekDownloadRoute.routeOrNull("src:telegram-native::" + ID) == null,
                "Telegram untouched");
        check(SourceRegistry.lookups == 0 && SoulseekBridge.gets == 0, "Other sources do no work");
        check(SoulseekDownloadRoute.ownsTrack(KEY), "Own selected source");
        check(
                !SoulseekDownloadRoute.ownsTrack("src:soulseek-other::" + ID),
                "Exact source ownership");
        rejects(
                IOException.class,
                () -> SoulseekDownloadRoute.routeOrNull("src:soulseek-native::bad"));
        rejects(IOException.class, () -> SoulseekDownloadRoute.routeOrNull(KEY + "?other=file"));
        check(SourceRegistry.lookups == 0, "Malformed source pins never resolve");
        SourceRegistry.enabled = false;
        rejects(IOException.class, () -> SoulseekDownloadRoute.routeOrNull(KEY));
        check(SoulseekBridge.gets == 0, "Disabled source never opens session");
        SourceRegistry.enabled = true;
        SoulseekBridge.expired = true;
        rejects(IOException.class, () -> SoulseekDownloadRoute.routeOrNull(KEY));
        SoulseekBridge.expired = false;
        SoulseekBridge.changeSession = true;
        rejects(IOException.class, () -> SoulseekDownloadRoute.routeOrNull(KEY));
        SoulseekBridge.changeSession = false;
        SoulseekBridge.INSTANCE.sessionTag = "session-a";
        Downloads.Route route = (Downloads.Route) SoulseekDownloadRoute.routeOrNull(KEY);
        check(
                ID.equals(SoulseekBridge.INSTANCE.selected),
                "Selected ID resolved without title search");
        check(
                "flac".equals(route.extension) && "audio/flac".equals(route.mimeType),
                "Selected format");
        check(route.offlineHls == null, "Original is not a streaming manifest");
        if (Boolean.getBoolean("host.v19")) {
            boolean removed = false;
            try {
                route.getClass().getDeclaredField("taggable");
            } catch (NoSuchFieldException expected) {
                removed = true;
            }
            check(
                    removed,
                    "1.9 route has no taggable field; selected-file tagger hook owns bypass");
        } else {
            java.lang.reflect.Field taggable = route.getClass().getDeclaredField("taggable");
            taggable.setAccessible(true);
            check(!taggable.getBoolean(route), "Legacy route does not request metadata work");
        }
        check(route.write instanceof SuspendLambda, "R8 constructor writer cast is satisfied");
        check(route.write.getArity() == 3, "Host writer arity");
        SoulseekDownloadRoute.Writer writer = (SoulseekDownloadRoute.Writer) route.write;
        ContinuationFixture continuation = new ContinuationFixture();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        long[] progress = new long[2];
        Object result =
                writer.invoke(
                        sink,
                        (written, total) -> {
                            progress[0] = written;
                            progress[1] = total;
                            return Unit.INSTANCE;
                        },
                        continuation);
        check(result == Unit.INSTANCE, "Completes synchronously as Kotlin Unit");
        check(
                Arrays.equals(sink.toByteArray(), SoulseekDownloads.ORIGINAL),
                "Writer preserves selected bytes");
        check(
                SoulseekDownloads.lastContext == continuation.context,
                "Cancellation uses caller job context");
        check(
                (SoulseekAddon.ORIGIN + "session-a/audio/" + ID).equals(SoulseekDownloads.lastUrl),
                "Captured session and file URL");
        check(progress[0] == 6 && progress[1] == 6, "Progress retained");
        SoulseekBridge.INSTANCE.sessionTag = "session-b";
        rejects(
                UncheckedIOException.class,
                () ->
                        writer.invoke(
                                new ByteArrayOutputStream(),
                                (a, b) -> Unit.INSTANCE,
                                continuation));
        SoulseekBridge.INSTANCE.sessionTag = "session-a";
        SoulseekDownloads.cancelled = true;
        rejects(
                CancellationException.class,
                () ->
                        writer.invoke(
                                new ByteArrayOutputStream(),
                                (a, b) -> Unit.INSTANCE,
                                continuation));
        SoulseekDownloads.cancelled = false;
        rejects(
                IllegalArgumentException.class,
                () -> writer.invoke(null, (a, b) -> Unit.INSTANCE, continuation));
        Downloads.rejectConstructor = true;
        rejects(IOException.class, () -> SoulseekDownloadRoute.routeOrNull(KEY));
        check(SoulseekDownloads.fetches == 3, "No alternate source fetch on resolution failures");
        System.out.println("PASS: " + checks + " explicit Soulseek download route checks.");
    }
}
