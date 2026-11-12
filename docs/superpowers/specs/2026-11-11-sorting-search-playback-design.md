# Tab order, unified search, Android playback stop, release — design (2026-11-11)

Owner request (2026-11-11), clarified in conversation. Four independent parts; build in this order:
A (tab order) and B (search) can run in parallel; C (playback) in parallel with both; D (release) last.

A separate in-flight task (admin dashboard count consistency + removing the Content Library "Results may be
incomplete" cap) is NOT part of this design but ships in the same backend deploy.

---

## A. Channels / Playlists / Videos tabs follow Content Sorting

**Why.** The admin curates order on the dashboard Content Sorting page; today only Home uses it. The tabs order
by subscribers / item count / upload date (`ChannelRepository.java:649-668`, `PlaylistRepository.java:576`,
`VideoRepository.java:619`), so the curation is invisible on the tabs.

**Behaviour (owner).**
- Each tab lists only its own type: Channels tab = channels only, Playlists = playlists only, Videos = videos only.
- Order: top-level categories in `displayOrder` (top→bottom), inside each category the item order from
  `category_content_order.position`; then every approved item of that type that has no category, A→Z by title.
- Changes to sorting reflect in the apps immediately (next load / pull-to-refresh), not after a cache TTL.

**Calls made (mine, owner saw them and did not object).**
- An item in several categories appears once, at its first position in the walk.
- Subcategories are treated exactly as Home treats them (`PublicContentService.getCategoryContentItems`
  parent handling, `:753-769`), so Home and tabs never disagree.
- An explicit user-chosen sort/filter on the Videos tab (length, date, sort) overrides the curated order; curated
  order is the default only.
- Items without an order row (default orders cap at 500 per type, `SortOrderService.java:663-714`) must not fall
  into the A→Z tail if they belong to a category: they get placed at the end of their category, in the same
  default order `SortOrderService` already uses.
- Archived items are excluded (same "live approved" definition the dashboard count fix settles on).

**How (constraints for the builder).**
- Backend only, endpoint unchanged: `GET /api/v1/content?type=CHANNELS|PLAYLISTS|VIDEOS` (no category, no q,
  no video filters) returns the curated order. Reuse `getCategoryContentItems`' walk + `seenContentKeys` dedupe
  and the existing offset cursor (`encodeCursor`/`decodeCursorOffset`). Do not invent a new cursor scheme.
- Build the full ordered id list per type once and cache it (not per page). Evict on every sort change, approval,
  rejection, archive, delete and category edit; the existing `evictPublicContentCaches()` hook in
  `SortOrderService` (`:131,206`) is the model. Firestore reads per rebuild must be bounded (one pass over
  `category_content_order` + item batch reads), never per page request.
- Apps: Android `ContentListViewModel` and iOS `ContentListViewModel` already render server order with no client
  re-sort. The builder must verify neither app caches tab pages past a refresh (HTTP cache headers, in-memory
  repos); fix only if it does.
- Category-filtered tab requests, `q=` requests (going away, see B) and video filters keep their current paths.

**Done when.** A backend test seeds categories+order rows and asserts the exact page sequence across cursor pages
for each type (including multi-category dedupe, subcategory, uncategorized A→Z, unordered-but-categorized);
reordering on the dashboard is visible in the live API on the next request.

---

## B. One search bar, accurate results, real durations

**Why.** Results don't feel like YouTube, durations show 0:00, and there are three different search behaviours.

**Facts (verified).**
- Public search is an in-process Lucene index (`search/SearchIndex.java`); it calls YouTube zero times per query.
- "Videos inside approved channels/playlists" already exists as the inner index (`InnerIndex`, crawler
  `UploadsFetcher`, pacing `CrawlPacer` ~15 s ±20 %, own breaker) but has NEVER run in prod: every boot since at
  least 2026-11-03 logs `inner search is off: search.inner.dir is not set` (`/opt/albunyaan/logs/app.log`).
  Even when set, `InnerState.mode` defaults OFF and `serveInner` false (admin `PUT /api/admin/inner-search`).
- 0:00: a missing duration is stored/sent as `0` (`ContentItemMapper.java:61`, `SearchIndex.java:1252`,
  `UploadsFetcher.java:111` maps NewPipe -1 → 0) and both apps render 0 as "0:00"
  (Android `ApiMappers.kt:44` + `SearchResultsAdapter.kt:171,212-220`, `VideoGridAdapter.kt:46`;
  iOS `Components.swift:354/421/475` hides only nil).

**Behaviour (owner).**
- Results include approved channels, playlists, videos AND every video inside an approved channel or playlist,
  even if not individually approved — **except excluded videos**, which never appear anywhere in search.
- Ranking as close to YouTube as practical: exact title > close > related, Arabic and English both.
- Real durations always. Unknown length → no badge, never "0:00".
- Must not cause YouTube rate limits/bans.
- Exactly ONE search bar: the Home one. Every screen that has a search field today (Channels, Playlists, Videos
  tabs; channel detail; playlist detail — Android and iOS) shows that same bar instead, and it behaves the same
  everywhere: opens the global search screen and searches everything. Searching from a channel page is NOT scoped
  to that channel (owner: "search everything like home").

**Calls made (mine).**
- No per-query YouTube calls, ever. The only YouTube traffic is the existing paced background crawler at the
  owner's ceiling. Before enabling in prod, measure server RAM/disk headroom (1 GB heap note in deploy memory)
  and the crawler's projected request volume for the current catalog; report numbers before turning on.
- Enable order in prod: set `search.inner.dir` (property must be added to `/opt/albunyaan/application-prod.yml`
  or have a Java default — that file replaces application.yml), FIRST_PASS crawl, then `serveInner` once the
  index has built. Kill switches already exist (STOP file, admin toggle); keep them.
- Exclusions: inner results are filtered by the same exclusion lists the apps receive; verify at query time, not
  only at crawl time, so a new exclusion takes effect immediately.
- Durations: fix at the source. Backfill missing `durationSeconds` for approved/searchable videos from data the
  backend already fetches (paced, not a burst); keep unknown as null (not 0) end to end; apps hide the badge on
  null and on 0.
- Ranking: tune within the existing Lucene tier ladder; add a fixture-based relevance test set (real catalog
  titles, Arabic + English, no music videos) that pins expected top results. No external search service.
- The suggest-content YouTube search (`SuggestContentViewModel`) is a different feature and stays.
- Removed local-filter search code on detail screens and the tab `q=` UI is deleted, not hidden (owner rule:
  proven-unused code is deleted). Backend `q=` on `/content` stays if any shipped app version still sends it.

**Done when.** Live probe: a title of a video that exists only inside an approved channel returns it; an excluded
video's title does not; no result shows 0:00; screenshots of every former search screen on phone / sw600dp /
sw720dp and RTL Arabic show the single bar.

---

## C. Android playback stops after 40–60 s (Samsung S25 Ultra, "most videos")

**Why.** Owner reports stop + "تعذر استعادة التشغيل تلقائياً" overlay + "هذا الفيديو غير متاح للتشغيل" toast on most
videos on that phone (Android 1.0.6).

**Facts.**
- Messages come from the 403 recovery path: `PlayerFragment.kt` `surfaceRecoveryExhausted` (~2129) after
  `maxStreamRefreshes = 2` (~1115) or the `ExtractionRateLimiter` (2 attempts / 5 min).
- Recovery re-extracts with the SAME NewPipe client; no switch to progressive itag 18 on a mid-play 403
  (fallback only arms on source build failure, `PlayerFragment.kt` ~3002/3017).
- Default client is NewPipe's IOS (`npe.ios.fetch.enabled` default true, `build.gradle.kts:133`) with a
  WebView-minted (web) PO token registered for all clients (`WebViewPoTokenProvider.kt:49-53`).
- 2026-08-22 finding: IOS client in 0.26.5 403s after ~60 s; web PO token is not valid for iOS/Android clients.
- NewPipeExtractor 0.26.5, Media3 1.11.0; last bump 2026-08-18.

**Root cause (proven off-device 2026-11-11, JVM probe with the app's exact jars).** NewPipe 0.26.5 merges
ANDROID → VISIONOS → IOS and keeps the first copy of each stream. When VISIONOS serves a video (most non-kids
videos) its HD streams win and sustain past 75 s. When VISIONOS refuses it (kids videos), audio and the HD ladder
come only from the IOS client, whose audio 403s at ~0.75–1 MB (~40–50 s): `xuE4k3wQt9I` audio itag251 c=IOS
`HTTP 403 at byte 786432`, identical with and without a pot. ANDROID adds only itag 18, which sustains (206 at
t+76 s). Recovery re-extracts with the same client and gets the same dying URLs. Unproven: that the user's "most
videos" are VISIONOS-unplayable ones (kids content is a large share of the catalog), and that the phone's IP sees
the same.

**NewPipe upgrade: NO (evidence overrides the request).** Latest is v0.27.1 (2026-11-10). v0.27.0 removed the
IOS/ANDROID pot path and PR #1529 states "videos made for kids cannot be played until we can support SABR"; the
probe confirms both kids ids fail extraction on 0.27.1 (`UNPLAYABLE`). Upgrading would turn a stop at 50 s into
not playing at all. Stay on 0.26.5 for this release.

**Required.**
- Reproduce first (JVM probe against a catalog lecture id using the app's extractor setup, timing bytes until
  403) — no music video ids. Fix the proven root cause.
- Fix: default `npe.ios.fetch.enabled` to `"false"` (`android/app/build.gradle.kts:~133`). Probe-verified: kids
  videos then resolve to ANDROID itag 18 (360p muxed) and sustain; non-kids keep VISIONOS HD. Cost: kids videos
  play at 360p on Android. Delete the then-dead IOS pot getter (`WebViewPoTokenProvider.kt:53`) and anything
  else only the IOS path used (owner rule: proven-unused code is deleted).
- Recovery: a mid-play 403 must not re-fetch the identical stream set twice and give up; it falls to itag 18
  before showing the exhausted state. Keep it to the smallest change in `PlayerFragment.kt`'s 403 path.
- Other core deps (Media3, OkHttp, etc.) only where release notes show a relevant fix.
- iOS: InnerTubeKit is in-repo (no external dep). Re-probe its ladder against the same ids; update client
  constants only if probes show breakage.
- Constraint: the affected phone belongs to someone else, a 4-hour drive away; it cannot be used for
  reproduction. No Android SDK on this Mac. Proof therefore comes from off-device JVM probes (same
  NewPipeExtractor + client path) and unit tests; the user's report after the update is the field check.
- Owner notes it "sounds like an old bug but still present on his device": confirm the installed version and
  source (Play vs sideload) on that phone. An outdated install would be its own cause.

**Done when.** Probe (0.26.5, IOS off) shows the chosen streams sustaining > 5 min (bytes past the 60 s boundary) for several
catalog videos incl. one kids video; unit tests cover the new recovery decision.

---

## D. Release

- Android 1.0.7 (Play `play` flavor + sideload), iOS 1.0.9. Release checklist in CLAUDE.md
  (versionCode/Name, `releases-meta.json` en/ar/nl, CHANGELOG).
- Play: play flavor only; sideload: signed on the home laptop with the sideload key + lineage, never the Play key.
- Review/beta notes never name YouTube or the video source; nothing false.
- Order: full 9-stage pipeline incl. Cubic → backend deploy (jar swap) → inner crawl enable → stores + sideload.
- Owner pre-authorised publishing ("stop asking permission"); report each step.

## Builder-check resolutions (2026-11-11)

- **C without a reproduction (my call).** The release does not wait on a device. The recovery change (a mid-play
  403 switches to a different client and then to itag 18 instead of repeating the same client) ships either way:
  it is a strict improvement, unit-tested, and proven by the off-device probe sustaining the fallback stream past
  the boundary. If the probe also proves a client root cause, that fix ships too.
- **Inner-search headroom is a gate (my call).** Measure first. If it fits, enable it. If it doesn't, B ships the
  single bar, durations and ranking WITHOUT inner results, and I bring the owner the numbers plus options (bigger
  server = cost, which is theirs). Done-criterion for the inner part moves with it.
- **Exclusions in search are global (my call, the conservative reading of "except if it's an excluded video").**
  A video id excluded under any approved channel/playlist never appears in search results, whichever source the
  hit came from. Home and tabs are unaffected: they show only items an admin approved explicitly.
- **Playing inner-only videos (answered).** Android channel pages already list and play non-individually-approved
  uploads via on-device NewPipe (`ChannelDetailViewModel.kt:35,424-441`), so the player takes any video id. The
  builder verifies the search→player path on both apps (and download), with no backend approval gate added.

## Non-goals
- No YouTube hand-off, embed or IFrame player (standing rule).
- No new search infrastructure (Elasticsearch etc.).
- No change to the approval flow or what admins can approve.

## Anti-goals (would be a failure even if it "works")
- Any per-search or per-scroll YouTube request.
- An excluded video visible anywhere.
- Home and tabs showing different orders for the same category.
- A play-flavor build containing sideload-only code, or the Play key touching the sideload APK.
