package lv.latviantube.data.playback;

import org.junit.Assume;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.Collections;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static org.junit.Assert.*;

/** Opt-in live extraction AND media-byte check: LATVIANTUBE_LIVE_TESTS=1 ./gradlew testDebugUnitTest. */
public class StreamResolverLiveTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Test public void resolvesPublicVideoAndFetchesVideoAndAudioBytes() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("latviantube.live"));
        PlaybackSource source = new StreamResolver().resolve(System.getProperty("latviantube.liveVideoId", "jNQXAC9IVRw"));
        assertFalse(source.variants.isEmpty());
        PlaybackSource.Variant variant = source.variants.stream()
                .filter(v -> v.codec != null && v.codec.startsWith("avc")).findFirst().orElse(source.variants.get(0));
        assertMediaBytes(variant.videoUrl);
        if (variant.audioUrl != null) assertMediaBytes(variant.audioUrl);
        OfflineStore offline = new OfflineStore(files.getRoot(), new OkHttpClient());
        offline.save(source, variant, Collections.emptyList());
        assertTrue(offline.load(source.videoId).offline);
    }

    private static void assertMediaBytes(String url) throws Exception {
        try (Response response = new OkHttpClient().newCall(new Request.Builder().url(url)
                .header("User-Agent", ExtractorDownloader.USER_AGENT).header("Range", "bytes=0-1023").build()).execute()) {
            assertTrue("Media HTTP " + response.code(), response.isSuccessful());
            assertNotNull(response.body());
            assertTrue(response.body().byteStream().read() >= 0);
        }
    }
}
