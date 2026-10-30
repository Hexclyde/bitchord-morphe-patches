package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class DebridCredentialsTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    interface Action {
        void run() throws Exception;
    }

    private static void rejects(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    static final class Memory implements DebridCredentials.Storage {
        byte[] saved;
        boolean fail;

        public byte[] read() {
            return saved == null ? null : saved.clone();
        }

        public void write(byte[] value) throws IOException {
            if (fail) throw new IOException("unavailable");
            saved = value.clone();
        }

        public void clear() {
            saved = null;
        }
    }

    private static DebridCredentials.Crypto crypto(String alias, int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        SecretKey key = new SecretKeySpec(bytes, "AES");
        return new DebridCredentials.AesCrypto(create -> key, alias);
    }

    public static void main(String[] args) throws Exception {
        Memory memory = new Memory();
        DebridCredentials.Crypto cipher = crypto("app.test.debrid", 7);
        DebridCredentials vault = new DebridCredentials(memory, cipher);
        for (DebridProvider.Kind kind : DebridProvider.Kind.values()) {
            DebridCredentials.Snapshot snapshot = vault.snapshot(kind);
            check(
                    !snapshot.enabled && !snapshot.configured,
                    "Each service starts off without a key");
            rejects(() -> vault.enabledKey(kind), "Disabled key cannot be used");
            rejects(() -> vault.setEnabled(kind, true), "Missing key cannot enable provider");
        }
        DebridProvider.Kind kind = DebridProvider.Kind.REAL_DEBRID;
        String key = "test-key-not-a-real-provider-secret-1";
        vault.save(kind, key, false);
        check(
                !vault.snapshot(kind).enabled && vault.snapshot(kind).configured,
                "Key-only save leaves provider disabled");
        check(
                !new String(memory.saved, StandardCharsets.UTF_8).contains(key),
                "Disk envelope contains no plaintext API key");
        rejects(() -> vault.enabledKey(kind), "Saved disabled key still cannot be used");
        check(
                vault.keyForValidation(kind).equals(key),
                "Explicit test may use configured disabled key");
        vault.setEnabled(kind, true);
        DebridCredentials.Credential first = vault.enabledSnapshot(kind);
        check(first.key.equals(key), "Enabled request receives exact configured key");
        vault.requireCurrent(kind, first.revision);
        checks++;
        DebridCredentials restored = new DebridCredentials(memory, cipher);
        check(
                restored.enabledKey(kind).equals(key),
                "Encrypted persistence restores exact API key");
        check(
                restored.snapshot(kind).revision.equals(first.revision),
                "Credential revision persists");
        AtomicInteger notifications = new AtomicInteger();
        AtomicBoolean unlocked = new AtomicBoolean();
        vault.addListener(
                changed -> {
                    notifications.incrementAndGet();
                    Thread reader =
                            new Thread(
                                    () -> {
                                        vault.snapshot(changed);
                                        unlocked.set(true);
                                    });
                    reader.start();
                    try {
                        reader.join(1000);
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                });
        vault.setEnabled(kind, false);
        check(
                notifications.get() == 1 && unlocked.get(),
                "Disable notifies consumers outside the vault lock");
        rejects(
                () -> vault.requireCurrent(kind, first.revision),
                "Disabling invalidates active request revision");
        vault.setEnabled(kind, true);
        check(
                !vault.snapshot(kind).revision.equals(first.revision),
                "Re-enable cannot resurrect an old job revision");
        DebridCredentials.Credential old = vault.enabledSnapshot(kind);
        vault.save(kind, "replacement-key-2", true);
        rejects(
                () -> vault.requireCurrent(kind, old.revision),
                "API-key rotation invalidates old-account work");
        String current = vault.snapshot(kind).revision;
        byte[] previous = memory.saved.clone();
        memory.fail = true;
        rejects(
                () -> vault.save(kind, "replacement-key-3", true),
                "Failed storage cannot silently accept new credentials");
        check(
                vault.snapshot(kind).revision.equals(current)
                        && Arrays.equals(memory.saved, previous),
                "Failed save preserves prior key and revision");
        memory.fail = false;
        rejects(
                () -> vault.save(kind, "bad key with spaces", true),
                "Spaces cannot enter Authorization header");
        rejects(() -> vault.save(kind, "bad\r\nheader", true), "Header injection rejected");
        rejects(
                () -> vault.save(kind, new String(new char[4097]).replace('\0', 'a'), true),
                "Oversized key rejected");
        vault.remove(kind);
        check(
                !vault.snapshot(kind).enabled && !vault.snapshot(kind).configured,
                "Removing key also disables provider");
        rejects(() -> vault.enabledKey(kind), "Removed key unavailable");
        vault.save(DebridProvider.Kind.TORBOX, "another-test-key-4", true);
        check(
                !vault.snapshot(kind).configured
                        && vault.snapshot(DebridProvider.Kind.TORBOX).enabled,
                "Providers retain independent toggles and keys");
        JSONObject envelope = new JSONObject(new String(memory.saved, StandardCharsets.UTF_8));
        byte[] body = java.util.Base64.getDecoder().decode(envelope.getString("ciphertext"));
        body[0] ^= 1;
        envelope.put("ciphertext", java.util.Base64.getEncoder().encodeToString(body));
        byte[] valid = memory.saved.clone();
        memory.saved = envelope.toString().getBytes(StandardCharsets.UTF_8);
        rejects(
                () -> new DebridCredentials(memory, cipher),
                "Authenticated ciphertext tampering rejected");
        memory.saved = valid;
        rejects(
                () -> new DebridCredentials(memory, crypto("different.app", 7)),
                "Application-bound authentication rejects copied envelope");
        rejects(
                () -> new DebridCredentials(memory, crypto("app.test.debrid", 8)),
                "Missing/replaced encryption key fails closed");
        JSONObject plain = new JSONObject().put("version", 1).put("providers", new JSONObject());
        memory.saved = cipher.seal(plain.toString().getBytes(StandardCharsets.UTF_8));
        rejects(
                () -> new DebridCredentials(memory, cipher),
                "Invalid provider inventory cannot become defaults");
        memory.saved = valid;
        vault.clearAll();
        check(memory.saved == null, "Clear removes sealed keys");
        for (DebridProvider.Kind value : DebridProvider.Kind.values())
            check(
                    !vault.snapshot(value).configured && !vault.snapshot(value).enabled,
                    "Clear disables every service");
        check(
                !new DebridCredentials(memory, cipher).snapshot(kind).configured,
                "Cleared settings reopen as explicitly unconfigured");
        System.out.println("PASS: " + checks + " protected debrid credential and toggle checks.");
    }
}
