package app.bitchord.telegram;

import com.music.bitchord.data.settings.AppSettings;

/** Battery saver holds back for BitChord's own Automix, with or without the patch's marker. */
public final class OffloadTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(!SourceAutomix.applied(), "the Source-independent AutoMix marker is off");
        check(!SourceAutomix.activeMix(), "no patch means no patched mix");
        SoundExtras.batterySaver = true;

        AppSettings.setSmartFadeEnabled(false);
        check(!SoundExtras.nativeSmartFade(), "the switch reads off");
        check(SoundExtras.offloadWanted(), "offload is wanted with Automix off");
        check(SoundExtras.offloadHeldBack == null, "nothing holds it back");

        AppSettings.setSmartFadeEnabled(true);
        check(SoundExtras.nativeSmartFade(), "the switch reads on");
        check(!SoundExtras.offloadWanted(), "BitChord's own Automix holds offload back");
        check("AutoMix".equals(SoundExtras.offloadHeldBack), "the reason is Automix");

        SoundExtras.batterySaver = false;
        check(!SoundExtras.offloadWanted(), "Battery saver off never asks for offload");
        check(SoundExtras.offloadHeldBack == null, "no reason without Battery saver");

        SoundExtras.batterySaver = true;
        AppSettings.setSmartFadeEnabled(false);
        check(SoundExtras.offloadWanted(), "turning Automix off lets offload back on");
        SoundExtras.smartFadeChanged();
        System.out.println("OffloadTest PASS " + checks + " checks");
    }
}
