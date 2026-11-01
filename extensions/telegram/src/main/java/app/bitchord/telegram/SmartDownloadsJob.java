package app.bitchord.telegram;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Android's scheduled run of Smart downloads; the work happens on its own thread. */
public final class SmartDownloadsJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                SmartDownloads.run(getApplicationContext());
                            } catch (RuntimeException e) {
                                HostLog.d("Smart downloads run failed: " + e);
                            } finally {
                                jobFinished(params, false);
                            }
                        },
                        "bitchord-smart-downloads");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
