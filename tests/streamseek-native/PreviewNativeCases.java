import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public final class PreviewNativeCases {
    static Class<?> decoder;

    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = decoder.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }

    static Object call(String name, long handle) throws Exception {
        return call(name, new Class<?>[] {long.class}, handle);
    }

    static void check(boolean value, String text) {
        if (!value) throw new AssertionError(text);
    }

    public static final class Input implements Closeable {
        final RandomAccessFile file;
        boolean cancelled;

        Input(File path) throws IOException {
            file = new RandomAccessFile(path, "r");
        }

        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (cancelled) throw new IOException("Fixture input cancelled");
            return file.read(bytes, offset, Math.min(length, 4096));
        }

        public long seek(long offset, int whence) throws IOException {
            if (cancelled) throw new IOException("Fixture input cancelled");
            if ((whence & 0x10000) != 0) return file.length();
            int mode = whence & ~0x20000;
            long base = mode == 0 ? 0 : mode == 1 ? file.getFilePointer() : file.length();
            long target = base + offset;
            if (target < 0 || target > file.length()) throw new IOException("Invalid fixture seek");
            file.seek(target);
            return target;
        }

        public void close() throws IOException {
            file.close();
        }
    }

    static byte[] decode(long handle) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long previous = -1;
        for (int i = 0; i < 1000000; i++) {
            byte[] chunk = (byte[]) call("next", handle);
            if (chunk == null) return bytes.toByteArray();
            if (chunk.length == 0) continue;
            long time = (Long) call("time", handle);
            check(time > previous, "Non-monotonic timestamp");
            previous = time;
            check(
                    bytes.size() + chunk.length <= 128 * 1024 * 1024,
                    "Fixture PCM exceeded bounded output");
            bytes.write(chunk);
        }
        throw new AssertionError("Decode did not terminate");
    }

    public static void run(String directory) throws Exception {
        Class<?> adapter = Class.forName("app.bitchord.soulseek.PreviewExtractors");
        Method seekFactory = adapter.getDeclaredMethod("seekMap", long.class, boolean.class);
        seekFactory.setAccessible(true);
        Class<?> seekType = Class.forName("androidx.media3.extractor.SeekMap");
        for (boolean estimated : new boolean[] {false, true}) {
            Object map = seekFactory.invoke(null, 30_000_000L, estimated);
            check(
                    Boolean.TRUE.equals(seekType.getMethod("isSeekable").invoke(map)),
                    "Preview seek map remains seekable");
            check(
                    Boolean.valueOf(estimated)
                            .equals(seekType.getMethod("isEstimated").invoke(map)),
                    "Default duration callback returns an actual boolean");
            check(
                    Long.valueOf(30_000_000L)
                            .equals(seekType.getMethod("getDurationUs").invoke(map)),
                    "Preview duration callback");
            check(
                    seekType.getMethod("getSeekPoints", long.class).invoke(map, 50_000L) != null,
                    "Seek point construction uses host ABI");
        }
        System.out.println(
                "PASS actual Media3 SeekMap callbacks, including isEstimated default method");
        decoder = Class.forName("app.bitchord.soulseek.NativePreviewAudio");
        File root = new File(directory);
        File[] files = root.listFiles((dir, name) -> name.endsWith(".expect"));
        check(files != null && files.length > 0, "No fixtures");
        Arrays.sort(files);
        for (File expected : files) {
            File audio =
                    new File(
                            root, expected.getName().substring(0, expected.getName().length() - 7));
            long handle = 0;
            try (Input input = new Input(audio)) {
                handle = (Long) call("open", new Class<?>[] {Object.class}, input);
                long[] info = (long[]) call("info", handle);
                check(info[0] > 0 && info[1] > 0, "Missing decoded format");
                byte[] actual = decode(handle);
                byte[] reference = java.nio.file.Files.readAllBytes(expected.toPath());
                check(
                        Arrays.equals(reference, actual),
                        audio.getName()
                                + " PCM differs: "
                                + actual.length
                                + " vs "
                                + reference.length);
                call("close", handle);
                handle = 0;
                input.seek(0, 0);
                handle = (Long) call("open", new Class<?>[] {Object.class}, input);
                check(
                        Arrays.equals(reference, decode(handle)),
                        audio.getName() + " rewind differs");

                String codec = (String) call("codec", handle);
                System.out.println(
                        "PASS "
                                + audio.getName()
                                + " codec="
                                + codec
                                + " rate="
                                + info[0]
                                + " channels="
                                + info[1]
                                + " bits="
                                + info[2]
                                + " bytes="
                                + actual.length
                                + " reopen/rewind");
            } finally {
                if (handle != 0) call("close", handle);
            }
        }
        File first =
                new File(root, files[0].getName().substring(0, files[0].getName().length() - 7));
        try (Input input = new Input(first)) {
            input.cancelled = true;
            try {
                call("open", new Class<?>[] {Object.class}, input);
                throw new AssertionError("Cancelled input opened");
            } catch (IOException expected) {
                System.out.println("PASS cancelled input preserves IOException across JNI");
            }
        }
        File invalid = new File(root, "invalid.bin");
        try (FileOutputStream output = new FileOutputStream(invalid)) {
            output.write(new byte[] {1, 2, 3, 4});
        }
        try (Input input = new Input(invalid)) {
            try {
                call("open", new Class<?>[] {Object.class}, input);
                throw new AssertionError("Invalid input opened");
            } catch (IOException expected) {
                System.out.println("PASS invalid input fails without native crash");
            }
        }
    }
}
