package lv.latviantube.data.playback;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.MediaFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/** Delegates YouTube's changing signatures/client negotiation to a pinned extractor. */
public final class StreamResolver {
    // NewPipe caches service/player state globally. Serialize its use across activity instances.
    private static final Object EXTRACTION_LOCK = new Object();
    private static final ExtractorDownloader DOWNLOADER = new ExtractorDownloader();
    private static boolean initialized;

    public static boolean validVideoId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }

    public void cancel(Thread worker) {
        DOWNLOADER.cancel(worker);
    }

    public PlaybackSource resolve(String videoId) throws Exception {
        if (!validVideoId(videoId)) throw new IOException("Invalid video ID");
        synchronized (EXTRACTION_LOCK) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if (!initialized) {
                NewPipe.init(DOWNLOADER, new Localization("lv", "LV"), new ContentCountry("LV"));
                initialized = true;
            }
            // Use only the stream extractor, avoiding unrelated recommendations/comments requests.
            StreamExtractor extractor = ServiceList.YouTube.getStreamExtractor(
                    "https://www.youtube.com/watch?v=" + videoId);
            extractor.fetchPage();
            boolean live = extractor.getStreamType() == StreamType.LIVE_STREAM;
            List<PlaybackSource.Variant> variants = new ArrayList<>();
            if (live) {
                addManifest(variants, extractor::getHlsUrl, PlaybackSource.Kind.HLS);
                if (variants.isEmpty()) {
                    addManifest(variants, extractor::getDashMpdUrl, PlaybackSource.Kind.DASH);
                }
            } else {
                List<AudioStream> audio;
                try { audio = extractor.getAudioStreams(); }
                catch (Exception unavailableAudio) { audio = Collections.emptyList(); }
                AudioStream bestAudio = audio.stream().filter(StreamResolver::progressive)
                        .max(Comparator.comparingInt(StreamResolver::audioPreference)
                                .thenComparingInt(AudioStream::getAverageBitrate)).orElse(null);
                if (bestAudio != null) {
                    List<VideoStream> videoOnly;
                    try { videoOnly = extractor.getVideoOnlyStreams(); }
                    catch (Exception unavailableVideo) { videoOnly = Collections.emptyList(); }
                    for (VideoStream video : videoOnly) {
                        if (progressive(video)) variants.add(variant(video, bestAudio));
                    }
                }
                List<VideoStream> muxed;
                try { muxed = extractor.getVideoStreams(); }
                catch (Exception unavailableMuxed) { muxed = Collections.emptyList(); }
                for (VideoStream video : muxed) {
                    if (progressive(video)) variants.add(variant(video, null));
                }
                if (variants.isEmpty()) {
                    addManifest(variants, extractor::getHlsUrl, PlaybackSource.Kind.HLS);
                    addManifest(variants, extractor::getDashMpdUrl, PlaybackSource.Kind.DASH);
                }
            }
            if (variants.isEmpty()) throw new IOException("Extractor returned no supported media");
            variants.sort(Comparator.comparingInt((PlaybackSource.Variant v) -> v.height).reversed());
            List<PlaybackSource.Subtitle> subtitles = new ArrayList<>();
            // Request WebVTT explicitly; retain Latvian captions without translating foreign audio.
            try {
                for (SubtitlesStream subtitle : extractor.getSubtitles(MediaFormat.VTT)) {
                    if (subtitle.getLanguageTag().startsWith("lv") && subtitle.isUrl()) {
                        subtitles.add(new PlaybackSource.Subtitle(subtitle.getContent(),
                                "text/vtt", subtitle.getLanguageTag()));
                        break;
                    }
                }
            } catch (Exception optionalCaptionFailure) {
                // Missing captions must not prevent media playback.
            }
            return new PlaybackSource(videoId, variants, subtitles, live, false);
        }
    }

    private static boolean progressive(Stream stream) {
        return stream.isUrl() && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                && stream.getContent().startsWith("https://") && stream.getFormat() != null;
    }

    private static int audioPreference(AudioStream stream) {
        int score = stream.getAudioLocale() != null
                && "lv".equals(stream.getAudioLocale().getLanguage()) ? 100 : 0;
        if (stream.getAudioTrackType() == AudioTrackType.ORIGINAL) score += 20;
        if ("audio/mp4".equals(stream.getFormat().getMimeType())) score += 10;
        return score;
    }

    private static PlaybackSource.Variant variant(VideoStream video, AudioStream audio) {
        String codec = video.getCodec();
        String family = codec == null ? "" : codec.startsWith("avc") ? "AVC"
                : codec.startsWith("vp") ? "VP9" : codec.startsWith("av01") ? "AV1" : codec;
        return new PlaybackSource.Variant(video.getId() + (audio == null ? "" : ":" + audio.getId()),
                video.getResolution() + (family.isEmpty() ? "" : " · " + family),
                video.getContent(), audio == null ? null : audio.getContent(),
                video.getFormat().getMimeType(), audio == null ? null : audio.getFormat().getMimeType(),
                codec, video.getHeight(), PlaybackSource.Kind.PROGRESSIVE);
    }

    private static void addManifest(List<PlaybackSource.Variant> variants, java.util.concurrent.Callable<String> lookup,
                                    PlaybackSource.Kind kind) {
        String url;
        try { url = lookup.call(); }
        catch (Exception unavailableManifest) { return; }
        if (url != null && url.startsWith("https://")) {
            variants.add(new PlaybackSource.Variant(kind.name(), kind.name(), url, null,
                    kind == PlaybackSource.Kind.HLS ? "application/x-mpegURL" : "application/dash+xml",
                    null, null, 0, kind));
        }
    }
}
