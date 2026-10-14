package lv.latviantube.data.registry;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lv.latviantube.data.model.Channel;

/**
 * The curated Latvian channel registry, loaded from
 * {@code assets/channels_lv.json}.
 *
 * <p>This registry is the backbone of the app, for two reasons:
 *
 * <ol>
 *   <li><b>It is the content source.</b> Channel uploads are the only reliable
 *       way to get Latvian video in bulk — the trending chart is unusable
 *       (HTTP 400) and search alone gives thin, query-dependent coverage.</li>
 *   <li><b>It is the strongest filter signal.</b> Registry membership adds
 *       {@code +6} in {@link lv.latviantube.data.filter.LatvianLanguageFilter},
 *       which is what rescues genuine Latvian titles that carry no diacritics
 *       and no function words (e.g. "DZIESMAS JAUNAI DIENAI").</li>
 * </ol>
 *
 * <p>Because membership is trusted, the registry must be curated and not merely
 * harvested. The same file therefore records an {@code excluded} list of
 * channels that <em>look</em> Latvian but are not, each with the reason — see
 * {@link #getExcluded()}. Those entries document real failures found by
 * {@code tools/verify-registry.mjs}, including a channel named "Latvijas Ziņas"
 * that publishes only in Russian.
 */
public final class ChannelRegistry {

    private static final String TAG = "ChannelRegistry";
    private static final String ASSET = "channels_lv.json";

    /** One themed row: a category of curated channels. */
    public static final class Category {
        private final String mId;
        private final String mTitleLv;
        private final String mTitleEn;
        private final List<Channel> mChannels;

        Category(String id, String titleLv, String titleEn, List<Channel> channels) {
            mId = id;
            mTitleLv = titleLv;
            mTitleEn = titleEn;
            mChannels = Collections.unmodifiableList(channels);
        }

        public String getId() {
            return mId;
        }

        /** Latvian display title, e.g. "Ziņas". */
        public String getTitleLv() {
            return mTitleLv;
        }

        public String getTitleEn() {
            return mTitleEn;
        }

        public List<Channel> getChannels() {
            return mChannels;
        }
    }

    /** A channel deliberately kept out of the registry, with the reason. */
    public static final class Exclusion {
        private final String mChannelId;
        private final String mTitle;
        private final String mReason;

        Exclusion(String channelId, String title, String reason) {
            mChannelId = channelId;
            mTitle = title;
            mReason = reason;
        }

        public String getChannelId() {
            return mChannelId;
        }

        public String getTitle() {
            return mTitle;
        }

        public String getReason() {
            return mReason;
        }
    }

    private final List<Category> mCategories = new ArrayList<>();
    private final Set<String> mIds = new HashSet<>();
    private final List<Exclusion> mExcluded = new ArrayList<>();

    private ChannelRegistry() {
    }

    /** Parses the bundled asset. Returns an empty registry rather than throwing. */
    public static ChannelRegistry load(Context context) {
        ChannelRegistry registry = new ChannelRegistry();
        try (InputStream in = context.getAssets().open(ASSET)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            registry.parse(new JSONObject(sb.toString()));
        } catch (Exception e) {
            Log.e(TAG, "Could not load " + ASSET, e);
        }
        return registry;
    }

    private void parse(JSONObject root) throws Exception {
        JSONArray excluded = root.optJSONArray("excluded");
        if (excluded != null) {
            for (int i = 0; i < excluded.length(); i++) {
                JSONObject e = excluded.getJSONObject(i);
                mExcluded.add(new Exclusion(
                        e.optString("channelId"),
                        e.optString("title"),
                        e.optString("reason")));
            }
        }

        JSONArray categories = root.optJSONArray("categories");
        if (categories == null) {
            return;
        }
        for (int i = 0; i < categories.length(); i++) {
            JSONObject c = categories.getJSONObject(i);
            String categoryId = c.optString("id");
            String titleLv = c.optString("titleLv");
            String titleEn = c.optString("titleEn");
            List<Channel> channels = new ArrayList<>();
            JSONArray items = c.optJSONArray("channels");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject ch = items.getJSONObject(j);
                    String id = ch.optString("id");
                    channels.add(new Channel(id, ch.optString("title"),
                            ch.optString("handle"), categoryId, titleLv));
                    mIds.add(id);
                }
            }
            mCategories.add(new Category(categoryId, titleLv, titleEn, channels));
        }
        Log.i(TAG, "Loaded " + mIds.size() + " curated channels in "
                + mCategories.size() + " categories; " + mExcluded.size() + " exclusions recorded.");
    }

    /** Whether a channel is trusted Latvian. Drives the +6 filter signal. */
    public boolean isCuratedChannel(String channelId) {
        return channelId != null && mIds.contains(channelId);
    }

    public List<Category> getCategories() {
        return Collections.unmodifiableList(mCategories);
    }

    public List<Exclusion> getExcluded() {
        return Collections.unmodifiableList(mExcluded);
    }

    public int size() {
        return mIds.size();
    }
}
