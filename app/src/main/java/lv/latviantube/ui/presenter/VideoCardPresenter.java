package lv.latviantube.ui.presenter;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.leanback.widget.ImageCardView;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import lv.latviantube.data.model.Video;

/**
 * Renders one video as a TV card.
 *
 * <p>Two details that matter on a TV specifically:
 *
 * <ul>
 *   <li>The card is a fixed size in dp. Leanback's focus zoom grows it, so the
 *       layout must reserve that room or adjacent rows clip.</li>
 *   <li>Images are requested at exactly the card size. TV boxes have little
 *       memory and decoding a full-resolution thumbnail per card is the usual
 *       cause of scroll jank on cheap hardware.</li>
 * </ul>
 */
public final class VideoCardPresenter extends Presenter {

    private static final int CARD_WIDTH_DP = 313;
    private static final int CARD_HEIGHT_DP = 176;

    private final int mCardWidth;
    private final int mCardHeight;

    public VideoCardPresenter(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mCardWidth = Math.round(CARD_WIDTH_DP * density);
        mCardHeight = Math.round(CARD_HEIGHT_DP * density);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();
        ImageCardView card = new ImageCardView(context);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setMainImageDimensions(mCardWidth, mCardHeight);
        return new ViewHolder(card);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        if (!(item instanceof Video)) {
            return;
        }
        Video video = (Video) item;
        ImageCardView card = (ImageCardView) viewHolder.view;

        card.setTitleText(video.getTitle());
        card.setContentText(buildSubtitle(video));

        Context context = card.getContext();
        // dontAnimate(): Glide cross-fades every load by default, which runs an
        // animation per card. On a TV scrolling ~240 cards that is continuous
        // animation work competing with the scroll itself.
        //
        // DiskCacheStrategy.ALL: RESOURCE only cached the full-size original, so
        // every re-bind after a card scrolled off-screen re-decoded from the
        // original. ALL also caches the downscaled bitmap, making a re-bind a
        // cheap memory-cache hit.
        RequestOptions options = new RequestOptions()
                .centerCrop()
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(mCardWidth, mCardHeight);

        Glide.with(context)
                .load(thumbnailFor(video))
                .apply(options)
                .into(card.getMainImageView());
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        ImageCardView card = (ImageCardView) viewHolder.view;
        // Release the bitmap promptly: a TV box scrolling ~17 rows will
        // otherwise hold every decoded thumbnail at once.
        Glide.with(card.getContext()).clear(card.getMainImageView());
        card.setMainImage(null);
        card.setBadgeImage(null);
    }

    /**
     * Falls back to YouTube's deterministic thumbnail URL so a card is never
     * blank just because the response shape omitted an image.
     */
    private static String thumbnailFor(Video video) {
        if (!TextUtils.isEmpty(video.getThumbnailUrl())) {
            return video.getThumbnailUrl();
        }
        return "https://i.ytimg.com/vi/" + video.getVideoId() + "/hq720.jpg";
    }

    private static String buildSubtitle(Video video) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(video.getChannelTitle())) {
            sb.append(video.getChannelTitle());
        }
        if (!TextUtils.isEmpty(video.getViewCountText())) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(video.getViewCountText());
        }
        if (!TextUtils.isEmpty(video.getPublishedTimeText())) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(video.getPublishedTimeText());
        }
        return sb.toString();
    }
}
