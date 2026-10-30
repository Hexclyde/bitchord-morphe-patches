package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/** Run native bitmap captures separately from interaction tests on headless hosts. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AudioFormatPreviewTest {
    @Test
    public void rendersDarkPage() throws Exception {
        render("download-formats-dark.png");
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-notnight-mdpi")
    public void rendersLightPage() throws Exception {
        render("download-formats-light.png");
    }

    private void render(String filename) throws Exception {
        ActivityController<AudioFormatSetupActivity> controller =
                Robolectric.buildActivity(AudioFormatSetupActivity.class).setup();
        try {
            View decor = controller.get().getWindow().getDecorView();
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
        }
    }
}
