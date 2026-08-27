# FitrahTube iOS — Design Spec

**Status:** Draft, awaiting user review.
**Date:** 2026-08-23
**Ticket:** IOS-APP-01
**Depends on:** `docs/architecture/ios-app-plan.md` (research, evidence, compliance analysis — referenced as "the plan" below; this spec does not repeat its evidence). Android inventory from 2026-08-23 (file:line citations below are Android sources under `android/app/src/main/`).

---

## 1. Goal

Ship a native SwiftUI app for **iPhone and iPad** with every feature the Android app has, the same information architecture and design tokens rendered with native iOS idioms, and the same backend. Stream resolution happens on the device (plan §6); the backend serves only the curated catalog and accounts.

---

## 2. Locked decisions (from brainstorm, 2026-08-23)

| # | Decision | Rationale |
|---|---|---|
| D1 | **iPhone + iPad only. No tvOS target.** Android has no TV build (no `LEANBACK_LAUNCHER`, no leanback dependency — `AndroidManifest.xml:48-51`, `build.gradle.kts:397-403`); its `sw720dp` bucket is a large-tablet layout and maps to iPad regular width. | Parity with what Android ships; tvOS has no `WKWebView`, so the embed compliance floor would not exist there (plan §9). |
| D2 | **Full parity in one effort**: plan's v1 (guest) and v1.1 (accounts, Me, sync, submissions, suggest, import) are both in scope. | User decision. |
| D3 | **Downloads/offline, Chromecast and AirPlay are all in scope.** In-app update is not (App Store handles updates; `minAppVersion` in remote config shows an "update required" screen). | User decision, with the 5.2.3 risk stated and accepted: Apple names YouTube verbatim in the downloading guideline (plan §9). |
| D4 | **Phase 0 probes skipped.** Code against the plan's 2026-08-22 measurements; the on-device checklist (plan §10) runs once there is an app. | Measurements one day old. |
| D5 | **UI = Android IA + tokens, native iOS idioms.** Same tabs, screens, flows, colors, spacing, card/grid proportions; SwiftUI `TabView`/`NavigationStack`, SF Symbols, system sheets. | User decision. |
| D6 | **Simulator-first.** User enrolls in the Apple Developer Program and registers the iOS app in Firebase project `albunyaan-tube` in parallel. Bundle ID `com.albunyaan.tube`. `GoogleService-Info.plist` lives at `~/.config/albunyaan/` (git-ignored); a placeholder is committed so simulator builds work. | No signing identity or Team on the dev Mac today. |
| D7 | **XcodeGen** (`ios/project.yml` → git-ignored `.xcodeproj`). Installed at `~/.local/bin/xcodegen` 2.46.0. | Diff-friendly, no Homebrew needed. |
| D8 | **Third-party code limited to** Firebase Auth (SPM), Google Sign-In (SPM), Google Cast SDK (XCFramework fetched by script — no SPM distribution exists, CocoaPods rejected: Ruby 2.6 on this Mac, project in maintenance mode). Everything else is Foundation/AVFoundation/SwiftData/URLSession/WebKit. | User decision. |
| D9 | **DI = native composition root.** One `AppContainer` builds every dependency behind a protocol; ViewModels take dependencies in `init`; views read the container from `@Environment`. Fakes for tests/previews. No DI framework. | Hilt-equivalent testability with zero dependencies. |
| D10 | **Backend additions are in scope**, inside the existing Spring app in `backend/` (no second backend): AASA/assetlinks handler, `DELETE /api/account`, `madeForKids`/`embeddable`/`ytRating` fields, Swift DTO codegen, "ad-free" removed from `share_app_promo`. | App Store requirements + deterministic player routing. |
| D11 | **Guest mode with 5 tabs**: Home → Channels → Me → Playlists → Videos (Android order, `bottom_nav_menu.xml:3-22`). Guest Me tab shows local favorites + a sign-in card. Never a forced sign-in. | App Store 5.1.1(v). |
| D12 | **The five dead Android settings are implemented for real on iOS**: Audio only, Background play, Safe Mode, Download quality, Wi-Fi-only downloads (`SettingsPreferences.kt:75-83` — stored, read by nothing on Android). | User decision. |
| D13 | **Animated splash like Android** (logo fade 400 ms + 30 dp slide, ~2.7 s total, `SplashFragment.kt:43,82,118`) as a SwiftUI overlay after the static launch screen. | User decision. |
| D14 | **Project structure**: app target + three local Swift packages (`FitrahAPI`, `InnerTubeKit`, `DownloadKit`). | Engines test with `swift test` without a simulator. |
| D15 | Branch `feature/ios-app` off `develop`; code under `ios/`; one PR per delivery phase (§15). | Repo conventions. |
| D16 | iOS 18 deployment target; built with Xcode 26.3 / iOS 26.2 SDK (plan §9: Xcode 26 required since 2026-04-28). | Plan §6.11 (`sidebarAdaptable` floor). |

---

## 3. Scope

**In**: every row of the plan's §7 parity table, plus Downloads, Chromecast, AirPlay, the five real settings, animated splash, accounts, Me, sync, submissions, suggest, import, account deletion, backend additions (§12).

**Out**: tvOS, Mac Catalyst/Vision Pro availability (opt out in App Store Connect), in-app update, Microsoft sign-in (hidden on Android — `SignInFragment.kt:209-210`), Android's MWEB dub-audio path (plan §6.5), the poToken minter in the shipped binary (plan §6.12), server-side stream resolution, any OTA code (plan §6.14), "Recently watched"/"History" beyond Android's coming-soon rows (`DownloadsFragment.kt:115-146`), the Length/Date/Sort filter UI (Android stores the state but inflates no menu for it — `FilterManager.kt:84-86`; no `R.menu.filter_menu` inflate site).

---

## 4. Project layout

```
ios/
├── project.yml                 # XcodeGen; FitrahTube.xcodeproj is git-ignored
├── Config/                     # Debug.xcconfig (API_BASE_URL=http://localhost:8080/), Release.xcconfig (https://app.fitrahtube.com/)
├── FitrahTube/                 # app target
│   ├── App/                    # FitrahTubeApp, AppContainer, Router, SplashRouter, RemoteConfig, DeepLinkParser
│   ├── Features/               # one folder per screen family, mirrors android ui/*
│   │   Home Channels Me Playlists Videos Search Categories Featured ChannelDetail PlaylistDetail
│   │   Player Shorts Downloads Favorites Settings About Onboarding Splash Auth Bootstrap Profile
│   │   Submissions Suggest Import Report
│   ├── DesignSystem/           # Tokens.swift (colors, type, spacing, radii), components
│   ├── Persistence/            # SwiftData models + stores, SettingsStore (UserDefaults)
│   ├── Sync/                   # SyncManager port
│   └── Resources/              # Localizable.xcstrings, Assets.xcassets, LaunchScreen, PrivacyInfo.xcprivacy,
│                               # GoogleService-Info.plist (placeholder), embed.html
├── FitrahTubeTests/            # ViewModel + routing tests against AppContainer.fake()
├── Packages/
│   ├── FitrahAPI/              # generated DTOs + client (Sources/FitrahAPI, Sources/FitrahAPIGenerated)
│   ├── InnerTubeKit/           # Sources/InnerTubeKit, Tests/InnerTubeKitTests (fixtures from the plan's probes)
│   └── DownloadKit/            # Sources/DownloadKit, Tests/DownloadKitTests
├── Vendor/                     # GoogleCast.xcframework — git-ignored, fetched
└── scripts/
    ├── fetch-cast-sdk.sh       # downloads Google's dynamic XCFramework zip into Vendor/
    ├── copy-firebase-plist.sh  # build phase: ~/.config/albunyaan/GoogleService-Info.plist → bundle, else placeholder
    └── generate-swift-dtos.sh  # called by scripts/generate-openapi-dtos.sh
```

`ios-remote-config.json` lives at the repo root next to `releases-meta.json` and is read from `main` via raw.githubusercontent.com (plan §6.13).

---

## 5. Dependency injection

```swift
protocol CatalogClient { func home(cursor:…) async throws -> HomePage; func content(query:…) … }
protocol StreamResolving { func resolve(_ videoId: String) async throws -> ResolvedStream }
protocol BrowseClient, FavoritesStore, SubscriptionsStore, SavedPlaylistsStore, AccountRepository,
         SyncEngine, DownloadManaging, SettingsStore, RemoteConfigProviding, ReportClient, IndexClient,
         ShareMetadataPublisher, ImportClient, ApprovalsClient …

@MainActor final class AppContainer {
    let catalog: CatalogClient; let resolver: StreamResolving; …
    static func live(baseURL: URL) -> AppContainer
    static func fake(…) -> AppContainer          // FakeCatalogClient ≙ Android FakeContentService
}
extension EnvironmentValues { @Entry var container: AppContainer = .fake() }
```

ViewModels are `@Observable @MainActor` classes with `init(catalog: CatalogClient, …)`. A screen creates its ViewModel from the container it reads via `@Environment(\.container)` (`@State private var model: HomeViewModel?` + `.task { model = HomeViewModel(catalog: container.catalog, …) }`). No singletons, no service locator. The container owns one `URLSession` for the backend, one ephemeral session per InnerTube client family, and one `ModelContainer`.

**Isolation rule (decided 2026-08-23, phase 0 review).** The app target compiles with `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`. `AppContainer` is `@MainActor`; its `init` and `fake()` are `nonisolated` (legal while every stored property is `Sendable`), so the `@Entry` environment default is a plain `.fake()` with no runtime isolation assumption. When a phase adds a non-`Sendable` SDK object (Firebase `Auth`, `GCKCastContext`), wrap it behind a `Sendable` protocol or a `@MainActor` store so the container's stored properties stay `Sendable` and `fake()` stays `nonisolated`. UI-facing store protocols (`FavoritesStore`, `SettingsStore`, …) are `@MainActor protocol`s with synchronous requirements, implemented by `@Observable` classes; engine protocols that run off the main actor (`StreamResolving`, `BrowseClient`, `CatalogClient`, `DownloadManaging`) are `nonisolated … Sendable` with `async` requirements.

---

## 6. Navigation

**Pre-shell** (`SplashRouter`, pure function, port of `SplashRouter.kt:22-37` minus forced sign-in):

```
!onboardingCompleted → Onboarding
signed out            → Main (guest)
me == nil (network)   → Main (guest, retry fetchMe in background)
ACTIVE                → Main
PENDING_PROFILE       → ProfileBootstrap
BLOCKED / DELETED     → sign out → Main (guest) + terminal alert
```

Splash runs `fetchMe` (1 attempt) and remote-config fetch in parallel with the animation; a deep link skips the animation (`SplashFragment.kt:108-250`). Onboarding: 3 pages, Skip/Next/Get started, flag persisted before navigating (`OnboardingFragment.kt:37-60`).

**Shell**: on compact width a standard `TabView` bottom bar; on regular/large width a custom **leading navigation rail** that mirrors Android's `NavigationRailView` (`layout-sw600dp/fragment_main_shell.xml`): width 80 pt (regular) / 96 pt (large), icons 28 / 32 pt, labels always shown (14 pt on large), items vertically centred, `background` surface with the 8 pt elevation shadow, `brand` tint when selected and `navInactive` otherwise, content and the offline banner offset by the rail width. `.sidebarAdaptable` is NOT used — on iPadOS 18+/26 it renders a floating top capsule bar with a sidebar toggle, which the user rejected on 2026-08-23 as unlike the Android tablet UI. Tabs Home, Channels, Me, Playlists, Videos. Re-selecting the current tab pops its stack to root; if already at root, scrolls to top (`MainShellFragment.kt:79-126`). Offline banner (`NWPathMonitor`) at the top; tab bar hidden during fullscreen playback (`MainShellFragment.kt:141-170`).

**Routes** (one `enum Route: Hashable` per `NavigationStack`): `channel(id)`, `playlist(id)`, `player(PlayerArgs)`, `shorts(ShortsArgs)`, `search`, `categories`, `subcategories(parent)`, `featured(mode)`, `downloads`, `favorites`, `settings`, `about`, `profile`, `mySubmissions`, `suggestContent`, `importFromYouTube`, `signIn`, `emailVerification`, `profileBootstrap`, `ageIneligible`. `PlayerArgs` carries Android's 12 arguments (`PlayerFragment.kt:400-438`): videoId, playlistId, startIndex, shuffled, targetVideoId, title, channelName, thumbnailUrl, description, durationSeconds, viewCount, channelId — the metadata fast path means no backend fetch before playback.

**Deep links**: custom scheme `albunyaantube://{video|channel|playlist|shorts}/{id}`; Universal Links `https://app.fitrahtube.com/{watch|channel|playlist}/{id}` and `/api/{watch|channel|playlist}/{id}` (`AndroidManifest.xml:48-129`; `shorts` is nav-graph-only on Android and becomes reachable on iOS). `onOpenURL` → `DeepLinkParser` → `Route`.

**Sheets and dialogs** (Android bottom sheets/dialog fragments): ContentReport, SubmitContent, EditSubmission, EditEmail/EditPassword/EditPhone, Language (not needed — iOS Settings deep link), Theme, DownloadQuality, QualityPicker, SubtitlePicker, AudioLanguage, DeveloperSettings; confirmations for sign-out, clear favorites, clear downloads, delete submission, import caution, import offer (one-time), blocked/deleted terminal alert.

**Filter**: `FilterState` (category id + name, length, date, sort) in UserDefaults, shared by Home/Channels/Playlists/Videos; category chosen from Categories/Subcategories ("Parent > Sub", pops back to origin — `SubcategoriesFragment.kt:52-86`).

---

## 7. Design system

**Colors** (`values/colors.xml:3-90`, `values-night/colors.xml:4-67`) defined in code as dynamic `UIColor`-backed `Color`s with light/dark pairs (`Tokens.swift`; diffable and unit-testable, no asset catalog), semantic names kept:

| Token | Light | Dark |
|---|---|---|
| brand (primary_green) | #275E4B | #35C491 |
| accent (primary_variant) | #35C491 | #35C491 |
| surfaceVariant | #E3E9E7 | #1A2E27 |
| background (background_gray) | #F5F5F5 | #121212 |
| homeSurface / homeCard | #F5F6F8 / #FFFFFF | #0F1512 / #1A231F |
| categoryPill | #E8F5F0 | #12352B |
| textPrimary / textSecondary / textMuted | #1A1A1A / #6B7280 / #9CA3AF | #F1F5F9 / #9CB3A7 / #74847C |
| accentRed | #D32F2F | #EF5350 |
| durationChip | #CC000000 | #CC000000 |
| videoCountChip | #CC275E4B | #CC35C491 |
| errorBg / errorText / errorIcon | #FFF3E0 / #E65100 / #FF6F00 | #3D2A1A / #FFCC80 / #FFB74D |
| skeleton / skeletonShimmer | #E0E0E0 / #FFFFFF (Android #F5F5F5 equals the page background, so the shimmer phase vanished — changed 2026-08-23) | #2A2A2A / #383838 |
| navInactive / navSelected | #757575 / brand | #B0B0B0 / brand |
| divider | #1A000000 | #1AFFFFFF |
| liveBadge / upcomingBadge | #F44336 / #2196F3 | same |
| heroOverlay | #40000000 | #66000000 |
| settingsIconBg | #F0F0F0 | #2A3530 |
| submissionPending/Approved/Rejected/Changes | #FFA000 / #43A047 / #E53935 / #1E88E5 | same |

Brand green is used for text and tints (AA-safe in both modes). Labels on a brand/accent fill use `onBrand` = light #FFFFFF / dark #0A1F18 (Android's `filter_chip` selected text on #35C491) — white on the dark-mode mint is 2.2:1 and fails AA, so iOS deliberately departs from Android there.

**Type** (`dimens.xml:140-197`, `styles.xml:22-72`): SF Pro, Dynamic Type relative to Android sizes — headline 20 bold (24 on large iPad), sectionTitle 18, subtitle 16, body 14, caption 12, badge 10 bold, duration 11 bold, homeSectionTitle 20 bold, itemTitle 15 medium, itemMeta 13, seeAll 14 medium, splashTitle 32/40/48, onboardingTitle 28/32/36. No `minimumScaleFactor`. Implementation maps each to the nearest system text style so Dynamic Type scales (`.title3` ≈ 20, `.headline` 17 ≈ 18, `.callout` 16, `.subheadline` 15 ≈ 14, `.caption` 12, `.caption2` 11 ≈ 10, `.footnote` 13); the large-iPad headline variant and splash/onboarding titles are added when their screens are built.

**Spacing** (`dimens.xml:4-13` + sw600/sw720 overrides): xxs 2, xs 4, sm 8, md 16/20/24, lg 24/32/40, xl 32/48/64, xxl 48, xxxl 96/112/128 — the three values select by effective width class (compact / regular <1000 pt / regular ≥1000 pt, approximating sw600/sw720).

**Radii** (`dimens.xml:16-19,109,126-132`): card 16, homeThumbnail 12, thumbnail 8, chip 4, filterChip 20, dialog 20, meChip 28, pill 999. Elevation → shadows sm 2 / md 4 / lg 8; list cards flat.

**Components** (names = Android layouts): `MediaCard` (home carousel, r16, 16:9, duration chip), `VideoRow` (140 pt 16:9 thumb r12, 16 bold title — `item_video_list.xml`), `VideoGridCell` (r12 thumb, fixed meta height — `item_video_grid.xml`), `ChannelRow` (circle 56/64/72), `HomeChannelItem` (circle 72/80/88, centered name), `PlaylistRow` (square 80/100/120 r12 + count chip), `ShortsCell` (9:16), `SectionHeader` (48 pt, emoji 20, "See all" chevron), `CategoryPill` (40 pt, r999), `CategoryChip` (surfaceVariant bg, brand text), `MeChip` (r28, 1 pt outline, 32 pt avatar), `Badge` (live/upcoming/duration), `SkeletonList`/`SkeletonHomeSection`/`SkeletonShorts`, `EmptyState` (96 pt icon, 20 bold, body ≤300 pt, optional 56 pt button), `ErrorState` (accentRed icon, retry 56/56/64), `InlineSectionEmpty/Error` (r12 cards), `DragHandle`.

**Grids** (`ChannelsFragmentNew.kt:150-151`, `VideosFragmentNew.kt:153-154`, `MeFragment.kt:153`, `dimens.xml:168-202`): channels/playlists 2/3/4 columns by width class; videos `max(2, min(8, floor(width/180)))`; Me 2 (3 on large iPad); shorts 2/4/5; carousel visible cards ch/pl/vid 2/2/2 → 4/3/3 → 6/5/5; card widths channel 100/110/120, playlist 220/240/280, video 260/280/320; content max width ∞/1200/1600 — on Android `content_max_width` is applied only by `layout-sw600dp/fragment_player.xml`, so it constrains the player (Phase 2) only; list and grid screens stay full-width on tablets as on Android. Pagination: `.onAppear` on the last row **and** `onScrollGeometryChange` "content fits and hasMore → loadMore()" with an in-flight guard (CLAUDE.md rule).

**Icons**: SF Symbols with the same meanings as the ~80 Android vectors; direction-sensitive icons use `.forward`/`.backward`. App icon and splash/onboarding artwork reused from `android/app/src/main/res` (adaptive foreground at 1024 px).

**Motion**: splash 400 ms fade + 30 pt slide; cross-dissolve on player rung transitions; static under Reduce Motion.

---

## 8. Backend client — `FitrahAPI`

- DTOs generated from `docs/architecture/api-specification.yaml` by `swift-openapi-generator` (client mode, URLSession transport), invoked from `scripts/generate-openapi-dtos.sh`. Hand-written wrappers where Android diverges from the spec (`my-submissions` uses `data` as the array key — `ApprovalDtos.kt:30-32`; Firestore timestamps `{seconds,nanos}` — `ApprovalDtos.kt:48-77`).
- Headers: `X-Device-Id` (UUID created once, UserDefaults — `NetworkModule.kt:95-101`) on every request; `Authorization: Bearer <Firebase ID token>` only when the host is the configured backend host (`FirebaseAuthInterceptor.kt:77-88`); on 401 with `WWW-Authenticate: Bearer`, refresh the token and retry once (`:117-179`).
- Account status: 403 body `{code: ACCOUNT_BLOCKED|ACCOUNT_DELETED}` on `/api/account`, `/api/admin`, `/api/v1/reports`, `/api/v1/index` → sign out, post `AccountStatusEvent`, terminal alert (`AccountStatusInterceptor.kt:43-133`, `MainActivity.kt:120-165`).
- Timeouts: 20 s idle (`timeoutIntervalForRequest`, = Android's 20 s read timeout) and 120 s for the whole transfer (`timeoutIntervalForResource`); no URLCache for API calls (Android has none); `GET /api/v1/home` honours `Cache-Control: max-age=300` via an in-memory TTL.
- Endpoints (method, path → use): `GET api/v1/content?type,cursor,limit≤50,category,length,date,sort,q` (lists, featured, shorts feed); `GET api/v1/categories`; `GET api/v1/search?q,type,limit` (bare array); `GET api/v1/home?cursor,categoryLimit,contentLimit,category`; `HEAD api/v1/{channels|playlists|videos}/{id}` (2xx ok, 410 blocked → hard stop, 404 → fail-open as Android `RetrofitContentService.kt:106-140`); `POST api/v1/reports`; `POST api/v1/index/streams` (≤50 items, 429 on 30 s dedupe); `GET api/account/me`; `POST/PUT api/account/profile` (422 `AGE_INELIGIBLE`, 429 rate-limit); `POST api/account/send-verification-email`; `GET api/account/sync?…` + `PUT/DELETE api/account/{subscriptions|playlists|favorites}/{id}`; `POST api/account/import/resolve`; `DELETE api/account` (new, §12); `POST {share}/api/share-metadata/{type}/{id}` (900 ms budget, fire-and-forget, signed-in only — `ShareMetadataPublisher.kt:29-87`); `GET api/admin/approvals/my-submissions?status,cursor,limit`; `POST api/admin/registry/{channels|playlists|videos}`; `PATCH …/submitter-note`; `DELETE …/submission`; `GET api/admin/youtube/search?q,type,pageToken`. Pagination: opaque cursor in `PageInfo{nextCursor,hasNext,totalCount,truncated}`.
- Errors: three shapes as Android (`{code}` envelope, `{code,message,retryAfterSeconds}`, OpenAPI `{error,message,details}`) mapped to one `APIError` enum.

---

## 9. InnerTube — `InnerTubeKit`

Implements plan §6.1–6.4, §6.7, §6.13 verbatim. Summary of the contract:

- `actor StreamResolver { func resolve(_ id: String, for purpose: Purpose) async throws -> ResolvedStream }` walks `RemoteConfig.resolverOrder` (`visionosHLS`, `androidItag18`, `embed`; unknown names dropped — *Owner directive 2026-08-27*: `openInYouTube` is no longer a valid entry, see below). One in-flight task per videoId, superseded requests cancelled, ≥500 ms spacing, 300 ms settle debounce, 8 s budget per rung. Branching on `playabilityStatus.status` **and** `reason`: bot-check → rotate `visitorData` (≤1/10 min) and retry once, then back-off; age gate → terminal "not available" state (never rotate); `UNPLAYABLE` → `androidItag18`; `LIVE_STREAM_OFFLINE` → scheduled state. When the catalog item carries `madeForKids`/`embeddable` (§12) rungs known to fail are skipped.
- `SessionStore`: one `visitorData` per client family (VISIONOS for `player`, ANDROID for itag 18, WEB for `browse`) in UserDefaults; contexts byte-identical per call; ephemeral `URLSession` with cookies disabled and fixed headers per client; cooldown on 429/repeated bot checks persisted (1 h → 24 h, port of `CooldownState.kt`).
- `ManifestCache`: memory-only, TTL `min(config, expires − duration − 600 s, 1 h)`, flushed on `NWPathMonitor` path change; re-resolve on 403/stall (§10).
- `ResolvedStream`: `.hls(URL, isLive, audioOnlyURL: itag140?, captionTracks)`, `.progressive(URL, label: "360p")`, `.embed(videoId)`; each carries `client`, `userAgent`, `expiresAt`, `resolvedAt`. *(Owner directive 2026-08-27: the `.openInYouTube(URL)` case is removed — no redirect or hand-off to YouTube, ever. Anything past `.embed` resolves to a terminal "not available" state, never a case that opens YouTube.)*
- `BrowseClient`: WEB-context `browse` — channel header, Videos via `VLUU…` uploads playlist continuation, Live/Shorts/Playlists via tab params, About from the header; playlist items via `VL<playlistId>`; continuation paging; exclusions from the backend applied; items pushed to `POST /api/v1/index/streams` (≤50, deduped). Degraded mode when bot-checked: approved playlists (`/api/v1/channels/{id}`) + Atom feed (`https://www.youtube.com/feeds/videos.xml?channel_id=`) + indexed search. `AtomFeedFetcher` with conditional GET (ETag/Last-Modified) ports `AtomChannelFeedFetcher.kt:12-60` for the Me tab.
- `RemoteConfig`: schema from plan §6.13; bundled default; last-known-good; fetched on launch and `willEnterForeground` with ≥15 min spacing; body ≤64 KiB; `minAppVersion` gate.
- `CaptionsProvider`: `captionTracks[].baseUrl&fmt=vtt` → cue overlay for auto-generated tracks (manual tracks come through HLS `SUBTITLES` renditions).

Not ported: `YoutubeClientRotator` (IOS client dead for this app — plan §3), `WebViewPoTokenProvider`, `NsigSolver`, `DubAudioEnumerator/Resolver`, `AndroidVrStreamResolver` (retired).

---

## 10. Player

**Host**: `AVPlayerViewController` via `UIViewControllerRepresentable`, stock transport (scrubber, ±10 s, speed, subtitle menu, PiP, AirPlay route picker) plus a FitrahTube toolbar and metadata area. Everything `@MainActor`.

**Feature parity** (Android source in parentheses):
- Resolve on open using `PlayerArgs` metadata; HEAD gate `api/v1/videos/{id}` before resolving (`PlayerRepository.kt:26-29`).
- Controls auto-hide 5 s; prev/next; double-tap left/right thirds ±10 s, centre double-tap fit/zoom in fullscreen (`PlayerGestureDetector.kt:10-79`) — implemented as an overlay on the content view.
- Quality: Auto / ≤1080p / ≤720p / ≤480p / Data saver → `preferredMaximumResolution` + `preferredPeakBitRate`; default cap = layer pixel size; cellular ceilings 720p; Low Data Mode honoured (plan §6.5). Labels "720p (1280×720)" from the HLS variant list (`PlayerViewModel.kt:1820-1849`). Rung 2 hides the control.
- Captions: stock menu for manual tracks; `CaptionsProvider` overlay for auto-generated ("(Auto-generated)"), auto-on when `UIAccessibility.isClosedCaptioningEnabled`.
- Audio language: `mediaSelectionGroup(forMediaCharacteristic: .audible)`; "Original: X" label; sticky per session (`PlayerViewModel.kt:79-123`).
- Audio-only: per-session toggle defaulting to the **Audio only** setting; swaps to the itag 140 URL at `currentTime`.
- Background play: when the **Background play** setting is on — `AVAudioSession` `.playback`, `audiovisualBackgroundPlaybackPolicy = .continuesIfPossible`, itag 140 swap on background; when off — pause on background. Interruption and route-change handling.
- PiP: user-initiated only; `allowsPictureInPicturePlayback`; never detach while active.
- Now Playing + `MPRemoteCommandCenter` (play/pause/skip/seek/next/prev).
- Favorite (SwiftData, toast pre-state), Share (`ShareLink` with `https://app.fitrahtube.com/api/watch/{id}`, OG publish when signed in, no "ad-free"), Report (VIDEO, with parent PLAYLIST/CHANNEL and subtype), Download button states COMPLETED → Open / RUNNING, QUEUED → disabled / else → quality picker (`PlayerFragment.kt:2105-2137`).
- Description expand/collapse with tappable http/https links (`PlayerDescriptions.kt`).
- Up Next: the playlist the video was opened from (grid of 2 on wide layouts); auto-advance on end unless Safe Mode; playlist paging prefetch when the queue ≤5; auto-skip unplayable max 3 consecutive (`PlayerViewModel.kt:1866-1925`).
- Live: LIVE badge, seek only within the DVR window, proactive re-resolve before `expire`, stall watchdog 45 s live / 6 s VOD (`PlayerViewModel.kt:1575-1610`).
- Fullscreen: 9:16 sources fullscreen in portrait, else landscape; auto-enter when opened in landscape on iPhone; zoom hint once (`PlayerFragment.kt:3382-3484`). Compact-height landscape hides metadata.
- Recovery: `AVPlayerItem.status == .failed` before first frame → next rung; 403 / failed-to-end / stall > 8 s → re-resolve same rung once, `replaceCurrentItem` + seek; budgets retries 3 / re-resolves 2 (`PlayerFragment.kt:1095-1111`); never silently swap a native stream into the embed.
- States and per-rung UI exactly as plan §6.6 (Loading with thumbnail, "Standard quality (360p)" pill, "Playing in YouTube's player" caption above the frame, embed error strings, terminal "not available" state, VoiceOver announcements). *(Owner directive 2026-08-27: there is no Open-in-YouTube confirmation sheet — that rung is removed.)*
- Resume position: session-only (Android persists none — `PlayerFragment.kt:2905-2930`).

**Embed rung**: bundled `embed.html` wrapping the IFrame API (`youtube-nocookie.com`, `playsinline=1`, `rel=0`, `enablejsapi=1`, `origin`, `hl`), `loadHTMLString(_:baseURL:)` so a Referer is sent, navigation-locked (`decidePolicyFor` cancels off-page main-frame navigations, `createWebViewWith` returns nil), one weak `WKScriptMessageHandler`, `onStateChange` verifies `video_id`, end screen covered by a Replay/Back card, reload once on content-process termination then terminal "not available" state, paused on background (plan §6.4 row 3, §6.10). *(Owner directive 2026-08-27: no rung 4 to fall back to.)*

**Shorts**: single 9:16 `AVPlayer` per item, no swipe-to-next (product decision, `ShortsPlayerFragment.kt:322-330`), repeat-one loop, tap play/pause with indicator, scrub-on-release timebar, rail (favorite, share, audio language, captions, download, channel avatar/handle), kebab (quality cap, report SHORT), portrait lock, stall 6 s → 2 recoveries → skip; feed = content service + channel Shorts tab blend (`ShortsFeedRepository.kt`). Embed rung sized 9:16.

**Safe Mode** (default on): embed navigation lock (always), playlist auto-advance off (plan §6.10). *(Owner directive 2026-08-27: the `openInYouTube` rung this used to remove no longer exists at all, in or out of Safe Mode.)*

**Chromecast** (Google Cast SDK, receiver `CC1AD845` as `CastOptionsProvider.kt:36-42`): `GCKUICastButton` in the toolbar, main player only (Android scope). On `sessionDidStart`/`didResume`: re-resolve a **fresh** stream, build `GCKMediaInformation` with the HLS manifest (`application/x-mpegurl`; `video/mp4` for itag 18; `.live` stream type for live; title, channel, thumbnail), `GCKMediaLoadRequestData` with autoplay and the local position; pause the local player; observe the load result and surface "Couldn't play on {device}" on failure (Android swallows it). On `sessionDidEnd`: seek local to the receiver's `approximateStreamPosition` and resume. Mini controller (`GCKUIMiniMediaControlsViewController`) pinned above the tab bar while a session is active. Known ceiling, documented in the About → Help text: the stream URL is bound to the phone's public IP, so casting works only when phone and Chromecast share an IPv4 NAT address (IPv6 networks and cellular will fail with the error above). Cast SDK is not loaded at all when `GCKCastContext` cannot be created.

**AirPlay**: `AVRoutePickerView` + `allowsExternalPlayback = true`. If the external device fails the item with a 403 (the plan's IP-binding risk), set `allowsExternalPlayback = false` and retry so video mirrors from the phone — AirPlay always works, at worst as mirroring.

---

## 11. Downloads — `DownloadKit`

Same model as Android: resolve on the device, no backend policy/token/manifest calls (those endpoints exist server-side and are unused by Android — `DownloadApi.kt` has no caller).

- `DownloadManager` (actor) owns a background `URLSession` (`allowsCellularAccess` = !wifiOnly, updated live when the setting changes) and an `AVAssetDownloadURLSession`.
- Quality picker (port of `DownloadQualityDialog.kt:64-114`): audio-only first, then 360p/480p/720p/1080p; the **Download quality** setting (low/medium/high = 360/720/1080) preselects it and is the tier used by playlist bulk download (`PlaylistDetailFragment.kt:693-732`, deduped on `playlistId|quality|videoId`).
- Engine per resolved rung: HLS → `AVAssetDownloadTask` with `AVAssetDownloadConfiguration` / `minimumRequiredMediaBitRate` chosen for the tier (no FFmpeg; downloads are packaged HLS playable by `AVPlayer` offline). itag 18 / itag 140 (audio-only) → `URLSessionDownloadTask`; pause/resume via resume data (a real upgrade over Android, where resume restarts). Embed rung → "This video can't be downloaded" (*Owner directive 2026-08-27*: there is no `openInYouTube` rung to reach past the embed). One re-resolve on 403 (`DownloadWorker.kt:266-276`); a download starts only if the URL's `expire` leaves ≥10 min.
- Persistence: SwiftData `DownloadItem { id, videoId, playlistId?, title, thumbnailURL?, qualityLabel, audioOnly, status (queued|running|paused|completed|failed|cancelled), bytesWritten, totalBytes?, errorCode?, localPath?, createdAt, completedAt? }`; files under `Application Support/downloads/`, excluded from iCloud backup; `.movpkg` for HLS, `.mp4`/`.m4a` otherwise. Task identifiers = item ids so in-flight tasks re-attach after relaunch (`handleEventsForBackgroundURLSession`).
- Error codes derived from `DownloadErrorCode.kt:12-39`: HTTP_403, HTTP_429, NETWORK, NO_STREAM, INVALID_INPUT, UNKNOWN kept with Android's localized strings; MERGE, NO_COMPATIBLE_VIDEO, VIDEO_AUDIO_MISMATCH dropped (no FFmpeg merge on iOS) and replaced by NOT_DOWNLOADABLE (embed rung; *Owner directive 2026-08-27*: no `openInYouTube` rung exists).
- Expiry: 30-day TTL, 1 h grace, sweep on launch and foreground (`DownloadExpiryPolicy.kt:23-28`); no quota (device storage is the limit, `DownloadStorage.kt:61-67`).
- Downloads screen (`DownloadsFragment.kt`, `DownloadsAdapter.kt:96-125`): rows with thumbnail, title, status, details (`size • relative time` or error), progress while running; actions Pause/Resume (running, paused, queued), Cancel (same), Retry/Remove (failed, cancelled), Open/Delete (completed); alphabetical by title; empty state "No downloads yet / Downloaded videos will appear here"; footer "%d downloads • %@ used • %@ available" with a bar of downloads-vs-total-device-storage; Library rows Favorites (live count), Recently watched / History (coming-soon toasts as Android). Entry points: Home overflow, Settings. **Open plays in-app** in the player's offline mode (same UI, no resolver, no quality/cast/download controls).
- Settings rows: Download quality, Wi-Fi only, Downloads library, Storage (used/available/total, quota bar), Clear downloads (confirmation → deletes via the manager, not the file system directly).
- Local notification on completion (`UNUserNotificationCenter`, permission requested on first download).

---

## 12. Backend additions (Spring, `backend/`)

| Item | Change | Tests |
|---|---|---|
| `WellKnownController` | `GET /.well-known/apple-app-site-association` and `/.well-known/assetlinks.json`, `application/json`, no redirect; values from `application.yml` (`app.ios.team-id`, `app.ios.bundle-id`, `app.android.sha256-fingerprints`); AASA `applinks` paths `/watch/*`, `/channel/*`, `/playlist/*`, `/api/watch/*`, `/api/channel/*`, `/api/playlist/*` with `appIDs: ["<TEAMID>.com.albunyaan.tube"]`; permitted in `SecurityConfig`, exempt from the `X-Device-Id` filter | MockMvc: content type, body, no auth |
| `DELETE /api/account` | Self-service: reuse the revoke + disable + soft-delete path of `AccountProfileService` (`:112-183`); 204; subsequent calls 403 `ACCOUNT_DELETED` | service + controller tests |
| `VideoValidationScheduler` | add `status` to `part`; persist `madeForKids`, `embeddable`, `contentRating.ytRating`; expose as optional fields on `ContentItemDto` and the video detail DTO; OpenAPI spec updated | scheduler mapping test |
| Swift codegen | `scripts/generate-openapi-dtos.sh` gains a Swift step (`swift-openapi-generator` via `swift run` in `ios/Packages/FitrahAPI`) | generated code compiles |
| `share_app_promo` | drop "ad-free" (backend string if any, Android `strings.xml`, iOS) | — |

Cloudflare currently returns 403 for `/.well-known/*` (plan §8); lifting that rule is outside the repo and is the user's task.

---

## 13. Accounts, Me, sync, import

- **Sign-in**: email/password (min 6 at sign-up as `SignInViewModel.kt:115`, 8 at bootstrap/edit), sign-up toggle, forgot password, Google (`GIDSignIn`), **Sign in with Apple** (`ASAuthorizationAppleIDProvider` → Firebase `OAuthProvider("apple.com")` with nonce); error mapping for the 13 codes in `AuthErrorMapper.kt:14-33`. After sign-in: password provider and unverified → EmailVerification; else re-run `SplashRouter`.
- **EmailVerification**: backend send with Firebase fallback, auto-send once, 60 s cooldown, "I've verified" → reload → route; back = sign out (`EmailVerificationViewModel.kt:59-142`).
- **ProfileBootstrap**: name ≤40; DOB picker; phone optional (E.164 regex + country hint, no libphonenumber); password ≥8 for non-password providers; two-phase commit with `profileSaved` latch (`ProfileBootstrapViewModel.kt:46-212`); 422 → AgeIneligible (terminal, deletes the Firebase user, continues as guest).
- **Me tab** (`MeFragment.kt`): signed-in — chips (subscriptions, cap 30 with `SubscriptionLimitGuard` semantics), favorites row with "See all", week-bucketed feed from Atom feeds with refresh state/backoff (`channel_feed_refresh_state`), Content/Pending tabs when awaiting > 0, kebab Profile / My Submissions / Suggest Content (role ∈ {moderator, admin}) / Import from YouTube / Sign out; one-time import offer. Guest — favorites + sign-in card.
- **Profile**: inline name/DOB (PUT with changed fields only; DOB ≥13 y), sheets for email (re-auth → `verifyBeforeUpdateEmail`), password (≥8, re-auth), phone; **Delete account** → confirmation → `DELETE /api/account` → Firebase `delete()` → guest.
- **Sync**: `SyncManager` port (`SyncManager.kt:76-634`): `bind(uid)` matrix, merge = tag anonymous rows → pull → push → mark; pull skips dirty local rows; monotonic tombstones; push 400/409/422 drops dirty, 401/403 aborts; one mutex; stalled-cursor guard; triggers foreground + push-on-change + connectivity restored. SwiftData models mirror the Room v11 columns, with one naming exception found in phase 1: a `@Model` property literally named `deleted` is silently reverted on save (it collides with Core Data's KVC `isDeleted`), so the Swift property is `isRemoved` while the sync payload keeps the wire name `deleted`.
- **Import from YouTube**: `GIDSignIn.addScopes(["https://www.googleapis.com/auth/youtube.readonly"])`; `subscriptions`, `playlists`, `videos?myRating=like` with the per-call bearer; review checklist; `POST /api/account/import/resolve` in batches of 200; dedupe against local rows; 429 stops the loop; token held in memory/keychain only and revocable from the Import screen (`YouTubeAuthManager.kt:17-100`, `ImportUiState.kt:28-85`).
- **My Submissions / Suggest Content**: as Android (`MySubmissionsFragment.kt`, `SuggestContentFragment.kt`), role-gated.
- **Account status events** → terminal alert and sign-out as §8.

---

## 14. i18n, accessibility, iPad

- `Localizable.xcstrings` generated by a one-off script from `values/strings*.xml`, `values-ar`, `values-nl` (778 keys, 11 plural groups; positional `%1$@` arguments; Arabic plurals as CLDR categories; keys missing in ar/nl fall back to English as on Android). `CFBundleLocalizations` = en, ar, nl. Settings "Language" row deep-links to iOS per-app language. Theme: system/light/dark → `preferredColorScheme`.
- RTL: leading/trailing only; `.forward/.backward` symbols; `formatted()` numerals; `\u{2068}…\u{2069}` isolation in composite strings; description `UITextView` `.natural`; no forced layout direction on transport controls.
- Accessibility: label + value on custom controls; ≥44 pt targets; Dynamic Type everywhere, single column at `.accessibility1+`; `AccessibilityNotification.Announcement` on rung changes; Reduce Motion → static skeletons and no splash slide.
- iPad: `horizontalSizeClass` + width drive layout; sidebar-adaptable tabs; player 16:9 top-anchored up to a max width; Shorts letterboxed 9:16; no split view. Verified on iPhone 17, iPad mini (A17 Pro), iPad Pro 13" (M5), in English and Arabic, before each phase's review pipeline.
- Privacy manifest, labels, export compliance flag, age-rating answers, reviewer notes per plan §9.

---

## 15. Delivery phases

| Phase | Scope | Gate |
|---|---|---|
| 0 | `ios/` scaffold: project.yml, app target, three packages, `AppContainer` + fakes, design tokens + components, xcconfigs, Cast fetch script, Firebase plist build phase, Swift DTO codegen, CI-able `xcodebuild test` | builds + tests on iPhone and iPad simulators |
| 1 | Catalog: splash, onboarding, shell, Home, Channels, Playlists, Videos, Search, Categories/Subcategories, Featured, filter, local Favorites, Settings (all rows, five real settings), About (+ developer dialog), i18n/RTL/iPad | every screen verified on 3 simulators × 2 languages |
| 2 | `InnerTubeKit`, player, Shorts, channel/playlist detail, report, share + metadata publish, deep/universal links, remote config, Safe Mode | engine tests; manual playback of lecture, kids, live, age-gated, embed-only samples |
| 3 | `DownloadKit`, Downloads screen, Cast, AirPlay | engine tests; simulator download/offline play; Cast verified on the user's network |
| 4 | Accounts: auth, verification, bootstrap, Me, profile + deletion, sync, submissions, suggest, import | tests against fakes; end-to-end once Firebase plist exists |
| 5 | Backend additions (§12) — parallel with 1–2 | `./gradlew test` |
| 6 | App Store readiness: manifests, labels, questionnaire, notes, TestFlight | blocked on Team ID + Firebase registration |

Each phase ends with the 9-stage review pipeline from `AGENTS.md` and one PR into `develop`.

---

## 16. Testing

- `InnerTubeKit`: ladder branching (status × reason matrix), session rotation rate limit, cooldown persistence, cache TTL/flush on path change, `browse` continuation parsing, Atom parsing, remote-config validation (unknown strategies dropped, size cap, last-known-good) — fixtures are real responses captured with the plan's Appendix A harness.
- `DownloadKit`: state machine transitions and action matrix, expiry sweep, resume-data round trip, cellular gate.
- `FitrahAPI`: header scoping by host, 401 single retry, 403 status envelope, cursor paging, the three error shapes.
- App: `SplashRouter` matrix, `DeepLinkParser`, filter persistence, grid column rules, `SyncManager` merge matrix, ViewModels against `AppContainer.fake()`.
- Backend: §12 tests.
- Manual matrix per phase: iPhone 17 / iPad mini / iPad Pro 13", en + ar, light + dark.
- Test time limits: 300 s wall-clock (`ios/scripts/test.sh` watchdog) and **60 s per test** — XCTest rounds `defaultTestExecutionTimeAllowance` up to 60 s and Swift Testing's `timeLimit` floor is one minute, so CLAUDE.md's 30 s per method is not expressible on iOS; 60 s is the platform floor (decided 2026-08-23).

---

## 17. Risks

| Risk | Mitigation |
|---|---|
| App Store 5.2.2/5.2.3 on native playback and on Downloads | Plan §9; embed fallback always works; Downloads is the user's accepted risk (D3). |
| VISIONOS enforcement arrives (android_vr precedent) | Remote-config reorder same day; minter on a branch (plan §6.12, §11). |
| `AVAssetDownloadTask` may reject YouTube's HLS (unverified) | Spike in phase 3 day 1; fallback is itag 18 + itag 140 progressive downloads for every tier (360p ceiling) — stated in the picker. |
| Cast fails off IPv4-NAT networks | Error surfaced; documented ceiling; AirPlay mirroring is the reliable TV path. |
| Firebase/Google Sign-In unverifiable until the plist exists | Fakes for all account flows; end-to-end in phase 4 once D6 is done. |
| Universal Links need AASA through Cloudflare | Backend handler in §12; Cloudflare rule is the user's task. |
| 120 Android strings are English-only in ar/nl | Same fallback as Android; list shipped with phase 1 for translation. |

---

## 18. Open questions (non-blocking)

1. Apple Team ID and Firebase iOS registration (D6) — needed for phases 4 and 6.
2. Whether `AVAssetDownloadTask` accepts YouTube's HLS packaging (§17).
3. Cloudflare `/.well-known/*` exemption.

---

## 19. References

- `docs/architecture/ios-app-plan.md` — evidence, compliance, runbook.
- Android sources cited inline (paths relative to `android/app/src/main/`).
- Apple: App Review Guidelines 2.3.1, 2.5.2, 4.8, 5.1.1(v), 5.1.4, 5.2.2, 5.2.3 (quoted in the plan §9).
- Google Cast iOS Sender SDK 4.8.6 — CocoaPods/manual XCFramework only (developers.google.com/cast/docs/ios_sender, fetched 2026-08-23).
