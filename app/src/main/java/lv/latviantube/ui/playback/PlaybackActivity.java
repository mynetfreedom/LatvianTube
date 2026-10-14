package lv.latviantube.ui.playback;

import android.content.Context;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.util.UnstableApi;

import java.util.List;
import java.util.Locale;

import lv.latviantube.R;
import lv.latviantube.data.model.Video;
import lv.latviantube.ui.MotherActivity;

/** Full-screen native TV playback. Browse/search remain independent of extraction/player APIs. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class PlaybackActivity extends MotherActivity implements PlayerEngine.Listener {
    private static final String EXTRA_VIDEO_ID = "video_id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_CHANNEL = "channel";
    private final Handler main = new Handler(Looper.getMainLooper());
    private PlayerEngine engine;
    private PlayerView playerView;
    private Video video;
    private View controls, loading, errorPanel;
    private TextView status, time;
    private SeekBar seek;
    private Button play, quality, sponsor, offline;
    private boolean seeking, savedOffline, savingOffline, foreground;
    private long restorePosition;
    private boolean resumePlayback = true;

    public static Intent newIntent(Context context, Video video) {
        return new Intent(context, PlaybackActivity.class)
                .putExtra(EXTRA_VIDEO_ID, video.getVideoId())
                .putExtra(EXTRA_TITLE, video.getTitle())
                .putExtra(EXTRA_CHANNEL, video.getChannelTitle());
    }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_playback);
        controls = findViewById(R.id.playback_controls);
        loading = findViewById(R.id.playback_loading);
        errorPanel = findViewById(R.id.playback_error_panel);
        status = findViewById(R.id.playback_status);
        time = findViewById(R.id.playback_time);
        seek = findViewById(R.id.playback_seek);
        play = findViewById(R.id.playback_play);
        quality = findViewById(R.id.playback_quality);
        sponsor = findViewById(R.id.playback_sponsor);
        offline = findViewById(R.id.playback_offline);
        playerView = findViewById(R.id.playback_player);
        playerView.setUseController(false);
        engine = new ExoPlayerEngine(this, playerView, this);
        play.setOnClickListener(v -> togglePlayback());
        quality.setOnClickListener(v -> showQualityDialog());
        sponsor.setOnClickListener(v -> {
            engine.setSponsorBlockEnabled(!engine.isSponsorBlockEnabled());
            updateButtons();
        });
        sponsor.setOnLongClickListener(v -> { showLicenses(); return true; });
        offline.setOnClickListener(v -> {
            if (savedOffline) engine.removeOffline(); else engine.saveOffline();
        });
        findViewById(R.id.playback_retry).setOnClickListener(v -> {
            restorePosition = engine.getPositionMs();
            errorPanel.setVisibility(View.GONE);
            engine.open(video);
            engine.play();
        });
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                // Keyboard seeking is handled below; touch seeks remain supported.
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                // The native player reports duration through the timeline's stored tag.
                Object duration = bar.getTag();
                if (duration instanceof Long) engine.seekTo((Long) duration * bar.getProgress() / 1000);
                seeking = false;
                showControls();
            }
        });
        seek.setOnKeyListener((v, keyCode, event) -> {
            if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                engine.seekTo(engine.getPositionMs() + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000));
                showControls();
                return true;
            }
            return false;
        });
        if (state != null) {
            restorePosition = state.getLong("position");
            resumePlayback = state.getBoolean("playing", true);
        }
        openIntent();
        main.post(timelineTick);
    }

    private void openIntent() {
        video = new Video(getIntent().getStringExtra(EXTRA_VIDEO_ID),
                getIntent().getStringExtra(EXTRA_TITLE), getIntent().getStringExtra(EXTRA_CHANNEL),
                null, null, null, null, null, null);
        ((TextView) findViewById(R.id.playback_title)).setText(video.getTitle()
                + (video.getChannelTitle().isEmpty() ? "" : "  —  " + video.getChannelTitle()));
        engine.open(video);
        if (!resumePlayback) engine.pause();
        updateButtons();
        showControls();
        play.requestFocus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        restorePosition = 0;
        resumePlayback = true;
        errorPanel.setVisibility(View.GONE);
        openIntent();
        if (foreground) engine.play();
    }

    private void togglePlayback() {
        if (engine.isPlaying()) engine.pause(); else engine.play();
        updateButtons();
        showControls();
    }

    private void updateButtons() {
        play.setText(engine.isPlaying() ? R.string.playback_pause : R.string.playback_play);
        sponsor.setText(engine.isSponsorBlockEnabled() ? R.string.playback_sponsor_on : R.string.playback_sponsor_off);
        offline.setText(savingOffline ? R.string.playback_offline_saving : savedOffline
                ? R.string.playback_offline_remove : R.string.playback_offline_save);
        offline.setEnabled(!savingOffline && (savedOffline || engine.canSaveOffline()));
        quality.setEnabled(!engine.getQualities().isEmpty());
    }

    private void showQualityDialog() {
        List<PlayerEngine.Quality> choices = engine.getQualities();
        if (choices.isEmpty()) return;
        String[] labels = new String[choices.size()];
        int selected = 0;
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label;
            if (choices.get(i).id.equals(engine.getSelectedQuality())) selected = i;
        }
        main.removeCallbacks(hideControls);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.playback_quality)
                .setNeutralButton(R.string.playback_licenses, (d, which) -> showLicenses())
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    engine.selectQuality(choices.get(which).id);
                    d.dismiss();
                }).create();
        dialog.setOnDismissListener(d -> showControls());
        dialog.show();
    }

    private void showLicenses() {
        try (InputStream input = getAssets().open("licenses/NOTICE.txt")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            TextView text = new TextView(this);
            text.setText(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            text.setPadding(32, 24, 32, 24);
            text.setTextColor(0xFFFFFFFF);
            android.text.util.Linkify.addLinks(text, android.text.util.Linkify.WEB_URLS);
            text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            ScrollView scroll = new ScrollView(this);
            scroll.addView(text);
            new AlertDialog.Builder(this).setTitle(R.string.playback_licenses).setView(scroll)
                    .setPositiveButton(R.string.playback_close, null).show();
        } catch (java.io.IOException ignored) { }
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        // YouTube WebVTT can specify its own line position, overriding bottom padding.
        // Reduce the caption viewport while controls are visible, preserving those cue positions.
        controls.post(this::positionCaptions);
        main.removeCallbacks(hideControls);
        if (engine.isPlaying()) main.postDelayed(hideControls, 5000);
    }
    private final Runnable hideControls = () -> {
        if (engine != null && engine.isPlaying() && !seeking
                && errorPanel.getVisibility() != View.VISIBLE) hideControlsNow();
    };
    private void hideControlsNow() {
        controls.setVisibility(View.GONE);
        positionCaptions();
    }
    private void positionCaptions() {
        if (playerView.getSubtitleView() == null) return;
        android.view.ViewGroup.MarginLayoutParams params =
                (android.view.ViewGroup.MarginLayoutParams) playerView.getSubtitleView().getLayoutParams();
        params.bottomMargin = controls.getVisibility() == View.VISIBLE ? controls.getHeight() : 0;
        playerView.getSubtitleView().setLayoutParams(params);
    }

    private final Runnable timelineTick = new Runnable() {
        @Override public void run() {
            if (engine == null) return;
            // Kept behind the engine interface, including in the activity's UI.
            long position = engine.getPositionMs();
            long duration = engine.getDurationMs();
            if (controls.getVisibility() == View.VISIBLE) {
                time.setText(formatTime(position) + (duration > 0 ? " / " + formatTime(duration) : ""));
                seek.setEnabled(engine.supportsSeek());
                seek.setTag(duration);
                if (!seeking && duration > 0) seek.setProgress((int) (position * 1000 / duration));
                updateButtons();
            }
            main.postDelayed(this, 1000);
        }
    };
    private static String formatTime(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600,
                seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    // FragmentActivity inherits a public dispatchKeyEvent with a library-group annotation.
    // Overriding it is necessary to consume TV media keys before focused controls see them.
    @SuppressLint("RestrictedApi")
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (engine == null || event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        switch (key) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (event.getRepeatCount() == 0) {
                    if (key == KeyEvent.KEYCODE_MEDIA_PLAY) engine.play();
                    else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) engine.pause(); else togglePlayback();
                    updateButtons(); showControls();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                engine.seekTo(engine.getPositionMs() + (key == KeyEvent.KEYCODE_MEDIA_REWIND ? -10000 : 10000));
                showControls(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (controls.getVisibility() == View.VISIBLE && errorPanel.getVisibility() != View.VISIBLE) {
                    hideControlsNow(); return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (controls.getVisibility() != View.VISIBLE) {
                    togglePlayback(); play.requestFocus(); return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (controls.getVisibility() != View.VISIBLE) {
                    engine.seekTo(engine.getPositionMs() + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000));
                    showControls(); seek.requestFocus(); return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (controls.getVisibility() != View.VISIBLE) {
                    showControls(); play.requestFocus(); return true;
                }
                break;
            default: break;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onUserInteraction() { super.onUserInteraction(); if (engine != null) showControls(); }
    @Override public void onLoading(boolean busy) { loading.setVisibility(busy ? View.VISIBLE : View.GONE); }
    @Override public void onReady() {
        errorPanel.setVisibility(View.GONE);
        if (restorePosition > 0 && engine.supportsSeek()) { engine.seekTo(restorePosition); restorePosition = 0; }
        updateButtons();
    }
    @Override public void onError(int message) {
        status.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        findViewById(R.id.playback_retry).requestFocus();
        showControls();
    }
    @Override public void onMessage(int message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    @Override public void onOfflineChanged(boolean saved, boolean saving) {
        savedOffline = saved; savingOffline = saving; updateButtons();
    }
    @Override protected void onPause() {
        foreground = false;
        if (engine != null) { resumePlayback = engine.isPlaying(); engine.pause(); }
        main.removeCallbacks(hideControls);
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (engine != null && resumePlayback) engine.play();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putLong("position", engine.getPositionMs());
        out.putBoolean("playing", foreground ? engine.isPlaying() : resumePlayback);
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (engine != null) { engine.release(); engine = null; }
        super.onDestroy();
    }
}
