# Phase 2 — Dub (Audio-Language) Restore — Design

- **Date:** 2026-06-10
- **Status:** Approved for spec review → implementation planning
- **Scope:** Both players (regular `PlayerFragment` + `ShortsPlayerFragment`/`PlayerBinder`)
- **Related:** Regression analysis `memory/player-translations-cc-dub-regression.md`; feasibility spike `memory/player-dubs-phase2-spike.md`; lean-DASH convergence `memory/player-convergence-libretube.md`; resolver `memory/player-android-vr-resolver.md`
- **Hard constraint:** Must not break current playback on the regular player or the Shorts player. The VR HD video path stays byte-for-byte the same until the user opts in.

---

## 1. Problem

The audio-language ("globe") dub picker disappeared from both players after the lean-DASH convergence made **ANDROID_VR** the primary stream resolver. VR returns only the *original* audio track and strips all dubs, so `ResolvedStreams.audioTracks.size <= 1` → `availableAudioLanguages()` returns empty → the globe button is gated off (`PlayerFragment.kt:1486`, `langCount >= 2`). The downstream UI (`AudioLanguageDialog`, the gate, `selectAudioTrack`) is intact; it is starved of data.

The app's NewPipe path *has* audio-track mapping code (`NewPipeExtractorClient.kt:518–587`) and a wired web poToken provider (`poTokenProvider`, line 46), but its `clientRotator` only fetches **IOS→ANDROID** clients (failure-driven, lines 125–128) — and those do not carry dubs either. So today **no** resolve path fetches the dub-carrying client. The dub-carrying **WEB/MWEB client is simply never requested.**

## 2. Spike findings (proven 2026-06-10 — see `player-dubs-phase2-spike.md`)

1. **ANDROID_VR carries 1 track (original only).** Confirmed on `GpQSUjNsNm0` (22 dubs on web), `0e3GPea1Tyg` (24 dubs).
2. **MWEB carries all dubs with direct URLs** (no signature cipher); WEB carries them but needs signature deciphering; TVHTML5 is DRM-gated (dead end).
3. **Streaming a web-family dub audio requires three things, all of which VR is exempt from:** the dub URL + a **videoId-bound GVS poToken** (`&pot=`) + **nsig** (`&n=` transformed by YouTube player JS). Miss any one → hard 403 from byte 0.
4. **Sustain is proven:** a full 30.56 MiB Arabic dub downloaded to 100% via `yt-dlp` mweb + bgutil pot + deno nsig. ExoPlayer can range-stream the URL once it is nsig-transformed and pot-appended.
5. **The app already owns the hard ingredients:** `WebViewPoTokenProvider.getWebClientPoToken(videoId)` mints a videoId-bound web pot; NewPipeExtractor solves nsig + signature internally.

## 3. Goals / Non-Goals

**Goals**
- Restore the globe dub picker on both players for videos that have dubs.
- Keep the VR HD video as the playback spine; dub audio is grafted in only on opt-in.
- Graceful, non-breaking failure at every step (enumerate, resolve, mid-stream).

**Non-Goals**
- No change to default VR playback, quality ladder, recovery, or Shorts video behavior.
- No dub support for VR-UNPLAYABLE / SABR-360p videos where even the web video can't sustain HD (dub audio may still work there, but it is not a target; treat as best-effort).
- No dub-aware downloads in this phase (note interaction in §9; defer).
- No same-language manual+ASR disambiguation (already a separate deferred follow-up).

## 4. Architecture

**Principle:** VR video is never touched. Two new paths, split by cost.

```
video load
  └─► VR resolve (UNCHANGED) ──► play HD video + VR original audio   ◄── default, always works
           │
           └─(deferred, after first frame)─► ENUMERATE (cheap: MWEB innertube player call, no pot/nsig)
                                                   └─► dub languages ──► merge into audioTracks
                                                                              └─► availableAudioLanguages() ≥ 2
                                                                                       └─► globe button shows

user taps globe ──► AudioLanguageDialog ──► pick language
   ├─ pick "Original" ─────────────► ensure VR original audio active (no web path)
   └─ pick a dub ─► STREAM (heavy: NewPipe WEB-client resolve → nsig + web pot)
                        └─► chosen audio-only stream
                              └─► MergingMediaSource(VR video, web dub audio)
                                    └─► rebuild via existing selectAudioTrack / switchAudioTrack
                                          └─ on any failure ─► revert to VR original, video uninterrupted
```

### 4.1 Enumerate path (cheap, eager-deferred)
- A new `DubAudioEnumerator` issues one **MWEB innertube `/player` POST** for the videoId and parses `streamingData.adaptiveFormats[].audioTrack` → distinct `{languageCode, displayName, isOriginal}`. **No pot, no nsig, no WebView.** (Validated by `/tmp/probe_dubs.py`.)
- Triggered **after playback starts** (post-first-frame) so it never delays resolution. Result cached per videoId.
- Output merged into `ResolvedStreams.audioTracks` as **lazy dub entries** (language + display name, no stream URL yet). `availableAudioLanguages()` then returns ≥2 and the globe gate flips on — no change to the gate or the helper.
- Failure (network/parse/null) → no entries added → globe stays hidden. Silent, identical to today.

### 4.2 Stream path (heavy, opt-in on tap)
- A new `DubAudioResolver` performs a **NewPipe extraction forced to the WEB client**, supplying the existing `poTokenProvider` so NewPipe applies nsig + the videoId-bound web pot. From the result it selects the **audio-only** stream whose `audioLocale` matches the chosen language, returning a streamable URL + the web UA + pot context.
- Runs only when a dub (not "Original") is picked. Shows a brief loading affordance (web-pot generation via WebView is not instant).
- Result cached per (videoId, language) for the session; URLs are treated as short-lived (re-resolve on expiry/403).

### 4.3 Merge & switch
- `DashSourceBuilder` (or its caller) builds `MergingMediaSource(vrVideoSource, webDubAudioSource)`. VR video keeps the existing Android-UA / no-token segment factory; the web audio source uses a **web-UA + `&pot=`** segment factory.
- Regular player: reuse `viewModel.selectAudioTrack(representative)` → `AudioTrackSwapReady` → existing MediaSource rebuild (`PlayerFragment.kt:512–526`), extended to build the merged source when `representative` is a web dub.
- Shorts: reuse `PlayerBinder.switchAudioTrack`, extended the same way.
- Selecting "Original" rebuilds with the VR audio only (no web source) — cheap and always available.

### 4.4 Per-source UA / poToken
- `SegmentDataSourceFactoryProvider` must support **two factories in one player**: the VR video factory (Android UA, no token) and the web audio factory (web UA, `&pot=` already on the URL). Thread the per-source client/UA the same way `ResolvedStreams.extractionClient` is threaded today (see `player-convergence-libretube.md` UA-403 note — UA must match the client that minted each URL).

## 5. Data model changes
- `AudioTrack` (StreamModels.kt): add a discriminator so a track can be (a) a real VR/streamable track, or (b) a **lazy dub** (language + displayName known, URL resolved on demand). E.g. `source: AudioTrackSource { VR_NATIVE, WEB_DUB_LAZY }` + nullable stream handle. `availableAudioLanguages()` grouping is unaffected (it already groups by language and picks a representative).
- `ResolvedStreams`: enumerate results appended to `audioTracks`; add an optional `dubEnumerationState` if the UI needs to distinguish "not yet enumerated" from "enumerated, none."

## 6. Error handling / safety guarantees
| Failure point | Behavior |
|---|---|
| Enumerate call fails / times out | No dub entries; globe hidden. No user-visible error. |
| Web pot generation fails (WebView) | Dub resolve aborts; toast "dub unavailable"; stay on current audio. |
| nsig / web resolve fails | Same as above; revert to VR original. |
| Dub segment 403 mid-playback | Player error caught → revert to VR original audio, keep video position. One re-resolve attempt (URL expiry) before giving up. |
| User picks "Original" | Always succeeds (VR track), no web path. |

The default VR video+audio path has **zero** new dependencies. Every new path is additive and fail-closed.

## 7. Testing
**JVM unit (must pass, 300s/30s budget):**
- `DubAudioEnumerator` parse: multi-dub fixture → N languages; single-track → empty; malformed → empty.
- `AudioTrack` lazy-dub modeling + `availableAudioLanguages()` still groups/sorts correctly with mixed VR + lazy-dub tracks.
- Merge decision: web-dub `representative` → `MergingMediaSource` path; VR/original → no web source.
- Per-source UA/pot factory selection.
- Failure paths: resolve throws → revert-to-original signalled.

**On-device (emulator first — the user's spike-first call):**
1. **The one unproven thing:** a dubbed video → tap globe → pick Arabic → confirm audio switches and **sustains past ~2 min** (no 403 cliff) with `MediaSource Rebuilds`/`403 Errors` app metrics clean. This validates the in-app NewPipe-web path end-to-end.
2. Globe hidden on a no-dub video; appears (~after first frame) on a dubbed video.
3. Pick "Original" → reverts cleanly; video never re-buffers from scratch unexpectedly.
4. Force a dub failure (airplane mid-resolve) → revert to original, video uninterrupted.
5. Repeat 1–4 on Shorts.
6. Rotate / multi-window / PiP during a dub → no leak, audio intact.
7. RTL Arabic UI + `sw600dp`/`sw720dp` globe/dialog placement unchanged.

## 8. Open implementation questions (resolve in the plan)
1. **Forcing NewPipe to the WEB client** for the dub resolve without disturbing the IOS→ANDROID `clientRotator` used by the main resolve. Likely a dedicated extraction config path separate from the rotator. Verify NewPipe (pinned version) applies `getWebClientPoToken` + nsig on that client.
2. **Enumerate transport:** hand-rolled MWEB innertube (cheapest, validated) vs a trimmed NewPipe web "info-only" call. Lean hand-rolled to avoid a WebView pot on the hot path.
3. **Loading affordance** for the heavy dub resolve (spinner in the dialog vs on the rail button).
4. Cache TTLs for enumerate and resolved dub URLs.

## 9. Out of scope / follow-ups
- Dub-aware downloads (`DownloadQualityDialog` reads `ResolvedStreams`; lazy dubs are not downloadable as-is). Note and defer.
- Lifting non-SABR VR-UNPLAYABLE video quality (separate, see resolver memory).
- Same-language manual+ASR caption disambiguation (already deferred).

## 10. Rollout
Emulator end-to-end (test #1 above) is the gate — it proves the in-app web dub path before any merge to develop. Then the physical SM-S938U pass, then the mandatory 7-stage review pipeline (`memory/feedback_review_pipeline.md`). Opt-in design means a failure of the web path degrades to today's behavior, not a regression.
