package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/** Download copies use an explicit, persisted choice; each job keeps its own snapshot. */
public final class DownloadDestinations {
    private static final String PREFERENCES = "bitchord-download-destinations";
    private static final String TELEGRAM_BRIDGE = "app.bitchord.telegram.DownloadUploadBridge";
    private static final Object PREFERENCE_LOCK = new Object();
    public static final String PHONE = "phone", TELEGRAM = "telegram", BOTH = "both";

    private DownloadDestinations() {}

    public static boolean ensureConfigured(Activity activity) {
        try {
            snapshot(activity);
            return true;
        } catch (IOException failure) {
            openSettings(activity);
            return false;
        }
    }

    public static void openSettings(Activity activity) {
        activity.startActivity(new Intent(activity, DownloadDestinationActivity.class));
    }

    /** Reads the fixed destination IDs; no Telegram network call occurs on the UI thread. */
    public static Snapshot snapshot(Context context) throws IOException {
        SharedPreferences preferences = preferences(context);
        if (!preferences.getBoolean("configured", false))
            throw new IOException("Choose where download copies go first");
        Snapshot result = stored(context);
        if (result.needsPhone()) verifyPhonePermission(context, result.phoneTreeUri);
        if (result.needsTelegram()) {
            try {
                Class.forName(TELEGRAM_BRIDGE, false, context.getClassLoader());
            } catch (ClassNotFoundException | LinkageError failure) {
                throw new IOException(
                        "Install or enable the Telegram integration to use this destination",
                        failure);
            }
        }
        return result;
    }

    static Snapshot stored(Context context) throws IOException {
        synchronized (PREFERENCE_LOCK) {
            SharedPreferences preferences = preferences(context);
            return new Snapshot(
                    preferences.getString("mode", ""),
                    preferences.getString("phoneTreeUri", ""),
                    preferences.getLong("telegramChatId", 0),
                    preferences.getLong("telegramUserId", 0),
                    preferences.getString("telegramTitle", ""));
        }
    }

    static void save(Context context, Snapshot snapshot) throws IOException {
        if (snapshot.needsPhone()) verifyPhoneFolder(context, snapshot.phoneTreeUri);
        if (snapshot.needsTelegram())
            validateTelegram(context, snapshot.telegramUserId, snapshot.telegramChatId);
        synchronized (PREFERENCE_LOCK) {
            if (!preferences(context)
                    .edit()
                    .putString("mode", snapshot.mode)
                    .putString("phoneTreeUri", snapshot.phoneTreeUri)
                    .putLong("telegramChatId", snapshot.telegramChatId)
                    .putLong("telegramUserId", snapshot.telegramUserId)
                    .putString("telegramTitle", snapshot.telegramTitle)
                    .putBoolean("configured", true)
                    .commit()) throw new IOException("Could not save download destinations");
        }
    }

    static JSONObject configuredTelegram(Context context) throws IOException {
        Object result =
                invokeTelegram(
                        context, "configuredDestination", new Class<?>[] {Context.class}, context);
        try {
            JSONObject destination = new JSONObject((String) result);
            if (destination.optLong("userId") <= 0 || destination.optLong("chatId") == 0)
                throw new IOException(
                        "Choose your private main channel in Telegram settings first");
            return destination;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Telegram destination is unavailable", failure);
        }
    }

    static void validateTelegram(Context context, long userId, long chatId) throws IOException {
        invokeTelegram(
                context,
                "validateDestination",
                new Class<?>[] {Context.class, long.class, long.class},
                context,
                userId,
                chatId);
    }

    static void openTelegramSetup(Activity activity) throws IOException {
        invokeTelegram(activity, "openSetup", new Class<?>[] {Activity.class}, activity);
    }

    private static Object invokeTelegram(
            Context context, String method, Class<?>[] parameters, Object... arguments)
            throws IOException {
        try {
            Class<?> bridge = Class.forName(TELEGRAM_BRIDGE, true, context.getClassLoader());
            return bridge.getMethod(method, parameters).invoke(null, arguments);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError failure) {
            throw new IOException(
                    "Install or enable the Telegram integration, then set up your private main"
                            + " channel",
                    failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            String message = cause == null ? null : cause.getMessage();
            throw new IOException(
                    message == null || message.length() == 0
                            ? "Telegram destination is unavailable"
                            : message,
                    cause == null ? failure : cause);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Telegram destination is unavailable", failure);
        }
    }

    static void verifyPhoneFolder(Context context, String uri) throws IOException {
        verifyPhonePermission(context, uri);
        try {
            Uri tree = Uri.parse(uri);
            Uri document =
                    DocumentsContract.buildDocumentUriUsingTree(
                            tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor =
                    context.getContentResolver()
                            .query(
                                    document,
                                    new String[] {
                                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                                        DocumentsContract.Document.COLUMN_FLAGS
                                    },
                                    null,
                                    null,
                                    null)) {
                if (cursor == null
                        || !cursor.moveToFirst()
                        || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))
                        || (cursor.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
                                == 0)
                    throw new IOException("Choose a folder that allows new download files");
            }
        } catch (RuntimeException failure) {
            throw new IOException(
                    "The download folder is no longer available; choose it again", failure);
        }
    }

    private static void verifyPhonePermission(Context context, String uri) throws IOException {
        try {
            Uri tree = Uri.parse(uri);
            if (!DocumentsContract.isTreeUri(tree))
                throw new IOException("Choose a download folder first");
            for (UriPermission permission :
                    context.getContentResolver().getPersistedUriPermissions())
                if (tree.equals(permission.getUri())
                        && permission.isReadPermission()
                        && permission.isWritePermission()) return;
        } catch (RuntimeException failure) {
            throw new IOException("Download folder permission unavailable", failure);
        }
        throw new IOException("Choose the download folder again to grant access");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static final class Snapshot {
        public final String mode;
        public final String phoneTreeUri;
        public final long telegramChatId;
        public final long telegramUserId;
        public final String telegramTitle;

        public Snapshot(
                String mode,
                String phoneTreeUri,
                long telegramChatId,
                long telegramUserId,
                String telegramTitle)
                throws IOException {
            if (!PHONE.equals(mode) && !TELEGRAM.equals(mode) && !BOTH.equals(mode))
                throw new IOException("Choose Phone, Telegram main channel, or Both");
            this.mode = mode;
            this.phoneTreeUri = phoneTreeUri == null ? "" : phoneTreeUri;
            this.telegramChatId = telegramChatId;
            this.telegramUserId = telegramUserId;
            this.telegramTitle = telegramTitle == null ? "" : telegramTitle;
            if (needsPhone() && this.phoneTreeUri.length() == 0)
                throw new IOException("Choose a download folder first");
            if (needsTelegram() && (telegramChatId == 0 || telegramUserId <= 0))
                throw new IOException("Choose your private Telegram main channel first");
        }

        public boolean needsPhone() {
            return PHONE.equals(mode) || BOTH.equals(mode);
        }

        public boolean needsTelegram() {
            return TELEGRAM.equals(mode) || BOTH.equals(mode);
        }
    }
}
