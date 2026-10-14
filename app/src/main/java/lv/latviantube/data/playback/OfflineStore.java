package lv.latviantube.data.playback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** App-private, explicit downloads. A saved item becomes visible only after every file is complete. */
public final class OfflineStore {
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long FREE_SPACE_RESERVE = 64L * 1024 * 1024;
    private final File root;
    private final OkHttpClient http;
    private volatile Call download;

    public OfflineStore(File filesDir, OkHttpClient http) {
        root = new File(filesDir, "offline");
        this.http = http;
    }

    public void cancel() {
        Call active = download;
        if (active != null) active.cancel();
    }

    public PlaybackSource load(String videoId) throws Exception {
        File directory = directory(videoId);
        File metadata = new File(directory, "metadata.json");
        if (!metadata.isFile()) return null;
        JSONObject json = new JSONObject(new String(Files.readAllBytes(metadata.toPath()),
                StandardCharsets.UTF_8));
        if (json.optInt("version") != 1 || !videoId.equals(json.optString("videoId"))) {
            throw new IOException("Invalid offline metadata");
        }
        File video = new File(directory, "video");
        checkFile(video, json.getLong("videoBytes"));
        File audio = new File(directory, "audio");
        if (json.has("audioBytes")) checkFile(audio, json.getLong("audioBytes"));
        List<PlaybackSource.Subtitle> subtitles = new ArrayList<>();
        File caption = new File(directory, "captions.vtt");
        if (json.has("captionBytes")) {
            checkFile(caption, json.getLong("captionBytes"));
            subtitles.add(new PlaybackSource.Subtitle(caption.toURI().toString(), "text/vtt", "lv"));
        }
        PlaybackSource.Variant variant = new PlaybackSource.Variant("offline", json.getString("label"),
                video.toURI().toString(), json.has("audioBytes") ? audio.toURI().toString() : null,
                json.getString("videoMime"), json.optString("audioMime", null),
                json.optString("codec", null), json.optInt("height"), PlaybackSource.Kind.PROGRESSIVE);
        return new PlaybackSource(videoId, Collections.singletonList(variant), subtitles, false, true);
    }

    public List<SponsorBlockClient.Segment> loadSegments(String videoId) throws Exception {
        File metadata = new File(directory(videoId), "metadata.json");
        if (!metadata.isFile()) return new ArrayList<>();
        JSONObject json = new JSONObject(new String(Files.readAllBytes(metadata.toPath()),
                StandardCharsets.UTF_8));
        return SponsorBlockClient.parse(json.optJSONArray("segments"));
    }

    public void save(PlaybackSource source, PlaybackSource.Variant variant,
                     List<SponsorBlockClient.Segment> segments) throws Exception {
        if (source.live || source.offline || variant.kind != PlaybackSource.Kind.PROGRESSIVE) {
            throw new IOException("Only on-demand progressive media can be saved");
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create offline storage");
        File[] abandoned = root.listFiles(file -> file.getName().startsWith(".pending-")
                && file.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L);
        if (abandoned != null) for (File file : abandoned) delete(file);
        File destination = directory(source.videoId);
        if (destination.exists()) throw new IOException("Video already saved");
        File staging = new File(root, ".pending-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Cannot create download directory");
        try {
            long videoBytes = copy(variant.videoUrl, new File(staging, "video"), MAX_BYTES);
            JSONObject metadata = new JSONObject().put("version", 1).put("videoId", source.videoId)
                    .put("savedAt", System.currentTimeMillis()).put("label", variant.label)
                    .put("height", variant.height).put("videoMime", variant.videoMimeType)
                    .put("codec", variant.codec).put("videoBytes", videoBytes);
            long totalBytes = videoBytes;
            if (variant.audioUrl != null) {
                long audioBytes = copy(variant.audioUrl, new File(staging, "audio"), MAX_BYTES - totalBytes);
                metadata.put("audioBytes", audioBytes).put("audioMime", variant.audioMimeType);
                totalBytes += audioBytes;
            }
            if (!source.subtitles.isEmpty()) {
                long captionBytes = copy(source.subtitles.get(0).url,
                        new File(staging, "captions.vtt"), Math.min(8 * 1024 * 1024, MAX_BYTES - totalBytes));
                metadata.put("captionBytes", captionBytes);
            }
            JSONArray savedSegments = new JSONArray();
            for (SponsorBlockClient.Segment segment : segments) {
                savedSegments.put(new JSONObject().put("category", "sponsor").put("actionType", "skip")
                        .put("segment", new JSONArray().put(segment.startMs / 1000.0)
                                .put(segment.endMs / 1000.0)));
            }
            metadata.put("segments", savedSegments);
            try (FileOutputStream output = new FileOutputStream(new File(staging, "metadata.json"))) {
                output.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (!staging.renameTo(destination)) throw new IOException("Cannot commit offline video");
        } finally {
            delete(staging);
        }
    }

    public void remove(String videoId) throws IOException { delete(directory(videoId)); }

    private long copy(String url, File target, long budget) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        Call call = http.newCall(new Request.Builder().url(url)
                .header("Range", "bytes=0-")
                .header("User-Agent", ExtractorDownloader.USER_AGENT).build());
        download = call;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Download HTTP " + response.code());
                }
                long expected = response.body().contentLength();
                // Some servers cap an open-ended Range response. Never publish only its first chunk.
                String contentRange = response.header("Content-Range");
                if (response.code() == 206) {
                    if (contentRange == null || !contentRange.matches("bytes 0-[0-9]+/[0-9]+")) {
                        throw new IOException("Incomplete download range");
                    }
                    long total = Long.parseLong(contentRange.substring(contentRange.indexOf('/') + 1));
                    long last = Long.parseLong(contentRange.substring(8, contentRange.indexOf('/')));
                    if (last + 1 != total || (expected >= 0 && expected != total)) {
                        throw new IOException("Incomplete download range");
                    }
                    expected = total;
                }
                if (budget <= 0 || expected > budget || (expected >= 0
                        && expected > root.getUsableSpace() - FREE_SPACE_RESERVE)) {
                    throw new IOException("Not enough storage or video exceeds 2 GiB");
                }
                long bytes = 0;
                try (InputStream input = response.body().byteStream();
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                        bytes += count;
                        if (bytes > budget || root.getUsableSpace() < FREE_SPACE_RESERVE) {
                            throw new IOException("Offline storage limit exceeded");
                        }
                        output.write(buffer, 0, count);
                    }
                    if (bytes == 0 || (expected >= 0 && expected != bytes)) {
                        throw new IOException("Incomplete download");
                    }
                    output.getFD().sync();
                }
                return bytes;
            }
        } finally { download = null; }
    }

    private File directory(String videoId) throws IOException {
        if (!StreamResolver.validVideoId(videoId)) throw new IOException("Invalid video ID");
        return new File(root, videoId);
    }

    private static void checkFile(File file, long expected) throws IOException {
        if (expected <= 0 || !file.isFile() || file.length() != expected) {
            throw new IOException("Incomplete offline video");
        }
    }

    private static void delete(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (!file.delete()) throw new IOException("Cannot remove offline file");
    }
}
