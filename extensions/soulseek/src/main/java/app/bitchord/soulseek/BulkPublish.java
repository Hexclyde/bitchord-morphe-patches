package app.bitchord.soulseek;

import java.io.IOException;

/** Each requested destination gets an independent attempt and durable completion receipt. */
final class BulkPublish {
    interface Action {
        void run() throws IOException;
    }

    private BulkPublish() {}

    static void run(
            BulkJobStore.Job job,
            BulkTransfer.Cancellation cancellation,
            Action phone,
            Action telegram,
            Action checkpoint)
            throws IOException {
        IOException failure = null;
        if (job.needsPhone() && !"complete".equals(job.phoneStatus)) {
            cancellation.check();
            try {
                phone.run();
                job.phoneStatus = "complete";
            } catch (IOException | RuntimeException | LinkageError error) {
                cancellation.check();
                if (!"creating".equals(job.phoneStatus) && !"writing".equals(job.phoneStatus))
                    job.phoneStatus = "failed";
                failure = new IOException("Phone copy failed: " + message(error), error);
            }
            checkpoint.run();
        }
        if (job.needsTelegram() && !"complete".equals(job.telegramStatus)) {
            cancellation.check();
            try {
                telegram.run();
                job.telegramStatus = "complete";
            } catch (IOException | RuntimeException | LinkageError error) {
                cancellation.check();
                if (!"receipt-check-required".equals(job.telegramStatus))
                    job.telegramStatus = "failed";
                IOException telegramFailure =
                        new IOException("Telegram copy failed: " + message(error), error);
                if (failure == null) failure = telegramFailure;
                else {
                    failure.addSuppressed(telegramFailure);
                    failure =
                            new IOException(
                                    failure.getMessage() + "; " + telegramFailure.getMessage(),
                                    failure);
                }
            }
            checkpoint.run();
        }
        cancellation.check();
        if (failure != null) throw failure;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? "Resume to retry." : error.getMessage();
    }
}
