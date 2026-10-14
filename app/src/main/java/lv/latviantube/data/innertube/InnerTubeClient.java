package lv.latviantube.data.innertube;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The InnerTube HTTP client — LatvianTube's only source of YouTube data.
 *
 * <h2>Blocking by design</h2>
 * Every method here is synchronous and must never be called from the main
 * thread. That is a deliberate change from the first version, which delivered
 * results via a callback posted to the main thread with
 * {@code Handler.post()}. That looked harmless but put all of the expensive work
 * on the UI thread, because callers did their parsing inside the callback:
 *
 * <pre>
 *   Handler.post(&#8594;) onSuccess(json)
 *                        ├─ CacheStore.put()            // file write
 *                        ├─ InnerTubeParser.extractVideos()  // recursive walk of ~600 KB JSON
 *                        └─ InnerTubeParser.extractVideos()  // again, for the count
 * </pre>
 *
 * Measured on a Grundig Android TV (Android 11, 192 MB heap) that produced
 * <b>65.98% janky frames</b>, 90th-percentile frame time 38 ms. Parsing a
 * channel response is tens of milliseconds of pure main-thread work, and the
 * home screen does it once per curated channel — 17 times.
 *
 * Keeping the client blocking makes the threading boundary explicit and puts it
 * in one place: {@link lv.latviantube.data.ContentRepository} owns the executor
 * and is the only thing that touches the main thread. See {@code ANALYSIS.md}
 * §E11.
 *
 * <h2>Endpoints, and which of them are actually usable</h2>
 * Each of these was probed against live YouTube before being relied on:
 *
 * <table>
 *   <tr><th>Endpoint</th><th>Result</th></tr>
 *   <tr><td>{@code search}</td><td>200; with {@code hl=lv} returns Latvian results</td></tr>
 *   <tr><td>{@code browse} channelId + videos params</td><td>200; 30 uploads</td></tr>
 *   <tr><td>{@code player}</td><td>200, but omits {@code defaultAudioLanguage}</td></tr>
 *   <tr><td>{@code browse} FEtrending</td><td><b>400</b> — unusable</td></tr>
 * </table>
 *
 * <h2>Language pinning</h2>
 * {@code hl=lv} and {@code gl=LV} are set on every request and are not
 * configurable at runtime. Everything the app can show is filtered through
 * {@link lv.latviantube.data.filter.LatvianLanguageFilter} afterwards anyway, but
 * pinning the locale upstream is what makes YouTube <em>rank</em> Latvian
 * results first, which is what gives the filter Latvian material to accept.
 */
public final class InnerTubeClient {

    private static final String TAG = "InnerTubeClient";

    /**
     * YouTube's public web-client key. This is not a secret and not an API
     * credential: it is the constant embedded in the youtube.com web player and
     * carries no account identity or quota of its own.
     */
    private static final String WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String CLIENT_VERSION = "2.20240726.00.00";
    private static final String BASE = "https://www.youtube.com/youtubei/v1/";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Params selecting the "Videos" (uploads) tab of a channel. Verified working. */
    public static final String TAB_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final OkHttpClient mHttp;
    private final RawRecorder mRecorder;

    public InnerTubeClient(RawRecorder recorder) {
        mRecorder = recorder;
        mHttp = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // --- Public API (blocking: never call from the main thread) -------------

    /** Latvian-scoped search. */
    public JSONObject search(String query) throws IOException {
        JSONObject body = new JSONObject();
        put(body, "query", query);
        return post("search", body, query);
    }

    /** A channel's uploads tab. */
    public JSONObject channelVideos(String channelId) throws IOException {
        JSONObject body = new JSONObject();
        put(body, "browseId", channelId);
        put(body, "params", TAB_VIDEOS);
        return post("browse", body, "channel:" + channelId);
    }

    /** "Watch next" rail for one video. */
    public JSONObject related(String videoId) throws IOException {
        JSONObject body = new JSONObject();
        put(body, "videoId", videoId);
        return post("next", body, "next:" + videoId);
    }

    public void shutdown() {
        mHttp.dispatcher().executorService().shutdown();
        mHttp.connectionPool().evictAll();
    }

    // --- Transport ----------------------------------------------------------

    private JSONObject post(String endpoint, JSONObject body, String summary) throws IOException {
        final long started = System.currentTimeMillis();
        try {
            JSONObject payload = new JSONObject();
            payload.put("context", buildContext());
            java.util.Iterator<String> keys = body.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                payload.put(key, body.get(key));
            }

            Request request = new Request.Builder()
                    .url(BASE + endpoint + "?key=" + WEB_KEY + "&prettyPrint=false")
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", "lv-LV,lv;q=0.9")
                    .header("User-Agent", USER_AGENT)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            Call call = mHttp.newCall(request);
            Response response = call.execute();
            String text;
            try (ResponseBody responseBody = response.body()) {
                text = responseBody == null ? "" : responseBody.string();
            }

            mRecorder.record(endpoint, summary, response.code(), text.length(),
                    System.currentTimeMillis() - started, text);

            if (!response.isSuccessful()) {
                throw new IOException("InnerTube " + endpoint + " HTTP " + response.code());
            }
            try {
                return new JSONObject(text);
            } catch (Exception parseError) {
                // A 200 that is not JSON is almost always a consent or bot-check
                // interstitial. Surface it as an error so it can never be read
                // as "no results".
                throw new IOException("InnerTube " + endpoint + " returned non-JSON body");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "Request failed: " + endpoint, e);
            throw new IOException("InnerTube " + endpoint + " failed: " + e.getMessage(), e);
        }
    }

    private static void put(JSONObject target, String key, String value) throws IOException {
        try {
            target.put(key, value);
        } catch (Exception e) {
            throw new IOException("Could not build request", e);
        }
    }

    private JSONObject buildContext() throws IOException {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", CLIENT_VERSION);
            // Pinned. The app is Latvian-only; device locale must not move this.
            client.put("hl", "lv");
            client.put("gl", "LV");
            JSONObject context = new JSONObject();
            context.put("client", client);
            return context;
        } catch (Exception e) {
            throw new IOException("Could not build context", e);
        }
    }
}
