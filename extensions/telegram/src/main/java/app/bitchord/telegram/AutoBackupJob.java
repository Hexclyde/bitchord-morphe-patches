package app.bitchord.telegram;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android's scheduled run of automatic backups. It is persisted, so it survives a reboot and the
 * process being stopped, and needs only a network connection; waits for a pause Telegram asked for,
 * playback and searches are kept by {@link AutoBackup} itself. A run schedules the next one from
 * what is saved, so intervals that passed while the phone was off make a single backup.
 */
public final class AutoBackupJob extends JobService {
    static final int JOB_ID = 0x5D0A12;
    static final long MIN_DELAY_MS = 15_000L;

    private volatile Thread worker;
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Override
    public boolean onStartJob(JobParameters params) {
        if (worker != null && worker.isAlive()) return false;
        stopped.set(false);
        final Context context = getApplicationContext();
        worker =
                new Thread(
                        () -> {
                            long next = AutoBackup.BUSY_MILLIS;
                            try {
                                TelegramBridge bridge = TelegramBridge.instance;
                                // The process was just started for this job: give Telegram a
                                // moment.
                                for (int i = 0; i < 20 && !stopped.get(); i++) {
                                    bridge = TelegramBridge.instance;
                                    if (bridge != null
                                            && bridge.autoBackup() != null
                                            && bridge.ready()) break;
                                    Thread.sleep(1000);
                                }
                                if (bridge != null
                                        && bridge.autoBackup() != null
                                        && bridge.ready()
                                        && !stopped.get())
                                    next = bridge.runAutoBackup(stopped, false).delayMillis;
                                else if (bridge != null && bridge.autoBackup() != null)
                                    next = Math.max(next, bridge.autoBackup().delayMillis());
                            } catch (InterruptedException interrupted) {
                                // Android stopped the job; the next run is planned below.
                            } catch (Exception | LinkageError e) {
                                HostLog.d(
                                        "Automatic backup run failed: "
                                                + e.getClass().getSimpleName());
                            } finally {
                                reschedule(context, next);
                                if (!stopped.get()) jobFinished(params, false);
                            }
                        },
                        "bitchord-auto-backup");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped.set(true);
        Thread current = worker;
        if (current != null) current.interrupt();
        // The run was cut short; the saved state decides when to try again.
        return false;
    }

    /**
     * Plans the next run from the saved state. Called at start-up without {@code replace} so a run
     * that was already planned keeps its time, and after a setting changes with it.
     */
    static void schedule(Context context, AutoBackup backup, boolean replace) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        try {
            if (!replace)
                for (JobInfo pending : jobs.getAllPendingJobs())
                    if (pending.getId() == JOB_ID && backup.delayMillis() >= 0) return;
        } catch (RuntimeException unavailable) {
            // Plan it again below.
        }
        reschedule(context, backup.delayMillis());
    }

    /** Replaces the planned run; a negative delay means no backup is wanted. */
    static void reschedule(Context context, long delayMillis) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        try {
            if (delayMillis < 0) {
                jobs.cancel(JOB_ID);
                return;
            }
            ComponentName service = new ComponentName(context, AutoBackupJob.class);
            long delay = Math.max(MIN_DELAY_MS, delayMillis);
            JobInfo.Builder builder =
                    new JobInfo.Builder(JOB_ID, service)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setMinimumLatency(delay);
            try {
                jobs.schedule(builder.setPersisted(true).build());
            } catch (RuntimeException notPermitted) {
                // Without the boot permission it still runs, but not after a restart.
                jobs.schedule(builder.setPersisted(false).build());
            }
        } catch (RuntimeException e) {
            HostLog.d("Automatic backup couldn't be scheduled: " + e.getClass().getSimpleName());
        }
    }
}
