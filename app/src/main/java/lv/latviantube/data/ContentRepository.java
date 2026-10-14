package lv.latviantube.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lv.latviantube.data.filter.FilterVerdict;
import lv.latviantube.data.filter.LatvianLanguageFilter;
import lv.latviantube.data.innertube.InnerTubeClient;
import lv.latviantube.data.innertube.InnerTubeParser;
import lv.latviantube.data.innertube.RawRecorder;
import lv.latviantube.data.model.Channel;
import lv.latviantube.data.model.Video;
import lv.latviantube.data.model.VideoGroup;
import lv.latviantube.data.registry.ChannelRegistry;

/**
 * The single seam through which the UI obtains video, and the only class in the
 * app that decides what runs on which thread.
 *
 * <h2>Threading contract</h2>
 * Public methods may be called from the main thread and return immediately.
 * <b>All parsing, JSON walking, cache file I/O and network access happen on
 * {@link #mWork}; only the finished result is posted back to the main thread.</b>
 *
 * <p>This is the fix for the app's worst performance problem. The first version
 * delivered InnerTube responses straight to the main thread and then, inside that
 * callback, wrote a cache file and walked the entire response tree twice — once
 * to build the video list and again to count it. On a Grundig Android TV that
 * measured <b>65.98% janky frames</b> (90th percentile 38 ms). The home screen
 * does that work once per curated channel, 17 times over.
 *
 * <h2>Every path is filtered</h2>
 * There is no unfiltered accessor. A caller cannot obtain YouTube content that
 * has not passed {@link LatvianLanguageFilter}, because no such method exists to
 * reach for by mistake.
 *
 * <h2>Counting and honesty about empty results</h2>
 * Rows report both how many videos were retrieved and how many survived the
 * gate. A row that returns 30 and admits 0 is a filter problem; a row that
 * returns 0 is a network or endpoint problem. Collapsing those into "no content"
 * would hide exactly the class of bug the test harness exists to catch.
 */
public final class ContentRepository {

    private static final String TAG = "ContentRepository";

    /** Channel uploads change slowly; 30 minutes bounds request rate well. */
    private static final long TTL_CHANNEL_MILLIS = 30L * 60L * 1000L;
    private static final long TTL_SEARCH_MILLIS = 10L * 60L * 1000L;

    /**
     * Three threads: categories load a few at a time rather than all eight at
     * once, which bounds the request rate against an endpoint with no published
     * quota while keeping first paint reasonably quick.
     */
    private static final int WORKER_THREADS = 3;

    /** Diagnostic attached to a row so the UI can explain a thin result. */
    public static final class RowStats {
        public final int retrieved;
        public final int admitted;
        public final boolean fromCache;
        public final boolean stale;
        public final boolean failed;

        public RowStats(int retrieved, int admitted, boolean fromCache, boolean stale, boolean failed) {
            this.retrieved = retrieved;
            this.admitted = admitted;
            this.fromCache = fromCache;
            this.stale = stale;
            this.failed = failed;
        }
    }

    /** A populated row plus its diagnostics. */
    public static final class Row {
        private final VideoGroup mGroup;
        private final RowStats mStats;

        public Row(VideoGroup group, RowStats stats) {
            mGroup = group;
            mStats = stats;
        }

        public VideoGroup getGroup() {
            return mGroup;
        }

        public RowStats getStats() {
            return mStats;
        }
    }

    private final InnerTubeClient mClient;
    private final CacheStore mCache;
    private final ChannelRegistry mRegistry;
    private final RawRecorder mRecorder;

    private final ExecutorService mWork = Executors.newFixedThreadPool(WORKER_THREADS);
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public ContentRepository(Context context, ChannelRegistry registry) {
        mRegistry = registry;
        mRecorder = new RawRecorder(context);
        mClient = new InnerTubeClient(mRecorder);
        mCache = new CacheStore(context);
    }

    public ChannelRegistry getRegistry() {
        return mRegistry;
    }

    public RawRecorder getRecorder() {
        return mRecorder;
    }

    // --- Home feed ----------------------------------------------------------

    /**
     * Loads one category row by fetching every curated channel in it.
     *
     * <p>Channels are fetched sequentially rather than in parallel: the point is
     * to finish a row, not to saturate a shared endpoint.
     */
    public void loadCategoryRow(final ChannelRegistry.Category category,
                                final ResultCallback<Row> callback) {
        mWork.execute(() -> {
            final String cacheKey = "category:" + category.getId();
            CacheStore.Entry cached = mCache.get(cacheKey);

            if (cached != null && cached.isFresh(TTL_CHANNEL_MILLIS)) {
                postResult(callback, buildRowFromCache(category, cached.getJson(), false));
                return;
            }

            VideoGroup group = new VideoGroup(category.getId(), category.getTitleLv());
            int retrieved = 0;
            int admitted = 0;
            boolean anySucceeded = false;

            for (Channel channel : category.getChannels()) {
                String perChannelKey = "channel:" + channel.getId();
                CacheStore.Entry channelCache = mCache.get(perChannelKey);

                JSONObject json = null;
                if (channelCache != null && channelCache.isFresh(TTL_CHANNEL_MILLIS)) {
                    json = channelCache.getJson();
                } else {
                    try {
                        json = mClient.channelVideos(channel.getId());
                        mCache.put(perChannelKey, json);
                    } catch (Exception e) {
                        Log.w(TAG, "Channel fetch failed: " + channel.getId(), e);
                        // Fall back to a stale entry rather than dropping the row.
                        if (channelCache != null) {
                            json = channelCache.getJson();
                        }
                    }
                }

                if (json == null) {
                    continue;
                }
                anySucceeded = true;

                // Parse once. The previous version walked this tree twice.
                List<Video> raw = InnerTubeParser.extractVideos(json);
                List<Video> latvian = filter(raw, channel);
                retrieved += raw.size();
                admitted += latvian.size();
                group.addAll(latvian);
            }

            if (!anySucceeded && cached != null) {
                // Everything failed but we have an old copy: better than nothing.
                postResult(callback, buildRowFromCache(category, cached.getJson(), true));
                return;
            }

            if (retrieved > 0) {
                mCache.put(cacheKey, serializeGroup(group, retrieved));
            }

            final RowStats stats = new RowStats(retrieved, admitted, false, false, false);
            postResult(callback, new Row(group, stats));

            if (!anySucceeded) {
                postError(callback, new Exception("no channels loaded"),
                        "Neizdevās ielādēt kanālus");
            }
        });
    }

    // --- Search -------------------------------------------------------------

    /** Latvian-scoped search across all of YouTube, then gated. */
    public void search(final String query, final ResultCallback<List<Video>> callback) {
        mWork.execute(() -> {
            final String cacheKey = "search:" + query.toLowerCase();
            CacheStore.Entry cached = mCache.get(cacheKey);

            if (cached != null && cached.isFresh(TTL_SEARCH_MILLIS)) {
                List<Video> videos = filter(InnerTubeParser.extractVideos(cached.getJson()), null);
                if (!videos.isEmpty()) {
                    postResult(callback, videos);
                    return;
                }
            }

            try {
                JSONObject result = mClient.search(query);
                mCache.put(cacheKey, result);
                List<Video> raw = InnerTubeParser.extractVideos(result);
                List<Video> videos = filter(raw, null);
                Log.i(TAG, "Search '" + query + "': " + raw.size()
                        + " retrieved, " + videos.size() + " Latvian");
                postResult(callback, videos);
            } catch (Exception e) {
                Log.w(TAG, "Search failed: " + query, e);
                postError(callback, e, "Meklēšana neizdevās");
            }
        });
    }

    // --- Related ------------------------------------------------------------

    /** Videos related to the one being watched, gated. */
    public void loadRelated(final String videoId, final ResultCallback<List<Video>> callback) {
        mWork.execute(() -> {
            try {
                JSONObject result = mClient.related(videoId);
                postResult(callback, filter(InnerTubeParser.extractVideos(result), null));
            } catch (Exception e) {
                Log.w(TAG, "Related failed: " + videoId, e);
                postError(callback, e, "Neizdevās ielādēt saistītos video");
            }
        });
    }

    // --- Filtering ----------------------------------------------------------

    /**
     * Runs every video through the Latvian gate.
     *
     * @param owner when non-null, the channel whose uploads tab produced these
     *              videos. A channel uploads response carries no byline, so the
     *              owning channel is stamped on. Without this the curated bonus
     *              never fires and genuine Latvian titles that depend on it are
     *              dropped — a bug the registry verifier caught.
     */
    private List<Video> filter(List<Video> videos, Channel owner) {
        List<Video> admitted = new ArrayList<>();
        for (Video video : videos) {
            String channelId = owner != null ? owner.getId() : video.getChannelId();
            String channelTitle = owner != null ? owner.getTitle() : video.getChannelTitle();

            boolean curated = mRegistry.isCuratedChannel(channelId);
            FilterVerdict verdict = LatvianLanguageFilter.gate(
                    video.scoringText(), curated, channelTitle, null);

            video.setVerdict(verdict);
            if (verdict.isAccepted()) {
                admitted.add(new Video(
                        video.getVideoId(), video.getTitle(),
                        channelTitle, channelId, video.getDescription(),
                        video.getThumbnailUrl(), video.getDurationText(),
                        video.getViewCountText(), video.getPublishedTimeText()));
            }
        }
        return admitted;
    }

    // --- Main-thread delivery ----------------------------------------------

    private <T> void postResult(ResultCallback<T> callback, T value) {
        mMain.post(() -> callback.onResult(value));
    }

    private <T> void postError(ResultCallback<T> callback, Exception error, String diagnostic) {
        mMain.post(() -> callback.onError(error, diagnostic));
    }

    // --- Cache helpers (background thread only) -----------------------------

    private Row buildRowFromCache(ChannelRegistry.Category category, JSONObject json, boolean stale) {
        VideoGroup group = deserializeGroup(category, json);
        int retrieved = json.optInt("retrieved", group.size());
        return new Row(group, new RowStats(retrieved, group.size(), true, stale, false));
    }

    private JSONObject serializeGroup(VideoGroup group, int retrieved) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", group.getId());
            json.put("title", group.getTitle());
            json.put("retrieved", retrieved);
            JSONArray videos = new JSONArray();
            for (Video v : group.getVideos()) {
                JSONObject item = new JSONObject();
                item.put("videoId", v.getVideoId());
                item.put("title", v.getTitle());
                item.put("channelTitle", v.getChannelTitle());
                item.put("channelId", v.getChannelId());
                item.put("thumbnailUrl", v.getThumbnailUrl());
                item.put("durationText", v.getDurationText());
                item.put("viewCountText", v.getViewCountText());
                item.put("publishedTimeText", v.getPublishedTimeText());
                videos.put(item);
            }
            json.put("videos", videos);
        } catch (Exception e) {
            Log.w(TAG, "Could not serialize group", e);
        }
        return json;
    }

    private VideoGroup deserializeGroup(ChannelRegistry.Category category, JSONObject json) {
        VideoGroup group = new VideoGroup(category.getId(), category.getTitleLv());
        JSONArray videos = json.optJSONArray("videos");
        if (videos == null) {
            return group;
        }
        for (int i = 0; i < videos.length(); i++) {
            JSONObject item = videos.optJSONObject(i);
            if (item == null) {
                continue;
            }
            group.add(new Video(
                    item.optString("videoId"),
                    item.optString("title"),
                    item.optString("channelTitle"),
                    item.optString("channelId", null),
                    "",
                    item.optString("thumbnailUrl", null),
                    item.optString("durationText", null),
                    item.optString("viewCountText", null),
                    item.optString("publishedTimeText", null)));
        }
        return group;
    }

    public void shutdown() {
        mWork.shutdownNow();
        mClient.shutdown();
    }
}
