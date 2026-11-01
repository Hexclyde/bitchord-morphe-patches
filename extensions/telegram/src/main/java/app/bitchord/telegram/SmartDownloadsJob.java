package app.bitchord.telegram;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Android's scheduled run of Smart downloads; the work happens on its own thread. */
public final class SmartDownloadsJob extends JobService {
    private volatile Thread worker;
    private final java.util.concurrent.atomic.AtomicBoolean stopped =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public boolean onStartJob(JobParameters params) {
        if (worker != null && worker.isAlive()) return false;
        stopped.set(false);
        worker =
                new Thread(
                        () -> {
                            try {
                                SmartDownloads.init(getApplicationContext());
                                SmartDownloads.run(getApplicationContext(), stopped);
                            } catch (RuntimeException e) {
                                HostLog.d("Smart downloads run failed: " + e);
                            } finally {
                                if (!stopped.get()) jobFinished(params, false);
                            }
                        },
                        "bitchord-smart-downloads");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped.set(true);
        Thread current = worker;
        if (current != null) current.interrupt();
        return false;
    }
}
