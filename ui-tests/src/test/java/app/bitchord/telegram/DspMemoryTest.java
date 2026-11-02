package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.media.MediaDataSource;
import android.media.MediaFormat;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@SuppressWarnings("unchecked")
public class DspMemoryTest {
    static final long MB = 1L << 20, MAXIMUM = 192 * MB;
    Supplier<SourceAudio.AnalysisFormat> formats;
    LongSupplier heap;
    boolean enabled, gated;
    long epoch;
    Map<Object, Object> controls, observed;
    final List<SourceAudio.DspMemory> memories = new ArrayList<>();
    final List<Control> nativeControls = new ArrayList<>();
    MediaFormat format;
    int probesClosed, sourcesClosed;

    static Object field(String name) throws Exception {
        Field field = SteadyPlayback.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    static void field(String name, Object value) throws Exception {
        Field field = SteadyPlayback.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Before
    public void setup() throws Exception {
        formats = SourceAudio.analysisFormats;
        heap = SteadyPlayback.heapBytes;
        enabled = SteadyPlayback.enabled;
        gated = (Boolean) field("gateObserved");
        epoch = (Long) field("configurationEpoch");
        controls = new HashMap<>((Map<Object, Object>) field("controls"));
        observed = new HashMap<>((Map<Object, Object>) field("observedControls"));
        assertTrue(((List<?>) field("analysisLeases")).isEmpty());
        ((Map<?, ?>) field("controls")).clear();
        ((Map<?, ?>) field("observedControls")).clear();
        field("gateObserved", false);
        SteadyPlayback.enabled = false;
        SteadyPlayback.heapBytes = () -> MAXIMUM;
        format = MediaFormat.createAudioFormat("audio/flac", 48000, 2);
        format.setLong(MediaFormat.KEY_DURATION, 214_000_000);
        SourceAudio.analysisFormats =
                () ->
                        new SourceAudio.AnalysisFormat() {
                            public MediaFormat read(MediaDataSource source) {
                                return format;
                            }

                            public void close() {
                                probesClosed++;
                            }
                        };
    }

    @After
    public void restore() throws Exception {
        for (SourceAudio.DspMemory memory : memories) memory.close();
        assertTrue(((List<?>) field("analysisLeases")).isEmpty());
        SourceAudio.analysisFormats = formats;
        SteadyPlayback.heapBytes = heap;
        SteadyPlayback.enabled = enabled;
        ((Map<Object, Object>) field("controls")).clear();
        ((Map<Object, Object>) field("controls")).putAll(controls);
        ((Map<Object, Object>) field("observedControls")).clear();
        ((Map<Object, Object>) field("observedControls")).putAll(observed);
        field("gateObserved", gated);
        field("configurationEpoch", epoch);
    }

    MediaDataSource source() {
        return new MediaDataSource() {
            public int readAt(long at, byte[] bytes, int offset, int length) {
                return -1;
            }

            public long getSize() {
                return 1;
            }

            public void close() {
                sourcesClosed++;
            }
        };
    }

    static final class Allocator {
        long allocatedCount, availableCount, individualAllocationSize = MB;
    }

    static final class Control {
        final Object allocator;
        int targetBufferBytesOverwrite;
        final Map<Object, Object> loadingStates = new HashMap<>();

        Control(Object allocator) {
            this.allocator = allocator;
        }

        void updateAllocator() {}
    }

    static final class Parameters {
        long bufferedDurationUs = 3_000_000;
    }

    Control register(Allocator allocator) {
        SteadyPlayback.enabled = true;
        Control control = new Control(allocator);
        nativeControls.add(control);
        SteadyPlayback.loadControl(control);
        SteadyPlayback.allowLoading(control, new Parameters());
        return control;
    }

    SourceAudio.DspMemory memory(long used) {
        SourceAudio.DspMemory memory = SourceAudio.dspMemory(MAXIMUM, used);
        memories.add(memory);
        return memory;
    }

    @Test
    public void actualFormatUsesLongerDurationAndClosesOnlyItsProbe() throws Exception {
        SourceAudio.AnalysisInfo info = SourceAudio.analysisInfo(source(), 120);
        assertEquals(48000, info.sampleRate);
        assertEquals(214d, info.seconds, 0);
        assertEquals(300d, SourceAudio.analysisInfo(source(), 300).seconds, 0);
        assertEquals(2, probesClosed);
        assertEquals(0, sourcesClosed);
    }

    @Test
    public void unknownAndInvalidFormatsCannotProduceAnAdmission() throws Exception {
        for (double seconds : new double[] {Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IOException.class, () -> SourceAudio.analysisInfo(source(), seconds));
        assertThrows(IOException.class, () -> SourceAudio.analysisInfo(null, 30));
        assertEquals(0, probesClosed);
        format.setLong(MediaFormat.KEY_DURATION, 0);
        assertThrows(IOException.class, () -> SourceAudio.analysisInfo(source(), 0));
        format = MediaFormat.createAudioFormat("video/mp4", 48000, 2);
        assertThrows(IOException.class, () -> SourceAudio.analysisInfo(source(), 30));
        format = MediaFormat.createAudioFormat("audio/flac", 0, 2);
        assertThrows(IOException.class, () -> SourceAudio.analysisInfo(source(), 30));
        format = new MediaFormat();
        assertThrows(IOException.class, () -> SourceAudio.analysisInfo(source(), 30));
        assertEquals(4, probesClosed);
    }

    @Test
    public void probeFailuresReleaseResourcesAndRemainIoFailures() {
        for (int failure = 0; failure < 3; failure++) {
            final int next = failure;
            SourceAudio.analysisFormats =
                    () ->
                            new SourceAudio.AnalysisFormat() {
                                public MediaFormat read(MediaDataSource source) throws IOException {
                                    if (next == 0) throw new IOException("setDataSource");
                                    if (next == 1) throw new IllegalStateException("track format");
                                    throw new NoClassDefFoundError("optional extractor");
                                }

                                public void close() {
                                    probesClosed++;
                                }
                            };
            assertThrows(IOException.class, () -> SourceAudio.analysisInfo(source(), 30));
        }
        assertEquals(3, probesClosed);
        SourceAudio.analysisFormats =
                () -> {
                    throw new IllegalStateException("constructor");
                };
        assertThrows(IOException.class, () -> SourceAudio.analysisInfo(source(), 30));
        assertEquals(3, probesClosed);
        assertEquals(0, sourcesClosed);
    }

    @Test
    public void absentDisabledOrUnprovenGateReservesStockPlayers() {
        assertEquals(5 * MB, SourceAudio.dspAvailable(MAXIMUM, 38 * MB));
        SteadyPlayback.enabled = true;
        Control control = new Control(new Allocator());
        nativeControls.add(control);
        SteadyPlayback.loadControl(control);
        assertEquals(5 * MB, SourceAudio.dspAvailable(MAXIMUM, 38 * MB));
        SteadyPlayback.allowLoading(control, new Parameters());
        assertEquals(98 * MB, SourceAudio.dspAvailable(MAXIMUM, 38 * MB));
    }

    @Test
    public void filledAndPooledBuffersAreNotReservedForASecondTime() {
        Allocator allocator = new Allocator();
        Control control = register(allocator);
        assertEquals(48 * MB, SteadyPlayback.analysisGrowth(MAXIMUM));
        assertEquals(98 * MB, SourceAudio.dspAvailable(MAXIMUM, 38 * MB));
        allocator.allocatedCount = 24;
        allocator.availableCount = 8;
        assertEquals(16 * MB, SteadyPlayback.analysisGrowth(MAXIMUM));
        assertEquals(98 * MB, SourceAudio.dspAvailable(MAXIMUM, 70 * MB));
        register(allocator);
        assertEquals(
                "A shared native allocator is counted once",
                16 * MB,
                SteadyPlayback.analysisGrowth(MAXIMUM));
        assertNotNull(control);
    }

    @Test
    public void distinctAllocatorsAndRetainedPoolsShareOneCeiling() {
        Allocator a = new Allocator(), b = new Allocator();
        a.allocatedCount = 24;
        a.availableCount = 8;
        b.availableCount = 16;
        Control first = register(a), second = register(b);
        assertEquals(0, SteadyPlayback.analysisGrowth(MAXIMUM));
        assertEquals(98 * MB, SourceAudio.dspAvailable(MAXIMUM, 86 * MB));
        assertNotSame(first, second);
    }

    @Test
    public void invalidAndOverflowingAllocatorStateUsesTheConservativeBudget() {
        Allocator allocator = new Allocator();
        Control control = register(allocator);
        allocator.allocatedCount = -1;
        assertEquals(-1, SteadyPlayback.analysisGrowth(MAXIMUM));
        assertEquals(5 * MB, SourceAudio.dspAvailable(MAXIMUM, 38 * MB));
        allocator.allocatedCount = Long.MAX_VALUE;
        allocator.individualAllocationSize = 2;
        assertEquals(-1, SteadyPlayback.analysisGrowth(MAXIMUM));
        assertNotNull(control);
        for (long[] state :
                new long[][] {
                    {0, 0}, {-1, 0}, {MAXIMUM, -1}, {MAXIMUM, MAXIMUM}, {MAXIMUM, Long.MAX_VALUE}
                }) assertEquals(0, SourceAudio.dspAvailable(state[0], state[1]));
    }

    @Test
    public void disablingCancelsDspButKeepsItsCeilingUntilActualWorkCloses() {
        Allocator allocator = new Allocator();
        Control control = register(allocator);
        SourceAudio.DspMemory memory = memory(38 * MB);
        assertEquals(98 * MB, memory.available());
        allocator.allocatedCount = 96;
        SteadyPlayback.setEnabled(false);
        assertTrue(memory.cancelled());
        assertEquals(0, memory.available(MAXIMUM, 38 * MB));
        assertFalse(SteadyPlayback.allowLoading(control, new Parameters()));
        memory.close();
        memory.close();
        assertTrue(SteadyPlayback.allowLoading(control, new Parameters()));
        assertEquals(8 << 20, control.targetBufferBytesOverwrite);
        assertEquals(0, memory.available(MAXIMUM, 0));
    }

    @Test
    public void startupPressureCancelsBackgroundWorkAndOwnedPoolsStillRefill() {
        Allocator allocator = new Allocator();
        Control control = register(allocator);
        SourceAudio.DspMemory memory = memory(38 * MB);
        allocator.availableCount = 96;
        Parameters starting = new Parameters();
        starting.bufferedDurationUs = 1_000_000;
        assertTrue(SteadyPlayback.allowLoading(control, starting));
        assertFalse(memory.cancelled());
        allocator.availableCount = 0;
        allocator.allocatedCount = 96;
        assertFalse(SteadyPlayback.allowLoading(control, starting));
        assertTrue(memory.cancelled());
    }

    @Test
    public void inspectionFailureCannotOpenTheGateWhileDspStillOwnsMemory() {
        Control control = register(new Allocator());
        SourceAudio.DspMemory memory = memory(38 * MB);
        assertFalse(SteadyPlayback.allowLoading(new Control(new Object()), new Parameters()));
        assertTrue(memory.cancelled());
        assertEquals(0, memory.available(MAXIMUM, 38 * MB));
        memory.close();
        SteadyPlayback.setEnabled(false);
        assertTrue(SteadyPlayback.allowLoading(control, new Parameters()));
    }

    @Test
    public void budgetRecheckUsesTheSameLeaseAndRejectsChangedHeapLimits() {
        Allocator allocator = new Allocator();
        Control control = register(allocator);
        SourceAudio.DspMemory memory = memory(38 * MB);
        allocator.allocatedCount = 32;
        assertEquals(98 * MB, memory.available(MAXIMUM, 70 * MB));
        assertEquals(0, memory.available(MAXIMUM / 2, 38 * MB));
        assertNotNull(control);
    }

    @Test
    public void measuredLowRamTraceReservesOnlyGrowthBeyondItsTemporaryCeiling() throws Exception {
        Allocator allocator = new Allocator();
        allocator.allocatedCount = 52;
        allocator.availableCount = 16;
        register(allocator);
        long preview = SourceAudio.dspAvailable(MAXIMUM, 118 * MB);
        assertEquals(66 * MB, preview);
        for (double seconds : new double[] {187, 214, 302})
            assertTrue(DspAnalysis.bytes(48000, seconds) <= preview);
        Method budget = SteadyPlayback.class.getDeclaredMethod("loadingBudget");
        budget.setAccessible(true);
        SourceAudio.DspMemory memory = memory(118 * MB);
        assertEquals(preview, memory.available());
        assertEquals(48 * MB, ((Number) budget.invoke(null)).longValue());
        assertEquals(
                "No existing live or pooled buffers were discarded",
                68,
                allocator.allocatedCount + allocator.availableCount);
        memory.close();
        assertEquals(
                "Ordinary playback retains its half-heap ceiling",
                96 * MB,
                ((Number) budget.invoke(null)).longValue());
    }

    @Test
    public void temporaryCeilingNeverPretendsExistingBuffersHaveBeenFreed() {
        Allocator allocator = new Allocator();
        allocator.allocatedCount = 80;
        allocator.availableCount = 16;
        register(allocator);
        SourceAudio.DspMemory memory = memory(152 * MB);
        assertEquals(32 * MB, memory.available());
        for (double seconds : new double[] {187, 214, 302})
            assertTrue(DspAnalysis.bytes(48000, seconds) > memory.available());
        assertEquals(96, allocator.allocatedCount + allocator.availableCount);
    }

    @Test
    public void optionalRuntimeAbsenceKeepsConservativeAdmission() throws Exception {
        ClassLoader isolated =
                new ClassLoader(SourceAudio.class.getClassLoader()) {
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (name.equals(SteadyPlayback.class.getName())
                                || name.startsWith(SteadyPlayback.class.getName() + "$"))
                            throw new ClassNotFoundException(name);
                        if (!name.equals(SourceAudio.class.getName())
                                && !name.startsWith(SourceAudio.class.getName() + "$"))
                            return super.loadClass(name, resolve);
                        Class<?> result = findLoadedClass(name);
                        if (result == null)
                            try (InputStream input =
                                    SourceAudio.class.getResourceAsStream(
                                            "/" + name.replace('.', '/') + ".class")) {
                                byte[] bytes = input.readAllBytes();
                                result = defineClass(name, bytes, 0, bytes.length);
                            } catch (IOException failure) {
                                throw new ClassNotFoundException(name, failure);
                            }
                        if (resolve) resolveClass(result);
                        return result;
                    }
                };
        Class<?> audio = isolated.loadClass(SourceAudio.class.getName());
        Method available = audio.getDeclaredMethod("dspAvailable", long.class, long.class);
        available.setAccessible(true);
        assertEquals(5 * MB, ((Number) available.invoke(null, MAXIMUM, 38 * MB)).longValue());
        Method begin = audio.getDeclaredMethod("dspMemory", long.class, long.class);
        begin.setAccessible(true);
        Object memory = begin.invoke(null, MAXIMUM, 38 * MB);
        Method initial = memory.getClass().getDeclaredMethod("available");
        initial.setAccessible(true);
        assertEquals(5 * MB, ((Number) initial.invoke(memory)).longValue());
        Method close = memory.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(memory);
    }
}
