package lv.latviantube.data.playback;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** Blocking NewPipe transport. Never delivers network/parsing work to the UI thread. */
public final class ExtractorDownloader extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    private final OkHttpClient http;
    private final Map<Thread, Call> activeCalls = new ConcurrentHashMap<>();

    public ExtractorDownloader() {
        this(new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).build());
    }

    public ExtractorDownloader(OkHttpClient http) {
        this.http = http;
    }

    public void cancel(Thread worker) {
        if (worker == null) return;
        Call call = activeCalls.get(worker);
        if (call != null) call.cancel();
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(request.url())
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "lv-LV,lv;q=0.9");
        for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            builder.removeHeader(header.getKey());
            for (String value : header.getValue()) builder.addHeader(header.getKey(), value);
        }
        byte[] payload = request.dataToSend();
        RequestBody body = payload == null ? null : RequestBody.create(payload, null);
        if (body == null && ("POST".equals(request.httpMethod())
                || "PUT".equals(request.httpMethod()) || "PATCH".equals(request.httpMethod()))) {
            body = RequestBody.create(new byte[0], null);
        }
        Call call = http.newCall(builder.method(request.httpMethod(), body).build());
        Thread worker = Thread.currentThread();
        activeCalls.put(worker, call);
        try {
            // Close the race between registration and cancellation of the worker.
            if (worker.isInterrupted()) {
                call.cancel();
                throw new InterruptedIOException();
            }
            try (okhttp3.Response response = call.execute()) {
                if (response.code() == 429) {
                    throw new ReCaptchaException("YouTube request limit", request.url());
                }
                ResponseBody responseBody = response.body();
                return new Response(response.code(), response.message(),
                        response.headers().toMultimap(), responseBody == null ? "" : responseBody.string(),
                        response.request().url().toString());
            }
        } finally {
            activeCalls.remove(worker, call);
        }
    }
}
