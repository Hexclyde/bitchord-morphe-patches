package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class VerificationMemoryTest {
    @Test
    public void verificationAllowsBothMonoCopiesAndBoundedReadWork() {
        for (int rate : new int[] {44100, 48000, 96000, 192000, 384000})
            assertEquals(
                    (16L * rate + 65536) * 8 + (12L << 20),
                    SourcePreference.verificationBytes(rate, 16));
    }

    @Test
    public void invalidWindowsDoNotRequestMemory() {
        for (double seconds : new double[] {-1, 0, 25, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(-1, SourcePreference.verificationBytes(48000, seconds));
        assertEquals(-1, SourcePreference.verificationBytes(0, 16));
    }

    @Test
    public void modelGrowthAndVerificationCannotSpendTheSameHeadroom() {
        long maximum = 192L << 20, used = 100L << 20;
        long bytes = SourcePreference.verificationBytes(48000, 16);
        try (SourceAudio.JavaMemory model = SourceAudio.reserveJava(maximum, used, 64L << 20)) {
            assertNotNull(model);
            assertNull(SourceAudio.reserveJava(maximum, used, bytes));
        }
        try (SourceAudio.JavaMemory verification = SourceAudio.reserveJava(maximum, used, bytes)) {
            assertNotNull(verification);
        }
    }
}
