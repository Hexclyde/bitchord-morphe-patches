package app.bitchord.telegram;

import static org.junit.Assert.*;

import com.music.bitchord.data.sources.SourceConfig;
import com.music.bitchord.data.sources.SourceKind;
import com.music.bitchord.data.sources.SourceRegistry;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class FlacRetirementTest {
    @After
    public void reset() {
        SourceRegistry.configs.setValue(new ArrayList<>());
        FlacAddon.init();
        FlacAddon.transport = FlacAddon::http;
    }

    SourceConfig source(String id, String url) {
        return new SourceConfig(id, SourceKind.ADDON, id, url, true, true, true);
    }

    @SuppressWarnings("unchecked")
    ArrayList<Object> filter(ArrayList<?> rows) throws Exception {
        return (ArrayList<Object>)
                FlacAddon.class.getMethod("availableConfigs", ArrayList.class).invoke(null, rows);
    }

    @Test
    public void startupRemovesOnlyTheOwnedSourceWithoutTouchingSavedAudio() throws Exception {
        SourceConfig first = source("community", "https://music.example");
        SourceConfig retired = source(FlacAddon.ID, FlacAddon.ORIGIN);
        SourceConfig otherAddress = source(FlacAddon.ID, "https://other.example");
        SourceConfig otherIdentity = source("another-addon", FlacAddon.ORIGIN);
        SourceConfig last = source("telegram-native", "https://telegram.bitchord.invalid");
        SourceRegistry.configs.setValue(
                new ArrayList<>(Arrays.asList(first, retired, otherAddress, otherIdentity, last)));
        File saved =
                new File(RuntimeEnvironment.getApplication().getFilesDir(), "saved-track.flac");
        byte[] bytes = {102, 76, 97, 67, 1, 2, 3};
        Files.write(saved.toPath(), bytes);
        FlacAddon.providerSession = "existing-session";
        FlacAddon.transport =
                (path, body, token, deadline) -> {
                    throw new AssertionError("Network request");
                };
        FlacAddon.init();
        List<SourceConfig> remaining = SourceRegistry.configs.getValue();
        assertEquals(Arrays.asList(first, otherAddress, otherIdentity, last), remaining);
        for (int i = 0; i < remaining.size(); i++)
            assertSame(
                    Arrays.asList(first, otherAddress, otherIdentity, last).get(i),
                    remaining.get(i));
        assertArrayEquals(bytes, Files.readAllBytes(saved.toPath()));
        assertEquals("existing-session", FlacAddon.providerSession);
        assertFalse(FlacAddon.enabled());
    }

    @Test
    public void laterSourcePublicationRemovesOwnedDuplicatesAndPreservesTheInput()
            throws Exception {
        SourceConfig other = source("community", "https://music.example");
        SourceConfig owned = source(FlacAddon.ID, FlacAddon.ORIGIN);
        ArrayList<Object> input = new ArrayList<>(Arrays.asList(owned, other, owned));
        ArrayList<Object> filtered = filter(input);
        assertEquals(Collections.singletonList(other), filtered);
        assertEquals(3, input.size());
        SourceRegistry.publish(
                new ArrayList<>(Collections.singletonList((SourceConfig) filtered.get(0))), true);
        assertNull(SourceRegistry.config(FlacAddon.ID));
        assertSame(other, SourceRegistry.config("community"));
    }

    @Test
    public void unrelatedPublicationAndStartupKeepTheirExistingList() throws Exception {
        SourceConfig other = source("community", "https://music.example");
        ArrayList<SourceConfig> existing = new ArrayList<>(Collections.singletonList(other));
        SourceRegistry.configs.setValue(existing);
        assertSame(existing, filter(existing));
        FlacAddon.init();
        assertSame(existing, SourceRegistry.configs.getValue());
    }

    @Test
    public void anOldEnabledPreferenceCannotStartAProviderRequest() throws Exception {
        SourceRegistry.configs.setValue(
                new ArrayList<>(Collections.singletonList(source(FlacAddon.ID, FlacAddon.ORIGIN))));
        assertFalse(SoundExtras.flacAddonApplied());
        assertFalse(FlacAddon.enabled());
        FlacAddon.transport =
                (path, body, token, deadline) -> {
                    throw new AssertionError("Network request");
                };
        try {
            FlacAddon.bodyOrNull(FlacAddon.ORIGIN + "/search?q=track");
            fail("Unavailable provider was searched");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("disabled"));
        }
    }

    @Test
    public void startupFlushesAnOwnedRowAfterNativeLoadPersistsItsOriginalList() throws Exception {
        SourceConfig other = source("community", "https://music.example");
        ArrayList<SourceConfig> loaded =
                new ArrayList<>(Arrays.asList(source(FlacAddon.ID, FlacAddon.ORIGIN), other));
        ArrayList<Object> filtered = filter(loaded);
        SourceRegistry.publish(
                new ArrayList<>(Collections.singletonList((SourceConfig) filtered.get(0))), false);
        SourceRegistry.persistedConfigs = new ArrayList<>(loaded);
        FlacAddon.init();
        assertEquals(Collections.singletonList(other), SourceRegistry.persistedConfigs);
        assertSame(other, SourceRegistry.persistedConfigs.get(0));
        assertEquals(2, loaded.size());
        List<SourceConfig> saved = SourceRegistry.persistedConfigs;
        FlacAddon.init();
        assertSame(saved, SourceRegistry.persistedConfigs);
    }

    @Test
    public void oldDownloadActionsCannotOpenProviderSetup() {
        android.app.Activity activity =
                Robolectric.buildActivity(android.app.Activity.class).setup().get();
        FlacAddon.providerSession = null;
        assertFalse(FlacAddon.needsVerification());
        FlacAddon.verify(activity);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        activity.finish();
    }

    @Test
    public void restoredProviderActivityFinishesWithoutCreatingABrowser() throws Exception {
        org.robolectric.android.controller.ActivityController<FlacVerificationActivity> controller =
                Robolectric.buildActivity(FlacVerificationActivity.class).create();
        FlacVerificationActivity activity = controller.get();
        try {
            assertTrue(activity.isFinishing());
            java.lang.reflect.Field web = FlacVerificationActivity.class.getDeclaredField("web");
            web.setAccessible(true);
            assertNull(web.get(activity));
        } finally {
            controller.destroy();
        }
    }
}
