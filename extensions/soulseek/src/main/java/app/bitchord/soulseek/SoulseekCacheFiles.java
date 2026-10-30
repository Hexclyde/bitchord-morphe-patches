package app.bitchord.soulseek;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Private staging cleanup after the owner proves that native workers and readers stopped. */
final class SoulseekCacheFiles {
    private SoulseekCacheFiles() {}

    static void clear(File root) throws IOException {
        File absolute = root.getAbsoluteFile();
        remove(absolute, absolute.getPath());
    }

    private static void remove(File file, String root) throws IOException {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return;
        // Delete links themselves; never walk outside the private staging tree through a link.
        if (!Files.isSymbolicLink(file.toPath())) {
            String canonical = file.getCanonicalPath();
            if (!canonical.equals(root) && !canonical.startsWith(root + File.separator))
                throw new IOException("Invalid Soulseek cache location");
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null)
                    throw new IOException("Cannot read Soulseek temporary storage");
                for (File child : children) remove(child, root);
            }
        }
        if (!file.delete() && file.exists())
            throw new IOException("Could not clear all Soulseek temporary audio");
    }
}
