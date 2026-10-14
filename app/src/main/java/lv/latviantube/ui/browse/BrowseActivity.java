package lv.latviantube.ui.browse;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import lv.latviantube.ui.MotherActivity;

/**
 * The single screen of LatvianTube: a Leanback browse fragment with one row per
 * curated Latvian category.
 *
 * <p>There is no sidebar. SmartTube's sidebar exists because it has ~18 sections
 * (subscriptions, history, playlists, queue, settings, notifications…), most of
 * which require a signed-in account. LatvianTube is deliberately anonymous and
 * stateless — it has no account, so it has nothing to put in a sidebar. Category
 * rows are the whole navigation model, which is also why the category list is
 * data-driven from {@code channels_lv.json}: adding a row means editing JSON,
 * not code.
 */
public final class BrowseActivity extends MotherActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            Fragment fragment = new BrowseFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, fragment, BrowseFragment.TAG)
                    .commit();
        }
    }
}
