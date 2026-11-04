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
import androidx.compose.foundation.layout.SizeKt;
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
                    "requiredSize=Landroidx/compose/foundation/layout/SizeKt;->requiredSizeIn$default(Landroidx/compose/ui/Modifier;FFFFI)Landroidx/compose/ui/Modifier;",
                    "offsetBy=Landroidx/compose/foundation/layout/OffsetKt;->offset(Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function1;)Landroidx/compose/ui/Modifier;",
                    "intOffset=Landroidx/compose/ui/unit/IntOffset;-><init>(J)V",
                    "startGroup=Landroidx/compose/runtime/ComposerImpl;->startReplaceGroup(I)V",
                    "endGroup=Landroidx/compose/runtime/ComposerImpl;->endReplaceGroup()V",
                    "shape=Landroidx/compose/foundation/shape/RoundedCornerShapeKt;->RoundedCornerShape(F)Landroidx/compose/foundation/shape/RoundedCornerShape;",
                    "clip=Landroidx/compose/ui/draw/BlurKt;->clip(Landroidx/compose/ui/Modifier;Landroidx/compose/ui/graphics/Shape;)Landroidx/compose/ui/Modifier;",
                    "hazeEffect=Lcom/music/bitchord/ui/components/OptimizedHazeKt;->optimizedHazeEffect(Landroidx/compose/ui/Modifier;Ldev/chrisbanes/haze/HazeState;Ldev/chrisbanes/haze/HazeStyle;Lkotlin/jvm/functions/Function1;)Landroidx/compose/ui/Modifier;",
                    "frost=Lcom/music/bitchord/ui/components/OptimizedHazeKt;->menuFrost(FJ)Ldev/chrisbanes/haze/HazeStyle;",
                    "drawElement=Landroidx/compose/ui/draw/DrawWithContentElement;-><init>(Lkotlin/jvm/functions/Function1;)V",
                    "drawContent=Lapp/bitchord/telegram/KaraokeLyricsTest$Drawing;->drawContent()V",
                    "drawContext=Lapp/bitchord/telegram/KaraokeLyricsTest$Drawing;->getDrawContext()Lapp/bitchord/telegram/KaraokeLyricsTest$Drawing;",
                    "contextCanvas=Lapp/bitchord/telegram/KaraokeLyricsTest$Drawing;->getCanvas()Lapp/bitchord/telegram/KaraokeLyricsTest$Drawing;",
                    "nativeCanvas=Lapp/bitchord/telegram/KaraokeLyricsTest$Drawing;->nativeCanvas(Lapp/bitchord/telegram/KaraokeLyricsTest$Drawing;)Landroid/graphics/Canvas;");

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
        // The capsule stands on the bottom of a tall window, as it does on the lyrics.
        int bottom = px(852);
        capsule.layout(0, bottom - capsule.getMeasuredHeight(), capsule.getMeasuredWidth(), bottom);
    }

    private static void assertResting(KaraokeCapsule capsule) {
        assertTrue(capsule.iconShown());
        assertEquals(0.3f, capsule.iconAlpha(), 0.001f);
        assertFalse(capsule.expanded());
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

    /** A pixel rectangle, left/top/right/bottom, in the lyrics box's coordinates. */
    private static final class Box {
        final float left, top, right, bottom;

        Box(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float centreX() {
            return (left + right) / 2f;
        }

        float centreY() {
            return (top + bottom) / 2f;
        }

        boolean overlaps(Box other) {
            return left < other.right
                    && other.left < right
                    && top < other.bottom
                    && other.top < bottom;
        }
    }

    /** The painted disc of a laid-out capsule, moved to where the capsule sits. */
    private static Box disc(KaraokeCapsule capsule, float left, float top) {
        android.graphics.RectF visual = new android.graphics.RectF();
        capsule.visualBounds(visual);
        return new Box(
                left + visual.left, top + visual.top, left + visual.right, top + visual.bottom);
    }

    @Test
    public void withTheMarkerOneCapsuleSitsAtTheBottomEndBesideTheTranslationButton() {
        KaraokeCapsule capsule = embed();
        assertNotNull(capsule);
        assertEquals(1, content.getChildCount());
        BoxChildDataElement place = find(composer.modifier, BoxChildDataElement.class);
        assertSame(Alignment.Companion.BottomEnd, place.alignment);
        assertFalse(place.matchParentSize);
        OffsetKt.Padding spacing = find(composer.modifier, OffsetKt.Padding.class);
        // The 34 dp translate button, a 14 dp gap and the 7 dp the 48 dp target keeps round the
        // disc.
        assertEquals(41f, spacing.end, 0f);
        assertEquals(0f, spacing.bottom, 0f);
        // A size modifier here would make the capsule a cover over the lyrics.
        assertNull(find(composer.modifier, androidx.compose.foundation.layout.SizeKt.Fill.class));
    }

    /** The native lyrics box: translate is a bare 34 dp disc in its bottom-end corner. */
    private void assertPortraitBesideTranslate(float fontScale) {
        org.robolectric.RuntimeEnvironment.setFontScale(fontScale);
        KaraokeCapsule capsule = embed();
        lay(capsule, 700);
        OffsetKt.Padding spacing = find(composer.modifier, OffsetKt.Padding.class);
        float boxWidth = px(393), boxHeight = px(500);
        Box translate = new Box(boxWidth - px(34), boxHeight - px(34), boxWidth, boxHeight);
        float left = boxWidth - px(spacing.end) - capsule.getWidth();
        float top = boxHeight - px(spacing.bottom) - capsule.getHeight();
        Box target = new Box(left, top, left + capsule.getWidth(), top + capsule.getHeight());
        Box disc = disc(capsule, left, top);
        assertEquals("the native 48 dp target", px(48), capsule.getWidth());
        assertEquals("the native 48 dp target", px(48), capsule.getHeight());
        assertEquals("a 34 dp painted disc", px(34), disc.right - disc.left, 1f);
        assertEquals("one baseline", translate.centreY(), disc.centreY(), 1f);
        assertEquals("48 dp between centres", px(48), translate.centreX() - disc.centreX(), 1f);
        assertEquals("a 14 dp painted gap", px(14), translate.left - disc.right, 1f);
        assertFalse("the target stays clear of translate", target.overlaps(translate));
    }

    @Test
    public void portraitDiscSharesTranslatesBaselineAtNormalText() {
        assertPortraitBesideTranslate(1f);
    }

    @Test
    public void portraitDiscSharesTranslatesBaselineAtDoubleText() {
        assertPortraitBesideTranslate(2f);
    }

    // ── Landscape: the native row below the lyrics ───────────────────────────

    /** The slot's tight 34 dp constraints, as {minW, maxW, minH, maxH}. */
    private int[] slotLimits() {
        return new int[] {px(34), px(34), px(34), px(34)};
    }

    /** Measures the capsule with the limits the native size node hands a child of the slot. */
    private int[] measureInSlot(KaraokeCapsule capsule, SizeKt.RequiredSizeIn required) {
        float density = context.getResources().getDisplayMetrics().density;
        int[] limits = required.childConstraints(slotLimits(), density);
        int width =
                limits[0] == limits[1]
                        ? View.MeasureSpec.makeMeasureSpec(limits[0], View.MeasureSpec.EXACTLY)
                        : View.MeasureSpec.makeMeasureSpec(limits[1], View.MeasureSpec.AT_MOST);
        int height =
                limits[3] == Integer.MAX_VALUE
                        ? View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        : View.MeasureSpec.makeMeasureSpec(limits[3], View.MeasureSpec.AT_MOST);
        capsule.measure(width, height);
        return limits;
    }

    private KaraokeCapsule embedLandscape() {
        KaraokeLyrics.landscape(composer);
        return (KaraokeCapsule) composer.view;
    }

    private static int offsetOf(OffsetKt.OffsetBy shift, boolean x) {
        Object boxed = shift.offset.invoke(null);
        long packed = ((androidx.compose.ui.unit.IntOffset) boxed).packed;
        return x ? (int) (packed >> 32) : (int) packed;
    }

    /**
     * The native pane under the lyrics: a row with 34 dp slots at both ends, centred vertically, as
     * tall as its status line wants. The capsule is a child of the translate slot, measured with
     * the limits the real size node gives it, centred in the slot and then moved by the extension's
     * offset, which reads the height measured for this frame.
     */
    private void assertLandscapeBesideTranslate(float rowDp, boolean open) {
        KaraokeCapsule capsule = embedLandscape();
        SizeKt.RequiredSizeIn required = find(composer.modifier, SizeKt.RequiredSizeIn.class);
        OffsetKt.OffsetBy shift = find(composer.modifier, OffsetKt.OffsetBy.class);
        assertNotNull(required);
        assertNotNull(shift);
        // The previous frame stands on the bottom of a tall window.
        capsule.layout(0, px(852) - px(48), px(48), px(852));
        if (open != capsule.expanded()) capsule.microphone().performClick();
        measureInSlot(capsule, required);
        float row = px(rowDp), slot = px(34), paneWidth = px(852);
        float slotTop = (row - slot) / 2f, slotLeft = paneWidth - slot;
        Box translate = new Box(slotLeft, slotTop, paneWidth, slotTop + slot);
        int width = capsule.getMeasuredWidth(), height = capsule.getMeasuredHeight();
        // Measured, not yet laid out: the offset must already use this frame's height.
        assertEquals(px(48), capsule.getHeight());
        float left = slotLeft + (slot - width) / 2f + offsetOf(shift, true);
        float top = slotTop + (slot - height) / 2f + offsetOf(shift, false);
        Box target = new Box(left, top, left + width, top + height);
        Box statusText =
                new Box(
                        slot + px(KaraokeLyrics.landscapeReserve(12f)),
                        0,
                        slotLeft - px(KaraokeLyrics.landscapeReserve(12f)),
                        row);
        String where = "row " + rowDp + "dp, " + (open ? "open" : "closed");
        assertEquals(
                where + ": bottom stays on the disc line",
                translate.centreY() + px(17),
                target.bottom,
                1f);
        assertEquals(where + ": 48 dp target", px(48), width);
        assertEquals(where + ": open height", open ? px(320) : px(48), height);
        assertFalse(where + ": clear of translate", target.overlaps(translate));
        assertFalse(where + ": clear of the status line", target.overlaps(statusText));
        assertEquals(
                where + ": the reserve is the same on both sides",
                statusText.left - slot,
                slotLeft - statusText.right,
                0.5f);
        Box disc = disc(capsule, left, top);
        float glyphY = top + height - px(17);
        assertEquals(where + ": the glyph stays on one baseline", translate.centreY(), glyphY, 1f);
        if (!open) {
            assertEquals(where + ": one baseline", translate.centreY(), disc.centreY(), 1f);
            assertEquals(where + ": 14 dp gap", px(14), translate.left - disc.right, 1f);
            assertEquals(where + ": 34 dp disc", px(34), disc.right - disc.left, 1f);
        }
    }

    @Test
    public void theSlotLetsTheTargetOpenTallerThanTheSlot() {
        KaraokeCapsule capsule = embedLandscape();
        SizeKt.RequiredSizeIn required = find(composer.modifier, SizeKt.RequiredSizeIn.class);
        float density = context.getResources().getDisplayMetrics().density;
        int[] limits = required.childConstraints(slotLimits(), density);
        assertEquals("48 dp wide", px(48), limits[0]);
        assertEquals("48 dp wide", px(48), limits[1]);
        assertEquals("at least the 48 dp target", px(48), limits[2]);
        assertEquals("no height limit of the slot's", Integer.MAX_VALUE, limits[3]);
        capsule.microphone().performClick();
        measureInSlot(capsule, required);
        assertTrue(capsule.getMeasuredHeight() > px(34));
        assertEquals(px(320), capsule.getMeasuredHeight());
        // The slot itself is not resized: the parent sees the child held to its 34 dp limits.
        assertEquals(
                px(34),
                SizeKt.RequiredSizeIn.reported(capsule.getMeasuredHeight(), px(34), px(34)));
    }

    @Test
    public void anUnspecifiedMaximumWouldInheritTheSlotAndCouldNotOpen() {
        float density = context.getResources().getDisplayMetrics().density;
        Modifier inherits =
                SizeKt.requiredSizeIn$default(
                        Modifier.Companion.$$INSTANCE, 48f, 48f, 48f, Float.NaN, 8);
        int[] limits =
                find(inherits, SizeKt.RequiredSizeIn.class).childConstraints(slotLimits(), density);
        assertEquals(px(48), limits[3]);
    }

    @Test
    public void landscapeDiscSharesTranslatesBaselineWhateverTheRowHeight() {
        // 34 dp slots alone, a one-line status at 1.3x and 2x text, and a wrapped large-text row.
        for (float row : new float[] {34f, 40f, 58f, 96f})
            assertLandscapeBesideTranslate(row, false);
    }

    @Test
    public void landscapeOpenSliderKeepsItsBottomOnTheDiscLine() {
        for (float row : new float[] {34f, 58f, 96f}) assertLandscapeBesideTranslate(row, true);
    }

    @Test
    public void landscapeReserveOnlyGrowsTheStatusMargin() {
        assertTrue(KaraokeLyrics.landscapeReserve(12f) > 12f);
        assertEquals(80f, KaraokeLyrics.landscapeReserve(80f), 0f);
        Marker.installed = false;
        assertEquals(
                "nothing changes without the patch", 12f, KaraokeLyrics.landscapeReserve(12f), 0f);
    }

    @Test
    public void anOpenSliderIsCappedByTheRoomAboveItsAnchorWithoutCompounding() {
        KaraokeCapsule capsule = new KaraokeCapsule(context);
        FrameLayout.LayoutParams place =
                new FrameLayout.LayoutParams(
                        px(48), px(48), android.view.Gravity.TOP | android.view.Gravity.START);
        place.topMargin = px(152);
        content.addView(capsule, place);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(px(393), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px(852), View.MeasureSpec.EXACTLY));
        content.layout(0, 0, px(393), px(852));
        int[] at = new int[2];
        capsule.getLocationInWindow(at);
        int anchor = at[1] + capsule.getHeight();
        capsule.microphone().performClick();
        // The host hands the view an unbounded height.
        int unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int exact = View.MeasureSpec.makeMeasureSpec(px(48), View.MeasureSpec.EXACTLY);
        capsule.measure(exact, unbounded);
        int first = capsule.getMeasuredHeight();
        assertTrue("held inside the window above the anchor", first <= anchor - px(8));
        assertTrue("and shorter than the 320 dp it wants", first < px(320));
        assertTrue(first >= px(48));
        assertEquals(px(48), capsule.getMeasuredWidth());
        // Laid out on the same bottom edge, the next frame measures the same, not more or less.
        int left = capsule.getLeft(), bottom = capsule.getBottom();
        capsule.layout(left, bottom - first, left + px(48), bottom);
        capsule.measure(exact, unbounded);
        assertEquals(first, capsule.getMeasuredHeight());
    }

    @Test
    public void landscapeAddsOneCapsuleWithItsOwnGroup() {
        KaraokeLyrics.landscape(composer);
        assertEquals(1, content.getChildCount());
        assertTrue(composer.view instanceof KaraokeCapsule);
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
    public void theExpandedSliderHasNoOffChildAndTheSettingsOwnTheDisable() {
        SoundExtras.singLevel = 64;
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertEquals("only the slider and the microphone", 2, capsule.getChildCount());
        assertNull(capsule.findViewWithTag("karaoke-off"));
        for (int i = 0; i < capsule.getChildCount(); i++) {
            CharSequence said = capsule.getChildAt(i).getContentDescription();
            assertFalse(said != null && said.toString().contains("off"));
        }
        assertNotEquals("Off", KaraokeLyrics.note());
        // Turned off in the settings, the level is kept and the capsule just rests.
        SoundExtras.setSingEnabled(false);
        assertFalse(KaraokeLyrics.modelOn());
        assertEquals("", KaraokeLyrics.note());
        assertEquals("Off", KaraokeLyrics.spoken());
        assertEquals(64, SoundExtras.singLevel);
        capsule.close(true);
        lay(capsule, 700);
        assertResting(capsule);
        assertEquals(2, capsule.getChildCount());
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
    public void microphoneUsesTheNativeActionScaleWithAnAccessibleTarget() {
        KaraokeCapsule capsule = embed();
        lay(capsule, 700);
        assertEquals(px(48), capsule.getWidth());
        assertEquals(px(48), capsule.microphone().getHeight());
        assertEquals(19, KaraokeCapsule.ICON_DP);
        assertTrue(capsule.microphone().isClickable());
    }

    @Test
    public void aFadedMicrophoneReopensFromItsOriginalTouchCoordinates() {
        KaraokeCapsule capsule = embed();
        lay(capsule, 700);
        capsule.microphone().performClick();
        capsule.close(true);
        lay(capsule, 700);
        assertResting(capsule);
        long now = android.os.SystemClock.uptimeMillis();
        float x = capsule.getWidth() / 2f, y = capsule.getHeight() / 2f;
        android.view.MotionEvent down =
                android.view.MotionEvent.obtain(
                        now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up =
                android.view.MotionEvent.obtain(
                        now, now + 30, android.view.MotionEvent.ACTION_UP, x, y, 0);
        try {
            assertTrue(capsule.dispatchTouchEvent(down));
            assertTrue(capsule.dispatchTouchEvent(up));
            idle(40);
            assertTrue(capsule.expanded());
            assertTrue(capsule.iconShown());
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    @Test
    public void closingDimsTheIconAndKeepsItsTargetAndDescription() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertTrue(capsule.expanded());
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertFalse(capsule.expanded());
        assertResting(capsule);
        assertEquals(0.3f, capsule.iconAlpha(), 0.001f);
        // The view itself is not made transparent, hidden or shrunk.
        assertEquals(1f, capsule.getAlpha(), 0f);
        assertEquals(1f, capsule.microphone().getAlpha(), 0f);
        assertEquals(View.VISIBLE, capsule.getVisibility());
        assertEquals(View.VISIBLE, capsule.microphone().getVisibility());
        assertTrue(capsule.microphone().isClickable());
        assertTrue(capsule.microphone().isFocusable());
        assertEquals(px(48), capsule.getWidth());
        assertEquals(px(48), capsule.getHeight());
        assertTrue(capsule.microphone().getWidth() >= px(48));
        assertTrue(capsule.microphone().getHeight() >= px(48));
        assertEquals("Karaoke", capsule.microphone().getContentDescription().toString());
        assertNotEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                capsule.microphone().getImportantForAccessibility());
        // The slider is gone from touch and accessibility; only the microphone's place is left.
        assertNotEquals(View.VISIBLE, capsule.slider().getVisibility());
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
        assertResting(capsule);
        assertEquals(0f, capsule.openFraction(), 0f);
        // With the system's animators off the same change is immediate.
        ValueAnimator.setDurationScale(0f);
        capsule.microphone().performClick();
        assertEquals(1f, capsule.openFraction(), 0f);
        capsule.close(true);
        assertEquals(0.3f, capsule.iconAlpha(), 0.001f);
    }

    @Test
    public void focusRevealsTheIconWithoutOpeningAndLosingFocusFadesItAgain() {
        KaraokeCapsule capsule = embed();
        capsule.microphone().performClick();
        capsule.close(true);
        assertResting(capsule);
        capsule.microphone()
                .performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        assertTrue(capsule.iconShown());
        assertFalse(capsule.expanded());
        capsule.microphone()
                .performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
        idle(10);
        assertResting(capsule);
        capsule.microphone().getOnFocusChangeListener().onFocusChange(capsule.microphone(), true);
        assertTrue(capsule.iconShown());
        capsule.microphone().getOnFocusChangeListener().onFocusChange(capsule.microphone(), false);
        assertResting(capsule);
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
        assertResting(capsule);
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
        assertResting(first);
        ComposerImpl again = new ComposerImpl(owner.get(), content);
        KaraokeLyrics.portrait(again, true);
        KaraokeCapsule second = (KaraokeCapsule) again.view;
        assertNotSame(first, second);
        assertTrue(second.iconShown());
        assertFalse(second.expanded());
    }

    // ── Blur ─────────────────────────────────────────────────────────────────

    public static final class Drawing {
        final android.graphics.Canvas canvas;

        Drawing(android.graphics.Canvas canvas) {
            this.canvas = canvas;
        }

        public Drawing getDrawContext() {
            return this;
        }

        public Drawing getCanvas() {
            return this;
        }

        static boolean unavailable;

        public static android.graphics.Canvas nativeCanvas(Drawing scope) {
            if (unavailable) throw new IllegalStateException("Canvas adapter unavailable");
            return scope.canvas;
        }

        public void drawContent() {
            canvas.drawColor(android.graphics.Color.WHITE);
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void theHostFrostFitsThe34dpDiscWithoutClippingIts48dpTouchTarget() {
        KaraokeLyrics.haze(new dev.chrisbanes.haze.HazeState());
        KaraokeCapsule capsule = embed();
        lay(capsule, 700);
        androidx.compose.ui.draw.DrawWithContentElement visual =
                find(composer.modifier, androidx.compose.ui.draw.DrawWithContentElement.class);
        assertNotNull("the frost has a drawing-only boundary", visual);
        assertNull(
                "a smaller layer clip would cut the activation target",
                find(composer.modifier, BlurKt.Clip.class));
        android.graphics.Bitmap bitmap =
                android.graphics.Bitmap.createBitmap(
                        capsule.getWidth(),
                        capsule.getHeight(),
                        android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas surface = new android.graphics.Canvas(bitmap);
        visual.onDraw.invoke(new Drawing(surface));
        int middle = bitmap.getWidth() / 2;
        // The disc sits on the bottom of its target: its widest row is 17 dp above the bottom.
        int row = px(48 - 17);
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(px(2), row)));
        assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(middle, row)));
        int first = -1, last = -1;
        for (int x = 0; x < bitmap.getWidth(); x++)
            if (android.graphics.Color.alpha(bitmap.getPixel(x, row)) > 0) {
                if (first < 0) first = x;
                last = x;
            }
        assertEquals(px(7), first);
        assertEquals(px(34), last - first + 1);
        android.graphics.Paint outside = new android.graphics.Paint();
        outside.setColor(android.graphics.Color.RED);
        surface.drawRect(0, row, px(4), row + 1, outside);
        assertEquals(
                "the clip never leaks into later drawing",
                255,
                android.graphics.Color.alpha(bitmap.getPixel(px(2), row)));

        assertEquals(px(48), capsule.microphone().getWidth());
        assertEquals(px(48), capsule.microphone().getHeight());
        long time = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down =
                android.view.MotionEvent.obtain(
                        time, time, android.view.MotionEvent.ACTION_DOWN, px(2), row, 0);
        android.view.MotionEvent up =
                android.view.MotionEvent.obtain(
                        time, time + 20, android.view.MotionEvent.ACTION_UP, px(2), row, 0);
        try {
            assertTrue(capsule.dispatchTouchEvent(down));
            assertTrue(capsule.dispatchTouchEvent(up));
            idle(40);
            assertTrue("the invisible outer touch area still opens the slider", capsule.expanded());
            lay(capsule, 700);
            android.graphics.Bitmap expanded =
                    android.graphics.Bitmap.createBitmap(
                            capsule.getWidth(),
                            capsule.getHeight(),
                            android.graphics.Bitmap.Config.ARGB_8888);
            try {
                visual.onDraw.invoke(new Drawing(new android.graphics.Canvas(expanded)));
                assertEquals(255, android.graphics.Color.alpha(expanded.getPixel(px(24), px(80))));
                assertEquals(0, android.graphics.Color.alpha(expanded.getPixel(px(1), px(1))));
                assertEquals(px(48), capsule.slider().getWidth());
                assertEquals(32, KaraokeCapsule.TRACK_DP);
            } finally {
                expanded.recycle();
            }
        } finally {
            down.recycle();
            up.recycle();
            bitmap.recycle();
        }
    }

    @Test
    public void anUnavailableCanvasAdapterFallsBackWithoutLosingTheControl() {
        KaraokeLyrics.haze(new HazeState());
        KaraokeCapsule capsule = embed();
        lay(capsule, 700);
        androidx.compose.ui.draw.DrawWithContentElement visual =
                find(composer.modifier, androidx.compose.ui.draw.DrawWithContentElement.class);
        Drawing.unavailable = true;
        try {
            visual.onDraw.invoke(new Drawing(new android.graphics.Canvas()));
        } finally {
            Drawing.unavailable = false;
        }
        KaraokeLyrics.portrait(composer, true);
        assertSame(capsule, composer.view);
        assertNull(find(composer.modifier, OptimizedHazeKt.Frost.class));
        assertNull(find(composer.modifier, androidx.compose.ui.draw.DrawWithContentElement.class));
        capsule.microphone().performClick();
        assertTrue(capsule.expanded());
    }

    @Test
    public void theHostBlurIsUsedWhileOpenAndDroppedForTheRestingIcon() {
        KaraokeLyrics.haze(new HazeState());
        KaraokeCapsule capsule = embed();
        idle(10);
        OptimizedHazeKt.Frost frost = find(composer.modifier, OptimizedHazeKt.Frost.class);
        assertNotNull(frost);
        assertNull(find(composer.modifier, BlurKt.Clip.class));
        assertNotNull(
                find(composer.modifier, androidx.compose.ui.draw.DrawWithContentElement.class));
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
    public void collapsedItMatchesPlayerActionsAndOpenItFitsTheViewport() {
        KaraokeCapsule capsule = embed();
        lay(capsule, 700);
        assertEquals(px(48), capsule.getWidth());
        assertEquals(px(48), capsule.getHeight());
        capsule.microphone().performClick();
        lay(capsule, 700);
        assertEquals(px(320), capsule.getHeight());
        assertEquals(px(48), capsule.getWidth());
        capsule.close(false);
        lay(capsule, 700);
        // A short viewport holds the open capsule to what is there.
        capsule.microphone().performClick();
        lay(capsule, 200);
        assertTrue(capsule.getHeight() <= px(200));
        assertTrue(capsule.getHeight() >= px(48));
        assertEquals(px(48), capsule.microphone().getHeight());
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
