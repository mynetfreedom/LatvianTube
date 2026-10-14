package lv.latviantube.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import java.util.Locale;

/**
 * Base activity holding the TV chrome decisions that must be identical on every
 * screen.
 *
 * <p>Mirrors SmartTube's {@code MotherActivity}, which exists because cheap TV
 * boxes are inconsistent about orientation, overscan and focus. The specific
 * workaround relied on here is the {@code configChanges} declaration in the
 * manifest plus {@code screenOrientation="unspecified"}: requesting a fixed
 * orientation from a translucent activity crashes on Android 8
 * ("Only fullscreen opaque activities can request orientation"). Keeping every
 * activity fullscreen and letting the system decide avoids the whole class of
 * problem rather than special-casing affected devices by name — which is what
 * SmartTube has to do, down to a device-model blacklist.
 *
 * <p>The theme comes from the manifest ({@code AppTheme.Leanback}), so this class
 * holds no {@code setTheme} call.
 */
public abstract class MotherActivity extends FragmentActivity {

    /**
     * Pins the UI to Latvian regardless of the TV's system locale.
     *
     * <p>This is not only about the app's own strings, which are Latvian in the
     * base {@code values/} folder. It is about the Leanback library's strings:
     * they resolve against the system locale, so on an English-locale TV the
     * search bar rendered "Speak to search" in an otherwise entirely Latvian UI.
     * Leanback does ship {@code values-lv}, so pinning the locale here makes its
     * strings Latvian too. Found by running the app on an emulator — see
     * {@code ANALYSIS.md} §E9.
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        Locale latvian = new Locale("lv", "LV");
        Locale.setDefault(latvian);
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.setLocale(latvian);
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
