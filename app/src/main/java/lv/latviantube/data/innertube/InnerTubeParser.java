package lv.latviantube.data.innertube;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lv.latviantube.data.model.Channel;
import lv.latviantube.data.model.Video;

/**
 * Turns an InnerTube response into {@link Video} / {@link Channel} records.
 *
 * <h2>Why this is not a simple field lookup</h2>
 * InnerTube nests the same video under several different renderer keys depending
 * on which surface produced it, and YouTube has been migrating surfaces from
 * {@code *Renderer} to {@code *ViewModel} shapes. Critically, channel tabs and
 * search results now return {@code lockupViewModel} — a shape that contains no
 * {@code videoRenderer} at all.
 *
 * <p>That migration is not hypothetical: the first version of this parser looked
 * only for {@code *Renderer} keys and silently returned <em>zero</em> videos for
 * every channel uploads request, while a naive regex over the same bytes found
 * 30. The regression was caught by the Node harness (see {@code ANALYSIS.md},
 * evidence group E4). This parser handles both shapes for that reason.
 */
public final class InnerTubeParser {

    /** Classic renderer keys that carry a video. */
    private static final String[] VIDEO_RENDERER_KEYS = {
            "videoRenderer",
            "gridVideoRenderer",
            "compactVideoRenderer",
            "playlistVideoRenderer",
    };

    private InnerTubeParser() {
    }

    private interface Visitor {
        void visit(JSONObject object);
    }

    // --- Public API ---------------------------------------------------------

    /** Every distinct video in the response, in document order. */
    public static List<Video> extractVideos(JSONObject root) {
        Map<String, Video> byId = new LinkedHashMap<>();
        walk(root, object -> {
            JSONObject lockup = object.optJSONObject("lockupViewModel");
            if (lockup != null) {
                Video v = fromLockup(lockup);
                if (v != null && !byId.containsKey(v.getVideoId())) {
                    byId.put(v.getVideoId(), v);
                }
            }
            for (String key : VIDEO_RENDERER_KEYS) {
                JSONObject r = object.optJSONObject(key);
                if (r == null) {
                    continue;
                }
                Video v = fromRenderer(r);
                if (v != null && !byId.containsKey(v.getVideoId())) {
                    byId.put(v.getVideoId(), v);
                }
            }
        });
        return new ArrayList<>(byId.values());
    }

    /** Every distinct channel referenced by the response. */
    public static List<Channel> extractChannels(JSONObject root) {
        Map<String, Channel> byId = new LinkedHashMap<>();
        walk(root, object -> {
            JSONObject r = object.optJSONObject("channelRenderer");
            if (r == null) {
                return;
            }
            String id = r.optString("channelId", null);
            if (TextUtils.isEmpty(id) || byId.containsKey(id)) {
                return;
            }
            String title = textOf(r.optJSONObject("title"));
            String subs = textOf(r.optJSONObject("subscriberCountText"));
            if (TextUtils.isEmpty(subs)) {
                subs = textOf(r.optJSONObject("videoCountText"));
            }
            byId.put(id, new Channel(id, title, subs, null, null));
        });
        return new ArrayList<>(byId.values());
    }

    // --- Shape handling -----------------------------------------------------

    private static Video fromRenderer(JSONObject r) {
        String videoId = r.optString("videoId", null);
        if (TextUtils.isEmpty(videoId)) {
            return null;
        }
        String owner = textOf(r.optJSONObject("ownerText"));
        if (TextUtils.isEmpty(owner)) {
            owner = textOf(r.optJSONObject("longBylineText"));
        }
        if (TextUtils.isEmpty(owner)) {
            owner = textOf(r.optJSONObject("shortBylineText"));
        }
        return new Video(
                videoId,
                textOf(r.optJSONObject("title")),
                owner,
                channelIdFrom(r.optJSONObject("ownerText")),
                textOf(r.optJSONObject("descriptionSnippet")),
                thumbnailOf(r.optJSONObject("thumbnail")),
                textOf(r.optJSONObject("lengthText")),
                firstNonEmpty(textOf(r.optJSONObject("viewCountText")),
                        textOf(r.optJSONObject("shortViewCountText"))),
                textOf(r.optJSONObject("publishedTimeText")));
    }

    /**
     * The modern shape. Title and byline live in a nested
     * {@code lockupMetadataViewModel}; the metadata rows carry a positional mix
     * of view count and published time with no stable labels.
     */
    private static Video fromLockup(JSONObject l) {
        String videoId = l.optString("contentId", null);
        if (TextUtils.isEmpty(videoId)) {
            return null;
        }
        // Guard against lockups that represent playlists or channels.
        String type = l.optString("contentType", "");
        if (!TextUtils.isEmpty(type) && !type.contains("VIDEO")) {
            return null;
        }

        JSONObject metadata = l.optJSONObject("metadata");
        JSONObject lockupMeta = metadata == null ? null : metadata.optJSONObject("lockupMetadataViewModel");

        String title = "";
        List<String> parts = new ArrayList<>();
        if (lockupMeta != null) {
            JSONObject titleNode = lockupMeta.optJSONObject("title");
            if (titleNode != null) {
                title = titleNode.optString("content", textOf(titleNode));
            }
            JSONObject md = lockupMeta.optJSONObject("metadata");
            JSONObject contentMeta = md == null ? null : md.optJSONObject("contentMetadataViewModel");
            JSONArray rows = contentMeta == null ? null : contentMeta.optJSONArray("metadataRows");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    JSONArray rowParts = row == null ? null : row.optJSONArray("metadataParts");
                    if (rowParts == null) {
                        continue;
                    }
                    for (int j = 0; j < rowParts.length(); j++) {
                        JSONObject part = rowParts.optJSONObject(j);
                        if (part == null) {
                            continue;
                        }
                        JSONObject textNode = part.optJSONObject("text");
                        String value = textNode != null
                                ? textNode.optString("content", textOf(textNode))
                                : textOf(part);
                        if (!TextUtils.isEmpty(value)) {
                            parts.add(value);
                        }
                    }
                }
            }
        }

        String duration = "";
        String views = "";
        String published = "";
        for (String part : parts) {
            if (TextUtils.isEmpty(duration) && part.matches("^\\d+:\\d{2}(:\\d{2})?$")) {
                duration = part;
            } else if (TextUtils.isEmpty(views) && part.matches("(?i).*(skat|view).*")) {
                views = part;
            } else if (TextUtils.isEmpty(published) && part.matches("(?i).*(pirms|ago).*")) {
                published = part;
            }
        }

        String channelId = metadata == null ? null : findChannelId(metadata);
        return new Video(videoId, title, "", channelId, "", thumbnailOfLockup(l),
                duration, views, published);
    }

    // --- Helpers ------------------------------------------------------------

    /** Recursively search a subtree for the first channel id. */
    private static String findChannelId(JSONObject node) {
        final String[] found = {null};
        walk(node, object -> {
            if (found[0] != null) {
                return;
            }
            JSONObject endpoint = object.optJSONObject("browseEndpoint");
            if (endpoint == null) {
                return;
            }
            String id = endpoint.optString("browseId", null);
            if (id != null && id.startsWith("UC")) {
                found[0] = id;
            }
        });
        return found[0];
    }

    private static String channelIdFrom(JSONObject textNode) {
        if (textNode == null) {
            return null;
        }
        JSONArray runs = textNode.optJSONArray("runs");
        if (runs == null || runs.length() == 0) {
            return null;
        }
        JSONObject first = runs.optJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject nav = first.optJSONObject("navigationEndpoint");
        JSONObject browse = nav == null ? null : nav.optJSONObject("browseEndpoint");
        return browse == null ? null : browse.optString("browseId", null);
    }

    /** Reads {@code simpleText} or joins {@code runs[].text}. */
    public static String textOf(JSONObject node) {
        if (node == null) {
            return "";
        }
        String simple = node.optString("simpleText", null);
        if (!TextUtils.isEmpty(simple)) {
            return simple;
        }
        String content = node.optString("content", null);
        if (!TextUtils.isEmpty(content)) {
            return content;
        }
        JSONArray runs = node.optJSONArray("runs");
        if (runs == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                sb.append(run.optString("text", ""));
            }
        }
        return sb.toString();
    }

    private static String thumbnailOf(JSONObject thumbnail) {
        if (thumbnail == null) {
            return null;
        }
        JSONArray thumbs = thumbnail.optJSONArray("thumbnails");
        if (thumbs == null || thumbs.length() == 0) {
            return null;
        }
        JSONObject last = thumbs.optJSONObject(thumbs.length() - 1);
        return last == null ? null : last.optString("url", null);
    }

    private static String thumbnailOfLockup(JSONObject lockup) {
        JSONObject image = lockup.optJSONObject("contentImage");
        if (image == null) {
            return null;
        }
        JSONObject thumbVm = image.optJSONObject("thumbnailViewModel");
        String url = null;
        if (thumbVm != null) {
            url = thumbnailOf(thumbVm.optJSONObject("image"));
        }
        if (TextUtils.isEmpty(url)) {
            JSONObject collection = image.optJSONObject("collectionThumbnailViewModel");
            JSONObject primary = collection == null ? null : collection.optJSONObject("primaryThumbnail");
            JSONObject inner = primary == null ? null : primary.optJSONObject("thumbnailViewModel");
            if (inner != null) {
                url = thumbnailOf(inner.optJSONObject("image"));
            }
        }
        return url;
    }

    private static String firstNonEmpty(String a, String b) {
        return TextUtils.isEmpty(a) ? b : a;
    }

    private static void walk(Object node, Visitor visitor) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            visitor.visit(object);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                walk(object.opt(keys.next()), visitor);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                walk(array.opt(i), visitor);
            }
        }
    }
}
