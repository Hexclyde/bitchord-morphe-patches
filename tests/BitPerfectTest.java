package app.bitchord.telegram;

import androidx.media3.common.Player;

import com.music.bitchord.data.settings.AppSettings;
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput;

import java.lang.reflect.Proxy;

/** Bit-perfect selection and active playback must gate offload and unsupported sleep fades. */
public final class BitPerfectTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(!BitPerfect.wanted(), "OFF is not a requested direct output");
        check(!BitPerfect.playing(), "An inactive status is not direct playback");
        SoundExtras.batterySaver = true;
        check(SoundExtras.offloadWanted(), "Offload is allowed with bit-perfect disabled");
        for (AppSettings.Mode mode : AppSettings.Mode.values()) {
            AppSettings.bitPerfectMode.value = mode;
            check(
                    BitPerfect.wanted() == (mode != AppSettings.Mode.OFF),
                    "Selection follows " + mode);
            if (mode != AppSettings.Mode.OFF) {
                check(!SoundExtras.offloadWanted(), "Offload must not defeat " + mode);
                check(
                        "Bit-perfect output is on".equals(SoundExtras.offloadHeldBack),
                        "The held-back reason describes the selection");
            }
        }
        AppSettings.bitPerfectMode.value = AppSettings.Mode.OFF;
        BitPerfectOutput._status.value = new BitPerfectOutput.Status(true);
        check(BitPerfect.playing(), "Active output is read from the current state value");
        int[] paused = {0};
        Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, values) -> {
                                    if (method.getName().equals("isPlaying")) return true;
                                    if (method.getName().equals("pause")) {
                                        paused[0]++;
                                        return null;
                                    }
                                    throw new AssertionError(
                                            "A direct-output timer must not alter player volume: "
                                                    + method.getName());
                                });
        SleepFade.pauseSoon(player, 10);
        check(paused[0] == 1, "The expired timer pauses immediately on direct output");
        check(!SleepFade.active(), "It leaves no unsupported fade pending");
        SleepFade.fadeEnding(player, 10);
        check(
                !SleepFade.active() && paused[0] == 1,
                "After-track leaves the host's end pause in charge");
        BitPerfectOutput._status.value = new BitPerfectOutput.Status(false);
        check(
                !BitPerfect.playing(),
                "A later inactive state is observed through the cached accessor");
        AppSettings.bitPerfectMode.value = null;
        check(!BitPerfect.wanted(), "Unavailable mode does not become a phantom selection");
        AppSettings.bitPerfectMode.value = AppSettings.Mode.OFF;
        SoundExtras.batterySaver = false;
        System.out.println("PASS: " + checks + " bit-perfect runtime checks.");
    }
}
