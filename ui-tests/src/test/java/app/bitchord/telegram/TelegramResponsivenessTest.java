package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;

import org.drinkless.tdlib.TdApi;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class TelegramResponsivenessTest {
    static CountDownLatch opening, opened;
    ActivityController<TelegramActivity> owner;

    @Implements(value = TelegramBridge.class, isInAndroidSdk = false)
    public static class SlowStartup {
        @Implementation
        public static void init(Context context) {
            opening.countDown();
            try {
                opened.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                throw new AssertionError(stopped);
            }
            TelegramBridge.instance = new TelegramBridge();
        }
    }

    @Before
    public void setup() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS");
        TelegramBridge.instance = null;
        TelegramBridge.enforceBackgroundStatus = true;
    }

    @After
    public void close() {
        if (opened != null) opened.countDown();
        if (TelegramBridge.indexStatusRelease != null)
            TelegramBridge.indexStatusRelease.countDown();
        if (owner != null) {
            owner.get().handler.removeCallbacksAndMessages(null);
            owner.pause().stop().destroy();
        }
        if (TelegramBridge.instance != null) TelegramBridge.instance.work.shutdownNow();
        TelegramBridge.instance = null;
        TelegramBridge.enforceBackgroundStatus = false;
        TelegramBridge.indexStatusEntered = TelegramBridge.indexStatusRelease = null;
    }

    @Test
    @Config(shadows = SlowStartup.class)
    public void pageOpensBeforeTheNativeStartupTaskFinishes() throws Exception {
        opening = new CountDownLatch(1);
        opened = new CountDownLatch(1);
        owner = Robolectric.buildActivity(TelegramActivity.class).setup();
        assertTrue(opening.await(2, TimeUnit.SECONDS));
        assertNotNull(TelegramStartupTest.text(owner.get().body, "Opening Telegram…"));
        assertFalse(owner.get().preparation.isDone());
        assertFalse(owner.get().isFinishing());
        opened.countDown();
        owner.get().preparation.get(3, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(owner.get().bridge);
    }

    @Test
    public void aBusyLargeIndexCannotBlockRenderingOrScrolling() throws Exception {
        TelegramBridge.init(RuntimeEnvironment.getApplication());
        TelegramBridge.instance.auth = new TdApi.AuthorizationStateReady();
        TelegramBridge.instance.credentials =
                ApplicationCredentials.parse("12345", "0123456789abcdef0123456789abcdef");
        TelegramBridge.indexStatusEntered = new CountDownLatch(1);
        TelegramBridge.indexStatusRelease = new CountDownLatch(1);
        owner = Robolectric.buildActivity(TelegramActivity.class).setup();
        TelegramActivity page = owner.get();
        page.preparation.get(3, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(TelegramBridge.indexStatusEntered.await(2, TimeUnit.SECONDS));
        assertNotNull(TelegramStartupTest.text(page.body, "Saved index"));
        assertNotNull(TelegramStartupTest.text(page.body, "Reading saved index…"));
        page.scroll.scrollTo(0, 100);
        page.render();
        assertFalse(page.isFinishing());
        TelegramBridge.indexStatusRelease.countDown();
        page.pageWork.submit(() -> {}).get(3, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(TelegramStartupTest.text(page.body, "12 files indexed from 1 chat"));
    }
}
