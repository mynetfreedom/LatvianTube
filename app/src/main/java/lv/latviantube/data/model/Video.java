package lv.latviantube.data.model;

import androidx.annotation.Nullable;

import lv.latviantube.data.filter.FilterVerdict;

/**
 * One YouTube video, plus the Latvian-language verdict that admitted it.
 *
 * <p>The verdict travels with the video rather than being discarded after
 * filtering. That is deliberate: it lets the UI show why a title is on screen,
 * and it makes a bad filter decision debuggable on the device instead of only
 * reproducible in the Node harness.
 */
public final class Video {

    private final String mVideoId;
    private final String mTitle;
    private final String mChannelTitle;
    private final String mChannelId;
    private final String mDescription;
    private final String mThumbnailUrl;
    private final String mDurationText;
    private final String mViewCountText;
    private final String mPublishedTimeText;

    private FilterVerdict mVerdict;

    public Video(String videoId,
                 String title,
                 String channelTitle,
                 @Nullable String channelId,
                 @Nullable String description,
                 @Nullable String thumbnailUrl,
                 @Nullable String durationText,
                 @Nullable String viewCountText,
                 @Nullable String publishedTimeText) {
        mVideoId = videoId;
        mTitle = title == null ? "" : title;
        mChannelTitle = channelTitle == null ? "" : channelTitle;
        mChannelId = channelId;
        mDescription = description == null ? "" : description;
        mThumbnailUrl = thumbnailUrl;
        mDurationText = durationText;
        mViewCountText = viewCountText;
        mPublishedTimeText = publishedTimeText;
    }

    public String getVideoId() {
        return mVideoId;
    }

    public String getTitle() {
        return mTitle;
    }

    public String getChannelTitle() {
        return mChannelTitle;
    }

    @Nullable
    public String getChannelId() {
        return mChannelId;
    }

    public String getDescription() {
        return mDescription;
    }

    @Nullable
    public String getThumbnailUrl() {
        return mThumbnailUrl;
    }

    @Nullable
    public String getDurationText() {
        return mDurationText;
    }

    @Nullable
    public String getViewCountText() {
        return mViewCountText;
    }

    @Nullable
    public String getPublishedTimeText() {
        return mPublishedTimeText;
    }

    public FilterVerdict getVerdict() {
        return mVerdict;
    }

    public void setVerdict(FilterVerdict verdict) {
        mVerdict = verdict;
    }

    /** The blob the language gate scores: title, description and channel name. */
    public String scoringText() {
        return mTitle + " \n " + mDescription + " \n " + mChannelTitle;
    }

    /** A stable identity for ExoPlayer/MediaSession style consumers. */
    public String getMediaId() {
        return mVideoId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Video)) {
            return false;
        }
        return mVideoId.equals(((Video) o).mVideoId);
    }

    @Override
    public int hashCode() {
        return mVideoId.hashCode();
    }

    @Override
    public String toString() {
        return mTitle + " [" + mVideoId + "]";
    }
}
