package lv.latviantube.ui.browse;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lv.latviantube.App;
import lv.latviantube.R;
import lv.latviantube.data.ContentRepository;
import lv.latviantube.data.ResultCallback;
import lv.latviantube.data.model.Video;
import lv.latviantube.data.model.VideoGroup;
import lv.latviantube.data.registry.ChannelRegistry;
import lv.latviantube.ui.playback.PlaybackActivity;
import lv.latviantube.ui.presenter.SearchCardPresenter;
import lv.latviantube.ui.presenter.VideoCardPresenter;
import lv.latviantube.ui.search.SearchActivity;

/**
 * The home screen: one carousel row per curated Latvian category.
 *
 * <h2>Why BrowseSupportFragment with headers disabled</h2>
 * This uses {@link BrowseSupportFragment} purely as a row container
 * ({@code HEADERS_DISABLED}), not for its navigation drawer. The alternative,
 * {@code RowsSupportFragment}, looks like the natural fit for a headerless row
 * list, but it exposes neither {@code setTitle} nor a public {@code setAdapter} —
 * both of which this screen needs. {@code BrowseSupportFragment} exposes all
 * three APIs and supports headerless use directly.
 *
 * <h2>Why rows fill in asynchronously</h2>
 * A category row is populated by fetching each of its curated channels in turn —
 * for the "Ziņas" row that is six HTTP round trips. Blocking the first paint on
 * all eight rows would mean staring at a spinner for many seconds on TV hardware.
 * Instead every row is created immediately and filled as its data arrives, so the
 * screen is interactive straight away.
 *
 * <h2>Why a row can legitimately be empty</h2>
 * If a row finishes with zero videos that is not necessarily a bug, and the
 * header says so explicitly, because "the endpoint returned nothing" and
 * "everything was filtered out as not Latvian" are different problems that would
 * otherwise look identical on screen. {@link HeaderItem} cannot be renamed, so the
 * status is carried in its description, which Leanback renders next to the row
 * title.
 */
public final class BrowseFragment extends BrowseSupportFragment {

    public static final String TAG = "BrowseFragment";

    private static final String LOG = "BrowseFragment";

    private ArrayObjectAdapter mRowsAdapter;
    private ContentRepository mRepository;

    /** category id -> its header, so status can be attached when data arrives. */
    private final Map<String, HeaderItem> mHeaders = new LinkedHashMap<>();
    private final Map<String, ArrayObjectAdapter> mRowAdapters = new LinkedHashMap<>();

    /**
     * Marker item for the "Meklēt" card. A dedicated type rather than a
     * synthetic {@link Video}, so the click handler can never mistake it for
     * playable content.
     */
    static final class SearchEntry {
        private SearchEntry() {
        }
    }

    static final SearchEntry SEARCH_ENTRY = new SearchEntry();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepository = App.get().getRepository();

        setTitle(getString(R.string.browse_title));
        setHeadersState(HEADERS_DISABLED);

        ListRowPresenter rowPresenter = new ListRowPresenter();
        // Cards on a dark TV background read better without a drop shadow, and
        // dimming on select fights the focus zoom.
        rowPresenter.setShadowEnabled(false);
        rowPresenter.setSelectEffectEnabled(false);

        mRowsAdapter = new ArrayObjectAdapter(rowPresenter);
        setAdapter(mRowsAdapter);

        VideoCardPresenter cardPresenter = new VideoCardPresenter(requireContext());
        List<ChannelRegistry.Category> categories = mRepository.getRegistry().getCategories();

        // Search must be reachable with the D-pad alone. Leanback's header search
        // affordance is not available on BrowseSupportFragment, so it gets a real
        // row instead — which is also easier to hit with a TV remote.
        ArrayObjectAdapter searchRow = new ArrayObjectAdapter(new SearchCardPresenter(requireContext()));
        searchRow.add(SEARCH_ENTRY);
        mRowsAdapter.add(new ListRow(
                new HeaderItem(0, getString(R.string.search_row_title)), searchRow));

        long headerId = 1;
        for (ChannelRegistry.Category category : categories) {
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(cardPresenter);
            mRowAdapters.put(category.getId(), rowAdapter);

            HeaderItem header = new HeaderItem(headerId++, category.getTitleLv());
            mHeaders.put(category.getId(), header);
            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }

        setOnItemViewClickedListener(new ItemClickListener());

        for (ChannelRegistry.Category category : categories) {
            loadCategory(category);
        }
    }

    private void loadCategory(final ChannelRegistry.Category category) {
        mRepository.loadCategoryRow(category, new ResultCallback<ContentRepository.Row>() {
            @Override
            public void onResult(ContentRepository.Row result) {
                ArrayObjectAdapter adapter = mRowAdapters.get(category.getId());
                if (adapter == null) {
                    return;
                }
                adapter.clear();
                VideoGroup group = result.getGroup();
                if (!group.isEmpty()) {
                    adapter.addAll(0, new ArrayList<Object>(group.getVideos()));
                }
                setHeaderStatus(category.getId(), result.getStats());
                Log.i(LOG, "Row " + category.getId() + ": "
                        + result.getStats().retrieved + " retrieved, "
                        + result.getStats().admitted + " admitted"
                        + (result.getStats().fromCache ? " (cache)" : ""));
            }

            @Override
            public void onError(Exception error, String diagnostic) {
                Log.w(LOG, "Row failed: " + category.getId() + " — " + diagnostic, error);
                setHeaderDescription(category.getId(), diagnostic);
            }
        });
    }

    private void setHeaderStatus(String categoryId, ContentRepository.RowStats stats) {
        if (stats.admitted == 0 && stats.retrieved > 0) {
            // Distinguish "the filter rejected everything" from "nothing came back".
            setHeaderDescription(categoryId, getString(R.string.row_empty_filtered));
        } else {
            setHeaderDescription(categoryId, String.valueOf(stats.admitted));
        }
    }

    private void setHeaderDescription(String categoryId, String text) {
        HeaderItem header = mHeaders.get(categoryId);
        if (header != null) {
            header.setDescription(text);
        }
    }

    /** Opens the player for the focused card. */
    private static final class ItemClickListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder,
                                  Object item,
                                  RowPresenter.ViewHolder rowViewHolder,
                                  Row row) {
            View view = itemViewHolder.view;
            if (item == SEARCH_ENTRY) {
                view.getContext().startActivity(
                        new Intent(view.getContext(), SearchActivity.class));
                return;
            }
            if (!(item instanceof Video)) {
                return;
            }
            view.getContext().startActivity(
                    PlaybackActivity.newIntent(view.getContext(), (Video) item));
        }
    }
}
