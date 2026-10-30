package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes the real pause controls with a gated worker; Android and native APIs stay unused. */
public final class BulkRunTest {
    static int checks;

    static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }

    static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static Object run() throws Exception {
        Constructor<?> constructor =
                Class.forName("app.bitchord.soulseek.BulkDownloads$Run").getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    static Object invoke(Object target, String name, Class<?>[] types, Object... values)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }

    static BulkDownloads controller(BulkJobStore store, BulkJobStore.Job job, Object run)
            throws Exception {
        // Construct only the controls under test, without an Android Context or starting any
        // runtime.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field access = unsafeClass.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        Object unsafe = access.get(null);
        BulkDownloads downloads =
                (BulkDownloads)
                        unsafeClass
                                .getMethod("allocateInstance", Class.class)
                                .invoke(unsafe, BulkDownloads.class);
        field(downloads, "lock", new Object());
        field(downloads, "store", store);
        Map<String, BulkJobStore.Job> jobs = new LinkedHashMap<>();
        jobs.put(job.id, job);
        Map<String, Object> runs = new LinkedHashMap<>();
        runs.put(job.id, run);
        field(downloads, "jobs", jobs);
        field(downloads, "runs", runs);
        return downloads;
    }

    public static void main(String[] arguments) throws Exception {
        BulkDownloadsTest.setup();
        queued();
        active();
        importRecovery();
        System.out.println("Bulk pause ownership: " + checks + " assertions passed");
    }

    public static final class PrivateContext extends android.content.ContextWrapper {
        File home;

        private PrivateContext() {
            super(null);
        }

        @Override
        public File getNoBackupFilesDir() {
            return home;
        }
    }

    static final class QueuedExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        boolean stopped;

        public void execute(Runnable task) {
            tasks.add(task);
        }

        public void shutdown() {
            stopped = true;
        }

        public List<Runnable> shutdownNow() {
            stopped = true;
            return tasks;
        }

        public boolean isShutdown() {
            return stopped;
        }

        public boolean isTerminated() {
            return stopped;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return stopped;
        }
    }

    static void importRecovery() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field access = unsafeClass.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        PrivateContext context =
                (PrivateContext)
                        unsafeClass
                                .getMethod("allocateInstance", Class.class)
                                .invoke(access.get(null), PrivateContext.class);
        context.home = Files.createTempDirectory("bulk-import-").toFile();
        byte[] audio = new byte[64];
        audio[0] = 'f';
        audio[1] = 'L';
        audio[2] = 'a';
        audio[3] = 'C';
        audio[4] = (byte) 128;
        audio[7] = 34;
        audio[8] = 4;
        audio[10] = 4;
        audio[18] = 10;
        audio[19] = (byte) 196;
        audio[20] = 66;
        audio[21] = (byte) 240;
        File incoming = new File(context.home, "retry.flac");
        Files.write(incoming.toPath(), audio);
        Method createManifest = BulkDownloads.class.getDeclaredMethod("localManifest", File.class);
        createManifest.setAccessible(true);
        BulkManifest manifest = (BulkManifest) createManifest.invoke(null, incoming);
        String token = "durable-source-token-for-import",
                id = BulkManifest.hash(token.getBytes("UTF-8")).substring(0, 32);
        JSONObject json =
                new JSONObject(BulkDownloadsTest.offer.json)
                        .put("fileId", id)
                        .put("size", audio.length)
                        .put("sha256", manifest.fileSha256)
                        .put("manifestSha256", manifest.sha256)
                        .put("manifestCid", manifest.cid);
        String policy = AudioFormatPreferences.encode(AudioFormatPreferences.defaults());
        BulkJobStore store = new BulkJobStore(new File(context.home, "jobs"));
        DownloadDestinations.Snapshot destination =
                new DownloadDestinations.Snapshot("phone", "content://chosen/tree", 0, 0, "");
        BulkJobStore.Job job =
                store.createLocal(
                        id,
                        BulkOffer.verified(BulkDownloadsTest.PEER, json.toString()),
                        destination.phoneTreeUri,
                        "phone",
                        0,
                        0,
                        policy);
        BulkJobStore.atomic(store.manifest(job), manifest.bytes);
        Files.write(store.complete(job).toPath(), audio);
        BulkDownloads downloads = controller(store, job, run());
        QueuedExecutor executor = new QueuedExecutor();
        field(downloads, "context", context);
        field(downloads, "writer", executor);
        field(downloads, "runs", new LinkedHashMap<String, Object>());
        check(
                downloads
                        .importVerified(incoming, "selected.flac", destination, token, policy)
                        .equals(id),
                "import retry keeps durable job identity");
        check(
                executor.tasks.size() == 1 && job.state == BulkJobStore.State.VERIFIED,
                "unfinished durable import with existing artifact is resubmitted");
        check(
                !incoming.exists()
                        && java.util.Arrays.equals(
                                audio, Files.readAllBytes(store.complete(job).toPath())),
                "retry keeps original verified bytes and removes duplicate source");
        Files.write(incoming.toPath(), audio);
        downloads.importVerified(incoming, "selected.flac", destination, token, policy);
        check(executor.tasks.size() == 1, "repeated retry never submits a second writer");
        downloads.pause(id);
        job.state = BulkJobStore.State.COMPLETE;
        store.save(job);
        Files.write(incoming.toPath(), audio);
        downloads.importVerified(incoming, "selected.flac", destination, token, policy);
        check(
                executor.tasks.size() == 1 && !incoming.exists(),
                "completed import never republishes a destination");
    }

    static void queued() throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        Future<?> first =
                writer.submit(
                        () -> {
                            firstStarted.countDown();
                            try {
                                releaseFirst.await();
                            } catch (InterruptedException ignored) {
                            }
                        });
        check(firstStarted.await(2, TimeUnit.SECONDS), "unrelated writer started");
        Object queued = run();
        AtomicBoolean wrote = new AtomicBoolean();
        Future<?> future =
                writer.submit(
                        () -> {
                            try {
                                if ((Boolean) invoke(queued, "claim", new Class<?>[0]))
                                    wrote.set(true);
                            } catch (Exception error) {
                                throw new RuntimeException(error);
                            }
                        });
        field(queued, "future", future);
        BulkJobStore store = new BulkJobStore(Files.createTempDirectory("bulk-queued-").toFile());
        BulkJobStore.Job job = store.create(BulkDownloadsTest.offer, "");
        BulkDownloads downloads = controller(store, job, queued);
        long before = System.nanoTime();
        downloads.pause(job.id);
        check(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1000,
                "queued pause does not wait behind unrelated upload");
        check(
                future.isCancelled() && !first.isDone(),
                "queued task cancelled while first writer remains active");
        check(job.state == BulkJobStore.State.PAUSED, "queued pause persisted");
        releaseFirst.countDown();
        first.get(2, TimeUnit.SECONDS);
        writer.shutdown();
        check(
                writer.awaitTermination(2, TimeUnit.SECONDS) && !wrote.get(),
                "cancel-before-claim cannot write later");
    }

    static void active() throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean(), writerExited = new AtomicBoolean();
        Object active = run();
        Future<?> future =
                writer.submit(
                        () -> {
                            try {
                                check(
                                        (Boolean) invoke(active, "claim", new Class<?>[0]),
                                        "writer claims ownership");
                                invoke(
                                        active,
                                        "bind",
                                        new Class<?>[] {Closeable.class},
                                        (Closeable) () -> closed.set(true));
                                started.countDown();
                                new CountDownLatch(1).await();
                            } catch (InterruptedException expected) {
                            } catch (Exception error) {
                                throw new RuntimeException(error);
                            } finally {
                                writerExited.set(true);
                            }
                        });
        field(active, "future", future);
        check(started.await(2, TimeUnit.SECONDS), "active writer ready");
        BulkJobStore store = new BulkJobStore(Files.createTempDirectory("bulk-active-").toFile());
        BulkJobStore.Job job = store.create(BulkDownloadsTest.offer, "");
        controller(store, job, active).pause(job.id);
        check(closed.get(), "active connection closed on pause");
        check(writerExited.get() && future.isDone(), "pause returns after active writer barrier");
        Object cancelled = run();
        invoke(cancelled, "stop", new Class<?>[0]);
        check(
                !(Boolean) invoke(cancelled, "claim", new Class<?>[0]),
                "cancelled run cannot acquire ownership");
        AtomicBoolean lateClosed = new AtomicBoolean();
        try {
            invoke(
                    cancelled,
                    "bind",
                    new Class<?>[] {Closeable.class},
                    (Closeable) () -> lateClosed.set(true));
        } catch (java.lang.reflect.InvocationTargetException expected) {
        }
        check(lateClosed.get(), "connection opened after cancellation closes immediately");
        writer.shutdown();
        check(writer.awaitTermination(2, TimeUnit.SECONDS), "worker stopped cleanly");
    }
}
