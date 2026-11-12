# Unified Search (Part B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One search entry (Home's) on every former search screen of both apps, opening global search; results include inner videos of approved channels/playlists minus globally excluded ids, rank exact > close > related, and never show "0:00".

**Architecture:** Backend-only ranking/visibility changes inside the existing in-process Lucene `SearchIndex` + `InnerIndex` (no new infra, no per-query YouTube call). Unknown duration becomes `null` on the wire (DTO is `NON_NULL`, so the field is omitted), filled at query time from the crawled inner corpus and at the source by the existing paced video validation. Apps delete their tab/detail search code and show Home's magnifier button, which pushes the existing global search screen. Inner search enablement in prod is an ops step gated on measured headroom.

**Tech Stack:** Spring Boot 3 / Java 17 / Lucene / Firestore; Android Kotlin + Hilt + XML layouts; iOS SwiftUI + Swift Testing.

**Spec:** `docs/superpowers/specs/2026-11-11-sorting-search-playback-design.md` — section "B. One search bar, accurate results, real durations" and "Builder-check resolutions (2026-11-11)". Executors read both.

## Global Constraints

- Backend Gradle: `export JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home` (bare `~/.local/jdk` fails). Tests: `cd backend && ./gradlew test --tests '<FQCN>'`; full suite `./gradlew test`.
- Test timeout: 300 s wall, 30 s per method (AGENTS.md).
- iOS gate: from repo root `bash ios/scripts/test.sh`. **xcodebuild is serialized**: never two `test.sh`/`xcodebuild`/`screenshots.sh` runs at once, across all workers.
- **No Android SDK on this Mac.** Every Android task names its substitute proof (backend test / iOS twin / grep proof) run here, plus `cd android && ./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug` and the emulator screenshots, run on Farouq's home laptop. The implementer reports which ran; "compiles on laptop: pending" is an honest state, "done" is not.
- UI preservation: phone `layout/`, `layout-sw600dp/`, `layout-sw720dp/` and RTL Arabic. Same view ids in every variant. RTL: `layout_gravity="end"` / `gravity="end|center_vertical"`, never left/right.
- Hilt only; no service locators. No new DI bindings expected.
- i18n en/ar/nl: no new strings (reuse `@string/search`, iOS `"search"`). A key is deleted from `values/`, `values-ar/`, `values-nl/` only when **no Android and no iOS reader** remains (`grep -rn` both trees). After any Android `strings.xml` change run `python3 ios/scripts/convert-strings.py` and commit `ios/FitrahTube/Resources/Localizable.xcstrings`; `--check` must pass.
- Never music-video ids (e.g. `dQw4w9WgXcQ`) in tests, fixtures or probes; use catalog lecture ids.
- No task adds a NewPipe/YouTube call to any request path. The only YouTube traffic stays the paced `InnerCrawler` and the existing daily validation.
- Backend `GET /api/v1/content?q=` stays byte-for-byte (`PublicContentService.searchContent`): shipped app versions send it.
- Unknown duration = `null` (field omitted). `0` and negatives are never sent for a VOD. Exception kept as is: `ContentItemMapper.fromLive` sends `0` for a live (apps hide 0).
- Proven-unused code is deleted, not hidden; the commit body lists the `grep` that proves zero remaining readers.
- Ops (`search.inner.dir`, inner modes, `serveInner`) and prod probes are executed by the **main session** only (Tasks 0, 13, 14), never by an implementer. Kill switches stay: `touch <search.inner.dir>/STOP`, `PUT /api/admin/inner-search {"mode":"OFF","serveInner":false}`.
- Commit: `[PREFIX]: Description (≤50 chars)`, prefixes `[FEAT] [FIX] [REFACTOR] [PERF] [DOCS] [TEST] [CHORE]`. After coding, the 9-stage review pipeline (AGENTS.md) runs before deploy.
- No new `.md` files.
- Coordination: Part A (tab order) may touch `PublicContentService` and the apps' `ContentListViewModel`; rebase on it before Tasks 8 and 11 and keep its behaviour.

## Review Focus

1. **Legacy zeros already stored** (Firestore `Video.durationSeconds: 0`, old corpus lines `"d":0`, stream docs with `0`) must render no badge, exactly like `null`. Test: Task 1 `unknownDurationsAreNullOnEveryHitKind`.
2. **Videos-tab length filter with a null duration** (`PublicContentService.java:1326` unboxes `getDurationSeconds()`): must not 500; the video matches no length bucket. Test: Task 1 `aLengthFilterSkipsVideosWithUnknownDurationInsteadOfFailing`.
3. **One id, many paths**: an id excluded by source A but listed by source B, stored as a stream, approved individually, or pasted as a URL must be absent from every `/search` answer. Test: Task 3 `anIdExcludedByAnySourceIsHiddenOnEveryPath`.
4. **Exclusion edited after boot**: added → hidden on the next search after the `CatalogChanged` re-read; removed → visible again; no restart. Test: Task 3 `anExclusionTakesEffectOnTheNextSearchAndALiftedOneComesBack`.
5. **Inner not serving** (headroom gate failed, index unbuilt, closing): `/search` must answer exactly today's curated order. Test: Task 4 `withoutInnerTheCuratedOrderIsUnchanged`.

---

### Task 0: Measure prod headroom and duration gaps (main session, read-only)

**Files:** none (numbers go in the session report, not a file).

**Interfaces:** Produces the gate verdict Task 13 reads: PASS (enable inner) or FAIL (B ships without inner results; owner gets numbers + options).

- [ ] **Step 1: Server facts** over SSH (`~/.ssh/id_ed25519_fitrah`, host 72.60.179.47, user per the backend-prod-deploy memory):
  `free -m; nproc; df -h /opt/albunyaan /tmp; ps -o pid,rss,args -C java; grep -E 'search\.(index|inner)' /opt/albunyaan/application-prod.yml; grep -E 'search index (catalog|built)' /opt/albunyaan/logs/app.log | tail -2`
- [ ] **Step 2: Catalog volumes** from the public API (no auth): page `https://app.fitrahtube.com/api/v1/content?type=CHANNELS&limit=50&cursor=…` summing `videoCount`, and `type=PLAYLISTS` summing `itemCount`, each count capped at 10,000 (`InnerState.MAX_PER_SOURCE`); page `type=VIDEOS` counting items with `durationSeconds` absent or `0`.
- [ ] **Step 3: Report these numbers** (R = raw, P = projected):

| # | Number | Source |
|---|---|---|
| R1 | MemTotal, MemAvailable (MiB) | `free -m` |
| R2 | JVM `-Xmx`, RSS (MiB) | `ps` |
| R3 | Free disk (GiB) on the fs of `/opt/albunyaan` and of `search.index.base-dir` (or `/tmp` if unset) | `df -h` |
| R4 | Cores | `nproc` |
| R5 | Main index docs and bytes → bytes/doc | `search index built:` line (`streams=`, `bytes=`) + catalog line |
| R6 | Sources S = approved channels + playlists | API |
| R7 | D = Σ min(count, 10 000) | API |
| P1 | Inner index bytes ≈ D × R5 bytes/doc (upper bound: main docs also store a DTO JSON) | calc |
| P2 | FIRST_PASS requests = 2 × S; hours at 30 s (`CrawlPacer.FIRST_PASS_INTERVAL_MS`) | calc |
| P3 | FULL requests F = Σ (2 + ⌈max(0, min(c,10 000) − 100) / 100⌉); days at 15 s (`BASE_INTERVAL_MS`) = F × 15 / 86 400 | calc |
| R8 | Approved videos with no/0 duration, of total | API |

- [ ] **Step 4: Gate.** PASS only if all hold: **G1** R1.MemAvailable − P1 ≥ 1024 MiB (2× `InnerCrawler.MEMORY_FLOOR_MIB`); **G2** free disk (R3, the fs holding `search.inner.dir` and `search.index.base-dir`) ≥ 3 × P1 + 2 GiB (live + retired index during a rebuild swap + corpus); **G3** P3 days ≤ 30. Report each G with its numbers. FAIL → Task 13 is skipped, Task 14 runs without the inner probes, owner gets the table and options (bigger server = their cost).

---

### Task 1: Unknown duration is null end to end (backend)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/dto/ContentItemMapper.java:61`
- Modify: `backend/src/main/java/com/albunyaan/tube/search/InnerCorpus.java:45-47` (record `Item`)
- Modify: `backend/src/main/java/com/albunyaan/tube/search/UploadsFetcher.java:111`
- Modify: `backend/src/main/java/com/albunyaan/tube/search/InnerIndex.java:316`
- Modify: `backend/src/main/java/com/albunyaan/tube/search/SearchIndex.java` `innerDto` (~:797-806), `streamDto` (~:1249-1254)
- Modify: `backend/src/main/java/com/albunyaan/tube/service/ContentValidationService.java:1321`
- Modify: `backend/src/main/java/com/albunyaan/tube/service/ContentImportService.java:561-565`, `SimpleImportService.java:512-517`
- Modify: `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java:1323-1336` (`matchesLengthFilter`)
- Test: `dto/ContentItemMapperTest.java`, `search/UploadsFetcherTest.java`, `search/InnerCorpusTest.java`, `search/SearchIndexTest.java`, `service/PublicContentServiceSearchTest.java`, `service/ContentValidationServiceTest.java`, `service/ContentImportServiceTest.java`, `service/SimpleImportServiceTest.java` (all under `backend/src/test/java/com/albunyaan/tube/`)

**Interfaces:**
- Produces: `record InnerCorpus.Item(String id, String t, Integer d, Integer a, String c, String u)` — `d` null = unknown, omitted from the JSONL line (record is already `NON_NULL`). `ContentItemDto.getDurationSeconds()` is null for every unknown or ≤ 0 VOD duration. Rule used everywhere: a stored value `> 0` is a duration, anything else is unknown.

- [ ] **Step 1: Write the failing tests**

```java
// ContentItemMapperTest
@Test void fromVideo_sendsNoDurationWhenUnknownOrZero() {
    Video v = new Video(); v.setYoutubeId("aaaaaaaaaaa");
    assertNull(ContentItemMapper.fromVideo(v).getDurationSeconds());
    v.setDurationSeconds(0);   assertNull(ContentItemMapper.fromVideo(v).getDurationSeconds());
    v.setDurationSeconds(754); assertEquals(754, ContentItemMapper.fromVideo(v).getDurationSeconds());
}
// UploadsFetcherTest: replaces aLiveOrUnknownDurationIsZeroAndAMissingDateIsLeftOut
@Test void aLiveOrUnknownDurationIsNullAndAMissingDateIsLeftOut() {
    assertNull(UploadsFetcher.item(stream("kRNck4MdV2g", "Live", -1), false).d());
    assertNull(UploadsFetcher.item(stream("kRNck4MdV2g", "Live", 0), false).d());
    assertNull(UploadsFetcher.item(stream("kRNck4MdV2g", "Live", -1), false).a());
}
// InnerCorpusTest
@Test void anUnknownDurationIsLeftOffTheLineAndReadsBackNull() // write Item(d=null): the gunzipped line has no "\"d\""; read() returns d()==null
// SearchIndexTest (Review Focus 1)
@Test void unknownDurationsAreNullOnEveryHitKind()
// catalog videos: durationSeconds null and 0; streams: null and 0L; inner items: d null, d 0 (legacy line), d 600.
// search each title: getDurationSeconds() is null for all but the inner d=600 hit, which is 600.
// PublicContentServiceSearchTest (Review Focus 2), modelled on aLengthFilterRestrictsInTabSearchToTheMatchingVideos
@Test void aLengthFilterSkipsVideosWithUnknownDurationInsteadOfFailing()
// videos vShort(120), vNull(null), vZero(0); length "SHORT" → verify searchPage(..., Set.of("vShort"), -1); no exception
// ContentValidationServiceTest, next to validateVideo_whenFound_shouldRefreshMetadata
@Test void validateVideo_aZeroDurationNeverOverwritesAKnownOne() // stored 754, StreamDetailsDto duration 0 → saved video keeps 754
// ContentImportServiceTest / SimpleImportServiceTest
@Test void anImportWithoutADurationStoresNull() // source duration null → saved Video.getDurationSeconds() is null (was 0)
```

- [ ] **Step 2: Run to verify they fail**
Run: `cd backend && ./gradlew test --tests '*ContentItemMapperTest' --tests '*UploadsFetcherTest' --tests '*InnerCorpusTest' --tests '*SearchIndexTest' --tests '*PublicContentServiceSearchTest' --tests '*ContentValidationServiceTest' --tests '*ContentImportServiceTest' --tests '*SimpleImportServiceTest'`
Expected: the new tests FAIL (0 instead of null; NPE in the length filter); compile errors in tests passing `int` are fine to fix by autoboxing.

- [ ] **Step 3: Implement**
  - `ContentItemMapper.fromVideo`: `Integer durationSeconds = d != null && d > 0 ? d : null`.
  - `InnerCorpus.Item.d`: `int` → `Integer`; update the `d` javadoc ("null = unknown or live").
  - `UploadsFetcher.item`: `s.getDuration() > 0 ? (int) s.getDuration() : null`.
  - `InnerIndex.doc`: add the `d` StoredField only when `item.d() != null`.
  - `SearchIndex.innerDto`: duration = stored `d` if present and `> 0`, else null. `streamDto`: `s.getDurationSeconds() != null && > 0 ? intValue : null`. `collectInner`'s Shorts test (`seconds > 0 && <= 60`) is unchanged.
  - `refreshVideoMetadata`: `dto.getDuration() != null && dto.getDuration() > 0`.
  - Import services: drop the `else video.setDurationSeconds(0)` branches.
  - `matchesLengthFilter`: unknown (null or ≤ 0) → `false` for any non-blank length.

- [ ] **Step 4: Run the Step 2 command, then the whole suite** `./gradlew test`. Expected: PASS.

- [ ] **Step 5: Commit** `[FIX]: Send unknown durations as null, not 0`

---

### Task 2: Search hits take their inner copy's duration

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/search/SearchIndex.java` `withInner` (~:620-647) and `originalTitles` (~:745-765)
- Test: `backend/src/test/java/com/albunyaan/tube/search/SearchIndexTest.java`

**Interfaces:**
- Consumes: Task 1's `Item.d` nullable; existing `innerCopy(IndexSearcher, String id, String preferredKey)`.
- Produces: no new public API. Every VIDEO item in a served `/search` answer whose `durationSeconds` is null takes the `d` (> 0) of its inner copy. Zero YouTube calls (the corpus is what the paced crawler already fetched).

- [ ] **Step 1: Write the failing tests**

```java
@Test void aStoredStreamWithoutADurationTakesItsInnerCopysDuration()
// stream "live-kahf" (durationSeconds null) under ALAFASY; inner item("live-kahf", ..., 600) → hit's getDurationSeconds() == 600
@Test void aCatalogVideoWithoutADurationTakesItsInnerCopysDuration()
// alafasy(inner) with vKahf duration null; inner item("vKahf", "Surah Al-Kahf", 1500) → 1500; a vKahf with 900 stored keeps 900
@Test void durationFillsShareTheTitleBudgetAndStopWhenItRunsOut()
// newTimeout = scripted(1, false): the first lookup is refused → durations stay null, the search still answers, slow line shows ":timeout" on titles
```

- [ ] **Step 2: Run** `./gradlew test --tests '*SearchIndexTest'` — Expected: the three FAIL.

- [ ] **Step 3: Implement** by widening the existing Q17 pass, not adding a second one: `withInner` passes `originalTitles` every answer item that is a stored stream **or** a VIDEO with null `durationSeconds` (inner items already carry theirs). In the loop, the title swap stays stored-streams-only; the duration is set from the found copy's `d` when the item has none. One budget (`innerTimeoutMs`), one lookup per item, same `trace.titles*` counters. Rename nothing.

- [ ] **Step 4: Run** `./gradlew test --tests '*SearchIndexTest'` — Expected: PASS.

- [ ] **Step 5: Commit** `[FEAT]: Fill missing search durations from inner`

---

### Task 3: An id excluded under any source never shows in search

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/search/SearchIndex.java` — `collect` (~:1079-1110), `collectInner` (~:703-740), `innerCopy` (~:783-795), plus one helper
- Test: `backend/src/test/java/com/albunyaan/tube/search/SearchIndexTest.java`

**Interfaces:**
- Produces: `private boolean excludedAnywhere(String id)` in `SearchIndex` — true when any `admittedSources` value's `excluded()` contains `id`. Read at query time from the live map, so the existing `CatalogChanged` re-read (`applyCatalogChange` → `admittedSources.putAll`) makes an edit effective on the next search. `titles()` and Home/tabs are untouched.

- [ ] **Step 1: Write the failing tests**

```java
@Test void anIdExcludedByAnySourceIsHiddenOnEveryPath()   // Review Focus 3
// channel UCalafasy excludes "vKahf"; playlist PLtafsir excludes nothing but its inner corpus lists "vKahf";
// vKahf is ALSO an approved catalog Video and a stored stream under the playlist.
assertFalse(ids(index.search("Surah Al-Kahf", null, 50)).contains("vKahf"));
assertEquals(SearchIndex.Hits.NONE, index.byId("vKahf", null));
assertFalse(ids(index.searchPage("Surah Al-Kahf", "VIDEO", 50, null, -1)).contains("vKahf")); // in-tab path too

@Test void anExclusionTakesEffectOnTheNextSearchAndALiftedOneComesBack()   // Review Focus 4
// saved(channel with excludedItems.videoIds += "lesson00001"); index.awaitWrites(); search "Zubdahnoor" → absent;
// saved(channel with the exclusion removed); awaitWrites(); search → present
```

- [ ] **Step 2: Run** `./gradlew test --tests '*SearchIndexTest'` — Expected: FAIL (catalog copy and other-source copies still shown).

- [ ] **Step 3: Implement** `excludedAnywhere(id)` and skip such hits (counting `trace.hidden`) in `collect`, `collectInner` and `innerCopy`, before `keeper`. Leave `keeper`'s per-source check in place (it still picks which source keeps a visible copy).
  Comment: `// ponytail: O(admitted sources) per hit, fresh on every edit; keep a union set updated in applyCatalogChange/replaceCatalog if the slow-search line ever shows it.`

- [ ] **Step 4: Run** `./gradlew test --tests '*SearchIndexTest'` — Expected: PASS.

- [ ] **Step 5: Commit** `[FIX]: Hide globally excluded ids from search`

---

### Task 4: Inner hits interleave by tier (exact > close > related)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/search/SearchIndex.java` — `record Ladder` (~:879), `ladder` (~:845-877), `merged` (~:650-662), `innerLadder` call
- Test: `backend/src/test/java/com/albunyaan/tube/search/SearchIndexTest.java`

**Interfaces:**
- Produces: `private record Ladder(List<ContentItemDto> items, String match, List<String> tokens, int upToTier1, int upToTier2)`. Answer order: C₁ ++ I₁ ++ C₂ ++ I₂ ++ C₃, cut to `want`, where Cₙ = curated hits of tier n (today's order inside a tier, curated before inner within the same tier) and Iₙ = inner hits of tier n (`innerLadder`, unchanged: tiers 1–2, longer before Shorts, 40 ms budget). `match` = the tier name of the answer's first item; `"none"` if empty. This replaces the old §7.1 rule "every curated tier ≤ 2 hit stays ahead of every inner hit" (owner rule 2026-11-11: exact title > close > related).

- [ ] **Step 1: Write the failing tests**

```java
@Test void anInnerExactTitleBeatsACuratedApproximateHit()
// catalog video vZubdahnur "Zubdahnur lesson" (tier 2 for query "zubdahnoor lesson"), inner item lesson00001 "Zubdahnoor lesson" (tier 1)
assertEquals(List.of("lesson00001", "vZubdahnur"), ids(index.search("zubdahnoor lesson", null, 50)).subList(0, 2));
assertEquals("exact", index.search("zubdahnoor lesson", null, 50).match());
@Test void aCuratedExactHitStaysAheadOfAnInnerExactHit()   // same tier: curated first
@Test void withoutInnerTheCuratedOrderIsUnchanged()       // Review Focus 5
// same catalog with inner == null, and with innerStore.state().serveInner = false: ids() equal the plain ladder's, match unchanged
```

- [ ] **Step 2: Run** `./gradlew test --tests '*SearchIndexTest'` — Expected: the first FAILS (inner hit placed after the curated tier-2 hit). Existing tests asserting the old §7.1 order fail too: update each to the new order and say so in the commit body; never touch a test that runs without inner.

- [ ] **Step 3: Implement**: `ladder` records `upToTier1` like `upToTier2`; `innerLadder` tags each hit with its tier (already `Tiered`); `merged` asks `innerLadder` for `need = want − upToTier1` and builds the interleave above. `withInner`'s early returns (not serving, CHANNEL/PLAYLIST type, no tokens, any failure) still return the plain ladder.

- [ ] **Step 4: Run** `./gradlew test --tests '*SearchIndexTest' --tests '*InnerSearchIT'` — Expected: PASS.

- [ ] **Step 5: Commit** `[FEAT]: Rank inner hits by tier with curated ones`

---

### Task 5: Relevance fixture from real catalog titles

**Files:**
- Create: `backend/src/test/resources/search/relevance-fixture.json`
- Modify: `backend/src/test/java/com/albunyaan/tube/search/SearchIndexTest.java` (one `@ParameterizedTest` + loader; reuses `newIndex`/`inner()` helpers)
- Modify (only if a case fails for a real ranking reason): `backend/src/main/java/com/albunyaan/tube/search/SearchText.java`

**Interfaces:**
- Consumes: Tasks 3–4 ordering.
- Produces: fixture shape
```json
{"channels":[{"youtubeId":"UC…","name":"…"}],
 "playlists":[{"youtubeId":"PL…","title":"…"}],
 "videos":[{"youtubeId":"…","title":"…","channelTitle":"…"}],
 "inner":{"channel:UC…":[{"id":"…","t":"…","d":1234}]},
 "cases":[{"query":"…","top":["id1","id2"],"absent":["id3"],"rule":"exact|close|related|prefix|arabic-fold|transliteration|inner-exact"}]}
```

- [ ] **Step 1: Build the fixture** from the live public API only (`https://app.fitrahtube.com/api/v1/content?type=CHANNELS|PLAYLISTS|VIDEOS&limit=50`, `/api/v1/channels/{id}` for a few uploads to use as inner items). Real ids and titles, **no music videos**, ≥ 40 items, ≥ 16 cases, ≥ 8 Arabic and ≥ 8 English/Dutch. Cases must cover: exact full title first; a one-letter typo (close); a partial phrase (related ranks after exact/close); prefix while typing; Arabic folding (tashkeel, `أ/إ/آ→ا`, `ة/ه`, `ى/ي`, with/without `ال`); transliteration variant (`tafsir/tafseer`); an inner-only exact title beating a curated close match; an excluded id absent (`"absent"`, optional on every case). `top` lists only the ids whose order the rule fixes (1–3).
- [ ] **Step 2: Write the test**
```java
@ParameterizedTest(name = "{0} [{2}]") @MethodSource("relevanceCases")
void realCatalogQueriesRankAsYouTubeWould(String query, List<String> top, String rule) throws Exception {
    assertEquals(top, ids(relevanceIndex.search(query, null, 50)).subList(0, top.size()));
}
```
  (plus the `absent` assertion for cases that carry it). `relevanceIndex` is built once per class from the fixture with `newIndex(...)` + `inner(...)`.
- [ ] **Step 3: Run** `./gradlew test --tests '*SearchIndexTest'`. Each failure is classified in the commit body: (a) the expectation broke the rule exact > close > related → fix the fixture; (b) the ranking is wrong → fix the root cause in `SearchText` tiers/analyzers (no per-query special cases, no external service). Re-run `--tests '*SearchTextTest' --tests '*SearchIndexTest'` after any `SearchText` change.
- [ ] **Step 4: Commit** `[TEST]: Pin search relevance on real titles` (+ `[FIX]: …` separately if `SearchText` changed)

---

### Task 6: Daily validation backfills missing durations first

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/ContentValidationService.java:933-975` (`getVideosForValidation`), `:1357-1368` (`fetchApprovedVideos`)
- Test: `backend/src/test/java/com/albunyaan/tube/service/ContentValidationServiceTest.java`

**Interfaces:**
- Consumes: Task 1's "> 0 is a duration" rule.
- Produces: same signature `List<Video> getVideosForValidation(int limit)`. Mirrors the playlist precedent (`getPlaylistsForValidation`, broken thumbnails jump the queue capped at half): read every approved video once per run; eligible videos (existing 24 h / ARCHIVED / UNAVAILABLE rules) with no duration (null or ≤ 0) and status ≠ ERROR jump the oldest-first queue, capped at `limit / 2`; the rest fill oldest-first. The YouTube budget stays `limit` (default 20/run, `app.validation.video.max-items-per-run`) through the existing `batchValidateVideosDtoWithDetails`: no new request type, no burst.

- [ ] **Step 1: Write the failing tests** (model: `validatePlaylists_brokenThumbnailsFirst_withinCap`, `validatePlaylists_brokenFirstCappedAtHalf`, `validatePlaylists_brokenRowRespects24hSkip`)
```java
@Test void validateVideos_missingDurationsFirst_withinCap()
// limit 4; 6 approved videos, two newest-validated lack durations → both are in the batch ahead of the oldest-first ones
@Test void validateVideos_missingDurationsCappedAtHalf()      // 5 lacking, limit 4 → exactly 2 of them
@Test void validateVideos_missingDurationRespects24hSkipAndError() // validated 1 h ago, or ERROR → no jump
```
- [ ] **Step 2: Run** `./gradlew test --tests '*ContentValidationServiceTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** in `getVideosForValidation`; `fetchApprovedVideos` reads all approved (`Integer.MAX_VALUE`, as `fetchApprovedPlaylists`). Comment: `// ponytail: one doc read per approved video per run; query a missing-duration flag instead if the catalog reaches tens of thousands.`
- [ ] **Step 4: Run** the Step 2 command, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[FEAT]: Validate videos missing durations first`

---

### Task 7: Android hides the duration badge when unknown

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/util/DurationFormat.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/util/DurationFormatTest.kt`
- Modify: `ui/SearchResultsAdapter.kt:171,212-220`, `ui/adapters/VideoGridAdapter.kt:46,85-…`, `ui/adapters/HomeFeaturedAdapter.kt:183,189,198-…`, `ui/adapters/FeaturedListAdapter.kt:169,203-…` (under `android/app/src/main/java/com/albunyaan/tube/`)

**Interfaces:**
- Produces: `object DurationFormat { fun badge(seconds: Int?): String? }` — null for null or ≤ 0, else `m:ss` / `h:mm:ss` with `Locale.US` (the adapters' current format). Domain `ContentItem.Video.durationSeconds` stays `Int` with 0 = unknown (`ApiMappers.kt:44` `?: 0` unchanged; `PlayerViewModel` already reads ≤ 0 as unknown): the wire is null, the domain type change would ripple through ~40 files for no behaviour gain.

- [ ] **Step 1: Write the failing test**
```kotlin
@Test fun `unknown or zero has no badge`() { assertNull(DurationFormat.badge(null)); assertNull(DurationFormat.badge(0)); assertNull(DurationFormat.badge(-1)) }
@Test fun `minutes and hours format as before`() { assertEquals("0:42", DurationFormat.badge(42)); assertEquals("12:34", DurationFormat.badge(754)); assertEquals("1:02:03", DurationFormat.badge(3723)) }
```
- [ ] **Step 2 (laptop):** `./gradlew :app:testPlayDebugUnitTest --tests '*DurationFormatTest'` — Expected: FAIL (unresolved).
- [ ] **Step 3: Implement** `DurationFormat.badge`; each of the four adapters sets `videoDuration.text = badge ?: ""` and `videoDuration.isVisible = badge != null` (as `PlaylistVideosAdapter.kt:64-65` already does); HomeFeaturedAdapter's content description omits the duration segment when null. Delete the four adapters' now-unused private `formatDuration`.
- [ ] **Step 4: Verify.** Here: `grep -n "fun formatDuration" ` in the four files → none. Laptop: `./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug` PASS; emulator Home + Search with a null-duration item show no chip (phone, sw600dp, sw720dp, ar RTL).
- [ ] **Step 5: Commit** `[FIX]: Android hides unknown duration badges`

---

### Task 8: Android tabs show Home's search button; tab search deleted

**Files:**
- Modify: `android/app/src/main/res/layout/fragment_channels_new.xml`, `layout/fragment_simple_list.xml` (no sw600dp/sw720dp variants exist; confirm with `ls res/layout*/fragment_channels_new.xml res/layout*/fragment_simple_list.xml`)
- Modify: `ui/ChannelsFragmentNew.kt:68-98`, `ui/PlaylistsFragmentNew.kt:68-97`, `ui/VideosFragmentNew.kt:85-114` (+ their `isSearchActive` empty-state branches)
- Modify: `ui/ContentListViewModel.kt` (search state), `data/source/ContentService.kt:29-35`, `data/source/RetrofitContentService.kt:22-…`, `data/source/api/ContentApi.kt:25`, `android/app/src/test/java/com/albunyaan/tube/data/source/FakeContentService.kt`
- Test: `android/app/src/test/java/com/albunyaan/tube/ui/ContentListViewModelTest.kt`

**Interfaces:**
- Consumes: Home's button, `layout/fragment_home_new.xml:56-66`: `ImageButton` `@dimen/touch_target_min` square, `?attr/selectableItemBackgroundBorderless`, `@drawable/ic_search`, `@string/search`, `app:tint="@color/primary_green"`; click = `findNavController().navigate(R.id.searchFragment)` (`HomeFragment.kt:203-205`; `searchFragment` is in `main_tabs_nav.xml`).
- Produces: in both layouts the `searchBar` container keeps its id and padding, holds only `ImageButton android:id="@+id/searchButton"` (Home's attributes), `android:gravity="end|center_vertical"`. `ContentService.fetchContent(type, cursor, pageSize, filters): CursorResponse` (no `query`). `ContentListViewModel` keeps load/refresh/loadMore/filters; no search state.

- [ ] **Step 1: Delete tab search, test-first.** Delete from `ContentListViewModelTest.kt` every test that exercises `setSearchQuery`/typing/debounce/`isSearchActive` (the block at ~:471-900; list each deleted name in the commit body). Add:
```kotlin
@Test fun `a tab fetch never sends a query`() = runTest { /* load(), loadMore(), refresh() → FakeContentService records only 4-arg fetchContent calls */ }
```
- [ ] **Step 2: Implement.** Delete `_searchQuery`, `searchQuery`, `isSearchActive`, `setSearchQuery`, `shownQuery`, `TYPING_DEBOUNCE_MS`, `ContentState.Success.isSearchActive` and any branch/field left only serving them (e.g. `fetching`'s query half, `keepListOnScreen` if only a query change used it); drop the `query` arg from every `fetchContent` call and from `ContentService`, `RetrofitContentService`, `FakeContentService`, `ContentApi.fetchContent` (`@Query("q")` at :25 only; `/search`'s at :33 stays). Fragments: delete `setupSearch()` and the TextWatcher imports; add `binding?.searchButton?.setOnClickListener { findNavController().navigate(R.id.searchFragment) }`; empty state keeps only the non-search branch; `swipeRefresh.isEnabled` no longer depends on search. Layouts: replace `searchInputLayout`/`searchEditText`/`searchClearButton` with the button. Delete string keys left with no reader on either platform (check `search_hint`, `search_clear`, `search_try_different_hint`; `search_no_results` is still read by iOS/SearchFragment — keep), then `python3 ios/scripts/convert-strings.py`.
- [ ] **Step 3: Verify.** Here: `grep -rn "setSearchQuery\|isSearchActive\|TYPING_DEBOUNCE_MS\|searchEditText" android/app/src/main/java/com/albunyaan/tube/ui/{ChannelsFragmentNew,PlaylistsFragmentNew,VideosFragmentNew,ContentListViewModel}.kt` → none; `python3 ios/scripts/convert-strings.py --check` PASS; backend `PublicContentServiceSearchTest` unchanged and green (q= path untouched). Laptop: `./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug` PASS; emulator Channels/Playlists/Videos: button end-aligned, tap opens Search, back returns, pagination autofill still fires on sw720dp; ar RTL button on the left edge.
- [ ] **Step 4: Commit** `[REFACTOR]: Android tabs use Home's search button`

---

### Task 9: Android detail pages show Home's search button; local filters deleted

**Files:**
- Modify: `res/layout/fragment_channel_detail.xml`, `res/layout-sw600dp/fragment_channel_detail.xml`, `res/layout-sw720dp/fragment_channel_detail.xml`, `res/layout/fragment_playlist_detail.xml`, `res/layout-sw600dp/fragment_playlist_detail.xml`, `res/layout-sw720dp/fragment_playlist_detail.xml`
- Modify: `ui/detail/ChannelDetailFragment.kt:94,102,112-141,502,522`, `ui/detail/ChannelDetailViewModel.kt:94-98` (+ `setSearchQuery`)
- Modify: `ui/detail/tabs/BaseChannelListTabFragment.kt:267-290`, `ChannelVideosTabFragment.kt:84-86`, `ChannelPlaylistsTabFragment.kt:58-60`, `ChannelLiveTabFragment.kt:80-82`, `ChannelShortsTabFragment.kt:242-250`
- Modify: `ui/detail/PlaylistDetailFragment.kt:120,128,201-230,327-346,775,787`, `ui/detail/PlaylistDetailViewModel.kt:64-72`
- Test: existing tests under `android/app/src/test/java/com/albunyaan/tube/ui/detail/` that reference the deleted members

**Interfaces:**
- Consumes: Task 8's button spec (same attributes, id `searchButton`, `R.id.searchFragment`). The channel page button is NOT scoped: it opens the same global search.
- Produces: no search state in `ChannelDetailViewModel` / `PlaylistDetailViewModel`; tab fragments observe `getState()` directly.

- [ ] **Step 1: Tests.** Delete tests of the local filter (list names in the commit body); keep every pagination/empty/error test green.
- [ ] **Step 2: Implement.** In all six layouts the `searchBar` container keeps its id and holds only `searchButton` (`gravity="end|center_vertical"`); fragments wire the click as in Task 8 and drop `searchHandler`, `SEARCH_DEBOUNCE_MS`, `setupSearch()`; `BaseChannelListTabFragment.observeState` collects `getState()` without `combine`; delete `matchesQuery` and its three overrides; Shorts and Playlist detail collect their state without the query `combine`; delete the VMs' `_searchQuery`/`searchQuery`/`isSearchActive`/`setSearchQuery`. Same string-key rule as Task 8.
- [ ] **Step 3: Verify.** Here: `grep -rn "searchQuery\|matchesQuery\|searchEditText\|SEARCH_DEBOUNCE_MS" android/app/src/main/java/com/albunyaan/tube/ui/detail` → none; `grep -c 'android:id="@+id/searchButton"' android/app/src/main/res/layout*/fragment_{channel,playlist}_detail.xml` → 1 each (6 files). Laptop: unit tests + `assemblePlayDebug` PASS; emulator channel + playlist pages on phone/sw600dp/sw720dp/ar: header intact, `fitsSystemWindows` unchanged, button opens global search.
- [ ] **Step 4: Commit** `[REFACTOR]: Android detail pages use global search`

---

### Task 10: iOS treats a zero duration as unknown

**Files:**
- Modify: `ios/FitrahTube/Catalog/LiveCatalogClient.swift:129`
- Test: `ios/FitrahTubeTests/LiveCatalogClientTests.swift`

**Interfaces:**
- Produces: `ContentItem.durationSeconds` is nil for absent, `0` or negative wire values, so `Components.swift:354/421/475` (`if let seconds`) and `videoAccessibilityLabel` (:32) hide the chip/segment with no change there. Live items (`liveChannelId`/`liveLink`) are told apart by those fields, never by `durationSeconds == 0` (grep shows no such reader).

- [ ] **Step 1: Write the failing test**
```swift
@Test func aZeroOrMissingDurationMapsToNil() async throws {
    // stub page: [{"id":"v0","type":"VIDEO","durationSeconds":0},{"id":"vn","type":"VIDEO"},{"id":"v1","type":"VIDEO","durationSeconds":754}]
    #expect(items.map(\.durationSeconds) == [nil, nil, 754])
}
```
  Update the existing live fixtures at `LiveCatalogClientTests.swift:242,259` only if they assert `durationSeconds == 0`.
- [ ] **Step 2: Run** `bash ios/scripts/test.sh` — Expected: the new test FAILS.
- [ ] **Step 3: Implement** `durationSeconds: dto.durationSeconds.flatMap { $0 > 0 ? $0 : nil }`.
- [ ] **Step 4: Run** `bash ios/scripts/test.sh` — Expected: PASS (both destinations).
- [ ] **Step 5: Commit** `[FIX]: iOS hides zero durations`

---

### Task 11: iOS tabs show Home's search button; tab search deleted

**Files:**
- Modify: `ios/FitrahTube/DesignSystem/Components.swift` (add `SearchButton`), `ios/FitrahTube/Features/Home/HomeView.swift:114-120`
- Modify: `ios/FitrahTube/Features/Lists/ContentListView.swift:32,42-75,110-130,152-176,238-275,380`, `ios/FitrahTube/Features/Lists/ContentListViewModel.swift`
- Modify: `ios/FitrahTube/Catalog/CatalogClient.swift:7`, `LiveCatalogClient.swift:38-55`, `FakeCatalogClient.swift:70`, every other `CatalogClient` conformer and `.content(type:` caller (11 sites incl. tests)
- Test: `ios/FitrahTubeTests/ContentListViewModelTests.swift`, `ios/FitrahTubeTests/LayoutRulesTests.swift` (if it asserts the tab header)

**Interfaces:**
- Produces: `struct SearchButton: View` — Home's exact button (magnifier 20 pt, `Color.brand`, 48×48 frame, `accessibilityLabel(String(localized: "search"))`) reading `@Environment(\.router)` and calling `router.push(.search)`. HomeView's header uses it (pixel-identical). `CatalogClient.content(type:cursor:limit:filter:) async throws -> CursorPage<ContentItem>` (no `query:`). `ContentListViewModel.State.content(items:hasMore:paginationError:)`.

- [ ] **Step 1: Tests.** Delete the query/debounce/submit tests (`ContentListViewModelTests.swift` ~:274-905: `queryBelowTwoCharsOmitsQParam` … `aNewQuerysFirstPageIsAnnouncedWithItsCount`; list names in the commit body). Add:
```swift
@Test func aTabFetchNeverSendsAQuery() async { /* load, loadMore, refresh: the recording catalog saw only the 4-argument content(...) */ }
```
- [ ] **Step 2: Implement.** `ContentListView.searchBar` becomes `HStack { Spacer(minLength: 0); SearchButton() }.padding(.horizontal, Spacing.md(widthClass))`; delete `queryBinding`, `showsSearchingIndicator`, the searching spinner, `searchEmptyState`, the `itemsQuery` scroll-to-top and the query-guard `onChange`s. VM: delete `query`, `submit()`, `search()`, `searchTask`, `isSearching`, `itemsQuery`, `loadQuery`, `queryParam`, the `isSearchActive` associated value and any field left serving only them. Drop `query:` from the protocol, live/fake clients and every caller. Keep `SearchField` (SearchView uses it). Same string-key rule (Global Constraints), checking `cd_search_icon`, `search_loading`, `search_try_different_hint`.
- [ ] **Step 3: Verify.** `grep -rn "query:" ios/FitrahTube/Catalog/CatalogClient.swift` → only `search(query:)`; `bash ios/scripts/test.sh` PASS; `python3 ios/scripts/convert-strings.py --check` PASS. Then `ios/scripts/screenshots.sh "iPhone 17"` and an iPad run: Channels/Playlists/Videos tabs show the button, ar RTL mirrored, tap pushes Search.
- [ ] **Step 4: Commit** `[REFACTOR]: iOS tabs use Home's search button`

---

### Task 12: iOS detail screens show Home's search button; local filters deleted

**Files:**
- Modify: `ios/FitrahTube/Features/Detail/ChannelDetailScreen.swift:110-111`, `ChannelDetailViewModel.swift:52,102-108`
- Modify: `ios/FitrahTube/Features/Detail/PlaylistDetailScreen.swift:34,52-55,172,230-232`, `PlaylistDetailViewModel.swift:32,59-76`
- Modify: `ios/FitrahTube/Features/Detail/ChannelTabPagination.swift:58,83,87,160-…` (`filtered(query:)`, `SearchFilter`)
- Test: `ios/FitrahTubeTests/ChannelTabPaginationTests.swift:121-170`, `ChannelDetailViewModelTests.swift:293`, `PlaylistDetailViewModelTests.swift:288,324`

**Interfaces:**
- Consumes: Task 11's `SearchButton` (unscoped: the channel page opens the same global search).
- Produces: `ChannelDetailViewModel` / `PlaylistDetailViewModel` without `query`; tab views read the unfiltered `TabState`; `SearchFilter` and both `filtered(query:)` deleted.

- [ ] **Step 1: Tests.** Delete `searchDisablesPaginationByDroppingTheCursor`, `anEmptyQueryLeavesTheStateUntouched`, `zeroMatchesRendersTheSearchNoResultsCopyNotTheTabsEmptyCopy`, `searchIsTrimmedCaseAndDiacriticInsensitiveOverTitleOrChannel`, `searchFiltersEachTabIndependentlyAndLeavesAboutAlone`, `searchFiltersLoadedItemsAndSuppressesPagination`; rewrite `aFilteredRowTapStillSeedsTheWholeListAndItsToken` as an unfiltered row tap if its seed assertion is not covered elsewhere.
- [ ] **Step 2: Implement.** Replace each `SearchField(...)` with `HStack { Spacer(minLength: 0); SearchButton() }` in the same slot and padding; delete the VMs' `query`, `trimmedQuery`, filter-derived `rows`/`visible*` (they return the loaded state), `SearchFilter`, `filtered(query:)`, `queryBinding` and its `onChange`, and the `search_no_results` branch of the playlist empty state.
- [ ] **Step 3: Verify.** `grep -rn "SearchFilter\|filtered(query" ios/FitrahTube` → none; `bash ios/scripts/test.sh` PASS; screenshots channel + playlist page on iPhone and iPad, en and ar.
- [ ] **Step 4: Commit** `[REFACTOR]: iOS detail pages use global search`

---

### Task 13: Enable inner search in prod (main session, ops)

**Preconditions:** Task 0 verdict PASS; Tasks 1–6 merged to `main`, the full 9-stage pipeline incl. Cubic passed, and the backend jar deployed (Part D order).

- [ ] **Step 1:** Back up: `cp /opt/albunyaan/application-prod.yml /opt/albunyaan/application-prod.yml.bak-$(date +%F)`.
- [ ] **Step 2:** `mkdir -p /opt/albunyaan/inner-search` owned by the service user; add to `application-prod.yml` (merge under an existing `search:` key):
```yaml
search:
  inner:
    dir: /opt/albunyaan/inner-search
```
  Prod yml, not a Java default: a default would switch the crawler dir on in every dev/test context. If G2 used `/tmp`, also set `search.index.base-dir` to a directory on the measured disk.
- [ ] **Step 3:** Restart; `app.log` no longer says `inner search is off`; `GET /api/admin/inner-search` (ADMIN token) → `mode: OFF`, no error.
- [ ] **Step 4:** `PUT /api/admin/inner-search {"mode":"FIRST_PASS"}`. Watch `status` hourly: `requestsByDay` within P2, `breaker.pausedUntil` 0, `signals` empty. Done when every source has `firstPageAt > 0` and the log shows `inner-index built`.
- [ ] **Step 5:** `PUT {"serveInner": true}`; run Task 14's API probes.
- [ ] **Step 6:** `PUT {"mode":"FULL"}` (first pages hold only each source's newest 100). Re-check `status.memory.availableMinusIndexMiB ≥ 1024` daily until the first full crawl ends (≈ P3 days); below it → lower `capPerSource` or `serveInner:false`, report.
  Kill: `touch /opt/albunyaan/inner-search/STOP` (no token, no restart) or `PUT {"mode":"OFF","serveInner":false}`. Report each step with its numbers.

---

### Task 14: End-to-end proof (main session)

- [ ] **Step 1: API probes** (`https://app.fitrahtube.com/api/v1/search?q=…&limit=50`): (a) the exact title of a video that is only inside an approved channel (not a catalog Video, not a stored stream) returns it with `sourceType`; (b) the title of an id excluded under some source does not return it; (c) for 10 queries (5 ar, 5 en) `jq '[.[] | select(.type=="VIDEO" and .durationSeconds==0)] | length'` = 0. Without inner (Task 0 FAIL) run (b) and (c) only and say so.
- [ ] **Step 2: Search → player → save, inner-only video.** iOS simulator: search (a)'s title → tap → plays ≥ 30 s → Save for offline completes (backend 404 on `/api/v1/videos/{id}` = allowed, `OfflineGateClient` ruling). Android (home laptop emulator, play flavor): same path, download completes. No backend approval gate is added; if either fails, root-cause before release.
- [ ] **Step 3: Screenshots** of every former search screen (Channels, Playlists, Videos tabs; channel page; playlist page): Android phone / sw600dp / sw720dp + ar RTL (laptop); iOS iPhone + iPad, en + ar (`ios/scripts/screenshots.sh`). Each shows the single Home button; tapping it opens global search.

---

## Self-review notes

- Spec coverage: one bar (8, 9, 11, 12); global search unscoped (9, 12); inner results (4, 13); global exclusions at query time (3); ranking + fixture (4, 5); null durations end to end (1, 7, 10); source backfill, paced (6; streams/catalog fill from the crawled corpus, 2); no per-query YouTube (constraint; 2 reads only the local corpus); headroom gate + ops (0, 13); deleted local-filter/tab-q code (8, 9, 11, 12); backend `q=` kept (constraint); suggest-content YouTube search untouched; done-criteria probes + screenshots + search→player→download (14).
- Names used across tasks: `InnerCorpus.Item.d: Integer` (1→2), `excludedAnywhere` (3), `Ladder.upToTier1` (4), `DurationFormat.badge` (7), `searchButton` view id (8, 9), `SearchButton` view (11, 12), `CatalogClient.content(type:cursor:limit:filter:)` (11).
