package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;

import androidx.media3.datasource.DataSpec;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        shadows = StreamseekPreviewTest.Installed.class,
        instrumentedPackages = "app.bitchord.soulseek.SoulseekPreview")
@LooperMode(LooperMode.Mode.PAUSED)
public class StreamseekPreviewTest {
    @Implements(value = SoulseekPreview.class, isInAndroidSdk = false)
    public static class Installed {
        @Implementation
        protected static boolean installed() {
            return true;
        }
    }

    Context context;
    SoulseekBridge bridge;
    SoulseekTrack track;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        SoulseekPreview.stopAll();
        context.getSharedPreferences("bitchord_streamseek_preview", 0).edit().clear().commit();
        bridge = SoulseekBridge.reset(context);
        DownloadSettings.reset();
        track = track("Track.mp3", 4);
        bridge.searchRows.add(track);
        bridge.previewFile = new File(context.getCacheDir(), "audio.part");
        Files.write(bridge.previewFile.toPath(), new byte[] {1, 2, 3, 4});
        bridge.previewDownloaded = 4;
        bridge.previewComplete = true;
    }

    @After
    public void cleanup() {
        SoulseekPreview.stopAll();
    }

    SoulseekTrack track(String path, int size) throws Exception {
        return new SoulseekTrack(
                new JSONObject().put("peer", "fixture-peer").put("path", path).put("size", size));
    }

    SoulseekPreview.Session session() throws Exception {
        SoulseekPreview.setEnabled(context, true);
        return new SoulseekPreview.Session(
                context,
                new SoulseekPreview.Offer(track, bridge.sessionTag, Long.MAX_VALUE),
                bridge);
    }

    @Test
    public void defaultOffAndSourceGatePreventPreviewTransfers() throws Exception {
        assertFalse(SoulseekPreview.available(context));
        try {
            SoulseekPreview.open(
                    Robolectric.buildActivity(android.app.Activity.class).setup().get(),
                    track,
                    bridge.sessionTag);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(0, bridge.previewStarts);
        SoulseekPreview.setEnabled(context, true);
        assertTrue(SoulseekPreview.available(context));
        DownloadSettings.soulseek = false;
        assertFalse(SoulseekPreview.available(context));
    }

    @Test
    public void offersBindExactPeerFileSessionAndRejectUnsupportedFormats() throws Exception {
        SoulseekPreview.setEnabled(context, true);
        android.app.Activity activity =
                Robolectric.buildActivity(android.app.Activity.class).setup().get();
        SoulseekPreview.open(activity, track, bridge.sessionTag);
        Intent intent = org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
        String token = intent.getStringExtra(SoulseekPreview.EXTRA);
        assertSame(track, SoulseekPreview.offer(token, context).track);
        bridge.sessionTag = "changed";
        try {
            SoulseekPreview.offer(token, context);
            fail();
        } catch (IOException expected) {
        }
        assertFalse(SoulseekPreview.supported(track("Track.m4a", 4)));
        assertTrue(SoulseekPreview.supported(track("Track.flac", 4)));
    }

    @Test
    public void privateDataSourceRejectsOtherUrisAndReusesOneTransferAcrossReopen()
            throws Exception {
        try (SoulseekPreview.Session session = session()) {
            PreviewDataSource source = new PreviewDataSource(session);
            try {
                source.open(new DataSpec.Builder().setUri("https://example.com/file.mp3").build());
                fail();
            } catch (IOException expected) {
            }
            assertEquals(4, source.open(new DataSpec.Builder().setUri(session.uri).build()));
            byte[] bytes = new byte[4];
            assertEquals(4, source.read(bytes, 0, 4));
            assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes);
            assertEquals(-1, source.read(bytes, 0, 4));
            source.close();
            source.open(new DataSpec.Builder().setUri(session.uri).build());
            source.close();
            assertEquals(1, bridge.previewStarts);
            assertEquals(1, bridge.previewReaders);
        }
        assertEquals(0, bridge.previewReaders);
    }

    @Test
    public void growingPrefixWaitsInsteadOfReportingEndOfFile() throws Exception {
        bridge.previewDownloaded = 2;
        bridge.previewComplete = false;
        try (SoulseekPreview.Session session = session()) {
            PreviewDataSource source = new PreviewDataSource(session);
            source.open(new DataSpec.Builder().setUri(session.uri).build());
            byte[] bytes = new byte[4];
            assertEquals(2, source.read(bytes, 0, 4));
            ExecutorService work = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> next = work.submit(() -> source.read(bytes, 2, 2));
                try {
                    next.get(30, TimeUnit.MILLISECONDS);
                    fail("Temporary EOF must wait");
                } catch (TimeoutException expected) {
                }
                bridge.previewDownloaded = 4;
                bridge.previewComplete = true;
                assertEquals(2, (int) next.get(1, TimeUnit.SECONDS));
                assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes);
            } finally {
                work.shutdownNow();
                source.close();
            }
        }
    }

    @Test
    public void unbufferedSeeksAndDisabledSessionsFailClosed() throws Exception {
        bridge.previewDownloaded = 1;
        bridge.previewComplete = false;
        try (SoulseekPreview.Session session = session()) {
            PreviewDataSource source = new PreviewDataSource(session);
            try {
                source.open(new DataSpec.Builder().setUri(session.uri).setPosition(3).build());
                fail();
            } catch (IOException expected) {
            }
            SoulseekPreview.setEnabled(context, false);
            try {
                session.check();
                fail();
            } catch (IOException expected) {
            }
            source.close();
        }
        assertEquals(0, bridge.previewReaders);
    }

    @Test
    public void flacHeaderReportsStructureWithoutClaimingLosslessOrigin() throws Exception {
        byte[] bytes = new byte[38];
        bytes[0] = 'f';
        bytes[1] = 'L';
        bytes[2] = 'a';
        bytes[3] = 'C';
        bytes[7] = 34;
        long packed = ((long) 96000 << 44) | ((long) 1 << 41) | ((long) 23 << 36) | 2880000L;
        for (int i = 0; i < 8; i++) bytes[18 + i] = (byte) (packed >>> (56 - i * 8));
        JSONObject header = PreviewEvidence.flacHeader(bytes);
        assertEquals(24, header.getInt("bit_depth"));
        assertEquals(96000, header.getInt("sample_rate_hz"));
        assertEquals(2, header.getInt("channels"));
        assertEquals(30, header.getDouble("header_duration_seconds"), 0.01);
        assertFalse(header.getBoolean("lossless_origin_verified"));
        bytes[7] = 0;
        assertNull(PreviewEvidence.flacHeader(bytes));
    }

    @Test
    public void reportsBindUserConfirmationToCompleteHashAndKeepClaimsSeparate() throws Exception {
        Files.write(bridge.previewFile.toPath(), "hello".getBytes("UTF-8"));
        String hash = PreviewEvidence.sha256(bridge.previewFile, 5, () -> {});
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
        JSONObject report =
                PreviewEvidence.report(
                        track("Track.mp3", 5),
                        5,
                        null,
                        new JSONObject().put("verdict", "excerpt_match"),
                        hash,
                        true);
        assertFalse(report.getBoolean("perfect_match_verified"));
        assertFalse(report.getBoolean("trusted_reference_checksum_compared"));
        assertTrue(
                report.getJSONObject("user_attestation").getBoolean("confirmed_correct_recording"));
        assertEquals(hash, report.getString("complete_file_sha256"));
        assertFalse(report.toString().contains(bridge.previewFile.getAbsolutePath()));
        try {
            PreviewEvidence.report(track, 2, null, null, null, true);
            fail();
        } catch (IOException expected) {
        }
        try {
            PreviewEvidence.sha256(bridge.previewFile, 4, () -> {});
            fail();
        } catch (IOException expected) {
        }
    }

    @Test
    public void pcmDecodingHandlesFloatAndIntegerEncodings() throws Exception {
        java.nio.ByteBuffer b =
                java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putFloat(Float.NaN).flip();
        assertEquals(0, PreviewDecode.sample(b, 4), 0);
        b.clear();
        b.putShort((short) -32768).flip();
        assertEquals(-1, PreviewDecode.sample(b, 2), 0);
        b.clear();
        b.put(new byte[] {0, 0, (byte) 128}).flip();
        assertEquals(-1, PreviewDecode.sample(b, 0x20000000), 0);
        assertEquals(0, PreviewDecode.bytesPerSample(999));
    }
}
