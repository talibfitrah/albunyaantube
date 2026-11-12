# Android playback stop (40–60 s) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kids videos stop dying at ~40–50 s on Android: NewPipe's IOS client goes off permanently, its dead code goes, and a repeated mid-play 403 plays the muxed itag 18 from the streams already in hand before the "could not recover" state appears.

**Architecture:** NewPipeExtractor 0.26.5 merges ANDROID → VISIONOS → IOS and keeps the first copy of each stream. With IOS off, VISIONOS still serves HD for non-kids videos, and kids videos get ANDROID itag 18. That is all the probe proved works. The recovery rule is one pure function (JVM-tested) that `PlayerFragment`'s 403 branches consult. The muxed fallback rebuilds the source from the current `ResolvedStreams`, so it needs no extraction and no rate-limiter budget.

**Tech Stack:** Kotlin, Media3 1.11.0, NewPipeExtractor **0.26.5 (pinned; do not upgrade)**, Hilt, JUnit4 + Mockito-Kotlin + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-11-11-sorting-search-playback-design.md` §C (+ "Builder-check resolutions").

## Findings this plan carries (Job 1, 2026-11-11)

- Every shipped Android release from 1.0.0 (vc 62) to 1.0.6 (vc 68) builds with IOS fetch on, in both `play` and `sideload` flavors. The flag lives in `defaultConfig` and no flavor or flavor source set overrides it. NewPipe is 0.26.5 and there is no ANDROID_VR path (removed in c46fb896, 2026-08-18, first tag v1.0.0-beta.38). Tag `v1.0.7` is the iOS release; Android at that commit is 1.0.5.
- There is no remote switch on Android: no Firebase Remote Config (only `firebase-auth`), no backend config read, and no app-version header. The developer toggle is behind `if (BuildConfig.DEBUG)` (`AboutFragment.kt:98`). **Only an app update fixes the field.**
- There is no Android minimum-version floor. The sideload updater prompt can be dismissed ("Later", once per process, `UpdatePromptFlow.kt:115`). Play binds `NoUpdateGateway` and has no in-app-updates library. **So no "raise the floor" task exists.** Adding a floor would be new work for 1.0.8+, and installs older than that could never be forced. Owner decision, out of scope.
- Paths that resolve through the same `GlobalStreamResolver → NewPipeExtractorClient.resolveStreams` inherit the fix: the main player, background/PiP (`PlaybackService` drives the fragment's ExoPlayer), audio-only mode, Shorts (`PlayerBinder.kt:404`), casting (`PlayerFragment.kt:3958` sends `selection.video.url`), prefetch and downloads (`DownloadWorker.kt:385`). Downloads already fall back to itag 18 after a repeated 403 (`DownloadWorker.kt:298-307`). With IOS off, an audio-only save of a kids video would store the whole 360p MP4 as "audio" (`DownloadWorker.kt:401-403` picks the muxed-derived track). Task 4 fixes that.
- One path this fix does not touch: user-selected web dubs use MWEB-minted audio (`DubAudioResolver`).

## Global Constraints

- NewPipeExtractor stays `v0.26.5` (`android/app/build.gradle.kts:563`). v0.27.x cannot play kids videos at all.
- No other dependency changes in this plan.
- Never use music-video ids in tests, fixtures or probes. Use catalog lecture ids: `V2Brp_esIVI`, `TGtdZmxsM5Q`, `vMA-kafObiQ` (VISIONOS-served) and `xuE4k3wQt9I`, `A47eqQ_nOk4` (kids).
- The UA stays the iOS-app UA for every NewPipe URL. The resolved label stays `ExtractionClient.NEWPIPE_IOS`. Reason: the 2026-11-11 runs proved VISIONOS adaptive and ANDROID itag 18 with that UA (run1/run3/run5). The Android Chrome UA was proven for itag 18 only (run4). Changing it is unproven risk.
- Hilt DI only. Same view behaviour on phone, sw600dp and sw720dp. No layout change in this plan.
- Commits use `[FIX]:`/`[REFACTOR]:`/`[TEST]:` at ≤50 chars, followed by the session attribution lines. Work on branch `fix/android-playback-stop`, then merge to `main` and delete the branch.
- Before the merge, run the 9-stage pipeline from `AGENTS.md` (Cubic included). Deploy nothing before Cubic.
- Where things run:
  - JVM unit tests run on this Mac. The SDK is at `~/Library/Android/sdk` (platforms 35/36, `compileSdk = 36`), but there is no `local.properties`, so pass `ANDROID_HOME`.
  - The probe runs on this Mac with plain JDK 17.
  - No device and no emulator is used. The owner's report from the S25 Ultra after the update is the field check. Ask the owner to confirm that phone's installed version and source (Play vs sideload).

`GRADLE` below means: `cd android && ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home ./gradlew`

## Review Focus

1. **A live channel with IOS off.** In 0.26.5 the HLS URL comes from VISIONOS, then IOS, then ANDROID (bytecode, `getHlsUrl`). If VISIONOS offers no live HLS, IOS-off could leave live with no manifest. Test: the Task 1 live run.
2. **Kids video when the WebView pot is unavailable** (old WebView, cooldown). The ANDROID request falls back to the reel endpoint (`fetchAndroidClient` with a null pot). The probe's `default` mode shows kids videos then fail with `UNPLAYABLE`. Expected: the existing "not available" state, not a refresh loop. Test: the Task 1 `iosOff none` run records it. Report it, do not fix it here.
3. **A 403 on a video with no muxed track.** Today's ladder must hold: refresh, refresh, then exhausted, with no crash. Test: Task 3 `no muxed track keeps the refresh ladder`.
4. **The next video after a muxed fallback gets HD again.** The sticky flag is kept only for the same `streamId`. Test: a Task 3 review check on the `!=` guard. It is not JVM-testable because it lives in the Fragment.
5. **Audio-only save of a kids video** must save audio, not the 360p video file. Test: Task 4.

---

### Task 1: Off-device proof gate (no repo change)

**Files:** `/private/tmp/claude-501/-Users-farouqabouumar-Development-albunyaantube/88109bb3-6d83-4bb7-84d4-a5c4ff95482a/scratchpad/probe/Probe.java` (scratchpad only; recreate it from this session's copy if the scratchpad was cleaned)

- [ ] **Step 1:** In `Probe.main`, change the late-check wait from `start + 75_000` to `start + 300_000` (the spec requires sustain past 5 min). After the `streams by client` log line, add `log("  hls=" + (info.getHlsUrl().isEmpty() ? "none" : "present"))`.
- [ ] **Step 2:** Compile from the scratchpad dir: `javac -cp 'lib0265/*' -d out0265 probe/Probe.java`
- [ ] **Step 3:** Run `java -cp 'out0265:lib0265/*' Probe iosOff web ios V2Brp_esIVI TGtdZmxsM5Q vMA-kafObiQ xuE4k3wQt9I A47eqQ_nOk4 | tee run6.txt`.
  Expected: every target line ends `late: HTTP 206 ... (t+30Xs)`. For VISIONOS ids: video-only + audio `c=VISIONOS`. For kids ids: only `muxed itag18 c=ANDROID pot=yes`.
  **If any target 403s, stop. Report to the owner. Do not start Task 2.**
- [ ] **Step 4:** Pick one catalog channel that is live right now (any LIVE badge in the app's channel list; not music). Run `... Probe iosOn web ios <liveId>` and `... Probe iosOff web ios <liveId>`.
  Expected: `hls=present` in both runs. If iosOff shows `hls=none`, stop and report: live would break.
- [ ] **Step 5:** Run `... Probe iosOff none ios xuE4k3wQt9I`. This records Review Focus 2. The expected output is `EXTRACTION FAILED ... UNPLAYABLE`. Paste it into the PR description as a known limitation.

### Task 2: IOS fetch off; delete IOS-only code

**Files:**
- Modify: `android/app/build.gradle.kts:126-134` (delete the `ENABLE_NPE_IOS_FETCH` block) and `:153-155` (delete `ENABLE_CLIENT_ROTATION`; only the rotator reads it)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt`
- Delete: `.../data/extractor/YoutubeClientRotator.kt`, `android/app/src/test/.../data/extractor/YoutubeClientRotatorTest.kt`
- Delete: `android/app/src/androidTest/.../data/extractor/potoken/PoTokenStreamResolutionTest.kt` (its single test forces `setFetchIosClient(true)`)
- Modify: `.../di/DataModule.kt:285,291` (drop the `clientRotator` param/arg)
- Modify: `.../player/PlaybackFeatureFlags.kt` (`KEY_IOS_FETCH`, `KEY_CLIENT_ROTATION`, their `VALID_KEYS`/clear/diagnostics entries, `isIosFetchEnabled`, `isClientRotationEnabled`, `setIosFetchEnabled`, `setClientRotationEnabled`)
- Modify: `.../ui/settings/DeveloperSettingsDialog.kt:111-120` (delete the iOS-fetch toggle) and `res/values/strings.xml:603-604` (`dev_settings_ios_fetch_title/desc`; no other locale has them)
- Modify: `.../data/extractor/potoken/WebViewPoTokenProvider.kt:19-27,53`
- Modify: `.../data/extractor/StreamModels.kt:189` (KDoc only), `.../util/HttpConstants.kt:19-31` (KDoc only)
- Test: `android/app/src/test/.../player/PlaybackFeatureFlagsTest.kt`, `.../potoken/WebViewPoTokenProviderTest.kt:50,91,94`

**Interfaces:**
- Produces: `NewPipeExtractorClient(downloader, cache, metrics, featureFlags, poTokenProvider, clock…)`, the same constructor minus `clientRotator`. Every resolve returns `extractionClient = ExtractionClient.NEWPIPE_IOS`.

- [ ] **Step 1: Prove each deletion dead (record the output in the PR).**
  Run from `android/app/src`: `grep -rn "YoutubeClientRotator\|isClientRotationEnabled\|ENABLE_CLIENT_ROTATION\|isIosFetchEnabled\|setIosFetchEnabled\|ENABLE_NPE_IOS_FETCH\|KEY_IOS_FETCH\|dev_settings_ios_fetch\|getIosClientPoToken\|setFetchIosClient" .`
  Expected hits (2026-11-11 baseline):
  - the files listed above;
  - the test doubles `NoOpPoTokenProvider.kt:12` and `WebViewPoTokenProviderTest.kt`.

  Nothing in `play/` or `sideload/`. NewPipe 0.26.5 calls `getIosClientPoToken` only inside `if (fetchIosClient)` (javap of `YoutubeStreamExtractor.onFetchPage`, offsets 66–98). `PoTokenProvider` declares it **abstract**, so the override stays and returns `null`.
- [ ] **Step 2: Update the tests first.**
  - In `PlaybackFeatureFlagsTest`, delete `ios fetch uses build-time default…`, `ios fetch override to true…` and `ios fetch override to false…`. Remove the `ENABLE_NPE_IOS_FETCH`/`ios_fetch`/`client_rotation` references at `:56,:112,:126,:165`.
  - In `WebViewPoTokenProviderTest`, call `getAndroidClientPoToken` where it calls `getIosClientPoToken` (same `poTokenOrNull` path).
  - Add one assertion to `WebViewPoTokenProviderTest`: `assertNull(provider.getIosClientPoToken("vid"))`.
- [ ] **Step 3:** Run `GRADLE :app:testSideloadDebugUnitTest --tests '*WebViewPoTokenProviderTest*'`. Expected: FAIL on the new `assertNull`.
- [ ] **Step 4: Make the change.**
  - `WebViewPoTokenProvider.getIosClientPoToken` becomes `= null`. Rewrite KDoc lines 19–27 to say the app fetches no IOS client (40–50 s audio 403, probe 2026-11-11), so only the Android getter feeds NewPipe and the web getter feeds `DubAudioResolver`.
  - In `NewPipeExtractorClient`:
    - Delete `applyIosFetchSetting`, `applyClientSetting`, `toExtractionClient`, the `clientRotator` field, every `synchronized(NewPipeExtractorClient::class.java) { … }` apply block (`:139-147`, `:188-199`, `loadVideoMetadata :317-319`), the `nextClient` arm (`:226-228`) and the `clientRotator.reset` calls.
    - Pass `ExtractionClient.NEWPIPE_IOS` at both `toResolvedStreams` call sites.
    - In the init block, replace `applyIosFetchSetting()` (`:934-935`) with `YoutubeStreamExtractor.setFetchIosClient(false)` and a one-line comment pointing at the spec.
    - Keep the visitorData retry.
  - Update the KDocs:
    - `StreamModels.NEWPIPE_IOS`: "every NewPipe resolve (VISIONOS + ANDROID URLs); fetched with the iOS-app UA they were proven with, probe 2026-11-11".
    - `HttpConstants.YOUTUBE_IOS_USER_AGENT`: same fact.
- [ ] **Step 5: Verify.** Run `GRADLE :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest :app:assemblePlayRelease :app:assembleSideloadDebug`. Expected: BUILD SUCCESSFUL.
  Then from `android/app/src`: `grep -rn "setFetchIosClient" main`. Expected: exactly one hit, `(false)`.
  Then re-run the Step 1 grep. Expected: hits only in `WebViewPoTokenProvider.kt` (null override), `NoOpPoTokenProvider.kt`, `WebViewPoTokenProviderTest.kt` and the single `setFetchIosClient(false)`.
- [ ] **Step 6: Commit.** `[FIX]: Turn off NewPipe IOS client on Android`

### Task 3: A repeated mid-play 403 plays itag 18 before giving up

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/player/Forbidden403Recovery.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`:
  - `handle403OrHttpError`, the `URL_EXPIRED` arm (`~:2422-2440`) and the `UNKNOWN_403/HTTP_ERROR/NETWORK_ERROR` arm (`~:2443-2463`);
  - the sticky reset (`~:2881-2885`);
  - a new private helper next to `requestStreamRefreshAndResume` (`~:2141`).
- Test: `android/app/src/test/java/com/albunyaan/tube/player/Forbidden403RecoveryTest.kt`

**Interfaces:**
- Produces: `internal enum class Forbidden403Step { REFRESH, PLAY_MUXED, EXHAUSTED }` and `internal fun next403Step(refreshesThisEpisode: Int, maxRefreshes: Int, playingMuxed: Boolean, hasMuxed: Boolean): Forbidden403Step`.
  - `refreshesThisEpisode`: the fragment's `streamRefreshCount`, which resets when playback resumes.
  - `playingMuxed`: the prepared source is progressive (`!preparedIsAdaptive`).
  - `hasMuxed`: `(viewModel.state.value.streamState as? StreamState.Ready)?.selection?.resolved?.videoTracks?.any { !it.isVideoOnly } == true`.

- [ ] **Step 1: Write the failing tests.**
```kotlin
@Test fun `first 403 refreshes`() = assertEquals(REFRESH, next403Step(0, 2, playingMuxed = false, hasMuxed = true))
@Test fun `repeat 403 on adaptive plays muxed`() = assertEquals(PLAY_MUXED, next403Step(1, 2, false, true))
@Test fun `repeat 403 at the budget edge still plays muxed before exhausting`() = assertEquals(PLAY_MUXED, next403Step(2, 2, false, true))
@Test fun `403 while muxed refreshes then exhausts`() { assertEquals(REFRESH, next403Step(1, 2, true, true)); assertEquals(EXHAUSTED, next403Step(2, 2, true, true)) }
@Test fun `no muxed track keeps the refresh ladder`() { assertEquals(REFRESH, next403Step(1, 2, false, false)); assertEquals(EXHAUSTED, next403Step(2, 2, false, false)) }
```
- [ ] **Step 2:** Run `GRADLE :app:testSideloadDebugUnitTest --tests '*Forbidden403RecoveryTest*'`. Expected: FAIL (unresolved reference).
- [ ] **Step 3: Implement `next403Step`.** Order: first 403 with budget left → REFRESH; else not muxed and muxed available → PLAY_MUXED; else budget left → REFRESH; else EXHAUSTED. Put a KDoc on it citing the probe: a fresh extraction returns the identical stream set, so a second refresh can't help.
- [ ] **Step 4:** Run the Step 2 command. Expected: PASS (5 tests).
- [ ] **Step 5: Wire it into `PlayerFragment`.** Read `maybePrepareStream` `:2777-2920` first. The sticky flag `adaptiveFailedForCurrentStream` is read at `:2845`, then cleared at `:2882` whenever `preparedStreamKey` is null. Every refresh nulls that key, so today the flag never survives a refresh.
  1. Change the `:2882` condition to clear the flag only when `adaptiveFailedForCurrentStream != streamState.streamId`.
  2. Add `private fun playMuxedFromCurrentStreams(reason: String)`. It sets `adaptiveFailedForCurrentStream` to the current stream id, sets `pendingResumeStreamId/PositionMs/PlayWhenReady` from the player as `requestStreamRefreshAndResume` does (`:2195-2197`), nulls the same `prepared*`/`factorySelectedVideoTrack` fields (`:2208-2213`), calls `player.stop()`, then calls `maybePrepareStream(viewModel.state.value)`. It must not call `forceRefreshForAutoRecovery` (no extraction, no limiter). Log `reason`.
  3. In both 403 arms, replace the `if (currentStreamRefreshCount < maxStreamRefreshes) { … } else { exhausted }` with a `when (next403Step(...))`. REFRESH keeps the existing `onStreamRefresh()` + `requestStreamRefreshAndResume(...)`. PLAY_MUXED calls the helper without `onStreamRefresh()`. EXHAUSTED keeps the existing exhausted + toast. Leave the GEO, RATE_LIMITED and non-403 arms unchanged.
- [ ] **Step 6:** Run `GRADLE :app:testSideloadDebugUnitTest :app:assemblePlayRelease`. Expected: BUILD SUCCESSFUL. Review check (Review Focus 4): the flag still clears on a different `streamId` and on both manual retry buttons (`:608`, `:627`).
- [ ] **Step 7: Commit.** `[FIX]: Repeat 403 plays itag 18 before giving up`

### Task 4: Audio-only save of a muxed-only video stores audio

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/download/DownloadWorker.kt` (`resolveAndDownload`, `~:275-284`)
- Test: `android/app/src/test/java/com/albunyaan/tube/download/DownloadWorkerRangeTest.kt`

**Interfaces:**
- Consumes: `NewPipeExtractorClient.deriveFallbackAudioTracks(audioTracksRaw, videoTracks)` (companion, `internal`, `:1014`), and the existing `downloadMuxedFallback(url, downloadId, title, audioOnly)`.

- [ ] **Step 1: Write the failing test.**
  - Add `muxedOnly: Boolean = false` to `runDownload`. When it is true, the fake resolve returns `videoTracks = listOf(<itag 18 muxed VideoTrack, c=ANDROID>)` and `audioTracks = NewPipeExtractorClient.deriveFallbackAudioTracks(emptyList(), videoTracks)`. That is what an IOS-off kids resolve returns.
  - Add the test `an audio-only save of a muxed-only video extracts the audio`. It mirrors `an audio-only download that still 403s takes the audio out of itag 18` (`:483`): `answer = ::kidsCdn`, `runDownload(audioOnly = true, muxedOnly = true, merger = merger)`. Assert:
    - Success;
    - `assertArrayEquals(body, muxedInputs.single())`;
    - the file name ends `.m4a`;
    - the file bytes equal `audio`.
- [ ] **Step 2:** Run `GRADLE :app:testSideloadDebugUnitTest --tests '*DownloadWorkerRangeTest*'`. Expected: the new test FAILS (no `extractAudio` call; the MP4 is committed as-is).
- [ ] **Step 3: Fix `resolveAndDownload`.** After the resolve, when `audioOnly`, the stream is `ResolvedStream.Progressive`, and its `url == muxedFallbackUrl`, return `downloadMuxedFallback(url, downloadId, title, audioOnly = true)` (one guard).
- [ ] **Step 4:** Run the Step 2 command. Expected: every test PASSES, the old 403 fallback tests included.
- [ ] **Step 5: Commit.** `[FIX]: Audio-only save of itag 18 extracts audio`

### Task 5: Whole-branch verification and pipeline

- [ ] **Step 1:** Run `GRADLE :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest :app:bundlePlayRelease :app:assembleSideloadRelease`. Expected: BUILD SUCCESSFUL, 0 test failures. The suite must stay under 300 s wall and 30 s per method.
- [ ] **Step 2:** Run the Task 1 Step 3 probe once more against the branch's NewPipe jar (unchanged `v0.26.5`) and paste `run6.txt` into the PR. This is the spec's "Done when" evidence.
- [ ] **Step 3:** Run the 9-stage pipeline (`AGENTS.md` §Mandatory Post-Coding Review Pipeline), Cubic included. If any stage is skipped, say which one and why.
- [ ] **Step 4:** Merge to `main` and delete `fix/android-playback-stop`. Release 1.0.7 (versionCode, `releases-meta.json` en/ar/nl, CHANGELOG) belongs to spec §D, not this plan.

**Not in this plan:**
- The iOS InnerTubeKit re-probe (spec §C). iOS has no IOS-client rung (`StreamResolver.runRung`: `visionosHLS`, `androidItag18`, plus `tvSamsungAdaptive` for HD), so no iOS code change follows from it.
- An Android minimum-version floor. Nothing exists to raise; see Findings.
