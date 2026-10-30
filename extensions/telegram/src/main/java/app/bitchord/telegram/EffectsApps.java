package app.bitchord.telegram;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.audiofx.AudioEffect;
import android.net.Uri;

import java.lang.reflect.Field;

/**
 * JamesDSP: BitChord's audio session is handed to JamesDSP, the open-source audio effect engine, so
 * its equaliser, convolver, crossfeed and the rest work on BitChord's music. Works with the
 * rootless app (which hears BitChord through Android's audio capture) and with the root or Magisk
 * version. BitChord already announces its audio session to effect apps; this makes sure JamesDSP
 * sees it, opens JamesDSP from BitChord, and can step BitChord's own effects aside.
 */
final class EffectsApps {
    private EffectsApps() {}

    static final String PREFERENCES = "bitchord_effects_apps";
    static final String ROOTLESS = "me.timschneeberger.rootlessjamesdsp";
    static final String ROOT = "james.dsp";
    static final String GET_IT =
            "https://f-droid.org/packages/me.timschneeberger.rootlessjamesdsp/";

    private static volatile SharedPreferences preferences;

    /** With this on BitChord's equaliser is left to JamesDSP and Battery saver stays off. */
    static volatile boolean useJamesDsp;

    static void init(Context context) {
        if (preferences != null || context == null) return;
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        useJamesDsp = preferences.getBoolean("useJamesDsp", false);
    }

    static void setUseJamesDsp(boolean on) {
        useJamesDsp = on;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("useJamesDsp", on).apply();
    }

    /** The JamesDSP package that's installed, or null. */
    static String installed(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String name : new String[] {ROOTLESS, ROOT})
            try {
                pm.getPackageInfo(name, 0);
                return name;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not this one.
            }
        return null;
    }

    static String describe(String installedPackage) {
        if (installedPackage == null) return Strings.t("JamesDSP isn’t installed");
        return ROOT.equals(installedPackage)
                ? Strings.t("JamesDSP (root) is installed")
                : Strings.t("JamesDSP (rootless) is installed");
    }

    /** Opens JamesDSP, or where to get it. */
    static void open(Context context) {
        String name = installed(context);
        Intent intent =
                name == null ? null : context.getPackageManager().getLaunchIntentForPackage(name);
        if (intent == null) intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GET_IT));
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            HostLog.d("JamesDSP couldn't be opened: " + e);
        }
    }

    /** BitChord's audio session id, as its service published it; 0 when nothing has played. */
    static int sessionId() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.data.settings.AppSettings");
            Field field = type.getDeclaredField("audioSessionId");
            field.setAccessible(true);
            Object flow = field.get(null);
            Object value = flow == null ? null : flow.getClass().getMethod("getValue").invoke(flow);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Exception | LinkageError e) {
            return 0;
        }
    }

    /**
     * Tells effect apps about BitChord's audio session again, as BitChord does when playback
     * starts. For JamesDSP installed while a song was already playing.
     */
    static boolean announce(Context context) {
        int session = sessionId();
        if (session <= 0) return false;
        Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        context.sendBroadcast(intent);
        HostLog.d("Announced audio session " + session + " to effect apps");
        return true;
    }

    /** Turns BitChord's own equaliser off, so the sound isn't equalised twice. */
    static boolean hostEqualiserOff() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.data.settings.AppSettings");
            Object settings = type.getDeclaredField("INSTANCE").get(null);
            type.getMethod("setEqualizerEnabled", boolean.class).invoke(settings, false);
            return true;
        } catch (Exception | LinkageError e) {
            HostLog.d("Couldn't turn BitChord's equaliser off: " + e);
            return false;
        }
    }

    static boolean hostEqualiserOn(Context context) {
        return context.getSharedPreferences(OutputEq.HOST_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("equalizer_enabled", false);
    }
}
