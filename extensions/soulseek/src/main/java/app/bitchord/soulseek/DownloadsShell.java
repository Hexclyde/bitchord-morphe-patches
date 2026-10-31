package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.Objects;

/** Hosts native download pages in the player's Compose content slot, below its real bars. */
public final class DownloadsShell {
    private static Object state;
    private static Method get, set, androidView, backHandler;
    private static final Function0<Unit> BACK =
            () -> {
                back();
                return Unit.INSTANCE;
            };
    private static Intent route;
    private static WeakReference<Container> container = new WeakReference<>(null);
    private static long revision;
    private static boolean available;
    private static final Function1<Context, View> FACTORY =
            context -> {
                Context candidate = context;
                while (!(candidate instanceof Activity) && candidate instanceof ContextWrapper)
                    candidate = ((ContextWrapper) candidate).getBaseContext();
                if (!(candidate instanceof Activity))
                    throw new IllegalStateException("Player Activity unavailable");
                Container root = new Container((Activity) candidate);
                container = new WeakReference<>(root);
                return root;
            };

    private DownloadsShell() {}

    private static synchronized void init() throws ReflectiveOperationException {
        if (state != null) return;
        Class<?> policy = Class.forName("androidx.compose.runtime.SnapshotMutationPolicy");
        Object equality =
                Proxy.newProxyInstance(
                        policy.getClassLoader(),
                        new Class<?>[] {policy},
                        (proxy, method, args) -> {
                            if (method.getName().equals("equivalent"))
                                return Objects.equals(args[0], args[1]);
                            if (method.getName().equals("merge")) return null;
                            if (method.getName().equals("hashCode"))
                                return System.identityHashCode(proxy);
                            if (method.getName().equals("equals")) return proxy == args[0];
                            return "Download navigation policy";
                        });
        Class<?> snapshot =
                Class.forName("androidx.compose.runtime.ParcelableSnapshotMutableState");
        Object candidate = snapshot.getConstructor(Object.class, policy).newInstance(0L, equality);
        get = snapshot.getMethod("getValue");
        set = snapshot.getMethod("setValue", Object.class);
        for (Method method :
                Class.forName("androidx.compose.ui.viewinterop.AndroidView_androidKt").getMethods())
            if (method.getName().equals("AndroidView") && method.getParameterCount() == 6)
                androidView = method;
        if (androidView == null) throw new NoSuchMethodException("Player AndroidView ABI");
        backHandler =
                Class.forName("coil.util.DrawableUtils")
                        .getMethod(
                                "BackHandler",
                                boolean.class,
                                Function0.class,
                                Class.forName("androidx.compose.runtime.ComposerImpl"),
                                int.class,
                                int.class);
        state = candidate;
        available = true;
    }

    public static boolean visible() {
        try {
            init();
            get.invoke(state);
            return route != null;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    private static void changed() {
        try {
            init();
            set.invoke(state, ++revision);
        } catch (ReflectiveOperationException problem) {
            throw new IllegalStateException(problem);
        }
    }

    static boolean open(Intent target) {
        try {
            init();
        } catch (ReflectiveOperationException | LinkageError missing) {
            return false;
        }
        if (!available || !DownloadNavigation.hasPlayer()) return false;
        route = new Intent(target);
        changed();
        DownloadNavigation.selectLibrary();
        return true;
    }

    static boolean owns(Activity activity) {
        Container root = container.get();
        return root != null && root.activity == activity && route != null;
    }

    static boolean navigate(Activity activity, Intent intent) {
        if (!owns(activity) || intent.getComponent() == null) return false;
        String name = intent.getComponent().getClassName();
        if (!name.equals(DownloadsHubActivity.class.getName())
                && !name.equals(DownloadExplorerActivity.class.getName())
                && !name.equals(BatchDownloadActivity.class.getName())) return false;
        return open(intent);
    }

    static void close() {
        if (route == null) return;
        route = null;
        changed();
    }

    public static boolean handleBack() {
        if (!visible()) return false;
        back();
        return true;
    }

    static void back() {
        if (route != null
                && route.getComponent() != null
                && !route.getComponent()
                        .getClassName()
                        .equals(DownloadsHubActivity.class.getName())) {
            String destination =
                    route.getIntExtra("download.batch.index", -1) >= 0
                                    && SoulseekDownloadChoice.isBatch(
                                            route.getStringExtra(
                                                    SoulseekDownloadChoice.EXTRA_CHOICE))
                            ? BatchDownloadActivity.class.getName()
                            : DownloadsHubActivity.class.getName();
            Intent target =
                    new Intent(route)
                            .setClassName(route.getComponent().getPackageName(), destination);
            target.removeExtra("download.batch.index");
            open(target);
        } else {
            close();
            DownloadNavigation.returnToPlayer();
        }
    }

    /**
     * Called at LibraryScreen's content slot. Padding is the host's measured bar/player padding.
     */
    public static boolean compose(Object modifier, Object padding, Object composer) {
        if (!visible()) return false;
        final Intent target = route;
        final long generation = revision;
        Function1<View, Unit> update =
                view -> {
                    ((Container) view).show(target, generation, padding);
                    return Unit.INSTANCE;
                };
        try {
            // R8 removes LibraryScreen's default-modifier assignment and its callers
            // pass null. Ask AndroidView to supply its own default in that case.
            androidView.invoke(
                    null, FACTORY, modifier, update, composer, 0, modifier == null ? 2 : 0);
            backHandler.invoke(null, true, BACK, composer, 0, 0);
            return true;
        } catch (ReflectiveOperationException problem) {
            Throwable cause =
                    problem instanceof InvocationTargetException ? problem.getCause() : problem;
            throw new IllegalStateException("Cannot embed download page", cause);
        }
    }

    /** Used only for the player's page heading; the Library tab keeps its own label. */
    public static String heading(String original) {
        return visible() ? app.bitchord.telegram.PatchStrings.t("Downloads") : original;
    }

    private static final class Container extends FrameLayout {
        final Activity activity;
        DownloadPage page;
        long version = -1;
        boolean started, stopped;
        private final android.app.Application.ActivityLifecycleCallbacks lifecycle =
                new android.app.Application.ActivityLifecycleCallbacks() {
                    public void onActivityCreated(Activity value, android.os.Bundle state) {}

                    public void onActivityResumed(Activity value) {}

                    public void onActivityPaused(Activity value) {}

                    public void onActivitySaveInstanceState(
                            Activity value, android.os.Bundle state) {}

                    public void onActivityStarted(Activity value) {
                        if (value == activity) {
                            stopped = false;
                            if (page != null && !started) {
                                page.onStart();
                                started = true;
                            }
                        }
                    }

                    public void onActivityStopped(Activity value) {
                        if (value == activity) {
                            stopped = true;
                            if (page != null && started) {
                                page.onStop();
                                started = false;
                            }
                        }
                    }

                    public void onActivityDestroyed(Activity value) {
                        if (value == activity) dispose();
                    }
                };

        Container(Activity activity) {
            super(activity);
            this.activity = activity;
            addOnAttachStateChangeListener(
                    new OnAttachStateChangeListener() {
                        public void onViewAttachedToWindow(View view) {
                            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
                            if (page != null && !started && !stopped) {
                                page.onStart();
                                started = true;
                            }
                        }

                        public void onViewDetachedFromWindow(View view) {
                            activity.getApplication()
                                    .unregisterActivityLifecycleCallbacks(lifecycle);
                            dispose();
                        }
                    });
        }

        void dispose() {
            if (page == null) return;
            if (started) page.onStop();
            page.onDestroy();
            page = null;
            started = false;
            version = -1;
            removeAllViews();
        }

        void show(Intent target, long generation, Object padding) {
            float density = getResources().getDisplayMetrics().density;
            int top = 0, bottom = 0;
            try {
                for (Method method : padding.getClass().getMethods()) {
                    if (method.getParameterCount() != 0) continue;
                    if (method.getName().startsWith("calculateTopPadding"))
                        top = Math.round(((Number) method.invoke(padding)).floatValue() * density);
                    if (method.getName().startsWith("calculateBottomPadding"))
                        bottom =
                                Math.round(
                                        ((Number) method.invoke(padding)).floatValue() * density);
                }
            } catch (ReflectiveOperationException problem) {
                throw new IllegalStateException(problem);
            }
            setPadding(0, top, 0, bottom);
            if (version == generation && page != null) return;
            dispose();
            String name = target.getComponent().getClassName();
            page =
                    name.equals(DownloadExplorerActivity.class.getName())
                            ? new DownloadExplorerPage(activity, target)
                            : name.equals(BatchDownloadActivity.class.getName())
                                    ? new BatchDownloadPage(activity, target)
                                    : new DownloadsHubPage(activity, target);
            page.onCreate(null);
            addView(page.view, new FrameLayout.LayoutParams(-1, -1));
            version = generation;
            if (isAttachedToWindow() && !stopped) {
                page.onStart();
                started = true;
            }
        }
    }
}
