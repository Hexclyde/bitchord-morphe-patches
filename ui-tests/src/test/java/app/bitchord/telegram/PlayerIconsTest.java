package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;

import androidx.compose.foundation.layout.BoxChildDataElement;
import androidx.compose.foundation.layout.OffsetKt;
import androidx.compose.runtime.ComposerImpl;
import androidx.compose.ui.Alignment;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.Color;
import androidx.compose.ui.viewinterop.AndroidView_androidKt;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;

import com.music.bitchord.ui.player.PlayerChromeKt;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the player's icons go: the Audio/Video pair in the drag strip, the Automix Editor icon at
 * the foot of the artwork, how each is inked and lit, and that the host's layout is never left
 * unbalanced or overlapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-night-mdpi",
        shadows = {PlayerIconsTest.Mix.class, PlayerIconsTest.NoVersionLookup.class})
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlayerIconsTest {
    /** The Automix patch turns the marker on; here it is switched by hand. */
    @Implements(value = SourceAutomix.class, isInAndroidSdk = false)
    public static class Mix {
        static boolean applied = true;

        @Implementation
        public static boolean applied() {
            return applied;
        }
    }

    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class NoVersionLookup {
        @Implementation
        public static android.content.Context app() {
            return null;
        }
    }

    /** What the patch hands the extension, spelled for the stand-ins in this test tree. */
    static final String BASE =
            String.join(
                    "\n",
                    "androidView=Landroidx/compose/ui/viewinterop/AndroidView_androidKt;->AndroidView(Lkotlin/jvm/functions/Function1;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/ComposerImpl;II)V",
                    "padding=Landroidx/compose/foundation/layout/OffsetKt;->padding(Landroidx/compose/ui/Modifier;FFFF)Landroidx/compose/ui/Modifier;",
                    "companion=Landroidx/compose/ui/Modifier$Companion;->$$INSTANCE:Landroidx/compose/ui/Modifier$Companion;",
                    "topCenter=Landroidx/compose/ui/Alignment$Companion;->TopCenter:Landroidx/compose/ui/Alignment;",
                    "bottomCenter=Landroidx/compose/ui/Alignment$Companion;->BottomCenter:Landroidx/compose/ui/Alignment;",
                    "childData=Landroidx/compose/foundation/layout/BoxChildDataElement;-><init>(Landroidx/compose/ui/Alignment;Z)V",
                    "startGroup=Landroidx/compose/runtime/ComposerImpl;->startReplaceGroup(I)V",
                    "endGroup=Landroidx/compose/runtime/ComposerImpl;->endReplaceGroup()V",
                    "offset=Landroidx/compose/foundation/layout/OffsetKt;->offset(Landroidx/compose/ui/Modifier;FF)Landroidx/compose/ui/Modifier;",
                    "wrapWidth=Landroidx/compose/foundation/layout/SizeKt;->wrapContentWidth(Landroidx/compose/ui/Modifier;Landroidx/compose/ui/BiasAlignment$Horizontal;Z)Landroidx/compose/ui/Modifier;",
                    "size=Landroidx/compose/foundation/layout/SizeKt;->size(Landroidx/compose/ui/Modifier;F)Landroidx/compose/ui/Modifier;",
                    "centerH=Landroidx/compose/ui/Alignment$Companion;->CenterHorizontally:Landroidx/compose/ui/BiasAlignment$Horizontal;",
                    "zIndex=Landroidx/compose/ui/ZIndexElement;-><init>(F)V",
                    "minWidth=Landroidx/compose/foundation/layout/SizeElement;->minWidth:F",
                    "minHeight=Landroidx/compose/foundation/layout/SizeElement;->minHeight:F",
                    "maxWidth=Landroidx/compose/foundation/layout/SizeElement;->maxWidth:F",
                    "maxHeight=Landroidx/compose/foundation/layout/SizeElement;->maxHeight:F");

    static final String CONSUME =
            "\n"
                + "consume=Landroidx/compose/runtime/ComposerImpl;->consume(Landroidx/compose/runtime/ProvidableCompositionLocal;)Ljava/lang/Object;";

    /** 1.9: the player's tint local. */
    static final String TINT =
            BASE
                    + CONSUME
                    + "\n"
                    + "tint=Lcom/music/bitchord/ui/player/PlayerControlsKt;->LocalPlayerTint:Landroidx/compose/runtime/DynamicProvidableCompositionLocal;";

    /** 1.9.1: the chrome local and the additive blend, which has no tint local. */
    static final String CHROME =
            BASE
                    + CONSUME
                    + "\n"
                    + "chrome=Lcom/music/bitchord/ui/player/PlayerChromeKt;->LocalPlayerChrome:Landroidx/compose/runtime/DynamicProvidableCompositionLocal;\n"
                    + "blend=Lcom/music/bitchord/ui/player/PlayerChromeKt;->additiveChrome(Landroidx/compose/ui/Modifier;Z)Landroidx/compose/ui/Modifier;";

    ActivityController<Activity> owner;
    FrameLayout content;
    ComposerImpl composer;
    MediaController controller;

    private static void set(String name, Object value) throws Exception {
        Field f = VideoSwitch.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @Before
    public void setup() throws Exception {
        com.music.bitchord.data.settings.AppSettings.lastPlayerScreen.setValue("MAIN");
        Mix.applied = true;
        owner = Robolectric.buildActivity(Activity.class).setup();
        content = new FrameLayout(owner.get());
        owner.get().setContentView(content);
        composer = new ComposerImpl(owner.get(), content);
        controller = new MediaController();
        controller.item = new MediaItem.Builder().setMediaId("dQw4w9WgXcQ").build();
        set("controller", new WeakReference<>(controller));
        set("preferences", null);
        set("switching", null);
        VideoSwitch.preferredVideo = false;
        VideoSwitch.enabled = true;
        AndroidView_androidKt.failure = null;
        PlayerChromeKt.blends = 0;
        PlayerIcons.forTest(TINT);
    }

    @After
    public void close() throws Exception {
        VideoSwitch.enabled = false;
        AndroidView_androidKt.failure = null;
        PlayerIcons.forTest(null);
        owner.pause().stop().destroy();
        set("controller", new WeakReference<>(null));
    }

    private ComposerImpl fresh() {
        return new ComposerImpl(owner.get(), content);
    }

    private VideoSwitch.Header modes() {
        PlayerIcons.modes(composer);
        return (VideoSwitch.Header) composer.view;
    }

    /** The host's title-row modifier: whatever the host built, the icon takes it as given. */
    private static final Modifier TITLE = new Modifier() {};

    private PlayerIcons.EditorEntry editor() {
        return editorOn(composer);
    }

    private static void measure(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static List<Modifier> chain(Modifier modifier) {
        List<Modifier> parts = new ArrayList<>();
        if (modifier instanceof Modifier.Chain) {
            parts.addAll(chain(((Modifier.Chain) modifier).base));
            parts.addAll(chain(((Modifier.Chain) modifier).next));
        } else parts.add(modifier);
        return parts;
    }

    private static <T> T find(Modifier modifier, Class<T> type) {
        for (Modifier part : chain(modifier)) if (type.isInstance(part)) return type.cast(part);
        return null;
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    /** Draws a view alone on a transparent bitmap the size it measured. */
    private static Bitmap draw(View view) {
        Bitmap image =
                Bitmap.createBitmap(
                        Math.max(1, view.getWidth()),
                        Math.max(1, view.getHeight()),
                        Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        return image;
    }

    // ── The drag strip's height ──────────────────────────────────────────────

    @Test
    public void theStripKeepsItsNativeHeightUnlessThePairShows() {
        assertEquals(32f, PlayerIcons.strip(32f, 1f), 0);
        PlayerIcons.pairShown(true);
        assertEquals(92f, PlayerIcons.strip(32f, 1f), 0);
        assertEquals("a taller native strip is never shrunk", 120f, PlayerIcons.strip(120f, 1f), 0);
        PlayerIcons.pairShown(false);
        assertEquals(32f, PlayerIcons.strip(32f, 2f), 0);
    }

    @Test
    public void theStripGrowsOnlyOnTheMainPlayerWithAudioVideoOnAndASwitchableSong() {
        VideoSwitch.Header row = modes();
        assertEquals(92f, PlayerIcons.strip(32f, 1f), 0);
        for (String mode : new String[] {"LYRICS", "QUEUE"}) {
            com.music.bitchord.data.settings.AppSettings.lastPlayerScreen.setValue(mode);
            row.control.refresh();
            assertEquals(mode, 32f, PlayerIcons.strip(32f, 1f), 0);
        }
        com.music.bitchord.data.settings.AppSettings.lastPlayerScreen.setValue("MAIN");
        row.control.refresh();
        assertEquals(92f, PlayerIcons.strip(32f, 1f), 0);
        VideoSwitch.setEnabled(false);
        assertEquals("Audio/Video off", 32f, PlayerIcons.strip(32f, 1f), 0);
        VideoSwitch.setEnabled(true);
        controller.item = new MediaItem.Builder().setMediaId("tg:123").build();
        row.control.refresh();
        assertEquals("External audio has no other version", 32f, PlayerIcons.strip(32f, 1f), 0);
    }

    @Test
    public void theCaptionsTouchTargetStartsBelowThePairAtEveryFontSize() {
        float[] expected = {92f, 94f, 100f};
        float[] scales = {1f, 1.3f, 2f};
        PlayerIcons.pairShown(true);
        for (int i = 0; i < 3; i++) {
            float strip = PlayerIcons.strip(32f, scales[i]);
            assertEquals("strip at " + scales[i], expected[i], strip, 0);
            // The caption's line sits on the strip's bottom edge; its target is at least 48 dp tall
            // and centred on the line.
            float line = 16 * scales[i] + 1;
            float target = Math.max(48, line);
            float targetTop = strip - line / 2 - target / 2;
            float pairBottom = PlayerIcons.HANDLE_DP + PlayerIcons.PAIR_DP;
            assertTrue("fontScale " + scales[i], targetTop >= pairBottom);
            assertTrue("the handle's own target ends above the pair", PlayerIcons.HANDLE_DP <= 11);
        }
        PlayerIcons.pairShown(false);
    }

    @Test
    public void theLandscapeCaptionsTargetStartsBelowThePairAtEveryFontSize() {
        for (float scale : new float[] {1f, 1.3f, 2f}) {
            float line = 16 * scale + 4;
            float target = Math.max(48, line);
            float gap = PlayerIcons.captionGap(scale);
            assertEquals("whole dp", Math.ceil(gap), gap, 0);
            // The caption starts under the pair row plus the gap.
            float targetTop = 48 + gap + line / 2 - target / 2;
            assertTrue("fontScale " + scale, targetTop >= 48);
            assertTrue("no more space than needed", gap < (target - line) / 2 + 1);
        }
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-night-mdpi")
    public void theLandscapeRowReservesTheGapOnlyWhileThePairShows() {
        org.robolectric.RuntimeEnvironment.setFontScale(1f);
        VideoSwitch.Header row = new VideoSwitch.Header(owner.get(), true);
        content.addView(row);
        measure(row, 852, 400);
        assertEquals(48 + 14, row.getMeasuredHeight());
        VideoSwitch.setEnabled(false);
        row.refresh();
        measure(row, 852, 400);
        assertEquals("hidden reserves nothing", 0, row.getMeasuredHeight());
        VideoSwitch.setEnabled(true);
        VideoSwitch.Header portrait = new VideoSwitch.Header(owner.get());
        content.addView(portrait);
        measure(portrait, 393, 400);
        assertEquals("the portrait strip owns its own spacing", 48, portrait.getMeasuredHeight());
    }

    // ── The pair ─────────────────────────────────────────────────────────────

    @Test
    public void thePairIsCentredBelowTheHandleAndKeepsBothTargets() {
        VideoSwitch.Header row = modes();
        assertSame(
                Alignment.Companion.TopCenter,
                find(composer.modifier, BoxChildDataElement.class).alignment);
        OffsetKt.Padding spacing = find(composer.modifier, OffsetKt.Padding.class);
        assertEquals("below the handle", PlayerIcons.HANDLE_DP, spacing.top, 0);
        assertEquals(0, spacing.bottom, 0);
        measure(row, 393, 200);
        assertEquals(104, row.getMeasuredWidth());
        assertEquals(48, row.getMeasuredHeight());
        assertTrue(row.control.audio.getWidth() >= 48 && row.control.audio.getHeight() >= 48);
        assertTrue(row.control.video.getWidth() >= 48 && row.control.video.getHeight() >= 48);
    }

    @Test
    public void thePairNeedsAudioVideoButNotAutomix() {
        Mix.applied = false;
        VideoSwitch.Header row = modes();
        assertEquals(View.VISIBLE, row.control.getVisibility());
        PlayerIcons.EditorEntry entry = editorOn(fresh());
        assertEquals("No Automix, no editor icon", View.GONE, entry.getVisibility());
    }

    private PlayerIcons.EditorEntry editorOn(ComposerImpl next) {
        PlayerIcons.editorAbove(TITLE, next);
        return (PlayerIcons.EditorEntry) next.view;
    }

    private PlayerIcons.EditorEntry square(ComposerImpl next, float side) {
        PlayerIcons.editorOnSquare(
                new androidx.compose.foundation.layout.SizeElement(side, side, side, side), next);
        return (PlayerIcons.EditorEntry) next.view;
    }

    // ── The editor icon ──────────────────────────────────────────────────────

    /** The first composition has no icon to size yet; the next one has. */
    private ComposerImpl settled() {
        editor();
        ComposerImpl next = fresh();
        editorOn(next);
        return next;
    }

    @Test
    public void theTitleSideIconIsTheTitleRowsOwnPlacementRaisedByItsHeight() {
        ComposerImpl next = settled();
        List<Modifier> parts = chain(next.modifier);
        assertEquals(4, parts.size());
        assertSame("the host's own, unscaled placement", TITLE, parts.get(0));
        OffsetKt.Offset raised = (OffsetKt.Offset) parts.get(1);
        assertEquals(0, raised.x, 0);
        assertEquals("48 dp up, ending at the title row", -48, raised.y, 0);
        androidx.compose.foundation.layout.SizeKt.Wrap centred =
                (androidx.compose.foundation.layout.SizeKt.Wrap) parts.get(2);
        assertSame(Alignment.Companion.CenterHorizontally, centred.alignment);
        assertFalse(centred.unbounded);
        assertEquals(
                "a 48 dp view inside it",
                48,
                ((androidx.compose.foundation.layout.SizeKt.Side) parts.get(3)).dp,
                0);
        assertNull(
                "not a child of the sleeve's box", find(next.modifier, BoxChildDataElement.class));
    }

    @Test
    public void theTitleSideIconReservesNothingWhileHidden() {
        PlayerIcons.EditorEntry entry = editor();
        com.music.bitchord.data.settings.AppSettings.lastPlayerScreen.setValue("LYRICS");
        entry.refresh();
        ComposerImpl next = fresh();
        editorOn(next);
        List<Modifier> parts = chain(next.modifier);
        assertEquals(
                0, find(next.modifier, androidx.compose.foundation.layout.SizeKt.Side.class).dp, 0);
        assertNull(find(next.modifier, OffsetKt.Offset.class));
    }

    @Test
    public void theEditorIconIsAFullTargetWithItsOwnLabel() {
        PlayerIcons.EditorEntry entry = editor();
        measure(entry, 393, 200);
        assertEquals(48, entry.getMeasuredWidth());
        assertEquals(48, entry.getMeasuredHeight());
        assertEquals("Automix Editor", entry.getContentDescription().toString());
        assertTrue(entry.isClickable());
    }

    @Test
    public void theLandscapeIconIsTheBottomOfTheHostsArtworkSquare() {
        square(composer, 300);
        ComposerImpl next = fresh();
        square(next, 300);
        List<Modifier> parts = chain(next.modifier);
        assertEquals(5, parts.size());
        assertTrue(parts.get(0) instanceof androidx.compose.foundation.layout.SizeElement);
        assertEquals(252, ((OffsetKt.Padding) parts.get(1)).top, 0);
        assertSame(
                Alignment.Companion.CenterHorizontally,
                ((androidx.compose.foundation.layout.SizeKt.Wrap) parts.get(2)).alignment);
        assertEquals(48, ((androidx.compose.foundation.layout.SizeKt.Side) parts.get(3)).dp, 0);
        assertEquals(
                "stacked above the artwork it is emitted ahead of",
                1,
                ((androidx.compose.ui.ZIndexElement) parts.get(4)).z,
                0);
    }

    @Test
    public void theLandscapeIconStaysHiddenForAnythingButAPlainSquare() {
        editor();
        for (Modifier other :
                new Modifier[] {
                    TITLE,
                    new androidx.compose.foundation.layout.SizeElement(300, 200, 300, 200),
                    new androidx.compose.foundation.layout.SizeElement(48, 48, 48, 48),
                    null
                }) {
            ComposerImpl next = fresh();
            PlayerIcons.editorOnSquare(other, next);
            assertNull(next.view);
            assertEquals(1, next.groupsOpen);
            assertEquals(1, next.groupsClosed);
        }
    }

    @Test
    public void theStatsLineIsLiftedOnlyWhileTheIconShows() {
        Modifier stats = Modifier.Companion.$$INSTANCE;
        assertSame("Nothing shown yet", stats, PlayerIcons.liftPortrait(stats));
        PlayerIcons.EditorEntry entry = editor();
        assertEquals(
                20,
                find((Modifier) PlayerIcons.liftPortrait(stats), OffsetKt.Padding.class).bottom,
                0);
        OffsetKt.Padding landscape =
                find((Modifier) PlayerIcons.liftLandscape(stats), OffsetKt.Padding.class);
        assertEquals(40, landscape.bottom, 0);
        assertEquals(0, landscape.top + landscape.start + landscape.end, 0);
        Mix.applied = false;
        entry.refresh();
        assertSame("Without Automix", stats, PlayerIcons.liftPortrait(stats));
        assertSame(stats, PlayerIcons.liftLandscape(stats));
    }

    @Test
    public void theLiftedStatsEndAboveTheIconWithTheHostsOwnSpacing() {
        // Portrait: the slot ends 20 dp above the title row, the stats 8 dp above the slot's end.
        // The icon is the 48 dp above the title row.
        float portraitStats = -(20 + 8) - PlayerIcons.PORTRAIT_LIFT_DP;
        assertTrue(portraitStats <= -PlayerIcons.EDITOR_DP);
        assertTrue("no lift is not enough", -(20 + 8) > -PlayerIcons.EDITOR_DP);
        // Landscape: the icon is the square's bottom 48 dp; the stats end 8 dp above it.
        float landscapeStats = -8 - PlayerIcons.LANDSCAPE_LIFT_DP;
        assertTrue(landscapeStats <= -PlayerIcons.EDITOR_DP);
        assertTrue("no lift is not enough", -8 > -PlayerIcons.EDITOR_DP);
    }

    // ── Balanced groups ──────────────────────────────────────────────────────

    @Test
    public void everyEmissionIsOneBalancedGroupShownOrHidden() {
        PlayerIcons.modes(composer);
        assertEquals(1, composer.groupsOpen);
        assertEquals(1, composer.groupsClosed);
        for (String mode : new String[] {"LYRICS", "MAIN"}) {
            com.music.bitchord.data.settings.AppSettings.lastPlayerScreen.setValue(mode);
            ComposerImpl next = fresh();
            PlayerIcons.modes(next);
            PlayerIcons.editorAbove(TITLE, next);
            assertEquals(mode, 2, next.groupsOpen);
            assertEquals(mode, 2, next.groupsClosed);
        }
    }

    @Test
    public void aChangedHostStopsTheIconsWithoutThrowingOrLeavingAGroupOpen() {
        AndroidView_androidKt.failure = new LinkageError("host ABI");
        PlayerIcons.modes(composer);
        assertEquals(composer.groupsOpen, composer.groupsClosed);
        assertEquals(1, composer.groupsOpen);
        assertNull(composer.view);
        PlayerIcons.editorAbove(TITLE, composer);
        PlayerIcons.modes(composer);
        assertEquals("The latch stops further attempts", 1, composer.groupsOpen);
        assertEquals(composer.groupsOpen, composer.groupsClosed);
        AndroidView_androidKt.failure = null;
        Modifier stats = Modifier.Companion.$$INSTANCE;
        assertSame(stats, PlayerIcons.liftPortrait(stats));
    }

    @Test
    public void withoutHostSymbolsNothingIsAddedAndTheLayoutIsUntouched() {
        PlayerIcons.forTest("");
        PlayerIcons.modes(composer);
        PlayerIcons.editorAbove(TITLE, composer);
        assertEquals(0, composer.groupsOpen);
        assertNull(composer.view);
        assertEquals(0, content.getChildCount());
        assertEquals(32f, PlayerIcons.strip(32f, 1f), 0);
    }

    @Test
    public void aChromePlayerWithoutItsBlendHelperIsRefusedNotShownPlain() {
        PlayerIcons.forTest(CHROME.replaceAll("\nblend=[^\n]*", ""));
        PlayerIcons.modes(composer);
        assertNull(composer.view);
        assertEquals(0, composer.groupsOpen);
    }

    // ── Ink ──────────────────────────────────────────────────────────────────

    @Test
    public void theTintedPlayerInksTheGlyphsInItsTintAtThreeQuarters() {
        composer.tint = new Color(0xffefbb77L << 32);
        VideoSwitch.Header row = modes();
        assertNull(find(composer.modifier, PlayerChromeKt.Additive.class));
        measure(row, 393, 100);
        row.control.audio.setSelected(false);
        Bitmap image = draw(row.control.audio);
        int glyph = glyphPixel(image);
        assertEquals(0xbf, alpha(glyph), 1);
        assertEquals(0xefbb77, glyph & 0xffffff);
        assertEquals("No blend without chrome", 0, PlayerChromeKt.blends);
    }

    @Test
    public void theChromePlayerInksWhiteBlendedWithTheHostsOwnHelper() {
        PlayerIcons.forTest(CHROME);
        composer.chrome = true;
        VideoSwitch.Header row = modes();
        assertNotNull(
                "The additive blend is on the icon",
                find(composer.modifier, PlayerChromeKt.Additive.class));
        assertEquals(1, PlayerChromeKt.blends);
        measure(row, 393, 100);
        row.control.audio.setSelected(false);
        int glyph = glyphPixel(draw(row.control.audio));
        assertEquals("Chrome drops the glyph to 0.60", 0x99, alpha(glyph), 1);
        assertEquals(0xffffff, glyph & 0xffffff);
        assertNotNull(find(editorModifier(), PlayerChromeKt.Additive.class));
    }

    private Modifier editorModifier() {
        ComposerImpl next = fresh();
        next.chrome = true;
        PlayerIcons.editorAbove(TITLE, next);
        return next.modifier;
    }

    @Test
    public void aChromePlayerWithTheBlendOffInksWhiteAtThreeQuartersWithNoBlend() {
        PlayerIcons.forTest(CHROME);
        composer.chrome = false;
        VideoSwitch.Header row = modes();
        assertNull(find(composer.modifier, PlayerChromeKt.Additive.class));
        measure(row, 393, 100);
        row.control.audio.setSelected(false);
        int glyph = glyphPixel(draw(row.control.audio));
        assertEquals(0xbf, alpha(glyph), 1);
        assertEquals(0xffffff, glyph & 0xffffff);
    }

    @Test
    public void aPlayerWithNeitherLocalGetsPlainWhite() {
        PlayerIcons.forTest(BASE);
        VideoSwitch.Header row = modes();
        measure(row, 393, 100);
        row.control.audio.setSelected(false);
        int glyph = glyphPixel(draw(row.control.audio));
        assertEquals(0xbf, alpha(glyph), 1);
        assertEquals(0xffffff, glyph & 0xffffff);
    }

    /** The audio glyph's top arc crosses the view's centre column near the top of the icon. */
    private static int glyphPixel(Bitmap image) {
        int x = image.getWidth() / 2, best = 0;
        for (int y = 0; y < image.getHeight() / 2; y++)
            if (alpha(image.getPixel(x, y)) > alpha(best)) best = image.getPixel(x, y);
        return best;
    }

    // ── The lit tile ─────────────────────────────────────────────────────────

    @Test
    public void theSelectedModeIsATileWithTheGlyphCutOutOfIt() {
        composer.tint = new Color(0xffefbb77L << 32);
        VideoSwitch.Header row = modes();
        measure(row, 393, 100);
        for (boolean video : new boolean[] {false, true}) {
            View icon = video ? row.control.video : row.control.audio;
            icon.setSelected(false);
            Bitmap plain = draw(icon);
            int cx = plain.getWidth() / 2, cy = plain.getHeight() / 2;
            int stroke = strokeRow(plain, cx);
            assertTrue("the unlit glyph is drawn", alpha(plain.getPixel(cx, stroke)) > 0);
            icon.setSelected(true);
            Bitmap lit = draw(icon);
            int tile = lit.getPixel(cx - 15, cy - 15);
            assertEquals("tile is the tint at 0.62", 0x9e, alpha(tile), 2);
            assertEquals(0xefbb77, tile & 0xffffff);
            assertEquals("the glyph is a hole in it", 0, alpha(lit.getPixel(cx, stroke)));
            assertEquals("nothing outside the tile", 0, alpha(lit.getPixel(cx - 24, cy)));
            assertEquals("its corners are rounded", 0, alpha(lit.getPixel(cx - 17, cy - 17)));
        }
    }

    /** The row where the glyph's strongest pixel in this column is, above the centre. */
    private static int strokeRow(Bitmap image, int x) {
        int best = 0;
        for (int y = 0; y < image.getHeight() / 2; y++)
            if (alpha(image.getPixel(x, y)) > alpha(image.getPixel(x, best))) best = y;
        return best;
    }

    @Test
    public void anUnselectedModeIsOnlyTheGlyphAndHasNoTile() {
        composer.tint = new Color(0xffefbb77L << 32);
        VideoSwitch.Header row = modes();
        measure(row, 393, 100);
        row.control.audio.setSelected(false);
        Bitmap image = draw(row.control.audio);
        int cx = image.getWidth() / 2, cy = image.getHeight() / 2;
        assertEquals("no tile", 0, alpha(image.getPixel(cx - 15, cy - 15)));
        assertTrue("the glyph is drawn", alpha(glyphPixel(image)) > 0);
    }

    @Test
    public void theLitTileUnderChromeIsTheDimmerNativeOne() {
        PlayerIcons.forTest(CHROME);
        composer.chrome = true;
        VideoSwitch.Header row = modes();
        measure(row, 393, 100);
        row.control.audio.setSelected(true);
        Bitmap image = draw(row.control.audio);
        int cx = image.getWidth() / 2, cy = image.getHeight() / 2;
        assertEquals(
                "0.62 less the 0.15 chrome drop", 0x78, alpha(image.getPixel(cx - 15, cy - 15)), 2);
    }

    @Test
    public void theEditorIconIsOnlyAGlyphAtTheNativeAlpha() {
        composer.tint = new Color(0xffefbb77L << 32);
        PlayerIcons.EditorEntry entry = editor();
        measure(entry, 393, 100);
        Bitmap image = draw(entry);
        int cx = image.getWidth() / 2, cy = image.getHeight() / 2;
        assertEquals("no tile", 0, alpha(image.getPixel(cx - 15, cy - 15)));
        // A 1.8 px bar straddling two pixel columns covers each by 0.9.
        int bar = image.getPixel(cx, cy);
        assertTrue(alpha(bar) >= 0xa0 && alpha(bar) <= 0xbf);
        for (int shift : new int[] {16, 8, 0})
            assertEquals(0xefbb77 >> shift & 0xff, bar >> shift & 0xff, 4);
    }

    // ── Sizes ────────────────────────────────────────────────────────────────

    private void everyTargetIsAtLeast48dp() {
        for (float fontScale : new float[] {1f, 1.3f, 2f}) {
            org.robolectric.RuntimeEnvironment.setFontScale(fontScale);
            VideoSwitch.Header row = modes();
            PlayerIcons.EditorEntry entry = editorOn(fresh());
            measure(row, 393, 500);
            measure(entry, 393, 500);
            assertTrue(row.control.audio.getWidth() >= 48 && row.control.audio.getHeight() >= 48);
            assertTrue(row.control.video.getWidth() >= 48 && row.control.video.getHeight() >= 48);
            assertTrue(entry.getWidth() >= 48 && entry.getHeight() >= 48);
            composer = fresh();
        }
    }

    @Test
    public void targetsKeep48dpAtEveryFontSizeInPortrait() {
        everyTargetIsAtLeast48dp();
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-night-mdpi")
    public void targetsKeep48dpAtEveryFontSizeInLandscape() {
        everyTargetIsAtLeast48dp();
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-notnight-mdpi")
    public void theLightThemeInksTheSameBecauseThePlayerSitsOverArtwork() {
        VideoSwitch.Header row = modes();
        measure(row, 393, 100);
        row.control.audio.setSelected(false);
        int glyph = glyphPixel(draw(row.control.audio));
        assertEquals(0xffffff, glyph & 0xffffff);
    }

    @Test
    public void thePairAndTheIconDoNotAnimate() {
        VideoSwitch.Header row = modes();
        PlayerIcons.EditorEntry entry = editorOn(fresh());
        assertNull(row.control.getAnimation());
        assertNull(entry.getAnimation());
        assertFalse(row.control.audio.animate().getStartDelay() != 0);
    }
}
