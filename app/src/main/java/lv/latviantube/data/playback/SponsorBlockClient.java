package lv.latviantube.data.playback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Optional, fail-open sponsor skipping. Hash-prefix lookup avoids sending a complete video ID. */
public final class SponsorBlockClient {
    public static final class Segment {
        public final long startMs;
        public final long endMs;
        public Segment(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private final OkHttpClient http;
    public SponsorBlockClient(OkHttpClient http) { this.http = http; }

    public List<Segment> fetch(String videoId) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(videoId.getBytes(StandardCharsets.UTF_8));
        String prefix = String.format(Locale.US, "%02x%02x", hash[0] & 255, hash[1] & 255);
        Request request = new Request.Builder().url("https://sponsor.ajay.app/api/skipSegments/"
                + prefix + "?categories=%5B%22sponsor%22%5D&actionTypes=%5B%22skip%22%5D").build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) return new ArrayList<>();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("SponsorBlock HTTP " + response.code());
            }
            JSONArray videos = new JSONArray(response.body().string());
            for (int i = 0; i < videos.length(); i++) {
                JSONObject video = videos.getJSONObject(i);
                if (videoId.equals(video.optString("videoID"))) {
                    return parse(video.optJSONArray("segments"));
                }
            }
            return new ArrayList<>();
        }
    }

    public static List<Segment> parse(JSONArray data) {
        List<Segment> result = new ArrayList<>();
        if (data == null) return result;
        for (int i = 0; i < data.length(); i++) {
            JSONObject entry = data.optJSONObject(i);
            if (entry == null || !"sponsor".equals(entry.optString("category"))
                    || !"skip".equals(entry.optString("actionType", "skip"))) continue;
            JSONArray range = entry.optJSONArray("segment");
            if (range == null || range.length() != 2) continue;
            double start = range.optDouble(0, Double.NaN), end = range.optDouble(1, Double.NaN);
            if (Double.isNaN(start) || Double.isNaN(end) || Double.isInfinite(start)
                    || Double.isInfinite(end) || start < 0 || end <= start
                    || end > Long.MAX_VALUE / 1000.0) continue;
            result.add(new Segment((long) (start * 1000), (long) Math.ceil(end * 1000)));
        }
        result.sort(Comparator.comparingLong(s -> s.startMs));
        List<Segment> merged = new ArrayList<>();
        for (Segment segment : result) {
            if (!merged.isEmpty() && segment.startMs <= merged.get(merged.size() - 1).endMs) {
                Segment previous = merged.remove(merged.size() - 1);
                merged.add(new Segment(previous.startMs, Math.max(previous.endMs, segment.endMs)));
            } else merged.add(segment);
        }
        return merged;
    }

    public static long skipTo(List<Segment> segments, long positionMs, long durationMs) {
        for (Segment segment : segments) {
            if (positionMs >= segment.startMs && positionMs < segment.endMs) {
                return durationMs > 0 ? Math.min(segment.endMs, durationMs) : segment.endMs;
            }
        }
        return positionMs;
    }
}
