package lv.latviantube.ui.playback;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.util.Locale;

import lv.latviantube.R;
import lv.latviantube.data.model.Video;
import lv.latviantube.ui.MotherActivity;

/**
 * Plays one video.
 *
 * <h2>What this does and does not do</h2>
 * Playback goes through the YouTube embedded player inside a {@link WebView},
 * loaded from the bundled {@code assets/player.html}. LatvianTube therefore does
 * <b>not</b> extract or decode stream URLs itself.
 *
 * <p>That is a deliberate architectural concession, not an oversight. SmartTube
 * plays video through the {@code MediaServiceCore} git submodule, which builds
 * DASH/HLS manifests, handles signature ciphers and speaks YouTube's proprietary
 * SABR streaming protocol against a patched ExoPlayer. That submodule is not part
 * of the SmartTube repository (it is empty in a clean clone and Gradle cannot even
 * configure without it). Reimplementing it is the single largest piece of work in
 * a YouTube client and is continuously broken by YouTube's own changes.
 *
 * <p>The trade-offs of the embedded player are real and worth stating plainly:
 * <ul>
 *   <li>Playback is controlled through the page, so D-pad handling is web-player
 *       behaviour rather than native TV player behaviour.</li>
 *   <li>Quality selection, SponsorBlock skipping and auto-frame-rate switching
 *       from SmartTube are <b>not</b> implemented.</li>
 *   <li>It requires the WebView to load YouTube's own player, so it is not
 *       usable offline and depends on an internet connection to youtube.com.</li>
 * </ul>
 * {@link PlayerEngine} exists so this can be replaced without touching the rest
 * of the app.
 */
public final class PlaybackActivity extends MotherActivity {

    private static final String EXTRA_VIDEO_ID = "video_id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_CHANNEL = "channel";

    /**
     * The player surface is rasterised at {@code 1 / PLAYBACK_RENDER_SCALE} of the
     * panel resolution and scaled back up. 1.5 renders a 1080p panel at 720p.
     */
    private static final float PLAYBACK_RENDER_SCALE = 2.0f;

    /**
     * The player page is served over HTTPS rather than {@code file:///}.
     *
     * <p>This is not cosmetic. YouTube's IFrame API rejects a {@code file://}
     * origin outright: the embedded player renders its own "player configuration
     * error (153)" page and never starts. Verified on an Android TV emulator —
     * see {@code ANALYSIS.md} §E8. {@link WebViewAssetLoader} maps the app's
     * assets onto {@code appassets.androidplatform.net}, which is a legitimate
     * origin the API accepts.
     */
    private static final String PLAYER_URL =
            "https://appassets.androidplatform.net/assets/player.html";

    private WebView mWebView;
    private FrameLayout mRoot;
    private WebViewAssetLoader mAssetLoader;

    public static Intent newIntent(Context context, Video video) {
        Intent intent = new Intent(context, PlaybackActivity.class);
        intent.putExtra(EXTRA_VIDEO_ID, video.getVideoId());
        intent.putExtra(EXTRA_TITLE, video.getTitle());
        intent.putExtra(EXTRA_CHANNEL, video.getChannelTitle());
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);
        mRoot = findViewById(R.id.playback_root);

        String videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String channel = getIntent().getStringExtra(EXTRA_CHANNEL);

        TextView titleView = findViewById(R.id.playback_title);
        StringBuilder overlay = new StringBuilder();
        if (!TextUtils.isEmpty(title)) {
            overlay.append(title);
        }
        if (!TextUtils.isEmpty(channel)) {
            if (overlay.length() > 0) {
                overlay.append("  —  ");
            }
            overlay.append(channel);
        }
        titleView.setText(overlay.toString());

        // Auto-hide the title like a TV on-screen display. Besides being the
        // conventional behaviour, a permanently visible translucent view stacked
        // over the WebView forces the window to keep compositing a blended layer
        // on top of the video. On the weak GPU in a budget TV that is measurable
        // work taken away from playback (see ANALYSIS.md §E12).
        titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 4000);

        if (TextUtils.isEmpty(videoId)) {
            titleView.setText(R.string.playback_missing_id);
            return;
        }

        mAssetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        mWebView = buildWebView();

        // Render the player at a fraction of the panel resolution and let the
        // display compositor scale it up to fill the screen.
        //
        // Measured on a Grundig Android TV: with the WebView laid out at the full
        // 1920x1080, 89% of frames missed their deadline and the GPU alone was
        // taking 22 ms per frame (90th percentile) — this SoC cannot rasterise
        // and composite 1080p Chromium content in real time. Capping playback
        // quality did not help (89% vs 86% janky), which is what proved the cost
        // is the surface size rather than the video decode.
        //
        // Rasterising at 2/3 linear resolution is ~55% fewer pixels. The GPU then
        // only has to scale a finished texture, which is cheap, and on a TV at
        // normal viewing distance the softness is far less objectionable than the
        // stutter. See ANALYSIS.md §E12.
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int renderWidth = Math.round(metrics.widthPixels / PLAYBACK_RENDER_SCALE);
        int renderHeight = Math.round(metrics.heightPixels / PLAYBACK_RENDER_SCALE);
        mWebView.setLayoutParams(new FrameLayout.LayoutParams(renderWidth, renderHeight));
        mWebView.setPivotX(0f);
        mWebView.setPivotY(0f);
        mWebView.setScaleX(PLAYBACK_RENDER_SCALE);
        mWebView.setScaleY(PLAYBACK_RENDER_SCALE);

        mRoot.addView(mWebView);

        String url = String.format(Locale.US, "%s?videoId=%s", PLAYER_URL, videoId);
        mWebView.loadUrl(url);
    }

    private WebView buildWebView() {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                // Serve /assets/* from the APK over the https origin above.
                return mAssetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(0xFF000000);
        webView.setFocusable(true);
        webView.requestFocus();
        return webView;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) {
            // Stop audio when the user leaves; the embedded player keeps playing
            // otherwise.
            mWebView.onPause();
            mWebView.evaluateJavascript("window.latviantubePause && window.latviantubePause();", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mRoot.removeView(mWebView);
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
