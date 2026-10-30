package app.bitchord.soulseek;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Per-service opt-in credentials sealed outside Android backup storage. */
public final class DebridCredentials {
    private static final int MAX_SEALED_BYTES = 64 * 1024;
    private static final int MAX_KEY_CHARS = 4096;
    private static DebridCredentials instance;

    public interface Listener {
        void onChanged(DebridProvider.Kind kind);
    }

    interface Storage {
        byte[] read() throws IOException;

        void write(byte[] bytes) throws IOException;

        void clear() throws IOException;
    }

    interface KeyAccess {
        SecretKey key(boolean create) throws Exception;
    }

    interface Crypto {
        byte[] seal(byte[] bytes) throws Exception;

        byte[] open(byte[] bytes) throws Exception;
    }

    public static final class Snapshot {
        public final boolean enabled, configured;
        public final String revision;

        Snapshot(boolean enabled, boolean configured, String revision) {
            this.enabled = enabled;
            this.configured = configured;
            this.revision = revision;
        }
    }

    /** In-memory request credential. Never serialize this object or include it in a status. */
    static final class Credential {
        final String key, revision;

        Credential(String key, String revision) {
            this.key = key;
            this.revision = revision;
        }
    }

    private static final class Value {
        final String key, revision;
        final boolean enabled;

        Value(String key, boolean enabled, String revision) {
            this.key = key;
            this.enabled = enabled;
            this.revision = revision;
        }
    }

    private final Storage storage;
    private final Crypto crypto;
    private final List<Listener> listeners = new ArrayList<>();
    private EnumMap<DebridProvider.Kind, Value> values;

    public static synchronized DebridCredentials get(Context context) throws IOException {
        if (instance == null) {
            Context app = context.getApplicationContext();
            File home = new File(app.getNoBackupFilesDir(), "debrid-downloads");
            if (!home.isDirectory() && !home.mkdirs())
                throw new IOException("Cannot create protected debrid settings.");
            String alias = app.getPackageName() + ".downloads.debrid";
            instance =
                    new DebridCredentials(
                            new AtomicStorage(new File(home, "credentials.sealed.json")),
                            new AesCrypto(new AndroidKeys(alias), alias));
        }
        return instance;
    }

    DebridCredentials(Storage storage, Crypto crypto) throws IOException {
        this.storage = storage;
        this.crypto = crypto;
        load();
    }

    public synchronized Snapshot snapshot(DebridProvider.Kind kind) {
        Value value = values.get(kind);
        return new Snapshot(value.enabled, !value.key.isEmpty(), value.revision);
    }

    public synchronized String enabledKey(DebridProvider.Kind kind) throws IOException {
        return enabledSnapshot(kind).key;
    }

    synchronized String keyForValidation(DebridProvider.Kind kind) throws IOException {
        Value value = values.get(kind);
        if (value == null || value.key.isEmpty())
            throw new IOException("Enter an API key to test.");
        return value.key;
    }

    public static void clearSaved(Context context) throws IOException {
        DebridCredentials current;
        synchronized (DebridCredentials.class) {
            current = instance;
            if (current == null) {
                File home =
                        new File(
                                context.getApplicationContext().getNoBackupFilesDir(),
                                "debrid-downloads");
                new AtomicStorage(new File(home, "credentials.sealed.json")).clear();
                return;
            }
        }
        current.clearAll();
    }

    synchronized Credential enabledSnapshot(DebridProvider.Kind kind) throws IOException {
        Value value = values.get(kind);
        if (value == null || !value.enabled || value.key.isEmpty())
            throw new IOException("Enable this debrid service and save its API key first.");
        return new Credential(value.key, value.revision);
    }

    public synchronized void requireCurrent(DebridProvider.Kind kind, String revision)
            throws IOException {
        Credential current = enabledSnapshot(kind);
        if (revision == null || !current.revision.equals(revision))
            throw new IOException(
                    "Debrid settings changed. Resolve the link again with the current account.");
    }

    /** An empty replacement retains an existing key; it never enables an unconfigured service. */
    public void save(DebridProvider.Kind kind, String replacementKey, boolean enabled)
            throws IOException {
        synchronized (this) {
            if (kind == null) throw new IOException("Choose a debrid service.");
            Value old = values.get(kind);
            String next =
                    replacementKey == null || replacementKey.isEmpty()
                            ? old.key
                            : validateKey(replacementKey);
            if (enabled && next.isEmpty())
                throw new IOException("Enter this service's API key before enabling it.");
            if (old.enabled == enabled && old.key.equals(next)) return;
            EnumMap<DebridProvider.Kind, Value> changed = new EnumMap<>(values);
            changed.put(kind, new Value(next, enabled, revision()));
            persist(changed);
            values = changed;
        }
        notifyChanged(kind);
    }

    public void setEnabled(DebridProvider.Kind kind, boolean enabled) throws IOException {
        save(kind, "", enabled);
    }

    public void remove(DebridProvider.Kind kind) throws IOException {
        synchronized (this) {
            if (kind == null) throw new IOException("Choose a debrid service.");
            EnumMap<DebridProvider.Kind, Value> changed = new EnumMap<>(values);
            changed.put(kind, new Value("", false, revision()));
            persist(changed);
            values = changed;
        }
        notifyChanged(kind);
    }

    public void clearAll() throws IOException {
        synchronized (this) {
            storage.clear();
            values = defaults();
        }
        for (DebridProvider.Kind kind : DebridProvider.Kind.values()) notifyChanged(kind);
    }

    public synchronized void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged(DebridProvider.Kind kind) {
        List<Listener> copy;
        synchronized (this) {
            copy = new ArrayList<>(listeners);
        }
        for (Listener listener : copy) {
            try {
                listener.onChanged(kind);
            } catch (RuntimeException ignored) {
                /* Revision checks still invalidate every older request. */
            }
        }
    }

    static String validateKey(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > MAX_KEY_CHARS)
            throw new IOException("Enter a valid service API key.");
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 33 || value.charAt(i) > 126)
                throw new IOException("API keys cannot contain spaces or control characters.");
        return value;
    }

    private static String revision() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static EnumMap<DebridProvider.Kind, Value> defaults() {
        EnumMap<DebridProvider.Kind, Value> result = new EnumMap<>(DebridProvider.Kind.class);
        for (DebridProvider.Kind kind : DebridProvider.Kind.values())
            result.put(kind, new Value("", false, revision()));
        return result;
    }

    private void load() throws IOException {
        byte[] sealed = storage.read();
        if (sealed == null) {
            values = defaults();
            return;
        }
        if (sealed.length == 0 || sealed.length > MAX_SEALED_BYTES)
            throw new IOException("Protected debrid settings are damaged.");
        byte[] plain = null;
        try {
            plain = crypto.open(sealed);
            if (plain.length > 32 * 1024)
                throw new IOException("Protected debrid settings are too large.");
            JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (json.getInt("version") != 1)
                throw new IOException("Unsupported debrid settings version.");
            JSONObject providers = json.getJSONObject("providers");
            if (providers.length() != DebridProvider.Kind.values().length)
                throw new IOException("Invalid debrid provider settings.");
            EnumMap<DebridProvider.Kind, Value> loaded = new EnumMap<>(DebridProvider.Kind.class);
            for (DebridProvider.Kind kind : DebridProvider.Kind.values()) {
                JSONObject entry = providers.getJSONObject(kind.id);
                String key = entry.getString("key"), revision = entry.getString("revision");
                boolean enabled = entry.getBoolean("enabled");
                if (!key.isEmpty()) validateKey(key);
                if (!revision.matches("[0-9a-f]{32}") || (enabled && key.isEmpty()))
                    throw new IOException("Invalid debrid provider settings.");
                loaded.put(kind, new Value(key, enabled, revision));
            }
            values = loaded;
        } catch (Exception failure) {
            throw new IOException(
                    "Protected debrid keys could not be opened. Remove the saved keys and enter"
                            + " them again.");
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private void persist(EnumMap<DebridProvider.Kind, Value> changed) throws IOException {
        byte[] plain = null;
        try {
            JSONObject providers = new JSONObject();
            for (DebridProvider.Kind kind : DebridProvider.Kind.values()) {
                Value value = changed.get(kind);
                providers.put(
                        kind.id,
                        new JSONObject()
                                .put("key", value.key)
                                .put("enabled", value.enabled)
                                .put("revision", value.revision));
            }
            plain =
                    new JSONObject()
                            .put("version", 1)
                            .put("providers", providers)
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
            byte[] sealed = crypto.seal(plain);
            if (sealed.length > MAX_SEALED_BYTES)
                throw new IOException("Debrid keys exceed protected storage limits.");
            storage.write(sealed);
        } catch (Exception failure) {
            throw new IOException("The debrid settings could not be saved securely.");
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    static final class AesCrypto implements Crypto {
        final KeyAccess keys;
        final byte[] aad;

        AesCrypto(KeyAccess keys, String alias) {
            this.keys = keys;
            this.aad = alias.getBytes(StandardCharsets.UTF_8);
        }

        public byte[] seal(byte[] plain) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.key(true));
            cipher.updateAAD(aad);
            return new JSONObject()
                    .put("version", 1)
                    .put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                    .put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(plain)))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        }

        public byte[] open(byte[] sealed) throws Exception {
            JSONObject json = new JSONObject(new String(sealed, StandardCharsets.UTF_8));
            if (json.getInt("version") != 1)
                throw new IOException("Unsupported protected settings.");
            byte[] iv = Base64.getDecoder().decode(json.getString("iv")),
                    body = Base64.getDecoder().decode(json.getString("ciphertext"));
            if (iv.length != 12 || body.length < 16)
                throw new IOException("Invalid protected settings.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.key(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(body);
        }
    }

    private static final class AndroidKeys implements KeyAccess {
        final String alias;

        AndroidKeys(String alias) {
            this.alias = alias;
        }

        public SecretKey key(boolean create) throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias(alias)) {
                if (!create) throw new IOException("Debrid encryption key is unavailable.");
                KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
                generator.init(
                        new KeyGenParameterSpec.Builder(
                                        alias,
                                        KeyProperties.PURPOSE_ENCRYPT
                                                | KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .setRandomizedEncryptionRequired(true)
                                .build());
                generator.generateKey();
            }
            Key key = store.getKey(alias, null);
            if (!(key instanceof SecretKey))
                throw new IOException("Debrid encryption key is unavailable.");
            return (SecretKey) key;
        }
    }

    private static final class AtomicStorage implements Storage {
        final AtomicFile file;

        AtomicStorage(File path) {
            file = new AtomicFile(path);
        }

        public byte[] read() throws IOException {
            if (!file.getBaseFile().exists()
                    && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
            try (FileInputStream input = file.openRead();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_SEALED_BYTES)
                        throw new IOException("Protected debrid settings exceed their limit.");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        }

        public void write(byte[] bytes) throws IOException {
            FileOutputStream output = file.startWrite();
            try {
                output.write(bytes);
                file.finishWrite(output);
            } catch (IOException failure) {
                file.failWrite(output);
                throw failure;
            }
        }

        public void clear() throws IOException {
            file.delete();
            if (file.getBaseFile().exists()
                    || new File(file.getBaseFile().getPath() + ".bak").exists())
                throw new IOException("Saved debrid keys could not be removed.");
        }
    }
}
