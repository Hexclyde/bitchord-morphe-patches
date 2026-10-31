package app.bitchord.telegram;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Private sync credentials; encryption failure requires reconnecting, without a plaintext fallback.
 */
final class SyncSecrets {
    private final Context app;
    private final SharedPreferences prefs;

    SyncSecrets(Context app) {
        this.app = app.getApplicationContext();
        prefs = this.app.getSharedPreferences("bitchord_sync_secrets", 0);
    }

    private SecretKey key() throws Exception {
        String alias = app.getPackageName() + ".playlist-sync.v1";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(
                new KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
        return generator.generateKey();
    }

    synchronized String get(String name) throws Exception {
        String stored = prefs.getString(name, null);
        if (stored == null) return null;
        String[] parts = stored.split(":", -1);
        if (parts.length != 2)
            throw new IllegalStateException("Secure connection storage is unavailable");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(
                cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    synchronized void put(String name, String value) throws Exception {
        if (value == null) {
            if (!prefs.edit().remove(name).commit())
                throw new IllegalStateException("Could not save connection");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encoded =
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                        + ":"
                        + Base64.encodeToString(
                                cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),
                                Base64.NO_WRAP);
        if (!prefs.edit().putString(name, encoded).commit())
            throw new IllegalStateException("Could not save connection");
    }
}
