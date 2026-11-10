package app.bitchord.telegram;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;

/** Android's measured heap policy; no allocations are made to fill the requested allowance. */
final class HeapStatus {
    private static volatile String description = "Java heap: startup measurement pending";
    private static boolean started;

    static synchronized void init(Context context) {
        if (started || context == null) return;
        started = true;
        Context application = context.getApplicationContext();
        Thread measure =
                new Thread(
                        () -> {
                            try {
                                ActivityManager manager =
                                        (ActivityManager)
                                                application.getSystemService(
                                                        Context.ACTIVITY_SERVICE);
                                boolean expanded =
                                        (application.getApplicationInfo().flags
                                                        & ApplicationInfo.FLAG_LARGE_HEAP)
                                                != 0;
                                Runtime runtime = Runtime.getRuntime();
                                long maximum = runtime.maxMemory();
                                description =
                                        (expanded ? "Expanded Java heap" : "Standard Java heap")
                                                + " · measured limit "
                                                + (maximum >> 20)
                                                + " MiB"
                                                + "\nFLAG_LARGE_HEAP="
                                                + expanded
                                                + ", memoryClassMiB="
                                                + (manager == null ? -1 : manager.getMemoryClass())
                                                + ", largeMemoryClassMiB="
                                                + (manager == null
                                                        ? -1
                                                        : manager.getLargeMemoryClass())
                                                + ", workingCeilingMiB="
                                                + (SourceAudio.workingHeapMaximum(maximum) >> 20);
                                description +=
                                        "\nStartup: "
                                                + SourceAudio.memoryDiagnostics(
                                                        maximum,
                                                        runtime.totalMemory()
                                                                - runtime.freeMemory());
                                HostLog.d(description.replace('\n', ' '));
                            } catch (RuntimeException | LinkageError unavailable) {
                                description =
                                        "Java heap · measured limit "
                                                + (Runtime.getRuntime().maxMemory() >> 20)
                                                + " MiB (Android policy details unavailable)";
                            }
                        },
                        "heap-startup-measurement");
        measure.setDaemon(true);
        measure.start();
    }

    static String description() {
        return description;
    }

    private HeapStatus() {}
}
