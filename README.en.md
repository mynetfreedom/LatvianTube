# LatvianTube — English summary

**A free, open-source Android TV client focused on Latvian-language YouTube videos.**

**[Download LatvianTube v1.1 APK](https://github.com/mynetfreedom/LatvianTube/releases/download/v1.1/LatvianTube_v.1.1.apk)** ·
[Release notes and source](https://github.com/mynetfreedom/LatvianTube/releases/tag/v1.1) ·
[Full documentation in Latvian](README.md)

Inspired by [SmartTube](https://github.com/yuliskov/SmartTube), LatvianTube combines
17 curated Latvian channels across eight categories with Latvian-scoped search
and a scored language filter. It requires no account or Google Play Services and
runs on Android TV 5.0+ (API 21).

Version **1.1** replaces embedded WebView playback with **Media3 / ExoPlayer** and
**NewPipeExtractor v0.26.5**. It adds quality selection, native remote controls,
Latvian captions when available, optional SponsorBlock, and explicit offline
saves of on-demand direct streams. SponsorBlock is off by default. Offline saves
include audio/captions, are limited to 2 GiB per item, and reopen from cached cards.

To build, install and launch on your TV, enable network ADB debugging and use:

```bash
./install-tv.sh YOUR_TV_IP:5555
# Skip building when the APK already exists
./install-tv.sh YOUR_TV_IP:5555 --no-build
```

Accept the TV authorization prompt. The build needs JDK 17 and Android SDK 34;
the APK is named `LatvianTube_v.1.1.apk`. The published APK uses this project's
existing debug signing key. Full installation, pairing, build and testing steps
are in the [Latvian README](README.md).

The filter infers language from metadata; spoken audio is not verified. YouTube
changes may require extractor updates. Live/HLS/DASH offline saving, SmartTube
SABR and automatic frame-rate switching are not implemented. Native playback and
offline reopening were verified on an Android TV emulator; the native build was
also installed and launched on a physical TV. Physical-TV performance remains
unmeasured. [Investigation and evidence](ANALYSIS.md).

Original LatvianTube source remains MIT licensed. **APKs containing
NewPipeExtractor are distributed under GPL-3.0-or-later terms.** The release
provides app source and the pinned extractor source. SponsorBlock data is
CC BY-NC-SA 4.0. See [THIRD_PARTY.md](THIRD_PARTY.md) for notices and source links.
