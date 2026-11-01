package app.bitchord.telegram;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class StorageSenseScannerTest {
    static int checks;

    static void check(boolean value, String text) {
        checks++;
        if (!value) throw new AssertionError(text);
    }

    static File write(File folder, String name, int size) throws IOException {
        File file = new File(folder, name);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[size]);
        return file;
    }

    static void delete(File file) throws IOException {
        if (Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }

    public static void main(String[] args) throws Exception {
        File home = Files.createTempDirectory("storage-scan").toFile();
        try {
            File root = new File(home, "root");
            File outside = new File(home, "outside");
            write(root, "a.flac", 10);
            write(root, "nested/b.mp3", 20);
            File secret = write(outside, "session.db", 500);
            Files.createSymbolicLink(new File(root, "escape").toPath(), outside.toPath());
            Files.createSymbolicLink(new File(root, "linked.db").toPath(), secret.toPath());

            StorageSenseScanner.Tree tree =
                    StorageSenseScanner.walk(root, 8, 100, true, StorageSense.NEVER);
            check(tree.bytes == 30 && tree.count == 2, "Links add nothing; real bytes are counted");
            check(!tree.truncated, "Nothing was cut short");
            for (StorageSenseScanner.Entry entry : tree.files)
                check(!entry.file.getPath().contains("escape"), "A link is never followed");

            check(
                    StorageSenseScanner.walk(root, 8, 1, false, StorageSense.NEVER).truncated,
                    "The entry limit stops the walk and says so");
            check(
                    StorageSenseScanner.walk(root, 1, 100, false, StorageSense.NEVER).bytes == 10,
                    "The depth limit holds");
            check(
                    StorageSenseScanner.walk(root, 8, 100, false, () -> true).cancelled,
                    "Cancelling stops the walk");
            check(
                    StorageSenseScanner.walk(
                                            new File(home, "absent"),
                                            8,
                                            100,
                                            false,
                                            StorageSense.NEVER)
                                    .count
                            == 0,
                    "A missing folder is empty");
            check(
                    StorageSenseScanner.walk(
                                            new File(root, "escape"),
                                            8,
                                            100,
                                            false,
                                            StorageSense.NEVER)
                                    .count
                            == 0,
                    "A root that's a link isn't walked");

            List<File> roots = Collections.singletonList(root);
            File inside = new File(root, "a.flac");
            check(StorageSenseScanner.prove(inside, roots) != null, "A file inside is proven");
            check(StorageSenseScanner.prove(root, roots) == null, "The root itself isn't");
            check(StorageSenseScanner.prove(secret, roots) == null, "Outside isn't");
            check(
                    StorageSenseScanner.prove(new File(root, "linked.db"), roots) == null,
                    "A link to outside isn't");
            check(
                    StorageSenseScanner.prove(new File(root, "escape/session.db"), roots) == null,
                    "Going through a link to outside isn't");
            check(
                    StorageSenseScanner.prove(new File(root, "../outside/session.db"), roots)
                            == null,
                    "Climbing out isn't");
            check(
                    StorageSenseScanner.prove(new File(root, "absent.flac"), roots) == null,
                    "A missing file isn't");
            check(
                    StorageSenseScanner.prove(inside, Arrays.asList((File) null, root)) != null,
                    "A missing root is skipped");

            File pack = new File(root, "pack");
            write(pack, "playlist.m3u8", 1);
            write(pack, "segment/1.ts", 2);
            Files.createSymbolicLink(new File(pack, "out").toPath(), outside.toPath());
            StorageSenseScanner.deleteTree(pack, roots);
            check(!pack.exists(), "A proven package is removed");
            check(secret.exists(), "What a link inside it pointed at is untouched");
            try {
                StorageSenseScanner.deleteTree(outside, roots);
                check(false, "Deleting outside the roots is refused");
            } catch (IOException expected) {
                checks++;
            }
            check(secret.exists(), "And it really is still there");

            byte[] flac = {'f', 'L', 'a', 'C', 0, 0, 0, 0, 0, 0, 0, 0};
            byte[] mp4 = {0, 0, 0, 32, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
            byte[] mpeg = {(byte) 0xff, (byte) 0xfb, 0, 0};
            byte[] webm = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 0};
            byte[] xml = {'<', '?', 'x', 'm', 'l', ' ', 'v', 'e', 'r', 's', 'i', 'o'};
            check(StorageSenseScanner.audioHead(flac, flac.length), "FLAC");
            check(StorageSenseScanner.audioHead(mp4, mp4.length), "MP4");
            check(StorageSenseScanner.audioHead(mpeg, mpeg.length), "MPEG");
            check(StorageSenseScanner.audioHead(webm, webm.length), "WebM");
            check(!StorageSenseScanner.audioHead(xml, xml.length), "A manifest isn't audio");
            check(!StorageSenseScanner.audioHead(new byte[0], 0), "Nor is nothing");
            File named = write(root, "notes.flac", 30);
            check(!StorageSenseScanner.looksLikeAudio(named), "A name proves nothing");

            String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            check(
                    empty.equals(
                            StorageSenseScanner.sha256(
                                    new ByteArrayInputStream(new byte[0]), StorageSense.NEVER)),
                    "Known digest");
            check(
                    StorageSenseScanner.sha256(new ByteArrayInputStream(new byte[9]), () -> true)
                            == null,
                    "A cancelled read proves nothing");
        } finally {
            delete(home);
        }
        System.out.println("StorageSenseScannerTest passed " + checks + " checks");
    }
}
