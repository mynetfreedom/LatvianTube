package lv.latviantube.data.playback;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import static org.junit.Assert.*;

public class OfflineStoreTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public MockWebServer server = new MockWebServer();

    private PlaybackSource source(boolean withAudio) {
        PlaybackSource.Variant variant = new PlaybackSource.Variant("v", "720p", server.url("/video").toString(),
                withAudio ? server.url("/audio").toString() : null, "video/mp4", "audio/mp4", "avc1", 720,
                PlaybackSource.Kind.PROGRESSIVE);
        return new PlaybackSource("abcdefghijk", Collections.singletonList(variant), Collections.emptyList(), false, false);
    }

    @Test public void publishesBothTracksAtomicallyAndLoadsWithoutNetwork() throws Exception {
        OfflineStore store = new OfflineStore(files.getRoot(), new OkHttpClient());
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-10/11").setBody("video bytes"));
        server.enqueue(new MockResponse().setBody("audio bytes"));
        PlaybackSource original = source(true);
        store.save(original, original.variants.get(0), Collections.singletonList(new SponsorBlockClient.Segment(1000, 2000)));
        server.shutdown();
        PlaybackSource saved = store.load("abcdefghijk");
        assertTrue(saved.offline);
        assertTrue(saved.variants.get(0).videoUrl.startsWith("file:"));
        assertTrue(saved.variants.get(0).audioUrl.startsWith("file:"));
        assertEquals(2000, store.loadSegments("abcdefghijk").get(0).endMs);
        String metadata = new String(Files.readAllBytes(new File(files.getRoot(), "offline/abcdefghijk/metadata.json").toPath()));
        assertFalse(metadata.contains("http://"));
        store.remove("abcdefghijk");
        assertNull(store.load("abcdefghijk"));
    }

    @Test public void failedAudioNeverExposesPartialVideo() throws Exception {
        OfflineStore store = new OfflineStore(files.getRoot(), new OkHttpClient());
        server.enqueue(new MockResponse().setBody("video bytes"));
        server.enqueue(new MockResponse().setResponseCode(403));
        PlaybackSource original = source(true);
        try {
            store.save(original, original.variants.get(0), Collections.emptyList());
            fail("Expected failure");
        } catch (java.io.IOException expected) { }
        assertNull(store.load("abcdefghijk"));
        assertEquals(0, new File(files.getRoot(), "offline").list().length);
    }

    @Test public void corruptedOfflineMediaIsDetected() throws Exception {
        OfflineStore store = new OfflineStore(files.getRoot(), new OkHttpClient());
        server.enqueue(new MockResponse().setBody("video bytes"));
        PlaybackSource original = source(false);
        store.save(original, original.variants.get(0), Collections.emptyList());
        Files.write(new File(files.getRoot(), "offline/abcdefghijk/video").toPath(), new byte[0]);
        try { store.load("abcdefghijk"); fail("Expected corrupt file failure"); }
        catch (java.io.IOException expected) { }
    }

    @Test(expected = java.io.IOException.class) public void rejectsPathTraversal() throws Exception {
        new OfflineStore(files.getRoot(), new OkHttpClient()).load("../outside");
    }

    @Test public void cappedRangeResponseIsNotPublishedAsACompleteVideo() throws Exception {
        OfflineStore store = new OfflineStore(files.getRoot(), new OkHttpClient());
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/20").setBody("part"));
        PlaybackSource original = source(false);
        try { store.save(original, original.variants.get(0), Collections.emptyList()); fail("Expected partial-range failure"); }
        catch (java.io.IOException expected) { }
        assertNull(store.load("abcdefghijk"));
        assertEquals(0, new File(files.getRoot(), "offline").list().length);
    }
}
