package app.bitchord.telegram;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Java memory of the separation model: compact masks, shared scratch, admission, retirement. */
public final class KaraokeMemoryTest {
    private static final long MIB = 1L << 20;
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final int ROW = SeparatedSing.FRAMES;
    private static final int TOTAL = 2 * SeparatedSing.BINS * ROW;

    private static float[] noise(long seed, boolean signed) {
        Random random = new Random(seed);
        float[] out = new float[TOTAL];
        for (int i = 0; i < out.length; i++) {
            float v = random.nextFloat() * 3;
            out[i] = signed && random.nextInt(9) == 0 ? -v : v;
        }
        out[5] = Float.NaN;
        out[ROW * 3 + 959] = Float.POSITIVE_INFINITY;
        out[ROW * 4 + 832] = Float.NEGATIVE_INFINITY;
        return out;
    }

    private static float[] reference(float[] mixture, float[] vocals) {
        int bins = SeparatedSing.BINS, last = SeparatedSing.MASK_FRAMES;
        float[] out = new float[2 * bins * last];
        for (int c = 0; c < 2; c++)
            for (int bin = 0; bin < bins; bin++)
                for (int frame = 0; frame < last; frame++) {
                    int index = (c * bins + bin) * ROW + ROW - last + frame;
                    out[(c * bins + bin) * last + frame] =
                            SeparatedSing.mask(mixture[index], vocals[index]);
                }
        return out;
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean)
                        java.lang.management.ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static void compactMasks() throws Exception {
        float[] mixture = noise(1, false), vocals = noise(2, true);
        float[] expected = reference(mixture, vocals);
        float[] into = new float[expected.length];
        ByteBuffer direct =
                ByteBuffer.allocateDirect(vocals.length * 4 + 8).order(ByteOrder.nativeOrder());
        direct.position(8);
        FloatBuffer values = direct.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();
        values.put(vocals);
        values.rewind();
        values.position(0);
        long before = allocated();
        SeparatedSing.fillMasks(mixture, values, into);
        long used = allocated() - before;
        check(Arrays.equals(expected, into), "masks equal the full-output formula in every column");
        check(values.position() == 0, "the output buffer is not moved");
        check(used < MIB / 4, "reading the masks allocates almost nothing: " + used);
        check(SeparatedSing.MASK_BYTES <= 2 * MIB + 4096, "one mask set is about 2 MiB");
        boolean bounded = true;
        for (float v : into) bounded &= v >= 0 && v <= 1;
        check(bounded, "masks stay within 0..1, bad input gives 0");
        // The default path goes through infer() and must give the same masks.
        float[] viaInfer = new float[expected.length];
        boolean ok =
                new SeparatedSing.Model() {
                    public float[] infer(float[] magnitude) {
                        return vocals;
                    }
                }.masks(mixture, viaInfer);
        check(ok && Arrays.equals(expected, viaInfer), "the default path gives the same masks");
        check(
                !new SeparatedSing.Model() {
                    public float[] infer(float[] magnitude) {
                        return new float[TOTAL - 1];
                    }
                }.masks(mixture, new float[expected.length]),
                "a wrong length is no estimate");
        check(
                !new SeparatedSing.Model() {
                    public float[] infer(float[] magnitude) {
                        return null;
                    }
                }.masks(mixture, new float[expected.length]),
                "nothing is no estimate");
        // The same through the model's own path, with the runtime replaced.
        SeparationModel.forTest(m -> vocals, true);
        float[] through = new float[expected.length];
        check(SeparationModel.MODEL.masks(mixture, through), "the model path answers");
        check(Arrays.equals(expected, through), "and gives the same masks");
        SeparationModel.forTest(m -> new float[3], true);
        check(!SeparationModel.MODEL.masks(mixture, through), "the model path rejects a bad shape");
        SeparationModel.forTest(null, false);
        for (Field field : SeparationModel.class.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == float[].class)
                check(
                        field.getName().equals("snapshot"),
                        "no retained full output: " + field.getName());
    }

    private static void scratchIsNotPinned() {
        List<float[]> seen = new ArrayList<>();
        int[] asks = {0};
        boolean[] refuse = {false};
        SeparatedSing engine =
                new SeparatedSing(
                        44100,
                        new SeparatedSing.Model() {
                            public float[] infer(float[] magnitude) {
                                seen.add(magnitude);
                                return new float[magnitude.length];
                            }

                            @Override
                            public float[] scratch(int length) {
                                asks[0]++;
                                return refuse[0] ? null : new float[length];
                            }
                        },
                        Runnable::run,
                        null);
        float[] audio = new float[44100 * 2 * 3];
        engine.process(audio, audio.length, 1);
        check(seen.size() >= 3, "estimates were asked for: " + seen.size());
        check(asks[0] == seen.size(), "the model is asked for the scratch every time");
        Map<float[], Boolean> distinct = new IdentityHashMap<>();
        for (float[] s : seen) distinct.put(s, true);
        check(distinct.size() == seen.size(), "a fresh array arrives each time, none is kept");
        refuse[0] = true;
        int before = seen.size();
        engine.process(new float[44100 * 2 * 2], 44100 * 4, 1);
        check(seen.size() == before, "refused scratch asks for no estimate");
        refuse[0] = false;
        engine.process(new float[44100 * 2 * 2], 44100 * 4, 1);
        check(seen.size() > before, "and the next one is asked for again");
    }

    private static long arrays(Object value) {
        if (value == null) return 0;
        if (!value.getClass().isArray()) return 0;
        Class<?> type = value.getClass().getComponentType();
        int n = Array.getLength(value);
        long bytes = 16;
        if (type.isPrimitive()) {
            int size =
                    type == double.class || type == long.class
                            ? 8
                            : type == byte.class || type == boolean.class ? 1 : 4;
            return bytes + (long) n * size;
        }
        for (int i = 0; i < n; i++) bytes += 4 + arrays(Array.get(value, i));
        return bytes;
    }

    private static void estimateCoversTheArrays() throws Exception {
        for (int rate : new int[] {44100, 48000, 96000, 192000}) {
            SeparatedSing engine =
                    new SeparatedSing(
                            rate,
                            new SeparatedSing.Model() {
                                public float[] infer(float[] magnitude) {
                                    return magnitude;
                                }
                            },
                            Runnable::run,
                            null);
            long sum = 0;
            for (Field field : SeparatedSing.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                sum += arrays(field.get(engine));
            }
            long estimate = SeparatedSing.heapBytes(rate);
            check(estimate >= sum, rate + " Hz: estimate " + estimate + " covers arrays " + sum);
            check(estimate - sum < MIB, rate + " Hz: and is not loose: " + (estimate - sum));
            check(estimate < 64 * MIB, rate + " Hz: bounded whatever the track length");
        }
        check(
                SeparatedSing.heapBytes(7999) == Long.MAX_VALUE,
                "an unsupported rate is never admitted");
        check(
                SeparatedSing.heapBytes(Integer.MAX_VALUE) == Long.MAX_VALUE,
                "rate overflow is refused");
        check(SeparatedSing.heapBytes(48000) > 21 * MIB, "48 kHz engine is over 21 MiB");
    }

    private static final long MAX = 192 * MIB;

    /** A half-heap request only fits when no earlier ticket is still open. */
    private static boolean nothingOpen() {
        SourceAudio.JavaMemory probe = SourceAudio.reserveJava(MAX, 0, MAX / 2);
        if (probe == null) return false;
        probe.close();
        return true;
    }

    private static void deniedAdmission() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SeparationModel.forTest(m -> new float[m.length], true);
        SeparationModel.admission =
                bytes -> {
                    asked.incrementAndGet();
                    return SourceAudio.reserveJava(MAX, MAX - 1, bytes);
                };
        Object key = new Object();
        VocalCut filter = new VocalCut(48000);
        for (int i = 0; i < 600; i++) {
            float[] block = new float[1024 * 2];
            check(
                    SeparationModel.process(key, block, block.length, 48000, 1, filter, true) == 0,
                    "the filter carries on");
        }
        Thread.sleep(50);
        check(SeparationModel.engine(key) == null, "refused: no engine");
        check(asked.get() <= 2, "refusals are paced, not repeated every block: " + asked.get());
        SeparationModel.admission = null;
        check(nothingOpen(), "no ticket stayed open");
        check(SeparationModel.MODEL.available(), "the model stays wanted and ready");
        SeparationModel.forTest(null, false);

        // Scratch refused: nothing allocated, null returned, ticket closed.
        SeparationModel.forTest(m -> new float[m.length], true);
        SeparationModel.admission = bytes -> SourceAudio.reserveJava(MAX, MAX - 1, bytes);
        check(SeparationModel.MODEL.scratch(TOTAL) == null, "scratch refused gives null");
        SeparationModel.admission = bytes -> SourceAudio.reserveJava(MAX, 0, bytes);
        float[] scratch = SeparationModel.MODEL.scratch(TOTAL);
        check(
                scratch != null && SeparationModel.MODEL.scratch(TOTAL) == scratch,
                "and it is kept once given");
        check(nothingOpen(), "the scratch ticket was closed");
        SeparationModel.forTest(null, false);
    }

    private static void budgetPressure() throws Exception {
        long engine = SeparatedSing.heapBytes(48000);
        // The editor, players and waves already hold this much of the 192 MiB.
        long baseline = MAX - 24 * MIB - 2 * engine - MIB;
        AtomicLong used = new AtomicLong(baseline);
        SeparationModel.forTest(m -> new float[m.length], true);
        SeparationModel.admission = bytes -> SourceAudio.reserveJava(MAX, used.get(), bytes);
        SeparatedSing.Model model = SeparationModel.MODEL;
        // Eight admissions at once while the allocations are still pending.
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1), got = new CountDownLatch(threads);
        List<SeparatedSing.Ticket> tickets = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++)
            pool.execute(
                    () -> {
                        try {
                            go.await();
                            SeparatedSing.Ticket ticket = model.reserve(engine);
                            if (ticket != null) tickets.add(ticket);
                        } catch (InterruptedException ignored) {
                        }
                        got.countDown();
                    });
        go.countDown();
        got.await();
        pool.shutdown();
        check(
                tickets.size() == 2,
                "two engines fit beside the baseline, not more: " + tickets.size());
        for (SeparatedSing.Ticket ticket : tickets) ticket.close();
        check(model.reserve(engine) != null, "released memory can be asked for again");
        // Both engines materialise: the process now uses their bytes and a third is refused.
        used.addAndGet(2 * engine);
        check(model.reserve(engine) == null, "a third engine is refused once two are live");
        SeparationModel.forTest(null, false);
    }

    private static float[] feed(SeparatedSing engine, float[] pcm) {
        float[] copy = pcm.clone();
        engine.process(copy, copy.length, 1);
        return copy;
    }

    private static void retirement() throws Exception {
        int rate = 44100;
        SeparationModel.forTest(m -> new float[m.length], true);
        SeparationModel.admission = bytes -> SourceAudio.reserveJava(MAX, 0, bytes);
        Object key = new Object();
        VocalCut filter = new VocalCut(rate);
        float[] block = new float[rate * 2];
        for (int i = 0; i < block.length; i++) block[i] = (float) (.3 * Math.sin(i * .01));
        SeparationModel.process(key, block.clone(), block.length, rate, 1, filter, true);
        SeparatedSing active = SeparationModel.engine(key);
        check(active != null, "engine installed");
        SeparationModel.flush(key);
        check(SeparationModel.engine(key) == active, "first flush after audio resets and keeps it");
        check(active.idle(), "and it holds nothing");
        SeparationModel.flush(key);
        check(SeparationModel.engine(key) == null, "second flush with no audio retires it");

        float[] first = block.clone();
        SeparationModel.process(key, first, first.length, rate, 1, filter, true);
        SeparatedSing again = SeparationModel.engine(key);
        check(again != null && again != active, "audio installs a new engine");
        SeparatedSing.Model model = SeparationModel.MODEL;
        SeparatedSing reused = new SeparatedSing(rate, model, Runnable::run, filter::process);
        feed(reused, block);
        reused.reset();
        SeparatedSing fresh = new SeparatedSing(rate, model, Runnable::run, filter::process);
        float[] a = feed(reused, block), b = feed(fresh, block);
        check(
                Arrays.equals(a, b) && reused.latencySamples() == fresh.latencySamples(),
                "a reinstalled engine sounds the same as a reset one");
        SeparationModel.forTest(null, false);
    }

    private static int live(Object... keys) {
        int count = 0;
        for (Object key : keys) if (SeparationModel.engine(key) != null) count++;
        return count;
    }

    private static void deferredThirdKeepsTheTwo() throws Exception {
        int rate = 44100;
        SeparationModel.forTest(m -> new float[m.length], true);
        SeparationModel.admission = bytes -> SourceAudio.reserveJava(MAX, 0, bytes);
        Object a = new Object(), b = new Object(), c = new Object(), d = new Object();
        for (Object key : new Object[] {a, b}) {
            float[] block = new float[2048];
            SeparationModel.process(key, block, block.length, rate, 1, new VocalCut(rate), true);
        }
        SeparatedSing first = SeparationModel.engine(a), second = SeparationModel.engine(b);
        float[] block = new float[2048];
        SeparationModel.process(c, block, block.length, rate, 1, new VocalCut(rate), true);
        check(SeparationModel.engine(c) == null, "no engine for a third while both play");
        check(
                SeparationModel.engine(a) == first && SeparationModel.engine(b) == second,
                "the two keep their engines");
        check(first.ranLastBlock() || !first.idle(), "and neither was closed or reset");
        // One of them goes idle: now the third may take its place.
        SeparationModel.process(a, new float[2048], 2048, rate, 0, new VocalCut(rate), true);
        check(first.idle(), "a stream that bypasses holds nothing");
        Thread.sleep(SeparationModel.DENIED_PAUSE_MS + 100);
        SeparationModel.process(d, new float[2048], 2048, rate, 1, new VocalCut(rate), true);
        check(
                SeparationModel.engine(d) != null && SeparationModel.engine(a) == null,
                "an idle engine makes room");
        check(SeparationModel.engine(b) == second, "the playing one is untouched");
        // A new rate on the same key replaces its own old engine only.
        SeparatedSing old = SeparationModel.engine(d);
        SeparationModel.process(d, new float[2048], 2048, 48000, 1, new VocalCut(48000), true);
        for (int i = 0; i < 400 && SeparationModel.engine(d) == old; i++) Thread.sleep(10);
        SeparatedSing renewed = SeparationModel.engine(d);
        check(renewed != null && renewed != old && renewed.sampleRate() == 48000, "format change");
        check(SeparationModel.engine(b) == second, "other stream still untouched");
        SeparationModel.forTest(null, false);
    }

    private static void concurrentInstalls() throws Exception {
        int rate = 44100, threads = 6;
        SeparationModel.forTest(m -> new float[m.length], true);
        Object[] keys = new Object[threads];
        for (int i = 0; i < threads; i++) keys[i] = new Object();
        AtomicInteger most = new AtomicInteger();
        SeparationModel.admission =
                bytes -> {
                    int now = live(keys);
                    most.accumulateAndGet(now, Math::max);
                    return SourceAudio.reserveJava(MAX, 0, bytes);
                };
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (Object key : keys)
            done.add(
                    pool.submit(
                            () -> {
                                go.await();
                                SeparationModel.process(
                                        key,
                                        new float[2048],
                                        2048,
                                        rate,
                                        1,
                                        new VocalCut(rate),
                                        true);
                                return null;
                            }));
        go.countDown();
        for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        check(most.get() <= 1, "an engine is only built while at most one other exists: " + most);
        check(live(keys) == 2, "exactly two engines end up live: " + live(keys));
        check(nothingOpen(), "no ticket stayed open");
        SeparationModel.forTest(null, false);
    }

    // The tensor's public accessor copies everything; only the private view may be used.
    public enum JavaType {
        FLOAT,
        DOUBLE
    }

    public static final class FakeInfo {
        public JavaType type;
        long[] shape;

        public long[] getShape() {
            return shape;
        }
    }

    public static final class FakeTensor {
        boolean closed;
        int copies, views;
        FakeInfo info = new FakeInfo();
        ByteBuffer view;

        FakeTensor(int length, JavaType type, long[] shape) {
            info.type = type;
            info.shape = shape;
            view = ByteBuffer.allocateDirect(length * 4).order(ByteOrder.nativeOrder());
        }

        public FakeInfo getInfo() {
            return info;
        }

        public FloatBuffer getFloatBuffer() {
            copies++;
            throw new AssertionError("the public accessor copies the whole output");
        }

        private ByteBuffer getBuffer() {
            if (closed) throw new IllegalStateException("closed");
            views++;
            return view;
        }

        void close() {
            closed = true;
        }
    }

    private static boolean rejects(Object target, java.lang.reflect.Method m, int length) {
        try {
            SeparationModel.borrowed(target, m, length);
            return false;
        } catch (Exception expected) {
            return true;
        }
    }

    private static void borrowsTheNativeView() throws Exception {
        int length = TOTAL;
        long[] shape = {1, 2, SeparatedSing.BINS, SeparatedSing.FRAMES};
        java.lang.reflect.Method borrower = SeparationModel.borrower(FakeTensor.class);
        FakeTensor tensor = new FakeTensor(length, JavaType.FLOAT, shape);
        long before = allocated();
        FloatBuffer view = SeparationModel.borrowed(tensor, borrower, length);
        long used = allocated() - before;
        check(tensor.copies == 0 && tensor.views == 1, "the private view is used, never a copy");
        check(used < MIB / 4, "borrowing allocates no array: " + used);
        check(view.remaining() == length && view.position() == 0, "all of it is visible");
        check(view.isDirect() && view.order() == ByteOrder.nativeOrder(), "native direct memory");
        float[] mixture = noise(3, false);
        float[] vocals = noise(4, true);
        view.put(vocals);
        float[] into = new float[2 * SeparatedSing.BINS * SeparatedSing.MASK_FRAMES];
        SeparatedSing.fillMasks(mixture, SeparationModel.borrowed(tensor, borrower, length), into);
        check(
                Arrays.equals(reference(mixture, vocals), into),
                "masks read from the view are exact");
        tensor.close();
        check(rejects(tensor, borrower, length), "after close nothing can be borrowed");
        check(rejects(new FakeTensor(length, JavaType.DOUBLE, shape), borrower, length), "type");
        check(
                rejects(
                        new FakeTensor(length, JavaType.FLOAT, new long[] {1, 2, 2049, 959}),
                        borrower,
                        length),
                "shape");
        check(
                rejects(new FakeTensor(length - 1, JavaType.FLOAT, shape), borrower, length),
                "capacity");
        FakeTensor swapped = new FakeTensor(length, JavaType.FLOAT, shape);
        swapped.view =
                swapped.view.order(
                        ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
                                ? ByteOrder.LITTLE_ENDIAN
                                : ByteOrder.BIG_ENDIAN);
        check(rejects(swapped, borrower, length), "byte order");
        FakeTensor heap = new FakeTensor(length, JavaType.FLOAT, shape);
        heap.view = ByteBuffer.allocate(length * 4).order(ByteOrder.nativeOrder());
        check(rejects(heap, borrower, length), "heap buffer is not native memory");
        FakeTensor moved = new FakeTensor(length, JavaType.FLOAT, shape);
        moved.view.position(4);
        check(rejects(moved, borrower, length), "position");
        check(rejects(new Object(), borrower, length), "wrong class");
        check(rejects(null, borrower, length), "no tensor");
    }

    public static void main(String[] args) throws Exception {
        check(Runtime.getRuntime().maxMemory() <= 200 * MIB, "runs under the device's heap cap");
        compactMasks();
        scratchIsNotPinned();
        estimateCoversTheArrays();
        deniedAdmission();
        budgetPressure();
        retirement();
        deferredThirdKeepsTheTwo();
        concurrentInstalls();
        borrowsTheNativeView();
        System.out.println("PASS: " + checks + " separation model memory checks.");
    }
}
