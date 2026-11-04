package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.compose.foundation.layout.BoxChildDataElement;
import androidx.compose.foundation.layout.OffsetKt;
import androidx.compose.runtime.ComposerImpl;
import androidx.compose.ui.Alignment;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.BlurKt;

import com.music.bitchord.ui.components.OptimizedHazeKt;

import dev.chrisbanes.haze.HazeState;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The Karaoke capsule on the lyrics: the marker gate, what a tap does to the shared settings, the
 * slider's two-way level, fading and its touch target, auto-close, accessibility and lifecycle.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-mdpi",
        shadows = KaraokeLyricsTest.Marker.class,
        instrumentedPackages = "app.bitchord.telegram.SoundExtras")
@LooperMode(LooperMode.Mode.PAUSED)
public class KaraokeLyricsTest {
    /** The Karaoke patch turns the marker on; here it is switched by hand. */
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class Marker {
        static boolean installed = true;

        @Implementation
        protected static boolean singApplied() {
            return installed;
        }
    }

    /** What the patch hands the extension, spelled for the stand-ins in this test tree. */
    static final String SYMBOLS =
            String.join(
                    "\n",
                    "androidView=Landroidx/compose/ui/viewinterop/AndroidView_androidKt;->AndroidView(Lkotlin/jvm/functions/Function1;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/ComposerImpl;II)V",
                    "padding=Landroidx/compose/foundation/layout/OffsetKt;->padding(Landroidx/compose/ui/Modifier;FFFFI)Landroidx/compose/ui/Modifier;",
                    "companion=Landroidx/compose/ui/Modifier$Companion;->$$INSTANCE:Landroidx/compose/ui/Modifier$Companion;",
                    "alignment=Landroidx/compose/ui/Alignment$Companion;->BottomEnd:Landroidx/compose/ui/Alignment;",
                    "childData=Landroidx/compose/foundation/layout/BoxChildDataElement;-><init>(Landroidx/compose/ui/Alignment;Z)V",
                    "tint=Lcom/music/bitchord/ui/player/PlayerControlsKt;->LocalPlayerTint:Landroidx/compose/runtime/DynamicProvidableCompositionLocal;",
                    "consume=Landroidx/compose/runtime/ComposerImpl;->consume(Landroidx/compose/runtime/ProvidableCompositionLocal;)Ljava/lang/Object;",
                    "picking=Lapp/bitchord/telegram/KaraokeLyricsTest$Picker;->getPicking()Z",
                    "startGroup=Landroidx/compose/runtime/ComposerImpl;->startReplaceGroup(I)V",
                    "endGroup=Landroidx/compose/runtime/ComposerImpl;->endReplaceGroup()V",
                    "shape=Landroidx/compose/foundation/shape/RoundedCornerShapeKt;->RoundedCornerShape(F)Landroidx/compose/foundation/shape/RoundedCornerShape;",
                    "clip=Landroidx/compose/ui/draw/BlurKt;->clip(Landroidx/compose/ui/Modifier;Landroidx/compose/ui/graphics/Shape;)Landroidx/compose/ui/Modifier;",
                    "hazeEffect=Lcom/music/bitchord/ui/components/OptimizedHazeKt;->optimizedHazeEffect(Landroidx/compose/ui/Modifier;Ldev/chrisbanes/haze/HazeState;Ldev/chrisbanes/haze/HazeStyle;Lkotlin/jvm/functions/Function1;)Landroidx/compose/ui/Modifier;",
                    "frost=Lcom/music/bitchord/ui/components/OptimizedHazeKt;->menuFrost(FJ)Ldev/chrisbanes/haze/HazeStyle;");

    ActivityController<Activity> owner;
    FrameLayout content;
    ComposerImpl composer;
    Context context;

    @Before
    public void setup() throws Exception {
        Marker.installed = true;
        owner = Robolectric.buildActivity(Activity.class).setup();
        context = owner.get();
        content = new FrameLayout(context);
        owner.get().setContentView(content);
        composer = new ComposerImpl(owner.get(), content);
        KaraokeLyrics.forTest(SYMBOLS);
        forgetListeners();
        prefs(null);
        SoundExtras.singEnabled = false;
        SoundExtras.singMethod = VocalCut.VOICE_GATE;
        SoundExtras.singLevel = 50;
        SeparationModel.forTest(null, false);
        ValueAnimator.setDurationScale(0f);
        touchMode();
    }

    /** A finger is on the screen, so closing the slider does not hand focus to the microphone. */
    private void touchMode() {
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(
                content.getViewRootImpl(),
                "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true));
    }

    @After
    public void restore() throws Exception {
        settle();
        ValueAnimator.setDurationScale(1f);
        SoundExtras.singEnabled = false;
        SeparationModel.forTest(null, false);
    }

    private static void prefs(SharedPreferences saved) {
        try {
            java.lang.reflect.Field field = SoundExtras.class.getDeclaredField("preferences");
            field.setAccessible(true);
            field.set(null, saved);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Waits for the separation model's worker, which has no application to load from here. */
    private static void settle() throws Exception {
        java.lang.reflect.Field pending =
                SeparationModel.class.getDeclaredField("reconcilePending");
        pending.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean flag =
                (java.util.concurrent.atomic.AtomicBoolean) pending.get(null);
        long end = System.nanoTime() + 5_000_000_000L;
        while (flag.get() && System.nanoTime() < end) Thread.sleep(5);
        assertFalse("the model worker finished", flag.get());
    }

    private KaraokeCapsule embed() {
        KaraokeLyrics.portrait(composer, true);
        return (KaraokeCapsule) composer.view;
    }

    private void lay(KaraokeCapsule capsule, int availableDp) {
        float dp = context.getResources().getDisplayMetrics().density;
        capsule.measure(
                View.MeasureSpec.makeMeasureSpec(Math.round(400 * dp), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(
                        Math.round(availableDp * dp), View.MeasureSpec.AT_MOST));
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
    }

    private int px(float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static void idle(long ms) {
        ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private List<Modifier> chain(Modifier modifier) {
        List<Modifier> parts = new ArrayList<>();
        if (modifier instanceof Modifier.Chain) {
            parts.addAll(chain(((Modifier.Chain) modifier).base));
            parts.addAll(chain(((Modifier.Chain) modifier).next));
        } else parts.add(modifier);
        return parts;
    }

    private <T> T find(Modifier modifier, Class<T> type) {
        for (Modifier part : chain(modifier)) if (type.isInstance(part)) return type.cast(part);
        return null;
    }

    // ── The marker gate and rendering ────────────────────────────────────────

    @Test
    public void withoutTheMarkerNothingIsAddedToTheLyrics() {
        Marker.installed = false;
        KaraokeLyrics.portrait(composer, true);
        KaraokeLyrics.landscape(composer);
        assertNull(composer.view);
        assertEquals(0, content.getChildCount());
    }

    @Test
    public void withTheMarkerOneCapsuleSitsAtTheBottomEndAboveTheTranslationButton() {
        KaraokeCapsule capsule = embed();
        assertNotNull(capsule);
        assertEquals(1, content.getChildCount());
        BoxChildDataElement place = find(composer.modifier, BoxChildDataElement.class);
        assertSame(Alignment.Companion.BottomEnd, place.alignment);
        assertFalse(place.matchParentSize);
        OffsetKt.Padding spacing = find(composer.modifier, OffsetKt.Padding.class);
        // Lined up with the translate button's right edge, a 34 dp button and a 12 dp gap up.
        assertEquals(0f, spacing.end, 0f);
        assertEquals(46f, spacing.bottom, 0f);
        // A size modifier here would make the capsule a cover over the lyrics.
        assertNull(find(composer.modifier, androidx.compose.foundation.layout.SizeKt.Fill.class));
    }

    @Test
    public void landscapeKeepsOnlyTheEdgeMargin() {
        KaraokeLyrics.landscape(composer);
        OffsetKt.Padding spacing = find(composer.modifier, OffsetKt.Padding.class);
        assertEquals(12f, spacing.bottom, 0f);
        assertEquals(12f, spacing.end, 0f);
    }

    @Test
    public void renderingNeverChangesTheSettingsOrWritesPreferences() {
        SharedPreferences saved =
                context.getSharedPreferences(SoundExtras.PREFERENCES, Context.MODE_PRIVATE);
        prefs(saved);
        KaraokeCapsule capsule = embed();
        lay(capsule, 600);
        capsule.draw(new android.graphics.Canvas());
        for (int i = 0; i < 3; i++) KaraokeLyrics.portrait(composer, true);
        assertFalse(SoundExtras.singEnabled);
        assertEquals(VocalCut.VOICE_GATE, SoundExtras.singMethod);
        assertEquals(50, SoundExtras.singLevel);
        assertTrue(saved.getAll().isEmpty());
        assertEquals("Filter", SeparationModel.status());
    }

    @Test
    public void aFailingCompositionSkipsOnceAndTheNextOneStillEmbeds() {
        KaraokeLyrics.portrait(null, true);
        assertNull(composer.view);
        KaraokeLyrics.portrait(composer, true);
        assertNotNull(composer.view);
    }

    // ── What a tap does ──────────────────────────────────────────────────────

    @Test
    public void tappingTheMicrophoneSelectsTheModelNotTheFilter() {
        SoundExtras.singMethod = VocalCut.VOICE_GATE;
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        assertEquals(SeparationModel.METHOD, SoundExtras.singMethod);
        assertTrue(SoundExtras.singEnabled);
        assertTrue(KaraokeLyrics.modelOn());
        assertEquals(50, SoundExtras.singLevel);
        assertTrue(capsule.expanded());
    }

    @Test
    public void aSavedZeroStaysZeroAndIsSaidToBeOriginalVocals() throws Exception {
        SoundExtras.singLevel = 0;
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        assertEquals(0, SoundExtras.singLevel);
        assertTrue(KaraokeLyrics.modelOn());
        settle();
        SeparationModel.forTest(inference(), true);
        assertEquals("Original vocals", KaraokeLyrics.note());
        assertTrue(KaraokeLyrics.spoken().contains("Original vocals"));
        assertEquals("0%", KaraokeLyrics.percent());
    }

    @Test
    public void theCapsuleDoesNotClaimTheModelIsReadyWhileItLoadsOrFails() throws Exception {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        settle();
        setState("Model loading");
        assertFalse(KaraokeLyrics.ready());
        assertEquals("Loading", KaraokeLyrics.note());
        assertTrue(KaraokeLyrics.spoken().startsWith("Model loading"));
        setState(SeparationModel.UNAVAILABLE);
        assertFalse(KaraokeLyrics.ready());
        assertEquals("Filter", KaraokeLyrics.note());
        assertTrue(KaraokeLyrics.spoken().startsWith(SeparationModel.UNAVAILABLE));
        setState(SeparationModel.TOO_SLOW);
        assertFalse(KaraokeLyrics.ready());
        setState("Model ready");
        assertTrue(KaraokeLyrics.ready());
        assertEquals("", KaraokeLyrics.note());
    }

    private static void setState(String value) throws Exception {
        Method set = SeparationModel.class.getDeclaredMethod("setState", String.class);
        set.setAccessible(true);
        set.invoke(null, value);
    }

    private static SeparationModel.Inference inference() {
        return magnitude -> magnitude;
    }

    @Test
    public void modelStatusChangesReachTheAttachedCapsuleWithoutPolling() throws Exception {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        settle();
        idle(10);
        setState("Model loading");
        idle(10);
        assertTrue(
                capsule.microphone().getStateDescription().toString().startsWith("Model loading"));
        setState("Model ready");
        idle(10);
        assertTrue(capsule.microphone().getStateDescription().toString().startsWith("Model ready"));
        assertTrue(Shadows.shadowOf(Looper.getMainLooper()).isIdle());
        CharSequence before = capsule.microphone().getStateDescription();
        content.removeView(capsule);
        setState("Model loading");
        idle(10);
        assertEquals(
                "a detached capsule hears nothing",
                before,
                capsule.microphone().getStateDescription());
    }

    // ── The shared level ─────────────────────────────────────────────────────

    private void touch(View view, int action, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, view.getWidth() / 2f, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test
    public void theSliderMapsItsHeightExactlyOntoTheSharedLevel() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        View slider = capsule.slider();
        assertEquals(px(320), capsule.getHeight());
        float h = slider.getHeight();
        touch(slider, MotionEvent.ACTION_DOWN, h);
        assertEquals(0, SoundExtras.singLevel);
        touch(slider, MotionEvent.ACTION_MOVE, 0);
        assertEquals(100, SoundExtras.singLevel);
        touch(slider, MotionEvent.ACTION_MOVE, h / 2);
        assertEquals(50, SoundExtras.singLevel);
        touch(slider, MotionEvent.ACTION_MOVE, h * 0.25f);
        assertEquals(75, SoundExtras.singLevel);
        touch(slider, MotionEvent.ACTION_UP, h * 0.25f);
        assertEquals(75, SoundExtras.singLevel);
        assertEquals("75%", KaraokeLyrics.percent());
    }

    @Test
    public void theSliderAndTheSettingsPageShareOneValueBothWays() {
        android.content.SharedPreferences saved =
                context.getSharedPreferences(SoundExtras.PREFERENCES, Context.MODE_PRIVATE);
        prefs(saved);
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        capsule.slider()
                .performAccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                        progress(30));
        assertEquals(30, SoundExtras.singLevel);
        assertEquals(30, saved.getInt("singLevel", -1));
        // The settings slider uses the same setter; the capsule shows what it wrote.
        SoundExtras.setSingLevel(80);
        assertEquals(80, KaraokeLyrics.level());
        AccessibilityNodeInfo info = capsule.slider().createAccessibilityNodeInfo();
        assertEquals(80f, info.getRangeInfo().getCurrent(), 0f);
        assertEquals(100f, info.getRangeInfo().getMax(), 0f);
        assertEquals(0f, info.getRangeInfo().getMin(), 0f);
        info.recycle();
    }

    private static Bundle progress(float value) {
        Bundle arguments = new Bundle();
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
        return arguments;
    }

    @Test
    public void everyLevelChangeUsesTheSharedSetterAndTheModelStaysTheMethod() {
        SoundExtras.singLevel = 10;
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        capsule.slider()
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);
        assertEquals(15, SoundExtras.singLevel);
        capsule.slider()
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null);
        assertEquals(10, SoundExtras.singLevel);
        assertEquals(SeparationModel.METHOD, SoundExtras.singMethod);
    }

    @Test
    public void resetGoesBackToTheSettingsDefaultAndFineStepsMoveByOnePercent() {
        SoundExtras.singLevel = 90;
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        AccessibilityNodeInfo info = capsule.slider().createAccessibilityNodeInfo();
        int reset = 0, finer = 0, coarser = 0;
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            String label = String.valueOf(action.getLabel());
            if (label.startsWith("Reset to")) reset = action.getId();
            if (label.equals("Decrease by 1%")) finer = action.getId();
            if (label.equals("Increase by 1%")) coarser = action.getId();
        }
        assertTrue(reset != 0 && finer != 0 && coarser != 0);
        assertEquals(
                "Reset to 50%",
                info.getActionList().stream()
                        .filter(a -> a.getId() == 0x4B000001)
                        .findFirst()
                        .get()
                        .getLabel()
                        .toString());
        capsule.slider().performAccessibilityAction(finer, null);
        assertEquals(89, SoundExtras.singLevel);
        capsule.slider().performAccessibilityAction(coarser, null);
        assertEquals(90, SoundExtras.singLevel);
        capsule.slider().performAccessibilityAction(reset, null);
        assertEquals(KaraokeLyrics.DEFAULT_LEVEL, SoundExtras.singLevel);
        info.recycle();
    }

    @Test
    public void keyboardAdjustsTheLevelAndStaysWithinZeroToOneHundred() {
        SoundExtras.singLevel = 99;
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        View slider = capsule.slider();
        slider.onKeyDown(
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(100, SoundExtras.singLevel);
        slider.onKeyDown(
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(100, SoundExtras.singLevel);
        slider.onKeyDown(
                android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_PAGE_DOWN));
        assertEquals(90, SoundExtras.singLevel);
        slider.onKeyDown(
                android.view.KeyEvent.KEYCODE_MOVE_HOME,
                new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_MOVE_HOME));
        assertEquals(0, SoundExtras.singLevel);
    }

    @Test
    public void offUsesTheSameSetterAndKeepsTheSavedLevel() {
        SoundExtras.singLevel = 64;
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertEquals(View.VISIBLE, capsule.offButton().getVisibility());
        assertTrue(px(48) <= capsule.offButton().getHeight());
        capsule.offButton().performClick();
        assertFalse(SoundExtras.singEnabled);
        assertEquals(64, SoundExtras.singLevel);
        assertEquals("Off", KaraokeLyrics.note());
        assertFalse(capsule.expanded());
        assertFalse(capsule.iconShown());
    }

    @Test
    public void persistedChoicesSurviveTheCapsule() {
        SharedPreferences saved =
                context.getSharedPreferences(SoundExtras.PREFERENCES, Context.MODE_PRIVATE);
        prefs(saved);
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        assertEquals(SeparationModel.METHOD, saved.getInt("singMethod", -1));
        assertTrue(saved.getBoolean("singEnabled", false));
        KaraokeLyrics.setLevel(0);
        assertEquals(0, saved.getInt("singLevel", -1));
        // Closing hides the controls and nothing more.
        capsule.close(true);
        assertTrue(saved.getBoolean("singEnabled", false));
        assertEquals(0, saved.getInt("singLevel", -1));
    }

    // ── Fading and the touch target ──────────────────────────────────────────

    @Test
    public void closingFadesTheIconButTheZoneKeepsItsTargetAndDescription() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertTrue(capsule.expanded());
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertFalse(capsule.expanded());
        assertFalse(capsule.iconShown());
        assertEquals(0f, capsule.iconAlpha(), 0f);
        // The view itself is not made transparent, hidden or shrunk.
        assertEquals(1f, capsule.getAlpha(), 0f);
        assertEquals(1f, capsule.microphone().getAlpha(), 0f);
        assertEquals(View.VISIBLE, capsule.getVisibility());
        assertEquals(View.VISIBLE, capsule.microphone().getVisibility());
        assertTrue(capsule.microphone().isClickable());
        assertTrue(capsule.microphone().isFocusable());
        assertEquals(px(76), capsule.getWidth());
        assertEquals(px(76), capsule.getHeight());
        assertTrue(capsule.microphone().getWidth() >= px(48));
        assertTrue(capsule.microphone().getHeight() >= px(48));
        assertEquals("Karaoke", capsule.microphone().getContentDescription().toString());
        assertNotEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                capsule.microphone().getImportantForAccessibility());
        // The slider is gone from touch and accessibility; only the microphone's place is left.
        assertNotEquals(View.VISIBLE, capsule.slider().getVisibility());
        assertNotEquals(View.VISIBLE, capsule.offButton().getVisibility());
        // Tapping the same place brings it back and opens the slider.
        capsule.microphone().performClick();
        assertTrue(capsule.iconShown());
        assertEquals(1f, capsule.iconAlpha(), 0f);
        assertTrue(capsule.expanded());
        assertEquals(View.VISIBLE, capsule.slider().getVisibility());
    }

    @Test
    public void fadingKeepsKaraokeOnAndDoesNotTouchTheModel() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        capsule.close(true);
        assertTrue(SoundExtras.singEnabled);
        assertEquals(SeparationModel.METHOD, SoundExtras.singMethod);
        assertEquals(50, SoundExtras.singLevel);
    }

    @Test
    public void thePlayerFadesWithItsOwnAnimatorsAndSnapsWhenTheyAreOff() {
        ValueAnimator.setDurationScale(1f);
        KaraokeCapsule capsule = embed();
        content.layout(0, 0, px(400), px(800));
        capsule.microphone().performClick();
        assertFalse("opening runs", capsule.openFraction() >= 1f);
        assertTrue("the slider is closed until the animation runs", capsule.openFraction() < 1f);
        idle(700);
        assertEquals(1f, capsule.openFraction(), 0f);
        capsule.close(true);
        assertTrue("the microphone stays until the slider has gone", capsule.iconShown());
        idle(1_500);
        assertFalse(capsule.iconShown());
        assertEquals(0f, capsule.openFraction(), 0f);
        // With the system's animators off the same change is immediate.
        ValueAnimator.setDurationScale(0f);
        capsule.microphone().performClick();
        assertEquals(1f, capsule.openFraction(), 0f);
        capsule.close(true);
        assertEquals(0f, capsule.iconAlpha(), 0f);
    }

    @Test
    public void focusRevealsTheIconWithoutOpeningAndLosingFocusFadesItAgain() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        capsule.close(true);
        assertFalse(capsule.iconShown());
        capsule.microphone()
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        assertTrue(capsule.iconShown());
        assertFalse(capsule.expanded());
        capsule.microphone()
                .performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
        idle(10);
        assertFalse(capsule.iconShown());
        capsule.microphone().getOnFocusChangeListener().onFocusChange(capsule.microphone(), true);
        assertTrue(capsule.iconShown());
        capsule.microphone().getOnFocusChangeListener().onFocusChange(capsule.microphone(), false);
        assertFalse(capsule.iconShown());
    }

    // ── Auto-close ───────────────────────────────────────────────────────────

    @Test
    public void theSliderClosesThreeSecondsAfterOpeningAndThenTheIconFades() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        idle(2_990);
        assertTrue(capsule.expanded());
        idle(20);
        assertFalse(capsule.expanded());
        assertFalse(capsule.iconShown());
        assertTrue(SoundExtras.singEnabled);
    }

    @Test
    public void draggingPausesAutoCloseAndReleasingStartsItAgain() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        idle(2_500);
        touch(capsule.slider(), MotionEvent.ACTION_DOWN, capsule.slider().getHeight() * 0.3f);
        touch(capsule.slider(), MotionEvent.ACTION_MOVE, capsule.slider().getHeight() * 0.2f);
        idle(10_000);
        assertTrue("held open while the finger is down", capsule.expanded());
        touch(capsule.slider(), MotionEvent.ACTION_UP, capsule.slider().getHeight() * 0.2f);
        idle(2_990);
        assertTrue(capsule.expanded());
        idle(20);
        assertFalse(capsule.expanded());
    }

    @Test
    public void touchExplorationAndFocusKeepTheSliderOpen() {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(manager).setEnabled(true);
        Shadows.shadowOf(manager).setTouchExplorationEnabled(true);
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        idle(60_000);
        assertTrue(capsule.expanded());
        // Closing is then explicit: the microphone toggles.
        capsule.microphone().performClick();
        assertFalse(capsule.expanded());
    }

    @Test
    public void theSliderKeepsTheLyricsFromScrollingWhileItIsDragged() {
        final boolean[] disallowed = new boolean[1];
        FrameLayout parent =
                new FrameLayout(context) {
                    @Override
                    public void requestDisallowInterceptTouchEvent(boolean disallow) {
                        disallowed[0] = disallow;
                        super.requestDisallowInterceptTouchEvent(disallow);
                    }
                };
        owner.get().setContentView(parent);
        ComposerImpl local = new ComposerImpl(owner.get(), parent);
        KaraokeLyrics.portrait(local, true);
        KaraokeCapsule capsule = (KaraokeCapsule) local.view;
        capsule.microphone().performClick();
        lay(capsule, 700);
        touch(capsule.slider(), MotionEvent.ACTION_DOWN, 40);
        assertTrue(disallowed[0]);
        touch(capsule.slider(), MotionEvent.ACTION_UP, 40);
        assertFalse(disallowed[0]);
    }

    // ── Glitter, lifecycle ───────────────────────────────────────────────────

    @Test
    public void glitterRunsOnlyWhileTheSliderIsOpenWithSomethingToShow() {
        ValueAnimator.setDurationScale(1f);
        KaraokeCapsule capsule = embed();
        content.layout(0, 0, px(400), px(800));
        assertFalse("hidden or collapsed draws nothing moving", capsule.shimmering());
        capsule.microphone().performClick();
        idle(600);
        assertTrue(capsule.shimmering());
        capsule.close(false);
        idle(600);
        assertFalse(capsule.shimmering());
        SoundExtras.singLevel = 0;
        capsule.microphone().performClick();
        idle(600);
        assertFalse("no removal, nothing to sparkle", capsule.shimmering());
        KaraokeLyrics.setLevel(40);
        capsule.slider()
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);
        assertTrue(capsule.shimmering());
    }

    @Test
    public void leavingTheLyricsStopsEverythingTheCapsuleStarted() {
        ValueAnimator.setDurationScale(1f);
        KaraokeCapsule capsule = embed();
        content.layout(0, 0, px(400), px(800));
        capsule.microphone().performClick();
        idle(600);
        assertTrue(capsule.shimmering());
        content.removeView(capsule);
        assertFalse(capsule.shimmering());
        idle(10_000);
        assertTrue("a detached capsule closes nothing later", capsule.expanded());
    }

    @Test
    public void aNewCapsuleAfterRecreationStartsWithItsMicrophoneShowing() {
        KaraokeCapsule first = embed();
        first.microphone().performClick();
        first.close(true);
        assertFalse(first.iconShown());
        ComposerImpl again = new ComposerImpl(owner.get(), content);
        KaraokeLyrics.portrait(again, true);
        KaraokeCapsule second = (KaraokeCapsule) again.view;
        assertNotSame(first, second);
        assertTrue(second.iconShown());
        assertFalse(second.expanded());
    }

    // ── Blur ─────────────────────────────────────────────────────────────────

    @Test
    public void theHostBlurIsUsedWhileTheIconShowsAndDroppedWhenItHasFaded() {
        KaraokeLyrics.haze(new HazeState());
        KaraokeCapsule capsule = embed();
        idle(10);
        OptimizedHazeKt.Frost frost = find(composer.modifier, OptimizedHazeKt.Frost.class);
        assertNotNull(frost);
        BlurKt.Clip clip = find(composer.modifier, BlurKt.Clip.class);
        assertEquals(
                38f,
                ((androidx.compose.foundation.shape.RoundedCornerShape) clip.shape).radiusDp,
                0f);
        assertEquals("a light tint over the blur", 0.06f, frost.style.alpha, 0f);
        // The helper keeps this colour as the glass's background and no-blur fallback: nearly
        // clear.
        int backgroundAlpha = (int) ((frost.style.tint >>> 56) & 0xff);
        assertTrue("background alpha " + backgroundAlpha, backgroundAlpha <= 13);
        capsule.microphone().performClick();
        capsule.close(true);
        ComposerImpl later = new ComposerImpl(owner.get(), content);
        KaraokeLyrics.portrait(later, true);
        assertNull(find(later.modifier, OptimizedHazeKt.Frost.class));
        capsule.microphone().performClick();
        ComposerImpl back = new ComposerImpl(owner.get(), content);
        KaraokeLyrics.portrait(back, true);
        assertNotNull(find(back.modifier, OptimizedHazeKt.Frost.class));
    }

    @Test
    public void withoutTheHostBlurTheCapsuleStillWorks() {
        KaraokeLyrics.haze(null);
        KaraokeCapsule capsule = embed();
        assertNull(find(composer.modifier, OptimizedHazeKt.Frost.class));
        assertNull(find(composer.modifier, BlurKt.Clip.class));
        capsule.microphone().performClick();
        assertTrue(capsule.expanded());
    }

    @Test
    public void thePlayersTintReachesTheCapsule() {
        composer.tint = new androidx.compose.ui.graphics.Color(0xff112233L << 32);
        KaraokeCapsule capsule = embed();
        java.lang.reflect.Field tint;
        try {
            tint = KaraokeCapsule.class.getDeclaredField("tint");
            tint.setAccessible(true);
            assertEquals(0xff112233, tint.getInt(capsule));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ── Size, font scale, short screens ──────────────────────────────────────

    @Test
    public void collapsedItIsTheDemoCapsuleAndOpenItIsAsTallAsTheViewportAllows() {
        KaraokeCapsule capsule = embed();
        lay(capsule, 700);
        assertEquals(px(76), capsule.getWidth());
        assertEquals(px(76), capsule.getHeight());
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertEquals(px(320), capsule.getHeight());
        assertEquals(px(76), capsule.getWidth());
        capsule.close(false);
        lay(capsule, 700);
        // A short viewport holds the open capsule to what is there.
        capsule.microphone().performClick();
        lay(capsule, 200);
        assertTrue(capsule.getHeight() <= px(200));
        assertTrue(capsule.getHeight() >= px(76));
        assertEquals(px(76), capsule.microphone().getHeight());
        assertTrue(capsule.slider().getHeight() >= 0);
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-mdpi")
    public void landscapeAndLargeTextKeepEveryTargetAtLeast48dp() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 300);
        assertTrue(capsule.getHeight() <= px(300));
        assertTrue(capsule.microphone().getWidth() >= px(48));
        assertTrue(capsule.microphone().getHeight() >= px(48));
        assertTrue(capsule.offButton().getHeight() >= px(48));
        assertTrue(capsule.offButton().getWidth() >= px(48));
        assertTrue(capsule.slider().getWidth() >= px(48));
    }

    // ── Bound symbols ────────────────────────────────────────────────────────

    @Test
    public void withoutTheSymbolsThePatchHandsOverNothingIsAddedAndNothingThrows() {
        KaraokeLyrics.forTest("");
        KaraokeLyrics.portrait(composer, true);
        KaraokeLyrics.portrait(composer, true);
        assertNull(composer.view);
    }

    @Test
    public void aBitChordWithoutAPlayerTintLocalGetsAWhiteCapsule() {
        KaraokeLyrics.forTest(SYMBOLS.replaceAll("(?m)^tint=.*\\n?", ""));
        composer.tint = new androidx.compose.ui.graphics.Color(0xff112233L << 32);
        KaraokeCapsule capsule = embed();
        try {
            java.lang.reflect.Field tint = KaraokeCapsule.class.getDeclaredField("tint");
            tint.setAccessible(true);
            assertEquals(0xffffffff, tint.getInt(capsule));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void aBitChordWithoutTheBlurHelpersStillShowsATranslucentCapsule() {
        String symbols = SYMBOLS.replaceAll("(?m)^(shape|clip|hazeEffect|frost)=.*\\n?", "");
        KaraokeLyrics.forTest(symbols);
        KaraokeLyrics.haze(new HazeState());
        KaraokeCapsule capsule = embed();
        assertNotNull(capsule);
        assertNull(find(composer.modifier, OptimizedHazeKt.Frost.class));
        assertNull(find(composer.modifier, BlurKt.Clip.class));
    }

    // ── The model's status listeners ─────────────────────────────────────────

    private KaraokeCapsule second() {
        ComposerImpl other = new ComposerImpl(owner.get(), new FrameLayout(context));
        FrameLayout box = new FrameLayout(context);
        content.addView(box);
        ComposerImpl local = new ComposerImpl(owner.get(), box);
        KaraokeLyrics.portrait(local, true);
        return (KaraokeCapsule) local.view;
    }

    /** Capsules of earlier tests were never detached from a window that no longer exists. */
    private static void forgetListeners() throws Exception {
        java.lang.reflect.Field field = SeparationModel.class.getDeclaredField("watchers");
        field.setAccessible(true);
        ((java.util.List<?>) field.get(null)).clear();
    }

    private static int listeners() throws Exception {
        java.lang.reflect.Field field = SeparationModel.class.getDeclaredField("watchers");
        field.setAccessible(true);
        return ((java.util.List<?>) field.get(null)).size();
    }

    @Test
    public void anOldCapsuleDetachingAfterANewOneAttachedLeavesTheNewOneListening()
            throws Exception {
        KaraokeCapsule old = embed();
        KaraokeCapsule fresh = second();
        assertEquals(2, listeners());
        ((ViewGroup) old.getParent()).removeView(old);
        assertEquals("only the old capsule's listener went", 1, listeners());
        setState("Model loading");
        idle(10);
        assertTrue(
                fresh.microphone().getStateDescription() != null
                        || fresh.microphone().getContentDescription() != null);
        KaraokeLyrics.activate();
        settle();
        setState("Model loading");
        idle(10);
        assertTrue(fresh.microphone().getStateDescription().toString().startsWith("Model loading"));
    }

    @Test
    public void attachingAgainDoesNotStackListenersAndDetachingRemovesThem() throws Exception {
        KaraokeCapsule capsule = embed();
        assertEquals(1, listeners());
        ViewGroup parent = (ViewGroup) capsule.getParent();
        parent.removeView(capsule);
        assertEquals(0, listeners());
        parent.addView(capsule);
        assertEquals(1, listeners());
        parent.removeView(capsule);
        parent.addView(capsule);
        assertEquals(1, listeners());
    }

    @Test
    public void aStatusPostedBeforeDetachingDoesNotRunAfterTheCapsuleIsAttachedAgain()
            throws Exception {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        settle();
        idle(10);
        setState("Model ready");
        idle(10);
        CharSequence before = capsule.microphone().getStateDescription();
        ViewGroup parent = (ViewGroup) capsule.getParent();
        setState("Model loading"); // posts a refresh that has not run yet
        parent.removeView(capsule);
        parent.addView(capsule);
        // The refresh from the first attach was cancelled; re-attaching refreshes on its own.
        assertEquals(1, listeners());
        idle(10);
        assertTrue(
                capsule.microphone().getStateDescription().toString().startsWith("Model loading"));
        assertNotEquals(before.toString(), capsule.microphone().getStateDescription().toString());
    }

    @Test
    public void aListenerThatThrowsIsDroppedAndNeitherTheModelNorOtherListenersSuffer()
            throws Exception {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        settle();
        idle(10);
        SeparationModel.watch(
                () -> {
                    throw new IllegalStateException("a dead observer");
                });
        assertEquals(2, listeners());
        setState("Model loading");
        assertEquals("the failing listener is dropped", 1, listeners());
        idle(10);
        assertTrue(
                capsule.microphone().getStateDescription().toString().startsWith("Model loading"));
        assertEquals("Model loading", SeparationModel.status());
        // And a model change still reaches the audio side after it.
        setState("Model ready");
        assertEquals("Model ready", SeparationModel.status());
    }

    // ── Lyrics visibility and its own group ──────────────────────────────────

    @Test
    public void whileTheLyricsAreNotOnScreenNothingIsAddedAndTheGroupStaysBalanced() {
        KaraokeLyrics.portrait(composer, false);
        assertNull(composer.view);
        assertEquals(1, composer.groupsOpen);
        assertEquals(1, composer.groupsClosed);
    }

    @Test
    public void theCapsuleIsInItsOwnBalancedGroupWhenTheLyricsAreVisible() {
        KaraokeLyrics.portrait(composer, true);
        assertNotNull(composer.view);
        assertEquals(1, composer.groupsOpen);
        assertEquals(1, composer.groupsClosed);
    }

    @Test
    public void thePlayersTintIsReadThroughTheHostsOwnConsumeWithItsRealParameterType() {
        composer.tint = new androidx.compose.ui.graphics.Color(0xff445566L << 32);
        KaraokeCapsule capsule = embed();
        try {
            java.lang.reflect.Field tint = KaraokeCapsule.class.getDeclaredField("tint");
            tint.setAccessible(true);
            assertEquals(0xff445566, tint.getInt(capsule));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void aTintThatCannotBeReadCostsTheColourNotTheControl() {
        KaraokeLyrics.forTest(
                SYMBOLS.replaceAll(
                        "(?m)^consume=.*$",
                        "consume=Landroidx/compose/runtime/ComposerImpl;->startReplaceGroup(I)V"));
        KaraokeCapsule capsule = embed();
        assertNotNull(capsule);
    }

    // ── Changes made elsewhere reach an attached capsule ─────────────────────

    @Test
    public void aLevelSetElsewhereUpdatesTheDescriptionOfAnAttachedCapsuleWithoutPolling()
            throws Exception {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        settle();
        idle(10);
        SeparationModel.forTest(inference(), true);
        idle(10);
        SoundExtras.setSingLevel(25);
        idle(10);
        assertTrue(capsule.microphone().getStateDescription().toString().contains("25"));
        SoundExtras.setSingLevel(60);
        idle(10);
        assertTrue(capsule.microphone().getStateDescription().toString().contains("60"));
        ViewGroup parent = (ViewGroup) capsule.getParent();
        parent.removeView(capsule);
        SoundExtras.setSingLevel(10);
        idle(10);
        assertTrue(capsule.microphone().getStateDescription().toString().contains("60"));
    }

    /** The host's share-pick state, which says whether lines are being picked. */
    public static class Picker {
        boolean picking;

        public boolean getPicking() {
            return picking;
        }
    }

    @Test
    public void whileLinesArePickedTheCapsuleStepsAsideAndComesBack() {
        Picker picker = new Picker();
        picker.picking = true;
        KaraokeLyrics.portrait(composer, true, picker);
        assertNull(composer.view);
        picker.picking = false;
        KaraokeLyrics.portrait(composer, true, picker);
        assertNotNull(composer.view);
    }
}
