package app.bitchord.telegram;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.webkit.*;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded recording for application and login failures. */
public final class Diagnostics {
    private static final long LIMIT_MS = 15 * 60 * 1000L;
    private static final Map<WebView, Boolean> watched =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadPoolExecutor writer =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128),
                    r -> {
                        Thread t = new Thread(r, "support-recording.jsonl");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private static Context app;
    private static SharedPreferences prefs;
    private static volatile DiagnosticStore store;
    private static boolean erasePending;
    private static final java.util.concurrent.atomic.AtomicLong dropped =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long deadline;
    private static volatile long generation;
    private static CrashHandler crashHandler;

    private Diagnostics() {}

    public static void seek(Object player, long target) {
        try {
            SeekTrace.requested(player, target);
        } catch (RuntimeException | LinkageError unavailable) {
            /* Observers must never change playback. */
        }
    }

    private static void action(PerformanceTrace.Stage stage, Object player) {
        if (!active()) return;
        try (PerformanceTrace.Span span = PerformanceTrace.begin(stage)) {
            span.measure("player", Integer.toUnsignedLong(System.identityHashCode(player)));
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    public static void playRequested(Object player) {
        action(PerformanceTrace.Stage.PLAY_REQUESTED, player);
    }

    public static void pauseRequested(Object player) {
        action(PerformanceTrace.Stage.PAUSE_REQUESTED, player);
    }

    public static void skipRequested(Object player) {
        action(PerformanceTrace.Stage.SKIP_REQUESTED, player);
    }

    public static void prepareRequested(Object player) {
        action(PerformanceTrace.Stage.PREPARE_REQUESTED, player);
    }

    public static void queueRequested(Object player) {
        action(PerformanceTrace.Stage.QUEUE_REQUESTED, player);
    }

    public static void playingRequested(Object player, boolean playing) {
        if (playing) playRequested(player);
        else pauseRequested(player);
    }

    public static synchronized void init(Context context) {
        HeapStatus.init(context);
        if (!SoundExtras.diagnosticsApplied() || app != null || context == null) return;
        try {
            app = context.getApplicationContext();
            if (app == null) app = context;
            prefs = app.getSharedPreferences("patch-diagnostics", Context.MODE_PRIVATE);
            final File file = new File(app.getNoBackupFilesDir(), "support-recording.jsonl");
            writer.execute(
                    () -> {
                        try {
                            DiagnosticStore loaded = new DiagnosticStore(file);
                            synchronized (Diagnostics.class) {
                                if (erasePending) {
                                    loaded.clear();
                                    erasePending = false;
                                }
                                store = loaded;
                            }
                            event("process.started");
                        } catch (Throwable ignored) {
                            deadline = 0;
                        }
                    });
            deadline = prefs.getLong("until", 0);
            long now = System.currentTimeMillis();
            if (deadline <= now || deadline > now + LIMIT_MS) deadline = 0;
            if (deadline > now && prefs.getBoolean("detailed", false)) {
                // A restarted process has new span IDs; the report retains the old process's
                // unfinished starts.
                PerformanceTrace.start(
                        Diagnostics::performance, prefs.getString("recording", null));
                sampleResources(generation);
            }
            if (deadline > now) {
                final long resumedGeneration = generation;
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(
                                () -> {
                                    if (resumedGeneration == generation) stop();
                                },
                                deadline - now);
            }
            if (app instanceof Application)
                ((Application) app).registerActivityLifecycleCallbacks(new Lifecycle());
            installCrashHandler();
        } catch (Throwable ignored) {
            deadline = 0;
        }
    }

    private static synchronized void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (crashHandler != null && previous == crashHandler) return;
        crashHandler = new CrashHandler(previous);
        Thread.setDefaultUncaughtExceptionHandler(crashHandler);
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        final Thread.UncaughtExceptionHandler previous;

        CrashHandler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        public void uncaughtException(Thread thread, Throwable error) {
            try {
                if (active() && store != null) store.append(DiagnosticRecord.failure(error));
            } catch (Throwable ignored) {
            } finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(2);
                }
            }
        }
    }

    static boolean active() {
        long left = deadline - System.currentTimeMillis();
        return prefs != null && left > 0 && left <= LIMIT_MS;
    }

    static synchronized void start(Context context) {
        start(context, 15, false);
    }

    static synchronized void start(Context context, int minutes) {
        start(context, minutes, true);
    }

    private static synchronized void start(Context context, int minutes, boolean detailed) {
        init(context);
        if (prefs == null) return;
        installCrashHandler();
        generation++;
        if (detailed) PerformanceTrace.start(Diagnostics::performance);
        else PerformanceTrace.stop();
        deadline = System.currentTimeMillis() + Math.min(15, Math.max(2, minutes)) * 60_000L;
        prefs.edit()
                .putLong("until", deadline)
                .putBoolean("detailed", detailed)
                .putString("recording", PerformanceTrace.recordingId())
                .apply();
        final long recordingGeneration = generation;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            if (recordingGeneration == generation) stop();
                        },
                        Math.max(0, deadline - System.currentTimeMillis()));
        if (detailed) sampleResources(generation);
        event("recording.started");
        event("environment.sdk sdk=" + Build.VERSION.SDK_INT + " patch=" + PatchVersion.BUILD);
        java.util.Map<String, Object> hardware = new java.util.LinkedHashMap<>();
        hardware.put("sdk", Build.VERSION.SDK_INT);
        hardware.put("manufacturer", Build.MANUFACTURER);
        hardware.put("device_model", Build.MODEL);
        hardware.put("abi", Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]);
        String version = "unknown";
        try {
            android.content.pm.PackageInfo info =
                    app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (info.versionName != null) version = info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException
                | RuntimeException unavailable) {
            // Package details can be unavailable during an update.
        }
        hardware.put("app_version", version);
        hardware.put("patch", PatchVersion.BUILD);
        append(DiagnosticRecord.structured("environment.sdk", hardware));
        try {
            android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
            event(
                    "webview.provider provider="
                            + (info == null
                                    ? "unavailable"
                                    : safe(info.packageName)
                                            + " version="
                                            + safe(info.versionName)));
        } catch (Throwable ignored) {
            event("webview.provider visibility=unavailable");
        }
        for (String name :
                new String[] {
                    "app.revanced.android.gms", "com.google.android.gms", "com.android.vending"
                }) {
            try {
                android.content.pm.PackageInfo info =
                        app.getPackageManager().getPackageInfo(name, 0);
                event("service.package package=" + name + " version=" + safe(info.versionName));
            } catch (Throwable ignored) {
                event("service.package package=" + name + " visibility=unavailable");
            }
        }
    }

    static synchronized void stop() {
        PerformanceTrace.stop();
        event("recording.stopped");
        deadline = 0;
        if (prefs != null) prefs.edit().remove("until").apply();
    }

    static synchronized void clear() {
        stop();
        generation++;
        if (store != null) {
            final DiagnosticStore current = store;
            writer.getQueue().clear();
            writer.execute(
                    () -> {
                        try {
                            current.clear();
                        } catch (java.io.IOException ignored) {
                        }
                        DiagnosticArchive.clear(app);
                    });
        } else erasePending = true;
    }

    private static void sampleResources(long revision) {
        if (revision != generation || !active()) return;
        try {
            writer.execute(
                    () -> {
                        if (revision != generation || !active()) return;
                        SourceAudio.traceMemory();
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> sampleResources(revision), 5000);
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            dropped.incrementAndGet();
        }
    }

    private static void performance(Map<String, Object> fields) {
        if (!active()) return;
        final long revision = generation;
        try {
            writer.execute(
                    () -> {
                        if (revision != generation || store == null) return;
                        String encoded = PerformanceTrace.encode(fields);
                        if (encoded != null)
                            try {
                                store.append(encoded);
                            } catch (java.io.IOException ignored) {
                                dropped.incrementAndGet();
                            }
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            dropped.incrementAndGet();
        }
    }

    static String report() {
        String content;
        try {
            content =
                    writer.submit(() -> store == null ? "Recorder unavailable\n" : store.snapshot())
                            .get(2, TimeUnit.SECONDS);
        } catch (Exception unavailable) {
            DiagnosticStore current = store;
            content = current == null ? "Recorder unavailable\n" : current.snapshot();
        }
        return "BitChord support report\nPatch bundle: "
                + PatchVersion.BUILD
                + "\nScope: this application's structured events and classified TrackLog events."
                + "\nGoogle/MicroG service internals and other applications' logs are unavailable."
                + "\nMessages, account values, page contents and URL parameters are excluded."
                + "\nWriter queue events dropped: "
                + dropped.get()
                + "\n"
                + HeapStatus.description()
                + "\n"
                + patchState()
                + "\n"
                + content;
    }

    private static String patchState() {
        StringBuilder state = new StringBuilder("Installed patch markers:");
        for (java.lang.reflect.Method method : SoundExtras.class.getDeclaredMethods()) {
            if (!method.getName().endsWith("Applied")
                    || method.getParameterTypes().length != 0
                    || method.getReturnType() != boolean.class) continue;
            try {
                if (Boolean.TRUE.equals(method.invoke(null)))
                    state.append(' ').append(method.getName().replace("Applied", ""));
            } catch (Exception ignored) {
            }
        }
        state.append("\n");
        if (SoundExtras.djApplied())
            state.append("DJ enabled=")
                    .append(DjMode.enabled)
                    .append(" journey=")
                    .append(DjMode.journey)
                    .append('\n');
        if (SoundExtras.batterySaverApplied())
            state.append("Battery saver enabled=").append(SoundExtras.batterySaver).append('\n');
        if (SoundExtras.audioFocusApplied())
            state.append("Play alongside enabled=").append(AudioFocus.alongside).append('\n');
        if (SoundExtras.pitchApplied())
            state.append("Pitch enabled=")
                    .append(SoundExtras.pitchEnabled)
                    .append(" semitones=")
                    .append(SoundExtras.pitchSemitones)
                    .append('\n');
        if (SoundExtras.videoSwitchApplied())
            state.append("Audio/video switch enabled=").append(VideoSwitch.enabled).append('\n');
        if (SourceAutomix.applied()) state.append("Source AutoMix installed=true\n");
        return state.toString();
    }

    private static String safe(String value) {
        return value != null && value.length() <= 100 && value.matches("[A-Za-z0-9._+() -]+")
                ? value
                : "unknown";
    }

    static void event(String safeEvent) {
        if (!active()) return;
        final long captured = generation;
        final java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("at", System.currentTimeMillis());
        String[] parts = safeEvent.split(" ");
        for (int i = 1; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals < 1) continue;
            String key = parts[i].substring(0, equals), value = parts[i].substring(equals + 1);
            Object typed = value;
            if (value.equals("true") || value.equals("false")) typed = Boolean.valueOf(value);
            else if (value.matches("-?[0-9]{1,10}")) {
                try {
                    typed = Long.valueOf(value);
                } catch (NumberFormatException ignored) {
                }
            }
            fields.put(key, typed);
        }
        final String record = DiagnosticRecord.structured(parts[0], fields);
        try {
            writer.execute(
                    () -> {
                        try {
                            synchronized (Diagnostics.class) {
                                if (captured == generation && store != null) store.append(record);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
        } catch (Throwable ignored) {
            dropped.incrementAndGet();
        }
    }

    private static void append(String record) {
        if (!active()) return;
        final long captured = generation;
        try {
            writer.execute(
                    () -> {
                        try {
                            synchronized (Diagnostics.class) {
                                if (captured == generation && store != null) store.append(record);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
        } catch (Throwable ignored) {
            dropped.incrementAndGet();
        }
    }

    public static void nativeLine(char severity, String message) {
        if (!active()) return;
        try {
            append(DiagnosticRecord.safeLine(severity, message));
        } catch (Throwable ignored) {
            dropped.incrementAndGet();
        }
    }

    public static void watch(WebView view) {
        if (view == null) return;
        watched.put(view, Boolean.TRUE);
        event("login.view.created");
    }

    private static boolean tracks(WebView view) {
        return view != null && watched.containsKey(view);
    }

    private static String route(String url) {
        if (url == null) return "unknown";
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String scheme = uri.getScheme();
            if ("intent".equals(scheme)) return "external-intent";
            if (!"https".equals(scheme) && !"http".equals(scheme)) return "other-scheme";
            String host = uri.getHost();
            if ("accounts.google.com".equals(host)) return "google-account";
            if ("music.youtube.com".equals(host)) return "youtube-music";
            if ("play.google.com".equals(host)) return "play-store";
            return "other-web";
        } catch (Throwable ignored) {
            return "invalid";
        }
    }

    public static void requested(WebView view, String url) {
        if (tracks(view)) event("login.navigation.requested route=" + route(url));
    }

    public static void pageStarted(WebView view, String url, Bitmap icon) {
        if (tracks(view)) event("login.page.started route=" + route(url));
    }

    public static void pageFinished(WebView view, String url) {
        if (tracks(view))
            event(
                    "login.page.finished route="
                            + route(url)
                            + " progress="
                            + view.getProgress()
                            + " width="
                            + view.getWidth()
                            + " height="
                            + view.getHeight());
    }

    public static void visited(WebView view, String url, boolean reload) {
        if (tracks(view)) event("login.history route=" + route(url) + " reload=" + reload);
    }

    public static void webError(WebView view, WebResourceRequest request, WebResourceError error) {
        try {
            if (tracks(view))
                event(
                        "login.web.error code="
                                + error.getErrorCode()
                                + " main="
                                + request.isForMainFrame());
        } catch (Throwable ignored) {
        }
    }

    public static void legacyWebError(WebView view, int code, String description, String url) {
        if (tracks(view)) event("login.web.error code=" + code + " route=" + route(url));
    }

    public static void httpError(
            WebView view, WebResourceRequest request, WebResourceResponse response) {
        try {
            if (tracks(view))
                event(
                        "login.http.error code="
                                + response.getStatusCode()
                                + " main="
                                + request.isForMainFrame());
        } catch (Throwable ignored) {
        }
    }

    public static void sslError(WebView view, SslErrorHandler handler, SslError error) {
        try {
            if (tracks(view)) event("login.tls.error code=" + error.getPrimaryError());
        } catch (Throwable ignored) {
        }
    }

    public static void rendererGone(WebView view, RenderProcessGoneDetail detail) {
        try {
            if (tracks(view)) event("login.renderer.gone crashed=" + detail.didCrash());
        } catch (Throwable ignored) {
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        private void stage(Activity activity, String stage) {
            if (!active()) return;
            String type = activity.getClass().getName();
            String kind =
                    type.startsWith("app.bitchord.")
                            ? "patch"
                            : type.startsWith("com.music.bitchord.") ? "host" : "other";
            event("activity." + stage + " kind=" + kind);
        }

        public void onActivityCreated(Activity a, Bundle b) {
            stage(a, "created");
        }

        public void onActivityStarted(Activity a) {
            stage(a, "started");
        }

        public void onActivityResumed(Activity a) {
            stage(a, "resumed");
        }

        public void onActivityPaused(Activity a) {
            stage(a, "paused");
        }

        public void onActivityStopped(Activity a) {
            stage(a, "stopped");
        }

        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

        public void onActivityDestroyed(Activity a) {
            stage(a, "destroyed");
        }
    }
}
