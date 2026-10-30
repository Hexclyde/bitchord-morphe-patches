package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class AtomicFile {
    private final File file, pending;

    public AtomicFile(File file) {
        this.file = file;
        pending = new File(file + ".pending");
    }

    public FileInputStream openRead() throws IOException {
        return new FileInputStream(file);
    }

    public FileOutputStream startWrite() throws IOException {
        return new FileOutputStream(pending);
    }

    public void finishWrite(FileOutputStream output) throws IOException {
        output.close();
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public void failWrite(FileOutputStream output) throws IOException {
        output.close();
        pending.delete();
    }
}
