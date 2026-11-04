package app.bitchord.telegram;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A private journal rebuilt only from validated records when it is read or exported. */
public final class DiagnosticStore {
    public static final int MAX_RECORDS = 512, MAX_BYTES = 128 * 1024, MAX_LINE = 2048;
    private final File file;
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private int bytes;
    private long dropped, corrupt;

    public DiagnosticStore(File file) throws IOException {
        this.file = file.getAbsoluteFile();
        File parent = this.file.getParentFile();
        if (parent == null
                || Files.isSymbolicLink(parent.toPath())
                || !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Diagnostic directory is unavailable");
        check(this.file);
        File temporary = new File(parent, this.file.getName() + ".tmp");
        check(temporary);
        if (temporary.exists()) {
            if (!this.file.exists())
                Files.move(
                        temporary.toPath(),
                        this.file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            else if (!temporary.delete())
                throw new IOException("Diagnostic recovery is unavailable");
        }
        if (this.file.exists()) {
            privateFile(this.file);
            load();
            if (this.file.length() > MAX_BYTES || corrupt > 0) rewrite();
        }
    }

    private static void check(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || file.exists() && !file.isFile())
            throw new IOException("Diagnostic path is not a regular file");
    }

    private static void privateFile(File file) throws IOException {
        if (!file.setReadable(false, false)
                || !file.setWritable(false, false)
                || !file.setExecutable(false, false)
                || !file.setReadable(true, true)
                || !file.setWritable(true, true))
            throw new IOException("Diagnostic file permissions are unavailable");
    }

    private void load() throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long offset = Math.max(0, input.length() - MAX_BYTES);
            input.seek(offset);
            if (offset > 0) {
                corrupt++;
                while (input.getFilePointer() < input.length() && input.read() != '\n') {}
                ;
            }
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean oversized = false;
            while (input.getFilePointer() < input.length()) {
                int value = input.read();
                if (value == '\n') {
                    if (oversized) {
                        corrupt++;
                    } else {
                        String original = new String(line.toByteArray(), StandardCharsets.UTF_8);
                        String safe = DiagnosticRecord.sanitize(original);
                        if (safe == null) corrupt++;
                        else {
                            if (!safe.equals(original)) corrupt++;
                            add(safe);
                        }
                    }
                    line.reset();
                    oversized = false;
                } else if (line.size() < MAX_LINE) line.write(value);
                else oversized = true;
            }
            if (line.size() > 0 || oversized) corrupt++;
        }
    }

    private void add(String safe) {
        int length = safe.getBytes(StandardCharsets.UTF_8).length + 1;
        while (!records.isEmpty()
                && (records.size() >= MAX_RECORDS || bytes + length > MAX_BYTES)) {
            bytes -= records.removeFirst().getBytes(StandardCharsets.UTF_8).length + 1;
            dropped++;
        }
        records.addLast(safe);
        bytes += length;
    }

    public synchronized void append(String encoded) throws IOException {
        String safe = DiagnosticRecord.sanitize(encoded);
        if (safe == null) {
            dropped++;
            return;
        }
        int length = safe.getBytes(StandardCharsets.UTF_8).length + 1;
        check(file);
        add(safe);
        if (file.exists() && file.length() + length <= MAX_BYTES && records.size() < MAX_RECORDS) {
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                privateFile(file);
                output.write((safe + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } else rewrite();
    }

    private void rewrite() throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        check(temporary);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            privateFile(temporary);
            for (String safe : records)
                output.write((safe + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        privateFile(file);
    }

    public synchronized String snapshot() {
        try {
            JSONObject severity = new JSONObject(), category = new JSONObject();
            StringBuilder out = new StringBuilder();
            for (String encoded : records) {
                String safe = DiagnosticRecord.sanitize(encoded);
                if (safe == null) continue;
                JSONObject event = new JSONObject(safe);
                String level = event.optString("severity", "INFO"),
                        area = event.optString("category", "OTHER");
                severity.put(level, severity.optInt(level) + 1);
                category.put(area, category.optInt(area) + 1);
                out.append(safe).append('\n');
            }
            JSONObject header =
                    new JSONObject()
                            .put("schema", "bitchord.diagnostics.1")
                            .put("event_count", records.size())
                            .put("severity_counts", severity)
                            .put("category_counts", category)
                            .put("dropped_since_open", dropped)
                            .put("corrupt_since_open", corrupt);
            return header + "\n" + out;
        } catch (Exception invalid) {
            return "{\"schema\":\"bitchord.diagnostics.1\",\"event_count\":0}\n";
        }
    }

    public synchronized void clear() throws IOException {
        check(file);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        check(temporary);
        if (file.exists() && !file.delete() || temporary.exists() && !temporary.delete())
            throw new IOException("Diagnostic journal could not be cleared");
        records.clear();
        bytes = 0;
        dropped = 0;
        corrupt = 0;
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized long corrupt() {
        return corrupt;
    }
}
