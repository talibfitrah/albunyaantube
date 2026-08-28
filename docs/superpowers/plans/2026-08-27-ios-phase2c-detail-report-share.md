# Channel Detail, Playlist Detail, Report, Share & Links Implementation Plan (iOS Phase 2, Plan C)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** After Plan A and B1–B5 the app resolves and plays a video, but every route that is not a player still ends at `PhaseTwoPlaceholderView`: `albunyaantube://channel/UC…` opens a debug list of arguments, a channel card in Home pushes the same, and the player's Report button shows a "coming soon" banner. Plan C closes Phase 2. When it lands: `Route.channel` and `Route.playlist` render real screens over `InnerTubeKit.BrowseClient`; the channel's Shorts and Playlists tabs render (they return an empty page today — CF-C1); a bot-checked `browse` degrades to the channel's Atom feed instead of an empty screen, and latches so it stops re-probing (CF-C3); Report is a real sheet posting to `POST /api/v1/reports` with parent context and 429 handling, replacing the banners in `PlayerToolbar` and the Shorts kebab (CF-B1-9); Share exists on all four Android surfaces with the same URLs; and `ios-remote-config.json` exists at the repo root on `main`, so the remote kill switch the runbook (`ios-app-plan.md` §11) depends on stops being a 404 (CF-B1-12).

**Architecture:** Two screens, one data layer, one sheet, and one JSON file. The data layer (`ChannelBrowse.swift` / `PlaylistBrowse.swift`) is where the interesting decisions live and all of them are pure and testable without a network: `ChannelTabPagination` (the per-tab state machine), `ChannelTabAutofill` (ruling 10's cap-then-button machine, which is deliberately **not** `PaginationGuard`), `BrowseFallback.decide` (bot-check → degraded), `SearchFilter.apply` (client-side filtering), `ReportPayload.make` (target/parent/subtype/reason mapping) and `ShareLinks` (three URL builders + three message builders). The two SwiftUI screens are assembly over those pure types plus components Phase 1 already shipped (`VideoRow`, `PlaylistRow`, `RemoteImage`, `EmptyStateView`, `ErrorStateView`, `SkeletonListView`, `DurationChip`, `transientBanner`). Only one package file changes (`BrowseClient.swift`, Task 1) and only because two wire shapes are unmodelled. No new `Route` cases, no new `StreamState` cases — see the enumeration in Global Constraints.

**Tech Stack:** Swift 6, SwiftUI, `@Observable`, Swift Testing; app target `ios/FitrahTube` plus `ios/Packages/InnerTubeKit` (Task 1 only) and `ios/Packages/FitrahAPI` (read-only — this plan generates nothing). XcodeGen. Gate: `ios/scripts/test.sh` (300 s wall). Acceptance screenshots via `ios/scripts/screenshots.sh`.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §6 (Routes — `channel(id)`, `playlist(id)`; "**Deep links**: custom scheme `albunyaantube://{video|channel|playlist|shorts}/{id}`; Universal Links `https://app.fitrahtube.com/{watch|channel|playlist}/{id}` and `/api/{watch|channel|playlist}/{id}`"; "**Sheets and dialogs** … ContentReport"), §8 (`POST api/v1/reports`; `POST api/v1/index/streams` (≤50 items, 429 on 30 s dedupe); `HEAD api/v1/{channels|playlists|videos}/{id}`; `POST {share}/api/share-metadata/{type}/{id}` "900 ms budget, fire-and-forget, signed-in only"), §9 (`BrowseClient`, verbatim: "WEB-context `browse` — channel header, Videos via `VLUU…` uploads playlist continuation, Live/Shorts/Playlists via tab params, About from the header; playlist items via `VL<playlistId>`; continuation paging; exclusions from the backend applied; items pushed to `POST /api/v1/index/streams` (≤50, deduped). Degraded mode when bot-checked: approved playlists (`/api/v1/channels/{id}`) + Atom feed (`https://www.youtube.com/feeds/videos.xml?channel_id=`) + indexed search."; `RemoteConfig` "schema from plan §6.13; bundled default; last-known-good; fetched on launch and `willEnterForeground` with ≥15 min spacing; body ≤64 KiB; `minAppVersion` gate"), §10 ("Share (`ShareLink` with `https://app.fitrahtube.com/api/watch/{id}`, OG publish when signed in, no 'ad-free')", "Report (VIDEO, with parent PLAYLIST/CHANNEL and subtype)"), §11 (`ShortsCell` 9:16, `PlaylistRow`, `ChannelRow`, `EmptyState`, `ErrorState`, grids "channels/playlists 2/3/4 columns by width class", "shorts 2/4/5", "Pagination: `.onAppear` on the last row **and** `onScrollGeometryChange` 'content fits and hasMore → loadMore()' with an in-flight guard (CLAUDE.md rule)"), §12 (`WellKnownController` — the AASA row), §14 (RTL, ≥44 pt, Dynamic Type, "single column at `.accessibility1+`"), §15 (phase 2 = "channel/playlist detail, report, share + metadata publish, deep/universal links, remote config, Safe Mode").
Detail: `docs/architecture/ios-app-plan.md` §6.7 (the whole section — `BrowseClient`, degraded mode, the backend-proxy alternative), §6.9 (`X-Device-Id`: "random UUID in UserDefaults, header on every backend call (backend rejects requests without it)"; "Browse, search, play, local favorites, **report and share work signed-out**"), §6.11 (iPad/RTL/accessibility), §6.12/§6.13 (remote config schema, the "data only" rule, the 64 KiB cap, the 2.3.1 line), §8 rows 1 and 4 (AASA; Swift codegen), §11 (the runbook — the row "`browse` bot-checked | channel pages empty | degraded mode (Atom + approved playlists) is automatic").
Behavioural source (authoritative Android record, cited file:line throughout): `docs/superpowers/plans/2026-08-23-ios-phase2-research/channel-detail.md` (all sections, **including the 2026-08-24 addendum**), `.../playlist-detail-shorts.md` **Part A** (§1–§5) and §6.1 (the channel Shorts grid), `.../share-report-links.md` (all sections), `.../remote-config-safemode.md` §1 and §7.
Predecessors: `.../2026-08-23-ios-phase2a-innertubekit.md`, `.../2026-08-24-ios-phase2b1-player-core.md`, `.../2026-08-27-ios-phase2b2-background-audio.md`, `.../2026-08-27-ios-phase2b3-embed-safemode.md`, `.../2026-08-27-ios-phase2b4-shorts.md`, `.../2026-08-27-ios-phase2b5-fullscreen-queue.md`.

**Android parity, and where this plan deliberately leaves it.** The record is the three briefs above. Behaviours mirrored: the availability gate before any extraction, fail-open on transport, 410 → terminal "Content not available" with no Retry (`ChannelDetailViewModel.kt:135-147`, `PlaylistDetailViewModel.kt:112-124`); five fixed channel tabs, never hidden when empty, each with its own empty copy (`ChannelDetailModels.kt:169-175`, `strings.xml:340-343`); the collapsing banner header with the scroll-reactive toolbar tint (`ChannelDetailFragment.kt:167-179`); the subscriber "–" placeholder (`:389-397`); the 30-channel subscribe cap and its message (`SubscriptionLimitGuard.kt:26,73`, `strings.xml:183`); the client-side in-header search with a 300 ms debounce that disables pagination while active (`ChannelDetailFragment.kt:112-142`, `BaseChannelListTabFragment.kt:269-294`, `PlaylistDetailFragment.kt:202-227,326-346`); the per-tab pagination machine — threshold 5, 1 s minimum between appends, autofill capped at 1 page on phones / 2 on ≥600 dp then a "Load more" footer (`ChannelDetailViewModel.kt:283-289,1137-1143`, `BaseChannelListTabFragment.kt:123-175,399-415`); the footer's LoadMore/Loading/Error trio (`ListFooterAdapter.kt:40-49`); the playlist hero with the blurred backdrop and the 4-cell action bar (`fragment_playlist_detail.xml:39-402`); 1-based playlist positions carried across pages via `nextItemOffset` (`NewPipePlaylistDetailRepository.kt:175-177,211`); Play All / Shuffle / row-tap emitting unconditionally with `targetVideoId` authoritative (`PlaylistDetailViewModel.kt:404-417`, `PlaylistDetailFragment.kt:745-754`); the Save toggle and its malformed-id refusal (`PlaylistDetailFragment.kt:160-196`); the kebab being Share + Report on both detail screens (`res/menu/menu_detail_kebab.xml`); the report sheet's 11 reasons, "Other" reveal, and its five states (`ContentReportBottomSheet.kt:98-144`); the report payload and its 429 → `RateLimitException` mapping (`ReportModels.kt:5-19`, `ReportRepository.kt:45-49`); `X-Device-Id` on every backend call (`NetworkModule.kt:94-101`); the three share URL shapes and the three-block message (`ShareLinks.kt:8-73`, brief §1.3); the fire-and-forget stream index push (`IndexRepository.kt:18-40`).

Behaviours **not** mirrored, each with its ruling or its citation:
- The Videos **dual path** with cursor-family provenance (**ruling 3**) — reconciliation note 1.
- The Room **pre-paint cache** (**ruling 4**) and its `publishedTime`-dropping quirk (`ChannelDetailViewModel.kt:396-411,1064-1076`).
- The `excluded` argument and its banner (**ruling 6**; no Android caller ever passes `true` — brief §1.2).
- The About tab's permanently-nil location / joined / totalViews rows (**ruling 7**; `NewPipeChannelDetailRepository.kt:740-746`).
- The blank Shorts loading state (**ruling 11**; the skeleton RecyclerView with no adapter, `fragment_channel_shorts_tab.xml:28-39`) and the Shorts cell's `%.1fM`-style view counts that bypass `CountFormat` (**ruling 37**; `ChannelShortsAdapter.kt:62-79`).
- The generic tab-empty copy shown for a zero-match search (**ruling 5**; brief §5.4 defect).
- The unconfigured playlist empty state and its missing search-no-results variant (**ruling 46**; `PlaylistDetailFragment.kt:427-433`).
- The visually silent playlist append (**ruling 47**; `PlaylistDetailViewModel.kt:348` sets `isAppending` and the fragment renders nothing).
- The playlist row's plain `video_views_format` instead of the plural (**ruling 48**; `PlaylistVideosAdapter.kt:71`).
- The "%1$d videos • %2$s" total-duration variant (**ruling 49**; `totalDurationSeconds` is always nil, `NewPipePlaylistDetailRepository.kt:329`).
- The playlist screen's missing autofill and missing upward-scroll guard (**ruling 55**; `PlaylistDetailFragment.kt:281-288`).
- The About tab's and `empty_state.xml`'s hardcoded `@android:color/black` / `darker_gray` (brief §6.5 defect — iOS uses the semantic tokens Phase 1 already ships).
- Downloads anywhere on either screen (**rulings 28 / 56**): no Download cell in the playlist action bar, no per-row download badges, no download-policy hint, no quality dialog. The action bar is **three** cells, not four.
- `ShareMetadataPublisher` (**rulings 30 / 68**) — Phase 4. Nothing dormant ships.
- `res/menu/menu_report.xml` (**ruling 73**) — dead on Android, ported as nothing.
- The player's **two disagreeing report entry points** (**ruling 66**; `PlayerFragment.kt:477-483` drops the context `:1824-1851` forwards). iOS has one path that always carries the full context.
- The tablet Shorts-rail Report button (**ruling 53**; `shortReportBtn` has zero Kotlin references).
- Android's report sheet is also *ahead-of-parity* in the other direction: its 5-argument `newInstance` with parent context (`ContentReportBottomSheet.kt:178-184`) is **never called** — all three Android call sites use the 2-argument form (`PlayerFragment.kt:482`, `ChannelDetailFragment.kt:451`, `PlaylistDetailFragment.kt:622`). Spec §10 requires the context, so iOS ships what Android's wire format supports but its UI never sends.

**Rulings this plan implements** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/RULINGS.md`):

| # | Ruling | Where it lands |
|---|---|---|
| **1** | Live tab ships in Phase 2 **with playback** — a live row is a normal player launch | 5 — the Live tab is a `channelTab(.live)` list whose rows push `Route.player(PlayerArgs(…, channelId:))`; `contentSubType = "LIVESTREAM"` rides into the report payload (Task 3's `ReportContext`) |
| **2** | → ruling 12 (extraction mechanism) | 1 and 2 — `BrowseClient`, WEB context, per §6.3 |
| **3** | **Single videos path, provenance flag dropped** — the dual path works around NewPipe v0.26 bugs iOS does not inherit | 2 and reconciliation note 1. One call: `BrowseClient.channelVideos` (`VLUU…`). No `videosUseChannelTab`, no cursor-family tracking, no channel-tab fallback |
| **4** | No Room-style pre-paint cache in v1; skeleton on open | 5 — `SkeletonListView` on `.loading(.initial)`, exactly the Phase-1 pattern |
| **5** | In-header search filters loaded pages but uses the tabs' **"no results"** copy (fixes defect 6) | 2 (`SearchFilter`) and 4/5 — zero matches renders `EmptyStateView` with `search_no_results`, not the tab's generic empty copy |
| **6** | `excluded` arg **not ported** (dead on Android) | Global Constraints — `Route.channel`/`.playlist` carry no `excluded`, and no exclusion banner is built. Not to be re-added "for deep-link parity" |
| **7** | About tab's permanently-nil rows (location / joined / totalViews) **omitted** | 5 — About renders description, links and the two rows that can hold data (subscribers, verified) |
| **8** | Subscriber **"–"** placeholder kept (`channel_subscribers_unknown`) | 5 — and see reconciliation note 4, which is why the *format* string is not used |
| **9** | Tabs: scrollable strip on compact, fixed/fill ≥600 pt; **swipe-between-tabs preserved** | 5 — a `TabView(.page(indexDisplayMode: .never))` under a custom header strip |
| **10** | **Keep both autofill machines**: channel tabs cap at 1–2 pages then a "show more" button; Phase-1 lists keep `PaginationGuard` | 2 (`ChannelTabAutofill`) — reconciliation note 2 |
| **11** | Shorts loading state renders the **9:16 skeleton grid** (fixes defect 1) | 5 — `SkeletonShorts`, a 9:16 variant of the existing `SkeletonGrid` |
| 12 | InnerTubeKit; **WEB for `browse`** | 1 and 2 — unchanged; `BrowseClient.send` already pins `clients["web"]` (`BrowseClient.swift:199`) |
| 14 | Terminal states have distinct copy and no retries | 4/5 — `content_unavailable_title`/`_message` with **no** Retry button, per the header-state tables in both briefs |
| 15 | Availability gate: identical HEAD endpoints, 404 fail-open / 410 hard-block, fail-open on transport | 2 — `BackendAvailabilityGate` gains a playlist path (it has channels + videos today, `BackendAvailabilityGate.swift:33-37`) |
| 19 | Device locale for `hl` | 1/2 — inherited from `InnerTubeLocale`; see the Arabic-header ceiling in reconciliation note 4 |
| **25** | Stream-index side channel (`POST` `IndexStreamsRequest`, fire-and-forget, log-and-drop) **IS ported** | 2 — `IndexClient`, called after every successful page, ≤50 per request |
| **27** | Subscribe (30-cap) and save-playlist ship in Phase 2 against **local stores** for guests | 4 and 5 — `SubscriptionsStore` / `SavedPlaylistsStore` over SwiftData, the same shape `FavoritesStore` already has |
| 28 / 56 | Every download affordance hidden until Phase 3 | 4 — the playlist action bar is Play all / Shuffle / Save. No Download cell, no row badges, no policy hint |
| 30 / 68 | `ShareMetadataPublisher` omitted until Phase 4 (amends spec §15's "share + metadata publish") | 3 — share works; nothing publishes |
| 37 | **ONE formatter** (`Format`) everywhere | 2/4/5 — `Format.compactCount` + the `video_views` substitution plural for every count; `Format.duration` for every duration; `Format.localizedFormat` for every composed string |
| **46** | Proper playlist empty copy **+ a distinct search-no-results variant** | 4 — `playlist_empty_state` for a genuinely empty playlist, `search_no_results` for zero matches |
| **47** | **Footer spinner while appending** (fixes defect 28) | 4 — the same `ListFooter` component Task 2 builds for the channel tabs |
| **48** | `video_views` **plural** everywhere | 2/4/5 — `video_views_format` is never referenced from Swift |
| **49** | Total-duration variant dropped | 4 — the metadata line is `playlist_metadata_format` only; `playlist_metadata_duration_format` stays an orphan key |
| 52 | Rail/affordance visibility driven solely by its own data publisher | 4/5 — every conditional affordance reads one source (e.g. the About links section reads `links.isEmpty`, nothing else writes it) |
| 53 | Report stays **kebab-only on every size class** | 3 and 5 — one Report entry point per screen, in the kebab |
| **55** | Playlist list adopts the **Phase-1 guarded autofill** (CLAUDE.md's pagination rule) | 4 — `PaginationGuard` + `onContentFits`, verbatim the `ContentListView` wiring. Reconciliation note 2 |
| 58 | Safe Mode's first real effect is auto-advance (B3/B5) | **Not here.** Plan C gates nothing on Safe Mode; the catalog is admin-curated and ruling 58 says nothing else is gated |
| **63** | `FEATURED_CATEGORY_ID` becomes a **RemoteConfig key** with the hardcoded id as bundled default | **5 (tail)** — batched there so Task 6 stays acceptance-only; the published document carries the value in Task 6. Fork D |
| 64 | The ASCII allowlist sanitizer is applied to any remote-sourced version string the `minAppVersion` screen displays | **Not here.** No `minAppVersion` screen is built in Plan C; Task 6 only publishes the document that carries the field. Recorded as a carry-forward |
| **65** | **AASA hosting is USER-BLOCKED** (needs the Apple Team ID and control of `app.fitrahtube.com`) | Global Constraints + Task 6 step 7. Custom-scheme links work today; Universal Links do not, and no code change makes them |
| **66** | **ONE report path**: every entry point forwards the full context (channelId / playlistId / contentSubType) | 3 — `ReportContext` is a single struct; there is no 2-argument constructor to accidentally call |
| 67 | Inbound **watch** links open the regular player even for Shorts | Global Constraints — `DeepLinkParser` is not touched on that path |
| **69** | Device id: the Phase-1 `DeviceId` (UserDefaults UUID, key `com.albunyaan.tube.deviceId`) | 3 — already attached by `DeviceIdMiddleware`; the report client must go through the shared `FitrahAPI` client, never a bare `URLSession` |
| **70** | Report UI uses the **localized keys**; ar/nl arrive via the converter's R7 fallback until translated | 3 — every `report_*` key already exists in the catalog with `ar`/`nl` present at `needs_review` |
| **71** | Share sheet: **text + URL as separate activity items**, subject via the subject affordance | 3 — `ShareLink(item: url, subject:, message:)`. Reconciliation note 5 |
| **72** | **429 keeps the user's selected reasons and text** (inline rate-limit message) | 3 — the sheet stays open on `.rateLimited`, diverging from `ContentReportBottomSheet.kt:131-135` deliberately |
| 73 | `menu_report.xml` ports nothing | — |
| **74** | iOS claims the `albunyaantube` scheme; the backend watch pages work unmodified | Global Constraints — already true (`DeepLinkParser.swift:19-25`); Task 6 verifies the watch-page hop end to end |

**Carry-forwards this plan absorbs** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md`):

| Item | What it demands | Task |
|---|---|---|
| **CF-C1** | `BrowseClient.channelTab(.shorts/.playlists)` returns an EMPTY page; the two `lockupViewModel` variants are unmodelled and **must be modelled before those tabs render** | **1** — and the field paths are deliberately *not* in this plan, because nobody has seen the payload. Step 1 of Task 1 is a live capture; Steps 2–3 are written against what it returns |
| **CF-C2** | "`BrowseClient` bot-check trips do NOT escalate the shared `SessionStore` cooldown… If browse should share the cooldown, wire `recordBotCheck()` into BrowseClient's botCheck path in Plan C" | **1 — decided: NO.** Reconciliation note 3 gives the argument and the cited Android behaviour, and names what Task 1 does instead |
| **CF-C3** | Degraded mode (bot-checked browse → approved playlists + `AtomFeedFetcher` + indexed search) is spec'd but not wired; `AtomFeedFetcher` is ready, its conditional GET dormant (T12-2) | **2** — `BrowseFallback` + `DegradedLatch`. **The "approved playlists" third of it cannot be built**: no channel-scoped playlist endpoint exists (Task 2's degraded table; BACKEND-BLOCKED item 6). Atom and indexed search ship; the Playlists tab shows an error until the endpoint does |
| **CF-B1-9** | The player's Report button shows `player_report_coming_soon`; Plan C wires the real VIDEO report flow (parent PLAYLIST/CHANNEL + subtype; Android `ContentReportBottomSheet`, `POST api/v1/reports`) | **3** — one sheet, four call sites, the coming-soon key retired |
| **CF-B1-10** | "Metadata 'Show more' always renders (Task-8 M2 dropped); add a `ViewThatFits` truncation probe" → Plan C or any plan touching `PlayerMetadataView` | **3** — Task 3 already opens that file's neighbour, and the probe is ~12 lines with a pure test |
| **CF-B1-12** | "`ios-remote-config.json` still not published at repo root; `refresh()` 404s; bundled `resolverOrder` is permanent — publish before the Phase 2 gate" | **6** — including the merge-to-`main` dependency, which is what actually makes it live |
| **CF-B5-1** | `PlaylistQueueSource` and the `PlayerArgs` launch contract are Plan C's integration surface; pass `targetVideoId` on a row tap (authoritative) and `startIndex` as a hint, **unconditionally**, without waiting for items to load | **4** — Task 4's Interfaces section restates the contract verbatim and its tests pin it |
| **CF-B5-2** | `LivePlaylistQueueSource` and PlaylistDetail page `BrowseClient.playlistItems` with **independent cursors** — deliberate | **4** — Plan C does **not** hand its page array to the player and does **not** add a shared paging owner |
| B3's `EmbedPage.baseURL` | `https://app.fitrahtube.com/embed` is used as the `loadHTMLString` base (a Referer origin) and the only main-frame URL the navigation lock accepts (`2026-08-27-ios-phase2b3-embed-safemode.md:625-629`) | **6 step 4 — backend, USER/BACKEND-BLOCKED.** `WatchPageController` serves `/watch`, `/channel`, `/playlist` and their `/api` variants and **nothing else**; grep of `backend/src/main` for "embed" finds only the outbound `YouTubeOEmbedClient`. The path need not resolve for the rung to work (WebKit sends the Referer regardless), so this is a hardening item, not a blocker — recorded, not built |
| B4's sibling-id carry | "if skip-on-failure is later wanted, it arrives as a sibling id list in the route arguments from Plan C's channel Shorts grid" | **Not absorbed** — fork E. The Shorts grid pushes a single `Route.shorts`, exactly as B4's Task 1 expects |
| CF-B1-11 / CF-B2-1 / CF-B2-10…15 / CF-B4-* | `ManifestCache` live TTL, the app-scoped player holder, landscape chrome, the vacuous iPad assertion, the PiP scrubber freeze, orientation lock | **Not absorbed** — all player-side, all B2/B4/B5's. Plan C mounts no `AVPlayer` |

---

## Global Constraints

Implementers inherit nothing from earlier plans. All of the following are binding:

- **Swift 6, `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor`** on the app and unit-test targets (`ios/project.yml:55`), `SWIFT_STRICT_CONCURRENCY: complete` (`:17-18`). Pure value types that tests construct off the main actor are marked `nonisolated` — the same annotation `Route`, `Format`, `PaginationGuard` and `LiveCatalogClient` already carry. `InnerTubeKit`'s `BrowseClient`, `SessionStore`, `AtomFeedFetcher` and `RemoteConfigStore` are **actors**: every call is `await`, and a view model must not hold their results in a way that assumes ordering.
- **One implementer at a time.** `ios/DerivedData` is shared; two concurrent `xcodebuild` runs corrupt it. Never dispatch two Plan C tasks in parallel.
- **Gate:** `ios/scripts/test.sh` **from the repo root**, 300 s wall-clock watchdog (exit 124 on trip), 60 s per test. It runs `convert-strings.py --check` → `xcodegen generate` → `xcodebuild test` (iPhone 17 + iPad Pro 13-inch (M5), one invocation) → `swift test` in `Packages/FitrahAPI` → `swift test` in `Packages/InnerTubeKit` (**~90 tests, the bulk of the budget — Task 1 adds to it, so watch the margin**) → a Release build. A task is not done until this is green.
- **No new files in the Xcode project file.** `project.yml:43-46` globs the whole `FitrahTube` directory; new `.swift` files need only `xcodegen generate`, which is gate stage 2.
- **`ios/scripts/screenshots.sh`'s device argument does not scope the trailing blocks.** `$@` filters only the first `DEVICES` assignment (`screenshots.sh:18-24`); every `-only-testing:` block appended after it (B1 tasks 3–10, B2, B3, B4) is hard-pinned to its own destination. To capture one Plan C screenshot during development, invoke a single `xcodebuild -only-testing:FitrahTubeUITests/ScreenshotTests/<case> -destination …` by hand; add permanent blocks only in Task 6.
- **Stale install symptom.** If a UI test launches into a screen that no longer exists in the source (a `PhaseTwoPlaceholderView` where `ChannelDetailScreen` should be, a missing identifier that is clearly present), the simulator is running a stale install: `xcrun simctl uninstall <device-udid> com.albunyaan.tube` and re-run. Do not "fix" source that is already correct.
- **Exhaustive-switch enumeration. Plan C adds NO new `Route` case and NO new `StreamState` case, and must not.** `Route.channel(id:name:avatarURL:)` and `Route.playlist(id:title:category:count:)` already exist (`Route.swift:40-41`) and already carry the arguments both screens need — the metadata fast path, exactly as `PlayerArgs` does. For the record, and so a future change knows what it would break, the complete set of switches over `Route` today is:
  - `ios/FitrahTube/Features/Placeholders/PhaseTwoPlaceholderView.swift:27` (`caseName`) — **exhaustive, no `default`**.
  - `ios/FitrahTube/Features/Placeholders/PhaseTwoPlaceholderView.swift:42` (`arguments`) — **exhaustive**, its catch-all at `:66` lists the cases explicitly.
  - `ios/FitrahTube/Features/Shell/MainShellView.swift:139` (`destination(for:)`) — has `default:` at `:156`, so a new case there **silently renders the placeholder instead of failing to compile**. This is why Tasks 4 and 5 add explicit `case .playlist(…)` / `case .channel(…)` arms *above* that `default:` and each ships a test that pins the arm, rather than trusting the compiler.
  - `ios/FitrahTube/App/Route.swift:57` switches `ContentType`, not `Route`.
  If a later change does add a `Route` case, the first two files are the compile errors; the third is the silent one.
  Similarly, `ChannelTab` (`BrowseClient.swift:66`) has exactly one switch — `var params` (`:73-79`) — and Task 1 changes its case list; that switch is the only site to update.
- **All user-visible strings go through `ios/scripts/convert-strings.py`.** Never hand-edit `Localizable.xcstrings`; regenerate it (`python3 ios/scripts/convert-strings.py`, no `--check`) and confirm the `git diff` shows exactly the keys you intended. `EXTRA_KEYS` raises on a collision with an Android key (`convert-strings.py:378-379`), so a key that later appears on Android fails loudly. **Verified 2026-08-27: every string these screens need already exists in the 780-key catalog** — the five `channel_tab_*` titles, the four per-tab empties, `channel_tab_error_generic`, `channel_subscribers_format`, `channel_subscribers_unknown`, `channel_verified`, `channel_about_*`, `channel_subscribe`/`channel_unsubscribe`, `me_subscription_cap_reached`, `playlist_play_all`, `playlist_shuffle`, `playlist_save`/`playlist_unsave`, `playlist_metadata_format`, `playlist_empty_state`, `playlist_video_position`, `a11y_playlist_video`, `video_views` (substitution plural), `video_count`, `live_badge`, `upcoming_badge`, `live_watching_count`, `live_past_meta`, `load_more`, `load_more_error`, `retry`, `cancel`, `search_hint`, `search_clear`, `search_no_results`, `empty_state_generic_headline`, `error_state_generic_headline`, `content_unavailable_title`/`_message`, all 20 `report_*` keys, all 7 `share_*` keys. **Plan C adds exactly three `EXTRA_KEYS` and one `REFUSE` entry**, named in Tasks 2 and 3. Do not invent a fourth without saying why the existing key is wrong.
- **`share_app_promo` must not say "ad-free".** Its Android value is "Get FitrahTube for ad-free Islamic content!" (`strings.xml:285`); spec D10 and §12's last row require dropping it, and the copy rule below forbids it outright. `SPECIFIER_OVERRIDES` only rewrites format specifiers, so the mechanism is: add `share_app_promo` to `REFUSE` (`convert-strings.py:16`) and re-add it under `EXTRA_KEYS` with the corrected copy in all three locales. Task 3.
- **Copy rules (spec §10, plan §6.6, §9 checklist).** Never the words "ad-free" anywhere in-app. Never a kids-vs-lecture explanation. Degraded-mode copy says *what* the user is seeing ("Showing recent uploads"), never *why* ("YouTube blocked us") — the second is both jargon and an invitation to retry into a block.
- **Every backend call goes through the shared `FitrahAPI` client or the shared `HTTPTransport`, never a bare `URLSession`.** `POST /api/v1/reports` **400s** without `X-Device-Id` (`ContentReportController.java:48-57`, and the rationale comment there), and `ShareMetadataPublisher.kt:16-28` records the shipped P0 that came from exactly this mistake on Android. `FitrahAPIClient.make` installs `DeviceIdMiddleware` (`FitrahAPIClient.swift:29-36`); the container's client is at `AppContainer.swift:93`. Hand-written backend calls follow `BackendAvailabilityGate`'s precedent (`BackendAvailabilityGate.swift:33-37`) for *shape* only — note that it sends `headers: [:]` (`:35`), because a HEAD availability probe is the one backend call that does not need identifying. Every hand-written call in this plan must **add `X-Device-Id` explicitly**, from the shared `FitrahAPI.DeviceId`; do not copy the empty-headers line.
- **`AtomFeedFetcher` has no throttle and its conditional GET is dormant.** Live probe, 5×, 2026-08-23: `feeds/videos.xml` sends neither `ETag` nor `Last-Modified` and ignores `If-Modified-Since` — only `Cache-Control: max-age=900` (`AtomFeedFetcher.swift:18-23`). Do **not** describe the 304 path as a rate-limit strategy, and do not add a throttle inside the package; degraded mode calls `latest(_:)` once per channel-screen open and that is the whole budget.
- **Pagination rule (CLAUDE.md, spec §11).** Every list must auto-trigger `loadMore()` when the loaded items fit on screen — a scroll listener alone never fires on a tablet or TV grid. The two machines that satisfy it are named in ruling 10 and reconciliation note 2; a third is not permitted.
- **Accessibility floor (plan §6.11, spec §14).** ≥44×44 pt tap targets measured on the target, not the glyph. Dynamic Type everywhere, single column at `.accessibility1+`. Every custom control carries label **and** value (`.accessibilityValue`), the idiom `PlayerToolbar.favoriteButton` already uses (`PlayerToolbar.swift:56-61`). Composite strings wrap their arguments in U+2068/U+2069 and go through `Format.localizedFormat`, never `String(localized:)` + `Locale.current` (gate wave-2 W8). Reduce Motion → static skeletons, no shimmer.
- **RTL.** `leading`/`trailing` only, never `left`/`right`; `.forward`/`.backward` symbols; numerals only through `Format`. The collapsing header's banner gradient, the tab strip's scroll direction and the playlist position column all mirror.
- **No new `.md` files.** This plan is the only document Plan C creates. `docs/superpowers/HANDOFF.md`, `docs/superpowers/plans/2026-08-23-ios-phase2a-innertubekit.md`, the B3/B4/B5 plans and any `ios/` peer docs are other agents' work — **never `git add` them**; stage only the exact files each task's commit step names. `ios-remote-config.json` (Task 6) is a `.json`, not an `.md`, and is explicitly in scope.
- **The simulator can prove the screens, not the extraction.** With fixture-backed doubles the whole of both screens — header, five tabs, pagination, footers, search, empty/error/unavailable states, degraded mode, the report sheet, the share sheet — renders and is assertable with no network. What it cannot prove: that YouTube's live Shorts/Playlists tab payloads still match Task 1's fixtures, that a real bot-check trips the fallback, that deep pagination on a 5 000-upload channel actually terminates, and everything on the device list. Task 6 separates the three tiers and marks the device tier USER-BLOCKED (`DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset; `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: NO` is the only reason simulator builds work).
- **Universal Links do not work and no code in this plan makes them work (ruling 65).** `/.well-known/apple-app-site-association` has no handler in `backend/src/main` (grep for "well-known" → zero hits) and returns 403 through Cloudflare (`ios-app-plan.md:303`). `DeepLinkParser` already parses the https shapes; iOS will simply never be *invoked* for them until the AASA is served under a real Team ID. Every https-link acceptance step in Task 6 is therefore run by pasting the URL into the address bar and following the watch page's own 50 ms hop into `albunyaantube://` (`WatchPageController.java:536-542`), which is ruling 74's whole point.

---

## Reconciliation — read all six before writing code

**1. Ruling 3 settles the Videos path, and it settles it in the opposite direction to what the docs say. Do not "fix" the docs from inside this plan.**

`ios-app-plan.md:179` and spec §9 both prescribe `VLUU…` uploads-playlist continuation, justified as "the trick Android uses because channel-tab continuations are unreliable past 1–2 pages" — which quotes `NewPipeChannelDetailRepository.kt:186-188`. That comment is **superseded on Android**: `ChannelDetailViewModel.kt:413-420` records that NewPipe v0.26.2 (#1492) fixed channel tabs and that the UU playlist is now the broken one (`getMoreItems` NPEs on `browseMetadataResponse` after page 1 in v0.26.3), and the live code at `:421-425` prefers the channel tab with UU as a *fallback*.

None of that transfers. Both bugs are **NewPipeExtractor** bugs in a JVM library iOS explicitly does not port (`ios-app-plan.md:251`), and `InnerTubeKit.BrowseClient` is a direct InnerTube client with neither extractor in it. Ruling 3 is the binding line — "Single videos path, provenance flag dropped — the dual path works around NewPipe v0.26 bugs iOS does not inherit — cost: re-add a fallback path if iOS hits its own pagination bugs" — and `BrowseClient.channelVideos` already implements the `VLUU…` path with a two-page fixture proving continuation works (`browse-channel-videos-page1.json` → `-page2.json`). **So: one path, `channelVideos`, no fallback, no provenance flag.** The cost ruling 3 names is real and Task 6 step 5 (the live checks) is where it gets measured: walk a large channel past page 5 against live YouTube and confirm the continuation keeps returning items. If it caps, *that* is when a fallback gets added, with evidence.

The doc conflict is real but it is **not this plan's to resolve** — `ios-app-plan.md` and the spec are other documents, and Plan C creates no `.md` files. Record it as a carry-forward; do not edit them.

**2. There are two pagination machines and that is deliberate (ruling 10). Do not unify them.**

- **Channel tabs** get `ChannelTabAutofill`: at most **1** autofill page on compact, **2** on regular, after which a **"Load more" button** appears in the footer and the user asks for the rest (`BaseChannelListTabFragment.kt:123-175,231-234,399-404`; `ChannelDetailViewModel.kt:331-359`). Plus the scroll trigger at **5 from the end**, downward-only, with a **1 000 ms** minimum between accepted appends and a single re-check **1 100 ms** after a rejected one (`ChannelDetailViewModel.kt:283-289`, `BaseChannelListTabFragment.kt:76-112,410-415`).
- **Playlist detail** gets Phase 1's `PaginationGuard` verbatim (ruling 55, fixing Android's missing autofill — brief Q10), wired exactly as `ContentListView` wires it: `@State private var paginationGuard`, `.onContentFits`, the copy-guard/commit dance at `ContentListView.swift:325-354`, `reset()` on search change.

They differ in the one place that matters: `PaginationGuard.shouldAutoLoad`'s **guard 1 refuses to autofill on compact at all** (`PaginationGuard.swift:45`), while the channel tabs autofill once even on a phone. Passing channel tabs through `PaginationGuard` would silently drop that page; passing the playlist through `ChannelTabAutofill` would surface a "Load more" button Android's playlist screen has never had. Two machines, both small, both pure, both tested — as on Android.

**3. CF-C2, decided: browse bot-checks do NOT escalate the shared `SessionStore` cooldown.**

The shared cooldown is consulted by `StreamResolver` before every resolve (`StreamResolver.swift:127`) and escalated only there (`:250`). Wiring `recordBotCheck()` into `BrowseClient` would mean **one bot-checked channel page silences playback for an hour** — and the ladder goes to 24 h on the fourth trip in a day (`SessionStore.swift:16`). Android's own cooldown is explicitly the other way round: it covers the NewPipe extraction paths and the **player is exempt** (`remote-config-safemode.md` §1.4, `CooldownState.kt:17-35`). Feeding browse trips into a ladder the player consults inverts that, and the failure mode is the worst one this app has: a false positive on a listing page taking the app's core function offline.

It is also unnecessary, because a bot-checked browse already has a correct answer — degraded mode (CF-C3, `ios-app-plan.md:399`: "degraded mode (Atom + approved playlists) is **automatic**"). What Task 1 does instead, and why it is the actual fix:

- **Adopt `visitorData` for the WEB family.** Nothing writes it today — `setVisitorData` is called only by `StreamResolver` for `visionos`/`android` (`StreamResolver.swift:214,242`), so `sessionStore.visitorData(for: .web)` is permanently nil and **every browse request goes out tokenless**. Plan A's headline discovery was that "the first tokenless call is always bot-checked and THAT response carries the visitorData to adopt". Browse never adopts it. This is the root cause of repeated browse bot-checks and it is ~6 lines.
- **`rotate(.web)` on a bot-check**, which is already throttled to once per 10 minutes (`SessionStore.swift:53-64`) and simply clears the stale token so the next call re-bootstraps. No inline retry — the next channel open gets the fresh session, and the current one goes degraded.
- **A browse-only latch** in the app layer (Task 2): one persisted `Date`, flat **1 hour**, its own `KeyValueStore` key, so a bot-checked app stops re-probing `browse` on every channel open. Flat, not a ladder — there is no evidence a ladder is needed, and the resolver's ladder exists because *its* trips are expensive in a way a listing page's are not.

Cost if this is wrong: browse keeps hitting YouTube once per hour while flagged, where escalating would have backed off to 24 h. That is the cheap direction to be wrong in.

**4. The channel header gives us a subscriber *string*, not a number, and the string is detected by an English-only heuristic.**

`BrowseClient.parseHeader` finds the subscriber row by looking for a metadata part whose lowercased text `contains("subscriber")` (`BrowseClient.swift:253`). With `hl=ar` (ruling 19 — device locale) that match fails and `subscriberText` is nil. Two consequences the implementer must not paper over:

- The screen renders `subscriberText` **verbatim** when present and `channel_subscribers_unknown` ("–") when nil. It does **not** feed it through `channel_subscribers_format` ("%s subscribers") — the string already contains the word, and formatting it would print "1.2M subscribers subscribers". Ruling 8 keeps the "–" placeholder; ruling 37's "one formatter" is about numbers we own, and this is a number we never receive.
- **In Arabic the line will read "–" on every channel.** That is a real ceiling, it degrades to the exact placeholder ruling 8 specifies, and fixing it properly means parsing a localized count out of prose — which is worse than the placeholder. Mark it with a `ponytail:` comment naming the ceiling, check it in Task 6's Arabic pass, and carry it forward. Do **not** "fix" it by pinning `hl=en` for the header call: ruling 19 chose device locale deliberately and a mixed-locale session is exactly the incoherence §6.3 exists to prevent.

**5. Ruling 71's "text + URL as separate activity items, subject via `activityViewController(_:subjectForActivityType:)`" is satisfied by `ShareLink`, not by a UIKit wrapper.**

`ShareLink(item: url, subject: Text(title), message: Text(body))` hands `UIActivityViewController` the URL as its own item, the body as a second text item, and the subject through the same channel the delegate method feeds. `PlayerToolbar` already uses the two-argument form (`PlayerToolbar.swift:34`). Building a `UIViewControllerRepresentable` + `UIActivityItemSource` to reach a delegate SwiftUI already calls would be ~60 lines to arrive where one initializer already is. **Do not build it.** The message body is therefore Android's three-block text **minus the URL line** (title + blank + the "in FitrahTube" line + blank + promo), because the URL is the item — a body that also contained the URL would put it on screen twice in Mail and Messages.

**6. The stream-index push has no generated client, and getting one is not the lazy path.**

`POST /api/v1/index/streams` exists on the backend (`IndexController.java:24,55`) but is **absent from `docs/architecture/api-specification.yaml` entirely** — so it is not in the generator's path filter (`openapi-generator-config.yaml` lists 8 paths) and not in `Client.swift`. Adding it means editing the shared spec (which the Vue admin and Kotlin clients also generate from), extending the filter, and re-running `generate-swift-dtos.sh`, which rewrites the 2 912-line `Types.swift` and the 805-line `Client.swift` — a large diff, in a shared contract, for one fire-and-forget POST whose response the client ignores (`ReportApi.kt:11` returns `Response<Void>`; `IndexRepository.kt:23` only logs).

Task 2 therefore hand-writes `IndexClient` over the same `HTTPTransport` `BackendAvailabilityGate` already uses for its hand-written HEAD probe (`BackendAvailabilityGate.swift:33-37`) — ~35 lines, one `Encodable` struct, `X-Device-Id` attached explicitly. Fork C records the alternative. Note the server's shape while writing it: the **≤50 cap is a silent truncation** (`IndexController.java:67-70` `.limit(MAX_ITEMS)`) while bean validation rejects >60 outright (`dto/IndexStreamsRequest.java:12` `@Size(max = 60)`), so batching at 50 is the only size that is neither truncated nor rejected; a byte-identical repeat within 30 s returns **429** (`IndexController.java:74-77`) which, like Android, is logged and dropped, not retried.

---

### Task 1: The two unmodelled tab shapes, and the WEB session browse never had

**Why this is first:** CF-C1 is a hard blocker — two of the five channel tabs cannot render until `BrowseClient` models their payloads, and no fixture for either exists in the repo (`Fixtures/browse-*.json` covers videos p1/p2, playlist, header, live and a synthetic bot-check). Everything else in Plan C is app-target work; this is the only package change, and doing it first means Tasks 2–5 never wait on a capture.

**This task cannot be written blind and this plan does not pretend otherwise.** The Shorts tab's `shortsLockupViewModel` and the Playlists tab's grid tile are named in the `ponytail:` comment at `BrowseClient.swift:170-174` from a 2026-08-24 reading, but nobody has recorded their field paths. Step 1 captures them. Steps 2–3 are written *against the capture*, following `videoItem(_:)`'s existing shape (`:307-345`) as the template. If the capture disagrees with the comment, the capture wins and the comment gets corrected.

**Files:**
- Modify: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/BrowseClient.swift`
- Create: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/LiveBrowseTests.swift` (the capture harness, `INNERTUBE_LIVE`-gated)
- Create: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-channel-shorts.json`, `browse-channel-playlists.json` (**live captures**, trimmed and redacted per Step 1's policy)
- Modify: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-botcheck.json`, `browse-channel-header.json` (**synthetic** `responseContext.visitorData` — C4, Step 1)
- Modify: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/BrowseClientTests.swift`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `public struct PlaylistTile: Sendable, Equatable { id, title, thumbnailURL, itemCountText: String?, channelName: String? }` — a playlist tile carries an item count, not a duration or a view count.
  - `ChannelTab` becomes `{ case live, shorts }`; the playlists `params` token moves onto its own method. **This is the enum's only switch site** (`var params`, `:73-79`).
  - `public func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile>`.
  - `channelTab(_:tab:continuation:)` keeps its `BrowsePage<VideoItem>` return and now really parses `.shorts` (a Short is a `VideoItem` with `durationSeconds` nil — see below).
  - `BrowseClient` adopts `responseContext.visitorData` for `.web` on **every** response that carries one, and rotates **only** when a token was attached and was bot-checked anyway (C3, Step 4 items 5–6).

**Reconciliation — why Shorts reuse `VideoItem` and Playlists do not.** A Short has an id, a title, a thumbnail and a view-count line; every one of those is already a `VideoItem` field, and the consumer (Task 5's 9:16 grid, and `Route.shorts`) needs nothing else. A second struct with the same five fields would have to be kept in sync with `VideoItem` forever and would force a second row-mapping in the app. A playlist tile is genuinely different — its badge is an item count, and there is no duration — so it gets its own type. If the capture shows a Short carrying a duration after all, keep it: the field already exists.

- [ ] **Step 1: Capture the two payloads (do this yourself, with network)**

Create `LiveBrowseTests.swift` following `LiveResolveTests.swift` exactly — `extension Tag { @Tag static var live: Self }` is already declared there (`:5`), so reuse it; the gate is `.enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1")` (`LiveResolveTests.swift:30`).

**`BrowseClient.send(browseId:params:continuation:)` is `private`** (`BrowseClient.swift:198`) and there is no public "give me the raw body" call, so the harness assembles the request from the same **public** pieces `BrowseClient` uses internally — this is not a parallel implementation, it is the same three types in the same order:

```swift
// The capture harness CF-C1 needs. Not a curl: the request must carry the real WEB context, the
// real headers and the real (possibly nil) visitorData, or the payload captured is one the client
// can never receive. BrowseClient.send is private, so use its own ingredients.
@Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
func captureChannelShortsTab() async throws {
    let store = RemoteConfigStore(transport: URLSessionTransport(), keyValueStore: InMemoryStore(),
                                  url: URL(string: "https://example.invalid/none")!)   // never fetched -> bundledDefault
    let context = try #require(await store.current().clients["web"])
    let request = BrowseRequestBuilder().build(
        browseId: channelId, params: ChannelTab.shorts.params, continuation: nil,
        context: context, visitorData: nil, locale: .init(hl: "en", gl: "US"))
    let body = try await URLSessionTransport().send(request).body
    try body.write(to: fixturesDirectory.appending(path: "browse-channel-shorts.json"))
}
```

(`URLSessionTransport` is public — `URLSessionTransport.swift:5`; `BrowseRequestBuilder` and its `build(…)` are public — `BrowseClient.swift:84,103`; `ChannelTab.params` is `internal`, which `@testable import InnerTubeKit` already reaches. `fixturesDirectory` is a `#filePath`-relative URL; keep it in the test file, it has no other consumer.)

Run: `INNERTUBE_LIVE=1 swift test --filter LiveBrowseTests` from `ios/Packages/InnerTubeKit`.

**Which channel.** Start with the one the existing fixtures use — `UCmMcOjsVehVlEOteyrhjI2Q` ("Alafasy", provenance at `BrowseClientTests.swift:8,13`). **No brief records whether that channel has populated Shorts and Playlists tabs** (grep of the research corpus for channel ids returns only `BrowseClientTests.swift`), and a channel with an empty tab captures an empty payload, which teaches the parser nothing. So: **probe 2–3 candidates live before trimming** — open `https://www.youtube.com/channel/<id>/shorts` and `/playlists` in a browser and confirm both are non-empty — and record the chosen id and the date in `BrowseClientTests.swift`'s header comment next to the existing one. It does not have to be the same channel as the other fixtures; a second provenance line is cheaper than a fixture of an empty tab. If no candidate has both, capture them from two different channels and say so.

**Trimming and redaction policy** (applies to both new fixtures, and is why the existing ones look the way they do):

- Keep only what the parser reads — the item container, ~5 items, the continuation item, and (for a header capture) `metadata`/`header`. Delete everything else at that level.
- **Strip every `trackingParams` and `clickTrackingParams`.** They are per-session opaque blobs, they are the bulk of a raw browse response, and none of them is read by any code in this repo.
- **Cap each fixture at ~80 KB.** The two largest existing fixtures are 76 KB and 78 KB and that is the practical ceiling for a file a reviewer can diff; if a trim lands over it, drop items until it does not.
- **Keep a `responseContext` with a `visitorData` value** — see the next paragraph. This is the one place the policy adds rather than removes.

**C4 — the fixtures have no `responseContext.visitorData` to adopt.** Grep of `Fixtures/browse-*.json`: only `browse-botcheck.json` has a `responseContext` at all, and it is `{}` (`:2`). The five real browse fixtures were stripped of it entirely. Step 4's adoption code therefore has nothing to test against, so this task **adds a synthetic value** to exactly two files, and only a synthetic one:

- `browse-botcheck.json` — `"responseContext": {"visitorData": "CgtGSVhUVVJFXzAwMSiFAA%3D%3D"}`. This is the bootstrap trip: a tokenless call comes back bot-checked *carrying the token to adopt* (Plan A's headline finding), and it is what the `adoptSurvivesBootstrapBotCheck` test in Step 2 drives.
- one small real fixture — `browse-channel-header.json` (19 KB, the smallest real one) gets the same synthetic key.

A **synthetic** value, never a captured one: a real `visitorData` is a session identifier for the machine that captured it, and committing one puts a live token in git. Say so in a comment at the top of the fixture's sibling test. Everything else in the two files stays a real capture.

- [ ] **Step 2: Write the failing tests**

In `BrowseClientTests.swift`, mirroring the existing `channelTab(.live)` test's shape (`FixtureTransport` from `Support/FixtureTransport.swift:5`):

```swift
@Test func shortsTabParsesIntoVideoItemsWithNoDuration() async throws {
    // CF-C1: `channelTab(.shorts)` returned an EMPTY page (BrowseClient.swift:170-174 ponytail note)
    // because `shortsLockupViewModel` is not the plain `lockupViewModel` the video parser reads.
    // Assert the real ids from the capture, and that the 9:16 grid's fields are all populated.
    let page = try await client.channelTab("UCmMcOjsVehVlEOteyrhjI2Q", tab: .shorts, continuation: nil)
    #expect(page.items.count == 5)
    #expect(page.items[0].id == "<from the capture>")
    #expect(page.items[0].thumbnailURL != nil)
    #expect(page.items[0].viewCountText != nil)
    #expect(page.items[0].durationSeconds == nil)   // Shorts tiles carry no duration badge
    #expect(page.items[0].channelId == "UCmMcOjsVehVlEOteyrhjI2Q")  // backfill, as .live already does
    #expect(page.nextContinuation != nil)
}

@Test func playlistsTabParsesIntoPlaylistTilesWithAnItemCount() async throws {
    let page = try await client.channelPlaylists("UCmMcOjsVehVlEOteyrhjI2Q", continuation: nil)
    #expect(page.items.count == 5)
    #expect(page.items[0].id == "<from the capture>")
    #expect(page.items[0].itemCountText != nil)     // the badge is a count, not a duration
}

@Test func aBrowseResponseCarryingVisitorDataIsAdopted() async throws {
    // Nothing wrote `.web` visitorData before this task -- `setVisitorData` is called only by
    // StreamResolver, for visionos/android (StreamResolver.swift:214,242) -- so every browse
    // request has gone out tokenless, which is exactly what Plan A's "the first tokenless call is
    // always bot-checked" finding predicts will keep happening.
    _ = try await client.channelHeader(channelId)          // the fixture with the synthetic token
    #expect(await sessionStore.visitorData(for: .web) == "CgtGSVhUVVJFXzAwMSiFAA%3D%3D")
}

@Test func aBootstrapBotCheckStillAdoptsItsTokenAndDoesNotRotateItAway() async throws {
    // THE ordering test (C3). Adoption happens on EVERY response that carries a token -- including
    // the interstitial, which is the whole point of Plan A's finding -- and `rotate(.web)` CLEARS
    // the family (SessionStore.swift:52-64, `keyValueStore.set(key, Data())`). So rotating on a
    // bootstrap bot-check would throw away the very token that makes the next call succeed, and
    // the client would bootstrap forever. No token was attached here, so nothing is stale.
    await #expect(throws: BrowseError.botCheck) { try await client.channelVideos(channelId, continuation: nil) }
    #expect(await sessionStore.visitorData(for: .web) == "CgtGSVhUVVJFXzAwMSiFAA%3D%3D")   // SURVIVES
    #expect(await sessionStore.cooldownRemaining(now: .now) == nil)                        // CF-C2: never escalate
}

@Test func aBotCheckWithATokenAlreadyAttachedRotatesItAsStale() async throws {
    // The other half: we sent a token and were bot-checked anyway, so the token is burnt. rotate()
    // clears it (throttled to 1/10 min by SessionStore itself) and the next call re-bootstraps.
    await sessionStore.setVisitorData("STALE", for: .web)
    await #expect(throws: BrowseError.botCheck) { try await client.channelVideos(channelId, continuation: nil) }
    // The interstitial's own token is adopted first, then rotate clears the family -- the net
    // effect is "no token", which is the correct state for a session YouTube has just rejected.
    #expect(await sessionStore.visitorData(for: .web) == nil)
    #expect(await sessionStore.cooldownRemaining(now: .now) == nil)                        // CF-C2
}

@Test func theSecondPageSendsTheAdoptedVisitorDataAsAHeader() async throws {
    // Use `RecordingTransport` (Support/FixtureTransport.swift:39) and read the recorded request
    // headers: X-Goog-Visitor-Id must be present on the continuation call.
}
```

- [ ] **Step 3: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `PlaylistTile` and `channelPlaylists` do not exist; then assertion failures on the two tab tests (empty pages) and on both session tests.

- [ ] **Step 4: Implement**

1. **`PlaylistTile`** next to `VideoItem`, same doc-comment discipline (say where the shape was captured and when).
2. **`ChannelTab`** loses `.playlists`; its `params` switch shrinks to two cases. Move `"EglwbGF5bGlzdHPyBgQKAkIA"` to a `private static let playlistsTabParams` on `BrowseClient` with the same "captured live, forwarded verbatim, nothing decodes it" comment.
3. **`channelPlaylists(_:continuation:)`** — the same `send` → parse → return shape `channelTab` has, with its own `parsePlaylistPage`. Reuse `itemsArray(_:)` (`:269`) unchanged if the capture's container is one of the three it already handles; extend it only if the capture shows a fourth, and say which in a comment.
4. **Shorts parsing** — extend `lockupViewModel(_:)` (`:298`) to also accept the Shorts renderer key, and `videoItem(_:)` (`:307`) to read the Shorts variant's title/thumbnail/stats paths. Keep both functions tolerant: an unrecognised item still yields `nil` and is skipped, never a crash and never a mis-parse.
5. **visitorData adoption**, in `send(browseId:params:continuation:)` (`:198`): after the transport returns and **before** returning the body, decode just `responseContext.visitorData` (one `dig` call — do not add a Codable tree) and `await sessionStore.setVisitorData(visitor, for: .web)` when it is present and non-empty. `send` returns `Data` and the bot-check throw happens later, in `parsePage`/`parseHeader` — so putting adoption here is what makes the **interstitial's own token** get adopted, which is Plan A's finding and what makes the next call succeed.
6. **Rotation on a *stale* token only.** `rotate(.web)` **clears** the family (`SessionStore.swift:52-64` writes empty `Data`), so rotating on the bootstrap trip would discard the token step 5 just adopted and the client would bootstrap forever. The rule: rotate **iff a token was attached to the request and the response was still bot-checked**. Mechanically, `send` cannot do this — it never sees the throw — so:
   - `send` returns the visitorData it sent along with the body (a two-field tuple or a tiny private struct; it already has the value in hand at `:205`).
   - Each of the four public methods wraps its `try Self.parse…` in `do { … } catch BrowseError.botCheck { if sentVisitorData != nil { _ = await sessionStore.rotate(.web) }; throw BrowseError.botCheck }`. Four three-line `catch`es beat threading a closure through the parser.
   - **Do not call `recordBotCheck()` anywhere.** Write the reconciliation-note-3 reasoning into a comment next to the rotate, so the next reader does not "fix" the omission.
7. Correct the `ponytail:` comment at `:170-174`: it now describes what *is* modelled, and what still is not (community/posts tabs, which NewPipe cannot extract either — brief §0.2).

- [ ] **Step 5: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`
Expected: green. Note the InnerTubeKit suite is the bulk of the 300 s budget — if the gate now trips the watchdog, that is a finding for the controller (the fix is a test-plan split, not deleting tests).

- [ ] **Step 6: Commit**

```bash
git add ios/Packages/InnerTubeKit/Sources/InnerTubeKit/BrowseClient.swift \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/BrowseClientTests.swift \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/LiveBrowseTests.swift \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-channel-shorts.json \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-channel-playlists.json \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-botcheck.json \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-channel-header.json
git commit -m "[FEAT]: iOS browse shorts and playlist tabs"
```

---

### Task 2: The browse data layer — pagination, exclusions, the index push, and degraded mode

**Why this is second:** both screens are assembly over this, and every decision worth testing lives here rather than in a SwiftUI body. Nothing in this task imports SwiftUI, so all of it runs in the unit suite with no simulator.

**Files:**
- Create: `ios/FitrahTube/Features/Detail/BrowseSource.swift` (`BrowseSource` protocol, `LiveBrowseSource`, `BrowseFallback`)
- Create: `ios/FitrahTube/Features/Detail/ChannelTabPagination.swift` (`TabState`, `ChannelTabAutofill`, `SearchFilter`)
- Create: `ios/FitrahTube/Features/Detail/ListFooter.swift` (the LoadMore / Loading / Error footer)
- Create: `ios/FitrahTube/Catalog/IndexClient.swift`
- Modify: `ios/FitrahTube/Catalog/BackendAvailabilityGate.swift` (a playlist path)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (register `browse`, `index`)
- Modify: `ios/scripts/convert-strings.py` (`EXTRA_KEYS` — one addition)
- Modify: `ios/FitrahTube/Resources/Localizable.xcstrings` (**regenerated by the script, never hand-edited**)
- Test: `ios/FitrahTubeTests/ChannelTabPaginationTests.swift` (new), `ios/FitrahTubeTests/BrowseFallbackTests.swift` (new), `ios/FitrahTubeTests/IndexClientTests.swift` (new), `ios/FitrahTubeTests/BackendAvailabilityGateTests.swift` (extend)

**Interfaces:**
- Consumes: Task 1's `BrowseClient` surface; `InnerTubeKit.AtomFeedFetcher`; `FitrahAPI`'s `getPublicChannel` / `searchPublicContent` (both generated, both with **zero call sites today** — free to use); `InnerTubeKit.HTTPTransport`.
- Produces:
  - `protocol BrowseSource: Sendable` — `channelHeader`, `channelVideos`, `channelTab`, `channelPlaylists`, `playlistItems`, each `async throws`, each returning the InnerTubeKit page types. One protocol, two implementations (`LiveBrowseSource` and the test double); it exists because the two screens need a seam the simulator can drive, which is the same reason `StreamResolving` exists.
  - `nonisolated struct TabState: Equatable` — `.idle | .loadingInitial | .loaded(items:continuation:isAppending:showsLoadMore:) | .empty | .errorInitial(messageKey:) | .errorAppend(messageKey:items:continuation:showsLoadMore:)`. This is the Android machine (`ChannelDetailViewModel.kt:1091-1108`) with the error payloads carried, so an append failure never blanks a populated list.
  - `nonisolated struct ChannelTabAutofill` — `mutating func shouldAutoLoad(widthClass:hasMore:isAppending:contentFits:) -> Bool`, `mutating func recordAppend(accepted:at:)`, `var showsLoadMore: Bool`, `mutating func reset()`. Caps: 1 compact / 2 regular; 1 000 ms minimum interval; one 1 100 ms re-check.
  - `nonisolated enum SearchFilter { static func apply(_ items: [VideoItem], query: String) -> [VideoItem] }` (+ a `PlaylistTile` overload) — trimmed, case- and diacritic-insensitive substring over title **or** channel name.
  - `nonisolated enum BrowseFallback { static func decide(_ error: any Error, latchedUntil: Date?, now: Date) -> Decision }` with `Decision = .surfaceError | .degrade(latchUntil: Date) | .alreadyDegraded`, and `static let latchDuration: TimeInterval = 3600`.
  - `struct DegradedLatch: Sendable` — the persistence half, deliberately three lines so the test above has something to drive rather than a `Date?` threaded through a view model:
    ```swift
    /// Key namespace matches InnerTubeKit's own (`SessionStore.swift:11-12`) so everything this app
    /// persists under UserDefaults is greppable from one prefix.
    struct DegradedLatch: Sendable {
        static let key = "FitrahTube.Browse.degradedUntil"
        let store: any KeyValueStore                       // the SAME UserDefaults-backed store
        var until: Date? {                                 // InnerTubeKit already uses -- do not add a second
            get { store.get(Self.key).flatMap { try? JSONDecoder().decode(Date.self, from: $0) } }
            nonmutating set { if let d = try? JSONEncoder().encode(newValue) { store.set(Self.key, d) } }
        }
    }
    ```
    `LiveBrowseSource` reads `latch.until` before each browse and writes it on a `.degrade`; the test injects an in-memory `KeyValueStore` (InnerTubeKit's test double already exists) and asserts the second open never reaches the transport.
  - `struct IndexClient: Sendable` — `func push(sourceType: SourceType, sourceId: String, items: [VideoItem]) async`.
  - `BackendAvailabilityGate.verify(playlistId:)`.
  - One localized key: `browse_degraded_notice`.

**Reconciliation — degraded mode is three different substitutions, not one.** Spec §9 lists "approved playlists (`/api/v1/channels/{id}`) + Atom feed + indexed search" as one phrase, but each replaces a different surface and they are not interchangeable:

| Bot-checked call | Degraded substitute | What the user loses |
|---|---|---|
| `channelHeader` | the `getPublicChannel` DTO's own name/thumbnail | banner, subscriber line, verified badge |
| `channelVideos` | `AtomFeedFetcher.latest(channelId)` — **15 items, no pagination, no duration, no view count** | everything past the 15 newest; the duration chip and the views line on every row |
| `channelPlaylists` | **nothing** — `channel_tab_error_generic` with Retry (see below) | the tab |
| `channelTab(.live)` / `(.shorts)` | **nothing** — same error row | the tab |
| `playlistItems` | **nothing** — the playlist screen shows its error state with Retry | the screen |
| in-header search | `searchPublicContent(q:type:)` — the backend's index | matches YouTube has but the index does not |

**Playlists has no substitute, contrary to spec §9's phrasing.** The spec's "approved playlists (`/api/v1/channels/{id}`)" describes an endpoint that does not exist in this shape: `getPublicChannel` returns a **raw `Channel`** DTO (`api-specification.yaml` `/v1/channels/{channelId}`) — no approved-playlist array, no embedded list of any kind — and there is **no channel-scoped playlist endpoint** anywhere in the spec or in `Client.swift`'s eight operations. `getPublicPlaylist` resolves one playlist *by id*, which is no help when the id is what we are missing. So the substitution cannot be built from the API that exists, and inventing a client-side approximation (e.g. filtering `getPublicContent(type: .playlists)` by uploader) would list the catalog's playlists rather than *this channel's*. Playlists therefore joins Live and Shorts on the error row, and "a channel-scoped approved-playlists endpoint" goes on the BACKEND-BLOCKED list (Task 6 step 7). Cost while it is blocked: a bot-checked channel shows Videos (Atom) and About, and three tabs offering Retry — which is honest.

So `BrowseFallback` decides *whether* to degrade; each call site owns *what to substitute*, and **four of the six substitute nothing**. A single "degraded mode" flag that pretended all six were covered would render an empty Live tab as if it were a real empty channel — which is ruling 54's network-vs-empty confusion in a different costume.

- [ ] **Step 1: Write the failing tests**

`ChannelTabPaginationTests.swift` — all pure, no `await`:

```swift
@Test func compactAutofillsExactlyOnePageThenOffersTheButton() {
    // ruling 10 / BaseChannelListTabFragment.kt:231-234,399-404 -- the channel tabs' cap is 1 on a
    // phone and 2 at >=600pt, and what follows the cap is a BUTTON, not silence. This is the whole
    // reason PaginationGuard (which refuses to autofill on compact at all) is not reused here.
    var a = ChannelTabAutofill()
    #expect(a.shouldAutoLoad(widthClass: .compact, hasMore: true, isAppending: false, contentFits: true))
    a.recordAppend(accepted: true, at: .now)
    #expect(a.shouldAutoLoad(widthClass: .compact, hasMore: true, isAppending: false, contentFits: true) == false)
    #expect(a.showsLoadMore)
}

@Test func regularAutofillsTwice() { … widthClass: .regular … }

@Test func appendsAreRateLimitedToOnePerSecond() {
    // ChannelDetailViewModel.kt:283-289 + MIN_APPEND_INTERVAL_MS (:1137).
    var a = ChannelTabAutofill()
    let t0 = Date(timeIntervalSince1970: 0)
    a.recordAppend(accepted: true, at: t0)
    #expect(a.accepts(at: t0.addingTimeInterval(0.9)) == false)
    #expect(a.accepts(at: t0.addingTimeInterval(1.0)))
}

@Test func aRejectedAppendSchedulesExactlyOneRecheck() { … 1.1 s, max 1 … }

@Test func loadMoreTapResetsTheAutofillBudget() {
    // BaseChannelListTabFragment.kt:216-225,240-243 -- an explicit tap renews the counter, so a
    // second screenful can autofill again. Without this the button appears once and then the list
    // is manual forever.
}

@Test func searchDisablesPaginationByDroppingTheCursor() {
    // BaseChannelListTabFragment.kt:269-294 / PlaylistDetailFragment.kt:336 -- the filtered Loaded
    // state carries nextPage = nil, so the near-end trigger cannot fire mid-search.
    let filtered = TabState.loaded(items: items, continuation: "TOKEN", isAppending: false, showsLoadMore: false)
        .filtered(query: "nasheed")
    #expect(filtered.continuation == nil)
}

@Test func zeroMatchesRendersTheSearchNoResultsCopyNotTheTabsEmptyCopy() {
    // RULING 5, fixing Android defect 6 (brief 5.4): Android shows "This channel has no videos yet"
    // when a search matches nothing, which is a lie about the channel.
    #expect(TabState.loaded(items: items, …).filtered(query: "zzzz").emptyMessageKey == "search_no_results")
    #expect(TabState.empty.emptyMessageKey == "channel_videos_empty")
}

@Test func appendFailureKeepsTheItemsAndTheCursor() {
    // ChannelDetailViewModel.kt:951-979 -- an append error must never blank a populated list.
}
```

`BrowseFallbackTests.swift`:

```swift
@Test func aBotCheckDegradesAndLatchesForOneHour() {
    // CF-C3 + reconciliation note 3: flat 1 h, not the resolver's 1h->24h ladder.
    let now = Date(timeIntervalSince1970: 1_000)
    guard case .degrade(let until) = BrowseFallback.decide(BrowseError.botCheck, latchedUntil: nil, now: now)
    else { Issue.record("expected .degrade"); return }
    #expect(until == now.addingTimeInterval(3600))
}

@Test func aLiveLatchSkipsTheProbeEntirely() {
    #expect(BrowseFallback.decide(BrowseError.botCheck, latchedUntil: now + 60, now: now) == .alreadyDegraded)
}

@Test func anExpiredLatchProbesAgain() { … latchedUntil: now - 1 … }

@Test func theLatchSurvivesAcrossSourceInstances() {
    // The persistence half (DegradedLatch, below). A latch held only in a view model is reset by
    // every back-navigation, which is exactly the re-probe-on-every-open it exists to stop.
    let store = InMemoryKeyValueStore()
    DegradedLatch(store: store).until = now.addingTimeInterval(3600)
    #expect(DegradedLatch(store: store).until != nil)
}

@Test func aTransportErrorIsSurfacedNotDegraded() {
    // Degraded mode answers a BLOCK, not a flaky network. An offline user gets the error state and
    // a Retry, which is honest; silently showing 15 Atom items would read as "this channel has 15
    // videos" forever.
    #expect(BrowseFallback.decide(URLError(.timedOut), latchedUntil: nil, now: now) == .surfaceError)
    #expect(BrowseFallback.decide(BrowseError.malformed, latchedUntil: nil, now: now) == .surfaceError)
}
```

`IndexClientTests.swift` (over a fake `HTTPTransport`, the same double `BackendAvailabilityGateTests` uses):

```swift
@Test func pushBatchesAtFiftyItems() {
    // IndexController.java:67-70 silently truncates above 50 while dto/IndexStreamsRequest.java:12
    // rejects above 60 outright -- 50 is the only size that is neither truncated nor 400'd.
}
@Test func pushSendsTheDeviceIdHeader() { … }
@Test func a429IsSwallowedNotRetried() {
    // IndexController.java:74-77 returns 429 for a byte-identical repeat within 30 s. Android logs
    // and drops (IndexRepository.kt:23); a retry would just re-trip the same dedupe key.
}
@Test func pushNeverThrowsAndNeverBlocksTheCaller() { … }
@Test func anEmptyItemListSendsNothing() { /* IndexRepository.kt:18 */ }
```

`BackendAvailabilityGateTests.swift`, extended:

```swift
@Test func aPlaylistReturning410IsBlocked() {
    // RULING 15 + PlaylistDetailViewModel.kt:112-124. The gate had channels and videos only
    // (BackendAvailabilityGate.swift:34); the playlist screen needs the third path.
}
@Test func aPlaylistTransportFailureFailsOpen() { … }
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — none of `ChannelTabAutofill`, `TabState`, `BrowseFallback`, `IndexClient` exists, and `BackendAvailabilityGate` has no playlist path.

- [ ] **Step 3: Implement**

1. **`TabState` + `SearchFilter`** — value types, `Equatable`, no dependency beyond InnerTubeKit's item structs. `filtered(query:)` returns a `TabState`, so the view has no branching of its own.
2. **`ChannelTabAutofill`** — mirror `PaginationGuard`'s discipline (`PaginationGuard.swift`): a `generation` bumped only by `reset()`, so an attempt copied before a search change is refused at commit time. That bug is already recorded once (gate wave-4 V1/V6) and this machine has the same shape.
3. **`ListFooter`** — a small `View` with three states, driven **only** from `TabState` (`isAppending` → spinner, `showsLoadMore && continuation != nil` → button, `.errorAppend` → message + Retry; `ListFooterAdapter.kt:40-49`, `BaseChannelListTabFragment.kt:326-332`). Ruling 47 is satisfied here once, for both screens.
4. **`IndexClient`** — one `Encodable` request mirroring `IndexStreamsRequest.kt:7-23` field-for-field (`sourceType`, `sourceId`, `items[{id,name,thumbnailUrl,uploaderName,channelId,duration,viewCount,streamType}]`), **`X-Device-Id` explicitly added** from the shared `FitrahAPI.DeviceId` (`BackendAvailabilityGate` sends no headers at all — do not copy that line), `chunked(50)`, every failure logged under `#if DEBUG` and dropped. Fire-and-forget: callers do `Task { await index.push(…) }` and never await it in a path the user is waiting on.
5. **`LiveBrowseSource`** — thin: gate → browse → `BrowseFallback.decide` on throw → substitute per the table above → push to the index on success. **Untested by design**, the same rule B1 applies to `LiveStreamResolver` and B5 to `LivePlaylistQueueSource`: everything decidable is in the pure types above, everything here is a client's own behaviour. Say so in its doc comment.
6. **`BackendAvailabilityGate`** — one line: `"api/v1/playlists/\(id)"` alongside the existing channel/video paths, with the same 404-fail-open / 410-block / transport-fail-open semantics.
7. **`AppContainer`** — register `browse: any BrowseSource` and `index: IndexClient` next to `innerTube` (`AppContainer.swift:77-83`), plus the `#if DEBUG` fakes the two screens' previews and UI tests need, following `sharedFake`'s existing pattern.
8. **`EXTRA_KEYS`** — one key, then regenerate:
   ```python
       # Degraded browse (Plan C task 2, CF-C3). iOS-only: Android has no degraded mode at all --
       # a bot-checked NewPipe call just surfaces an error -- so there is no source string to port.
       # Copy rule (plan Global Constraints): say WHAT is shown, never why. "Blocked by YouTube"
       # would be both jargon and an invitation to retry into a block.
       "browse_degraded_notice": {
           "en": "Showing recent uploads only",
           "ar": "عرض أحدث المقاطع فقط",
           "nl": "Alleen recente uploads worden getoond",
       },
   ```

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`
Expected: green, including `convert-strings.py --check`.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Detail/BrowseSource.swift \
        ios/FitrahTube/Features/Detail/ChannelTabPagination.swift \
        ios/FitrahTube/Features/Detail/ListFooter.swift \
        ios/FitrahTube/Catalog/IndexClient.swift \
        ios/FitrahTube/Catalog/BackendAvailabilityGate.swift \
        ios/FitrahTube/App/AppContainer.swift \
        ios/scripts/convert-strings.py \
        ios/FitrahTube/Resources/Localizable.xcstrings \
        ios/FitrahTubeTests/ChannelTabPaginationTests.swift \
        ios/FitrahTubeTests/BrowseFallbackTests.swift \
        ios/FitrahTubeTests/IndexClientTests.swift \
        ios/FitrahTubeTests/BackendAvailabilityGateTests.swift
git commit -m "[FEAT]: iOS browse layer with degraded fallback"
```

---

### Task 3: The detail kebab — share links, and the real report flow

**Why this is third:** it retires two "coming soon" banners and it is what Tasks 4 and 5 mount, so building it before the screens means neither screen grows its own copy. It also has no dependency on Tasks 1–2, so if the live capture in Task 1 stalls, this can proceed.

**Files:**
- Create: `ios/FitrahTube/Features/Report/ReportPayload.swift` (pure: `ReportContext`, `ReportReason`, `ReportPayload`, `ReportState`)
- Create: `ios/FitrahTube/Features/Report/ReportSheet.swift` (the sheet + its `@Observable` model)
- Create: `ios/FitrahTube/Features/Report/ReportClient.swift` (the `FitrahAPI` wrapper)
- Create: `ios/FitrahTube/Features/Detail/ShareLinks.swift` (pure: three URL builders, three message builders)
- Create: `ios/FitrahTube/Features/Detail/DetailKebab.swift` (Share + Report menu, shared by both screens)
- Modify: `ios/FitrahTube/Features/Player/PlayerToolbar.swift` (real Report; share URL through `ShareLinks`)
- Modify: `ios/FitrahTube/Features/Player/PlayerMetadataView.swift` (CF-B1-10)
- Modify: `ios/FitrahTube/Features/Player/ShortsOverlay.swift` **only if B4 has landed** — see the note below
- Modify: `ios/FitrahTube/App/AppContainer.swift` (register `report`)
- Modify: `ios/scripts/convert-strings.py` (`REFUSE` + `EXTRA_KEYS` — two additions)
- Modify: `ios/FitrahTube/Resources/Localizable.xcstrings` (regenerated)
- Test: `ios/FitrahTubeTests/ReportPayloadTests.swift` (new), `ios/FitrahTubeTests/ShareLinksTests.swift` (new), `ios/FitrahTubeTests/PlayerMetadataViewTests.swift` (extend or create)

**Interfaces:**
- Consumes: `InnerTubeKit.HTTPTransport` (the same seam Task 2's `IndexClient` uses) and `FitrahAPI.DeviceId`. **Not** the generated `submitContentReport` — see the reconciliation note below. The generated `ReasonsPayloadPayload`'s 11 cases (`Types.swift:2629`) are still the reference for the wire strings, and they match the 11 `report_reason_*` catalog keys 1:1.
- Produces:
  - `nonisolated struct ReportContext: Equatable, Sendable { targetType, targetId, parentType: ParentType?, parentId: String?, contentSubType: ContentSubType? }` — **the only way to construct a report.** Ruling 66 is enforced by there being no shorter initializer.
  - `nonisolated enum ReportReason: String, CaseIterable` — 11 cases in the sheet's visual order (`ContentReportBottomSheet.kt:98-113`), each with its `report_reason_*` key.
  - `nonisolated struct ReportBody: Encodable, Equatable` — the seven wire fields, `parentType`/`parentId`/`contentSubType` optional and omitted when nil.
  - `nonisolated enum ReportPayload { static func make(context:reasons:otherText:) -> Result<ReportBody, ReportValidation> }` and `static let maxReasons = 10`.
  - `ReportState = .idle | .submitting | .succeeded | .rateLimited | .failed(messageKey:)`.
  - `nonisolated enum ShareLinks` — `video(id) / channel(id) / playlist(id) -> URL` and `message(for:title:locale:) -> String`. All three builders produce `https://app.fitrahtube.com/...` URLs only (`ShareLinksTests.swift` below, `:689-691`) — never a `youtube.com`/`youtu.be` link. *(Owner directive 2026-08-27: no share or deep-link surface may produce a YouTube link; already the case here — stated for the record.)*
  - `DetailKebab(share: ShareLinks.Target, report: ReportContext)` — a `Menu` with two items and nothing else.

**Reconciliation — the report body cannot go through the generated client, so `ReportClient` is hand-written.**

`submitContentReport` exists in `Client.swift:674` and its 429 is modelled, but its request body carries **only four fields** — `targetType`, `targetId`, `reasons`, `otherDescription` (`Types.swift:2605-2660`), because `docs/architecture/api-specification.yaml:341-363` declares only those four. The backend accepts three more: `parentType`, `parentId`, `contentSubType` (`ContentReportController.java:165-171`, with a comment saying exactly what they are for — "set when reporting an item from a channel- or playlist-detail screen … so the resolve flow puts the exclusion in the correct bucket"). The spec is simply behind the controller.

Spec §10 requires the parent context ("Report (VIDEO, with parent PLAYLIST/CHANNEL and subtype)") and ruling 66 makes it the whole point of the flow, so a client that cannot send those three fields is not shippable. The two ways forward are: edit the shared OpenAPI document and regenerate (rewriting the 2 912-line `Types.swift` and 805-line `Client.swift`, in a contract the Vue admin and Kotlin clients also generate from), or hand-write one POST. **Hand-write it** — same call, same decision and the same ~35 lines as reconciliation note 6's `IndexClient`, and it lands in a plan that is already writing that shape once. Adding the three optional fields to the spec goes on the BACKEND-BLOCKED list (Task 6 step 7); when they land, `ReportClient` collapses into the generated call and `ReportBody` is deleted (CF-C-7).

**Reconciliation — three more places iOS deliberately differs from the Android sheet.**

1. **The reason list is capped at 10, not 11.** The backend validates `@NotEmpty @Size(max = 10) List<ReportReason> reasons` (`ContentReportController.java:161`) while the enum has **11** values (`ReportModels.kt:26-29`). A user who checks every box on Android gets a 400 and the generic "Failed to submit report." — a live, reachable bug that Android's UI does not guard. iOS caps selection at 10: the unselected rows go disabled with `report_reason_limit` as their accessibility hint. Silently truncating the list instead would submit a report that says something the user did not say.
2. **429 keeps the sheet open (ruling 72).** `ContentReportBottomSheet.kt:131-135` dismisses on `RateLimited`, discarding every checkbox and the typed description — while its own `Error` path keeps them. The asymmetry is user-hostile and unexplained. iOS shows `report_rate_limited` inline, leaves Submit enabled, and keeps the selection.
3. **The validation copy is localized.** `ReportViewModel.kt:34-37,49` hardcodes English ("Please select at least one reason.", "Failed to submit report.") while the localized keys `report_select_reason` and `report_error` sit unused in `strings.xml:639-640`. iOS uses the keys (ruling 70). Both already exist in the catalog with `ar`/`nl` present at `needs_review` — the converter's R7 fallback, which ruling 70 accepts until translations land.

**Note on the Shorts entry point.** B4's Shorts kebab shows the same `player_report_coming_soon` banner deliberately, "so Plan C has one flow to wire, not two" (B4 plan, CF-B1-9 row), and B4 owns `contentSubType = "SHORT"` / `parentType = CHANNEL` as *Plan C's payload concern*. If `ShortsOverlay.swift` exists at HEAD when you start, replace its banner with `ReportContext(targetType: .video, targetId: args.videoId, parentType: args.channelId.map { _ in .channel }, parentId: args.channelId, contentSubType: .short)`. If B4 has not landed, **skip that file** and record it — do not create a stub Shorts screen to have something to edit.

- [ ] **Step 1: Write the failing tests**

`ReportPayloadTests.swift`:

```swift
@Test func aVideoReportedFromAChannelTabCarriesTheChannelParent() {
    // RULING 66 + spec 10 ("Report (VIDEO, with parent PLAYLIST/CHANNEL and subtype)"). Android's
    // 5-arg newInstance (ContentReportBottomSheet.kt:178-184) supports exactly this and is never
    // called from anywhere (all three call sites use the 2-arg form) -- iOS ships the wire format
    // Android defined but never sent.
    let ctx = ReportContext(targetType: .video, targetId: "abc", parentType: .channel,
                            parentId: "UC123", contentSubType: nil)
    let body = try #require(ReportPayload.make(context: ctx, reasons: [.music], otherText: nil).get())
    #expect(body.targetType == "VIDEO")
    #expect(body.parentType == "CHANNEL")
    #expect(body.parentId == "UC123")
    // The three parent fields are exactly what the generated client CANNOT send (the OpenAPI
    // schema omits them, api-specification.yaml:341-363) -- which is why ReportClient is
    // hand-written. This test is the reason that decision exists.
}

@Test func nilParentFieldsAreOmittedFromTheJSONEntirely() throws {
    // Encoding them as explicit nulls is not the same as omitting them: the controller's record
    // binds absent and null identically today, but a `@Size(max = 128) String parentId` that
    // arrives as "" would be kept. Optional + default encoder = omitted; pin it on the bytes.
    let body = try #require(ReportPayload.make(context: channelSelfReport, reasons: [.other], otherText: "x").get())
    let json = String(decoding: try JSONEncoder().encode(body), as: UTF8.self)
    #expect(json.contains("parentType") == false)
}

@Test func aLiveRowCarriesTheLivestreamSubtype() { /* ChannelLiveTabFragment.kt:62-69 */ }
@Test func aShortCarriesTheShortSubtypeAndItsChannelParent() { /* ChannelShortsTabFragment.kt:89-101 */ }
@Test func aChannelReportingItselfCarriesNoParent() { /* ChannelDetailFragment.kt:449-453 */ }

@Test func aBlankParentIdIsCoercedToNil() {
    // ReportRepository.kt:41 -- the backend keeps parent context only when parentType is
    // CHANNEL/PLAYLIST *and* parentId is non-blank (ContentReportService.java:83-93); sending an
    // empty string just gets it dropped server-side, so drop it here where it can be tested.
}

@Test func zeroReasonsIsRefusedWithoutANetworkCall() {
    // ReportViewModel.kt:34-37, but with the localized key Android leaves unused (RULING 70).
    #expect(ReportPayload.make(context: ctx, reasons: [], otherText: nil)
        == .failure(.noReasons(messageKey: "report_select_reason")))
}

@Test func elevenReasonsCannotBeConstructed() {
    // ContentReportController.java:161 validates @Size(max = 10) against an 11-value enum. Android
    // lets the user check all 11 and eats a 400. The cap is here so the UI can render the eleventh
    // row disabled rather than discovering the limit from a server error.
    #expect(ReportReason.allCases.count == 11)
    #expect(ReportPayload.maxReasons == 10)
    #expect(ReportPayload.make(context: ctx, reasons: ReportReason.allCases, otherText: nil).isFailure)
}

@Test func otherDescriptionIsTrimmedAndCappedAtFiveHundred() {
    // bottom_sheet_content_report.xml:124-142 (maxLength 500) and the server's @Size(max = 500).
}

@Test func otherDescriptionIsDroppedWhenOtherIsNotSelected() {
    // ContentReportBottomSheet.kt:76 clears the field when the box is unchecked.
}

@Test func everyReasonHasACatalogKey() {
    // RULING 70. All 11 report_reason_* keys already exist; this pins the mapping so a renamed
    // enum case cannot silently render a raw key on screen.
    for reason in ReportReason.allCases { #expect(Bundle.main.localizedString(forKey: reason.messageKey, value: nil, table: nil) != reason.messageKey) }
}
```

`ShareLinksTests.swift`:

```swift
@Test func theThreeShareURLsMatchAndroidExactly() {
    // ShareLinks.kt:8-73 -- note video maps to the path segment "watch", and the /api prefix is
    // ALWAYS present on shared links (the non-/api routes exist only for inbound).
    #expect(ShareLinks.video("abc") == URL(string: "https://app.fitrahtube.com/api/watch/abc"))
    #expect(ShareLinks.channel("UC1") == URL(string: "https://app.fitrahtube.com/api/channel/UC1"))
    #expect(ShareLinks.playlist("PL1") == URL(string: "https://app.fitrahtube.com/api/playlist/PL1"))
}

@Test func everyShareURLRoundTripsThroughOurOwnDeepLinkParser() {
    // RULING 74 + the backend watch page's 50 ms hop (WatchPageController.java:536-542): a link we
    // emit must be a link we accept. This is the test that catches a path-shape drift on either side.
    for url in [ShareLinks.video("abc"), ShareLinks.channel("UC1"), ShareLinks.playlist("PL1")] {
        #expect(DeepLinkParser.route(for: url) != nil)
    }
}

@Test func theMessageBodyOmitsTheURLBecauseTheURLIsTheItem() {
    // Reconciliation note 5: ShareLink(item:subject:message:) already hands the URL over as its own
    // activity item; repeating it in the body prints it twice in Mail and Messages.
    let body = ShareLinks.message(for: .video("abc"), title: "T", locale: .init(identifier: "en"))
    #expect(body.contains("T"))
    #expect(body.contains("app.fitrahtube.com") == false)
}

@Test func thePromoLineNeverSaysAdFree() {
    // Global Constraints + spec D10/12: Android's share_app_promo is "Get FitrahTube for ad-free
    // Islamic content!" and the embed rung plays YouTube's ads, so the claim is false in-app.
    for loc in ["en", "ar", "nl"] {
        #expect(ShareLinks.message(for: .video("a"), title: "T", locale: .init(identifier: loc))
            .localizedCaseInsensitiveContains("ad-free") == false)
    }
}

@Test func aVideoTitleIsTruncatedAtOneSixtyWithAnEllipsis() {
    // PlayerFragment.kt:3317-3323 -- take(157) + "...". Channel and playlist titles are not truncated.
}
```

`PlayerMetadataViewTests.swift` (CF-B1-10):

```swift
@Test func showMoreIsHiddenWhenTheDescriptionAlreadyFits() {
    // CF-B1-10 / B1 task-8 M2: the toggle renders unconditionally today, so a two-line description
    // gets a "Show more" that expands nothing.
    #expect(DescriptionTruncation.needsToggle(fits: true, isExpanded: false) == false)
    #expect(DescriptionTruncation.needsToggle(fits: false, isExpanded: false))
    #expect(DescriptionTruncation.needsToggle(fits: true, isExpanded: true))   // still offer "Show less"
}
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `ReportContext`, `ReportPayload`, `ShareLinks`, `DescriptionTruncation` do not exist.

- [ ] **Step 3: Implement**

1. **`ReportPayload.swift`** — the pure types above. `make` returns `Result` so the sheet has no validation logic of its own. `ReportReason.wireValue` is one exhaustive switch to the SCREAMING_SNAKE strings the backend's enum uses (no `default:` — a new reason must not silently vanish); cross-check each against the generated `ReasonsPayloadPayload` (`Types.swift:2629`), which is the only place those strings are already written down.
2. **`ReportClient.swift`** — one hand-written `POST api/v1/reports` over the container's `HTTPTransport`, structurally identical to Task 2's `IndexClient`: JSON-encode `ReportBody`, **add `X-Device-Id` explicitly** from `FitrahAPI.DeviceId` (`ContentReportController.java:48-57` 400s without it — and `BackendAvailabilityGate` sends no headers, so there is no line to copy) plus `Content-Type: application/json`. Status mapping: **201** → `.succeeded`; **429** → `.rateLimited`; anything else → `.failed("report_error")`; `CancellationError` rethrown before the generic catch, mirroring `ReportRepository.kt:50-51`. Unlike `IndexClient` this is **not** fire-and-forget — the sheet awaits it and shows the outcome.
3. **`ReportSheet.swift`** — a `.sheet` with `.presentationDetents([.medium, .large])`: title `report_title`, subtitle `report_subtitle`, 11 `Toggle` rows (each ≥44 pt, `.accessibilityValue` carrying checked/unchecked), the "Other" row revealing a `TextField(axis: .vertical)` capped at 500 characters, and Cancel / Submit. Submit disabled while `.submitting`. `.succeeded` → dismiss with a `transientBanner` carrying `report_success` **on the presenting screen**, not inside a sheet that is going away.
4. **`ShareLinks.swift`** — a `Target` enum with the three cases, `url(for:)`, and `message(for:title:locale:)` composing through `Format.localizedFormat` (never `String(localized:)` + `Locale.current`). Video titles truncate at 160.
5. **`DetailKebab.swift`** — `Menu { ShareLink(...); Button(report) } label: { Image(systemName: "ellipsis") }`, 44 pt, `report_content` and `action_share` as labels. One component, both screens (ruling 53).
6. **`PlayerToolbar.swift`** — the share URL moves to `ShareLinks.video(args.videoId)` (deleting the inline string interpolation at `:16-21`) and gains `message:`; the report button opens the sheet with `ReportContext(targetType: .video, targetId: args.videoId, parentType: args.playlistId != nil ? .playlist : (args.channelId != nil ? .channel : nil), parentId: args.playlistId ?? args.channelId, contentSubType: nil)` — the precedence Android's kebab path uses (`PlayerFragment.kt:1832-1838`). Delete the `player_report_coming_soon` reference; leave the key in the catalog (it is an `EXTRA_KEYS` entry another plan authored — removing it is a `convert-strings.py` edit that belongs to whoever wants the cleanup).
7. **`PlayerMetadataView.swift`** — a `ViewThatFits` probe (or an equivalent `lineLimit` measurement) feeding `DescriptionTruncation.needsToggle`. The decision function is pure and tested; the geometry is not.
8. **`convert-strings.py`** — add `"share_app_promo"` to `REFUSE`, then two `EXTRA_KEYS`:
   ```python
       # share_app_promo: REFUSED above and re-authored here. Android's value claims "ad-free",
       # which spec D10 / 12 require dropping and which the embed rung (B3) makes false -- rung 3
       # plays YouTube's own player, ads included.
       "share_app_promo": {
           "en": "Get FitrahTube for curated Islamic content!",
           "ar": "حمّل فطرة تيوب لمحتوى إسلامي منتقى!",
           "nl": "Download FitrahTube voor geselecteerde islamitische content!",
       },
       # report_reason_limit (Plan C task 3): the disabled-row hint once 10 reasons are selected.
       # iOS-only -- Android has no cap and eats the backend's 400 (ContentReportController.java:161
       # validates @Size(max = 10) against an 11-value enum).
       "report_reason_limit": {
           "en": "You can select up to 10 reasons",
           "ar": "يمكنك اختيار ١٠ أسباب كحد أقصى",
           "nl": "Je kunt maximaal 10 redenen selecteren",
       },
   ```
   Then regenerate and confirm the diff is exactly one changed value and one added key.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`
Expected: green. Then by hand: launch, open any video, tap Report, submit with one reason against a running backend (or the container's fake) and confirm the thank-you banner; submit six times in an hour against the real backend and confirm the sixth shows `report_rate_limited` **with the checkboxes still checked** (ruling 72).

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Report/ReportPayload.swift \
        ios/FitrahTube/Features/Report/ReportSheet.swift \
        ios/FitrahTube/Features/Report/ReportClient.swift \
        ios/FitrahTube/Features/Detail/ShareLinks.swift \
        ios/FitrahTube/Features/Detail/DetailKebab.swift \
        ios/FitrahTube/Features/Player/PlayerToolbar.swift \
        ios/FitrahTube/Features/Player/PlayerMetadataView.swift \
        ios/FitrahTube/App/AppContainer.swift \
        ios/scripts/convert-strings.py \
        ios/FitrahTube/Resources/Localizable.xcstrings \
        ios/FitrahTubeTests/ReportPayloadTests.swift \
        ios/FitrahTubeTests/ShareLinksTests.swift \
        ios/FitrahTubeTests/PlayerMetadataViewTests.swift
git commit -m "[FEAT]: iOS report sheet and share links"
```

---

### Task 4: PlaylistDetail — hero, items, Play All / Shuffle, Save

**Why this is fourth:** it is the smaller of the two screens and it exercises every piece Tasks 2–3 built (one paginated list, the kebab, the availability gate, the index push, `PaginationGuard`) without the five-tab machinery. It also closes B5's contract, so the player's queue stops being unreachable.

**Hard prerequisite: B5's Task 1 must be on the branch before this task starts.** `PlayerArgs` at HEAD has nine fields and no `startIndex` / `shuffled` / `targetVideoId` (`Route.swift:70-80`), and `ios/FitrahTube/Features/Player/PlayerQueue.swift` does not exist — so the whole launch contract below is uncompilable until B5 Task 1 lands. Plan C runs after B5 in the phase order, so this should be true on arrival; **check it first** (`grep -n targetVideoId ios/FitrahTube/App/Route.swift`) and if the three fields are absent, **stop and report** rather than adding them here. Two plans adding the same three fields is a merge conflict in the one file every route flows through, and B5's Task 1 is where their semantics are tested. This is the same hedge Tasks 3 and 5 apply to B4's `Route.shorts(PlayerArgs)`, with one difference: B4's is optional (Task 5 degrades to whatever the case carries), and this one is not.

**Files:**
- Create: `ios/FitrahTube/Features/Detail/PlaylistDetailScreen.swift`
- Create: `ios/FitrahTube/Features/Detail/PlaylistDetailViewModel.swift`
- Create: `ios/FitrahTube/Features/Detail/DetailHeader.swift` (the collapsing header + scroll-reactive toolbar tint, shared with Task 5)
- Create: `ios/FitrahTube/Catalog/SavedPlaylistsStore.swift` (SwiftData, mirroring `SwiftDataFavoritesStore`)
- Modify: `ios/FitrahTube/Features/Shell/MainShellView.swift` (`case .playlist(…)` above the `default:`)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (register `savedPlaylists`)
- Test: `ios/FitrahTubeTests/PlaylistDetailViewModelTests.swift` (new), `ios/FitrahTubeTests/MainShellRoutingTests.swift` (new or extend)

**Interfaces:**
- Consumes: Task 2's `BrowseSource` / `TabState` / `PaginationGuard` / `ListFooter` / `IndexClient` / `BackendAvailabilityGate`; Task 3's `DetailKebab`; B5's `PlayerArgs` fields.
- Produces: `PlaylistDetailViewModel` (`header`, `items: TabState`, `query`, `isSaved`), `PlaylistDetailScreen(id:title:category:count:)`, `SavedPlaylistsStore`.

**The B5 launch contract, restated verbatim** (`2026-08-27-ios-phase2b5-fullscreen-queue.md` Task 1; CF-B5-1). Plan C owns these three call sites and B5 owns the type:

```swift
// PlaylistDetail's "Play all"  -> Route.player(PlayerArgs(
//                                     videoId: firstItem.id, playlistId: playlistId,
//                                     startIndex: 0, shuffled: false, targetVideoId: nil, …))
// PlaylistDetail's "Shuffle"   -> ... startIndex: 0, shuffled: true,  targetVideoId: nil
// PlaylistDetail's row tap     -> ... startIndex: rowIndex, shuffled: false,
//                                     targetVideoId: item.id        // AUTHORITATIVE
```

Four rules that come with it, each of which has a test below:
- **Emission is UNCONDITIONAL.** Plan C must not wait for its own items to load before navigating; the player resolves the playlist itself (`PlaylistDetailViewModel.kt:404-417` — "Emission is unconditional — no check that items loaded").
- **`videoId` must still be a real, playable id** — it is what plays while the queue loads. For Play All / Shuffle before items exist, pass the first known item; if there genuinely is none, that is a Plan C empty-state, not a player launch.
- **Plan C does not prefetch the first stream.** Android does (`PlaylistDetailFragment.kt:294-298`); B5's own `open()` resolve is that call, and a second one would spend the rate limiter's interactive budget twice on the same video.
- **Plan C must not hand its loaded page array to the player** (CF-B5-2). The player's cursor advances independently; a shared array needs a shared paging owner, which neither plan wants.

**Reconciliation — the header is `getPublicPlaylist`, not `BrowseClient`, for one field only.** `BrowseClient.playlistItems` returns items, not a header; `InnerTubeKit` has no `PlaylistHeader` type and building one means a second parse of the same response. The screen's header needs a title, a thumbnail and a count — and `Route.playlist(id:title:category:count:)` already carries title, category and count from every list tap (`Route.swift:63`), which is the metadata fast path doing its job. For a **deep link** all three are nil, so the header falls back to the generated `getPublicPlaylist` DTO (present at `Client.swift:425`, zero call sites today) and to the first page's own item count. Do **not** add a header parse to `BrowseClient` for this. The `channelId`/`channelName` link line and its approval gate (`PlaylistDetailViewModel.kt:136-155`) are **out of scope** — see the Out-of-scope list.

- [ ] **Step 1: Write the failing tests**

`PlaylistDetailViewModelTests.swift`, over a fake `BrowseSource`:

```swift
@Test func aBlockedPlaylistIsTerminalWithNoRetry() {
    // RULING 14/15 + PlaylistDetailViewModel.kt:120-124. A 410 is the backend saying the catalog
    // pulled this; retrying cannot change it, and a Retry button that reloads the same 410 is worse
    // than no button.
}
@Test func aTransportFailureOnTheGateFailsOpenAndStillLoads() { /* :112-119 */ }

@Test func positionsAreOneBasedAndContinueAcrossPages() {
    // NewPipePlaylistDetailRepository.kt:175-177,211 -- nextItemOffset = itemOffset + items.size.
    // Page 2's first row is 6, not 1: the number is the item's place in the playlist, not in the page.
}

@Test func playAllEmitsEvenWhenItemsHaveNotLoaded() {
    // CF-B5-1, verbatim: "Plan C must NOT wait for its own items to load before navigating".
}

@Test func aRowTapPassesTargetVideoIdAndTheIndexAsAHint() {
    let args = vm.playerArgs(forRowAt: 3)
    #expect(args.targetVideoId == "d")     // authoritative
    #expect(args.startIndex == 3)          // hint only
    #expect(args.shuffled == false)
    #expect(args.playlistId == "PL1")
}

@Test func shuffleSetsTheFlagAndNoTargetVideo() { /* PlaylistDetailViewModel.kt:413-417 */ }

@Test func searchFiltersLoadedItemsAndSuppressesPagination() {
    // PlaylistDetailFragment.kt:326-346 -- and RULING 46's distinct copy for zero matches, which
    // Android does not have (its empty_state is never configured at all, :427-433).
    #expect(vm.filtered(query: "zzz").emptyMessageKey == "search_no_results")
    #expect(vm.filtered(query: "").emptyMessageKey == "playlist_empty_state")
}

@Test func appendingShowsTheFooterSpinner() {
    // RULING 47, fixing defect 28: Android sets isAppending and renders nothing.
    #expect(vm.footerState(while: .appending) == .loading)
}

@Test func theMetadataLineIsCountOnly() {
    // RULING 49 -- totalDurationSeconds is always nil upstream, so the "%1$d videos • %2$s"
    // variant can never render truthfully and playlist_metadata_duration_format stays an orphan.
}

@Test func viewCountsUseThePluralNotThePlainString() {
    // RULING 48 -- PlaylistVideosAdapter.kt:71 uses video_views_format; every other surface uses
    // the video_views substitution plural, and RULING 37 says there is one formatter.
}

@Test func savingRefusesAMalformedPlaylistId() { /* PlaylistDetailFragment.kt:787 ^[A-Za-z0-9_-]{3,128}$ */ }

@Test func eachLoadedPagePushesToTheStreamIndex() { /* RULING 25 + NewPipePlaylistDetailRepository.kt:202-208 */ }
```

`MainShellRoutingTests.swift`:

```swift
@Test func thePlaylistRouteRendersTheRealScreenNotThePlaceholder() {
    // Global Constraints: MainShellView.destination(for:) has a `default:` at :156, so a missing
    // arm is a silent placeholder rather than a compile error. This is the test that notices.
}
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `PlaylistDetailViewModel` does not exist; the routing test finds `PhaseTwoPlaceholderView`.

- [ ] **Step 3: Implement**

1. **`DetailHeader`** — a `ScrollView` whose first child is the hero, with `onScrollGeometryChange` driving a `0…1` collapse fraction that (a) fades the inline title into the navigation bar and (b) interpolates the toolbar tint from `.white` over the image to `Color.textPrimary` when collapsed (`PlaylistDetailFragment.kt:253-264`, `ChannelDetailFragment.kt:167-179`). One component, both screens. **No `UIViewControllerRepresentable`** — a collapsing header is layout, not a UIKit port.
2. **The hero** — `RemoteImage` at 16:9, centred, `Size.contentMaxWidth`-capped, over a blurred copy of the same image at `.opacity(0.6)` with the `heroOverlay` token wash (`fragment_playlist_detail.xml:53-129`). Heights 200/280/320 pt by width class.
3. **The action bar** — **three** equal cells: `playlist_play_all`, `playlist_shuffle`, `playlist_save`/`playlist_unsave` (rulings 28/56 delete the fourth). Each ≥44 pt with label + value.
4. **The list** — `VideoRow` with a leading 32 pt position column (`item_playlist_video.xml:14-25`), `a11y_playlist_video` as the row label, `ListFooter` at the end, `PaginationGuard` + `onContentFits` wired exactly as `ContentListView.swift:314-362` does it.
5. **`SavedPlaylistsStore`** — SwiftData, same shape as `SwiftDataFavoritesStore` including its tombstone handling; `isSaved` read live, the toggle optimistic with a revert on throw (the `FavoriteToggle.perform` pattern at `PlayerToolbar.swift:112`).
6. **`MainShellView`** — `case .playlist(let id, let title, let category, let count): PlaylistDetailScreen(...)` **above** the `default:` arm.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`, then by hand on iPhone 17 and iPad Pro 13-inch: open a playlist from the Playlists tab and from `albunyaantube://playlist/PL…`; confirm the deep-linked one fills its title from the network; scroll to the footer; tap Load-more behaviour; Play All, Shuffle and a row tap each land in the player with the right queue.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Detail/PlaylistDetailScreen.swift \
        ios/FitrahTube/Features/Detail/PlaylistDetailViewModel.swift \
        ios/FitrahTube/Features/Detail/DetailHeader.swift \
        ios/FitrahTube/Catalog/SavedPlaylistsStore.swift \
        ios/FitrahTube/Features/Shell/MainShellView.swift \
        ios/FitrahTube/App/AppContainer.swift \
        ios/FitrahTubeTests/PlaylistDetailViewModelTests.swift \
        ios/FitrahTubeTests/MainShellRoutingTests.swift
git commit -m "[FEAT]: iOS playlist detail screen"
```

---

### Task 5: ChannelDetail — banner header, five tabs, subscribe

**Why this is fifth:** it is the largest screen and it reuses everything the previous four tasks built — the header, the footer, the kebab, both pagination machines, the degraded fallback. Doing it last means the only new work is the tab container and the five tab bodies.

**Files:**
- Create: `ios/FitrahTube/Features/Detail/ChannelDetailScreen.swift`
- Create: `ios/FitrahTube/Features/Detail/ChannelDetailViewModel.swift`
- Create: `ios/FitrahTube/Features/Detail/ChannelTabsView.swift` (the strip + the swipeable pager + the five bodies)
- Create: `ios/FitrahTube/Catalog/SubscriptionsStore.swift` (SwiftData, 30-channel cap)
- Modify: `ios/FitrahTube/Features/Shell/MainShellView.swift` (`case .channel(…)`)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (register `subscriptions`)
- Modify: `ios/FitrahTube/DesignSystem/Components.swift` (`SkeletonShorts` — a 9:16 variant of `SkeletonGrid`, ruling 11)
- Modify (ruling 63, unrelated to this screen — see the tail of Step 3): `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/RemoteConfig.swift`, `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/Resources/remote-config-default.json`, `ios/FitrahTube/Features/Featured/FeaturedViewModel.swift`, `ios/FitrahTube/Features/Featured/FeaturedView.swift`, `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift`
- Test: `ios/FitrahTubeTests/ChannelDetailViewModelTests.swift` (new), `ios/FitrahTubeTests/SubscriptionsStoreTests.swift` (new), `ios/FitrahTubeTests/MainShellRoutingTests.swift` (extend)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: `ChannelDetailViewModel` (`header`, five per-tab `TabState`s, `selectedTab`, `query`, `isSubscribed`, `isDegraded`), `ChannelDetailScreen(id:name:avatarURL:)`, `SubscriptionsStore`, `SkeletonShorts`.

**Reconciliation — the five tabs are five different things and only two of them are the same list.**

| Tab | Source | Cell | Tap | Empty key |
|---|---|---|---|---|
| Videos | `channelVideos` (`VLUU…`, ruling 3) | `VideoRow` | `Route.player(PlayerArgs(…, channelId: id, channelName: header.name))` | `channel_videos_empty` |
| Live | `channelTab(.live)` | `VideoRow` + LIVE/UPCOMING `Badge` | same, plus `contentSubType = .livestream` in the report context | `channel_live_empty` |
| Shorts | `channelTab(.shorts)` (Task 1) | 9:16 grid, **2 / 4 / 5 columns** (spec §11) | `Route.shorts(…)` — see below | `channel_shorts_empty` |
| Playlists | `channelPlaylists` (Task 1) | `PlaylistRow` | `Route.playlist(id:title:category:nil count:)` | `channel_playlists_empty` |
| About | the header — no second call | text + link rows | links open externally | `channel_about_no_description` |

`Route.shorts`'s payload depends on whether B4 has landed: at HEAD today it is `.shorts(id: String)` (`Route.swift:39`); B4's Task 1 replaces it with `.shorts(PlayerArgs)` specifically so this grid can pass the metadata fast path. **Construct whatever the case carries when you start**, and if it is `PlayerArgs`, fill `videoId`, `title`, `thumbnailURL`, `channelId`, `channelName` and `channelAvatarURL` from the grid cell and the header — that is the integration B4's plan names in its Out-of-scope list.

The Videos tab is also where ruling 39's fix stays fixed: pass the **real channel name** from the header, never the category. `PlayerArgs.init(item:)` (`Route.swift:88-92`) never populates `channelId`, so the report context for a video opened from a list is currently blank — this tab is one of the two places that finally fills it (ruling 66).

**Reconciliation — the tab strip is not a `Picker`.** Ruling 9 requires a scrollable strip on compact and fixed/fill at ≥600 pt, *and* swipe-between-tabs (`ChannelDetailFragment.kt:196` `isUserInputEnabled = true`). A segmented `Picker` gives neither the scroll nor the swipe. Build a `ScrollView(.horizontal)` strip of buttons with a `matchedGeometryEffect` indicator over a `TabView(...).tabViewStyle(.page(indexDisplayMode: .never))`, and switch the strip to a fixed `HStack` when `widthClass != .compact`. Note the deliberate contrast with B4: **Shorts refuse paging gestures (ruling 50), channel tabs require them (ruling 9)** — the two are different surfaces and a "no paging anywhere" rule would break this one.

- [ ] **Step 1: Write the failing tests**

`ChannelDetailViewModelTests.swift`:

```swift
@Test func theHeaderAndTheFirstTabLoadInParallel() {
    // ChannelDetailViewModel.kt:109-124 -- deliberate, "to cut 300-600 ms off cold open".
}

@Test func tabsLoadLazilyOnFirstSelectionAndNeverReload() {
    // :374-386 -- ensureTabLoaded no-ops unless the tab is .idle.
}

@Test func allFiveTabsExistEvenWhenEmpty() {
    // ChannelDetailModels.kt:169-175 -- tabs are never hidden; each shows its own empty state.
    #expect(vm.tabs.count == 5)
}

@Test func anUnknownSubscriberCountRendersTheDashNotAFormattedZero() {
    // RULING 8 + reconciliation note 4: BrowseClient gives us a STRING, and its detection heuristic
    // is English-only (BrowseClient.swift:253), so nil is the expected Arabic result.
    #expect(vm.subscriberLine(for: nil) == String(localized: "channel_subscribers_unknown"))
    #expect(vm.subscriberLine(for: "1.2M subscribers") == "1.2M subscribers")  // verbatim, NOT re-formatted
}

@Test func aBotCheckedVideosTabDegradesToTheAtomFeed() {
    // CF-C3 + plan 11 ("degraded mode ... is automatic"). Fifteen items, no continuation, and the
    // notice banner -- not an error state, and not a silent short list. Videos is one of only TWO
    // surfaces with a substitute (the other is the header); see the next test for the other three.
    #expect(vm.videos.items.count == 15)
    #expect(vm.videos.continuation == nil)
    #expect(vm.isDegraded)
}

@Test func aBotCheckedLiveShortsAndPlaylistsTabsShowErrorsBecauseThereIsNoSubstitute() {
    // Task 2's degraded table: only the header and Videos have a degraded source. Playlists has
    // none either -- getPublicChannel returns a raw Channel with no approved-playlist array, and no
    // channel-scoped playlist endpoint exists (BACKEND-BLOCKED item 6). Rendering any of
    // the three empty would claim the channel has no streams / no Shorts / no playlists.
    for tab in [vm.live, vm.shorts, vm.playlists] {
        #expect(tab.errorMessageKey == "channel_tab_error_generic")
    }
}

@Test func degradedModeIsLatchedSoASecondOpenDoesNotReProbe() { /* Task 2's BrowseFallback */ }

@Test func aVideoTapCarriesTheRealChannelNameAndTheChannelId() {
    // RULING 39 (channelName <- category was the Android bug) + RULING 66 (the report context).
    #expect(vm.playerArgs(for: item).channelName == "Alafasy")
    #expect(vm.playerArgs(for: item).channelId == "UCmMcOjsVehVlEOteyrhjI2Q")
}

@Test func aLiveTapCarriesTheLivestreamSubtype() { /* ChannelLiveTabFragment.kt:62-69 */ }

@Test func searchFiltersEachTabIndependentlyAndLeavesAboutAlone() { /* brief 5.4 */ }

@Test func theShortsTabRendersItsSkeletonWhileLoading() {
    // RULING 11, fixing defect 1: Android's shorts skeleton RecyclerView never gets an adapter
    // (fragment_channel_shorts_tab.xml:28-39), so its loading state is a blank rectangle.
    #expect(vm.shorts.skeletonKind == .shortsGrid)
}

@Test func theAboutTabOmitsTheRowsThatCanNeverHoldData() {
    // RULING 7 -- location / joinedDate / totalViews are always nil upstream
    // (NewPipeChannelDetailRepository.kt:740-746).
    #expect(vm.aboutRows.map(\.key) == ["channel_subscribers_format", "channel_verified"])
}
```

`SubscriptionsStoreTests.swift`:

```swift
@Test func theThirtyFirstSubscriptionIsRefusedWithTheCapMessage() {
    // RULING 27 + SubscriptionLimitGuard.kt:26,73 + strings.xml:183. Guest-local; no account needed.
}
@Test func unsubscribingIsNeverCapped() { … }
@Test func aMalformedChannelIdIsRefused() { /* ChannelDetailFragment.kt:309-312 ^[A-Za-z0-9_-]{3,64}$ */ }
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `ChannelDetailViewModel`, `SubscriptionsStore`, `SkeletonShorts` do not exist.

- [ ] **Step 3: Implement**

Order that keeps the gate green throughout: `SubscriptionsStore` and `SkeletonShorts` first (both standalone), then the view model, then `ChannelTabsView`, then the screen, then the `MainShellView` arm.

Three things to be careful about:

- **The header and the tab pager are one scroll surface.** The banner collapses as the *selected tab's* list scrolls; a `ScrollView` per tab inside a `TabView` inside an outer `ScrollView` gives nested scrolling and a header that never collapses. Put `DetailHeader` and the strip in a `VStack` above a `TabView` that fills the remaining height, and let each tab own its own scroll — the header pins rather than collapses on compact if that is what falls out. Ruling 9's requirement is the strip and the swipe; a fully collapsing banner over a paged container is the part to sacrifice if it fights, and Task 6 step 4 (the simulator matrix) is where that gets judged on a screenshot rather than argued here.
- **The Shorts grid must autofill.** `channel_shorts_span_count` is 2 / 4 / 5 (spec §11), so on an iPad the first page can easily fit with nothing to scroll — the exact case CLAUDE.md's pagination rule exists for. Wire `onContentFits` there like everywhere else; `ChannelTabAutofill`'s cap then stops it after two pages.
- **Degraded rows are missing fields, not zero fields.** `AtomFeedFetcher.latest` returns `VideoItem`s with only id, title, `publishedText` and thumbnail (`AtomFeedFetcher.swift:36`). `VideoRow` must render a row with no duration chip and no views line rather than "0 views" or "0:00".

**Last, and unrelated to this screen — ruling 63 (`FEATURED_CATEGORY_ID` becomes a remote-config key).** It is batched here rather than in Task 6 so that task stays acceptance-only; it touches none of the files above and can be done first or last.

- `RemoteConfig` gains `public var featuredCategoryId: String?` — **optional**. Every existing field is non-optional, and `bundledDefault` `fatalError`s on a decode failure (`RemoteConfig.swift:88`), so a required addition would crash on the first launch after upgrade against an already-persisted last-known-good copy. Optional decodes to nil from old data and from the current `remote-config-default.json`.
- Set it to `"itirf9pGpAvoBT5VSkEc"` in `remote-config-default.json`. `sanitized` (`:164-175`) needs no change — it filters `resolverOrder` and `clients` only, and passes anything else through.
- `FeaturedViewModel.featuredCategoryId` (the `static let` at `FeaturedViewModel.swift:19`) is renamed `bundledFeaturedCategoryId` and stays the fallback; `FeaturedView`'s `.task` resolves `await container.innerTube.remoteConfig.current().featuredCategoryId ?? FeaturedViewModel.bundledFeaturedCategoryId` before constructing the view model. `FeaturedViewModelTests.swift:186-205` already pins the fallback path and only needs the renamed symbol.
- One `RemoteConfigTests` case: a document with **no** `featuredCategoryId` decodes with it nil and leaves `bundledDefault` intact.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`, then by hand on iPhone 17 and iPad Pro 13-inch, en and ar: open a channel from Home, from the Channels tab, from a playlist row, and from `albunyaantube://channel/UC…`; visit all five tabs; swipe between them; search; subscribe and unsubscribe.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Detail/ChannelDetailScreen.swift \
        ios/FitrahTube/Features/Detail/ChannelDetailViewModel.swift \
        ios/FitrahTube/Features/Detail/ChannelTabsView.swift \
        ios/FitrahTube/Catalog/SubscriptionsStore.swift \
        ios/FitrahTube/Features/Shell/MainShellView.swift \
        ios/FitrahTube/DesignSystem/Components.swift \
        ios/FitrahTube/App/AppContainer.swift \
        ios/FitrahTubeTests/ChannelDetailViewModelTests.swift \
        ios/FitrahTubeTests/SubscriptionsStoreTests.swift \
        ios/FitrahTubeTests/MainShellRoutingTests.swift
git commit -m "[FEAT]: iOS channel detail screen"
```

Ruling 63 is a **separate commit** (different files, unrelated change):

```bash
git add ios/Packages/InnerTubeKit/Sources/InnerTubeKit/RemoteConfig.swift \
        ios/Packages/InnerTubeKit/Sources/InnerTubeKit/Resources/remote-config-default.json \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift \
        ios/FitrahTube/Features/Featured/FeaturedViewModel.swift \
        ios/FitrahTube/Features/Featured/FeaturedView.swift \
        ios/FitrahTubeTests/FeaturedViewModelTests.swift
git commit -m "[FEAT]: iOS featured category id from remote config"
```

---

### Task 6: Publish the remote config, then the acceptance pass

**Why this is last:** the config file is release prep — it only becomes live when it reaches `main` — and the acceptance pass is what confirms the whole of Phase 2 works together, including that `RemoteConfigStore.refresh()` stops 404ing. Apart from publishing that one JSON file and the gate step that validates it, this task writes no product code; ruling 63's schema change was batched into Task 5 precisely to keep it that way.

**Files:**
- Create: **`ios-remote-config.json` at the repo root**
- Modify: `ios/scripts/test.sh` (one gate stage — step 2)
- Modify: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift` (the gate stage's test — step 2)
- Modify: `ios/scripts/screenshots.sh` (permanent Plan C blocks)
- Modify: `ios/FitrahTubeUITests/ScreenshotTests.swift`
- Otherwise touch only what a finding requires. Screenshots under `.superpowers/sdd/2026-08-27-ios-phase2c-detail-report-share/screenshots/`.

- [ ] **Step 1: Publish `ios-remote-config.json`**

The app already fetches `https://raw.githubusercontent.com/talibfitrah/albunyaantube/main/ios-remote-config.json` (`AppContainer.swift:36`) on launch and on `.active` with ≥15 min spacing (`FitrahTubeApp.swift:43-61`) — the URL and the call site are both correct and neither changes. **The file simply does not exist**, so `refresh()` silently returns and `current()` serves `RemoteConfig.bundledDefault` forever (`RemoteConfig.swift:139`). Until it lands on `main`, every row of `ios-app-plan.md` §11's runbook whose action is "change the remote config" is a lie.

Write it to be **byte-compatible with the bundled default** (`ios/Packages/InnerTubeKit/Sources/InnerTubeKit/Resources/remote-config-default.json`) — same `schemaVersion`, `minAppVersion`, `resolverOrder`, `manifestCacheSeconds`, and the same three `clients` entries with identical `clientName` values. `resolverOrder` is `["visionosHLS", "androidItag18"]` — embed ships dark, do not add it to the published config; `sanitize` still accepts it for DR. Two hard rules from the sanitizer and the plan:

- `RemoteConfigStore.sanitized` (`RemoteConfig.swift:164-175`) drops any `resolverOrder` entry outside `{visionosHLS, androidItag18, embed}` and drops any known client key whose `clientName` does not match `{visionos: VISIONOS, android: ANDROID, web: WEB}`. A typo in either place silently removes a rung. *(Owner directive 2026-08-27: `openInYouTube` is no longer in the allow-list — the sanitizer drops it unconditionally, so a published config can never re-enable a YouTube hand-off. See RULINGS.md Q75.)*
- **Data only** (`ios-app-plan.md:241`): strings and orderings consumed by bundled code. No URLs to code, no new strategy names. "Parameter tweaks are config; a new strategy or client family is an App Store submission."
- Body ≤64 KiB (`RemoteConfig.swift:120`) — the document is under 2 KB, so this is a ceiling, not a constraint.

**Merge-to-`main` is the publishing act.** `raw.githubusercontent.com/.../main/...` serves the file from `main`, exactly as Android's Available-updates screen reads `releases-meta.json` (`CLAUDE.md` release checklist step 4; `ReleaseSummaryFetcher.kt:122-123`). A commit on `feature/ios-app` changes nothing in production. Record in the commit message that the file is inert until the branch merges, and put it on the Phase-2 gate checklist. Note also that raw.githubusercontent serves `max-age=300`, so a change takes up to five minutes plus the app's own 15-minute spacing to reach a running install.

Include `"featuredCategoryId": "itirf9pGpAvoBT5VSkEc"` — Task 5's tail added the field to `RemoteConfig` and to the bundled default, and the published document has to carry it or the fetched config silently reverts Featured to the fallback.

- [ ] **Step 2: Make the gate validate the published file**

A config document that fails to decode, or that loses a rung to `sanitized`, is indistinguishable at runtime from no document at all — `refresh()` swallows every failure (`RemoteConfig.swift:129-135`) and `current()` quietly serves the bundled default. Nothing would notice until the runbook needed the file. So the gate reads it.

Smallest thing that actually exercises the real code: **`RemoteConfigStore.init` already runs `sanitized` on the persisted last-known-good copy** (`RemoteConfig.swift:110-117`), so a test can seed an in-memory `KeyValueStore` with the file's bytes under `RemoteConfigStore.lastGoodKey`, construct a store, and read `current()`. No new API, no re-implementation of the sanitizer, no network. In `RemoteConfigTests.swift`:

```swift
@Test(.enabled(if: ProcessInfo.processInfo.environment["IOS_REMOTE_CONFIG_PATH"] != nil))
func thePublishedRepoRootConfigSurvivesSanitizing() throws {
    // The file is inert until it reaches `main`, but a typo in it is silent at runtime -- refresh()
    // swallows a decode failure and current() falls back to the bundled default, so the app looks
    // fine and the runbook's "remote config first" rows quietly do nothing. Seeding lastGood is
    // what runs the REAL private sanitizer (RemoteConfig.swift:110-117) without exposing it.
    let path = try #require(ProcessInfo.processInfo.environment["IOS_REMOTE_CONFIG_PATH"])
    let store = InMemoryStore()
    store.set(RemoteConfigStore.lastGoodKey, try Data(contentsOf: URL(filePath: path)))
    let config = RemoteConfigStore(transport: FixtureTransport(routes: []), keyValueStore: store,
                                   url: URL(string: "https://example.invalid/none")!).current()
    #expect(config.resolverOrder.count == 2)      // nothing dropped as an unknown strategy (embed ships dark)
    #expect(config.clients.count == 3)            // no clientName mismatch dropped a family
    #expect(config.featuredCategoryId != nil)     // ruling 63's key is present
}
```

(`RemoteConfigStore.lastGoodKey` is `static let` with no access modifier — internal, which `@testable import` reaches.)

In `ios/scripts/test.sh`, export the path once, next to the existing `PATH` line so it is set before `run_all`:

```bash
# Plan C: validates the published ios-remote-config.json through the real decoder + sanitizer.
# Absent -> the test skips, so a checkout without the file (or before the merge to main) is green.
export IOS_REMOTE_CONFIG_PATH="$(cd "$(dirname "$0")/../.." && pwd)/ios-remote-config.json"
```

Guard it so a missing file skips rather than fails — the `.enabled(if:)` above covers the unset case; also skip when the path does not exist, so the gate stays green on a branch that has not published yet.

- [ ] **Step 3: Commit the config and the gate step**

```bash
git add ios-remote-config.json \
        ios/scripts/test.sh \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift
git commit -m "[CHORE]: Publish the iOS remote config"
```

The commit message must record that the file is **inert until this branch merges to `main`** — that merge is the publishing act, not this commit.

- [ ] **Step 4: Simulator matrix (do this yourself)**

Append permanent blocks to `screenshots.sh` in the same shape as the B1/B2/B3/B4 blocks — each with its own `OUT`, its own single `-only-testing:` line per case, and `simctl shutdown` after. **Remember the device argument does not scope them.** New identifiers use the `detail.` and `report.` prefixes (`detail.header`, `detail.tabStrip`, `detail.kebab.button`, `detail.kebab.share`, `detail.kebab.report`, `detail.subscribeButton`, `detail.saveButton`, `detail.playAll`, `detail.shuffle`, `detail.footer.loadMore`, `detail.degradedNotice`, `report.sheet`, `report.reason.*`, `report.submit`); reused components keep the identifiers they already emit.

Then check, on iPhone 17 and iPad Pro 13-inch (M5), en and ar, light and dark:

- **Channel header**: banner + circular avatar + name + verified badge + subscriber line; the toolbar's back chevron and kebab are white over the banner and `textPrimary` once collapsed; the title crossfades into the bar. With no banner, the placeholder shows and the gradient does not.
- **Tabs**: all five present and none hidden when empty; the strip scrolls on iPhone and fills at iPad width; **swiping between tabs works** (ruling 9); the selection survives a rotation.
- **Per-tab states**: skeleton on first load (**the Shorts skeleton is a 9:16 grid, not a blank area** — ruling 11); each tab's own empty copy; `channel_tab_error_generic` + Retry on failure; the footer's spinner / Load-more / Error trio.
- **Pagination**: on the iPad, a first page that fits triggers a second automatically, and stops at two with the Load-more button (ruling 10). On the iPhone, one autofill then the button. Tapping the button renews the budget.
- **Search**: 300 ms debounce; filtering is per-tab; About ignores it; zero matches shows **`search_no_results`**, not the tab's empty copy (ruling 5); the near-end trigger cannot fire while filtered.
- **Subscribe**: label flips Subscribe/Subscribed, the button disables during the write, and the 31st subscription shows `me_subscription_cap_reached`.
- **Playlist**: hero with the blurred backdrop; **three** action cells, no Download anywhere (rulings 28/56); 1-based positions continuing onto page 2; footer spinner while appending (ruling 47); count-only metadata (ruling 49); Save/Saved; `playlist_empty_state` for an empty playlist and `search_no_results` for zero matches (ruling 46).
- **Play All / Shuffle / row tap** each open the player with the queue: Play All starts at item 1, Shuffle starts somewhere else and Up Next is shuffled, a row tap starts on that row. Confirm a tap navigates **before** items finish loading (CF-B5-1).
- **Kebab**: Share opens the sheet with the `api/{watch|channel|playlist}/{id}` URL and a body that does **not** repeat the URL and does **not** say "ad-free"; Report opens the sheet.
- **Report**: 11 rows; the 11th disables once 10 are checked and announces `report_reason_limit`; "Other" reveals the field and unchecking clears it; Submit with zero reasons shows `report_select_reason` **with no network call**; success dismisses with `report_success`; a 429 keeps the sheet, the checkboxes and the text (ruling 72).
- **Degraded mode** (`-fitrah-fake-browse-botcheck`, a `#if DEBUG` fake in the container): the header still renders (from `getPublicChannel`); Videos shows 15 rows with **no duration chip and no views line**, no footer, and the `browse_degraded_notice` banner; **Live, Shorts and Playlists all show the error state with Retry, not an empty state** (Playlists has no substitute — Task 2's table, BACKEND-BLOCKED item 6); About renders. Leave the screen and come back: the latch means the second open does not re-probe, and the same three tabs still offer Retry.
- **Unavailable**: a 410 channel and a 410 playlist each show `content_unavailable_title`/`_message` with **no Retry** (ruling 14).
- **RTL (ar)**: the tab strip scrolls from the trailing edge; the position column is on the trailing side; the collapsing header mirrors; the subscriber line reads "–" (reconciliation note 4 — expected, not a bug to fix here).
- **Dynamic Type `.accessibility3`**: the tab strip stays reachable, the action bar wraps rather than clipping, rows go single column.
- **VoiceOver**: header, strip (each tab with its selected state), rows, footer button, kebab items, and every report row reachable with label **and** value. Every tap target measured ≥44×44 pt with Accessibility Inspector, not eyeballed.

- [ ] **Step 5: Live-YouTube checks (do these yourself, on the simulator, with network)**

Record each result in the commit message; do not "fix" behaviour that is correct.

1. A real channel opens: header, Videos, Live, **Shorts**, **Playlists** and About all populate. (Shorts/Playlists are the CF-C1 proof — if either is empty against a channel that visibly has them, Task 1's fixtures are stale and that is the finding.)
2. **Deep pagination**: scroll the Videos tab of a channel with 500+ uploads past page 5 and confirm the `VLUU…` continuation keeps returning items. This is the measurement reconciliation note 1 defers to; if it caps at ~100, report it — the fix is ruling 3's named fallback, with evidence.
3. A real playlist opens, pages, and Play All plays.
4. The index push fires: watch the backend log (or a proxy) for `POST /api/v1/index/streams` with ≤50 items after each page, and confirm a fast double-open produces a 429 that changes nothing on screen.
5. Report submits against the real backend and returns 201; a sixth report within the hour returns 429 and shows the inline message.
6. Share from all four surfaces; paste each link into a browser and confirm the watch page renders its OG card and hops to `albunyaantube://` (ruling 74). **The https link will NOT open the app directly — that needs the AASA (ruling 65) and is expected to fail.**
7. `albunyaantube://channel/UC…`, `://playlist/PL…`, `://video/…` from Notes and from a cold start each land on the right screen.
8. `refresh()` reaches a real document: temporarily point `AppConfig.innerTubeRemoteConfigURL` at your branch's raw URL, confirm `current()` returns the fetched config, then **revert the URL** before committing.

- [ ] **Step 6: Fix anything steps 4–5 surface**, re-run `ios/scripts/test.sh`, commit `[FIX]: iOS Plan C detail screen polish pass`.

- [ ] **Step 7: Record the blocked items — do not attempt**

Report these to the controller verbatim.

**USER-BLOCKED (needs the user, not code):**
1. **Apple Team ID + provisioning profile.** `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset, so no device install is possible and every device item below is unrun. Plan C inherits B2's undone 27-item device checklist as well.
2. **AASA hosting (ruling 65).** Universal Links are dead until (a) a Team ID exists, (b) `WellKnownController` ships (spec §12), and (c) the Cloudflare rule that 403s `/.well-known/*` is lifted — which `ios-app-plan.md:303` and spec `:261` both record as the user's task, outside this repo. Android's own six `autoVerify` App Link filters are failing verification for the same missing `assetlinks.json`, so this is one fix for both platforms.
3. **A translator for ar/nl.** Every `report_*` string, and the four `EXTRA_KEYS` this plan and its predecessors added, sit at `needs_review` with English text in ar and nl (ruling 70 accepts this as the interim state).

**BACKEND-BLOCKED (needs a change in `backend/` or in the shared OpenAPI document, out of Plan C's scope):**
4. **`POST /api/v1/reports` is missing three fields it already accepts.** `api-specification.yaml:341-363` declares only `targetType`/`targetId`/`reasons`/`otherDescription`, while `ContentReportController.java:165-171` binds `parentType`, `parentId` and `contentSubType` and documents exactly what they are for. Spec §10 and ruling 66 require them, so Task 3 hand-wrote `ReportClient` (its reconciliation note). **Add the three as optional properties to the spec's `/v1/reports` request schema and regenerate**; then `ReportClient` collapses into the generated `submitContentReport` call and `ReportBody` is deleted (CF-C-7).
5. **`POST /api/v1/index/streams` is absent from `docs/architecture/api-specification.yaml` entirely** — the endpoint exists (`IndexController.java:55`) but no generated client can reach it. Task 2 hand-wrote a client (reconciliation note 6); adding the path to the spec is the tidier fix and belongs with whoever next regenerates the DTOs. Best done in the same pass as item 4.
6. **There is no channel-scoped approved-playlists endpoint.** Spec §9 promises degraded mode "approved playlists (`/api/v1/channels/{id}`)", but that path returns a raw `Channel` DTO with no playlist array, and nothing else in the spec resolves *a channel's* approved playlists (`getPublicPlaylist` needs an id you do not have). So the channel's Playlists tab has no degraded substitute and shows an error instead (Task 2's table, fork B). **Add `GET /api/v1/channels/{id}/playlists`** (or an `approvedPlaylists` array on the existing channel response) and the tab gets its substitute for free — the call site is one line in `LiveBrowseSource`.
7. **`https://app.fitrahtube.com/embed` serves nothing.** B3 uses it as the embed rung's `loadHTMLString` base URL and as the only main-frame URL its navigation lock accepts (`2026-08-27-ios-phase2b3-embed-safemode.md:625-629`). `WatchPageController` serves `/watch`, `/channel`, `/playlist` and their `/api` variants only. The rung works without it — WebKit sends the Referer from the base URL whether or not it resolves — but a real page there would make the origin verifiable and would stop a curious user landing on a 404. Small `WatchPageController` addition; not Plan C's.
8. **`reasons` is capped at 10 server-side against an 11-value enum** (`ContentReportController.java:161` vs `ReportModels.kt:26-29`). Task 3 caps the client at 10; raising the server to 11 would be the better fix and would let a user report everything they see.
9. **`<meta name="apple-itunes-app">` on the watch pages** (`ios-app-plan.md:307`) — the Smart App Banner, blocked on an App Store ID.

**Device checklist (USER-BLOCKED, for when a Team ID exists):**
10. A real bot-check on a real network trips degraded mode and the latch (the simulator can only fake it).
11. Deep-linked cold start from Messages/Mail lands on the right screen without a flash of the shell.
12. The share sheet's rendering in Messages, Mail and WhatsApp — subject, body and URL preview (ruling 71's whole justification).
13. VoiceOver reading order on the collapsing header while it collapses.
14. Memory under a long Shorts-grid scroll on a 5 000-upload channel (`RemoteImage`'s cache is in-RAM only since CF-B2-16).

---

## Out of scope for Plan C (later phases, or deliberate deferrals)

- **`ShareMetadataPublisher`** (rulings 30 / 68) — Phase 4. It no-ops for signed-out users by construction (`ShareMetadataPublisher.kt:42-47`) and iOS Phase 2 has no accounts, so shipping it would be dormant code. This amends spec §15's phase-2 row "share + metadata publish": share is Phase 2, publish is Phase 4.
- **The playlist's channel-name link and its approval gate** (`PlaylistDetailViewModel.kt:136-155,191-247`: canonicalize the uploader URL, `isInApprovedRegistry(CHANNEL, ucId)`, fail closed, `===`-guarded write-back). B5 routed it here as "Plan C, which already owns the registry surfaces". It needs a canonical-id resolver iOS does not have, a registry call nothing else makes, and its failure mode is a hidden line. Cost of never building it: the playlist header shows no uploader link. The Save/Share/Report affordances do not depend on it.
- **The `excluded` argument and its banner** (ruling 6) and the backend exclusion list applied to browse results (spec §9's "exclusions from the backend applied"). No Android caller passes `excluded=true`, and the exclusion list has no iOS consumer yet; the availability gate (ruling 15) is what actually keeps blocked content out of the player. Recorded as a carry-forward, not built.
- **A Room-style pre-paint cache** (ruling 4) and its `publishedTime`-dropping quirk.
- **The About tab's location / joined / totalViews rows** (ruling 7) and the `channel_joined_date` / `channel_total_views` keys that stay orphaned.
- **Downloads on either screen** (rulings 28 / 56): no action-bar cell, no per-row badges, no `playlist_detail_download*` strings, no bulk-download paging.
- **Any Shorts feed, pager or sibling-id list** — rulings 50/51 and B4's reconciliation note 1. The Shorts grid pushes one route; fork E is where that gets revisited.
- **A `minAppVersion` "update required" screen** and ruling 64's ASCII sanitizer applied to it. Task 6 publishes the document that carries `minAppVersion`; nothing reads it yet. Carry-forward.
- **Safe Mode gating anything on these screens** (ruling 58) — the catalog is admin-curated; Safe Mode's Phase-2 effects are B3's navigation lock, B3's rung-4 removal and B5's auto-advance.
- **The backend-proxy alternative to on-device browse** (`ios-app-plan.md:181`: `/api/v1/channels/{id}/videos?cursor=`, cached 1 h). It is the documented fallback "if on-device `browse` proves fragile"; Task 6 step 5 item 2 is the measurement that would justify it.
- **A throttle on `AtomFeedFetcher`.** Degraded mode calls it once per screen open; a `max-age=900`-derived throttle is real work for a call that happens at human speed.
- **Editing `ios-app-plan.md` §6.7 or spec §9** to correct the pagination-strategy sentence (reconciliation note 1). Plan C creates and edits no `.md` files.

## Carry-forward for Phase 3 / the Phase-2 gate

- **CF-C-1:** `ios-remote-config.json` is inert until `feature/ios-app` merges to `main` — `raw.githubusercontent.com/.../main/...` is what the app reads. Add "confirm the raw URL returns 200 after the merge" to the Phase-2 release checklist. Until then `resolverOrder` is still whatever shipped in the binary, and every "remote config first" row of the runbook is unavailable.
- **CF-C-2:** `ChannelHeader.subscriberText` is found by an English-only substring match (`BrowseClient.swift:253`), so the subscriber line is "–" in Arabic and Dutch on every channel. Reconciliation note 4 explains why the fix is not "pin `hl=en`". If it matters, the honest fix is reading the count from a numeric field in the payload — which the 2026-08-24 capture did not record and a future capture should look for.
- **CF-C-3:** the docs disagree with the ruling about which continuation surface to page (reconciliation note 1). `ios-app-plan.md:179` and spec §9 say `VLUU…`; Android abandoned it in v0.26.3. Plan C ships `VLUU…` per ruling 3 and measures it in Task 6. Whoever next edits those documents should record that the justification quoted there is stale, even though the conclusion happens to be right for iOS.
- **CF-C-4:** `BrowseClient` now adopts the WEB `visitorData` from every response (including the bootstrap interstitial) and rotates it **only when a token was attached and was bot-checked anyway**; it still does **not** consult or escalate the shared cooldown, by decision (reconciliation note 3). The adopt-then-rotate ordering is load-bearing — `rotate` clears the family, so rotating on the bootstrap trip discards the token that makes the next call work, and two tests pin both halves. If browse bot-checks turn out to be frequent *after* visitorData adoption, revisit — the evidence will be in how often `browse_degraded_notice` appears, and the cheap next step is escalating the browse-only latch, never sharing the resolver's.
- **CF-C-5:** `ChannelTabAutofill` and `PaginationGuard` are two machines for one CLAUDE.md rule (ruling 10). They are separately tested and neither is generic. If a third list screen appears and wants a third variant, that is the moment to unify — not before.
- **CF-C-6:** the report sheet caps reasons at 10 to match a server limit that is one short of the enum (`ContentReportController.java:161`). If the backend raises it to 11, `ReportPayload.maxReasons` and one test are the whole client change, and `report_reason_limit` becomes an orphan key.
- **CF-C-7:** `IndexClient` and `ReportClient` are both hand-written because `docs/architecture/api-specification.yaml` is behind the controllers — the index path is absent entirely, and `/v1/reports` omits `parentType`/`parentId`/`contentSubType` (`:341-363`) though `ContentReportController.java:165-171` binds them. When the spec catches up, replace both with the generated calls and delete `IndexClient`, `ReportClient` and `ReportBody`; the tests move over unchanged, since they assert batching, 429-swallowing and payload shape, not transport.
- **CF-C-8:** the two detail screens are the first consumers of `getPublicChannel` / `getPublicPlaylist`, generated in Phase 0 with zero call sites until now. `getPublicChannel` is used for the degraded header and for the deep-linked playlist title only — **not** for approved playlists, which it does not carry (fork B, BACKEND-BLOCKED item 6). If a channel-scoped playlists endpoint lands, `LiveBrowseSource`'s `channelPlaylists` fallback is a one-line addition and the Playlists tab's degraded row in Task 2's table flips from "nothing" to a substitute.
- **CF-C-9:** `Route.channel`/`.playlist` carry `name`/`avatarURL` and `title`/`category`/`count` and are `Hashable` — they are the metadata fast path. A deep link fills none of them, so **every screen must render correctly with all optionals nil**. Any future field added there must keep that property, or deep links regress silently.
- **CF-C-10:** the gate's `ios-remote-config.json` check (Task 6 step 2) **skips** when `IOS_REMOTE_CONFIG_PATH` is unset or the file is absent, so a branch that has not published yet stays green. That is deliberate, and it also means deleting the file would not fail the gate — the merge-to-`main` checklist item (CF-C-1) is the real guard, not this test.
- **CF-C-11:** `DegradedLatch` writes to the same `UserDefaults`-backed `KeyValueStore` InnerTubeKit persists `SessionStore`'s cooldown and visitorData in, under `FitrahTube.Browse.degradedUntil`. If a "reset extraction state" affordance is ever added to the 7-tap DeveloperDialog (ruling 62), it must clear this key too, or the app stays degraded after the reset.

---

## Forks for the controller (defaults are chosen; work proceeds unless overridden)

**A. CF-C2: a browse bot-check does not escalate the shared cooldown.** Reconciliation note 3 in full. **Default: do not escalate** — Android exempts the player from this cooldown (`remote-config-safemode.md` §1.4), and wiring browse into a ladder `StreamResolver` consults means one bad listing page can silence playback for an hour and, on the fourth trip, for a day. What Task 1 does instead is the actual root-cause fix (browse never adopted a WEB `visitorData`, so every call has been tokenless). Override = two lines in `BrowseClient`'s bot-check path, and then a channel page can take the player down.

**B. Degraded mode covers the header and Videos only; Live, Shorts and Playlists show an error.** Just two surfaces have a substitute source — `getPublicChannel` for the header, the Atom feed for Videos. Playlists was expected to be a third (spec §9 names "approved playlists (`/api/v1/channels/{id}`)") and is not: that endpoint returns a raw `Channel` with no playlist array, and nothing in the API resolves a *channel's* approved playlists (BACKEND-BLOCKED item 6). **Default: show the error state with Retry on all three**, because rendering them empty claims the channel has no streams, no Shorts and no playlists. Override = show them empty with the degraded notice, which is fewer states and a quieter screen, and a lie about the channel. When the backend adds the endpoint, Playlists moves to the substitute column and the fork narrows to two tabs.

**C. `IndexClient` and `ReportClient` are hand-written rather than generated.** Reconciliation note 6 and Task 3's first reconciliation note: the index endpoint is absent from `api-specification.yaml` altogether, and the reports schema there is three fields short of what the controller accepts — so *neither* body can go through the generated client as it stands. **Default: hand-write both** — ~35 lines each over the same `HTTPTransport`, versus editing a shared OpenAPI document and regenerating 3 700 lines, in a contract the Vue admin and Kotlin clients also generate from. Override = fix the spec (add the path, add the three optional report fields), regenerate, and delete both hand-written clients; strictly better long-term, strictly more diff today, and it makes Tasks 2 and 3 depend on a shared contract landing first.

**D. Ruling 63: `featuredCategoryId` becomes a remote-config key.** It is the one value a remote config would obviously own (`remote-config-safemode.md` §1.4, Q8), the field is optional so nothing breaks, and the whole change is ~10 lines plus one test. **Default: implement it, in Task 5's tail** (batched there, in its own commit, so Task 6 is acceptance-only). Override = leave `FeaturedViewModel.featuredCategoryId` hardcoded, in which case a category id change is an App Store submission — and ruling 63 stays unimplemented, which someone will notice at the Phase-2 gate.

**E. B4's sibling-id list is not built.** B4 offered it as the cheap route to skip-on-failure: "a sibling id list in the route arguments from Plan C's channel Shorts grid and this screen advancing an index". **Default: do not build it.** `Route.shorts` carries one video; B4's screen has no skip because rulings 50/51 removed the thing it would skip to, and adding a list now means Plan C ships an argument with no consumer. Override = the grid passes its loaded ids, and B4's screen grows an index — small on both sides, and best done as one change with both plans landed, not as speculation from this one.

**F. The collapsing banner may not survive the paged tab container.** Ruling 9 requires a scrollable tab strip *and* swipe-between-tabs; a fully collapsing header above a `TabView` of independently scrolling lists is the hard part, and SwiftUI has no `CoordinatorLayout`. **Default: the strip and the swipe are non-negotiable (ruling 9); the header collapses if it falls out of the layout cleanly and pins if it does not** — judged on Task 6's screenshots, not argued in advance. Override = drop swipe-between-tabs to get a clean collapse, which contradicts ruling 9 and is a controller decision, not an implementer's.
