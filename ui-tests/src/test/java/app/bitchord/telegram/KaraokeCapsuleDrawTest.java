package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** What the capsule really paints: a clear glass with a legible glyph, never a solid disc. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class KaraokeCapsuleDrawTest {
    private KaraokeCapsule capsule;

    @Before
    public void create() {
        capsule = new KaraokeCapsule(RuntimeEnvironment.getApplication());
    }

    private Bitmap paint(int tint, boolean blurred) {
        capsule.tint(tint);
        capsule.blurred(blurred);
        int size =
                Math.round(
                        48
                                * RuntimeEnvironment.getApplication()
                                        .getResources()
                                        .getDisplayMetrics()
                                        .density);
        capsule.measure(
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST));
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        capsule.draw(new Canvas(bitmap));
        return bitmap;
    }

    private int discTop() {
        return Math.round(discBounds().top);
    }

    private RectF discBounds() {
        RectF bounds = new RectF();
        capsule.visualBounds(bounds);
        return bounds;
    }

    private float dp() {
        return RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
    }

    private static int alpha(Bitmap bitmap, int x, int y) {
        return Color.alpha(bitmap.getPixel(x, y));
    }

    @Test
    public void theCollapsedCapsuleIsClearGlassWithAGlyphYouCanSee() {
        // A pale blue tint, as the player takes from artwork.
        Bitmap bitmap = paint(0xffd6e0ee, true);
        int w = bitmap.getWidth();
        // Inside the disc, away from the glyph and the rim: translucent, not a filled disc.
        int clear = alpha(bitmap, w / 2, discTop() + Math.round(4 * dp()));
        assertTrue("glass alpha " + clear, clear > 0 && clear <= 40);
        int strong = 0, any = 0;
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < w; x++) {
                int a = alpha(bitmap, x, y);
                if (a >= 180) strong++;
                if (a > 0) any++;
            }
        assertTrue("glyph strokes are drawn: " + strong, strong > 60);
        assertTrue("the glass is not almost everything", strong < any / 3);
    }

    @Test
    public void theDiscLeavesTouchSpaceAroundItsNativeActionSize() {
        Bitmap bitmap = paint(0xffd6e0ee, true);
        int middle = Math.round(discBounds().centerY());
        assertEquals(0, alpha(bitmap, 1, middle));
        int inset =
                Math.round(
                        7
                                * RuntimeEnvironment.getApplication()
                                        .getResources()
                                        .getDisplayMetrics()
                                        .density);
        assertTrue(alpha(bitmap, inset + 2, middle) > 0);
    }

    @Test
    public void withoutTheHostBlurItIsStillTranslucent() {
        Bitmap bitmap = paint(0xffd6e0ee, false);
        int clear = alpha(bitmap, bitmap.getWidth() / 2, discTop() + Math.round(4 * dp()));
        assertTrue("fallback glass alpha " + clear, clear > 0 && clear <= 60);
    }

    @Test
    public void aDarkTintGivesADarkGlyphOnLightArtwork() {
        Bitmap bitmap = paint(0xff202830, true);
        int dark = 0;
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int p = bitmap.getPixel(x, y);
                if (Color.alpha(p) >= 180 && Color.red(p) < 80) dark++;
            }
        assertTrue("dark glyph pixels " + dark, dark > 60);
    }

    @Test
    public void aClosedCapsulePaintsADimDiscAndGlyphAndStaysTouchable() {
        capsule.microphone().performClick();
        capsule.close(false);
        Bitmap bright = paint(0xffd6e0ee, true);
        capsule.close(true);
        Bitmap resting = paint(0xffd6e0ee, false);
        long brightAlpha = 0, restingAlpha = 0;
        for (int y = 0; y < resting.getHeight(); y++)
            for (int x = 0; x < resting.getWidth(); x++) {
                brightAlpha += alpha(bright, x, y);
                restingAlpha += alpha(resting, x, y);
            }
        assertTrue("the microphone remains visible", restingAlpha > 0);
        assertTrue("the whole disc and glyph are dim", restingAlpha < brightAlpha / 2);
        assertTrue(capsule.microphone().isClickable());
    }

    @Test
    public void theCollapsedDiscIs34dpAndSitsOnTheBottomOfItsTarget() {
        paint(0xffd6e0ee, true);
        RectF disc = discBounds();
        assertEquals(7 * dp(), disc.left, 0.5f);
        assertEquals(14 * dp(), disc.top, 0.5f);
        assertEquals(41 * dp(), disc.right, 0.5f);
        assertEquals(48 * dp(), disc.bottom, 0.5f);
        assertEquals(34 * dp(), disc.width(), 0.5f);
        assertEquals(34 * dp(), disc.height(), 0.5f);
        assertEquals(96, capsule.microphone().getWidth());
        assertEquals(96, capsule.microphone().getHeight());
    }

    @Test
    public void theOpenCapsuleFillsItsViewAndTheInsetEasesBetween() {
        capsule.microphone().performClick();
        int height = Math.round(320 * dp());
        capsule.measure(
                View.MeasureSpec.makeMeasureSpec(Math.round(48 * dp()), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        RectF disc = discBounds();
        assertEquals(0f, disc.left, 0.5f);
        assertEquals(0f, disc.top, 0.5f);
        assertEquals(capsule.getWidth(), disc.right, 0.5f);
        assertEquals(capsule.getHeight(), disc.bottom, 0.5f);
    }

    @Test
    public void theFrostClipIsExactlyTheDrawnDisc() {
        paint(0xffd6e0ee, true);
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        capsule.clipVisual(canvas);
        RectF disc = discBounds();
        android.graphics.Rect clip = canvas.getClipBounds();
        assertEquals(disc.left, clip.left, 1f);
        assertEquals(disc.top, clip.top, 1f);
        assertEquals(disc.right, clip.right, 1f);
        assertEquals(disc.bottom, clip.bottom, 1f);
    }

    @Test
    public void theMicrophoneGlyphIsCentredInTheDiscNotTheTarget() {
        Bitmap bitmap = paint(0xffd6e0ee, true);
        RectF disc = discBounds();
        int top = Integer.MAX_VALUE, bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = Math.round(disc.left + 8); x < disc.right - 8; x++)
                if (alpha(bitmap, x, y) >= 180) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
        assertTrue("a glyph is drawn", bottom > top);
        assertEquals((top + bottom) / 2f, disc.centerY(), 3f * dp());
    }

    /**
     * The vertical centre, in pixels from the top, of the strong glyph pixels in the bottom 48 dp.
     */
    private float glyphCentre(Bitmap bitmap) {
        int top = Integer.MAX_VALUE, bottom = -1;
        for (int y = Math.max(0, bitmap.getHeight() - Math.round(48 * dp()));
                y < bitmap.getHeight();
                y++)
            for (int x = 0; x < bitmap.getWidth(); x++)
                if (alpha(bitmap, x, y) >= 180) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
        assertTrue("a glyph is drawn", bottom > top);
        return (top + bottom) / 2f;
    }

    @Test
    public void theGlyphKeepsItsBaselineSeventeenDpAboveTheBottomWhileOpening() {
        capsule.tint(0xffffffff);
        capsule.measure(
                View.MeasureSpec.makeMeasureSpec(Math.round(48 * dp()), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(48 * dp()), View.MeasureSpec.EXACTLY));
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        Bitmap closed =
                Bitmap.createBitmap(
                        capsule.getWidth(), capsule.getHeight(), Bitmap.Config.ARGB_8888);
        capsule.draw(new Canvas(closed));
        float closedFromBottom = closed.getHeight() - glyphCentre(closed);

        capsule.microphone().performClick();
        capsule.measure(
                View.MeasureSpec.makeMeasureSpec(Math.round(48 * dp()), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(320 * dp()), View.MeasureSpec.AT_MOST));
        capsule.layout(0, 0, capsule.getMeasuredWidth(), capsule.getMeasuredHeight());
        Bitmap open =
                Bitmap.createBitmap(
                        capsule.getWidth(), capsule.getHeight(), Bitmap.Config.ARGB_8888);
        capsule.draw(new Canvas(open));
        float openFromBottom = open.getHeight() - glyphCentre(open);

        assertEquals(17 * dp(), closedFromBottom, 3f);
        assertEquals(
                "the glyph does not drift with the capsule opening",
                closedFromBottom,
                openFromBottom,
                1.5f);
    }

    private android.graphics.Rect glyphBounds(View mic) {
        Bitmap bitmap =
                Bitmap.createBitmap(mic.getWidth(), mic.getHeight(), Bitmap.Config.ARGB_8888);
        mic.draw(new Canvas(bitmap));
        android.graphics.Rect ink = new android.graphics.Rect();
        boolean found = false;
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (alpha(bitmap, x, y) < 180) continue;
                if (!found) {
                    ink.set(x, y, x + 1, y + 1);
                    found = true;
                } else ink.union(x, y, x + 1, y + 1);
            }
        bitmap.recycle();
        assertTrue("native-size glyph is drawn", found);
        return ink;
    }

    @Test
    public void openingKeepsTheNativeGlyphSize() {
        SoundExtras.singEnabled = false;
        paint(Color.WHITE, true);
        android.graphics.Rect closed = glyphBounds(capsule.microphone());
        capsule.microphone().performClick();
        SoundExtras.singEnabled = false;
        paint(Color.WHITE, true);
        android.graphics.Rect opened = glyphBounds(capsule.microphone());
        assertEquals("same native glyph width", closed.width(), opened.width());
        assertEquals("same native glyph height", closed.height(), opened.height());
    }
}
