package lv.latviantube.ui.presenter;

import android.content.Context;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import androidx.leanback.widget.ImageCardView;
import androidx.leanback.widget.Presenter;

import lv.latviantube.R;

/**
 * Renders the single "Meklēt" (Search) card that sits at the top of the home
 * screen.
 *
 * <p>This exists because search had no entry point. The obvious Leanback
 * affordance, {@code BrowseSupportFragment.setOnSearchClickedListener}, is not
 * available — it belongs to the older framework {@code BrowseFragment} and is
 * absent from the current androidx class (verified against the androidx source;
 * see {@code ANALYSIS.md} §E9). With no icon in the header and no sidebar, there
 * was no way to reach {@code SearchActivity} from the UI at all, which testing on
 * the emulator made obvious: the activity was correct but unreachable.
 *
 * <p>A first-class row is a better TV answer than a header icon anyway, because
 * it is reachable with nothing but the D-pad.
 */
public final class SearchCardPresenter extends Presenter {

    private static final int CARD_WIDTH_DP = 313;
    private static final int CARD_HEIGHT_DP = 176;

    private final int mCardWidth;
    private final int mCardHeight;

    public SearchCardPresenter(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mCardWidth = Math.round(CARD_WIDTH_DP * density);
        mCardHeight = Math.round(CARD_HEIGHT_DP * density);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        ImageCardView card = new ImageCardView(parent.getContext());
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setMainImageDimensions(mCardWidth, mCardHeight);
        card.setMainImage(ContextCompat.getDrawable(parent.getContext(), R.drawable.ic_search));
        card.setMainImageScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        return new ViewHolder(card);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        ImageCardView card = (ImageCardView) viewHolder.view;
        card.setTitleText(card.getContext().getString(R.string.search_row_title));
        card.setContentText(card.getContext().getString(R.string.search_hint));
        card.setMainImage(ContextCompat.getDrawable(card.getContext(), R.drawable.ic_search));
        card.setMainImageScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        ImageCardView card = (ImageCardView) viewHolder.view;
        card.setBadgeImage(null);
    }
}
