package lv.latviantube.data.innertube;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records every raw InnerTube exchange to disk.
 *
 * <p>Implements the "cache and record everything" rule: the raw response, its
 * timestamp, endpoint, HTTP status and byte count. This matters specifically
 * because the failure modes here are indistinguishable from success at the call
 * site — a 400 from {@code FEtrending}, a consent wall served with HTTP 200, or
 * an empty result set all look like "no videos". With the raw bodies on disk, a
 * future regression can be diagnosed by reading what the server actually said.
 *
 * <p>Files are capped and rotated so the recorder cannot fill a TV box's storage.
 */
public final class RawRecorder {

    private static final String TAG = "RawRecorder";
    private static final String DIR = "innertube-log";
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    private final File mDir;
    private boolean mEnabled;

    public RawRecorder(Context context) {
        mDir = new File(context.getCacheDir(), DIR);
        if (!mDir.exists() && !mDir.mkdirs()) {
            Log.w(TAG, "Could not create raw log dir: " + mDir);
        }
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    /** Record one exchange. Never throws: logging must not break playback. */
    public synchronized void record(String endpoint, String requestSummary, int status,
                                    long bytes, long millis, String body) {
        if (!mEnabled) {
            return;
        }
        try {
            trimIfOversized();
            File out = new File(mDir, endpoint + "-" + System.currentTimeMillis() + ".json");
            FileWriter w = new FileWriter(out);
            try {
                w.write("{\n");
                w.write("  \"at\": \"" + STAMP.format(new Date()) + "\",\n");
                w.write("  \"endpoint\": \"" + escape(endpoint) + "\",\n");
                w.write("  \"request\": \"" + escape(requestSummary) + "\",\n");
                w.write("  \"status\": " + status + ",\n");
                w.write("  \"bytes\": " + bytes + ",\n");
                w.write("  \"ms\": " + millis + ",\n");
                w.write("  \"body\": \"" + escape(truncate(body, 200_000)) + "\"\n");
                w.write("}\n");
            } finally {
                w.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not record exchange", e);
        }
    }

    private void trimIfOversized() {
        File[] files = mDir.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (total <= MAX_BYTES) {
            return;
        }
        // Oldest first.
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_BYTES) {
                break;
            }
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[truncated]";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
