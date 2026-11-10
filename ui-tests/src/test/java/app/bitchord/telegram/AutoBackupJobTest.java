package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The scheduled run of automatic backups as Android's job scheduler sees it. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class AutoBackupJobTest {
    private JobScheduler jobs() {
        return (JobScheduler)
                RuntimeEnvironment.getApplication().getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @Test
    public void plannedRunIsPersistedAndNeedsOnlyAConnection() {
        Context context = RuntimeEnvironment.getApplication();
        AutoBackupJob.reschedule(context, 3 * 3600_000L);
        JobInfo job = jobs().getPendingJob(AutoBackupJob.JOB_ID);
        assertNotNull(job);
        assertTrue("Survives a reboot", job.isPersisted());
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        assertEquals(3 * 3600_000L, job.getMinLatencyMillis());
        assertEquals("One planned run at a time", 1, jobs().getAllPendingJobs().size());
    }

    @Test
    public void aNewPlanReplacesTheOldAndNoPlanCancels() {
        Context context = RuntimeEnvironment.getApplication();
        AutoBackupJob.reschedule(context, 6 * 3600_000L);
        AutoBackupJob.reschedule(context, 60_000L);
        assertEquals(60_000L, jobs().getPendingJob(AutoBackupJob.JOB_ID).getMinLatencyMillis());
        assertEquals(1, jobs().getAllPendingJobs().size());
        AutoBackupJob.reschedule(context, -1);
        assertNull(
                "A negative delay means no backup is wanted",
                jobs().getPendingJob(AutoBackupJob.JOB_ID));
    }

    @Test
    public void aRunIsNeverPlannedTighterThanTheFloor() {
        AutoBackupJob.reschedule(RuntimeEnvironment.getApplication(), 0);
        assertEquals(
                AutoBackupJob.MIN_DELAY_MS,
                jobs().getPendingJob(AutoBackupJob.JOB_ID).getMinLatencyMillis());
    }
}
