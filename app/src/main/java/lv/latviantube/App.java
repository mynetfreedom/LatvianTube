package lv.latviantube;

import android.app.Application;

import lv.latviantube.data.ContentRepository;
import lv.latviantube.data.registry.ChannelRegistry;

/**
 * Process-wide singletons.
 *
 * <p>Deliberately minimal. SmartTube's {@code MainApplication} also builds a
 * {@code ViewManager} registry, installs Conscrypt, wipes the HTTP keep-alive
 * setting and registers a crash handler — all of which exist to work around
 * minSdk 17 hardware (Android 4.3 TVs). LatvianTube targets API 21+ and does not
 * need any of it.
 */
public final class App extends Application {

    private static App sInstance;

    private ContentRepository mRepository;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        ChannelRegistry registry = ChannelRegistry.load(this);
        mRepository = new ContentRepository(this, registry);

        // Raw response capture is OFF, including in debug builds.
        //
        // It used to be enabled whenever BuildConfig.DEBUG was true, which meant
        // the debug APK installed on a real TV wrote every InnerTube response
        // body — hundreds of kilobytes each, 17 of them just to paint the home
        // screen — to the device's flash storage. On a TV box with slow eMMC
        // that is a measurable source of stutter, and it is the kind of
        // diagnostic that should be switched on deliberately, not inherited by
        // whoever installs a debug build. Turn it on here when you actually need
        // it:
        //
        //     mRepository.getRecorder().setEnabled(true);
    }

    public static App get() {
        return sInstance;
    }

    public ContentRepository getRepository() {
        return mRepository;
    }

    @Override
    public void onTerminate() {
        if (mRepository != null) {
            mRepository.shutdown();
        }
        super.onTerminate();
    }
}
