package app.bitchord.telegram;

import android.content.Context;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns a session independent of BitChord's vocal tracker. All session work is serialized. */
final class SeparationModel {
    static final int METHOD = 2;
    static final String ASSET = "vocals_umxhq_int8.onnx";
    static final String SHA256 = "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb";
    private static final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "bitchord-separation");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final AtomicBoolean reconcilePending = new AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong();
    private static boolean memoryObserver;
    private static final Map<Object, SeparatedSing> engines =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Integer> pending = new WeakHashMap<>();
    private static volatile Object session, environment;
    private static volatile boolean wanted;

    /** Whether the stream that last played can use the model at its own speed and pitch. */
    private static volatile boolean playingNormal = true;

    private static volatile String state = "Filter";
    private static final java.util.List<Runnable> watchers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile long lastInferenceMs;
    private static Method tensorFactory, run, floatBuffer;

    static final String UNAVAILABLE = "Model unavailable · using filter";
    static final String TOO_SLOW = "Too slow on this device · using filter";

    /** An estimate has to arrive before the audio it's for leaves the delay, with a margin. */
    static final long BUDGET_MS = 1300;

    static final int SLOW_RUNS = 3;
    static final long FIRST_BACKOFF_MS = 60_000, LAST_BACKOFF_MS = 30 * 60_000;
    private static int slowRuns;
    private static long backoffMs = FIRST_BACKOFF_MS;
    private static volatile boolean tooSlow;
    private static volatile long retryAtMs;

    /** Reused by every inference, and only ever touched on the worker. */
    private static float[] snapshot, output;

    private static ByteBuffer inputBytes;

    /** Streams that have had audio since they started, so anything else is at a stream's start. */
    private static final Map<Object, Boolean> started = new WeakHashMap<>();

    /** Stands in for the runtime in tests, which have neither it nor the model. */
    interface Inference {
        float[] run(float[] magnitude) throws Exception;
    }

    private static volatile Inference inference;

    static void forTest(Inference fake, boolean want) {
        inference = fake;
        session = fake == null ? null : fake;
        wanted = want;
        playingNormal = true;
        tooSlow = false;
        slowRuns = 0;
        backoffMs = FIRST_BACKOFF_MS;
        setState(fake == null ? "Filter" : "Model ready");
        reset(true);
    }

    /** Told whenever the model's status changes; the lyrics control shows it without polling. */
    static void watch(Runnable listener) {
        if (listener != null) watchers.add(listener);
    }

    /** Removes this very listener; another one that was added since stays. */
    static void unwatch(Runnable listener) {
        watchers.remove(listener);
    }

    /** A listener that fails is dropped; it never reaches the model or the audio worker. */
    /** Karaoke's own settings changed from a page or the lyrics; listeners show them again. */
    static void announce() {
        changed();
    }

    private static void changed() {
        for (Runnable listener : watchers) {
            try {
                listener.run();
            } catch (RuntimeException | LinkageError failed) {
                watchers.remove(listener);
            }
        }
    }

    private static void setState(String value) {
        state = value;
        changed();
    }

    static boolean tooSlow() {
        return tooSlow;
    }

    static final SeparatedSing.Model MODEL =
            new SeparatedSing.Model() {
                @Override
                public float[] infer(float[] magnitude) throws Exception {
                    return SeparationModel.infer(magnitude);
                }

                /** Runs on the worker, so the inference that ran out has already finished. */
                @Override
                public void failed() {
                    setState(UNAVAILABLE);
                    reset(true);
                    closeSession();
                }

                @Override
                public boolean available() {
                    return wanted && session != null && !tooSlow;
                }

                @Override
                public float[] scratch(int length) {
                    if (snapshot == null || snapshot.length != length) snapshot = new float[length];
                    return snapshot;
                }
            };

    static void configure() {
        wanted = SoundExtras.singEnabled && SoundExtras.singMethod == METHOD;
        long changed = revision.incrementAndGet();
        synchronized (MODEL) {
            tooSlow = false;
            slowRuns = 0;
            backoffMs = FIRST_BACKOFF_MS;
        }
        observeMemory();
        if (!wanted) reset(true);
        if (!reconcilePending.compareAndSet(false, true)) return;
        worker.execute(
                () -> {
                    try {
                        if (!wanted) {
                            closeSession();
                            setState("Filter");
                            return;
                        }
                        if (session == null) {
                            setState("Model loading");
                            load();
                        }
                        if (!wanted) {
                            closeSession();
                            setState("Filter");
                        }
                    } catch (Exception | LinkageError | OutOfMemoryError failure) {
                        closeSession();
                        setState(UNAVAILABLE);
                    } finally {
                        reconcilePending.set(false);
                        if (changed != revision.get()) configure();
                    }
                });
    }

    static String status() {
        return wanted && !playingNormal ? "Filter fallback at current speed or pitch" : state;
    }

    static void reset(boolean close) {
        synchronized (engines) {
            for (SeparatedSing engine : engines.values())
                if (close) engine.close();
                else engine.reset();
            if (close) {
                engines.clear();
                pending.clear();
                started.clear();
            }
        }
    }

    /** One stream was flushed. Any other stream carries on as it was. */
    static void flush(Object key) {
        boolean keep = session != null && wanted;
        synchronized (MODEL) {
            if (tooSlow && System.currentTimeMillis() >= retryAtMs) {
                tooSlow = false;
                slowRuns = 0;
                if (session != null) setState("Model ready");
            }
            keep = keep && !tooSlow;
        }
        synchronized (engines) {
            started.remove(key);
            SeparatedSing engine = engines.get(key);
            if (engine == null) return;
            // A seek is the one time the delay can go without anyone hearing a jump.
            if (keep) engine.reset();
            else {
                engine.close();
                engines.remove(key);
            }
        }
    }

    /** Audio went past without passing through, so what the delay holds is out of date. */
    static void bypass(Object key) {
        if (engines.isEmpty()) return;
        SeparatedSing engine = engines.get(key);
        if (engine != null) engine.reset();
    }

    static SeparatedSing engine(Object key) {
        return engines.isEmpty() ? null : engines.get(key);
    }

    /**
     * Returns how many frames at the start of the block are not to be played. {@code normal} is
     * this stream's own speed and pitch: another stream's say nothing about it.
     */
    static int process(
            Object key,
            float[] samples,
            int count,
            int rate,
            float level,
            VocalCut filter,
            boolean normal) {
        if (level <= 0 || BitPerfect.playing()) {
            bypass(key);
            return 0;
        }
        if (playingNormal != normal) {
            playingNormal = normal;
            changed();
        }
        if (!wanted || !normal || rate < 8000 || rate > SeparatedSing.MAX_RATE) {
            bypass(key);
            if (!normal)
                // Whatever follows starts mid-stream, even if nothing had been played before it.
                synchronized (engines) {
                    started.put(key, true);
                }
            filter.process(samples, count, level);
            return 0;
        }
        SeparatedSing engine = engines.get(key);
        if (engine != null && engine.sampleRate() == rate) {
            // A lost session or a slow phone only stops the estimates: the delay carries on under
            // the filter until a seek lets it go, so nothing is skipped or heard twice.
            if (!engine.fallsBackOn(filter)) engine.fallback(filter, filter::process);
            return engine.process(samples, count, level);
        }
        if (engine != null) engine.reset();
        synchronized (engines) {
            boolean fresh = !started.containsKey(key);
            if (fresh) started.put(key, true);
            if (session != null && !tooSlow) {
                if (fresh) {
                    // Nothing has been heard of this stream yet, so it can start in the delay now,
                    // rather than start under the filter and fall silent when the engine arrives.
                    engine = install(key, rate, filter);
                    if (engine != null) return engine.process(samples, count, level);
                } else if (!pending.containsKey(key) && pending.size() < 2) {
                    pending.put(key, rate);
                    worker.execute(
                            () -> {
                                try {
                                    if (wanted && session != null) install(key, rate, filter);
                                } finally {
                                    synchronized (engines) {
                                        pending.remove(key);
                                    }
                                }
                            });
                }
            }
        }
        filter.process(samples, count, level);
        return 0;
    }

    private static SeparatedSing install(Object key, int rate, VocalCut filter) {
        SeparatedSing created = null;
        try {
            created = new SeparatedSing(rate, MODEL, worker, filter::process);
            created.fallback(filter, filter::process);
            synchronized (engines) {
                if (!wanted) {
                    created.close();
                    return null;
                }
                if (engines.size() >= 2 && !engines.containsKey(key)) {
                    Iterator<SeparatedSing> old = engines.values().iterator();
                    old.next().close();
                    old.remove();
                }
                SeparatedSing previous = engines.put(key, created);
                if (previous != null) previous.close();
            }
            return created;
        } catch (OutOfMemoryError pressure) {
            if (created != null) created.close();
            setState(UNAVAILABLE);
            reset(true);
            worker.execute(SeparationModel::closeSession);
            return null;
        }
    }

    private static void load() throws Exception {
        Context context = SoundExtras.app();
        if (context == null) throw new IOException("Application unavailable");
        File directory = new File(context.getFilesDir(), "sing-models");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Model storage unavailable");
        File model = new File(directory, ASSET);
        if (!valid(model)) {
            File pending = new File(directory, ASSET + ".part");
            try (InputStream input = context.getAssets().open(ASSET);
                    FileOutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (!wanted) throw new InterruptedIOException();
                    output.write(buffer, 0, n);
                }
                output.getFD().sync();
            } catch (Exception failure) {
                pending.delete();
                throw failure;
            }
            if (!valid(pending) || !pending.renameTo(model)) {
                pending.delete();
                throw new IOException("Model verification failed");
            }
        }
        Class<?> envType = Class.forName("ai.onnxruntime.OrtEnvironment");
        Class<?> optionsType = Class.forName("ai.onnxruntime.OrtSession$SessionOptions");
        Object options = optionsType.getConstructor().newInstance();
        try {
            optionsType.getMethod("setIntraOpNumThreads", int.class).invoke(options, 2);
            optionsType.getMethod("setCPUArenaAllocator", boolean.class).invoke(options, false);
            optionsType
                    .getMethod("setMemoryPatternOptimization", boolean.class)
                    .invoke(options, false);
            Class<?> optimizations =
                    Class.forName("ai.onnxruntime.OrtSession$SessionOptions$OptLevel");
            optionsType
                    .getMethod("setOptimizationLevel", optimizations)
                    .invoke(options, optimizations.getField("ALL_OPT").get(null));
            environment = envType.getMethod("getEnvironment").invoke(null);
            Object created =
                    envType.getMethod("createSession", String.class, optionsType)
                            .invoke(environment, model.getAbsolutePath(), options);
            try {
                validateShape(created, "getInputInfo", "mix_magnitude");
                validateShape(created, "getOutputInfo", "target_magnitude");
                tensorFactory =
                        Class.forName("ai.onnxruntime.OnnxTensor")
                                .getMethod(
                                        "createTensor", envType, FloatBuffer.class, long[].class);
                run = created.getClass().getMethod("run", Map.class);
                floatBuffer =
                        Class.forName("ai.onnxruntime.OnnxTensor").getMethod("getFloatBuffer");
                session = created;
                setState("Model ready");
            } catch (Exception | LinkageError failure) {
                close(created);
                throw failure;
            }
        } finally {
            close(options);
        }
    }

    private static void validateShape(Object created, String method, String name) throws Exception {
        Map<?, ?> nodes = (Map<?, ?>) created.getClass().getMethod(method).invoke(created);
        Object node = nodes.get(name);
        if (nodes.size() != 1 || node == null)
            throw new IOException("Unexpected separation interface");
        Object info = node.getClass().getMethod("getInfo").invoke(node);
        long[] shape = (long[]) info.getClass().getMethod("getShape").invoke(info);
        if (!Arrays.equals(shape, new long[] {1, 2, SeparatedSing.BINS, SeparatedSing.FRAMES}))
            throw new IOException("Unexpected separation shape");
    }

    private static synchronized void observeMemory() {
        Context app = SoundExtras.app();
        if (memoryObserver || app == null) return;
        app.registerComponentCallbacks(
                new android.content.ComponentCallbacks2() {
                    public void onConfigurationChanged(
                            android.content.res.Configuration configuration) {}

                    public void onLowMemory() {
                        release();
                    }

                    public void onTrimMemory(int level) {
                        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN)
                            release();
                    }

                    private void release() {
                        reset(true);
                        worker.execute(
                                () -> {
                                    closeSession();
                                    setState(wanted ? UNAVAILABLE : "Filter");
                                });
                    }
                });
        memoryObserver = true;
    }

    private static float[] infer(float[] magnitude) throws Exception {
        long started = System.nanoTime();
        float[] result;
        Inference fake = inference;
        if (fake != null) result = fake.run(magnitude);
        else result = runSession(magnitude);
        noteInference((System.nanoTime() - started) / 1000000);
        return result;
    }

    /** Estimates that keep arriving too late are no use, and cost the phone two busy threads. */
    static void noteInference(long ms) {
        lastInferenceMs = ms;
        synchronized (MODEL) {
            if (ms <= BUDGET_MS) {
                slowRuns = 0;
                return;
            }
            if (++slowRuns < SLOW_RUNS || tooSlow) return;
            tooSlow = true;
            retryAtMs = System.currentTimeMillis() + backoffMs;
            backoffMs = Math.min(backoffMs * 2, LAST_BACKOFF_MS);
            setState(TOO_SLOW);
        }
    }

    private static float[] runSession(float[] magnitude) throws Exception {
        Object current = session;
        if (!wanted || current == null) throw new IOException("Separation inactive");
        if (inputBytes == null || inputBytes.capacity() != magnitude.length * 4)
            inputBytes =
                    ByteBuffer.allocateDirect(magnitude.length * 4).order(ByteOrder.nativeOrder());
        if (output == null || output.length != magnitude.length)
            output = new float[magnitude.length];
        FloatBuffer floats = inputBytes.asFloatBuffer();
        floats.put(magnitude);
        floats.rewind();
        Object tensor = null, result = null;
        boolean failed = true;
        try {
            tensor =
                    tensorFactory.invoke(
                            null,
                            environment,
                            floats,
                            new long[] {1, 2, SeparatedSing.BINS, SeparatedSing.FRAMES});
            result = run.invoke(current, Collections.singletonMap("mix_magnitude", tensor));
            Object target = result.getClass().getMethod("get", int.class).invoke(result, 0);
            FloatBuffer values = (FloatBuffer) floatBuffer.invoke(target);
            if (values.remaining() != magnitude.length)
                throw new IOException("Unexpected separation shape");
            values.get(output);
            failed = false;
            return output;
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof OutOfMemoryError)
                throw (OutOfMemoryError) wrapped.getCause();
            throw wrapped;
        } finally {
            close(result);
            close(tensor);
            if (failed) {
                closeSession();
                setState(UNAVAILABLE);
            }
        }
    }

    private static boolean valid(File file) throws Exception {
        if (!file.isFile() || file.length() != 9054206) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest())
            hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        return SHA256.contentEquals(hash);
    }

    /** Only on the worker, between inferences. */
    private static void closeSession() {
        Object current = session;
        session = null;
        close(current);
        snapshot = null;
        output = null;
        inputBytes = null;
    }

    private static void close(Object value) {
        if (value != null)
            try {
                value.getClass().getMethod("close").invoke(value);
            } catch (Exception ignored) {
            }
    }
}
