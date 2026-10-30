import app.bitchord.soulseek.IpfsNative;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Host-JVM JNI smoke test. Uses two local nodes and never dials a public peer. */
public final class IpfsJniSmoke {
    private static String field(String text, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(text);
        if (!m.find()) throw new AssertionError(field + " missing");
        return m.group(1);
    }

    private static String addresses(String text) {
        Matcher m = Pattern.compile("\"listenAddresses\":(\\[[^\\]]+\\])").matcher(text);
        if (!m.find()) throw new AssertionError("listen addresses missing");
        return m.group(1);
    }

    private static void expectIo(RunnableIo action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
        }
    }

    private interface RunnableIo {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("bitchord-ipfs-jni-");
        IpfsNative.load();
        long a = IpfsNative.create(home.resolve("a").toString(), "[]");
        long b = IpfsNative.create(home.resolve("b").toString(), "[]");
        try {
            String ai = IpfsNative.info(a), bi = IpfsNative.info(b);
            String ap = field(ai, "peerId"), bp = field(bi, "peerId");
            IpfsNative.setPeers(a, "[\"" + bp + "\"]");
            IpfsNative.setPeers(b, "[\"" + ap + "\"]");
            Path source = home.resolve("selected");
            byte[] original = new byte[65539];
            for (int i = 0; i < original.length; i++) original[i] = (byte) (i * 17);
            Files.write(source, original);
            String publication = IpfsNative.publish(a, source.toString());
            String root = field(publication, "manifestCid"), cid = field(publication, "cid");
            IpfsNative.grantPublication(a, root, "[\"" + bp + "\"]");
            IpfsNative.connect(b, ap, addresses(ai), 5000);
            if (!Arrays.equals(original, IpfsNative.fetchBlock(b, ap, cid, 5000)))
                throw new AssertionError("JNI block mismatch");
            if (IpfsNative.fetchBlock(b, ap, root, 5000).length == 0)
                throw new AssertionError("JNI manifest missing");
            expectIo(() -> IpfsNative.fetchBlock(b, ap, "badcid", 1000));
            expectIo(() -> IpfsNative.create(home.resolve("a").toString(), "[]"));
            IpfsNative.unpublish(a, root);
            if (IpfsNative.clearPrivate(a) <= original.length)
                throw new AssertionError("JNI cache not reclaimed");
            IpfsNative.cancel(b);
        } finally {
            IpfsNative.close(a);
            IpfsNative.close(b);
        }
        expectIo(() -> IpfsNative.info(a));
        IpfsNative.close(a);
        try (java.util.stream.Stream<Path> paths = Files.walk(home)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }
        System.out.println(
                "IPFS JNI smoke passed: paired transfer, manifest, grants, cleanup, invalid"
                        + " handles");
    }
}
