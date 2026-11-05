package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.music.bitchord.data.settings.AppSettings;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** The batch page at a real 393 x 852 dp phone viewport, drawn with native graphics. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class BatchDownloadLayoutTest {
    static final int WIDTH = 393, HEIGHT = 852;
    final String token = "layout-batch";
    ActivityController<Activity> controller;
    Activity activity;
    BatchDownloadPage page;
    BatchDownloadPageTest.Fake fake;
    Locale before;
    SoulseekTrack track;

    @Before
    public void open() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        controller = Robolectric.buildActivity(Activity.class);
        // The product's download screens run without an action bar; set that before creation.
        controller.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
        controller.setup().visible();
        activity = controller.get();
        assertNull("no framework title bar", activity.getActionBar());
        BatchDownloadPageTest.offer(token);
    }

    @After
    public void close() {
        AppSettings.themeMode.setValue("SYSTEM");
        if (page != null) page.onDestroy();
        SoulseekDownloadChoice.forget(token);
        DownloadBatch.forget(token);
        controller.pause().stop().destroy();
        Locale.setDefault(before);
    }

    /** Builds the page for one theme and font scale with twenty enabled sources. */
    void show(String theme, float fontScale) throws Exception {
        AppSettings.themeMode.setValue(theme);
        android.content.res.Configuration config =
                new android.content.res.Configuration(activity.getResources().getConfiguration());
        config.fontScale = fontScale;
        activity.getResources()
                .updateConfiguration(config, activity.getResources().getDisplayMetrics());
        page =
                new BatchDownloadPage(
                        activity,
                        new Intent().putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token));
        page.onCreate(null);
        // BatchDownloadActivity attaches the page exactly like this.
        activity.setContentView(page.view);
        fake = new BatchDownloadPageTest.Fake();
        fake.source(
                "a",
                BatchDownloadPageTest.song(
                        "a", 1, "Second", "Second artist", "FLAC 24-bit / 96 kHz"),
                BatchDownloadPageTest.song("a", 2, "Second", "Second artist", "MP3 320kbps"),
                BatchDownloadPageTest.song("a", 3, "Second", "Second artist", ""));
        for (int i = 1; i < 20; i++) fake.source("s" + i);
        fake.soulseek = true;
        track = BatchDownloadPageTest.track("studio-peer", "Second", 48_000_000L, 301, 24, 96000);
        fake.result =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(track),
                        "session",
                        new AtomicBoolean(true)::get,
                        (app, file, session, now) -> new AtomicInteger().incrementAndGet());
        page.searcher = fake;
        Field foreground = BatchDownloadPage.class.getDeclaredField("foreground");
        foreground.setAccessible(true);
        foreground.setBoolean(page, true);
        page.onStart();
        await(() -> tag("batch-find:s19") != null);
        layout();
    }

    void await(BooleanSupplier ready) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (ready.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("timed out waiting for the page");
    }

    View tag(String exact) {
        return page.view.findViewWithTag(exact);
    }

    /** Lays the whole attached window out at the phone size, as the real window does. */
    void layout() {
        shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, WIDTH, HEIGHT);
        assertTrue("the page is attached to the window", page.view.isAttachedToWindow());
        assertEquals(WIDTH, page.view.getWidth());
    }

    /** Visible bounds inside the window. */
    void assertFullyVisible(View view) {
        Rect seen = new Rect();
        assertTrue(String.valueOf(view.getTag()), view.getGlobalVisibleRect(seen));
        assertEquals(view.getTag() + " height", view.getHeight(), seen.height());
        assertEquals(view.getTag() + " width", view.getWidth(), seen.width());
        assertTrue(seen.top >= 0 && seen.bottom <= HEIGHT);
    }

    /** Position of a view inside the scrolling content. */
    int top(View view) {
        int y = 0;
        for (View v = view; v != page.view; v = (View) v.getParent()) y += v.getTop();
        return y;
    }

    int rows() {
        List<View> found = new ArrayList<>();
        BatchDownloadPageTest.collect(page.view, "batch-candidate:", found);
        return found.size();
    }

    void openSecondTrack() throws Exception {
        tag("batch-toggle:1").performClick();
        await(() -> rows() == 4 && !BatchDownloadPageTest.allText(page.view).contains("Searching"));
        tag("batch-choose:1:" + BatchDownloadPageTest.key("a", 1)).performClick();
        layout();
    }

    void scrollTo(View tile) {
        ScrollView scroll = (ScrollView) page.view;
        int max = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
        scroll.scrollTo(0, Math.min(top(tile), max));
        shadowOf(Looper.getMainLooper()).idle();
    }

    void save(String name) throws Exception {
        View decor = activity.getWindow().getDecorView();
        assertEquals(WIDTH, decor.getWidth());
        assertEquals(HEIGHT, decor.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        File output = new File("target/previews/" + name + ".png");
        output.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        assertTrue(output.length() > 0);
    }

    static double luminance(int color) {
        double result = 0;
        int[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
        double[] weights = {.2126, .7152, .0722};
        for (int i = 0; i < 3; i++) {
            double c = channels[i] / 255.0;
            result += weights[i] * (c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4));
        }
        return result;
    }

    static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    void assertTouchTargets() {
        List<View> controls = new ArrayList<>();
        BatchDownloadPageTest.collect(page.view, "batch-", controls);
        int checked = 0;
        for (View control : controls) {
            String name = String.valueOf(control.getTag());
            if (name.startsWith("batch-candidate:")
                    || name.startsWith("batch-candidates:")
                    || name.startsWith("batch-preview:")
                    || name.startsWith("batch-selected:")) continue;
            if (control.getVisibility() != View.VISIBLE) continue;
            checked++;
            assertTrue(name, control.isClickable() || control instanceof android.widget.EditText);
            assertTrue(name + " height " + control.getHeight(), control.getHeight() >= 48);
            if (control instanceof CompoundButton || control instanceof android.widget.ImageButton)
                assertTrue(name + " width " + control.getWidth(), control.getWidth() >= 48);
        }
        assertTrue(checked >= 8);
    }

    void assertReadablePalette(boolean dark) {
        assertEquals(dark, page.downloadStyle.dark);
        assertEquals(dark ? Color.BLACK : Color.WHITE, page.downloadStyle.background);
        assertEquals(
                page.downloadStyle.background,
                ((android.graphics.drawable.ColorDrawable) page.view.getBackground()).getColor());
        View tile = (View) tag("batch-candidates:1").getParent();
        List<TextView> texts = new ArrayList<>();
        gather(tile, texts);
        assertTrue(texts.size() > 10);
        for (TextView text : texts) {
            int color = text.getCurrentTextColor();
            String name = text.getText().toString();
            assertTrue(
                    name,
                    color == page.downloadStyle.foreground
                            || color == page.downloadStyle.secondary);
            // Measured against the surface the text really sits on.
            assertTrue(name, contrast(color, surface(text)) >= 4.5);
        }
        // Flat candidate rows add no fill of their own: they sit on the native card.
        View row = tag("batch-candidate:" + BatchDownloadPageTest.key("a", 1));
        assertNull(row.getBackground());
        assertNull(((ViewGroup) row).getChildAt(1).getBackground());
        assertEquals(page.downloadStyle.card, surface(row));
    }

    /** The nearest opaque colour behind a view. */
    static int surface(View view) {
        View current = view;
        while (current != null) {
            Drawable back = current.getBackground();
            if (back instanceof ColorDrawable
                    && Color.alpha(((ColorDrawable) back).getColor()) == 255)
                return ((ColorDrawable) back).getColor();
            if (back instanceof GradientDrawable) {
                ColorStateList fill = ((GradientDrawable) back).getColor();
                if (fill != null && Color.alpha(fill.getDefaultColor()) == 255)
                    return fill.getDefaultColor();
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        throw new AssertionError("no opaque surface behind " + view);
    }

    static void gather(View view, List<TextView> out) {
        if (view instanceof TextView
                && !(view instanceof Button)
                && !(view instanceof CompoundButton)) out.add((TextView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                gather(((ViewGroup) view).getChildAt(i), out);
    }

    /** With twenty sources enabled the first requested track still opens the list. */
    void assertFirstTrackVisible() {
        View toggle = tag("batch-toggle:0");
        ViewGroup card = (ViewGroup) tag("batch-candidates:0").getParent();
        View duration = card.getChildAt(1);
        ScrollView scroll = (ScrollView) page.view;
        assertEquals(0, scroll.getScrollY());
        assertTrue(
                "first track header in the top 60% of the viewport",
                top(duration) + duration.getHeight() <= scroll.getHeight() * 6 / 10);
        assertTrue(top(toggle) + toggle.getHeight() <= scroll.getHeight());
        assertFullyVisible(duration);
        assertFullyVisible(toggle);
    }

    void assertPhoneLayout() {
        // The twenty source capsules sit in one strip, so the first tracks stay on screen.
        View strip = (View) tag("batch-find:s19").getParent().getParent();
        assertTrue(strip instanceof HorizontalScrollView);
        assertSame(tag("batch-find:s1").getParent(), tag("batch-find:s19").getParent());
        assertTrue("strip is one row of capsules", strip.getHeight() <= 96);
        View first = tag("batch-toggle:0");
        assertTrue(first.getVisibility() == View.VISIBLE);
        ScrollView scroll = (ScrollView) page.view;
        View tile = (View) tag("batch-candidates:1").getParent();
        scrollTo(tile);
        View radio = tag("batch-choose:1:" + BatchDownloadPageTest.key("a", 1));
        int y = top(radio) - scroll.getScrollY();
        if (y < 0 || y + radio.getHeight() > scroll.getHeight()) {
            // Large text pushes the list below the header; bring its first row to the top.
            scrollTo((View) radio.getParent().getParent());
        }
        // The real window shows the selected candidate's controls, not only the header.
        assertFullyVisible(radio);
        assertTrue(((CompoundButton) radio).isChecked());
        View meta = ((ViewGroup) radio.getParent()).getChildAt(1);
        assertTrue("metadata sits beside the radio", meta.getLeft() >= radio.getRight());
        assertTrue(
                "metadata fits the card", meta.getRight() <= ((View) radio.getParent()).getWidth());
    }

    static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
            count++;
        return count;
    }

    /** One quality copy, no repeated title, one control row, and candidates near the top. */
    void assertCompactComposition() {
        String key = BatchDownloadPageTest.key("a", 1);
        View row = tag("batch-candidate:" + key);
        String shown = BatchDownloadPageTest.allText(row);
        assertEquals(shown, 1, occurrences(shown, "FLAC 24-bit / 96 kHz"));
        assertFalse(shown, shown.contains("Bit depth"));
        assertFalse(shown, shown.contains("Sample rate"));
        assertFalse(shown, shown.contains("Second · Second artist"));
        assertTrue(shown, shown.contains("Addon a"));
        assertTrue(shown, shown.contains("5:01 · File size: Not reported"));
        // Everything reported is still spoken by the radio.
        String spoken = tag("batch-choose:1:" + key).getContentDescription().toString();
        assertTrue(spoken, spoken.contains("Bit depth: 24 bits"));
        assertTrue(spoken, spoken.contains("Sample rate: 96 kHz"));
        assertTrue(spoken, spoken.contains("File size: Not reported"));
        // The selection summary names the file once and keeps the request's 5:02 out of it.
        String summary = BatchDownloadPageTest.allText(tag("batch-selected:1"));
        assertEquals(summary, 1, occurrences(summary, "FLAC 24-bit / 96 kHz"));
        assertTrue(summary, summary.contains("5:01"));
        assertFalse(summary, summary.contains("5:02"));
        assertFalse(summary, summary.contains("Second · Second artist"));
        assertEquals(1, BatchDownloadPageTest.named(page.view, "Second · Second artist").size());
        assertEquals(1, BatchDownloadPageTest.named(page.view, "Duration · 5:02").size());
        // One row holds the wide toggle and the 48dp clear control; no text Unselect button.
        View toggle = tag("batch-toggle:1"), clear = tag("batch-unselect:1");
        assertSame(toggle.getParent(), clear.getParent());
        assertTrue(clear instanceof android.widget.ImageButton);
        assertEquals(48, clear.getWidth());
        assertEquals(48, clear.getHeight());
        assertTrue(toggle.getWidth() > clear.getWidth() * 3);
        assertTrue(BatchDownloadPageTest.named(page.view, "Unselect").isEmpty());
        assertTrue(clear.getContentDescription().toString().startsWith("Unselect"));
        assertFalse(
                BatchDownloadPageTest.allText(page.view)
                        .contains("Reported formats are unverified"));
        // The first candidate sits in the upper part of the opened card.
        View tile = (View) tag("batch-candidates:1").getParent();
        assertTrue(
                top(tag("batch-choose:1:" + key)) - top(tile)
                        <= ((ScrollView) page.view).getHeight() * 6 / 10);
    }

    @Test
    public void lightPhoneLayoutKeepsTracksAndSelectionVisible() throws Exception {
        show("LIGHT", 1f);
        assertFirstTrackVisible();
        openSecondTrack();
        assertCompactComposition();
        assertPhoneLayout();
        assertTouchTargets();
        assertReadablePalette(false);
        save("batch-light");
        tag("batch-toggle:1").performClick();
        layout();
        assertEquals(View.GONE, tag("batch-candidates:1").getVisibility());
        assertTrue(
                BatchDownloadPageTest.allText(tag("batch-selected:1"))
                        .contains("Selected audio file · Addon a"));
        scrollTo((View) tag("batch-selected:1").getParent());
        assertFullyVisible(tag("batch-selected:1"));
        assertFullyVisible(tag("batch-toggle:1"));
        save("batch-light-collapsed");
    }

    @Test
    public void darkPhoneLayoutKeepsTracksAndSelectionVisible() throws Exception {
        show("DARK", 1f);
        assertFirstTrackVisible();
        openSecondTrack();
        assertPhoneLayout();
        assertTouchTargets();
        assertReadablePalette(true);
        save("batch-dark");
        tag("batch-toggle:1").performClick();
        layout();
        assertEquals(View.GONE, tag("batch-candidates:1").getVisibility());
        scrollTo((View) tag("batch-selected:1").getParent());
        assertFullyVisible(tag("batch-selected:1"));
        assertFullyVisible(tag("batch-toggle:1"));
        save("batch-dark-collapsed");
    }

    @Test
    public void doubleFontScaleWrapsMetadataWithoutLosingTargets() throws Exception {
        show("LIGHT", 2f);
        assertEquals(2f, activity.getResources().getConfiguration().fontScale, 0f);
        openSecondTrack();
        assertPhoneLayout();
        assertTouchTargets();
        assertReadablePalette(false);
        List<TextView> texts = new ArrayList<>();
        gather(tag("batch-candidate:" + BatchDownloadPageTest.key("a", 1)), texts);
        assertTrue(texts.size() >= 3);
        for (TextView text : texts) {
            // Text grows and wraps: no fixed height, no ellipsis, nothing cut off.
            android.text.Layout layout = text.getLayout();
            assertNotNull(text.getText().toString(), layout);
            assertEquals(
                    text.getText().toString(),
                    0,
                    layout.getEllipsisCount(layout.getLineCount() - 1));
            assertTrue(text.getText().toString(), text.getHeight() >= layout.getHeight());
            assertTrue(text.getRight() <= ((View) text.getParent()).getWidth());
        }
        String spoken =
                tag("batch-choose:1:" + BatchDownloadPageTest.key("a", 1))
                        .getContentDescription()
                        .toString();
        assertTrue(spoken, spoken.contains("Bitrate: Not reported"));
        assertTrue(spoken, spoken.contains("File size: Not reported"));
        assertEquals(48, tag("batch-unselect:1").getHeight());
        save("batch-font2");
    }
}
