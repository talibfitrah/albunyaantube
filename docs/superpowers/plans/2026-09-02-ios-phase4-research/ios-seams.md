# Phase 4 research — iOS seams already in place

Every file below was read via `git show cac46c11:<path>` (the working tree is mid-edit by another
agent). Line numbers are from that commit.

---

## 1. `AppContainer` — what is injectable today

`ios/FitrahTube/App/AppContainer.swift` (362 lines), `@MainActor final class` (`:61`).

Stored / lazy members:

| Member | Line | Type |
|---|---|---|
| `catalog` | `:71` | `any CatalogClient` (injected) |
| `gateTransport` | `:78` | `any HTTPTransport` (injected) |
| `settings` | `:80` | `any SettingsStore` |
| `filters` | `:81` | `any FilterStore` |
| `searchHistory` | `:82` | `any SearchHistoryStore` |
| `favorites` | `:83` | `any FavoritesStore` |
| `savedPlaylists` | `:85` | `any SavedPlaylistsStore` |
| `subscriptions` | `:87` | `any SubscriptionsStore` |
| `offlineStore` | `:89` | `OfflineStore` (concrete) |
| `offlineBase` | `:92` | `URL` |
| `categories` | `:93` | `any CategoriesCache` |
| `network` | `:94` | `NetworkMonitor` |
| `offlineGate` | `:97` | `OfflineGateClient` |
| `offlineManager` | `:105` | `OfflineManager` (actor) |
| `castController` | `:112` | `CastController` |
| `innerTube` / `resolver` | `:156`, `:162` | `InnerTube` / `StreamResolver` |
| `index` | `:180` | `IndexClient` |
| `report` | `:182` | `any ReportClient` |
| `browse` | `:193` | `any BrowseSource` (injected via `injectedBrowse`) |
| `degradedHeader`, `playlistHeader` | `:206`, `:210` | `@Sendable` closures |

**No auth, no account, no sync, no import seam exists.** Phase 4 adds them.

- `init` (`:212-226`) takes `catalog`, `userDefaults`, `modelContainer`, `apiBaseURL`, plus optional
  `browse` / `degradedHeader` / `playlistHeader` / `gateTransport` / `offlineEngine`. It is
  `@MainActor`, not `nonisolated` — the doc at `:55-60` records the deviation from spec §5 (the
  Phase 1 `@Observable` stores are not `Sendable`).
- `live(baseURL:)` (`:228-241`) builds `DeviceId.persisted()`, `FitrahAPIClient.make(baseURL:deviceId:)`
  and the two `PublicHeaders` closures.
- `fake(catalog:defaults:browse:offlineEngine:)` (`:253-284`, `#if DEBUG`) uses a private
  `"fitrahtube.fake"` `UserDefaults` suite, an in-memory `ModelContainer`, and — importantly —
  `gateTransport: FixedStatusTransport(status: 503)` (`:283`) so a fixture container makes **zero**
  network requests for the offline gate. `FixedStatusTransport` (`:348-351`) is the ready-made
  pattern for a Phase 4 fake HTTP seam.
- `sharedFake` (`:336-341`) wipes its suite once at creation; `.fake()` deliberately does not.
- `AppContainer.current` (`:69`) — the app's ONE container, set by `FitrahTubeApp.init` (`:45`), the
  seam for `AppDelegate` callbacks with no scene. Firebase's `FirebaseApp.configure()` and
  `GIDSignIn.handleURL` will want the same.
- `makeModelContainer(inMemory:storeURL:)` (`:296-329`) — `Schema(versionedSchema: FavoritesSchemaV4.self)`
  with `FavoritesMigrationPlan`, retry-once then delete-store-and-rebuild then in-memory last resort.
  Its comment at `:290-292` literally says "Losing local favorites is the accepted cost (phase 4's
  sync restores them from the server)".
- `EnvironmentValues.container` (`:354-361`): `sharedFake` in DEBUG, `preconditionFailure` in
  Release.

**Isolation rule for Phase 4 (spec §5, `:55-60`):** Firebase `Auth` is not `Sendable`. Wrap it
behind a `@MainActor @Observable` store or a `Sendable` protocol so the container's stored
properties keep their current shape — the same trade `CastController` already made (`:107-112`,
built lazily, `setUp()` from the AppDelegate).

---

## 2. Launch routing — `FitrahTubeApp` and `SplashRouter`

`ios/FitrahTube/App/SplashRouter.swift` — **13 lines total**:

```swift
nonisolated enum SplashDestination: Equatable { case onboarding; case main }        // :4-7
nonisolated enum SplashRouter {
    static func destination(onboardingCompleted: Bool) -> SplashDestination { … }   // :10-12
}
```

Its own doc (`:1-3`) says "phase 4 adds the signed-in/account-status branches from
`splash-onboarding.md:1.7` (guest routing per spec §6 — iOS never forces sign-in)". So the port is
**a new function, not a rewrite** — spec §6's matrix is:

```
!onboardingCompleted   → Onboarding
signed out             → Main (guest)
me == nil (network)    → Main (guest, retry fetchMe in background)
ACTIVE                 → Main
PENDING_PROFILE        → ProfileBootstrap
BLOCKED / DELETED      → sign out → Main (guest) + terminal alert
```

`SplashRouterTests.swift` already exists and pins the 2-case version; extend it.

`ios/FitrahTube/App/FitrahTubeApp.swift` (343 lines):
- `@State private var container` built in the property initialiser (`:17-19` DEBUG with the
  `-fitrah-fake-container` / `-fitrah-api-base-url` hooks, `:30` Release).
- `init()` (`:36-46`) does the `-fitrah-stdout` redirect and sets `AppContainer.current`. **This is
  where `FirebaseApp.configure()` goes** (before any Firebase use, and it must tolerate a missing
  plist — see `dependencies-and-blockers.md` §3).
- `body` (`:63-91`): `RootView()` + `.environment(\.container/\.router)` + `.onOpenURL { router.open($0) }`
  (`:68` — Google Sign-In needs `GIDSignIn.sharedInstance.handle(url)` **before** the deep-link
  parser gets it) + a `.task` of eight launch hooks (`:69-81`) + `.onChange(of: scenePhase)`
  (`:82-84`) + `.overlay { UpdateRequiredView() }` (`:87-89`).
- `refreshRemoteConfigIfDue()` (`:98-132`) — the ≥15 min-spaced launch/foreground hook that also
  fires `offlineManager.sweep()` (`:107`) and `offlineManager.schedule()` (`:120`). **This is the
  natural home for the sync foreground trigger** (Android's `ProcessLifecycleOwner ON_RESUME`), and
  CF-D-13 already records that this glue is untested view-layer code.
- Debug launch hooks are the screenshot rig's whole API: `-fitrah-tab`, `-fitrah-route <case> …`
  (`:191-253`), `-fitrah-deeplink`, `-fitrah-banner`, `-fitrah-seed-favorites` (`:271-287`),
  `-fitrah-seed-subscriptions` (`:293-302`), `-fitrah-seed-offline` (`:307-324`). Phase 4 will want
  `-fitrah-fake-auth <signedOut|active|pendingProfile|blocked|deleted>` and a
  `-fitrah-seed-submissions` in the same shape.

---

## 3. `Route` and the shell

`ios/FitrahTube/App/Route.swift`:
- `Tab` (`:5-7`): `home, channels, me, playlists, videos` — the Me tab **exists** and is third.
  `Tab.title`/`symbolName` (`:12-31`): `nav_me` / `person.crop.circle`.
- `Route` (`:36-50`), **11 cases**: `player(PlayerArgs)`, `shorts(PlayerArgs)`, `channel(id:name:avatarURL:)`,
  `playlist(id:title:category:count:)`, `search`, `categories`, `subcategories(parentId:parentName:)`,
  `featured(categoryId:categoryName:)`, `favorites`, `settings`, `about`, `offline`.
  **No `profile`, `mySubmissions`, `suggestContent`, `importFromYouTube`, `signIn`,
  `emailVerification`, `profileBootstrap`, `ageIneligible`** — spec §6 lists all eight; all eight are
  Phase 4 additions.
- `PlayerArgs` (`:71-102`) — 16 fields incl. Phase 3's `offlineItemId`.

`ios/FitrahTube/Features/Shell/MainShellView.swift`:
- `destination(for:)` (`:169-196` in this commit's numbering) is a **fully exhaustive switch with no
  `default:` arm** — the Phase 3 plan's Global Constraint says so and it still holds. Adding N
  `Route` cases produces exactly one compile error, in that switch. `MainShellRoutingTests.swift`
  pins the arms.
- `rootView(for:)` (`:200-207`): `.me → MeGuestView()`.
- Two structurally different layout branches (`TabView` under 600 pt, a `ZStack` + `NavigationRailView`
  above) with a documented identity cost at the boundary (`:43-52`).
- Each tab's stack carries a `.safeAreaInset(edge: .bottom)` cast mini-controller (`:123-137`).

`ios/FitrahTube/Features/Me/MeGuestView.swift` (106 lines) — the current Me tab:
- `ScrollViewReader` + reselect-scroll-to-top on `router.scrollToTopSignal` (`:21-34`).
- `signInCard` (`:46-66`): `me_guest_title` / `me_guest_body` / `me_guest_sign_in` in a
  `Color.homeCard` rounded card; the button is **`.disabled(true)` with an explicit
  `// ponytail: sign-in needs phase-4 auth` note at `:56-61`**. Phase 4's smallest possible first
  change is enabling that button.
- `favoritesSection` (`:70-91`): `SectionHeader` with a "See all" that pushes `.favorites`, then
  `viewModel.recentFavorites` as `VideoRow`s, else `EmptyStateView`.
- Previews for LTR and `ar` + `.rightToLeft` (`:94-105`) — the house convention.

---

## 4. `FitrahAPI` — the generated client and the hand-written escape hatch

`ios/Packages/FitrahAPI/Sources/FitrahAPI/`:
- `FitrahAPIClient.swift` — `defaultSessionConfiguration` (`:9-16`): 20 s
  `timeoutIntervalForRequest`, 120 s `timeoutIntervalForResource`, `urlCache = nil`,
  `waitsForConnectivity = false` (spec §8 verbatim). ONE shared `URLSession` (`:21`).
  `make(baseURL:deviceId:transport:)` (`:29-39`) appends `"api"` to the base URL and installs
  **exactly one middleware**: `DeviceIdMiddleware`.
- `DeviceIdMiddleware.swift` (24 lines) — adds `X-Device-Id` to every request (`:21`).
  **There is no auth middleware.** Phase 4 adds a second one, and it must carry spec §8's rules:
  Bearer only when `request.url.host == apiHost`, plus the single 401 retry on
  `WWW-Authenticate: Bearer`. `RecordingTransport.swift` + `TestLimits.swift` in
  `Tests/FitrahAPITests/` are already shared into the app test target
  (`project.yml:114-115`), so a middleware test has its rig.
- `DeviceId.swift` — the UserDefaults UUID (`com.albunyaan.tube.deviceId`, RULING 69).
- `GeneratedSources/{Client,Types}.swift` — generated from the 8 filtered public paths only
  (`openapi-generator-config.yaml:6-15`).

`ios/FitrahTube/Catalog/PublicHeaders.swift` (54 lines) — the **workaround pattern Phase 4 will
reuse**, and its own doc says why it exists (`:5-12`): `api-specification.yaml` types
`createdAt`/`updatedAt` as `date-time` strings while the backend emits Firestore `Timestamp`
objects, so the generated `getPublicChannel`/`getPublicPlaylist` threw on every production response
and both call sites were silently dead. It:
- takes `transport: HTTPTransport = URLSessionTransport()`, `baseURL`, `deviceId` (`:18-22`);
- declares **narrow private `Decodable` structs with only the fields it reads** (`:35-45`);
- one generic `get<T>` adding `X-Device-Id` and rejecting non-2xx (`:47-53`).

`ios/FitrahTube/Features/Offline/OfflineGateClient.swift` is the Phase 3 evolution of the same
pattern, and adds the piece Phase 4 needs most: **status-code semantics with an envelope check**
(`:33-51` — a 404 counts as "gone" only when the body is the backend's own
`{timestamp,status,error,message,path}` JSON, because a proxy 404 would otherwise mass-delete).
Phase 4's account clients want the same discipline for 401/403 and for the
`{code: ACCOUNT_BLOCKED|ACCOUNT_DELETED}` envelope.

`ios/FitrahTube/Catalog/BackendAvailabilityGate.swift`, `IndexClient.swift`,
`Features/Report/ReportClient.swift` are three more hand-written clients on the same seam — the
house style is established and tested (`PublicHeadersTests`, `IndexClientTests`).

---

## 5. SwiftData schema and the user-scoping seam

`ios/FitrahTube/Persistence/FavoriteVideo.swift`:
- Versioned schemas V1→V4 (`:24-46`) and `FavoritesMigrationPlan` with **three lightweight stages**
  (`:48-55`). Adding an entity or an optional/defaulted property is a V5 + one more
  `.lightweight(...)` line — the pattern is proven three times.
- `@Model final class FavoriteVideo` (`:57-98`): `#Unique<FavoriteVideo>([\.videoId, \.userId])`
  (`:65`, deliberately on the **pair** so a second user cannot upsert over the first's row —
  `:58-64`), then `videoId, title, channelName, thumbnailUrl?, durationSeconds, addedAt, userId,
  updatedAt, isRemoved, dirty, approvalStatus, source?, importedAt?`. **Complete** against Room v11.
- **The `deleted` → `isRemoved` rename** and its evidence live at `:12-18`: a `@Model` property
  literally named `deleted` mutates in memory but is silently reverted by the next
  `ModelContext.save()` (Core Data KVC `isDeleted` collision), confirmed by a controlled A/B test.
  The wire name stays `deleted`.

`ios/FitrahTube/Catalog/SubscriptionsStore.swift`:
- `@MainActor protocol SubscriptionsStore { items; isSubscribed(_:); toggle(id:name:avatarURL:) }` (`:8-12`).
- `SubscriptionsError { invalidChannelId, capReached }` (`:14-19`).
- `@Model SubscribedChannel` (`:22-46`): `channelId, title, avatarUrl?, followedAt, userId,
  updatedAt, isRemoved, dirty`. **Missing vs Room v11: `channelUrl`, `approvalStatus`, `source`,
  `importedAt`.** Name drift: `title` (Room `name`), `followedAt` (Room `subscribedAt`).
- `SwiftDataSubscriptionsStore.cap = 30` (`:49`); id regex `[A-Za-z0-9_-]{3,64}` (`:64`);
  `toggle` soft-deletes with `dirty = true` and **never touches `updatedAt`** ("the server
  timestamp", `:86`) — already the Android contract.

`ios/FitrahTube/Catalog/SavedPlaylistsStore.swift`:
- `@Model SavedPlaylist` (`:20-46`): `playlistId, title, thumbnailUrl?, itemCount, addedAt, userId,
  updatedAt, isRemoved, dirty`. **Missing: `playlistUrl`, `uploaderName`, `approvalStatus`,
  `source`, `importedAt`. Extra: `itemCount`, which no sync DTO carries.** Name drift: `addedAt`
  (Room `savedAt`).

`ios/FitrahTube/Persistence/FavoritesStore.swift`:
- `currentUserId: String = "" { didSet { refresh() } }` (`:24-26`) with the doc "Phase 4 sets this
  from real auth state… re-subscribed when auth state changes" (`:21-23`). **The property is on the
  concrete class only** — `protocol FavoritesStore` (`:11-16`) does not declare it, and the same is
  true of `SubscriptionsStore` (`:8-12`) and `SavedPlaylistsStore` (`:8-12`). The container hands out
  `any FavoritesStore`, so Phase 4 cannot set the uid through the protocol as things stand. Cheapest
  fix: one `@MainActor protocol UserScoped { var currentUserId: String { get set } }` that all three
  adopt, and the container exposes.
- `isFavorite` intentionally ignores `approvalStatus` while `items` filters on it (`:8-10`) — an
  AWAITING imported favorite still reads as favorited but is hidden from the list. That asymmetry
  is Android parity and must survive.

**Phase 4 schema work:** `FavoritesSchemaV5` adding the five/six missing columns (all optional or
defaulted → lightweight) plus new `@Model`s for `SyncState` (entityType + userId composite) and
`AccountBinding`. Whether those two are SwiftData or UserDefaults is fork F3.

---

## 6. `SettingsStore`

`ios/FitrahTube/Persistence/SettingsStore.swift`:
- Protocol (`:9-32`): `appLocale`, `theme`, `audioOnly`, `backgroundPlay`, `safeMode`,
  `downloadQuality`, `wifiOnlyDownloads`, `onboardingCompleted`, **`importOfferShown`**, plus
  derived `resolvedLocale` and `colorScheme`.
- `importOfferShown` (`:23`, key `"import_offer_shown"` at `:44`, persisted at `:57`) is already the
  one-time import-offer latch Android calls `SettingsPreferences.IMPORT_OFFER_SHOWN_KEY` — Phase 4
  only has to read it.
- `appLocale` exists but nothing writes it — RULING 33 removed the picker (`:10-14`); the doc says
  it is "restored together by phase 4's picker", which is a **stale note**: spec §14 keeps the
  Settings "Language" row as a deep link to iOS per-app language. Do not build a picker.
- Defaults are read per-key, never through `UserDefaults.register(defaults:)`, to avoid the
  process-global registration domain leaking between suites (`:59-70`).

Also on Settings: Android's Settings has an Account section (`settings_account_header`,
`settings_account_signed_in_as`, `settings_account_sign_out*` — 8 keys already in the iOS catalog).
`SettingsView.swift` renders none of it today.

---

## 7. Firebase: nothing exists

Spec §15 row 0 lists "Firebase plist build phase" as a **Phase 0 deliverable**, and spec §4 names
`ios/scripts/copy-firebase-plist.sh`. Verified at `cac46c11`:

- `ios/scripts/` contains exactly `convert-strings.py`, `fetch-cast-sdk.sh`,
  `generate-swift-dtos.sh`, `screenshots.sh`, `test.sh`. **No `copy-firebase-plist.sh`.**
- `ios/project.yml` — `packages:` has only `FitrahAPI` and `InnerTubeKit` (`:33-37`); the app target
  has no build phase at all, no `GoogleService-Info.plist` reference, no Firebase/GoogleSignIn
  dependency (`:39-107`). Grep for `Firebase|GoogleService|GIDClientID|SignInWithApple|entitlement`
  over the file: zero hits.
- No `ios/FitrahTube/FitrahTube.entitlements`, no `GoogleService-Info.plist` anywhere in the tree.
- `ios/Config/Debug.xcconfig` (4 lines) and `Release.xcconfig` (4 lines) carry only `API_BASE_URL`,
  `FITRAH_TEAM_ID = ` (empty) and `#include? "Local.xcconfig"`.
- `Info.plist` properties (`project.yml:67-107`) have `CFBundleURLTypes` for the `albunyaantube`
  scheme only (`:104-106`) — Google Sign-In needs a **second** URL type with the reversed client id.

This is the same class of debt as Phase 3 contradiction 3 (the Cast fetch script Phase 0 promised
and never shipped, paid for in Phase 3 Task 8). **Phase 4 pays Phase 0's Firebase debt.**

---

## 8. Privacy manifest — will need edits

`ios/FitrahTube/Resources/PrivacyInfo.xcprivacy` (60 lines) declares:
- `NSPrivacyTracking = false`, empty `NSPrivacyTrackingDomains` (`:22-25`);
- **one** collected type: `NSPrivacyCollectedDataTypeDeviceID`, not linked, not tracking, purpose
  App Functionality (`:28-39`) — with a comment (`:6-12`) recording that an *empty* array while the
  `X-Device-Id` header ships is a review-rejection risk;
- accessed APIs: `UserDefaults` CA92.1 (`:43-50`) and System Boot Time 35F9.1 (`:51-58`).

Phase 4 adds **email address**, **name**, **date of birth**, **phone number** and **user ID**, all
linked to identity, purpose App Functionality — plus whatever the Firebase and GoogleSignIn SDKs
declare in their own bundled manifests. Not optional; App Store privacy validation reads it.

---

## 9. Design-system pieces a Me screen would reuse

`ios/FitrahTube/DesignSystem/Components.swift`: `RemoteImage` (`:51`), `DurationChip` (`:174`),
`Badge` (`:207`), `CategoryChip` (`:224`), `MediaCard` (`:244`), `VideoRow` (`:330`),
`VideoGridCell` (`:381`), `ChannelRow` (`:443`), `HomeChannelItem` (`:494`), `PlaylistRow` (`:545`),
`SectionHeader` (`:598`), `HomeSectionRow` (`:655`), `SearchField` (`:717`), `CategoryPill` (`:764`),
`BannerMessage`/`TransientBanner` (`:827`, `:846`), `Shimmer` (`:917`), `SkeletonGrid` (`:936`),
`SkeletonShorts` (`:970`), `SkeletonCarousel` (`:978`). Plus `DesignSystem/StateViews.swift`
(`EmptyStateView`, `ErrorState`), `Tokens.swift`, `Layout.swift` (`Spacing`, `Radius`, `TypeScale`,
`WidthClass`), `ComponentsGallery.swift`.

**No `MeChip`** (spec §7 names one: r28, 1 pt outline, 32 pt avatar). `HomeChannelItem` (circular
avatar + centred name) is the closest existing shape; the chip is a new component.

`ios/FitrahTube/Catalog/Formatting.swift` — `Format`, the ONE formatter (RULING 37: ICU compact +
plurals). Every count/date on a Me screen goes through it; `SearchField` and `PaginationGuard`
(`Features/Lists/PaginationGuard.swift`) are the other reusable pieces. Note the CLAUDE.md
pagination rule: the Me feed is a paged list on tablets and needs `PaginationGuard`, unlike the
Saved screen (Phase 3 exempted that one explicitly because it is a complete local list).

`ios/FitrahTube/Features/Favorites/FavoritesView.swift` + `FavoritesViewModel.swift` — the existing
favorites UI the Me tab already embeds via `recentFavorites` / `contentItem(for:)` /
`playerArgs(for:)`.

---

## 10. Strings pipeline — most account copy is already in the catalog

`ios/FitrahTube/Resources/Localizable.xcstrings` at `cac46c11` holds **780 keys**. Counted by
prefix:

| Prefix | Count | Examples |
|---|---|---|
| `auth_*` | 27 | `auth_sign_in_title`, `auth_email_hint`, `auth_forgot_password`, `auth_google_button`, all 13 `auth_error_*` |
| `email_verification_*` | 9 | `email_verification_body`, `..._check_now`, `..._rate_limited` |
| `bootstrap_*` | 21 | `bootstrap_display_name_label`, `bootstrap_dob_hint`, `bootstrap_error_*` |
| `profile_*` | 28 | `profile_delete_account`, `profile_delete_account_dialog_message` |
| `me_*` | 27 | `me_kebab_profile`, `me_tab_content`, `me_tab_pending`, `me_subscription_cap_reached`, `me_awaiting_*` |
| `my_submissions_*` | 20 | `my_submissions_delete_confirm_*`, `my_submissions_already_reviewed` |
| `suggest_*` | 15 | `suggest_error_not_allowed`, `suggest_already_pending` |
| `import_*` | 26 | `import_caution_*`, `import_offer_*`, `import_youtube_*` |
| `account_*` | 4 | `account_blocked_title/body`, `account_deleted_title/body` |
| `age_ineligible_*` | 3 | |
| `settings_account_*` | 8 | `settings_account_sign_out_confirm_*` |

**Missing and iOS-new** (must be authored under `EXTRA_KEYS` in
`ios/scripts/convert-strings.py:71+`, en/ar/nl, the `me_guest_*` / `share_app_promo` precedent):
"Sign in with Apple" (no Android source), any copy for a *guest* Me tab that gains a real sign-in
CTA, and any revoke-YouTube-access affordance (§C6). Two Android keys are ported but out of scope:
`auth_microsoft_button`, `auth_microsoft_unavailable_tv` (spec §3 Out) — candidates for `REFUSE`.

Rule (Phase 3 Global Constraints, still binding): never hand-edit `Localizable.xcstrings`; every
string goes through `convert-strings.py`, and `test.sh` stage 1 is `convert-strings.py --check`.

---

## 11. Test and screenshot rig conventions

`ios/scripts/test.sh` — five stages under a **300 s** wall-clock watchdog (`:1-13`):
`convert-strings.py --check` → `xcodegen generate` → `xcodebuild test` on **iPhone 17 + iPad Pro
13-inch (M5)** in one invocation → `swift test` in both packages → a Release build (compiles the
non-DEBUG paths). Per-test limit **60 s** (`:7-10`, the platform floor). `report_failures()`
(`:36-55`) walks the xcresult for failure messages. Overridable via `IPHONE_SIM`/`IPAD_SIM`
(`:27-28`). A Cast-framework pre-stage heals a fresh checkout.

`ios/scripts/screenshots.sh` — runs `FitrahTubeUITests` (deliberately outside the 300 s gate,
`ScreenshotTests.swift:13-14`: ~46 app launches) across `iPad mini (A17 Pro)`, `iPad Pro 13-inch (M5)`,
`iPhone 17` (`:23`), writing PNGs under
`.superpowers/sdd/<plan>/screenshots/task-14/<device-slug>/` via `TEST_RUNNER_FITRAH_SHOTS_DIR`
(`:5-6`). iPads capture every screen × {en-light, ar-dark} × {portrait, landscape}; the iPhone runs
Dynamic Type `.accessibility3` plus VoiceOver-label assertions (`:11-12`).

`ScreenshotTests.swift` conventions: a `Screen(key, arguments, anchor)` table (`:38-67`), where
`arguments` are the app's own DEBUG launch hooks and `anchor` is a locale-independent element that
proves the screen finished loading (`Anchor.button(substring)` / `.firstSwitch` / `.secondButton`
/ `.element(exact)`, `:21-35`) so no shot is ever taken mid-skeleton (`:19-20`). A Phase 4 screen
needs a launch hook, a locale-independent anchor, and a row in that table.

Existing tests Phase 4 will extend rather than replace: `SplashRouterTests`, `RouterTests`,
`MainShellRoutingTests`, `AppContainerTests`, `SubscriptionsStoreTests`, `FavoritesStoreTests`,
`SettingsStoreTests`, `LocalizationTests`, `PublicHeadersTests`, and in the package
`FitrahAPITests/DeviceIdMiddlewareTests` (the shape an auth-middleware test copies).
