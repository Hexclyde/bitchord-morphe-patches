package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class AudioTweaksStateTest {
    Context context;
    SharedPreferences saved;

    @Before
    public void reset() throws Exception {
        context = RuntimeEnvironment.getApplication();
        saved = context.getSharedPreferences(SoundExtras.PREFERENCES, Context.MODE_PRIVATE);
        saved.edit().clear().commit();
        forgetLoadedState();
    }

    private void forgetLoadedState() throws Exception {
        Field field = SoundExtras.class.getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void freshInstallRequiresAnExplicitEnable() {
        SoundExtras.init(context);
        assertFalse(SoundExtras.singEnabled);
        assertFalse(SoundExtras.pitchEnabled);
        assertFalse(SoundExtras.loudnessEnabled);
        assertFalse(SoundExtras.sleepFade);
        assertFalse(SoundExtras.batterySaver);
        assertFalse(SoundExtras.losslessOverBluetooth);
        assertEquals(50, SoundExtras.singLevel);
    }

    @Test
    public void olderCustomValuesMigrateAndExplicitOffWinsAfterRestart() throws Exception {
        saved.edit().putInt("singLevel", 80).putInt("pitch", 3).putInt("targetLufs", -10).commit();
        SoundExtras.init(context);
        assertTrue(SoundExtras.singEnabled);
        assertTrue(SoundExtras.pitchEnabled);
        assertTrue(SoundExtras.loudnessEnabled);
        SoundExtras.setSingEnabled(false);
        SoundExtras.setPitchEnabled(false);
        SoundExtras.setLoudnessEnabled(false);
        forgetLoadedState();
        SoundExtras.init(context);
        assertFalse(SoundExtras.singEnabled);
        assertFalse(SoundExtras.pitchEnabled);
        assertFalse(SoundExtras.loudnessEnabled);
        assertEquals(80, SoundExtras.singLevel);
        assertEquals(3, SoundExtras.pitchSemitones);
        assertEquals(-10, SoundExtras.targetLufs);
    }

    @Test
    public void loudnessGateNeutralisesPlaybackWithoutLosingSavedLevel() {
        SoundExtras.init(context);
        SoundExtras.setTargetLufs(-10);
        assertEquals(Integer.valueOf(-620), SoundExtras.loudness(-620));
        SoundExtras.setLoudnessEnabled(true);
        assertEquals(Integer.valueOf(-220), SoundExtras.loudness(-620));
        SoundExtras.setLoudnessEnabled(false);
        assertEquals(Integer.valueOf(-620), SoundExtras.loudness(-620));
        assertEquals(-10, SoundExtras.targetLufs);
    }

    @Test
    public void pitchGateRestoresNeutralPitchAndPreservesSpeedAndValue() throws Exception {
        SoundExtras.init(context);
        SoundExtras.setPitch(7);
        final PlaybackParameters[] state = {new PlaybackParameters(1.25f, 1f)};
        Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getPlaybackParameters"))
                                        return state[0];
                                    if (method.getName().equals("setPlaybackParameters")) {
                                        state[0] = (PlaybackParameters) args[0];
                                        return null;
                                    }
                                    return null;
                                });
        Method apply = SoundExtras.class.getDeclaredMethod("applyPitch", Player.class);
        apply.setAccessible(true);
        apply.invoke(null, player);
        assertEquals(1f, state[0].pitch, 0.0001f);
        SoundExtras.setPitchEnabled(true);
        apply.invoke(null, player);
        assertEquals(SoundExtras.pitchFactor(7), state[0].pitch, 0.0001f);
        SoundExtras.setPitchEnabled(false);
        apply.invoke(null, player);
        assertEquals(1f, state[0].pitch, 0.0001f);
        assertEquals(1.25f, state[0].speed, 0.0001f);
        assertEquals(7, SoundExtras.pitchSemitones);
    }
}
