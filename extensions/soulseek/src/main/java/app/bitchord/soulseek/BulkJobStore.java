package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fsynced per-item journals. Restart always requires an explicit resume. */
public final class BulkJobStore {
    public final File home;

    public enum State {
        QUEUED,
        RUNNING,
        PAUSED,
        FAILED,
        CANCELLED,
        VERIFIED,
        PUBLISHING,
        COMPLETE
    }

    public static final class Job {
        public final String id;
        public final BulkOffer offer;
        public final String destination;
        public final String destinationMode;
        public final long telegramChatId, telegramUserId;
        public final boolean localOnly;
        public final String formatPolicy;
        public volatile State state;
        public volatile int verifiedChunks;
        public volatile String route = "", message = "", publishedUri = "";
        public volatile String phoneStatus = "pending",
                telegramStatus = "pending",
                telegramReceipt = "";

        Job(String id, BulkOffer offer, String destination) {
            this(id, offer, destination, "phone", 0, 0);
        }

        Job(String id, BulkOffer offer, String destination, String mode, long chat, long user) {
            this(id, offer, destination, mode, chat, user, false);
        }

        Job(
                String id,
                BulkOffer offer,
                String destination,
                String mode,
                long chat,
                long user,
                boolean localOnly) {
            this(id, offer, destination, mode, chat, user, localOnly, "");
        }

        Job(
                String id,
                BulkOffer offer,
                String destination,
                String mode,
                long chat,
                long user,
                boolean localOnly,
                String policy) {
            this.id = id;
            this.offer = offer;
            this.destination = destination;
            state = State.QUEUED;
            destinationMode = mode;
            telegramChatId = chat;
            telegramUserId = user;
            this.localOnly = localOnly;
            this.formatPolicy = policy;
            if (!needsPhone()) phoneStatus = "skipped";
            if (!needsTelegram()) telegramStatus = "skipped";
        }

        public long verifiedBytes() {
            return Math.min(offer.size, (long) verifiedChunks * BulkManifest.BLOCK_SIZE);
        }

        public boolean needsPhone() {
            return destinationMode.equals("phone") || destinationMode.equals("both");
        }

        public boolean needsTelegram() {
            return destinationMode.equals("telegram") || destinationMode.equals("both");
        }
    }

    public BulkJobStore(File home) throws IOException {
        this.home = home;
        if (!home.isDirectory() && !home.mkdirs())
            throw new IOException("Cannot create download storage.");
    }

    public synchronized Job create(BulkOffer offer, String destination) throws IOException {
        return create(offer, destination, "phone", 0, 0);
    }

    public synchronized Job create(
            BulkOffer offer, String destination, String mode, long chat, long user)
            throws IOException {
        return create(offer, destination, mode, chat, user, "");
    }

    public synchronized Job create(
            BulkOffer offer, String destination, String mode, long chat, long user, String policy)
            throws IOException {
        if (!(mode.equals("phone") || mode.equals("telegram") || mode.equals("both"))
                || ((!mode.equals("phone")) && (chat == 0 || user <= 0)))
            throw new IOException("Invalid pinned download destination.");
        Job job =
                new Job(
                        UUID.randomUUID().toString().replace("-", ""),
                        offer,
                        destination == null ? "" : destination,
                        mode,
                        chat,
                        user,
                        false,
                        policy);
        save(job);
        return job;
    }

    synchronized Job createLocal(
            String id,
            BulkOffer offer,
            String destination,
            String mode,
            long chat,
            long user,
            String policy)
            throws IOException {
        if (!id.matches("[0-9a-f]{32}")) throw new IOException("Invalid local download identity.");
        if (!(mode.equals("phone") || mode.equals("telegram") || mode.equals("both"))
                || ((!mode.equals("phone")) && (chat == 0 || user <= 0)))
            throw new IOException("Invalid pinned download destination.");
        Job job = new Job(id, offer, destination, mode, chat, user, true, policy);
        job.state = State.VERIFIED;
        save(job);
        return job;
    }

    public File part(Job job) {
        return new File(home, job.id + ".part");
    }

    public File manifest(Job job) {
        return new File(home, job.id + ".manifest");
    }

    public File complete(Job job) {
        return new File(home, job.id + "-" + job.offer.name);
    }

    synchronized void remove(Job job) throws IOException {
        for (File file :
                new File[] {
                    part(job), manifest(job), complete(job), new File(home, job.id + ".json")
                })
            if (file.exists() && !file.delete())
                throw new IOException("Cannot remove private download data.");
        try (FileChannel directory = FileChannel.open(home.toPath(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    public synchronized void save(Job job) throws IOException {
        try {
            JSONObject value =
                    new JSONObject()
                            .put("version", 1)
                            .put("id", job.id)
                            .put("peer", job.offer.peer)
                            .put("offer", job.offer.json)
                            .put("destination", job.destination)
                            .put("state", job.state.name())
                            .put("destinationMode", job.destinationMode)
                            .put("telegramChatId", job.telegramChatId)
                            .put("telegramUserId", job.telegramUserId)
                            .put("phoneStatus", job.phoneStatus)
                            .put("localOnly", job.localOnly)
                            .put("formatPolicy", job.formatPolicy)
                            .put("telegramStatus", job.telegramStatus)
                            .put("telegramReceipt", job.telegramReceipt)
                            .put("verifiedChunks", job.verifiedChunks)
                            .put("route", job.route)
                            .put("message", job.message)
                            .put("publishedUri", job.publishedUri);
            atomic(
                    new File(home, job.id + ".json"),
                    value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Cannot save download progress.", error);
        }
    }

    public synchronized List<Job> load() throws IOException {
        List<Job> jobs = new ArrayList<>();
        File[] entries = home.listFiles();
        if (entries == null) throw new IOException("Cannot read download storage.");
        for (File file : entries) {
            if (!file.getName().matches("[0-9a-f]{32}\\.json")) continue;
            try {
                JSONObject value =
                        new JSONObject(new String(read(file, 32768), StandardCharsets.UTF_8));
                String id = value.getString("id");
                if (!file.getName().equals(id + ".json") || value.getInt("version") != 1)
                    throw new IOException("Invalid download journal.");
                Job job =
                        new Job(
                                id,
                                BulkOffer.verified(
                                        value.getString("peer"), value.getString("offer")),
                                value.optString("destination", ""),
                                value.optString("destinationMode", "phone"),
                                value.optLong("telegramChatId", 0),
                                value.optLong("telegramUserId", 0),
                                value.optBoolean("localOnly", false),
                                value.optString("formatPolicy", ""));
                if (!(job.destinationMode.equals("phone")
                                || job.destinationMode.equals("telegram")
                                || job.destinationMode.equals("both"))
                        || (job.needsTelegram()
                                && (job.telegramChatId == 0 || job.telegramUserId <= 0)))
                    throw new IOException("Invalid stored destination.");
                job.phoneStatus = value.optString("phoneStatus", job.phoneStatus);
                job.telegramStatus = value.optString("telegramStatus", job.telegramStatus);
                job.telegramReceipt = value.optString("telegramReceipt", "");
                job.state = State.valueOf(value.getString("state"));
                job.verifiedChunks = value.getInt("verifiedChunks");
                if (job.verifiedChunks < 0 || job.verifiedChunks > 1024)
                    throw new IOException("Invalid checkpoint.");
                job.route = value.optString("route", "");
                job.message = value.optString("message", "");
                job.publishedUri = value.optString("publishedUri", "");
                if (job.state == State.RUNNING || job.state == State.QUEUED) {
                    job.state = State.PAUSED;
                    job.message = "Resume this download after restarting.";
                    save(job);
                }
                // A SAF publication interrupted after createDocument needs user inspection, never a
                // duplicate.
                if (job.state == State.PUBLISHING) {
                    job.state = State.PAUSED;
                    job.message = "Check the destination before resuming publication.";
                    save(job);
                }
                jobs.add(job);
            } catch (Exception error) {
                throw new IOException(
                        "A download journal is damaged; recovery is required: " + file.getName(),
                        error);
            }
        }
        return jobs;
    }

    static byte[] read(File file, int max) throws IOException {
        long length = file.length();
        if (length <= 0 || length > max) throw new IOException("Invalid stored data size.");
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            new java.io.DataInputStream(input).readFully(bytes);
            if (input.read() != -1) throw new IOException("Stored data changed while reading.");
        }
        return bytes;
    }

    static void atomic(File target, byte[] bytes) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(bytes);
            out.getFD().sync();
        }
        Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel directory =
                FileChannel.open(target.getParentFile().toPath(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (UnsupportedOperationException unsupported) {
            /* File contents are already fsynced. */
        }
    }
}
