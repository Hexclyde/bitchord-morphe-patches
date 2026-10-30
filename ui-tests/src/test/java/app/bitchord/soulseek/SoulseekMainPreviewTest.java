package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.LocaleList;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Internal visual QA using dummy accounts; run separately from behavioral tests. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SoulseekMainPreviewTest {
    @Test
    public void signedInOverview() throws Exception {
        render(true, "soulseek-main-ready.png");
    }

    @Test
    public void signedOutOverview() throws Exception {
        render(false, "soulseek-main-sign-in.png");
    }

    private void render(boolean signedIn, String filename) throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        app.getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        SoulseekBridge bridge = SoulseekBridge.reset(app);
        DownloadSettings.reset();
        bridge.username = signedIn ? "demo-listener" : "";
        bridge.connected = signedIn;
        bridge.status = signedIn ? "Connected" : "Not signed in";
        bridge.credentials.saved =
                signedIn ? new String[] {"demo-listener", "dummy-not-a-real-password"} : null;
        DownloadDestinations.configured = signedIn;
        DownloadDestinations.mode = DownloadDestinations.PHONE;
        if (signedIn) AudioFormatPreferences.save(app, AudioFormatPreferences.defaults());
        else app.getSharedPreferences("bitchord_audio_formats", 0).edit().clear().commit();
        ActivityController<SoulseekActivity> controller =
                Robolectric.buildActivity(SoulseekActivity.class).setup();
        try {
            SoulseekActivity activity = controller.get();
            Field field = SoulseekActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(activity);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            do {
                shadowOf(Looper.getMainLooper()).idle();
                if (worker.getActiveCount() == 0 && worker.getQueue().isEmpty()) {
                    shadowOf(Looper.getMainLooper()).idle();
                    break;
                }
                Thread.sleep(5);
            } while (System.nanoTime() < end);
            View decor = activity.getWindow().getDecorView();
            decor.measure(
                    View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 393, 852);
            Bitmap bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bitmap));
            File directory = new File("../build/ui-previews");
            directory.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(directory, filename))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        } finally {
            controller.pause().stop().destroy();
            bridge.work.shutdownNow();
            Locale.setDefault(before);
        }
    }
}
