package lv.latviantube.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * A small on-disk JSON cache with a time-to-live.
 *
 * <p>Two reasons this exists rather than relying on OkHttp's HTTP cache:
 *
 * <ol>
 *   <li><b>Pacing.</b> InnerTube is an undocumented endpoint used here without a
 *       quota or key. The app should not re-fetch every curated channel on every
 *       launch, and a TV box that is left on should not poll continuously. A TTL
 *       bounds the request rate by construction.</li>
 *   <li><b>Offline behaviour.</b> A cached row is better than an error screen on
 *       a device whose network dropped between launches.</li>
 * </ol>
 *
 * <p>Expired entries are still returned when a refresh fails, flagged as stale,
 * so a transient network failure degrades to old content rather than nothing.
 */
public final class CacheStore {

    private static final String TAG = "CacheStore";
    private static final String DIR = "innertube-cache";

    private final File mDir;

    public CacheStore(Context context) {
        mDir = new File(context.getCacheDir(), DIR);
        if (!mDir.exists() && !mDir.mkdirs()) {
            Log.w(TAG, "Could not create cache dir: " + mDir);
        }
    }

    /** One cached payload plus its age. */
    public static final class Entry {
        private final JSONObject mJson;
        private final long mAgeMillis;

        Entry(JSONObject json, long ageMillis) {
            mJson = json;
            mAgeMillis = ageMillis;
        }

        public JSONObject getJson() {
            return mJson;
        }

        public long getAgeMillis() {
            return mAgeMillis;
        }

        public boolean isFresh(long ttlMillis) {
            return mAgeMillis <= ttlMillis;
        }
    }

    /** Returns the entry whether or not it is fresh, or null if absent/unreadable. */
    public Entry get(String key) {
        File file = fileFor(key);
        if (!file.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
            JSONObject wrapper = new JSONObject(sb.toString());
            JSONObject payload = wrapper.optJSONObject("payload");
            long storedAt = wrapper.optLong("storedAt", 0L);
            if (payload == null) {
                return null;
            }
            return new Entry(payload, System.currentTimeMillis() - storedAt);
        } catch (Exception e) {
            Log.w(TAG, "Cache read failed for " + key, e);
            return null;
        }
    }

    public void put(String key, JSONObject payload) {
        try (FileWriter writer = new FileWriter(fileFor(key))) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("storedAt", System.currentTimeMillis());
            wrapper.put("payload", payload);
            writer.write(wrapper.toString());
        } catch (Exception e) {
            Log.w(TAG, "Cache write failed for " + key, e);
        }
    }

    public void clear() {
        File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private File fileFor(String key) {
        // Keep the name filesystem-safe; keys contain colons and slashes.
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(mDir, safe + ".json");
    }
}
