package app.bitchord.soulseek;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Account secrets stay in no-backup storage, encrypted with a device Keystore key. */
final class SoulseekCredentialStore {
    private final AtomicFile file;
    private final String alias;

    SoulseekCredentialStore(Context context, File home) {
        file = new AtomicFile(new File(home, "account.sealed.json"));
        alias = context.getPackageName() + ".soulseek.account";
    }

    synchronized String[] load() throws Exception {
        if (!file.getBaseFile().exists()) return null;
        byte[] envelope = file.readFully();
        JSONObject saved = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
        if (saved.getInt("version") != 1) throw new Exception("Unsupported account format");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                new GCMParameterSpec(128, Base64.getDecoder().decode(saved.getString("iv"))));
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(saved.getString("ciphertext")));
        try {
            JSONObject account = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            return new String[] {account.getString("username"), account.getString("password")};
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    synchronized void save(String username, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] plain =
                new JSONObject()
                        .put("username", username)
                        .put("password", password)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] bytes =
                    new JSONObject()
                            .put("version", 1)
                            .put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                            .put(
                                    "ciphertext",
                                    Base64.getEncoder().encodeToString(cipher.doFinal(plain)))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
            FileOutputStream out = file.startWrite();
            try {
                out.write(bytes);
                file.finishWrite(out);
            } catch (Exception e) {
                file.failWrite(out);
                throw e;
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    synchronized void clear() throws Exception {
        file.delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) store.deleteEntry(alias);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
}
