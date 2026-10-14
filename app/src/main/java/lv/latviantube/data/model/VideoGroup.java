package lv.latviantube.data.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named, ordered group of videos — one carousel row on the home screen.
 *
 * <p>Equivalent to SmartTube's {@code MediaGroup}, minus the integer
 * {@code TYPE_*} taxonomy. LatvianTube identifies rows by a string id because
 * its row set is small and data-driven from {@code assets/channels_lv.json},
 * whereas SmartTube needs stable ints for its sidebar-order preferences and
 * broadcast deep links.
 */
public final class VideoGroup {

    private final String mId;
    private final String mTitle;
    private final List<Video> mVideos = new ArrayList<>();

    public VideoGroup(String id, String title) {
        mId = id;
        mTitle = title;
    }

    public String getId() {
        return mId;
    }

    public String getTitle() {
        return mTitle;
    }

    public List<Video> getVideos() {
        return Collections.unmodifiableList(mVideos);
    }

    public void add(Video video) {
        mVideos.add(video);
    }

    public void addAll(List<Video> videos) {
        mVideos.addAll(videos);
    }

    public boolean isEmpty() {
        return mVideos.isEmpty();
    }

    public int size() {
        return mVideos.size();
    }
}
