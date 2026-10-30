package app.bitchord.soulseek;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SoulseekCacheFilesTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("soulseek-cache-cleanup-");
        Path root = Files.createDirectories(home.resolve("transfers/opaque"));
        Path credentials = home.resolve("account.sealed.json");
        Files.write(credentials, new byte[] {1, 2, 3});
        Files.write(root.resolve("part.flac.part"), new byte[] {4, 5, 6});
        Path outside = Files.createTempDirectory("soulseek-cache-outside-");
        Path keep = outside.resolve("keep.txt");
        Files.write(keep, new byte[] {7, 8});
        Files.createSymbolicLink(root.resolve("outside"), outside);
        SoulseekCacheFiles.clear(home.resolve("transfers").toFile());
        check(!Files.exists(home.resolve("transfers")), "Private cache removed");
        check(
                Files.exists(credentials) && Files.readAllBytes(credentials).length == 3,
                "Encrypted account retained");
        check(Files.exists(keep), "Symbolic link target outside cache untouched");
        SoulseekCacheFiles.clear(home.resolve("transfers").toFile());
        check(Files.exists(credentials), "Clearing missing cache is idempotent");
        Files.createSymbolicLink(home.resolve("transfers"), outside);
        SoulseekCacheFiles.clear(home.resolve("transfers").toFile());
        check(
                !Files.isSymbolicLink(home.resolve("transfers")) && Files.exists(keep),
                "Root link removed without traversing target");
        Files.delete(credentials);
        Files.delete(home);
        Files.delete(keep);
        Files.delete(outside);
        System.out.println("PASS: " + checks + " Soulseek cache cleanup checks.");
    }
}
