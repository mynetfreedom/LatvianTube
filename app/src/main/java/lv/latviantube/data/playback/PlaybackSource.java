package lv.latviantube.data.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resolved media only; content discovery still goes through ContentRepository. */
public final class PlaybackSource {
    public enum Kind { PROGRESSIVE, HLS, DASH }

    public static final class Variant {
        public final String id;
        public final String label;
        public final String videoUrl;
        public final String audioUrl;
        public final String videoMimeType;
        public final String audioMimeType;
        public final String codec;
        public final int height;
        public final Kind kind;

        public Variant(String id, String label, String videoUrl, String audioUrl,
                       String videoMimeType, String audioMimeType, String codec,
                       int height, Kind kind) {
            this.id = id;
            this.label = label;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.videoMimeType = videoMimeType;
            this.audioMimeType = audioMimeType;
            this.codec = codec;
            this.height = height;
            this.kind = kind;
        }
    }

    public static final class Subtitle {
        public final String url;
        public final String mimeType;
        public final String language;

        public Subtitle(String url, String mimeType, String language) {
            this.url = url;
            this.mimeType = mimeType;
            this.language = language;
        }
    }

    public final String videoId;
    public final List<Variant> variants;
    public final List<Subtitle> subtitles;
    public final boolean live;
    public final boolean offline;

    public PlaybackSource(String videoId, List<Variant> variants, List<Subtitle> subtitles,
                          boolean live, boolean offline) {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("No playable variants");
        }
        this.videoId = videoId;
        this.variants = Collections.unmodifiableList(new ArrayList<>(variants));
        this.subtitles = Collections.unmodifiableList(new ArrayList<>(subtitles));
        this.live = live;
        this.offline = offline;
    }
}
