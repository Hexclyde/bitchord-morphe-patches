package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;

import com.music.bitchord.data.settings.AppSettings;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The equaliser saved for an output stays off while JamesDSP has the effects, and comes back. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class OutputEqJamesDspTest {
    private Context app;

    private static void set(String name, Object value) throws Exception {
        Field field = OutputEq.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static OutputEq.Snapshot tone(boolean on, int x) {
        OutputEq.Snapshot snapshot = new OutputEq.Snapshot();
        snapshot.enabled = on;
        snapshot.toneX = x;
        return snapshot;
    }

    private void save(String output, OutputEq.Snapshot snapshot) {
        app.getSharedPreferences(OutputEq.PREFERENCES, 0)
                .edit()
                .putString("eq." + output, snapshot.toJson())
                .commit();
    }

    private boolean hostOn() {
        return app.getSharedPreferences(OutputEq.HOST_PREFERENCES, 0)
                .getBoolean("equalizer_enabled", false);
    }

    private void switchTo() throws Exception {
        save(Outputs.current().key, saved);
        set("currentOutput", "before");
        Method changed = OutputEq.class.getDeclaredMethod("outputChanged");
        changed.setAccessible(true);
        changed.invoke(null);
    }

    private OutputEq.Snapshot saved;

    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(OutputEq.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences(OutputEq.HOST_PREFERENCES, 0).edit().clear().commit();
        set("mine", app.getSharedPreferences(OutputEq.PREFERENCES, 0));
        set("host", app.getSharedPreferences(OutputEq.HOST_PREFERENCES, 0));
        set("enabled", true);
        set("applying", false);
        AppSettings.equalizerCalls.clear();
        EffectsApps.useJamesDsp = false;
        saved = tone(true, 4);
    }

    @After
    public void tearDown() {
        EffectsApps.useJamesDsp = false;
    }

    @Test
    public void anOutputSwitchTurnsTheEqualiserOnWithoutJamesDsp() throws Exception {
        switchTo();
        assertTrue(hostOn());
        assertTrue(AppSettings.equalizerCalls.contains("enabled=true"));
    }

    @Test
    public void anOutputSwitchLeavesTheEqualiserOffWhileJamesDspHasTheEffects() throws Exception {
        EffectsApps.useJamesDsp = true;
        switchTo();
        assertFalse(hostOn());
        assertFalse(AppSettings.equalizerCalls.contains("enabled=true"));
        assertTrue(AppSettings.equalizerCalls.contains("enabled=false"));
        // The output still has its own setting, tone pad and all.
        OutputEq.Snapshot kept = OutputEq.saved(Outputs.current().key);
        assertTrue(kept.enabled);
        assertEquals(4, kept.toneX);
        assertEquals(4, (int) AppSettings.equalizerToneX.getValue());
    }

    @Test
    public void turningJamesDspOffPutsTheSavedEqualiserBack() throws Exception {
        save(Outputs.current().key, saved);
        EffectsApps.useJamesDsp = true;
        OutputEq.apply(saved);
        assertFalse(hostOn());
        // BitChord's equaliser reading off must not overwrite what the output saved.
        OutputEq.remember();
        assertTrue(OutputEq.saved(Outputs.current().key).enabled);

        EffectsApps.setUseJamesDsp(false);
        assertTrue(hostOn());
        assertTrue(AppSettings.equalizerCalls.contains("enabled=true"));
    }

    @Test
    public void everyFlowTakesTheSnapshotThroughItsOwnSetter() throws Exception {
        saved = tone(true, 3);
        saved.toneY = -2;
        saved.balance = 0.5f;
        saved.mode = "MANUAL";
        switchTo();
        assertEquals(3, (int) AppSettings.equalizerToneX.getValue());
        assertEquals(-2, (int) AppSettings.equalizerToneY.getValue());
        assertEquals(0.5f, AppSettings.equalizerBalance.getValue(), 0f);
        assertEquals(
                com.music.bitchord.data.settings.EqualizerMode.MANUAL,
                AppSettings.equalizerMode.getValue());
        assertTrue(hostOn());
    }

    @Test
    public void anOutputSavedWithTheEqualiserOffStaysOff() throws Exception {
        saved = tone(false, 1);
        switchTo();
        assertFalse(hostOn());
        EffectsApps.setUseJamesDsp(false);
        assertFalse(hostOn());
    }
}
