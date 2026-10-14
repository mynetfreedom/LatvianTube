package lv.latviantube.ui.search;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.leanback.app.SearchSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;

import java.util.ArrayList;
import java.util.List;

import lv.latviantube.App;
import lv.latviantube.R;
import lv.latviantube.data.ContentRepository;
import lv.latviantube.data.ResultCallback;
import lv.latviantube.data.model.Video;
import lv.latviantube.ui.playback.PlaybackActivity;
import lv.latviantube.ui.presenter.VideoCardPresenter;

/**
 * Leanback search over Latvian video.
 *
 * <p>Search is a first-class content source here rather than a convenience.
 * Because the trending chart is unavailable (HTTP 400) and the curated registry
 * is finite, search is what lets a user reach Latvian material outside the
 * registry's 17 channels. The query itself is not restricted — instead
 * {@code hl=lv}/{@code gl=LV} bias YouTube toward Latvian results upstream, and
 * every returned video is gated afterwards, so typing "dziesmas" surfaces Latvian
 * songs while typing an English phrase simply yields little.
 *
 * <p>An empty result is reported distinctly from a failed request.
 */
public final class SearchFragment extends SearchSupportFragment implements SearchSupportFragment.SearchResultProvider {

    private static final String LOG = "SearchFragment";

    private ArrayObjectAdapter mRowsAdapter;
    private ContentRepository mRepository;
    private VideoCardPresenter mCardPresenter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepository = App.get().getRepository();
        mCardPresenter = new VideoCardPresenter(requireContext());

        mRowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setSearchResultProvider(this);
        setOnItemViewClickedListener(new ItemClickListener());
    }

    @Override
    public ObjectAdapter getResultsAdapter() {
        return mRowsAdapter;
    }

    @Override
    public boolean onQueryTextChange(String newQuery) {
        // Live search on every keystroke would hammer an unquota'd endpoint;
        // wait for submit.
        return true;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        if (TextUtils.isEmpty(query)) {
            return false;
        }
        runSearch(query.trim());
        return true;
    }

    private void runSearch(final String query) {
        Log.i(LOG, "Search: " + query);
        mRepository.search(query, new ResultCallback<List<Video>>() {
            @Override
            public void onResult(List<Video> result) {
                mRowsAdapter.clear();
                if (result.isEmpty()) {
                    mRowsAdapter.add(new ListRow(
                            new HeaderItem(getString(R.string.search_no_results)),
                            new ArrayObjectAdapter(mCardPresenter)));
                    return;
                }
                HeaderItem header = new HeaderItem(
                        getString(R.string.search_results_header, result.size()));
                ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);
                rowAdapter.addAll(0, new ArrayList<Object>(result));
                mRowsAdapter.add(new ListRow(header, rowAdapter));
            }

            @Override
            public void onError(Exception error, String diagnostic) {
                Log.w(LOG, "Search failed: " + diagnostic, error);
                mRowsAdapter.clear();
                mRowsAdapter.add(new ListRow(
                        new HeaderItem(diagnostic),
                        new ArrayObjectAdapter(mCardPresenter)));
            }
        });
    }

    private static final class ItemClickListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (!(item instanceof Video)) {
                return;
            }
            View view = itemViewHolder.view;
            view.getContext().startActivity(
                    PlaybackActivity.newIntent(view.getContext(), (Video) item));
        }
    }
}
