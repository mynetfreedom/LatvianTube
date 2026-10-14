# Playback dependencies and distribution

LatvianTube's original source is MIT licensed (see `LICENSE`). The native player
uses **NewPipeExtractor v0.26.5**, by Team NewPipe and its contributors, under
**GPL-3.0-or-later**.
APKs containing it must be distributed under GPL-3.0-or-later terms, with the
corresponding source for the app and its dependencies, build instructions, and
license notices. The original MIT grant remains available for LatvianTube source;
it does not make the combined APK MIT-only.

- Extractor source: https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5
- Extractor license: https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/LICENSE
- Full GPL text is bundled at `app/src/main/assets/licenses/GPL-3.0.txt`.
- Playback/build instructions: `README.md` (Latvian); `README.en.md` is a short English summary.
- Releases include the pinned extractor source archive alongside the APK; app source is available from the release tag.

Media3 1.4.1 / ExoPlayer and AndroidX are from the Android Open Source Project,
Apache-2.0: https://github.com/androidx/media/tree/1.4.1 . OkHttp 4.12.0 is by Square,
Apache-2.0: https://github.com/square/okhttp/tree/parent-4.12.0 . NewPipe's runtime
dependencies include Rhino 1.8.1 (MPL-2.0), jsoup 1.22.2 (MIT), TeamNewPipe/nanojson
(MIT), protobuf-javalite 4.35.1 (BSD-3-Clause), and jsr305 3.0.2 (BSD-3-Clause).
Their upstream notices and licenses continue to apply.

SponsorBlock segment data is provided by Ajay Ramachandran and contributors:
https://sponsor.ajay.app . Data is CC BY-NC-SA 4.0:
https://github.com/ajayyy/SponsorBlock/wiki/Database-and-API-License . The app reads
sponsor/skip entries only, merges overlapping ranges, rounds boundaries to
milliseconds, and optionally retains those transformed ranges with an explicit
offline save. These transformations are not endorsed by SponsorBlock. The app
makes no submissions or vote/view telemetry calls. Lookups are off by default;
enabling them sends a four-character SHA-256 video-ID prefix and the network's IP
address to the SponsorBlock service.

SmartTube remains an architectural reference; no SmartTube source is bundled.
This change does not initialize or depend on either of SmartTube's submodules.
