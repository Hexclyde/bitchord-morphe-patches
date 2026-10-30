package app.bitchord.soulseek;

import android.content.Context;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** Explicit provider resolution and complete-file capture before normal verified publication. */
public final class DebridDownloads implements DebridCredentials.Listener {
    private static volatile DebridDownloads instance;
    private final Context context;
    private final DebridCredentials credentials;
    private final DebridHttp http;
    private final Map<DebridProvider.Kind, DebridProvider> providers;
    private final Map<String, Prepared> preparations = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final Set<Run> controls = new HashSet<>();
    private int preparing;
    private final Object lock = new Object();
    private volatile ThreadPoolExecutor writer = newWriter();
    private final File home;
    private volatile boolean stopped, stopping, shutdownFailed;
    private final Object stopLock = new Object();
    private volatile String status = "Debrid downloads are idle.";

    public static DebridDownloads get(Context context) throws IOException {
        DownloadSettings.requireEnabled(context, DownloadSettings.DEBRID);
        DebridDownloads value = instance;
        if (value != null && value.stopping)
            throw new IOException("Debrid downloads are still stopping.");
        synchronized (DebridDownloads.class) {
            value = instance;
            if (value != null && value.shutdownFailed)
                throw new IOException("Restart the app before starting debrid downloads again.");
            if (value == null)
                instance =
                        value =
                                new DebridDownloads(
                                        context,
                                        DebridCredentials.get(context),
                                        new DebridHttp(),
                                        defaultProviders());
            else value.reopen();
            return value;
        }
    }

    public static DebridDownloads existing() {
        return instance;
    }

    private static ThreadPoolExecutor newWriter() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(32),
                r -> new Thread(r, "bitchord-debrid-download"));
    }

    private void reopen() throws IOException {
        synchronized (lock) {
            if (stopping) throw new IOException("Debrid downloads are still stopping.");
            if (shutdownFailed)
                throw new IOException("Restart the app before starting debrid downloads again.");
            if (stopped) {
                if (!writer.isTerminated())
                    throw new IOException("Debrid downloads are still stopping.");
                writer = newWriter();
                stopped = false;
                credentials.addListener(this);
                status = "Debrid downloads are idle.";
            }
        }
    }

    private static Map<DebridProvider.Kind, DebridProvider> defaultProviders() {
        Map<DebridProvider.Kind, DebridProvider> result = new EnumMap<>(DebridProvider.Kind.class);
        for (DebridProvider provider :
                new DebridProvider[] {
                    new RealDebridProvider(),
                    new AllDebridProvider(),
                    new PremiumizeProvider(),
                    new TorboxProvider()
                }) result.put(provider.kind(), provider);
        return result;
    }

    DebridDownloads(
            Context context,
            DebridCredentials credentials,
            DebridHttp http,
            Map<DebridProvider.Kind, DebridProvider> providers)
            throws IOException {
        this.context = context.getApplicationContext();
        this.credentials = credentials;
        this.http = http;
        this.providers = providers;
        home = new File(this.context.getNoBackupFilesDir(), "debrid-capture");
        if (!home.isDirectory() && !home.mkdirs())
            throw new IOException("Cannot create private debrid download storage.");
        File[] stale = home.listFiles();
        if (stale == null) throw new IOException("Cannot read private debrid storage.");
        for (File file : stale)
            if (file.getName().matches("[0-9a-f]{32}\\.part") && !file.delete())
                throw new IOException("Cannot clear interrupted debrid capture.");
        credentials.addListener(this);
    }

    public static final class Prepared {
        public final String id;
        public final DebridProvider.Kind kind;
        public volatile DebridModels.Resolved resolved;
        public volatile List<DebridModels.Entry> entries;
        public volatile boolean ready;
        public volatile String state, message;
        private final String revision;
        private final long expires = System.currentTimeMillis() + 30L * 60 * 1000;
        private Set<String> selected;

        private Prepared(String id, DebridModels.Resolved resolved, String revision) {
            this.id = id;
            kind = resolved.kind;
            this.revision = revision;
            update(resolved);
        }

        private void update(DebridModels.Resolved value) {
            resolved = value;
            entries = value.entries;
            ready = value.ready;
            state = value.state;
            message = value.message;
        }
    }

    public static final class Job {
        public final String id, name;
        public final DebridProvider.Kind kind;
        public volatile String state = "QUEUED", message = "Queued.", bulkJobId = "";
        public volatile long bytes, total;
        private final Prepared prepared;
        private final DebridModels.Entry entry;
        private final DownloadDestinations.Snapshot destination;
        private final String formats;

        private Job(
                Prepared prepared,
                DebridModels.Entry entry,
                DownloadDestinations.Snapshot destination,
                String formats)
                throws IOException {
            id = UUID.randomUUID().toString().replace("-", "");
            kind = prepared.kind;
            name = AudioFormatPreferences.audioName(entry.name);
            this.entry = entry;
            this.prepared = prepared;
            this.destination = destination;
            this.formats = formats;
            total = entry.size;
        }
    }

    private final class Run implements DebridModels.Cancellation {
        final DebridProvider.Kind kind;
        final String revision;
        private final Set<Closeable> resources = new HashSet<>();
        volatile boolean cancelled, started, cancelRequested;
        volatile Thread thread;
        Future<?> future;
        volatile String redactionKey = "";
        final CountDownLatch done = new CountDownLatch(1);
        final boolean transfer;

        Run(DebridProvider.Kind kind, String revision, boolean transfer) {
            this.kind = kind;
            this.revision = revision;
            this.transfer = transfer;
        }

        public void check() throws IOException {
            DownloadSettings.requireEnabled(context, DownloadSettings.DEBRID);
            if (cancelled || stopped || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Debrid operation paused.");
            if (revision != null) credentials.requireCurrent(kind, revision);
            if (transfer) downloadsAllowed();
        }

        public void bind(Closeable resource) throws IOException {
            synchronized (this) {
                check();
                resources.add(resource);
            }
        }

        public void clear(Closeable resource) {
            synchronized (this) {
                resources.remove(resource);
            }
        }

        public void checkDownloadUrl(String url) throws IOException {
            if (!redactionKey.isEmpty()) DebridHttp.rejectCredentialUrl(url, redactionKey);
        }

        synchronized boolean claim() {
            if (cancelled) return false;
            started = true;
            thread = Thread.currentThread();
            return true;
        }

        void stop() {
            List<Closeable> closing;
            Thread worker;
            synchronized (this) {
                cancelled = true;
                closing = new ArrayList<>(resources);
                resources.clear();
                worker = thread;
            }
            if (worker != null) worker.interrupt();
            for (Closeable resource : closing)
                try {
                    resource.close();
                } catch (IOException | RuntimeException ignored) {
                }
        }
    }

    public void validate(DebridProvider.Kind kind, String key) throws IOException {
        DebridHttp.validateKey(key);
        Run run = control(kind, null);
        run.redactionKey = key;
        try {
            provider(kind).validate(key, http, run);
        } finally {
            controlDone(run);
        }
    }

    public Prepared prepare(DebridProvider.Kind kind, String input) throws IOException {
        DebridCredentials.Credential credential = credentials.enabledSnapshot(kind);
        Run run = control(kind, credential.revision);
        run.redactionKey = credential.key;
        boolean reserved = false;
        try {
            synchronized (lock) {
                ensureOpen();
                expire();
                prunePreparations();
                if (preparations.size() + preparing >= 16)
                    throw new IOException(
                            "Dismiss or finish existing prepared links before adding more.");
                preparing++;
                reserved = true;
            }
            if (input != null) DebridHttp.rejectCredentialText(input, credential.key);
            validateInput(input, run);
            run.check();
            DebridModels.Resolved resolved =
                    provider(kind).resolve(input, credential.key, http, run);
            run.check();
            checkResolved(kind, resolved, credential.key);
            Prepared result =
                    new Prepared(
                            UUID.randomUUID().toString().replace("-", ""),
                            resolved,
                            credential.revision);
            synchronized (lock) {
                ensureOpen();
                preparations.put(result.id, result);
            }
            status = "Provider link prepared. Select the audio files to save.";
            return result;
        } finally {
            if (reserved)
                synchronized (lock) {
                    preparing--;
                }
            controlDone(run);
        }
    }

    public Prepared refresh(String id) throws IOException {
        Prepared value;
        synchronized (lock) {
            value = requirePrepared(id);
        }
        DebridCredentials.Credential credential = credential(value);
        Run run = control(value.kind, value.revision);
        run.redactionKey = credential.key;
        try {
            DebridModels.Resolved next =
                    provider(value.kind).refresh(value.resolved, credential.key, http, run);
            run.check();
            checkResolved(value.kind, next, credential.key);
            synchronized (lock) {
                ensureOpen();
                validateSelected(value, next);
                value.update(next);
            }
            status =
                    next.ready
                            ? "Provider files are ready for download."
                            : "The provider is still preparing the requested files.";
            return value;
        } finally {
            controlDone(run);
        }
    }

    public List<Prepared> prepared() {
        synchronized (lock) {
            expire();
            return new ArrayList<>(preparations.values());
        }
    }

    public List<Job> jobs() {
        synchronized (lock) {
            return new ArrayList<>(jobs.values());
        }
    }

    public void discardPrepared(String id) throws IOException {
        synchronized (lock) {
            for (Job job : jobs.values())
                if (job.prepared.id.equals(id) && !terminal(job))
                    throw new IOException(
                            "Cancel or finish this link's local downloads before dismissing it.");
            preparations.remove(id);
        }
    }

    public void forget(String id) throws IOException {
        synchronized (lock) {
            Job job = requireJob(id);
            if (runs.containsKey(id))
                throw new IOException("Pause this download before dismissing it.");
            jobs.remove(id);
        }
    }

    private static boolean terminal(Job job) {
        return "HANDED_OFF".equals(job.state) || "CANCELLED".equals(job.state);
    }

    private void prune(int needed) {
        if (jobs.size() + needed > 64) {
            Iterator<Job> records = jobs.values().iterator();
            while (records.hasNext() && jobs.size() + needed > 64) {
                Job job = records.next();
                if (terminal(job) && !runs.containsKey(job.id)) records.remove();
            }
        }
    }

    private void prunePreparations() {
        if (preparations.size() >= 16) {
            Iterator<Prepared> values = preparations.values().iterator();
            while (values.hasNext() && preparations.size() >= 16) {
                Prepared value = values.next();
                boolean referenced = false;
                for (Job job : jobs.values())
                    if (job.prepared == value && !terminal(job)) {
                        referenced = true;
                        break;
                    }
                if (value.selected != null && !referenced) values.remove();
            }
        }
    }

    public List<String> enqueue(
            String id,
            List<String> fileIds,
            DownloadDestinations.Snapshot destination,
            String formatPolicy)
            throws IOException {
        DownloadSettings.requireEnabled(context, DownloadSettings.DEBRID);
        AudioFormatPreferences.Settings formats = AudioFormatPreferences.decode(formatPolicy);
        if (!formats.configured || fileIds == null || fileIds.isEmpty() || fileIds.size() > 32)
            throw new IOException("Choose supported audio files and format choices first.");
        Prepared value;
        synchronized (lock) {
            value = requirePrepared(id);
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>(fileIds);
        if (selected.size() != fileIds.size())
            throw new IOException("The selection contains a duplicate file.");
        List<DebridModels.Entry> entries = new ArrayList<>();
        for (String fileId : selected) {
            DebridModels.Entry entry = value.resolved.entry(fileId);
            checkAudio(entry, formats);
            entries.add(entry);
        }
        BulkTransferService.ensureRunning(context);
        DebridCredentials.Credential credential = credential(value);
        Run run = control(value.kind, value.revision);
        run.redactionKey = credential.key;
        try {
            synchronized (value) {
                if (value.selected != null && !value.selected.equals(selected))
                    throw new IOException(
                            "This provider transfer already has a fixed selection. Prepare it again"
                                    + " to choose different files.");
                if (value.selected == null) {
                    DebridModels.Resolved next =
                            provider(value.kind)
                                    .select(
                                            value.resolved,
                                            new ArrayList<>(selected),
                                            credential.key,
                                            http,
                                            run);
                    run.check();
                    checkResolved(value.kind, next, credential.key);
                    value.selected = Collections.unmodifiableSet(new LinkedHashSet<>(selected));
                    validateSelected(value, next);
                    value.update(next);
                }
            }
            List<String> ids = new ArrayList<>();
            synchronized (lock) {
                ensureOpen();
                prune(entries.size());
                if (jobs.size() + entries.size() > 64
                        || writer.getQueue().remainingCapacity() < entries.size())
                    throw new IOException("The debrid download queue is full.");
                for (DebridModels.Entry entry : entries) {
                    for (Job existing : jobs.values())
                        if (existing.prepared == value
                                && existing.entry.fileId.equals(entry.fileId)
                                && !"CANCELLED".equals(existing.state))
                            throw new IOException("This selected file is already queued.");
                }
                for (DebridModels.Entry entry : entries) {
                    Job job = new Job(value, entry, destination, formatPolicy);
                    jobs.put(job.id, job);
                    submit(job);
                    ids.add(job.id);
                }
            }
            return ids;
        } finally {
            controlDone(run);
        }
    }

    private void submit(Job job) throws IOException {
        Run run = new Run(job.kind, job.prepared.revision, true);
        runs.put(job.id, run);
        job.state = "QUEUED";
        try {
            run.future = writer.submit(() -> execute(job, run));
        } catch (RejectedExecutionException full) {
            runs.remove(job.id);
            throw new IOException("The debrid download queue is full.");
        }
    }

    private void execute(Job job, Run run) {
        if (!run.claim()) return;
        File source = new File(home, job.id + ".part");
        try {
            BulkTransferService.awaitRunning();
            synchronized (lock) {
                run.check();
                job.state = "RUNNING";
                job.message = "Checking the selected provider file.";
            }
            if (!job.bulkJobId.isEmpty()) {
                BulkDownloads.get(context).resume(job.bulkJobId);
                synchronized (lock) {
                    run.check();
                    job.state = "HANDED_OFF";
                    job.message = "Publication is tracked in the download queue.";
                }
                return;
            }
            DebridCredentials.Credential credential = credential(job.prepared);
            run.redactionKey = credential.key;
            DebridModels.Resolved next =
                    provider(job.kind).refresh(job.prepared.resolved, credential.key, http, run);
            run.check();
            checkResolved(job.kind, next, credential.key);
            validateEntry(job.entry, next.entry(job.entry.fileId));
            synchronized (lock) {
                run.check();
                job.prepared.update(next);
            }
            if (!next.ready) {
                synchronized (lock) {
                    run.check();
                    job.state = "WAITING_PROVIDER";
                    job.message =
                            "The provider is preparing this file. Refresh the link, then resume.";
                }
                return;
            }
            DebridModels.Download download =
                    provider(job.kind).download(next, job.entry.fileId, credential.key, http, run);
            run.check();
            DebridHttp.rejectCredentialUrl(download.url, credential.key);
            DebridHttp.rejectCredentialText(download.name, credential.key);
            String resolvedName = AudioFormatPreferences.audioName(download.name);
            if (!job.name.equals(resolvedName)
                    || (job.entry.size >= 0
                            && download.size >= 0
                            && job.entry.size != download.size))
                throw new IOException(
                        "The provider returned a different file identity. Prepare the link again.");
            if (download.size > DebridModels.MAX_BYTES
                    || (download.size >= 0
                            && home.getUsableSpace() < download.size + 32L * 1024 * 1024))
                throw new IOException("There is not enough space for this audio file.");
            job.total = download.size;
            job.bytes = 0;
            job.message = "Downloading the selected audio file.";
            http.fetch(
                    download,
                    source,
                    run,
                    (done, total) -> {
                        job.bytes = done;
                        job.total = total;
                    });
            run.check();
            if (job.entry.size >= 0 && source.length() != job.entry.size)
                throw new IOException("The completed file does not match the selected size.");
            AudioFormatPreferences.validateSelectedFile(
                    AudioFormatPreferences.decode(job.formats), source, job.name);
            run.check();
            job.message = "Verifying and publishing the completed original.";
            String bulk =
                    BulkDownloads.get(context)
                            .importVerified(source, job.name, job.destination, job.id, job.formats);
            source = null;
            boolean interrupted;
            synchronized (lock) {
                job.bulkJobId = bulk;
                interrupted = run.cancelled;
                if (interrupted) {
                    if (!"CANCELLED".equals(job.state)) job.state = "PAUSED";
                    job.message = "Publication paused. Check the pinned job in the download queue.";
                } else {
                    job.state = "HANDED_OFF";
                    job.message =
                            "Verified. Phone and Telegram copies are tracked in the download"
                                    + " queue.";
                }
            }
            if (interrupted) {
                if (run.cancelRequested) BulkDownloads.get(context).cancel(bulk);
                else BulkDownloads.get(context).pause(bulk);
            }
        } catch (Exception | LinkageError failure) {
            synchronized (lock) {
                if (!run.cancelled) {
                    job.state = "FAILED";
                    job.message = safeFailure(failure, run);
                }
            }
        } finally {
            if (source != null) source.delete();
            synchronized (lock) {
                if (runs.get(job.id) == run) runs.remove(job.id);
            }
            run.thread = null;
            Thread.interrupted();
            run.done.countDown();
        }
    }

    public void pause(String id) throws IOException {
        halt(id, false);
    }

    public void cancel(String id) throws IOException {
        halt(id, true);
    }

    private void halt(String id, boolean cancel) throws IOException {
        Job job;
        Run run;
        boolean queued = false;
        synchronized (lock) {
            job = requireJob(id);
            run = runs.get(id);
            if (run != null) {
                run.cancelRequested = cancel;
                synchronized (run) {
                    if (!run.started) {
                        run.cancelled = true;
                        queued = true;
                        if (run.future != null) {
                            run.future.cancel(false);
                            writer.remove((Runnable) run.future);
                        }
                        runs.remove(id, run);
                    }
                }
                if (!queued) run.stop();
            }
            job.state = cancel ? "CANCELLED" : "PAUSED";
            job.message =
                    cancel
                            ? "Local download cancelled. Provider-side transfers are unchanged."
                            : "Paused. Resume restarts incomplete file capture.";
        }
        if (run != null && !queued && run.future != null) await(run.future);
        if (!job.bulkJobId.isEmpty()) {
            BulkDownloads bulk = BulkDownloads.existing();
            if (bulk != null) {
                if (cancel) bulk.cancel(job.bulkJobId);
                else bulk.pause(job.bulkJobId);
            }
        }
    }

    public void resume(String id) throws IOException {
        DownloadSettings.requireEnabled(context, DownloadSettings.DEBRID);
        BulkTransferService.ensureRunning(context);
        synchronized (lock) {
            ensureOpen();
            Job job = requireJob(id);
            if (runs.containsKey(id)) return;
            if ("CANCELLED".equals(job.state))
                throw new IOException("Prepare this cancelled download again.");
            credential(job.prepared);
            submit(job);
        }
    }

    public void cancelProvider(DebridProvider.Kind kind) {
        onChanged(kind);
    }

    @Override
    public void onChanged(DebridProvider.Kind kind) {
        List<String> ids = new ArrayList<>();
        List<Run> requests = new ArrayList<>();
        synchronized (lock) {
            for (Job job : jobs.values())
                if (job.kind == kind && runs.containsKey(job.id)) ids.add(job.id);
            for (Run run : controls) if (run.kind == kind) requests.add(run);
            for (Prepared value : preparations.values())
                if (value.kind == kind) {
                    value.state = "CONFIGURATION_CHANGED";
                    value.message = "Provider settings changed. Prepare this link again.";
                }
        }
        for (Run run : requests) run.stop();
        for (String id : ids)
            try {
                pause(id);
            } catch (IOException failure) {
                status = "A provider operation is still stopping. Restart the app before retrying.";
            }
    }

    public void stop() throws IOException {
        synchronized (stopLock) {
            List<String> ids;
            List<Run> requests;
            synchronized (lock) {
                if (stopped) return;
                stopping = true;
                stopped = true;
                ids = new ArrayList<>(runs.keySet());
                requests = new ArrayList<>(controls);
            }
            IOException failure = null;
            for (Run run : requests) run.stop();
            for (String id : ids)
                try {
                    pause(id);
                } catch (IOException error) {
                    failure = error;
                }
            for (Run run : requests)
                if (run.thread != Thread.currentThread())
                    try {
                        if (!run.done.await(30, TimeUnit.SECONDS))
                            failure = new IOException("A provider request is still stopping.");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        failure = new IOException("Debrid shutdown was interrupted.");
                    }
            writer.shutdown();
            try {
                if (!writer.awaitTermination(30, TimeUnit.SECONDS))
                    failure = new IOException("A debrid writer is still stopping.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = new IOException("Debrid shutdown was interrupted.");
            }
            credentials.removeListener(this);
            stopping = false;
            if (failure != null) {
                shutdownFailed = true;
                throw failure;
            }
            status = "Debrid downloads stopped.";
        }
    }

    public boolean hasActiveWork() {
        synchronized (lock) {
            return !runs.isEmpty() || !controls.isEmpty();
        }
    }

    public int activeCount() {
        synchronized (lock) {
            return runs.size() + controls.size();
        }
    }

    public String status() {
        return status;
    }

    private Run control(DebridProvider.Kind kind, String revision) throws IOException {
        synchronized (lock) {
            ensureOpen();
            if (controls.size() >= 4)
                throw new IOException("Wait for the current provider operation.");
            Run run = new Run(kind, revision, false);
            run.thread = Thread.currentThread();
            controls.add(run);
            return run;
        }
    }

    private void controlDone(Run run) {
        synchronized (lock) {
            controls.remove(run);
        }
        run.thread = null;
        run.done.countDown();
    }

    private DebridCredentials.Credential credential(Prepared prepared) throws IOException {
        credentials.requireCurrent(prepared.kind, prepared.revision);
        return credentials.enabledSnapshot(prepared.kind);
    }

    private DebridProvider provider(DebridProvider.Kind kind) throws IOException {
        DebridProvider value = providers.get(kind);
        if (value == null) throw new IOException("This debrid provider is not available.");
        return value;
    }

    private void ensureOpen() throws IOException {
        DownloadSettings.requireEnabled(context, DownloadSettings.DEBRID);
        if (stopped || stopping)
            throw new IOException(
                    "Debrid downloads are stopped. Start a new operation explicitly.");
    }

    private Prepared requirePrepared(String id) throws IOException {
        ensureOpen();
        Prepared value = preparations.get(id);
        if (value == null || value.expires < System.currentTimeMillis())
            throw new IOException("Prepare the provider link again.");
        credentials.requireCurrent(value.kind, value.revision);
        return value;
    }

    private Job requireJob(String id) throws IOException {
        Job value = jobs.get(id);
        if (value == null) throw new IOException("Unknown debrid download.");
        return value;
    }

    private void expire() {
        Iterator<Prepared> values = preparations.values().iterator();
        while (values.hasNext())
            if (values.next().expires < System.currentTimeMillis()) values.remove();
    }

    private static void checkResolved(
            DebridProvider.Kind kind, DebridModels.Resolved resolved, String key)
            throws IOException {
        if (resolved == null || resolved.kind != kind)
            throw new IOException("The provider returned a different service identity.");
        DebridHttp.rejectCredentialText(resolved.state, key);
        DebridHttp.rejectCredentialText(resolved.message, key);
        for (DebridModels.Entry entry : resolved.entries)
            DebridHttp.rejectCredentialText(entry.name, key);
    }

    private static void validateSelected(Prepared value, DebridModels.Resolved next)
            throws IOException {
        if (value.selected == null) return;
        for (String id : value.selected) validateEntry(value.resolved.entry(id), next.entry(id));
    }

    private static void validateEntry(DebridModels.Entry before, DebridModels.Entry after)
            throws IOException {
        if (!before.fileId.equals(after.fileId)
                || !before.name.equals(after.name)
                || (before.size >= 0 && after.size >= 0 && before.size != after.size))
            throw new IOException("The selected provider file changed. Prepare the link again.");
    }

    private static void checkAudio(
            DebridModels.Entry entry, AudioFormatPreferences.Settings settings) throws IOException {
        if (entry.size > DebridModels.MAX_BYTES)
            throw new IOException("Audio files are limited to 256 MiB.");
        boolean allowed = false;
        for (AudioFormatPreferences.Format format :
                AudioFormatPreferences.candidateFormats(entry.name))
            allowed |= settings.enabled.contains(format);
        if (!allowed)
            throw new IOException("The selected file does not match an enabled audio format.");
    }

    public static void validateInput(String input) throws IOException {
        validateInput(input, null);
    }

    private static void validateInput(String input, DebridModels.Cancellation cancellation)
            throws IOException {
        if (input == null || input.length() > 8192 || DebridModels.hasControl(input))
            throw new IOException("Paste a public HTTPS link or magnet link.");
        if (input.startsWith("magnet:?")) {
            try {
                URI uri = new URI(input);
                String value = uri.getRawSchemeSpecificPart();
                if (value == null
                        || !value.matches(
                                "(?i).*xt=urn:(btih:[0-9a-f]{40}|btih:[a-z2-7]{32}|btmh:1220[0-9a-f]{64})(&.*)?"))
                    throw new IOException("The magnet link has no supported torrent identity.");
            } catch (java.net.URISyntaxException malformed) {
                throw new IOException("The magnet link is invalid.");
            }
        } else if (cancellation == null) DebridHttp.publicHttps(input);
        else DebridHttp.publicHttps(input, cancellation);
    }

    private static void downloadsAllowed() throws IOException {
        try {
            if (!Boolean.TRUE.equals(
                    Class.forName("com.music.bitchord.data.settings.AppSettings")
                            .getMethod("getDownloadsAllowedNow")
                            .invoke(null)))
                throw new IOException("Downloads are paused by the current download settings.");
        } catch (IOException paused) {
            throw paused;
        } catch (Exception | LinkageError unavailable) {
            throw new IOException("The current download settings could not be checked.");
        }
    }

    private static void await(Future<?> future) throws IOException {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (CancellationException queued) {
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("The debrid writer wait was interrupted.");
        } catch (Exception failure) {
            throw new IOException(
                    "The debrid writer did not stop cleanly. Restart before retrying.");
        }
    }

    private static String safeFailure(Throwable failure, Run run) {
        String message = failure.getMessage();
        boolean secret = false;
        if (message != null && !run.redactionKey.isEmpty())
            try {
                DebridHttp.rejectCredentialUrl(message, run.redactionKey);
            } catch (IOException unsafe) {
                secret = true;
            }
        if (failure instanceof IOException
                && message != null
                && message.length() <= 256
                && !secret
                && !message.contains("://")
                && !message.contains("Bearer")
                && !message.contains("token=")) return message;
        return "The provider download could not complete. Retry or prepare the link again.";
    }
}
