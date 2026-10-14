package lv.latviantube.ui.playback;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import lv.latviantube.R;
import lv.latviantube.data.model.Video;
import lv.latviantube.data.playback.ExtractorDownloader;
import lv.latviantube.data.playback.OfflineStore;
import lv.latviantube.data.playback.PlaybackSource;
import lv.latviantube.data.playback.SponsorBlockClient;
import lv.latviantube.data.playback.StreamResolver;
import okhttp3.OkHttpClient;

/** Hardware-decoded playback; extraction, optional lookups and file I/O stay off the main thread. */
@UnstableApi
public final class ExoPlayerEngine implements PlayerEngine {
    private static final String TAG = "ExoPlayerEngine";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService extraction = Executors.newSingleThreadExecutor();
    private final ExecutorService extras = Executors.newSingleThreadExecutor();
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final StreamResolver resolver = new StreamResolver();
    private final ExoPlayer player;
    private final PlayerView view;
    private final Listener listener;
    private final DefaultMediaSourceFactory mediaFactory;
    private final OfflineStore offline;
    private final SponsorBlockClient sponsorBlock;
    private final android.content.SharedPreferences preferences;
    private volatile Thread extractionThread;
    private Future<?> resolveJob, sponsorJob, downloadJob;
    private PlaybackSource source;
    private PlaybackSource.Variant selectedVariant;
    private List<PlaybackSource.Variant> supported = new ArrayList<>();
    private List<SponsorBlockClient.Segment> segments = Collections.emptyList();
    private Video currentVideo;
    private String quality = "auto";
    private boolean released, desiredPlay = true, sponsorEnabled, saving, saved;
    private int generation, sponsorGeneration, refreshes;

    public ExoPlayerEngine(Context context, PlayerView view, Listener listener) {
        this.view = view;
        this.listener = listener;
        preferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE);
        sponsorEnabled = preferences.getBoolean("sponsorBlock", false);
        OkHttpClient mediaHttp = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).build();
        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(mediaHttp)
                .setUserAgent(ExtractorDownloader.USER_AGENT);
        mediaFactory = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, httpFactory));
        offline = new OfflineStore(context.getFilesDir(), mediaHttp);
        sponsorBlock = new SponsorBlockClient(new OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS).build());
        player = new ExoPlayer.Builder(context).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage("lv").setPreferredTextLanguage("lv").build());
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (released) return;
                listener.onLoading(state == Player.STATE_BUFFERING);
                if (state == Player.STATE_READY) listener.onReady();
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (released) return;
                // Signed YouTube URLs expire. Refresh once at the current position; never loop.
                if (source != null && !source.offline && refreshes == 0 && expiredUrl(error)) {
                    refreshes++;
                    resolve(player.getCurrentPosition(), false);
                    return;
                }
                Log.w(TAG, "Media playback failed, code=" + error.errorCode);
                listener.onLoading(false);
                listener.onError(R.string.playback_media_failed);
            }
        });
        main.post(skipTick);
    }

    @Override public void open(Video video) {
        if (released) return;
        cancelJobs();
        generation++;
        currentVideo = video;
        source = null;
        selectedVariant = null;
        supported.clear();
        segments = Collections.emptyList();
        quality = "auto";
        refreshes = 0;
        saved = false;
        player.stop();
        player.clearMediaItems();
        listener.onOfflineChanged(false, false);
        if (video == null || !StreamResolver.validVideoId(video.getVideoId())) {
            listener.onError(R.string.playback_missing_id);
            return;
        }
        resolve(0, true);
    }

    private void resolve(long position, boolean useOffline) {
        if (resolveJob != null) resolveJob.cancel(true);
        resolver.cancel(extractionThread);
        if (downloadJob != null) downloadJob.cancel(true);
        offline.cancel();
        saving = false;
        int requestGeneration = ++generation;
        listener.onLoading(true);
        String videoId = currentVideo.getVideoId();
        resolveJob = extraction.submit(() -> {
            extractionThread = Thread.currentThread();
            try {
                PlaybackSource local;
                try {
                    local = offline.load(videoId);
                } catch (Exception invalidDownload) {
                    // Incomplete/corrupt files cannot trap Retry in a permanent local failure.
                    offline.remove(videoId);
                    local = null;
                }
                boolean hasSaved = local != null;
                PlaybackSource resolved = useOffline ? local : null;
                if (resolved == null) resolved = resolver.resolve(videoId);
                PlaybackSource result = resolved;
                List<SponsorBlockClient.Segment> localSegments = result.offline
                        ? offline.loadSegments(videoId) : Collections.emptyList();
                main.post(() -> {
                    if (released || requestGeneration != generation) return;
                    source = result;
                    saved = hasSaved;
                    segments = localSegments;
                    supported = supportedVariants(result);
                    if (supported.isEmpty()) {
                        listener.onLoading(false);
                        listener.onError(R.string.playback_no_decoder);
                        return;
                    }
                    PlaybackSource.Variant variant = findVariant(quality);
                    prepare(variant, position);
                    listener.onOfflineChanged(saved, saving);
                    loadSponsors();
                });
            } catch (Exception error) {
                // Do not log exception text: extractor errors may contain signed URLs.
                Log.w(TAG, "Extraction failed: " + error.getClass().getSimpleName());
                int message = error instanceof org.schabi.newpipe.extractor.exceptions.ReCaptchaException
                        ? R.string.playback_youtube_blocked : R.string.playback_extract_failed;
                main.post(() -> {
                    if (released || requestGeneration != generation) return;
                    listener.onLoading(false);
                    listener.onError(message);
                });
            } finally { extractionThread = null; }
        });
    }

    private static List<PlaybackSource.Variant> supportedVariants(PlaybackSource result) {
        List<PlaybackSource.Variant> list = new ArrayList<>();
        for (PlaybackSource.Variant variant : result.variants) {
            try {
                String mime = MimeTypes.getVideoMediaMimeType(variant.codec);
                if (mime == null || !MediaCodecUtil.getDecoderInfos(mime, false, false).isEmpty()) {
                    list.add(variant);
                }
            } catch (Exception ignored) { /* codec cannot be used on this device */ }
        }
        return list;
    }

    private PlaybackSource.Variant findVariant(String id) {
        for (PlaybackSource.Variant variant : supported) {
            if (variant.id.equals(id)) return variant;
        }
        // Conservative TV default. Prefer AVC at <=720p; users can request higher resolutions.
        PlaybackSource.Variant best = null;
        for (PlaybackSource.Variant variant : supported) {
            if (variant.height <= 720 && (best == null || variant.height > best.height
                    || (variant.height == best.height && variant.codec != null
                    && variant.codec.startsWith("avc")))) best = variant;
        }
        return best == null ? supported.get(supported.size() - 1) : best;
    }

    private void prepare(PlaybackSource.Variant variant, long position) {
        selectedVariant = variant;
        MediaItem.Builder item = new MediaItem.Builder().setMediaId(currentVideo.getVideoId())
                .setUri(variant.videoUrl).setMimeType(variant.videoMimeType);
        List<MediaItem.SubtitleConfiguration> captions = new ArrayList<>();
        for (PlaybackSource.Subtitle subtitle : source.subtitles) {
            captions.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                    .setMimeType(subtitle.mimeType).setLanguage(subtitle.language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
        }
        item.setSubtitleConfigurations(captions);
        MediaSource videoSource = mediaFactory.createMediaSource(item.build());
        MediaSource media = videoSource;
        if (variant.audioUrl != null) {
            MediaItem audio = new MediaItem.Builder().setUri(variant.audioUrl)
                    .setMimeType(variant.audioMimeType).build();
            media = new MergingMediaSource(true, videoSource, mediaFactory.createMediaSource(audio));
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMaxVideoSize(Integer.MAX_VALUE,
                        variant.kind == PlaybackSource.Kind.PROGRESSIVE ? Integer.MAX_VALUE : 720).build());
        Log.i(TAG, "Preparing " + (source.offline ? "offline" : "online") + " media, quality=" + variant.label);
        player.setMediaSource(media, Math.max(0, position));
        player.setPlayWhenReady(desiredPlay);
        player.prepare();
    }

    @Override public void play() { desiredPlay = true; if (!released) player.play(); }
    @Override public void pause() { desiredPlay = false; if (!released) player.pause(); }
    @Override public boolean isPlaying() { return !released && player.getPlayWhenReady(); }
    @Override public boolean supportsSeek() { return !released && player.isCurrentMediaItemSeekable(); }
    @Override public long getPositionMs() { return released ? 0 : player.getCurrentPosition(); }
    @Override public long getDurationMs() { return released ? 0 : player.getDuration(); }
    @Override public void seekTo(long position) {
        if (!supportsSeek()) return;
        long duration = player.getDuration();
        player.seekTo(Math.max(0, duration == C.TIME_UNSET ? position : Math.min(position, duration)));
    }

    @Override public List<Quality> getQualities() {
        List<Quality> choices = new ArrayList<>();
        if (source == null || supported.isEmpty()) return choices;
        if (!source.offline) choices.add(new Quality("auto", view.getContext().getString(R.string.playback_quality_auto)));
        if (selectedVariant != null && selectedVariant.kind != PlaybackSource.Kind.PROGRESSIVE) {
            int groupIndex = 0;
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() == C.TRACK_TYPE_VIDEO) {
                    for (int i = 0; i < group.length; i++) {
                        if (group.isTrackSupported(i)) choices.add(new Quality("track:" + groupIndex + ":" + i,
                                group.getTrackFormat(i).height + "p"));
                    }
                }
                groupIndex++;
            }
        } else for (PlaybackSource.Variant variant : supported) {
            choices.add(new Quality(variant.id, variant.label));
        }
        return choices;
    }

    @Override public String getSelectedQuality() {
        return source != null && source.offline && selectedVariant != null ? selectedVariant.id : quality;
    }
    @Override public void selectQuality(String id) {
        if (released || source == null || supported.isEmpty()) return;
        if (selectedVariant.kind != PlaybackSource.Kind.PROGRESSIVE) {
            androidx.media3.common.TrackSelectionParameters.Builder parameters =
                    player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            if (!"auto".equals(id)) {
                String[] parts = id.split(":");
                if (parts.length != 3 || !"track".equals(parts[0])) return;
                try {
                    Tracks.Group group = player.getCurrentTracks().getGroups().get(Integer.parseInt(parts[1]));
                    int track = Integer.parseInt(parts[2]);
                    if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isTrackSupported(track)) return;
                    parameters.setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), track));
                } catch (RuntimeException invalidTrack) { return; }
            }
            player.setTrackSelectionParameters(parameters.build());
        } else {
            if (!"auto".equals(id) && supported.stream().noneMatch(v -> v.id.equals(id))) return;
            prepare(findVariant(id), player.getCurrentPosition());
        }
        quality = id;
    }

    @Override public void setSponsorBlockEnabled(boolean enabled) {
        sponsorEnabled = enabled;
        preferences.edit().putBoolean("sponsorBlock", enabled).apply();
        loadSponsors();
    }
    @Override public boolean isSponsorBlockEnabled() { return sponsorEnabled; }

    private void loadSponsors() {
        int request = ++sponsorGeneration;
        if (sponsorJob != null) sponsorJob.cancel(true);
        if (!sponsorEnabled || source == null || source.offline || source.live) return;
        segments = Collections.emptyList();
        String id = source.videoId;
        int mediaGeneration = generation;
        sponsorJob = extras.submit(() -> {
            try {
                List<SponsorBlockClient.Segment> result = sponsorBlock.fetch(id);
                main.post(() -> {
                    if (!released && request == sponsorGeneration && mediaGeneration == generation) segments = result;
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (!released && request == sponsorGeneration && mediaGeneration == generation)
                        listener.onMessage(R.string.playback_sponsor_unavailable);
                });
            }
        });
    }

    private final Runnable skipTick = new Runnable() {
        @Override public void run() {
            if (released) return;
            if (sponsorEnabled && source != null && !source.live && player.isPlaying() && supportsSeek()) {
                long position = player.getCurrentPosition();
                long target = SponsorBlockClient.skipTo(segments, position, player.getDuration());
                if (target > position) {
                    player.seekTo(target);
                    listener.onMessage(R.string.playback_sponsor_skipped);
                }
            }
            main.postDelayed(this, 500);
        }
    };

    @Override public boolean canSaveOffline() {
        return source != null && !source.offline && !source.live && selectedVariant != null
                && selectedVariant.kind == PlaybackSource.Kind.PROGRESSIVE && !saving && !saved;
    }
    @Override public void saveOffline() {
        if (!canSaveOffline()) return;
        saving = true;
        listener.onOfflineChanged(saved, true);
        int request = generation;
        PlaybackSource snapshot = source;
        PlaybackSource.Variant variant = selectedVariant;
        List<SponsorBlockClient.Segment> sponsorSnapshot = new ArrayList<>(segments);
        downloadJob = downloads.submit(() -> {
            boolean success;
            try { offline.save(snapshot, variant, sponsorSnapshot); success = true; }
            catch (Exception error) {
                Log.w(TAG, "Offline save failed: " + error.getClass().getSimpleName());
                success = false;
            }
            boolean result = success;
            main.post(() -> {
                if (released || request != generation) return;
                saving = false;
                saved = result;
                listener.onOfflineChanged(saved, false);
                listener.onMessage(result ? R.string.playback_offline_saved : R.string.playback_offline_failed);
            });
        });
    }
    @Override public void removeOffline() {
        if (released || !saved || saving || currentVideo == null) return;
        long position = player.getCurrentPosition();
        boolean wasOffline = source != null && source.offline;
        if (wasOffline) player.stop();
        int request = generation;
        String id = currentVideo.getVideoId();
        listener.onLoading(true);
        downloads.submit(() -> {
            try {
                offline.remove(id);
                main.post(() -> {
                    if (released || request != generation) return;
                    saved = false;
                    listener.onOfflineChanged(false, false);
                    if (wasOffline) resolve(position, false);
                    else listener.onLoading(false);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (released || request != generation) return;
                    listener.onLoading(false);
                    listener.onMessage(R.string.playback_offline_failed);
                });
            }
        });
    }

    private void cancelJobs() {
        if (resolveJob != null) resolveJob.cancel(true);
        resolver.cancel(extractionThread);
        if (sponsorJob != null) sponsorJob.cancel(true);
        sponsorGeneration++;
        if (downloadJob != null) downloadJob.cancel(true);
        offline.cancel();
        saving = false;
    }
    @Override public void release() {
        if (released) return;
        released = true;
        generation++;
        cancelJobs();
        main.removeCallbacksAndMessages(null);
        extraction.shutdownNow();
        extras.shutdownNow();
        downloads.shutdownNow();
        view.setPlayer(null);
        player.release();
    }

    private static boolean expiredUrl(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return status == 403 || status == 410;
            }
        }
        return false;
    }
}
