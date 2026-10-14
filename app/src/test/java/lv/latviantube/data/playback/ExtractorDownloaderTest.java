package lv.latviantube.data.playback;

import org.junit.Rule;
import org.junit.Test;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import static org.junit.Assert.*;

public class ExtractorDownloaderTest {
    @Rule public MockWebServer server = new MockWebServer();
    private final ExtractorDownloader downloader = new ExtractorDownloader(new OkHttpClient());

    @Test public void preservesClientOverridesAndPayload() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        Response response = downloader.execute(Request.newBuilder().url(server.url("/player").toString())
                .httpMethod("POST").headers(Collections.singletonMap("User-Agent", Collections.singletonList("client-ua")))
                .dataToSend("body".getBytes(StandardCharsets.UTF_8)).build());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("client-ua", request.getHeader("User-Agent"));
        assertEquals("lv-LV,lv;q=0.9", request.getHeader("Accept-Language"));
        assertEquals("body", request.getBody().readUtf8());
        assertEquals(200, response.responseCode());
        assertEquals("{\"ok\":true}", response.responseBody());
    }

    @Test public void reportsFinalRedirectAndKeepsHttpFailuresVisible() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/blocked")));
        server.enqueue(new MockResponse().setResponseCode(403).setBody("blocked"));
        Response response = downloader.execute(Request.newBuilder().url(server.url("/first").toString())
                .httpMethod("GET").build());
        assertEquals(403, response.responseCode());
        assertEquals("blocked", response.responseBody());
        assertEquals(server.url("/blocked").toString(), response.latestUrl());
    }

    @Test(expected = ReCaptchaException.class) public void rateLimitIsNotAnEmptySuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        downloader.execute(Request.newBuilder().url(server.url("/limited").toString()).httpMethod("GET").build());
    }
}
