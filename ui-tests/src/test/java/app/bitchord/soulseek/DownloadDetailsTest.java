package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.*;
import android.view.*;
import android.widget.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DownloadDetailsTest {
    String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }

    @Test
    public void peerDetailsShowOnlyAdvertisedFactsAndStayReadOnly() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        JSONObject row =
                new JSONObject(
                        "{peer:'listener',path:'Music/Artist -"
                            + " Track.flac',size:123456,bitrate:1200,duration:60,sampleRate:96000,bitDepth:24,variableBitrate:0,queueLength:7,attributes:{'99':12}}");
        SoulseekTrack track = new SoulseekTrack(row);
        int before = SoulseekSelectedDownloads.selectedReferences.size();
        Dialog dialog = DownloadDetails.show(activity, track);
        String content = text(dialog.getWindow().getDecorView());
        assertTrue(content, content.contains("96 kHz"));
        assertTrue(content, content.contains("24 bits"));
        assertTrue(content, content.contains("listener"));
        assertTrue(content, content.contains("Attribute 99"));
        assertTrue(content, content.contains("unverified"));
        assertEquals(before, SoulseekSelectedDownloads.selectedReferences.size());
        dialog.dismiss();
        activity.finish();
    }
}
