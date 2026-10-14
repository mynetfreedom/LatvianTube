package lv.latviantube.ui.search;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import lv.latviantube.ui.MotherActivity;

/**
 * Hosts {@link SearchFragment}. A separate activity rather than a browse-screen
 * mode so search survives a configuration change independently of the home feed,
 * and so it can later be given its own launcher intent-filter for remote-control
 * search keys without restructuring the browse screen.
 */
public final class SearchActivity extends MotherActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            Fragment fragment = new SearchFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, fragment, "SearchFragment")
                    .commit();
        }
    }
}
