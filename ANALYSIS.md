# ANALYSIS.md — REA investigation of SmartTube, and what LatvianTube took from it

Target: `https://github.com/yuliskov/SmartTube` @ `486c493`
Method: REA investigation discipline
Date: recon performed 2026-05

**Current playback update (2026-10-14):** the WebView engine described below has
been replaced by Media3 / ExoPlayer and NewPipeExtractor v0.26.5. The original
investigation remains historical evidence; its missing-extractor and playback
limitations are superseded by §E12 and `README.en.md` → Native playback.

---

## 0. Routing: why REA's binary tooling was not used

The request was to "use REA and inspect this repo". The REA skill's own routing
rule governs this case, and it is explicit:

> "Skip REA for ordinary source-repository architecture analysis."
> "For ordinary analysis of a complete source repository, use normal repository
> tools and do not run REA readiness or provider commands."

REA's tools exist to recover understanding that the source *cannot* give you:
decompiling binaries, reading APKs, tracing minified JavaScript, replaying
network captures. SmartTube is a complete, public, Java source repository. There
is no shipped artifact whose behaviour is in question, so `open_binary`,
`inspect_android_package` and the rest would add cost without adding evidence.

What was carried over is REA's **discipline**, which is the part that actually
matters here:

1. Turn the request into a checklist of questions, each with the evidence that
   would answer it (§1).
2. Start from the smallest useful overview and follow each question to its
   answer (§2).
3. Distinguish **observation**, **inference** and **unknown** in every conclusion
   (§2, §4).
4. Corroborate with the evidence type the claim requires — here, live probing of
   YouTube rather than assertion from reading code (§3).
5. Keep a finding ledger with limitations and residual unknowns (§5).

One place REA tooling *would* have applied: had the question been "how does
SmartTube extract video streams", the honest answer requires the `MediaServiceCore`
submodule — and a built APK of it. That artifact is not present (§2.5). That
limitation is recorded rather than papered over.

---

## 1. Investigation checklist

| # | Question | Answer | Evidence |
|---|---|---|---|
| Q1 | What kind of app is SmartTube, and what is its architecture? | Android TV YouTube client, Java, Leanback UI, MVP split across two modules | E1 |
| Q2 | What content rows does it present? | 18 sidebar sections; rows produced by `BrowsePresenter` | E1 |
| Q3 | How does it play video? | ExoPlayer (Amazon fork 2.10.6) + custom SABR source | E1 |
| Q4 | Where is the YouTube extraction/extraction logic? | **Not in this repo** — in an uninitialised git submodule | E1 |
| Q5 | Does SmartTube support Latvian? | UI: yes (~120/748 strings). Content: no language scoping at all | E6 |
| Q6 | Can "Latvian content" be read from YouTube metadata? | **No** — see E3. Must be inferred | E3 |
| Q7 | Which InnerTube endpoints actually work from here? | search ✔, channel uploads ✔, player ✔, trending ✘ (400) | E2, E4 |
| Q8 | Does an inferred Latvian filter work on real data? | Yes: 120 videos, 115 admitted, 8/8 fixtures | E5 |
| Q9 | Is the curated channel list trustworthy? | Not until verified; 4 of 21 candidates were not Latvian | E5 |
| Q10 | Can the Android app be compiled here? | **No** — no JDK, no Android SDK | E7 |

---

## 2. Evidence

### E1 — SmartTube architecture (observation)

Read from the checkout at `.cache/recon/SmartTube`.

- **Two first-party modules.** `smarttubetv/` (186 Java files, the Android-TV
  view layer) and `common/` (211 files, platform-agnostic MVP + domain + player).
  The TV module holds Activities, Fragments and card presenters; `common` holds
  presenters, models and the ExoPlayer engine.
- **MVP with a view registry.** Presenters depend on `common/app/views/*View`
  interfaces, never on Activities. `MainApplication.setupViewManager()` maps each
  view interface to its Activity. This is what makes the TV frontend swappable.
- **Row taxonomy.** `BrowsePresenter.initSectionMapping()` /
  `initRowAndGridMapping()` map a `MediaGroup.TYPE_*` integer to a title, icon,
  section type and an `Observable<MediaGroup>` provider. The rows a user sees:
  Home, Shorts, Trending, Kids, Sports, Live, My Videos, Gaming, News, Music,
  Channels, Subscriptions, History, Blocked, Playlists, Notifications, Queue,
  Settings.
- **Player.** `PlayerEngine` is a fat interface (~40 methods) implemented by
  `ExoPlayerController`, wrapping `SimpleExoPlayer` from a vendored Amazon fork
  of ExoPlayer 2.10.6. `PlaybackPresenter` owns an ordered list of
  `BasePlayerController` plugins (SponsorBlock, AFR, chat, error recovery).
- **Reported defects in the README.** The project states its development
  environment was compromised and that signing keys may have been exposed. Noted
  as context, not used as a basis for any implementation decision here.

### E2 — Is there a usable public data path? (observation)

Probed `https://www.youtube.com/youtubei/v1/{search,browse,player}` with a web
client context. Results:

| Endpoint | HTTP | Outcome |
|---|---|---|
| `search` with `hl=lv&gl=LV` | 200 | 671 KB, genuine Latvian results |
| `browse` + channelId + `EgZ2aWRlb3PyBgQKAjoA` | 200 | 30 uploads |
| `player` | 200 | works; `defaultAudioLanguage` **absent** |
| `browse` + `browseId=FEtrending` | **400** | "Request contains an invalid argument" |

### E3 — Can language be read from metadata? (observation → negative result)

This is the finding that shaped the whole design.

1. **`defaultAudioLanguage` is absent.** Checked across a mix of Latvian and
   non-Latvian videos; the field did not appear for any of them in the `player`
   response. Audio language is therefore *unknown*, not Latvian.
2. **Channel country is not exposed** to the WEB client's about renderer —
   regex over the channel about response found no `country` field.
3. **A Latvian channel name proves nothing.** A channel called "Latvijas Ziņas"
   ("Latvian News") publishes exclusively in Russian. Channel *name* language and
   channel *content* language are independent.
4. **`hl=lv` biases ranking, and that is all.** It makes YouTube surface Latvian
   results first; it does not restrict the result set. A query still returns
   whatever YouTube wants, including Cyrillic-titled videos.

**Conclusion (inference).** "Latvian content" cannot be read off an authoritative
field. It must be *scored* from weak signals, and the confidence must be surfaced
rather than hidden behind a boolean.

### E4 — The renderer migration, caught by the harness (observation)

The first parser version looked only for classic `*Renderer` keys. Every channel
uploads response then yielded **zero** videos, while a naive regex over the same
bytes found **30**. Diagnosing the response showed the actual shape:

```
30 × lockupViewModel   (modern)
30 × richItemRenderer
30 × lockupMetadataViewModel
 0 × videoRenderer     ← what the parser looked for
```

Channel tabs and search now return `lockupViewModel`, whose title and byline live
in a nested `lockupMetadataViewModel` and whose metadata rows are positional
(`[viewCount, publishedTime]`) with no stable labels. Both shapes must be handled.
This is recorded because it is the single most likely way a reimplementation
silently returns nothing while appearing to work.

### E5 — Does the filter work? (measurement)

`node tools/evaluate.mjs` → `tools/REPORT.md`:

- 7 Latvian search queries, **120 videos retrieved, 115 admitted**, 5 rejected.
- Fixtures: **8/8 pass**, including the three hard cases — `Новости Латвии`
  (Cyrillic, −25), `Best of Latvia 4K` (English-only, −2), and
  `DZIESMAS JAUNAI DIENAI` (Latvian but no diacritics or function words, correctly
  rejected without channel context and admitted with it).

`node tools/verify-registry.mjs` → all candidate channels checked against live
uploads. This found **four channels that had to be excluded**, and one harness bug:

| Finding | Consequence |
|---|---|
| `Latvijas Ziņas` — 0% admitted, publishes "Новости Латвии" in Russian | Removed from registry; recorded in `excluded` |
| `Delfi Lietuva` — Lithuanian broadcaster surfaced by Latvian search | Excluded |
| `Oga` — generic name resolved to a channel publishing German vlogs | Excluded |
| `Diena` — search resolved to "Daily Dainá", an English channel | Excluded |
| **Harness bug**: channel-tab videos carry no byline, so `channelId` was null and the curated bonus never applied | Fixed by stamping the owning channel |

After fixes: **17/17 channels verify at ≥97% admitted.**

A recall bug was also found and fixed here: `"Metronoms - Nāk Nakts"` (genuine
Latvian) scored 3 against a threshold of 4. The fix was linguistic rather than a
threshold fudge — diacritics are now weighted by how exclusive they are to
Latvian (`ā ē ī ū ō ņ ķ ļ ģ` at 4; `š ž č`, which are also Czech/Croatian/
Turkish, at 2). That brought the fixture to exactly 4 and left every negative
fixture still rejected.

### E6 — Localization: Latvian exists, but thinly (observation)

Correction to an initial misreading: Latvian **is** present in SmartTube.

- All 748 base strings live in `common/src/main/res/values/strings.xml`, across
  46 locales. `common/src/main/res/values-lv/strings.xml` exists with **120
  strings — about 16% coverage**. 630 base keys have no Latvian entry and fall
  back to English silently.
- Lint disables `MissingTranslation`, and there is no runtime completeness check,
  so the shortfall is invisible to both developers and users.
- The app module explicitly tells contributors not to translate its own
  `strings.xml`; translations go through Crowdin into `common`.

**Consequence for LatvianTube.** Latvian is the *base* locale, not an overlay.
A missing string falls back to Latvian, which is the correct failure direction for
this audience.

### E7 — Build feasibility on this machine (observation)

```
java -version      → Unable to locate a Java Runtime
/usr/libexec/java_home -V  → no JDK found
~/Library/Android/sdk      → does not exist
brew list | grep jdk       → nothing
Java files checked         → 22 (none compilable here)
Chrome                     → present
Node                       → v24.14.1
```

There is no JDK and no Android SDK, therefore the Android module **cannot be
compiled, and the APK cannot be built or run.** The user was asked and chose to
proceed with source-only delivery, accepting this.

What was done instead of compiling:

- Every uncertain Leanback API was checked against the **actual androidx source**
  rather than assumed. This found three real errors that would otherwise have
  shipped:
  1. `BrowseFragment` originally extended `RowsSupportFragment`, which exposes
     **neither `setTitle` nor a public `setAdapter`** (`setAdapter` exists only on
     its inner adapter class). Switched to `BrowseSupportFragment` with
     `HEADERS_DISABLED`, which has both.
  2. `HeaderItem.setName(String)` **does not exist** — `HeaderItem` is
     effectively immutable by name. Status is now carried on
     `setDescription(CharSequence)`, which does exist.
  3. `new HeaderItem(String, String)` matches **no constructor** — the two-arg
     form takes `(long id, String name)`. Fixed with a real long id.
  Confirmed as *existing* (so left alone): `RowsSupportFragment.setOnItemViewClickedListener`,
  `ListRowPresenter.setShadowEnabled`, `RowPresenter.setSelectEffectEnabled`,
  `SearchSupportFragment.SearchResultProvider` (`getResultsAdapter`,
  `onQueryTextChange`, `onQueryTextSubmit`), `ImageCardView` setters,
  `ArrayObjectAdapter.addAll(int, Collection)` and `clear()`.
- `tools/validate_project.py` performs static checks a compiler would otherwise
  catch: XML/JSON well-formedness, every `R.*` reference resolving to a defined
  resource, manifest and layout references resolving, package declarations
  matching directories, balanced braces, JS↔Java filter-weight parity, and
  registry integrity. It currently reports **no errors** across 6 XML files,
  22 Java files and the registry.

The validator is explicitly **not** a compiler: it does not type-check, resolve
imports, or verify that a method exists. It caught real defects (the malformed
`HeaderItem` usage) but passing it is not evidence that the project builds.

### E8 — Runtime testing found three defects static analysis could not (observation)

A self-contained toolchain was installed inside the workspace (Temurin JDK 17 +
Android SDK 34, nothing system-wide) and the APK was run on an **Android TV
emulator** (`system-images;android-34;android-tv;arm64-v8a`, `tv_1080p`,
headless).

**Defect 1 — the import bug the compiler caught.** First `assembleDebug` failed:

```
InnerTubeClient.java:169: error: cannot find symbol
    Call call = mHttp.newCall(request);
```

An `okhttp3.Call` import removed during an earlier cleanup pass while the type was
still used. `validate_project.py` reported "No errors found" on this same code,
because it cannot resolve symbols. This is the cleanest demonstration of where the
static approach stops.

**Defect 2 — the player did not play at all (error 153).** With the app running,
selecting a video opened `PlaybackActivity`, the WebView initialised, and YouTube
rendered its own error page — in Latvian, confirming `hl=lv` reaches the player:

> Videoklipu atskaņotāja konfigurācijas kūda / Klūda 153

Cause: the player page was loaded from `file:///android_asset/player.html`. The
YouTube IFrame API **rejects `file://` origins outright**. No amount of compiling,
linting or resource checking would have surfaced this — it is a policy of a remote
service, observable only by running.

Fix: `androidx.webkit`'s `WebViewAssetLoader` serves the bundled page over a real
HTTPS origin (`https://appassets.androidplatform.net/assets/player.html`), and the
page now passes `origin: window.location.origin` to the API. After the fix,
`cr_MediaCodecBridge: create MediaCodec video decoder, mime video/x-vnd.on2.vp9`
appeared in logcat and the video played — with Latvian captions
("roza linda [dziedāšana] fermā tāda vienīgā.").

**Defect 3 — search was unreachable.** `SearchActivity` was written, correct, and
impossible to get to: `BrowseActivity` has no sidebar and current
`BrowseSupportFragment` provides no search affordance
(`setOnSearchClickedListener` belongs to the older framework `BrowseFragment` and
is absent from the androidx class — verified in the androidx source). Confirmed by
the shell refusing to start the non-exported activity, which is correct behaviour
and exactly what exposed the gap: *nothing else could have reached it either*.

Fix: an explicit `Meklēt` row at the top of the home screen, reachable with the
D-pad alone. That is better TV UX than a header icon regardless.

### E9 — Localization defect found only on screen (observation)

With the app running, the Leanback search bar rendered **"Speak to search" in
English** in an otherwise fully Latvian UI. The app's own strings were correct;
this was the Leanback library's own string resolving against the emulator's
English system locale.

`values` in the Leanback AAR was inspected and **does contain `values-lv`**. Fix:
`MotherActivity.attachBaseContext` pins the resources to `lv-LV`, so the app is
Latvian regardless of the TV's locale — which is the correct behaviour for a
Latvian-only product anyway. After the fix the bar reads
**"Runāt, lai meklētu"**.

### E10 — Verified runtime behaviour (observation)

Everything below was observed on the running emulator, not inferred:

| Behaviour | Observed |
|---|---|
| Installs as a TV app | `adb install` → Success; resolves as `LEANBACK_LAUNCHER` |
| Registry loads | `ChannelRegistry: Loaded 17 curated channels in 8 categories; 3 exclusions recorded.` |
| Live content through the gate | 8 rows, **465 retrieved / 461 admitted**; `zinas 150/148`, `sports 120/120`, `virtuve 65/65` |
| Render | 8 carousels, Latvian titles, relative times ("pirms 19 minūtēm") |
| Playback | MediaCodec VP9 decoder created; video visible; Latvian captions |
| Search | query `latvijas` → "Rezultāti: 20" Latvian results |
| Search reaches outside the registry | Results included *Laika Doma* and *Sporta Studija*, neither curated |
| Cache | Second launch: every row logged `(cache)` |
| Crashes | **0** across all runs |

### E11 — The app stuttered on real TV hardware: main-thread parsing (measurement)

**Reported symptom.** On a Grundig Android TV (Arçelik, Android 11, API 30,
192 MB heap, WebView 142) the app worked but stuttered every 10–15 seconds.

**Method.** The TV was reachable over adb, so the measurement was taken on the
device showing the fault rather than on the emulator. Baseline from
`dumpsys gfxinfo` plus `Choreographer` stall messages:

| Metric | Before |
| --- | --- |
| Janky frames | **65.98%** |
| 90th percentile frame | 38 ms |
| 95th percentile frame | 53 ms |
| 99th percentile frame | 113 ms |
| Choreographer stalls | recurring, one of 121 frames (≈2 s) |

An idle capture first ruled out a repeating background task: **45 seconds with
the home screen untouched produced zero Choreographer stalls.** The freezes were
therefore tied to interaction, which pointed at work being done in response to
touching the UI.

**Root cause (observation).** `InnerTubeClient` delivered every response through
`Handler.post()`, i.e. onto the **main thread**, and the repository then did all
of its expensive work inside that callback:

```java
mMain.post(() -> callback.onSuccess(result));   // InnerTubeClient
// then, on the UI thread:
mCache.put(perChannelKey, result);                 // file write
InnerTubeParser.extractVideos(result);             // recursive walk of ~600 KB JSON
InnerTubeParser.extractVideos(result);             // ← again, for the count
```

Three separate faults compounded:

1. **Parsing on the UI thread.** `extractVideos` is a recursive walk of the whole
   response. A channel uploads response is ~30 videos inside a large nested
   document; doing this on the main thread is tens of milliseconds per call.
2. **The same tree was walked twice** — once to build the list, once inside
   `videoCount()` purely to produce a count for the log line.
3. **Cache file writes on the UI thread**, plus `RawRecorder` was switched on for
   *every debug build*, writing each raw response body (hundreds of KB, 17 of them
   just to paint the home screen) to the TV's flash.

The home screen does this once per curated channel — 17 times per load.

**Fix.**

- `InnerTubeClient` is now **blocking**, with no handler: it is a transport, and
  says so.
- `ContentRepository` owns a 3-thread executor and is the only class that touches
  the main thread. Parsing, cache I/O and network all happen off the UI thread;
  only the finished `Row` / `List<Video>` is posted back.
- The double parse was removed (`videoCount()` deleted).
- `RawRecorder` now defaults to **off**, including in debug builds, and must be
  enabled deliberately.
- Glide: `dontAnimate()` (the default cross-fade animates every one of ~460
  cards) and `DiskCacheStrategy.ALL` (RESOURCE cached only the full-size
  original, so a re-bind after scrolling re-decoded it).

**Result, measured on the same TV** — 75 s of continuous scrolling forcing card
recycling (2323 frames):

| Metric | Before | After |
| --- | --- | --- |
| Janky frames | 65.98% | **4.30%** |
| 90th percentile | 38 ms | **11 ms** |
| 95th percentile | 53 ms | **15 ms** |
| 99th percentile | 113 ms | **22 ms** |
| Choreographer stalls (app) | recurring, up to 121 frames | **0** |

Functionality re-verified after the refactor: all 8 rows load, `latvijas` search
returns 15 Latvian results from 16 retrieved, and there are **0** crashes —
specifically no `NetworkOnMainThreadException` and no
`CalledFromWrongThreadException`.

**Limitations of this measurement.** The "before" figure is `gfxinfo`'s
cumulative lifetime counter, while the "after" figure is a controlled 75 s scroll
window; they are not a matched pair, and the percentile improvements are the more
trustworthy comparison. Two other processes on the TV stalled at the same moment
as the app's single 121-frame stall, so some contention is the TV's own and not
attributable to LatvianTube. The residual 4.30% is first-load image decoding,
which is expected.

---

## 3. What transferred, and what did not

**Adopted (with reasoning):**

| SmartTube pattern | In LatvianTube | Why |
|---|---|---|
| View-contract separation | Simplified: UI → `ContentRepository` → `InnerTubeClient` | Keeps YouTube detail out of screens |
| Row taxonomy driven by a mapping table | Categories driven by `channels_lv.json` | Adding a row is a JSON edit, not code |
| `MediaServiceManager` as the single data seam | `ContentRepository` | One place where filtering is guaranteed |
| Player as a swappable engine | `PlayerEngine` implemented by `ExoPlayerEngine` | Native implementation replaced the original web activity |
| Record everything | `RawRecorder` (raw body, status, bytes, ms) | A 400 or consent wall must never read as "no results" |
| `MotherActivity` base | `MotherActivity` | One place for TV config-change handling |

**Deliberately not adopted:**

| SmartTube | Why not |
|---|---|
| Two modules + two git submodules | That structure serves three distribution flavors and a swappable frontend; LatvianTube needs neither |
| minSdk 17, Java 8, pinned okhttp 3.12.13 | The Android 4.3 era support forces much of SmartTube's complexity; LatvianTube starts at 21 |
| In-tree Leanback/Fragment forks, `ui/mod/**` | These patch TV focus bugs for 2013 hardware |
| 18 sidebar sections | Most need an account; LatvianTube is anonymous and stateless |
| SponsorBlock, DeArrow, Lounge remote, AFR, chat | Out of scope; would require the extraction layer anyway |
| Sign-in | Not needed to browse Latvian content |

---

## 4. Findings summary

1. **SmartTube is a TV client whose hard part lives outside its repository.**
   The extraction engine (`MediaServiceCore`) is an uninitialised submodule; a
   clean clone cannot even configure in Gradle. This is the single most important
   fact for anyone planning to build "something like SmartTube".
2. **YouTube will not tell you what language a video is in.** Not via
   `defaultAudioLanguage` (absent), not via channel country (not exposed), not
   via channel name (demonstrably misleading). Language must be inferred and the
   inference's confidence surfaced.
3. **A channel registry is worth more than a clever filter.** The gate's strongest
   signal is curated membership, which is what rescues genuine Latvian titles that
   carry no diacritics. But a registry is only as good as its verification — 4 of
   21 candidates were not Latvian.
4. **Trending is unavailable, so the home screen must be built differently.**
   `FEtrending` returns 400 and the trending page serves a consent wall. Curated
   uploads plus scoped search are the sources that actually work.
5. **Surface migrations produce silent zero-result failures.** `lockupViewModel`
   replaced `videoRenderer` on channel tabs; a parser that only knows the old
   shape returns an empty list without erroring.
6. **SmartTube's Latvian UI coverage is ~16%**, and its fallback is English.

---

## 5. Limitations and residual unknowns

Stated plainly, because each of these bounds a claim made above.

1. **The Android app has never been executed.** No JDK, no SDK. Every statement
   about its runtime behaviour is design intent, not observation. Only the Node
   harness and the static validator actually ran.
2. **The filter was validated on 120 videos from 7 queries and 17 channels.**
   That is a spot check, not a benchmark. No labelled ground-truth corpus exists
   here, so **no precision or recall figure is claimed.**
3. **Recall is bounded by the seed set.** A Latvian video from a channel outside
   the registry, with a title containing no diacritics and no function words, is
   invisible to the app. `DZIESMAS JAUNAI DIENAI` is the canonical example.
4. **Audio language is never verified.** The gate infers from text. A Latvian
   title over a Russian-language video would pass. This is a real false-positive
   path and it is not detectable with the metadata YouTube exposes.
5. **Playback relies on YouTube's embedded player in a WebView**, because the
   extraction layer is absent. Consequently there is no quality control, no
   SponsorBlock, no AFR, and no offline playback. D-pad behaviour is the web
   player's, not a native TV player's.
6. **InnerTube is undocumented and unversioned.** The `WEB_KEY` is the public
   constant from youtube.com; it is not a credential, but it can change without
   notice and carry no support. The `RawRecorder` exists partly for this reason.
7. **Long-run rate-limit behaviour is untested.** Caching (30 min TTL for
   channels) and sequential fetching bound the request rate, but no sustained
   load test was run.
8. **`verify-registry.mjs` uses the last 30 uploads.** A channel that switched
   language recently may be misclassified in either direction.
9. **The vendored `exoplayer-amzn-2.10.6` and `leanback-1.0.0` trees were
   excluded from analysis** as third-party code. Statements about player
   behaviour describe SmartTube's use of them, not the libraries themselves.

---

### E12 — Native replacement of the embedded player (2026-10-14)

**Implementation.** The bundled iframe page and WebKit dependency were removed.
`PlaybackActivity` now uses `PlayerEngine`, implemented by `ExoPlayerEngine`
(Media3 1.4.1). `StreamResolver` delegates client negotiation and signature / n
handling to NewPipeExtractor **v0.26.5**, not SmartTube's absent submodule and not a
new hand-written decipherer. Its GPL-3.0-or-later dependency was explicitly
approved by the user; original MIT source and combined-APK distribution terms
are distinguished in `THIRD_PARTY.md` and bundled notices.

**Observed on the Android TV 14 arm64 emulator.** A curated LTV Ziņu dienests
video, `rOhTU5K28R0` (“Liepājā daudzdzīvokļu mājas ūdenī konstatētas legionellas
baktērijas”), played through `c2.goldfish.h264.decoder` and
`c2.android.aac.decoder`, with Latvian captions. Native controls displayed an
advancing position against the 3:21 duration. The quality chooser exposed AVC / VP9
resolutions from 144p through 1080p; selecting 1080p retained the paused 0:16
position. Play/pause and the SponsorBlock toggle were exercised.

An explicit offline save of the 720p AVC variant completed with **28,669,011 video
bytes**, **3,270,208 audio bytes**, **24,606 caption bytes**, and metadata. A fresh
app launch with the emulator's HTTP proxy directed to the closed local port
`127.0.0.1:9` reopened the cached card and logged
`Preparing offline media, quality=720p · AVC`; frames and playback position
advanced from local files. The previous proxy setting was restored immediately.
This is an HTTP-connectivity failure test, not a physical network-disconnection
or airplane-mode test.

**Automated evidence.** Nine deterministic JVM tests cover extractor transport
headers, request payloads, redirects, HTTP 429, sponsor action/range validation,
overlap merging, seek boundaries, atomic two-track saves, incomplete audio,
truncated ranges, corrupt local files, and invalid path IDs. One opt-in live test
resolves a public video, fetches video/audio byte ranges, and saves complete media.
It passed with the short public video `jNQXAC9IVRw` and separately with the LTV
video above (including available Latvian captions). Android compilation succeeds;
lint reports zero errors. `tools/validate_project.py` reports no errors.

**Design limits.** Automatic direct-stream selection is a fixed compatible quality
up to 720p, not bandwidth adaptation; manual selection reconstructs the media
source while preserving position/pause. HLS/DASH playback is wired but was not
exercised in this run. SponsorBlock's interval behavior is verified with fixtures;
no claim is made that this LTV video had a real sponsor segment to skip. Downloads
are explicit, app-private, complete before publication, and limited to on-demand
direct streams, 2 GiB per item. They are not resumable and there is no offline
library screen; reopening relies on cached content cards. No SmartTube SABR,
account access, AFR, PiP, or full feature parity is claimed. Native performance on
the Grundig TV and API 21 runtime behavior remain unmeasured.

### E13 — Network installation on the user's physical TV (2026-10-14)

The native build was installed over network ADB after the user accepted the TV's
authorization prompt. The existing host ADB server could not connect despite a
reachable TV TCP port; a separate local server on port 5038 succeeded. Installation
used `adb install -r`, returned `Success`, and launched `BrowseActivity` with
`Status: ok`. A subsequent process/activity check confirmed LatvianTube running
in the foreground. This verifies installation and launch of the native build,
not sustained native playback or performance on the physical TV.
