package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
                        76
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

    private static int alpha(Bitmap bitmap, int x, int y) {
        return Color.alpha(bitmap.getPixel(x, y));
    }

    @Test
    public void theCollapsedCapsuleIsClearGlassWithAGlyphYouCanSee() {
        // A pale blue tint, as the player takes from artwork.
        Bitmap bitmap = paint(0xffd6e0ee, true);
        int w = bitmap.getWidth();
        // Inside the disc, away from the glyph and the rim: translucent, not a filled disc.
        int clear = alpha(bitmap, w / 2, Math.round(w * 0.12f));
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
    public void withoutTheHostBlurItIsStillTranslucent() {
        Bitmap bitmap = paint(0xffd6e0ee, false);
        int clear = alpha(bitmap, bitmap.getWidth() / 2, Math.round(bitmap.getWidth() * 0.12f));
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
    public void aFadedCapsulePaintsNothingButStaysThere() {
        capsule.microphone().performClick();
        capsule.close(true);
        Bitmap bitmap = paint(0xffd6e0ee, true);
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++)
                assertEquals("pixel " + x + "," + y, 0, alpha(bitmap, x, y));
        assertTrue(capsule.microphone().isClickable());
    }
}
