package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.music.bitchord.data.model.Song;
import com.music.bitchord.data.settings.DownloadQuality;
import com.music.bitchord.download.Downloads;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;

/**
 * The Patches & tweaks page with every feature installed, the download-quality question, and the
 * sleep fade driven against a stand-in player.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-mdpi",
        shadows = ExtrasPageTest.EverythingInstalled.class,
        instrumentedPackages = "app.bitchord.telegram.SoundExtras")
public class ExtrasPageTest {
    /** Every feature the page knows about, as if each patch were applied. */
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class EverythingInstalled {
        @Implementation
        protected static boolean singApplied() {
            return true;
        }

        @Implementation
        protected static boolean pitchApplied() {
            return true;
        }

        @Implementation
        protected static boolean loudnessApplied() {
            return true;
        }

        @Implementation
        protected static boolean sleepFadeApplied() {
            return true;
        }

        @Implementation
        protected static boolean batterySaverApplied() {
            return true;
        }

        @Implementation
        protected static boolean losslessBluetoothApplied() {
            return true;
        }

        @Implementation
        protected static boolean downloadQualityApplied() {
            return true;
        }

        @Implementation
        protected static boolean headphoneApplied() {
            return true;
        }

        @Implementation
        protected static boolean outputEqApplied() {
            return true;
        }

        @Implementation
        protected static boolean djApplied() {
            return true;
        }

        @Implementation
        protected static boolean jamesDspApplied() {
            return true;
        }
    }

    ExtrasActivity activity;

    @Before
    public void setup() {
        activity = Robolectric.buildActivity(ExtrasActivity.class).setup().visible().get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void reset() {
        SoundExtras.setSleepFade(false);
        SoundExtras.setSleepFadeSeconds(SoundExtras.FADE_SECONDS);
        SoundExtras.setLosslessBluetoothEnabled(false);
        SoundExtras.setLosslessOverBluetooth(false);
        DjMode.setEnabled(false);
        DownloadChoice.setAsk(false);
        SleepFade.cancel();
    }

    static void collect(View view, List<View> out) {
        out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    List<View> views() {
        List<View> all = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), all);
        return all;
    }

    List<String> texts() {
        List<String> found = new ArrayList<>();
        for (View view : views())
            if (view instanceof TextView) found.add(((TextView) view).getText().toString());
        return found;
    }

    View described(String description) {
        for (View view : views())
            if (description.contentEquals(
                    view.getContentDescription() == null ? "" : view.getContentDescription()))
                return view;
        fail("no view described as " + description);
        return null;
    }

    TelegramSwitch enable(String id) {
        View view = activity.findViewById(android.R.id.content).findViewWithTag("enable:" + id);
        assertNotNull("enable switch for " + id, view);
        return (TelegramSwitch) view;
    }

    void click(String text) {
        for (View view : views())
            if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
                View target = view;
                while (!target.isClickable() && target.getParent() instanceof View)
                    target = (View) target.getParent();
                target.performClick();
                return;
            }
        fail("no " + text);
    }

    @Test
    public void everyFeatureShowsAndTheTogglesWork() {
        List<String> texts = texts();
        assertTrue(texts.contains("Patches & tweaks"));
        for (String title :
                new String[] {
                    "Fade out at the sleep timer",
                    "Battery saver",
                    "Lossless addons over Bluetooth",
                    "Headphone correction",
                    "Equaliser per output",
                    "DJ mode",
                    "JamesDSP",
                    "Ask the quality for each download"
                }) assertTrue(title, texts.contains(title));
        assertTrue(texts.contains("JamesDSP isn’t installed"));
        assertTrue(texts.contains("No profile for this output yet."));
        assertTrue(texts.contains("Connect a Bluetooth output and it will be listed here."));

        // The new features start switched off, and their switches turn them on.
        assertFalse(SoundExtras.sleepFade);
        enable("sleep-fade").toggle();
        assertTrue(SoundExtras.sleepFade);
        enable("sleep-fade").toggle();
        assertFalse(SoundExtras.sleepFade);

        assertFalse(DjMode.enabled);
        enable("dj-mode").toggle();
        assertTrue(DjMode.enabled);

        assertFalse(HeadphoneEq.enabled);
        enable("headphone-correction").toggle();
        assertTrue(HeadphoneEq.enabled);
        enable("headphone-correction").toggle();

        // Lossless over Bluetooth needs both its switch and an output allowed.
        ((TelegramSwitch) described("All Bluetooth outputs")).toggle();
        assertFalse(SoundExtras.losslessAnywhere());
        enable("lossless-bluetooth").toggle();
        assertTrue(SoundExtras.losslessAnywhere());
        ((TelegramSwitch) described("All Bluetooth outputs")).toggle();
        assertFalse(SoundExtras.losslessAnywhere());

        assertFalse(DownloadChoice.ask);
        enable("download-quality").toggle();
        assertTrue(DownloadChoice.ask);

        // The equaliser code lands on the clipboard.
        click("Copy code");
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(clipboard.getPrimaryClip());
        String code = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(code, code.startsWith(OutputEq.CODE_PREFIX));
        assertNotNull(OutputEq.Snapshot.fromCode(code));
    }

    @Test
    public void downloadsAreAskedAboutOnceAndCarryTheChoice() {
        DownloadChoice.init(activity);
        DownloadChoice.setAsk(true);
        Downloads.INSTANCE.queued.clear();
        Song one = new Song("vid1", "Song one", "Band", "3:00");
        Song two = new Song("vid2", "Song two", "Band", "3:10");
        assertTrue(DownloadChoice.hold(activity, one, "Album"));
        assertTrue(DownloadChoice.hold(activity, two, "Album"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        List<View> inDialog = new ArrayList<>();
        collect(dialog.getWindow().getDecorView(), inDialog);
        boolean heading = false;
        View lossless = null;
        for (View view : inDialog) {
            if (view instanceof TextView
                    && "Download 2 songs".contentEquals(((TextView) view).getText()))
                heading = true;
            if ("Lossless"
                    .contentEquals(
                            view.getContentDescription() == null
                                    ? ""
                                    : view.getContentDescription())) lossless = view;
        }
        assertTrue("asked once for both", heading);
        assertNotNull(lossless);
        lossless.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertEquals(Arrays.asList("Album: vid1", "Album: vid2"), Downloads.INSTANCE.queued);
        // Queued again by the patch: not held a second time, and prepared in the chosen quality.
        assertFalse(DownloadChoice.hold(activity, one, "Album"));
        assertEquals(DownloadQuality.LOSSLESS, DownloadChoice.quality(one, DownloadQuality.HIGH));
        assertEquals(DownloadQuality.HIGH, DownloadChoice.quality(one, DownloadQuality.HIGH));
        assertEquals(
                DownloadQuality.LOSSLESS, DownloadChoice.quality(two, DownloadQuality.STANDARD));
        assertEquals("LOSSLESS", DownloadChoice.last);

        // Asking off: nothing is held.
        DownloadChoice.setAsk(false);
        assertFalse(DownloadChoice.hold(activity, one, null));
        // No activity to ask in: nothing is held either.
        DownloadChoice.setAsk(true);
        assertFalse(DownloadChoice.hold(activity.getApplicationContext(), one, null));
    }

    /** A player that only knows its volume and whether it plays. */
    static final class FakePlayer implements java.lang.reflect.InvocationHandler {
        float volume = 1f;
        boolean playing = true;
        int pauses;
        long position = 60_000;
        final List<Float> volumes = new ArrayList<>();
        final Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "getVolume":
                    return volume;
                case "setVolume":
                    volume = (Float) args[0];
                    volumes.add(volume);
                    return null;
                case "isPlaying":
                case "getPlayWhenReady":
                    return playing;
                case "pause":
                    playing = false;
                    pauses++;
                    return null;
                case "getCurrentPosition":
                    return position;
                case "getDuration":
                    return 180_000L;
                case "getApplicationLooper":
                    return Looper.getMainLooper();
                case "getCurrentMediaItem":
                    return MediaItem.fromUri("test://song");
                case "getCurrentMediaItemIndex":
                    return 0;
                case "getPlaybackState":
                    return Player.STATE_READY;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "FakePlayer";
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
            }
        }
    }

    @Test
    public void theSleepFadeGoesDownThenPausesAndPutsTheVolumeBack() {
        SoundExtras.setSleepFade(true);
        SoundExtras.setSleepFadeSeconds(3);
        FakePlayer fake = new FakePlayer();
        fake.volume = 0.8f;
        SoundExtras.sleepPause(fake.player);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500));
        assertEquals(0, fake.pauses);
        assertTrue("part way down: " + fake.volume, fake.volume > 0.05f && fake.volume < 0.6f);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000));
        assertEquals(1, fake.pauses);
        assertEquals(0.8f, fake.volume, 1e-6);
        float last = 1f;
        boolean down = true;
        for (int i = 0; i < fake.volumes.size() - 1; i++) {
            down &= fake.volumes.get(i) <= last;
            last = fake.volumes.get(i);
        }
        assertTrue("only ever quieter until the pause", down);
        assertTrue("reached near silence", fake.volumes.get(fake.volumes.size() - 2) < 0.01f);
        assertFalse(SleepFade.active());
    }

    @Test
    public void theFadeStandsDownForOthers() {
        SoundExtras.setSleepFade(true);
        SoundExtras.setSleepFadeSeconds(4);
        // Someone else moves the volume: the fade stops and leaves it to them.
        FakePlayer fake = new FakePlayer();
        SoundExtras.sleepPause(fake.player);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000));
        assertTrue(SleepFade.active());
        fake.volume = 0.3f;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertFalse(SleepFade.active());
        assertEquals(0.3f, fake.volume, 1e-6);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000));
        assertEquals(0, fake.pauses);

        // The user pauses: the fade stops and the volume goes back up.
        FakePlayer paused = new FakePlayer();
        SoundExtras.sleepPause(paused.player);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000));
        paused.playing = false;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertFalse(SleepFade.active());
        assertEquals(1f, paused.volume, 1e-6);
        assertEquals(0, paused.pauses);

        // A seek backwards means someone is listening: the fade stops and the volume goes back.
        FakePlayer sought = new FakePlayer();
        SoundExtras.sleepPause(sought.player);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000));
        sought.position = 10_000;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertFalse(SleepFade.active());
        assertEquals(1f, sought.volume, 1e-6);

        // The fade switched off: a plain pause, at once.
        SoundExtras.setSleepFade(false);
        FakePlayer plain = new FakePlayer();
        SoundExtras.sleepPause(plain.player);
        assertEquals(1, plain.pauses);
        assertTrue(plain.volumes.isEmpty());

        // "Sleep after this song": the end fades and stays silent, without a pause of its own.
        SoundExtras.setSleepFade(true);
        FakePlayer ending = new FakePlayer();
        SleepFade.fadeEnding(ending.player, 2);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_600));
        assertEquals(0, ending.pauses);
        assertEquals(0f, ending.volume, 1e-6);
        ending.playing = false;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals(1f, ending.volume, 1e-6);
        assertFalse(SleepFade.active());
    }

    @Test
    public void losslessOverBluetoothFollowsTheOutput() {
        assertFalse(SoundExtras.losslessAnywhere());
        SoundExtras.setLosslessBluetoothEnabled(true);
        SoundExtras.setLosslessDevice("bt:AA:BB", true);
        // The phone's speaker is the output here, so a device list doesn't apply.
        assertFalse(SoundExtras.losslessAnywhere());
        SoundExtras.setLosslessOverBluetooth(true);
        assertTrue(SoundExtras.losslessAnywhere());
        SoundExtras.setLosslessDevice("bt:AA:BB", false);
        assertFalse(SoundExtras.losslessDevices.contains("bt:AA:BB"));
    }
}
