package app.bitchord.telegram;

import android.content.Context;

import java.util.Locale;

/** Shared UI translations for optional download modules, using BitChord's chosen language. */
public final class PatchStrings {
    private PatchStrings() {}

    public static void refresh(Context context) {
        Strings.refresh(context);
    }

    public static String t(String english) {
        return Strings.t(english);
    }

    public static String f(String english, Object... values) {
        return Strings.f(english, values);
    }

    public static Locale locale() {
        return Strings.current();
    }

    public static String state(Object value) {
        String raw = value == null ? "" : value.toString();
        String label;
        switch (raw.toUpperCase(Locale.ROOT)) {
            case "QUEUED":
                label = "Queued";
                break;
            case "RUNNING":
            case "DOWNLOADING":
                label = "Running";
                break;
            case "PAUSED":
                label = "Paused";
                break;
            case "FAILED":
                label = "Failed";
                break;
            case "VERIFIED":
                label = "Verified";
                break;
            case "COMPLETE":
            case "COMPLETED":
                label = "Complete";
                break;
            case "CANCELLED":
                label = "Cancelled";
                break;
            case "READY":
                label = "Ready";
                break;
            case "PREPARING":
            case "VERIFYING":
                label = "Preparing";
                break;
            case "WAITING_PROVIDER":
                label = "Waiting for provider";
                break;
            case "PUBLISHING":
            case "COPYING":
            case "HANDED_OFF":
                label = "Copying";
                break;
            case "CONFIGURATION_CHANGED":
                label = "Configuration changed";
                break;
            case "RECEIPT-CHECK-REQUIRED":
                label = "Waiting for receipt";
                break;
            default:
                label = raw;
        }
        return t(label);
    }

    public static boolean rtl() {
        return Strings.rightToLeft();
    }
}
