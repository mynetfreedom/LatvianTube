package lv.latviantube.ui.playback;

import java.util.List;
import lv.latviantube.data.model.Video;

/** Native playback contract. Calls and listener delivery occur on the main thread. */
public interface PlayerEngine {
    final class Quality {
        public final String id;
        public final String label;
        public Quality(String id, String label) { this.id = id; this.label = label; }
    }

    interface Listener {
        void onLoading(boolean loading);
        void onReady();
        void onError(int messageResource);
        void onMessage(int messageResource);
        void onOfflineChanged(boolean saved, boolean saving);
    }

    void open(Video video);
    void play();
    void pause();
    void release();
    boolean isPlaying();
    boolean supportsSeek();
    long getPositionMs();
    long getDurationMs();
    void seekTo(long positionMs);
    List<Quality> getQualities();
    String getSelectedQuality();
    void selectQuality(String id);
    void setSponsorBlockEnabled(boolean enabled);
    boolean isSponsorBlockEnabled();
    boolean canSaveOffline();
    void saveOffline();
    void removeOffline();
}
