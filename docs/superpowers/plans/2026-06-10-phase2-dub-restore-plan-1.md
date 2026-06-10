# Phase 2 Dub Restore — Implementation Plan 1 (through the feasibility gate)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the audio-language ("globe") dub picker on the **regular player**, proving on the emulator that a web-sourced dub audio track merges onto the untouched VR HD video and sustains — the one thing the off-device spike could not verify.

**Architecture:** VR HD video stays the playback spine (Android-UA, no pot, no nsig — unchanged). A cheap, deferred **enumerate** (MWEB innertube list, no pot/nsig) discovers which dubs exist → globe appears. On opt-in tap, a **resolve** (NewPipe web client; nsig + videoId-bound web pot handled by NewPipe + the existing `WebViewPoTokenProvider`) yields the chosen language's audio URL, built as its own web-UA `ProgressiveMediaSource` and combined via `MergingMediaSource(VR video, web dub audio)`. Any failure → revert to VR original. The default path gains zero new dependencies.

**Tech Stack:** Kotlin, Media3 1.10.1 (`MergingMediaSource`/`ProgressiveMediaSource`/`DashMediaSource`), NewPipeExtractor v0.26.2 (`YoutubeStreamExtractor.setFetchIosClient`/`setPoTokenProvider`, `PoTokenProvider.getWebClientPoToken`), Hilt, JUnit/Robolectric (JVM unit), emulator `127.0.0.1:44435`.

**Scope of THIS plan:** regular player only, through the emulator GO/NO-GO at the end of Phase D. **Out of scope → Plan 2 (post-gate):** Shorts (`PlayerBinder.switchAudioTrack`), mid-stream-403 revert hardening, dub-aware download interaction, full RTL/`sw600dp`/`sw720dp` QA matrix, the 7-stage review pipeline.

**Spike facts this plan builds on (see `memory/player-dubs-phase2-spike.md`):** MWEB lists all dubs with no pot/nsig; web-family dub audio sustains only with URL + videoId-bound GVS pot + nsig (all three); the app already implements `getWebClientPoToken` (videoId-bound) and NewPipe solves nsig internally. **Residual risk resolved at the Phase D gate:** that NewPipe's in-app web-client resolve actually produces a sustaining dub URL. If it does not, pivot note is in Phase C.

---

## File Structure

**Create:**
- `app/src/main/java/com/albunyaan/tube/data/extractor/DubAudioEnumerator.kt` — issues one MWEB innertube `/player` call, parses `adaptiveFormats[].audioTrack` → `List<DubLanguage>`. No pot/nsig/WebView. Single responsibility: *list which dub languages exist*.
- `app/src/main/java/com/albunyaan/tube/data/extractor/DubAudioResolver.kt` — resolves ONE chosen language's streamable audio URL via a NewPipe web-client extraction (under the global client lock). Single responsibility: *get a playable dub audio stream*.
- `app/src/test/java/com/albunyaan/tube/data/extractor/DubAudioEnumeratorTest.kt`
- `app/src/test/java/com/albunyaan/tube/data/extractor/DubAudioResolverTest.kt`
- `app/src/test/java/com/albunyaan/tube/player/DubMergeDecisionTest.kt`

**Modify:**
- `data/extractor/StreamModels.kt` — add `AudioTrackSource { VR_NATIVE, WEB_DUB }`; add `AudioTrack.source` (default `VR_NATIVE`); document that a `WEB_DUB` lazy entry may carry `url=""` until resolved.
- `data/extractor/ResolvedStreamsExt.kt` — no change to `availableAudioLanguages()` (lazy dubs ride in `audioTracks`); add a tiny helper `mergeDubLanguages(...)` if needed.
- `player/CronetDataSourceFactory.kt` — add `createForWebUA()` (mirror `createForIosUA`/`createForAndroidUA`).
- `player/SegmentDataSourceFactoryProvider.kt` — add `forWebDub()` returning the web-UA factory (uncached or cached — see Task D2).
- `player/DashSourceBuilder.kt` — when the resolved selection's single audio track is `source==WEB_DUB`, build `MergingMediaSource(vrVideoSource, webDubAudioSource[, subtitles])`.
- `data/extractor/NewPipeExtractorClient.kt` — (a) fire the deferred enumerate after a successful resolve; (b) host `DubAudioResolver`'s web-client extraction under the existing global client lock.
- `ui/player/PlayerViewModel.kt` — `selectAudioTrack`: when the pick is `WEB_DUB`, resolve the real URL (async, emit a brief loading state) before emitting `AudioTrackSwapReady`; on failure emit a revert-to-original.
- `ui/player/PlayerFragment.kt` — `AudioTrackSwapReady` handler: route a `WEB_DUB` selection through the new merge build instead of the single-MPD rebuild.

---

## Phase A — Data model: mark a track's source

### Task A1: `AudioTrackSource` enum + `AudioTrack.source` field

**Files:**
- Modify: `app/src/main/java/com/albunyaan/tube/data/extractor/StreamModels.kt:46-65` (AudioTrack), add enum near line 66.
- Test: `app/src/test/java/com/albunyaan/tube/data/extractor/StreamModelsAudioSourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamModelsAudioSourceTest {
    @Test fun audioTrack_defaults_to_vr_native() {
        val t = AudioTrack(url = "https://x", mimeType = "audio/mp4", bitrate = 1, codec = null)
        assertEquals(AudioTrackSource.VR_NATIVE, t.source)
    }

    @Test fun lazy_web_dub_entry_carries_language_without_url() {
        val dub = AudioTrack(
            url = "", mimeType = null, bitrate = null, codec = null,
            language = "ar", trackName = "Arabic",
            trackType = AudioTrackKind.DUBBED, source = AudioTrackSource.WEB_DUB
        )
        assertEquals(AudioTrackSource.WEB_DUB, dub.source)
        assertEquals("ar", dub.language)
        assertTrue(dub.url.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — `./gradlew :app:testDebugUnitTest --tests "*StreamModelsAudioSourceTest*"` → FAIL (unresolved `AudioTrackSource`, `source`).

- [ ] **Step 3: Implement** — in `StreamModels.kt`, add after `AudioTrackKind`:

```kotlin
/** Where an [AudioTrack] came from. WEB_DUB tracks are sourced from the YouTube web client
 *  (pot + nsig) and merged onto the VR video; a WEB_DUB entry may be a lazy placeholder
 *  (url == "") produced by enumeration and resolved to a real URL only when selected. */
enum class AudioTrackSource { VR_NATIVE, WEB_DUB }
```

and add the field to `AudioTrack` (last param, defaulted so all existing call sites compile unchanged):

```kotlin
    val trackType: AudioTrackKind? = null,
    /** Origin of this track; WEB_DUB rides a different client (UA + pot) than the VR video. */
    val source: AudioTrackSource = AudioTrackSource.VR_NATIVE
)
```

- [ ] **Step 4: Run test** → PASS. Also run `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL (defaulted field, no call-site breakage).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/albunyaan/tube/data/extractor/StreamModels.kt app/src/test/java/com/albunyaan/tube/data/extractor/StreamModelsAudioSourceTest.kt
git commit -m "[ANDROID-DUB-01]: Add AudioTrackSource to mark web dubs"
```

---

## Phase B — Enumerate: light up the globe

### Task B1: `DubAudioEnumerator` — parse MWEB audio languages (pure, JVM-tested)

**Files:**
- Create: `app/src/main/java/com/albunyaan/tube/data/extractor/DubAudioEnumerator.kt`
- Test: `app/src/test/java/com/albunyaan/tube/data/extractor/DubAudioEnumeratorTest.kt`

Design: the enumerator has (a) a pure `parseDubLanguages(playerResponseJson: String): List<DubLanguage>` that the test drives with a fixture, and (b) a `suspend fun enumerate(videoId): List<DubLanguage>` that does the MWEB POST via the injected `Downloader` and calls the parser. Only the parser is unit-tested (network is exercised at the emulator gate).

- [ ] **Step 1: Write the failing test** (fixture trimmed from the real MWEB response — two dubs + original):

```kotlin
package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Test

class DubAudioEnumeratorTest {
    private val twoDubs = """
      {"streamingData":{"adaptiveFormats":[
        {"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"en.4","displayName":"English original","audioIsDefault":true}},
        {"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"ar.3","displayName":"Arabic"}},
        {"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"fr.3","displayName":"French"}},
        {"itag":137,"mimeType":"video/mp4"}
      ]}}""".trimIndent()

    @Test fun parses_distinct_dub_languages_with_original_flag() {
        val langs = DubAudioEnumerator.parseDubLanguages(twoDubs)
        assertEquals(3, langs.size)
        assertEquals(setOf("en", "ar", "fr"), langs.map { it.languageCode }.toSet())
        assertEquals(true, langs.first { it.languageCode == "en" }.isOriginal)
        assertEquals(false, langs.first { it.languageCode == "ar" }.isOriginal)
    }

    @Test fun single_audio_track_returns_empty() {
        val one = """{"streamingData":{"adaptiveFormats":[
            {"itag":139,"mimeType":"audio/mp4","audioTrack":{"id":"en.4","displayName":"English original","audioIsDefault":true}}]}}"""
        assertEquals(emptyList<DubLanguage>(), DubAudioEnumerator.parseDubLanguages(one))
    }

    @Test fun no_audioTrack_metadata_returns_empty() {
        assertEquals(emptyList<DubLanguage>(),
            DubAudioEnumerator.parseDubLanguages("""{"streamingData":{"adaptiveFormats":[{"itag":139}]}}"""))
    }

    @Test fun malformed_json_returns_empty_not_throws() {
        assertEquals(emptyList<DubLanguage>(), DubAudioEnumerator.parseDubLanguages("not json"))
    }
}
```

- [ ] **Step 2: Run** → FAIL (`DubAudioEnumerator` unresolved).

- [ ] **Step 3: Implement** `DubAudioEnumerator.kt`. The `id` field is like `en.4`/`ar.3`; the language code is the part before the dot. `displayName` ending in "original" or `audioIsDefault==true` ⇒ original. Use `org.schabi.newpipe.extractor.utils.JsonUtils` / `com.grack.nanojson.JsonParser` (already on the classpath via NewPipe) to parse:

```kotlin
package com.albunyaan.tube.data.extractor

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonObject

data class DubLanguage(val languageCode: String, val displayName: String, val isOriginal: Boolean)

class DubAudioEnumerator(/* inject Downloader in Task B2 */) {
    companion object {
        fun parseDubLanguages(playerResponseJson: String): List<DubLanguage> = try {
            val root = JsonParser.`object`().from(playerResponseJson)
            val formats = root.getObject("streamingData")?.getArray("adaptiveFormats") ?: return emptyList()
            val byCode = LinkedHashMap<String, DubLanguage>()
            for (f in formats) {
                val at = (f as? JsonObject)?.getObject("audioTrack") ?: continue
                val id = at.getString("id") ?: continue
                val code = id.substringBefore('.').takeIf { it.isNotBlank() } ?: continue
                val display = at.getString("displayName") ?: code
                val isOriginal = at.getBoolean("audioIsDefault", false) ||
                    display.trim().endsWith("original", ignoreCase = true)
                byCode.putIfAbsent(code, DubLanguage(code, display, isOriginal))
            }
            if (byCode.size <= 1) emptyList() else byCode.values.toList()
        } catch (t: Throwable) { emptyList() }
    }
}
```

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/albunyaan/tube/data/extractor/DubAudioEnumerator.kt app/src/test/java/com/albunyaan/tube/data/extractor/DubAudioEnumeratorTest.kt
git commit -m "[ANDROID-DUB-02]: DubAudioEnumerator MWEB language parser"
```

### Task B2: MWEB network call in the enumerator

**Files:** Modify `DubAudioEnumerator.kt` (add `suspend fun enumerate(videoId): List<DubLanguage>`).

- [ ] **Step 1:** Add a constructor `Downloader` (NewPipe's `NewPipe.getDownloader()`), build the MWEB innertube POST exactly as the validated probe (`memory/player-dubs-phase2-spike.md` → `/tmp/probe_dubs.py`, MWEB context: `clientName "MWEB"`, `clientVersion "2.20250120.00.00"`, `X-Youtube-Client-Name: 2`, mobile web UA, `html5Preference HTML5_PREF_WANTS`), POST to `https://www.youtube.com/youtubei/v1/player?prettyPrint=false`, hand the body to `parseDubLanguages`. Wrap in try/catch → `emptyList()`. No poToken, no nsig.

```kotlin
    suspend fun enumerate(videoId: String): List<DubLanguage> = withContext(Dispatchers.IO) {
        try {
            val body = mwebPlayerBody(videoId)            // helper builds the JSON above
            val resp = downloader.post(PLAYER_URL, MWEB_HEADERS, body.toByteArray())
            parseDubLanguages(resp.responseBody())
        } catch (t: Throwable) { emptyList() }
    }
```

- [ ] **Step 2:** (no unit test for the network call — covered at the Phase B emulator check). Verify compile: `./gradlew :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 3: Commit** `[ANDROID-DUB-03]: DubAudioEnumerator MWEB request`.

### Task B3: Fire enumerate after resolve; merge lazy dubs into `audioTracks`

**Files:** Modify `data/extractor/NewPipeExtractorClient.kt` (post-resolve hook) and the state plumbing that feeds `PlayerViewModel`.

- [ ] **Step 1:** After a successful primary resolve returns a `ResolvedStreams` with `audioTracks.size <= 1`, launch the enumerate **off the hot path** (deferred; do not block first-frame). On result with ≥2 languages, produce lazy `AudioTrack`s:

```kotlin
fun List<DubLanguage>.toLazyAudioTracks(): List<AudioTrack> = map {
    AudioTrack(url = "", mimeType = null, bitrate = null, codec = null,
        language = it.languageCode, trackName = it.displayName,
        trackType = if (it.isOriginal) AudioTrackKind.ORIGINAL else AudioTrackKind.DUBBED,
        source = AudioTrackSource.WEB_DUB)
}
```

Merge them into the active `ResolvedStreams.audioTracks` (replace the VR single-original entry's language grouping is preserved: keep the VR original track as `VR_NATIVE`, append the non-original lazy dubs). `availableAudioLanguages()` then returns ≥2 → globe shows. Cache per videoId (reuse the existing 30-min stream cache keying or a small `ConcurrentHashMap`).

- [ ] **Step 2:** Decide the surface for "augment after the fact": emit an updated `StreamState.Ready` (the VM already supports re-emitting Ready — see `selectAudioTrack`/`updateState`). Wire the enumerate result to update `streamState.selection.resolved.audioTracks`.

- [ ] **Step 3: Emulator check B (globe visibility — NOT the go/no-go):**

```bash
cd android && ./gradlew :app:assembleDebug && adb -s 127.0.0.1:44435 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1:44435 shell am start -a android.intent.action.VIEW -d "albunyaantube://video/GpQSUjNsNm0"
adb -s 127.0.0.1:44435 logcat -s NewPipeExtractor PlayerViewModel | grep -i "dub\|audioTracks"
```
Expected: after first frame, globe button visible; a single-audio video (e.g. any music-only clip) → globe hidden. Tapping the globe lists the languages (selection not wired yet → no-op/graceful).

- [ ] **Step 4: Commit** `[ANDROID-DUB-04]: Deferred MWEB enumerate lights the globe`.

---

## Phase C — Resolve a chosen dub via NewPipe web client

### Task C1: `DubAudioResolver.selectAudioStream(...)` — pure selection logic

**Files:** Create `DubAudioResolver.kt` (+ test). Split the testable selection from the NewPipe network call.

- [ ] **Step 1: Write the failing test** — given a list of NewPipe-style audio entries (modeled as a small internal `data class DubStreamCandidate(languageCode, bitrate, url, mimeType)`), pick the highest-bitrate match for the requested language; null if none.

```kotlin
class DubAudioResolverTest {
    private val cands = listOf(
        DubStreamCandidate("ar", 49_000, "u-ar-lo", "audio/mp4"),
        DubStreamCandidate("ar", 129_000, "u-ar-hi", "audio/mp4"),
        DubStreamCandidate("fr", 129_000, "u-fr", "audio/mp4"),
    )
    @Test fun picks_highest_bitrate_for_language() =
        assertEquals("u-ar-hi", DubAudioResolver.selectAudioStream(cands, "ar")?.url)
    @Test fun returns_null_when_language_absent() =
        assertEquals(null, DubAudioResolver.selectAudioStream(cands, "de"))
}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** `selectAudioStream(candidates, lang) = candidates.filter { it.languageCode == lang }.maxByOrNull { it.bitrate }`. **Step 4: Run** → PASS. **Step 5: Commit** `[ANDROID-DUB-05]: DubAudioResolver stream selection`.

### Task C2: NewPipe web-client extraction under the global client lock

**Files:** Modify `NewPipeExtractorClient.kt` (host the call) + `DubAudioResolver.kt`.

- [ ] **Step 1:** Implement `suspend fun resolveDubAudio(videoId, languageCode): AudioTrack?`. Inside the **existing global client lock** used by `applyClientSetting` (so it can't race the main resolve):
  1. Save current `setFetchIosClient` state; set `setFetchIosClient(false)` (web base) and ensure `setPoTokenProvider(webViewPoTokenProvider)` is set.
  2. Fresh `YoutubeStreamExtractor` for the videoId; `fetchPage()`.
  3. From `getAudioStreams()`, map each to `DubStreamCandidate(audioLocale.toLanguageTag(), averageBitrate, content, format.mimeType)`; `selectAudioStream(.., languageCode)`.
  4. Return an `AudioTrack(url = chosen.url /* already nsig'd + pot'd by NewPipe */, mimeType, bitrate, codec=null, language=languageCode, source = WEB_DUB)`.
  5. Restore the prior `setFetchIosClient` state in a `finally`.
  Wrap in try/catch → null.

- [ ] **Step 2:** Verify compile. (Network correctness is proven at the Phase D gate.)

- [ ] **Step 3: Commit** `[ANDROID-DUB-06]: Resolve dub audio via NewPipe web client`.

> **PIVOT NOTE (only if the Phase D gate fails to sustain):** if NewPipe's in-app web resolve does not yield a sustaining URL, replace C2's body with the hand-rolled path proven in the spike — MWEB innertube resolve for the direct dub URL, then nsig via NewPipe's `YoutubeThrottlingParameterUtils`/`YoutubeJavaScriptPlayerManager`, then append `getWebClientPoToken(videoId).playerResponsePoToken` as `&pot=`. Same `AudioTrack` output, so Phase D is unaffected.

---

## Phase D — Merge web dub audio onto VR video + the emulator GO/NO-GO

### Task D1: `CronetDataSourceFactory.createForWebUA()`

**Files:** Modify `player/CronetDataSourceFactory.kt`.

- [ ] **Step 1:** Mirror `createForIosUA`/`createForAndroidUA`, using the mobile-web UA validated in the spike: `Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36`. **Step 2:** compile. **Step 3: Commit** `[ANDROID-DUB-07]: Web-UA Cronet factory`.

### Task D2: `SegmentDataSourceFactoryProvider.forWebDub()`

**Files:** Modify `player/SegmentDataSourceFactoryProvider.kt`.

- [ ] **Step 1:** Add `fun forWebDub(): DataSource.Factory` building `DefaultDataSource.Factory(context, CacheDataSource over cronetDataSourceFactory.createForWebUA())`. (Cache OK — dub audio is VOD; URLs carry their own expiry.) **Step 2:** compile. **Step 3: Commit** `[ANDROID-DUB-08]: forWebDub data source factory`.

### Task D3: Merge decision in `DashSourceBuilder.build()` (JVM-tested decision)

**Files:** Modify `player/DashSourceBuilder.kt`; Test `player/DubMergeDecisionTest.kt`.

- [ ] **Step 1: Write the failing test** — a pure helper `isWebDubMerge(resolved): Boolean` is true iff the single audio track is `WEB_DUB` with a non-empty url:

```kotlin
class DubMergeDecisionTest {
    private fun streams(audio: AudioTrack) = ResolvedStreams(
        streamId = "v", videoTracks = listOf(/* one VR video track */),
        audioTracks = listOf(audio), durationSeconds = 100)
    @Test fun web_dub_with_url_triggers_merge() {
        assertTrue(DashSourceBuilder.isWebDubMerge(streams(
            AudioTrack("https://dub","audio/mp4",129000,null,source=AudioTrackSource.WEB_DUB))))
    }
    @Test fun vr_native_audio_does_not_merge() {
        assertFalse(DashSourceBuilder.isWebDubMerge(streams(
            AudioTrack("https://vr","audio/mp4",129000,null))))
    }
    @Test fun lazy_unresolved_web_dub_does_not_merge() {
        assertFalse(DashSourceBuilder.isWebDubMerge(streams(
            AudioTrack("","audio/mp4",null,null,source=AudioTrackSource.WEB_DUB))))
    }
}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** the helper + wire `build()`:

```kotlin
companion object {
    fun isWebDubMerge(resolved: ResolvedStreams): Boolean {
        val a = resolved.audioTracks.singleOrNull() ?: return false
        return a.source == AudioTrackSource.WEB_DUB && a.url.isNotEmpty()
    }
}
```

In `build()`, when `isWebDubMerge(resolved)`: build the VR **video-only** DASH source with the existing `forStreams(resolved)` (Android UA) and a web dub audio `ProgressiveMediaSource` via `forWebDub()` over `mediaItem(resolved.audioTracks.single().url, resolved.audioTracks.single().mimeType)`, then `MergingMediaSource(vrVideo, webDubAudio, *subtitleSources)`. (Reuse the existing subtitle `SingleSampleMediaSource` merge code — it already wraps in `MergingMediaSource`.)

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `[ANDROID-DUB-09]: MergingMediaSource(VR video, web dub audio)`.

### Task D4: Route a `WEB_DUB` pick through resolve → merge (regular player)

**Files:** Modify `ui/player/PlayerViewModel.kt` (`selectAudioTrack`) and `ui/player/PlayerFragment.kt` (`AudioTrackSwapReady`, lines 1533-1571).

- [ ] **Step 1:** In `selectAudioTrack(track)`: if `track.source == WEB_DUB && track.url.isEmpty()` (lazy), launch a coroutine: emit a transient "resolving dub" UI state (reuse an existing loading affordance), call `dubAudioResolver.resolveDubAudio(streamId, track.language!!)`; on success emit `AudioTrackSwapReady` with the **resolved** track (real url, `WEB_DUB`); on null → toast + re-emit the original VR track (revert). For `VR_NATIVE` or "Original", keep today's behavior.
- [ ] **Step 2:** In the `AudioTrackSwapReady` handler: when `event.newSelection.audio.source == WEB_DUB`, do **not** take the single-MPD `filteredResolved` rebuild (lines 1564-1570). Instead build via the merge path (Task D3): set the selection so the VR video tracks are retained AND the single audio is the resolved web dub, then prepare through the same `handleLiveStreamRefresh`/prepare entry so position + playWhenReady are preserved. (The video tracks must NOT be filtered out — only the audio is the web dub.)
- [ ] **Step 3:** compile + existing player unit tests green: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** `[ANDROID-DUB-10]: Wire web-dub selection through resolve+merge`.

### Task D5: 🚦 EMULATOR GO/NO-GO — does the in-app web dub sustain?

- [ ] **Step 1:** Build + install:
```bash
cd android && ./gradlew :app:assembleDebug && adb -s 127.0.0.1:44435 install -r app/build/outputs/apk/debug/app-debug.apk
```
- [ ] **Step 2:** Open a known-dubbed video and switch to Arabic:
```bash
adb -s 127.0.0.1:44435 shell am start -a android.intent.action.VIEW -d "albunyaantube://video/GpQSUjNsNm0"
# tap globe → Arabic; then watch:
adb -s 127.0.0.1:44435 logcat -s PlayerFragment PlayerViewModel ExoPlayerImpl | grep -iE "403|dub|audioTrack|rebuild|position"
```
- [ ] **Step 3: PASS criteria:** audio switches to Arabic; playback **sustains past 2 minutes** with no 403 cliff; video stays the VR HD ladder (unchanged); the app's `403 Errors`/`MediaSource Rebuilds` metrics stay clean; picking "Original" reverts cleanly.
- [ ] **Step 4: Record the verdict** in `memory/player-dubs-phase2-spike.md` (append an "IN-APP GATE" section: pass → proceed to Plan 2; fail → execute the Task C2 PIVOT NOTE and re-run this gate).
- [ ] **Step 5: Commit** `[ANDROID-DUB-11]: Dub restore regular player — emulator-verified` (only if PASS; include the metric evidence in the message).

---

## Self-Review

- **Spec coverage:** §4.1 enumerate → Phase B; §4.2 stream/resolve → Phase C; §4.3 merge & switch → Phase D; §5 model → Phase A; §6 failure (enumerate-silent, resolve-null-revert) → B3/D4 (mid-stream-403 hardening explicitly deferred to Plan 2, matching this plan's stated scope); §7 JVM tests → A1/B1/C1/D3 + the Phase D emulator matrix item #1. Shorts (§ both-players) and the full QA matrix are **intentionally Plan 2** — called out in the header scope, not gaps.
- **Placeholder scan:** the Task C2 "PIVOT NOTE" is a contingency, not a placeholder — C2's primary path is fully specified. No TBD/TODO steps.
- **Type consistency:** `AudioTrackSource.{VR_NATIVE,WEB_DUB}`, `AudioTrack.source`, `DubLanguage(languageCode,displayName,isOriginal)`, `DubStreamCandidate(languageCode,bitrate,url,mimeType)`, `DashSourceBuilder.isWebDubMerge`, `SegmentDataSourceFactoryProvider.forWebDub`, `CronetDataSourceFactory.createForWebUA` — names are used identically across tasks.

## Plan 2 (written after the Phase D gate passes)
Shorts dub switch (`PlayerBinder.switchAudioTrack` + `ShortsPlayerFragment`), mid-stream-403 → revert-to-original hardening + one re-resolve on URL expiry, dub-aware download interaction, RTL Arabic + `sw600dp`/`sw720dp` placement QA, and the mandatory 7-stage review pipeline before landing on `develop`.
