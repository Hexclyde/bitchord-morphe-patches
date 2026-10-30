package app.bitchord.soulseek;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONArray;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class NostrIdentityStore {
    private static final int MAX_ENVELOPE_BYTES = 16 * 1024;
    private final AtomicFile file;
    private final String alias;

    static final class Settings {
        final String secret;
        final List<String> relays;
        final List<String> peers;

        Settings(String secret, List<String> relays, List<String> peers) {
            this.secret = secret;
            this.relays = Collections.unmodifiableList(new ArrayList<>(relays));
            this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
        }
    }

    NostrIdentityStore(Context context, File home) {
        file = new AtomicFile(new File(home, "identity.sealed.json"));
        alias = context.getPackageName() + ".soulseek.nostr";
    }

    synchronized Settings loadOrCreate() throws Exception {
        NostrNative.load();
        File base = file.getBaseFile();
        if (!base.exists() && !sibling(".bak").exists()) {
            Settings created =
                    validate(
                            NostrNative.generateSecret(),
                            Arrays.asList("wss://relay.damus.io", "wss://nos.lol"),
                            Collections.emptyList());
            save(created);
            return created;
        }
        JSONObject envelope = new JSONObject(new String(readBounded(), StandardCharsets.UTF_8));
        if (envelope.getInt("version") != 1)
            throw new IOException("Unsupported connection identity format.");
        byte[] iv = Base64.getDecoder().decode(envelope.getString("iv"));
        byte[] ciphertext = Base64.getDecoder().decode(envelope.getString("ciphertext"));
        if (iv.length != 12 || ciphertext.length < 16)
            throw new IOException("Invalid connection identity envelope.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(ciphertext);
        try {
            JSONObject settings = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            return validate(
                    settings.getString("secret"),
                    strings(settings.getJSONArray("relays"), 3),
                    strings(settings.getJSONArray("peers"), 16));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    synchronized void configure(List<String> relays, List<String> peers) throws Exception {
        Settings current = loadOrCreate();
        save(validate(current.secret, relays, peers));
    }

    synchronized void clear() throws Exception {
        file.delete();
        File pending = sibling(".new");
        if (pending.exists() && !pending.delete())
            throw new IOException("Could not remove pending connection identity.");
        if (file.getBaseFile().exists() || sibling(".bak").exists())
            throw new IOException("Could not remove connection identity.");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) store.deleteEntry(alias);
        if (store.containsAlias(alias))
            throw new IOException("Could not remove connection identity key.");
    }

    private Settings validate(String secret, List<String> relays, List<String> peers)
            throws Exception {
        if (secret == null || !secret.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Invalid connection identity.");
        if (relays == null || peers == null || relays.size() > 3 || peers.size() > 16)
            throw new IOException("Invalid connection settings.");
        secret = secret.toLowerCase(Locale.ROOT);
        NostrNative.publicKey(secret);
        List<String> validatedRelays = NostrConfig.relays(NostrConfig.lines(relays));
        List<String> validatedPeers =
                peers.isEmpty()
                        ? Collections.emptyList()
                        : NostrConfig.peers(NostrConfig.lines(peers));
        return new Settings(secret, validatedRelays, validatedPeers);
    }

    private void save(Settings settings) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] plain =
                new JSONObject()
                        .put("secret", settings.secret)
                        .put("relays", new JSONArray(settings.relays))
                        .put("peers", new JSONArray(settings.peers))
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] envelope =
                    new JSONObject()
                            .put("version", 1)
                            .put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                            .put(
                                    "ciphertext",
                                    Base64.getEncoder().encodeToString(cipher.doFinal(plain)))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
            if (envelope.length > MAX_ENVELOPE_BYTES)
                throw new IOException("Connection settings exceed the storage limit.");
            FileOutputStream output = file.startWrite();
            try {
                output.write(envelope);
                file.finishWrite(output);
            } catch (Exception e) {
                file.failWrite(output);
                throw e;
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private byte[] readBounded() throws IOException {
        try (FileInputStream input = file.openRead();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_ENVELOPE_BYTES)
                    throw new IOException("Connection identity exceeds the storage limit.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private File sibling(String suffix) {
        return new File(file.getBaseFile().getPath() + suffix);
    }

    private static List<String> strings(JSONArray values, int limit) throws Exception {
        if (values.length() > limit) throw new IOException("Too many connection settings.");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String)) throw new IOException("Invalid connection setting.");
            result.add((String) value);
        }
        return result;
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            if (!create) throw new IOException("Connection identity key is unavailable.");
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build());
            generator.generateKey();
        }
        Key saved = store.getKey(alias, null);
        if (!(saved instanceof SecretKey))
            throw new IOException("Connection identity key is unavailable.");
        return (SecretKey) saved;
    }
}
