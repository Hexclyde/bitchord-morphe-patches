package app.bitchord.soulseek;

import android.content.Context;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.CoroutineSingletons;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Version-qualified bridge to the host registry. Selected rows cannot enter its fallback walk. */
public final class HostDownloadAccess {
    private HostDownloadAccess() {}

    public static void init(Context context) {
        app.bitchord.telegram.DownloadSelection.init(context);
    }

    static Object propertyObject(Object object, String name) throws Exception {
        Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object instanceof Class ? null : object);
    }

    static String propertyText(Object object, String name) {
        try {
            Object value = propertyObject(object, name);
            if ("sourceQuality".equals(name)) {
                try {
                    String reference = String.valueOf(propertyObject(object, "videoId"));
                    String exact =
                            (String)
                                    Class.forName("app.bitchord.telegram.FlacAddon")
                                            .getMethod("choiceLabel", String.class)
                                            .invoke(null, reference);
                    if (!exact.isEmpty()) return exact;
                } catch (ReflectiveOperationException | LinkageError optionalSourceAbsent) {
                    // Optional add-on; other sources keep the native quality label.
                }
            }
            return value == null ? "" : String.valueOf(value);
        } catch (Exception unavailable) {
            return "";
        }
    }

    static Object call(Object owner, String name, Object... arguments) throws Exception {
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        Method found = null;
        for (Method method : type.getMethods())
            if (method.getName().equals(name)
                    && method.getParameterTypes().length == arguments.length) {
                if (found != null) throw new IOException("Ambiguous host interface");
                found = method;
            }
        if (found == null) throw new IOException("Host interface unavailable");
        return found.invoke(Modifier.isStatic(found.getModifiers()) ? null : owner, arguments);
    }

    static Object registry(String method, Object... arguments) throws Exception {
        return call(
                Class.forName("com.music.bitchord.data.sources.SourceRegistry"), method, arguments);
    }

    static List<?> sources() throws Exception {
        return (List<?>) registry("activeForDownload");
    }

    static String sourceText(Object source, String method) throws Exception {
        return String.valueOf(
                Class.forName("com.music.bitchord.data.sources.MusicSource")
                        .getMethod(method)
                        .invoke(source));
    }

    static List<?> search(Object source, String query) throws Exception {
        Class<?> api = Class.forName("com.music.bitchord.data.sources.MusicSource");
        Method search = null;
        for (Method method : api.getMethods())
            if (method.getName().equals("search")) search = method;
        if (search == null) throw new IOException("Search is unavailable");
        CountDownLatch done = new CountDownLatch(1);
        Object[] result = new Object[1];
        Continuation<Object> continuation =
                new Continuation<Object>() {
                    public CoroutineContext getContext() {
                        return EmptyCoroutineContext.INSTANCE;
                    }

                    public void resumeWith(Object value) {
                        result[0] = value;
                        done.countDown();
                    }
                };
        Object immediate = search.invoke(source, query, 12, true, null, continuation);
        if (immediate != CoroutineSingletons.COROUTINE_SUSPENDED) return (List<?>) immediate;
        if (!done.await(25, TimeUnit.SECONDS)) throw new IOException("Source search timed out");
        if (result[0] != null && result[0].getClass().getName().equals("kotlin.Result$Failure"))
            throw new IOException("Source search failed");
        return (List<?>) result[0];
    }

    /** Called at the host's fallback entry, before another provider or YouTube can be searched. */
    public static boolean forbidFallback(Object song) {
        return app.bitchord.telegram.DownloadSelection.forbidFallback(song);
    }

    public static void fileNameStart(Object song) {
        app.bitchord.telegram.DownloadSelection.fileNameStart(song);
    }

    public static String fileNameFinish(String name) {
        return app.bitchord.telegram.DownloadSelection.fileNameFinish(name);
    }

    public static String selectedFileName(Object song, String name) {
        return app.bitchord.telegram.DownloadSelection.selectedFileName(song, name);
    }

    static String selectedTitle(Context context, String reference) {
        return context.getSharedPreferences("bitchord_download_explorer", 0)
                .getString("title." + reference, reference);
    }

    static void enqueue(Context context, Object song, String sourceId, boolean now)
            throws Exception {
        init(context);
        String reference = propertyText(song, "videoId");
        if (!reference.startsWith("src:" + sourceId + "::"))
            throw new IOException("Source did not return a source-specific file");
        Object config = registry("config", sourceId);
        if (config == null
                || !Boolean.TRUE.equals(call(config, "getEnabled"))
                || !Boolean.TRUE.equals(call(config, "getAllowDownloads")))
            throw new IOException("This source no longer permits downloads");
        app.bitchord.telegram.DownloadSelection.keep(context, song);
        Class<?> host = Class.forName("com.music.bitchord.download.Downloads");
        call(host, "enqueue", context, song, null);
        if (now) {
            Object lock = propertyObject(host, "lock");
            synchronized (lock) {
                @SuppressWarnings("unchecked")
                Map<String, Object> queue = (Map<String, Object>) propertyObject(host, "pending");
                Object selected = queue.remove(reference);
                if (selected != null) {
                    Map<String, Object> rest = new LinkedHashMap<>(queue);
                    queue.clear();
                    queue.put(reference, selected);
                    queue.putAll(rest);
                }
            }
        }
    }

    static void forget(String id) throws Exception {
        Class.forName("com.music.bitchord.download.Downloads")
                .getMethod("forget", String.class)
                .invoke(null, id);
    }

    static Map<?, ?> state(String fieldName) throws Exception {
        Object flow =
                propertyObject(Class.forName("com.music.bitchord.download.Downloads"), fieldName);
        return (Map<?, ?>)
                Class.forName("kotlinx.coroutines.flow.StateFlow")
                        .getMethod("getValue")
                        .invoke(flow);
    }

    static void cancel(String id) throws Exception {
        call(Class.forName("com.music.bitchord.download.Downloads"), "cancel", id);
    }
}
