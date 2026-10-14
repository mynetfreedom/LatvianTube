package lv.latviantube.data.model;

/**
 * A curated Latvian YouTube channel and the row it belongs to.
 */
public final class Channel {

    private final String mId;
    private final String mTitle;
    private final String mHandle;
    private final String mCategoryId;
    private final String mCategoryTitleLv;

    public Channel(String id, String title, String handle, String categoryId, String categoryTitleLv) {
        mId = id;
        mTitle = title;
        mHandle = handle == null ? "" : handle;
        mCategoryId = categoryId;
        mCategoryTitleLv = categoryTitleLv;
    }

    public String getId() {
        return mId;
    }

    public String getTitle() {
        return mTitle;
    }

    public String getHandle() {
        return mHandle;
    }

    public String getCategoryId() {
        return mCategoryId;
    }

    public String getCategoryTitleLv() {
        return mCategoryTitleLv;
    }

    @Override
    public String toString() {
        return mTitle + " (" + mId + ")";
    }
}
