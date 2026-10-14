package lv.latviantube.ui.playback;

import lv.latviantube.data.model.Video;

/**
 * The playback seam.
 *
 * <p>Defined now, implemented by exactly one class
 * ({@link PlaybackActivity}'s embedded WebView player), so that a native player
 * can be added later without the browse or search screens changing. This is the
 * same shape as SmartTube's {@code PlayerEngine} interface — an engine-agnostic
 * facade that the rest of the app talks to — but with one method instead of the
 * ~40 SmartTube needs for SABR, DASH, HLS, track selection, zoom, AFR and PiP.
 *
 * <p>Implementing this natively is the main piece of future work. It requires an
 * InnerTube player-response parser, signature / n-parameter handling, adaptive
 * manifest construction and a patched media player that can speak SABR. See
 * {@code ANALYSIS.md}, "Hard parts".
 */
public interface PlayerEngine {

    /** Start playing. */
    void open(Video video);

    void play();

    void pause();

    void release();

    /** True when the engine can report a playback position. */
    boolean supportsSeek();
}
