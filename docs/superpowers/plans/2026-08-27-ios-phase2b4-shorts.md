# Shorts Player Implementation Plan (iOS Phase 2, Plan B4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** `Route.shorts` exists, `albunyaantube://shorts/{id}` parses into it, and it renders `PhaseTwoPlaceholderView` — a debug list of arguments. B4 makes it a real screen: one 9:16 short, looping, with the tap-to-pause indicator, the scrub bar, the action rail and the kebab, portrait-locked, over the same resolve ladder the main player already walks. It is the last Phase-2 *playback surface*; B5 owns fullscreen, gestures and Up Next.

**Architecture:** There is **no feed and no pager** — see reconciliation note 1, which is the single most important paragraph in this plan. `ShortsScreen` owns exactly one `PlayerViewModel` (B1) driving exactly one `PlayerHostView` (B1/B2), and that reuse is the whole design: the resolve ladder, the recovery budget, the stall watchdog, the quality ceiling, the audio-language menu, the caption tracks, the audio session, the Safe Mode filter and the `.embed` rung — the ladder's terminal rung (*Owner directive 2026-08-27*: there is no `.openInYouTube` rung; a video unplayable past `.embed` renders a terminal "not available" state) — all arrive already built and already tested. B4 adds one parameter to the host (`presentation`), one screen, and the chrome that hangs off it. Everything decidable is pure and testable without AVFoundation: `ShortsPresentation`'s knob table, `ShortsOverlay.showsChannelRow`, `ShortsScrub.progress` / `.time`. The `AVPlayerViewController` glue lives where it already lives.

**Tech Stack:** Swift 6, SwiftUI, AVKit, `@Observable`, Swift Testing; app target `ios/FitrahTube` only (`ios/Packages/InnerTubeKit` is read-only in this plan — B4 changes no package source). XcodeGen. Gate: `ios/scripts/test.sh` (300 s wall). Acceptance screenshots via `ios/scripts/screenshots.sh`.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §10 ("**Shorts**" paragraph, verbatim: "single 9:16 `AVPlayer` per item, no swipe-to-next … repeat-one loop, tap play/pause with indicator, scrub-on-release timebar, rail (favorite, share, audio language, captions, download, channel avatar/handle), kebab (quality cap, report SHORT), portrait lock, stall 6 s → 2 recoveries → skip … Embed rung sized 9:16"), §6 (`Route.shorts(ShortsArgs)`), §7 (deep links), §11 (`ShortsCell` 9:16, `content_max_width`), §14 (iPad: "Shorts letterboxed 9:16"). Detail: `docs/architecture/ios-app-plan.md` §6.8 (the whole Shorts section — "the same ladder, loop, no pager"; "on the embed rung size the `WKWebView` 9:16 and keep the navigation lock"), §6.2 ("Shorts: resolve the current item only (Android disables swipe-to-next deliberately, §6.8)"), §6.10 (Safe Mode), §6.11 (accessibility: "Shorts overlay text on a scrim; ≥ 44 pt targets"). Behavioural source: `docs/superpowers/plans/2026-08-23-ios-phase2-research/playlist-detail-shorts.md` **Part B** (§6–§13, the authoritative Android record) and `.../player.md` §3.2 (stall thresholds), §8.6 (`shorts_audio_track_original_prefix`). Predecessors: `.../2026-08-24-ios-phase2b1-player-core.md`, `.../2026-08-27-ios-phase2b2-background-audio.md`, `.../2026-08-27-ios-phase2b3-embed-safemode.md`.

**Android parity, and where this plan deliberately leaves it.** The record is `playlist-detail-shorts.md` Part B, cited file:line throughout; the origin plan is `docs/superpowers/plans/2026-04-14-android-shorts-player.md` (note that plan's Subscribe button was later removed on purpose — `ShortsPagerAdapter.kt:44-46`, "Subscribe is intentionally absent — that UX lives on the channel detail screen" — so the *brief*, not the old plan, is what B4 mirrors). Behaviours mirrored: repeat-one loop (`PlayerBinder.kt:154`), fill-and-crop video gravity (`item_shorts_page.xml:10-16` `resize_mode="zoom"`), full-screen tap toggle with the 112 dp flash indicator, 600 ms hold + 250 ms fade (`ShortsPageViewHolder.kt:124-154`), 3 dp scrub bar seeking only on release (`:841-853`), the rail order Like → Share → audio-language → CC (`item_shorts_page.xml:36-102`), audio-language options labelled `shorts_audio_track_original_prefix` (`:471-521`), kebab = Quality + Report only (`menu_shorts_kebab.xml:5-15`), channel avatar + `@handle` + 2-line title hidden when the channel is blank (`ShortsPageViewHolder.kt:44-60`), portrait lock and hidden system bars (`:886-903`), pause on background and resume-iff-it-was-playing (`:916-938`), 6 s stall watchdog (`:180-226`). Behaviours **not** mirrored, each with its ruling: the vertical pager and the feed behind it (rulings 50 + 51 — note 1), the tablet rail Report button (ruling 53; it is dead code on Android — defect 33), the always-visible Download button (rulings 28/56), the rail buttons that vanish on a like toggle (ruling 52 — defect Q7), and `keepScreenOn` (brief §13's recorded Android omission — on iOS there is nothing to port because `AVPlayer.preventsDisplaySleepDuringVideoPlayback` defaults to `true`; **do not add an `isIdleTimerDisabled` call**, AVFoundation already holds the display awake while a video is decoding).

**Rulings this plan implements** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/RULINGS.md`):

| # | Ruling | Where it lands |
|---|---|---|
| **50** | **Shorts vertical paging gestures disabled** (verbatim anti-doom-scrolling policy; confirmed contract) | Tasks 2–3 — there is no pager, no paging scroll view and no `TabView(.page)` anywhere in this plan. The gesture cannot be disabled by accident because the construction that would carry it is never built. |
| **51** | **Shorts global feed OUT of scope**; shorts open from the channel tab | Task 1 and note 1 — `ShortsScreen` takes one video's arguments and resolves one video. No `CatalogClient` call, no `BrowseClient` call. |
| **57** | Tab bar stays visible on the shorts screen on iPhone (parity); status bar hidden | Task 2 — the route pushes onto the selected tab's own `NavigationStack`, so the tab bar is inherited for free; `.statusBarHidden(true)` + `.toolbar(.hidden, for: .navigationBar)` deliver the rest |
| **53** | Report stays **kebab-only on every size class** (the tablet rail button is dead on Android — defect 33) | Task 3 — one Report entry point, in the kebab, identical on iPhone and iPad |
| **52** | Rail button visibility driven **solely by the count/track publishers** (fixes defect 32/Q7) | Task 3 — `AudioLanguageMenu` already self-hides at ≤1 option off its own `options` state, and the captions menu hides on an empty track list. Nothing else may write those visibilities, so a like toggle cannot clear them. |
| 28 / 56 | Every download affordance hidden until Phase 3 | Task 3 — no Download button on the rail. `shorts_download_cd` / `shorts_download_preparing` stay unused catalog keys. |
| 34 | The settings are REAL on iOS; audio-only seeds the player toggle | Task 2 — inherited from `PlayerViewModel`; the Background-play half is deliberately overridden for this screen (fork B) |
| 43 | Platform-standard auto-PiP via AVKit | Task 2 — auto-PiP is gated on `backgroundPlay`, which this screen forces false, so Shorts produce no floating window. Nothing new is written. |
| 36 | Quality: per-stream pick | Task 3 — the kebab's picker is `QualityOption` verbatim (CF-B1-5) |
| 37 | ONE formatter (`Format`) | Task 3 — the only formatted number on this screen is the scrub time (`player_duration_minutes_seconds`) |
| 14 | Age-restricted / geo-blocked / private / removed are terminal, no retries | Task 3 — inherited: `.contentUnavailable` renders `PlayerStateView`, exactly as `PlayerScreen`'s `default:` branch does |
| 58 | Safe Mode's first real effect | Task 3 — inherited whole from B3: `PlayerViewModel.map(_:safeMode:)` already applies Safe Mode before `ShortsScreen` ever sees a state. *(Owner directive 2026-08-27: rung 4 no longer exists at all, in or out of Safe Mode — RULINGS.md Q75 supersedes ruling 58's rung-4 wording.)* **B4 adds no Safe Mode code.** |
| 40 | Acceptance bar = "plays reliably, position-preserving refresh on failure", not Android's ladder-for-ladder parity | Task 4 |
| 67 | Inbound *watch* links open the regular player even for Shorts | Task 1 — untouched: `albunyaantube://video/{id}` and the `app.fitrahtube.com/watch/…` Universal Links still map to `.player`. Only the explicit `albunyaantube://shorts/{id}` scheme reaches this screen. |
| 11 | Shorts loading state renders the 9:16 skeleton grid | **Not here.** That ruling is about the channel detail **Shorts tab grid** — Plan C. B4's loading state is the player's own (`PlayerStateView`). |

**Carry-forwards this plan absorbs** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md`):

| Item | What it demands | Task |
|---|---|---|
| **CF-C1** | `BrowseClient.channelTab(.shorts)` returns an EMPTY page; the Shorts `lockupViewModel` variants are unmodelled | **B4 needs neither BrowseClient Shorts parsing nor the catalog Shorts feed** — it resolves one video id from its route arguments and makes no list call at all. CF-C1 stays entirely Plan C's, and Plan C's channel Shorts grid is what will construct this screen's arguments. Stated explicitly because the plan-B decomposition's B4 line says the opposite; see note 1. |
| **CF-B1-5** | `QualityOption.label`'s "Auto"/"Data Saver" are catalog strings; "B4's Shorts kebab reuses this type and inherits them" | 3 — the kebab's quality picker is `ForEach(QualityOption.allCases)` writing `model.selectedQuality`, the same two lines `PlayerScreen.qualityMenu` uses. No second ladder, no second label set. |
| **CF-B2-2** | `.prefetch` is B5's lane; a `.prefetch` refusal must be skipped silently; auto-advance must never pass `forceRefresh: true` | 2 — **satisfied by construction**: with no neighbours there is nothing to prefetch. B4 issues exactly the `.player` / `.autoRecovery` / `.proactiveTTLRefresh` kinds `PlayerViewModel` already issues, and adds **no** call site that passes `purpose: .prefetch` or `forceRefresh: true`. A test pins that the screen's own resolve path is `open()` and nothing else. |
| **CF-B2-8** | `detach()` deactivates the audio session unconditionally; once Shorts also owns audio, exactly one owner must deactivate | 2 — Shorts do not add an owner. They mount **the same `PlayerHostView`**, whose `Coordinator` owns the one `BackgroundPlaybackController`, and `ShortsScreen` and `PlayerScreen` are different routes that are never on screen together. The rule is written into the Global Constraints so a future "shorts rail inside the player" idea has to confront it. |
| **CF-B3-1** | `EmbedRungView` hard-codes a 16:9 frame; B4 needs 9:16 on the same rung — parameterise the aspect ratio, keep the lock and the end cover unchanged | 3 — one defaulted parameter, plus the ≥200×200 pt re-check B3's Task 4 asks for (the arithmetic is in Task 3) |
| **CF-B3-3** | The embed and the native host both own `AVAudioSession`; "Shorts (B4) adding a third player on the same screen would break that invariant" | 2 and 3 — **B4 adds no third player.** `ShortsScreen` mounts either the native host or the embed, never both, on the same one-branch-per-surface shape `PlayerScreen` uses. This is a Global Constraint, not an implementation detail. |
| CF-B1-9 | The player's Report button shows a "coming soon" banner; Plan C wires the real VIDEO report flow | 3 — the Shorts kebab's Report shows the **same** `player_report_coming_soon` banner, so Plan C has one flow to wire, not two. `contentSubType = "SHORT"` and `parentType = CHANNEL` are Plan C's payload concern; B4 records the requirement and passes nothing. |
| CF-B2-15 | Background play ON + a stream with no itag 140 keeps pulling video in the background | 2 — cannot arise here: this screen forces `backgroundPlay` false, so `backgroundPolicy` is `.pauses` for every stream shape |
| CF-B1-6 / CF-B2-1 / CF-B2-10 / CF-B2-11 / CF-B2-14 | `PlayerArgs`' remaining 3 fields, the app-scoped player holder, iPhone landscape chrome, the vacuous iPad assertion, the PiP scrubber freeze | **Not absorbed** — all B5. B4 adds one field to `PlayerArgs` (`channelAvatarURL`, Task 1) and does not touch the other three; it is portrait-locked, so CF-B2-10's landscape problem does not reach it. |

---

## Global Constraints

Implementers inherit nothing from earlier plans. All of the following are binding:

- **Swift 6, `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor`** on the app and unit-test targets (`ios/project.yml`). `AVPlayer` / `AVPlayerItem` / `AVPlayerViewController` are not `Sendable` and are main-actor-confined by default. Do not add `nonisolated` to silence a warning. `NotificationCenter` observer blocks are nonisolated: decode the payload inside the block and hop with `MainActor.assumeIsolated`, exactly as `BackgroundPlaybackController.attach` already does (`BackgroundPlaybackController.swift:76-100`).
- **One implementer at a time.** `ios/DerivedData` is shared; two concurrent `xcodebuild` runs corrupt it. Never dispatch two B4 tasks in parallel.
- **Gate:** `ios/scripts/test.sh` from the repo root, 300 s wall-clock watchdog, 60 s per test. It runs `convert-strings.py --check` → `xcodegen generate` → `xcodebuild test` (iPhone 17 + iPad Pro 13-inch (M5)) → `swift test` (packages — InnerTubeKit's suite is ~90 tests and is the bulk of the budget) → a Release build. A task is not done until this is green.
- **AVKit chrome is not XCUITest-accessible** on this toolchain (Xcode 26.3 / iOS 26.2), and on this screen the stock transport is switched off entirely, so there is nothing there to anchor on even in principle. AVKit's own "Video" label is system-localized and must never be asserted against. Every UI assertion anchors on FitrahTube's own accessibility identifiers. **New identifiers use the `shorts.` prefix** (`shorts.stage`, `shorts.playPauseIndicator`, `shorts.scrubber`, `shorts.likeButton`, `shorts.shareButton`, `shorts.kebab.button`, `shorts.kebab.report`, `shorts.qualityOption.*`, `shorts.channelHandle`, `shorts.title`, `shorts.back`); reused components keep the `player.` identifiers they already emit (`AudioLanguageMenu` → `player.audioLanguageMenu.button`) — **do not rename them**, the existing B1 tests assert on them.
- **`ios/scripts/screenshots.sh`'s device argument does not scope the trailing player blocks.** Passing a device name filters only the `DEVICES` loop; every `-only-testing:` block appended after it (B1 tasks 3–9, B2, B3) still runs on its own hard-coded destination. To capture one Shorts screenshot during development, **invoke a single `xcodebuild -only-testing:FitrahTubeUITests/ScreenshotTests/<case> -destination …` line by hand**; add the permanent block to `screenshots.sh` only in Task 4.
- **Stale install symptom.** If a UI test launches into a screen that no longer exists in the source (a placeholder where `ShortsScreen` should be, a missing identifier that is clearly present), the simulator is running a stale install: `xcrun simctl uninstall <device-udid> com.albunyaan.tube` and re-run. Do not "fix" source that is already correct.
- **`.ready` and `.rung2Progressive` share ONE `switch` branch** — this rule applies inside `ShortsScreen` exactly as it does in `PlayerScreen.stateView`, and for the identical reason: two branches give SwiftUI two view identities, so a mid-play demotion dismantles the host and drops the `AVPlayer` whose `currentTime()` is the only thing carrying the position (`PlayerScreen.swift`, Task 7's identity note; `PlayerHostView.player(for:replacing:audioOnly:)`). One branch, rung-specific chrome inside it.
- **Exactly one player on screen.** `ShortsScreen` mounts *either* `PlayerHostView` (rungs 1–2) *or* `EmbedRungView` (rung 3), never both, and never alongside `PlayerScreen`. This is what keeps CF-B2-8 / CF-B3-3's "exactly one audio-session owner" true. Do not add a second `AVPlayer`, a preview player, a muted background player, or a neighbouring page's player to this screen.
- **Never resolve a neighbour.** No `purpose: .prefetch`, no speculative `resolve`, and no `forceRefresh: true` outside the paths `PlayerViewModel` already owns (`retry()`, `handleRecoveryEvent`, `reResolveIfExpiring`). Plan §6.2: "Shorts: resolve the current item only". CF-B2-2 reserves `.prefetch` for B5.
- **All user-visible strings go through `ios/scripts/convert-strings.py`.** B4 needs **two** new keys, both in that script's `EXTRA_KEYS` dict, because Android has no source string for either (its kebab content description is a hard-coded literal — brief §9.3, recorded as a defect — and its scrub bar is an ExoPlayer `DefaultTimeBar` with no label at all). Never hand-edit `Localizable.xcstrings`; regenerate it. Every other string this plan uses **already exists** in the catalog (verified 2026-08-27): `shorts_like_cd`, `shorts_share_cd`, `shorts_audio_track_cd`, `shorts_audio_track_title`, `shorts_audio_track_original_prefix`, `shorts_channel_handle` (its `ar` value already carries the LRM mark), `subtitle_picker_cd`, `player_action_quality`, `player_quality_dialog_title`, `player_quality_auto`, `player_quality_data_saver`, `report_content`, `player_report_coming_soon`, `player_action_play_pause`, `player_duration_minutes_seconds`, `back`.
- **Do not use `shorts_error_unavailable` or `shorts_error_feed_empty`.** Both are live Android keys the converter keeps in the catalog, and both describe a feed this plan does not build ("Couldn't play this short. **Skipping…**" is a promise B4 cannot keep; ruling 54's network-vs-empty split is likewise about the feed). Shorts failures land on `PlayerStateView`'s existing, correct copy. Leave the keys in the catalog — they are Android's, not ours to delete — and do not reference them from Swift. The same applies to `shorts_subscribe` / `shorts_subscribed` (Subscribe is intentionally absent) and `shorts_download_cd` / `shorts_download_preparing` (Phase 3).
- **Copy rules (spec §10, plan §6.6, §9 checklist).** Never the words "ad-free" anywhere in-app. Never a kids-vs-lecture explanation: say *what* is playing, never *why* this video needed a different player.
- **Accessibility floor (plan §6.11).** Every rail button and the kebab and back controls are ≥44×44 pt (measure the tap target, not the glyph). Overlay text sits on a scrim, never on bare video. The title and handle scale with Dynamic Type; the rail glyphs do not. Reduce Motion turns the play/pause flash into an instant show/hide, not a fade.
- **No new `.md` files.** This plan is the only document B4 creates. `docs/superpowers/HANDOFF.md`, `docs/superpowers/plans/2026-08-23-ios-phase2a-innertubekit.md`, the B3 plan and any `ios/` peer docs are work owned by other agents — **never `git add` them**; stage only the exact files each task's commit step names.
- **The simulator can prove the chrome, not the stream.** With the existing `#if DEBUG` fixture resolvers (`FixturePlayerResolver`, `FixtureHLSPlayerResolver`, …) the whole screen — stage, loop, tap indicator, scrubber, rail, kebab, embed frame, error states — renders and is assertable with no network. What it cannot prove: a real 9:16 YouTube short's aspect handling, a real multi-audio short, a real stall, the ringer switch, and everything on the device list. Task 4 separates the three tiers and marks the device tier USER-BLOCKED (the repo has no signing identity — `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset).

---

## Reconciliation — read all four before writing code

**1. There is no feed and there is no pager, and this contradicts both the spec sentence and the plan-B decomposition line. Ruling 51 wins.**

Spec §10's Shorts paragraph ends "feed = content service + channel Shorts tab blend (`ShortsFeedRepository.kt`)", and `.superpowers/sdd/scratch/plan-B-decomposition.md`'s B4 line says "feed = content service + channel Shorts blend … B4 uses the content-service feed path". **Ruling 51 post-dates the decomposition scaffold and overrides both**: "Shorts global feed OUT of scope (unreachable on Android outside an internal deep link, no attribution); shorts open from the channel tab". Read together with **ruling 50** (paging gestures disabled) the two leave nothing for a feed to do:

- Android's pager is `isUserInputEnabled = false` (`ShortsPlayerFragment.kt:322-329`). Its **only** programmatic advance is `SkipCurrent` after a short exhausts its recovery budget (`:424-433`).
- So the feed's entire observable purpose, once ruling 50 removes swiping, is *supplying the next item for a skip*.
- Ruling 51 removes the global feed. The channel feed's iOS source is `BrowseClient.channelTab(.shorts)`, which **returns an empty page today** (CF-C1) and is Plan C's to model.
- Building a pager over the catalog's `type=VIDEOS&length=SHORT` endpoint to serve a skip would therefore be: unreachable by gesture, populated from the feed ruling 51 struck, and carrying no channel attribution (`ShortsFeedRepository.kt:53-56` leaves `channelId`/`channelName` blank forever — Android's own Q6 defect).

**This plan therefore ships a single-short screen.** `ShortsScreen` receives one video's arguments, resolves that video, loops it, and when it cannot be played shows `PlayerStateView` with Retry — the same terminal surface every other unplayable video gets. **Cost if wrong**, stated plainly: skip-on-failure disappears (there is nothing to skip to), and if the controller later wants it, the cheap version is Plan C's channel Shorts grid passing a sibling id list in the route arguments and this screen advancing an index — a change measured in tens of lines, on top of a screen that already exists. That is strictly less work than building, testing and then deleting a feed now. **Do not build a `ShortsFeedRepository`, a `TabView(.page)`, a `ScrollView(.vertical)` with paging, or a prefetch trigger.**

**2. "stall 6 s → 2 recoveries → skip" (spec §10) becomes "stall 6 s → recover → `.recoveryExhausted` + Retry".** The thresholds are already right: `PlaybackRecovery`'s VOD stall window is 6 s (`player.md` §3.2), the same number Android's Shorts watchdog uses (`STALL_RECOVERY_MS`, brief §9.6). The budgets differ — B1 implements spec §10's global "retries 3 / re-resolves 2", Android's Shorts uses its own `MAX_ERROR_RECOVERIES = 2` — and B4 **keeps B1's**: a second, Shorts-only budget machine would be a second unenforceable model of the same contract, and the difference only ever mattered because Android's shorts had a next item to fall into. The "skip" terminus becomes `.recoveryExhausted`, which `PlayerStateView` already renders with a working manual Retry. One recovery machine, not two.

**3. Ruling 57's "status bar hidden" is honoured, and the tab bar comes for free.** `Route.shorts` is pushed onto the selected tab's own `NavigationStack` (`MainShellView.navigationStack(for:)`), so the tab bar stays visible on iPhone with no code — exactly Android's behaviour, and the reason its overlay margins exist (brief §9.5). What does take code is the top: `.statusBarHidden(true)` plus `.toolbar(.hidden, for: .navigationBar)`, and then a Back control of our own, because hiding the bar hides the system chevron. See fork D — this is the one place B4 deliberately re-creates an Android affordance instead of taking the platform's.

**4. Safe Mode, the ladder, the availability gate and the report payload are all inherited, not re-implemented.** `PlayerViewModel` already applies B3's `map(_:safeMode:)` (*Owner directive 2026-08-27*: there is no rung 4 to remove — it doesn't exist regardless of Safe Mode; the ladder ends at `.embed`), already routes through `RateLimitedResolver`, already maps `ExtractionError` onto the terminal states of ruling 14, and already carries B1's HEAD availability gate through `StreamResolver`. `ShortsScreen` constructs the same view model with the same container dependencies and reads its `state`. **If a Safe Mode, ladder or gate behaviour is missing on this screen, the bug is in the shared code and must be fixed there** — never with a Shorts-only branch.

---

### Task 1: The route carries a short, not a bare id — arguments, deep link, entry point, strings

**Why this is first:** everything else needs somewhere to render. Today `Route.shorts(id: String)` carries one string and `MainShellView.destination(for:)` sends it to `PhaseTwoPlaceholderView` through the `default:` arm. The screen needs the same metadata fast path the player has (title, channel, thumbnail, duration — spec §6: "the metadata fast path means no backend fetch before playback"), plus the channel avatar the rail's bottom overlay shows. `PlayerArgs` already carries eight of those nine fields, so it becomes the payload rather than a parallel `ShortsArgs` type that would have to be kept in sync with it.

**Files:**
- Modify: `ios/FitrahTube/App/Route.swift` (`PlayerArgs.channelAvatarURL`; `case shorts(PlayerArgs)`)
- Modify: `ios/FitrahTube/App/DeepLinkParser.swift` (`.shorts(id:)` → `.shorts(PlayerArgs(videoId: id))`)
- Modify: `ios/FitrahTube/Features/Placeholders/PhaseTwoPlaceholderView.swift` (the `.shorts` argument list)
- Modify: `ios/scripts/convert-strings.py` (`EXTRA_KEYS` — two additions)
- Modify: `ios/FitrahTube/Resources/Localizable.xcstrings` (**regenerated by the script, never hand-edited**)
- Test: `ios/FitrahTubeTests/DeepLinkParserTests.swift` (extend), `ios/FitrahTubeTests/RouteTests.swift` (extend; create only if it genuinely does not exist — check first)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `PlayerArgs.channelAvatarURL: URL?` (defaulted `nil`, so no existing initializer call site changes).
  - `Route.shorts(PlayerArgs)` — **replaces** `Route.shorts(id: String)`.
  - Two localized keys: `shorts_more_options_cd`, `shorts_seek_cd`.

**Reconciliation — spec §6 names the payload `ShortsArgs`.** It is `PlayerArgs` here, and the reason is that spec §6's own justification for `PlayerArgs` ("Android's 12 arguments … the metadata fast path means no backend fetch before playback") applies verbatim to Android's Shorts arguments. Those are seven (brief §6), and six of them already exist on `PlayerArgs` verbatim:

| Android `shortsPlayerFragment` arg | `PlayerArgs` field |
|---|---|
| `initialShortId` | `videoId` |
| `channelId` | `channelId` |
| `initialShortTitle` | `title` |
| `initialChannelName` | `channelName` |
| `initialThumbnailUrl` | `thumbnailURL` |
| `initialDurationSeconds` | `durationSeconds` |
| `initialChannelAvatarUrl` | **new** `channelAvatarURL` |

So the whole delta is one field. A separate `ShortsArgs` would duplicate those six, plus an initializer and the `ContentItem` mapping, and would have to be extended in lockstep every time `PlayerArgs` grows (CF-B1-6 already queues three more fields for B5). If a Shorts-only argument ever appears that has no meaning for the player, that is the moment to split — not before.

- [ ] **Step 1: Write the failing tests**

In `DeepLinkParserTests.swift`, update whatever currently asserts `.shorts(id:)` and add:

```swift
@Test func shortsSchemeCarriesPlayerArgsWithOnlyTheVideoId() {
    // Ruling 51: the deep link is a SINGLE short, not an entry into a feed -- so everything except
    // the id is nil and the screen resolves exactly one video.
    let route = DeepLinkParser.route(for: URL(string: "albunyaantube://shorts/xc7keR2piUM")!)
    #expect(route == .shorts(PlayerArgs(videoId: "xc7keR2piUM")))
}

@Test func watchLinksStillOpenTheRegularPlayerEvenForAShort() {
    // Ruling 67, verbatim: "Inbound watch links open the regular player even for Shorts (parity;
    // the receiver cannot know it is a Short before resolution)". This is the test that stops a
    // well-meaning future change from sniffing durations at parse time.
    let route = DeepLinkParser.route(for: URL(string: "https://app.fitrahtube.com/api/watch/xc7keR2piUM")!)
    #expect(route == .player(PlayerArgs(videoId: "xc7keR2piUM")))
}
```

In `RouteTests.swift` (or `PlayerArgsTests`, wherever `PlayerArgs(item:)` is already pinned):

```swift
@Test func channelAvatarDefaultsToNilAndDoesNotDisturbTheCatalogMapping() {
    let item = ContentItem(id: "v1", type: .video, title: "T", category: "Cat", description: nil,
                           thumbnailURL: nil, durationSeconds: 30, uploadedDaysAgo: nil,
                           viewCount: nil, channelTitle: "Real Channel", subscribers: nil,
                           videoCount: nil, itemCount: nil)
    #expect(PlayerArgs(item: item).channelAvatarURL == nil)
    #expect(PlayerArgs(item: item).channelName == "Real Channel")   // RULINGS #17, still true
}
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `PlayerArgs` has no `channelAvatarURL`, and `.shorts(PlayerArgs)` does not exist. Existing `.shorts(id:)` call sites in `DeepLinkParser` and `PhaseTwoPlaceholderView` also fail to compile; that is the change surface, and it is exactly three files.

- [ ] **Step 3: Implement**

1. `PlayerArgs` gains one stored property, positioned after `channelId` so the memberwise order stays readable:
   ```swift
   /// Shorts only (B4): the channel avatar the Shorts bottom overlay shows next to the @handle
   /// (`ShortsPageViewHolder.kt:44-60`). The main player has no avatar affordance, so this is nil on
   /// every `.player` route -- and nil is also the honest value for a Short opened from a deep link,
   /// where the sender supplies nothing but an id.
   var channelAvatarURL: URL? = nil
   ```
2. `Route.shorts(id: String)` → `Route.shorts(PlayerArgs)`.
3. `DeepLinkParser`: `case "shorts": return .shorts(PlayerArgs(videoId: id))`. Leave the `segments[0] != "shorts"` guard on the Universal Link path exactly as it is — it is what makes ruling 67 true, and its comment already says so.
4. `PhaseTwoPlaceholderView`: `.shorts` no longer needs an arm at all once Task 2 gives it a real screen, but it must compile in between — give it the same argument list `.player` has, extracted into one helper both cases call, or simply `case .shorts(let args): playerArguments(args)`. Do not leave two copies of the nine-row list.
5. `EXTRA_KEYS` in `convert-strings.py`, with the comment convention the existing entries use:
   ```python
       # Shorts chrome (B4 tasks 2-3). Both are iOS-only. Android's kebab content description is a
       # hard-coded "More options" literal in fragment_shorts_player.xml:44 (recorded as a defect in
       # playlist-detail-shorts.md 9.3, not a string resource we can port), and its scrub bar is an
       # ExoPlayer DefaultTimeBar with no accessibility label at all.
       "shorts_more_options_cd": {"en": "More options", "ar": "المزيد من الخيارات", "nl": "Meer opties"},
       "shorts_seek_cd": {"en": "Seek", "ar": "التنقل في المقطع", "nl": "Zoeken in video"},
   ```
   Then regenerate: `python3 ios/scripts/convert-strings.py` (no `--check`), and confirm `git diff` on `Localizable.xcstrings` shows exactly two added keys.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`
Expected: green, including `convert-strings.py --check`.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/App/Route.swift \
        ios/FitrahTube/App/DeepLinkParser.swift \
        ios/FitrahTube/Features/Placeholders/PhaseTwoPlaceholderView.swift \
        ios/scripts/convert-strings.py \
        ios/FitrahTube/Resources/Localizable.xcstrings \
        ios/FitrahTubeTests/DeepLinkParserTests.swift \
        ios/FitrahTubeTests/RouteTests.swift
git commit -m "[FEAT]: iOS shorts route carries player args"
```

---

### Task 2: The Shorts playback surface — `PlayerHostView.presentation`, the loop, and the 9:16 stage

**Why this is second:** it is the whole "reuse, don't rebuild" thesis, and it is where the plan is either cheap or it is not. Four behaviours separate a Shorts stage from the main player's: no stock transport, fill-and-crop instead of fit, repeat-one, and no background playback or PiP. Every one of them is a property already set in `PlayerHostView.makeUIViewController`/`updateUIViewController` — so B4 adds **one parameter**, not a second host.

**Files:**
- Create: `ios/FitrahTube/Features/Player/ShortsScreen.swift` (the screen; chrome arrives in Task 3)
- Create: `ios/FitrahTube/App/OrientationLock.swift` (the `UIApplicationDelegateAdaptor` and its one flag — fork C)
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (`presentation`, the loop observer, the effective-background-play read)
- Modify: `ios/FitrahTube/App/FitrahTubeApp.swift` (`@UIApplicationDelegateAdaptor`)
- Modify: `ios/FitrahTube/Features/Shell/MainShellView.swift` (`case .shorts(let args): ShortsScreen(args: args)`)
- Modify: `ios/FitrahTubeTests/Support/PlayerTestDoubles.swift` (`RecordingResolver.Call` gains `purpose`; see Step 1)
- Test: `ios/FitrahTubeTests/PlayerHostTests.swift` (extend), `ios/FitrahTubeTests/ShortsScreenTests.swift` (new)

**Interfaces:**
- Consumes: Task 1's `Route.shorts(PlayerArgs)`.
- Produces:
  - `enum PlayerPresentation: Sendable, Equatable { case standard, shorts }` and `PlayerHostView(state:quality:audioOnly:model:presentation:)` — **`presentation` defaults to `.standard`, so no existing `PlayerHostView` call site changes.**
  - `PlayerPresentation`'s pure knob table: `showsPlaybackControls`, `videoGravity`, `loops`, `allowsBackgroundPlayback`.
  - `RecordingResolver.Call.purpose` (test double only) — the lane assertion CF-B2-2 needs.
  - `ShortsScreen(args: PlayerArgs)`.
  - `OrientationLock.mask` (a `@MainActor` static) + `AppDelegate`.

**Reconciliation — why `AVPlayerViewController` at all, when the transport is switched off.** Android uses a bare `PlayerView` with `use_controller="false"`. The iOS equivalent would be a plain `AVPlayerLayer` in a `UIViewRepresentable` — genuinely less code *in isolation*, and genuinely more code here, because `PlayerHostView` is where the quality ceiling, the audio-language hand-off, the caption hand-off, the recovery observers, the audio session, the stall watchdog and the position-preserving item replacement already live (`PlayerHostView.swift`, ~550 lines, all of it tested). Re-hosting an `AVPlayerLayer` means re-creating that glue for Shorts or refactoring it out from under the main player mid-phase. Reuse the controller and turn its chrome off: `showsPlaybackControls = false` is one line and leaves the video surface fully available to the SwiftUI overlay above it.

- [ ] **Step 1: Write the failing tests**

`PlayerPresentation` is a pure table, so it is pinned directly. In `PlayerHostTests.swift`:

```swift
@Test func shortsPresentationSwitchesOffEveryMainPlayerAffordance() {
    // Spec 10 Shorts: no stock transport, fill-and-crop (Android resize_mode="zoom"), repeat-one.
    // Ruling 43 + CF-B2-15: no background playback => backgroundPolicy .pauses AND
    // canStartPictureInPictureAutomaticallyFromInline false, both of which key off this one flag.
    #expect(PlayerPresentation.shorts.showsPlaybackControls == false)
    #expect(PlayerPresentation.shorts.videoGravity == .resizeAspectFill)
    #expect(PlayerPresentation.shorts.loops)
    #expect(PlayerPresentation.shorts.allowsBackgroundPlayback == false)
}

@Test func standardPresentationIsUnchangedFromB1AndB2() {
    // The regression guard for the defaulted parameter: nothing about the main player moved.
    #expect(PlayerPresentation.standard.showsPlaybackControls)
    #expect(PlayerPresentation.standard.videoGravity == .resizeAspect)
    #expect(PlayerPresentation.standard.loops == false)
    #expect(PlayerPresentation.standard.allowsBackgroundPlayback)
}

@Test func shortsNeverAskAVFoundationToKeepPlayingInTheBackground() {
    // CF-B2-15's cost cannot arise on this screen: with backgroundPlay false the policy is .pauses
    // for EVERY stream shape, itag 140 or not, so nothing pulls video for a screen nobody sees.
    #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: false, pictureInPictureActive: false) == .pauses)
    #expect(AudioSessionPolicy.decide(.enteredBackground, PlaybackPolicyContext(
        backgroundPlay: false, userAudioOnly: false, audioOnlyAvailable: true,
        pictureInPictureActive: false, wasPlayingBeforeInterruption: false,
        autoSwappedToAudioOnly: false)) == .none)
}

@Test func theLoopRestartsFromZeroRatherThanAdvancing() {
    // Android REPEAT_MODE_ONE (PlayerBinder.kt:154). The decision is pure so the notification glue
    // has nothing to decide: an ended item under .shorts seeks to zero and plays; under .standard
    // it does nothing (B5's auto-advance is the only thing allowed to react there).
    #expect(PlayerPresentation.shorts.actionOnPlayToEnd == .restart)
    #expect(PlayerPresentation.standard.actionOnPlayToEnd == .none)
}
```

In a new `ShortsScreenTests.swift`, over the **existing** shared double — `RecordingResolver` in `ios/FitrahTubeTests/Support/PlayerTestDoubles.swift:15-45`. **Do not write a third resolver double.** Its real shape today is `init(_ outcome: Outcome, holdsUntilReleased: Bool = false)`, `enum Outcome { case hls, progressive, failure(ExtractionError) }` (it builds its own `Resolved`), and `struct Call: Equatable { var videoId: String; var kind: RequestKind; var forceRefresh: Bool }` — **no `purpose` field**. Add one, and record it in `resolve()`:

```swift
    // B4: `purpose` was dropped on the floor. It is the lane CF-B2-2 reserves (.prefetch is B5's),
    // so a test that means "Shorts never resolve a neighbour" has to be able to see it.
    // `Equatable` comes off `Call` because `InnerTubeKit.Purpose` does not conform and this plan is
    // read-only in that package -- nothing in the suite compares whole `Call` values (verified
    // 2026-08-27: every assertion is on `.kind`, `.forceRefresh` or `calls.count`), so the
    // synthesised conformance was unused.
    struct Call { var videoId: String; var kind: RequestKind; var purpose: Purpose; var forceRefresh: Bool }
    ...
    _calls.append(Call(videoId: videoId, kind: kind, purpose: purpose, forceRefresh: forceRefresh))
```

Then the test:

```swift
@Test func shortsResolveTheCurrentItemAndNothingElse() async {
    // Plan 6.2 ("Shorts: resolve the current item only") + CF-B2-2 (.prefetch is B5's lane).
    // Opening the screen's view model must produce EXACTLY ONE resolve -- no neighbour, no
    // speculative warm-up -- on the interactive lane, uncached-forcing nothing.
    let resolver = RecordingResolver(.hls)
    let model = PlayerViewModel(resolver: resolver, settings: makeSettings(),
                                args: PlayerArgs(videoId: "short1"))
    await model.open()
    #expect(resolver.calls.count == 1)
    #expect(resolver.calls[0].forceRefresh == false)
    #expect(resolver.calls[0].kind == .player)
    // `Purpose` is not `Equatable` (InnerTubeKit.Models.swift:99, and this plan changes no package
    // source), so the lane is matched rather than compared. This is also the .prefetch assertion:
    // it fails on any call that is not `.player`.
    #expect(resolver.calls.allSatisfy { if case .player = $0.purpose { true } else { false } })
}
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `PlayerPresentation` does not exist, `ShortsScreen` does not exist.

- [ ] **Step 3: Implement**

**`PlayerPresentation`** (put it in `PlayerHostView.swift`, next to the type that reads it — it has no other consumer):

```swift
/// The two playback surfaces this app has. Not a feature flag and not a style: each case is a set
/// of four AVKit properties that must move together, and naming the surface is what stops them
/// drifting apart. Spec 10's Shorts paragraph is the whole right-hand column.
enum PlayerPresentation: Sendable, Equatable {
    case standard, shorts

    var showsPlaybackControls: Bool { self == .standard }
    var videoGravity: AVLayerVideoGravity { self == .shorts ? .resizeAspectFill : .resizeAspect }
    var loops: Bool { self == .shorts }
    /// Ruling 34 gives the Background play SETTING a real effect -- for the main player. Shorts
    /// override it to off (Android parity, brief 9.5: onStop pauses, onStart resumes iff it was
    /// playing). A 60-second clip on repeat-one is not a background-audio use case, and leaving it
    /// on would loop audio out of a screen the user has walked away from, indefinitely.
    var allowsBackgroundPlayback: Bool { self == .standard }
    var actionOnPlayToEnd: PlayToEndAction { loops ? .restart : .none }
}

enum PlayToEndAction: Sendable, Equatable { case restart, none }
```

**`PlayerHostView`** — the four wiring points, all inside the existing make/update pair:

1. `let presentation: PlayerPresentation = .standard` as a stored property with a default, so the existing `PlayerScreen` call site is untouched.
2. In `makeUIViewController` and `updateUIViewController`: `controller.showsPlaybackControls = presentation.showsPlaybackControls` and `controller.videoGravity = presentation.videoGravity`.
3. One computed `private var effectiveBackgroundPlay: Bool { presentation.allowsBackgroundPlayback && model.backgroundPlay }`, and route **all three** existing reads of `model.backgroundPlay` through it — `makeCoordinator()`, `configurePictureInPicture(_:backgroundPlay:)` and `applyBackgroundController`'s `background.backgroundPlay = …`. Miss one and Shorts get a floating PiP window from a home-swipe. Leave `configurePictureInPicture`'s body alone otherwise: `allowsPictureInPicturePlayback` stays `true` and is unreachable here because the transport that would show the button is switched off and auto-start is gated on the flag you just forced false.
4. The loop, in `Coordinator.observe(item:player:model:isLive:)`, next to the existing `failedToEndObserver`. It needs **three** edits, not one — the field, the arm, and the teardown:

   ```swift
   // (a) the stored field, beside `failedToEndObserver` in Coordinator's property block:
   private var endObserver: NSObjectProtocol?

   // (b) inside `observe(...)`, after the failed-to-play-to-end observer is installed.
   // Android REPEAT_MODE_ONE (PlayerBinder.kt:154). Per-ITEM, like every other observer here, so a
   // recovery replaceCurrentItem re-arms it against the new item rather than looping a dead one.
   // AVPlayerLooper/AVQueuePlayer would mean a second player type on this screen; one notification
   // and a seek is the whole feature.
   if playToEnd == .restart {
       endObserver = NotificationCenter.default.addObserver(
           forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak player] _ in
               MainActor.assumeIsolated {
                   player?.seek(to: .zero)
                   player?.play()
               }
           }
   }

   // (c) in `stopObserving()` (PlayerHostView.swift:399-407), which today clears
   // statusCancellable / failedToEndObserver / timeObserverToken only. Miss this and the loop
   // survives its own item: every recovery swap adds another observer to the same player.
   if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
   endObserver = nil
   ```

   `observe` takes the action as a parameter (`playToEnd: PlayToEndAction`) the same way it already takes `isLive`. **`applyPolicyAction`'s MIN-4 re-arm call site needs no change**: it only runs on `.swapToAudioOnly` / `.restoreVideoNow`, and with `.shorts.allowsBackgroundPlayback == false` the guard in `AudioSessionPolicy.decide(.enteredBackground, …)` returns `.none`, so neither action can ever fire on this screen. Give that call site whatever value keeps `.standard` behaviour (`.none`); do not plumb `playToEnd` through `policyHandler` for a path Shorts cannot reach.

**`ShortsScreen`** — Task 2 builds the stage only; Task 3 fills the overlay:

```swift
struct ShortsScreen: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @State private var model: PlayerViewModel?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let model { stage(model.state, model: model) } else { ProgressView().tint(.white) }
        }
        .statusBarHidden(true)                              // ruling 57
        .toolbar(.hidden, for: .navigationBar)              // fork D; the Back control is Task 3's
        .onAppear { OrientationLock.lockPortrait() }
        .onDisappear { OrientationLock.release() }
        .task {
            guard model == nil else { return }
            let vm = PlayerViewModel(resolver: Self.resolver(container: container),
                                     settings: container.settings, args: args)
            model = vm
            await vm.open()
        }
    }

    @ViewBuilder
    private func stage(_ state: StreamState, model: PlayerViewModel) -> some View {
        switch state {
        // ONE branch for both playable rungs -- Global Constraints, and the same identity rule
        // PlayerScreen.stateView carries. Rung-specific chrome differs INSIDE it (Task 3).
        case .ready, .rung2Progressive:
            PlayerHostView(state: state, quality: model.selectedQuality, audioOnly: model.audioOnly,
                           model: model, presentation: .shorts)
                .accessibilityIdentifier("shorts.stage")
                .aspectRatio(9.0 / 16.0, contentMode: .fit)
                .frame(maxWidth: Size.playerMaxWidth(widthClass))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        default:
            PlayerStateView(state: state, isOnline: container.network.isOnline,
                            thumbnailURL: args.thumbnailURL) { Task { await model.retry() } }
        }
    }
}
```

`Self.resolver(container:)` is the same `#if DEBUG` fixture ladder `PlayerScreen` uses. **Do not copy it** — `PlayerScreen.resolver(container:)` is `private static`; make it `static` (drop `private`) and call it, or lift it to a free function both call. One copy: it is the screen's only route to the real `RateLimitedResolver` wiring, and two copies means a future fixture hook lands on one screen and not the other.

`Size.playerMaxWidth(widthClass)` gives the iPad its centred column (spec §14 "Shorts letterboxed 9:16"); on iPhone it is nil and the 9:16 box fills the width. The `.aspectRatio(_:contentMode: .fit)` is what letterboxes rather than stretches, and `videoGravity = .resizeAspectFill` is what crops a non-9:16 source *inside* that box — the two are different levers and both are needed (a 16:9 video opened as a Short is cropped to fill, exactly as Android's `resize_mode="zoom"` does).

**`OrientationLock` + `AppDelegate`** (fork C — the whole file):

```swift
/// Portrait lock for the Shorts screen (spec 10, brief 9.5: ShortsPlayerFragment.kt:886-903 locks
/// portrait on resume and restores the previous policy on pause). SwiftUI exposes no per-view
/// orientation control, and UIHostingController's own override is not reachable from a NavigationStack
/// destination, so the supported-orientation callback is the only hook -- which lives on the app
/// delegate. This is the entire reason this app has one.
@MainActor enum OrientationLock {
    private(set) static var mask: UIInterfaceOrientationMask = .allButUpsideDown

    static func lockPortrait() {
        mask = .portrait
        request(.portrait)
    }

    static func release() {
        mask = .allButUpsideDown
        request(.allButUpsideDown)   // hands control back to the device; does not force a rotation
    }

    private static func request(_ orientations: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: orientations))
        // ponytail: no error handler. A refused geometry request means the device stays where it is,
        // which is the pre-B4 behaviour -- degrading to "not locked" is correct, crashing is not.
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        MainActor.assumeIsolated { OrientationLock.mask }
    }
}
```

and in `FitrahTubeApp`: `@UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate`. Note for B5: ruling 42's fullscreen orientation behaviour will want this same hook — leave `mask` as the one writable seam rather than adding a second mechanism.

Finally `MainShellView.destination(for:)` gains `case .shorts(let args): ShortsScreen(args: args)` above the `default:` arm.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`
Expected: green. Then, by hand (the screenshot script's device argument does not scope trailing blocks — Global Constraints):

```bash
cd ios && xcodebuild test -project FitrahTube.xcodeproj -scheme FitrahTube \
  -testPlan FitrahTubeUITests \
  -only-testing:FitrahTubeUITests/ScreenshotTests/testShortsScreen \
  -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath DerivedData
```

(add that case in Task 3; until then, launch the app in the simulator with `-fitrah-fake-player` and open `albunyaantube://shorts/abc123` via `xcrun simctl openurl booted`.) Confirm: the fixture clip plays, loops instead of stopping, has **no** AVKit transport, fills the 9:16 box, the status bar is gone, the tab bar is still there, and rotating the simulator does nothing (iPhone only — the iPad rotates, see I3 in Task 3).

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/ShortsScreen.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTube/App/OrientationLock.swift \
        ios/FitrahTube/App/FitrahTubeApp.swift \
        ios/FitrahTube/Features/Shell/MainShellView.swift \
        ios/FitrahTubeTests/Support/PlayerTestDoubles.swift \
        ios/FitrahTubeTests/PlayerHostTests.swift \
        ios/FitrahTubeTests/ShortsScreenTests.swift
git commit -m "[FEAT]: iOS shorts playback surface"
```

---

### Task 3: The Shorts chrome — tap indicator, scrub bar, rail, overlay, kebab, and the embed rung at 9:16

**Why this is third:** the stage plays; now it needs the controls Android's `item_shorts_page.xml` provides, and the two non-native rungs need somewhere to land. Everything here is a SwiftUI overlay on the Task 2 stage plus two `switch` arms.

**Blocked until B3 Task 4 is on the branch.** This task modifies `ios/FitrahTube/Features/Player/EmbedRungView.swift`, which **B3 Task 4 creates** (`docs/superpowers/plans/2026-08-27-ios-phase2b3-embed-safemode.md`, Task 4 Files: "Create: `EmbedRungView.swift`"), and it renders `StreamState.embed`, which B3 Task 2 adds. Tasks 1 and 2 are independent of B3 and may proceed; **do not start Task 3 until `git log`/`ls` confirms `EmbedRungView.swift` and the `.embed` case exist on this branch.** If B3 has not landed and the schedule demands it, the only safe partial is Tasks 1, 2 and 4 with the `.embed` arm and its test omitted — and then B4 is not done, because a Short that resolves to rung 3 would fall through to `PlayerStateView`.

**Files:**
- Modify: `ios/FitrahTube/Features/Player/ShortsScreen.swift` (the overlay, the kebab, the `.embed` arm)
- Create: `ios/FitrahTube/Features/Player/ShortsOverlay.swift` (`ShortsOverlay` + the pure `ShortsScrub` helpers)
- Create: `ios/FitrahTube/Features/Player/CaptionsMenu.swift` (extracted from `PlayerScreen.captionsMenu`)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (use the extracted `CaptionsMenu`; use the extracted rung-announcement modifier)
- Modify: `ios/FitrahTube/Features/Player/EmbedRungView.swift` (CF-B3-1: one defaulted `aspectRatio` parameter)
- Test: `ios/FitrahTubeTests/ShortsScreenTests.swift` (extend), `ios/FitrahTubeUITests/ScreenshotTests.swift` (extend)

**Interfaces:**
- Consumes: Task 1's two strings, Task 2's `ShortsScreen`, B1's `FavoriteToggle` / `QualityOption` / `AudioLanguageMenu` / `PlayerScreen.captionTracks(_:)`, B3's `EmbedRungView` and `PlayerStateView` secondary action.
- Produces:
  - `ShortsOverlay(model:args:)` — the rail, the bottom channel/title block, the tap target, the flash indicator and the scrub bar.
  - `ShortsScrub.progress(current:duration:)` and `ShortsScrub.time(progress:duration:)` — pure, testable, no `AVPlayer`.
  - `ShortsOverlay.showsChannelRow(channelName:)` — pure.
  - `CaptionsMenu(model:tracks:)` — the extracted view, used by both screens.
  - `EmbedRungView(resolved:model:args:aspectRatio:)` with `aspectRatio` defaulting to `16.0/9.0`.

**Layout** (Android `item_shorts_page.xml` + `fragment_shorts_player.xml`, brief §9.2–§9.3):

```
ZStack {
  <Task 2 stage>                                  // PlayerHostView, .shorts
  Color.clear.contentShape(Rectangle())           // full-screen tap target -> togglePlayPause
  playPauseIndicator                              // 112 pt, centre; see the timing rule below
  VStack {
    HStack { backButton; Spacer(); kebabMenu }     // 44 pt targets, top corners, white on scrim
    Spacer()
    HStack(alignment: .bottom) {
      channelAndTitle                              // avatar 36 + @handle + 2-line title, on a scrim
      Spacer()
      rail                                         // Like / Share / audio-language / CC
    }
    scrubBar                                       // 3 pt track, seek on release
  }
  .padding(Spacing.md(widthClass))
}
```

- **Tap target**: full-screen `Color.clear.contentShape(Rectangle()).onTapGesture` toggling `model.currentPlayer` between `play()` and `pause()`. It sits **above** the host (the transport is off, so nothing underneath wants the tap) and **below** the rail (so a rail tap is not also a pause). Label `player_action_play_pause`.
- **Flash indicator** (Android `ShortsPageViewHolder.kt:124-154`): 112 pt circle, translucent black. On **pause** the play glyph stays visible for as long as playback is paused. On **resume** the pause glyph shows and fades after **600 ms** over **250 ms**. Under Reduce Motion the fade is a plain removal. Identifier `shorts.playPauseIndicator`.
- **Scrub bar**: a `Slider` bound to a local `@State progress`, driven by an `addPeriodicTimeObserver` at **250 ms** (Android's ticker rate, `:857-879`) — reuse `CaptionOverlay.TimeObserver`'s start/store/cancel shape, it is the same one-owner pattern and already exists in this folder. **Seek only on release**: `Slider(value:in:onEditingChanged:)` — while `editing` is true, stop writing the observer's value into `progress` (otherwise the thumb fights the user) and issue no seek; on the false edge, `player.seek(to: ShortsScrub.time(progress:duration:))`. 3 pt track via `.scaleEffect(y:)` on a tinted slider or a custom track — either is fine, do not import a slider library. `accessibilityLabel(shorts_seek_cd)`, `accessibilityValue` = `player_duration_minutes_seconds` on the current time.
- **Rail** (top→bottom, `shorts.` identifiers, 56 pt spacing `Spacing.md`): **Like** — `FavoriteToggle.perform(item:wasFavorite:store:)` verbatim from `PlayerToolbar`, heart/heart.fill, labels `player_action_favorite` + the favorited/not-favorited values, cd `shorts_like_cd`; **Share** — `ShareLink` on `https://app.fitrahtube.com/api/watch/{videoId}`, the same URL `PlayerToolbar.shareURL` builds (extract that one-line computed property so both use it), cd `shorts_share_cd`; **audio language** — `AudioLanguageMenu(model:)` unchanged, which already hides itself at ≤1 option (ruling 52 satisfied by construction — nothing else may write its visibility); **captions** — `CaptionsMenu(model:tracks: PlayerScreen.captionTracks(state))`, hidden on an empty list. **No Download button** (rulings 28/56). **No Report button, on any size class** (ruling 53).
- **Channel + title**: `RemoteImage` avatar 36 pt circle + `String(format: shorts_channel_handle, channelName)` + the title at 2 lines, all over a bottom scrim gradient (plan §6.11: "Shorts overlay text on a scrim"). The whole row is hidden when `args.channelName` is nil/blank — `ShortsOverlay.showsChannelRow` — which is Android's behaviour verbatim and is why a deep-linked short shows title only. Tapping avatar or handle pushes `Route.channel(id:name:avatarURL:)` when `args.channelId` is non-blank, and does nothing when it is blank (Android `:631-642`).
- **Kebab** (`menu_shorts_kebab.xml`, ruling 53): a SwiftUI `Menu` with **Quality** (a submenu of `QualityOption.allCases` writing `model.selectedQuality`, checkmark on the current pick, section title `player_quality_dialog_title`) and **Report** (`report_content`) which shows the same `player_report_coming_soon` transient banner `PlayerToolbar.reportButton` shows — **CF-B1-9: Plan C wires one report flow, not two.** Label `shorts_more_options_cd`, identifier `shorts.kebab.button`.
- **Back**: `@Environment(\.dismiss)`, chevron, 44 pt, label `back`, identifier `shorts.back`. Confirm in Task 4 that the interactive swipe-back gesture still works with the navigation bar hidden; if it does not, the fix is `.navigationBarBackButtonHidden(false)` on a transparent bar, not a custom pop.

**The `.embed` arm (CF-B3-1) and the ≥200×200 pt check.** Add to `stage`'s switch, above `default:`:

```swift
case .embed(let resolved):
    EmbedRungView(resolved: resolved, model: model, args: args, aspectRatio: 9.0 / 16.0)
```

`EmbedRungView` gains `let aspectRatio: CGFloat = 16.0 / 9.0` and uses it in its one `.aspectRatio(...)` call. **Nothing else about the rung changes** — the navigation lock, the weak message-handler proxy, the end cover, the caption above the frame, `allowsPictureInPicturePlayback = false` and the non-persistent data store all stay exactly as B3 built them. B3's Task 4 asks the parameterising plan to re-check the 200×200 pt floor because 9:16 makes width the tight dimension; here is that check: the frame is `.fit` inside a portrait, portrait-**locked** screen, so height is the binding constraint on every supported device and width comes out at `height × 9/16`. Narrowest realistic case is a 320 pt-wide phone, where the width binds instead at 320 pt and height is 569 pt — both over 200. On the iPad the centred column is at most `Size.playerMaxWidth` wide and at least ~500 pt (a 950 pt-tall landscape window × 9/16). **The one geometry that would breach it — a ~390 pt-tall iPhone landscape window, giving a 219 pt-wide frame — cannot occur, because this screen is portrait-locked.** *(Fix round 2, I3: the lock is iPhone-only. There is no `UIRequiresFullScreen` — deliberately, it is not to be added — so iPad multitasking ignores the mask and an iPad CAN rotate on this screen; the narrowest iPad landscape geometry is ≈562 pt wide, which still clears the 200 pt floor with room to spare.)* So, as in B3: no `minHeight`, and the screenshot matrix is where a regression shows. If fork C is ever answered "no portrait lock", **re-do this arithmetic**.

**The terminal "not available" arm** (*Owner directive 2026-08-27*: `.openInYouTube` no longer exists — unplayable past `.embed` is a terminal not-available card, never a hand-off confirmation) rides `default:` → `PlayerStateView`, the same way `PlayerScreen` handles it. **Verify B3's actual shape before writing it**: if B3 attached that card as a `.sheet` on `PlayerScreen`'s body rather than to a view `PlayerStateView` can carry, move that modifier onto a small shared wrapper both screens use. Two copies of the same terminal-state UI is exactly the duplication ruling 66 ("ONE report path") was written about, applied to a different case.

**Rung announcements.** `PlayerScreen` has an `.onChange(of: model?.state)` that announces the rung-2 transition (and, after B3, the embed transition). `ShortsScreen` needs the identical announcement — plan §6.11: "every rung transition announced". Lift it into a small view modifier (`.rungAnnouncements(state:)`) in `PlayerStateView.swift` or alongside it, and apply it on both screens. One copy.

- [ ] **Step 1: Write the failing tests**

In `ShortsScreenTests.swift`:

```swift
@Test func scrubMathsRoundTripsAndSurvivesADegenerateDuration() {
    #expect(ShortsScrub.progress(current: 15, duration: 60) == 0.25)
    #expect(ShortsScrub.time(progress: 0.25, duration: 60) == 15)
    // A live/unknown duration is 0 or NaN on a real AVPlayerItem; the bar must clamp, not divide.
    #expect(ShortsScrub.progress(current: 15, duration: 0) == 0)
    #expect(ShortsScrub.progress(current: 15, duration: .nan) == 0)
    #expect(ShortsScrub.time(progress: 2.0, duration: 60) == 60)   // clamped, never seeks past end
}

@Test func theChannelRowHidesItselfWhenTheShortCarriesNoChannel() {
    // ShortsPageViewHolder.kt:44-60 -- feed/deep-link mode "often lacks channelName", and an "@"
    // with nothing after it is worse than no row. Blank, not just nil: Android checks isBlank.
    #expect(ShortsOverlay.showsChannelRow(channelName: "Ustadh Example"))
    #expect(ShortsOverlay.showsChannelRow(channelName: nil) == false)
    #expect(ShortsOverlay.showsChannelRow(channelName: "   ") == false)
}

@Test func theShortsKebabReusesTheOneQualityLadder() {
    // CF-B1-5: B4's kebab reuses QualityOption and inherits its catalog labels. This fails loudly
    // if someone adds a Shorts-only ladder (Android hard-codes 2160..144 in the VM; iOS has one).
    #expect(QualityOption.allCases.count == 5)
    #expect(QualityOption.auto.label == String(localized: "player_quality_auto"))
    #expect(QualityOption.dataSaver.label == String(localized: "player_quality_data_saver"))
}

@Test func theEmbedRungIsTheOnlyThingThatChangesShapeForShorts() {
    // CF-B3-1: the aspect ratio is the ONE parameter. Its default keeps every B3 call site 16:9.
    #expect(EmbedRungView.defaultAspectRatio == 16.0 / 9.0)
    // The 200x200 pt floor at 9:16, on the narrowest portrait-locked geometry this app supports.
    #expect(EmbedRungView.fittedSize(container: CGSize(width: 320, height: 568),
                                     aspectRatio: 9.0 / 16.0).width >= 200)
}
```

(`EmbedRungView.fittedSize` is a two-line pure helper — add it if B3 did not; it is what makes the floor a test rather than a comment.)

In `ScreenshotTests.swift`, add `testShortsScreen`: launch with `-fitrah-fake-player`, `xcrun simctl`-free (drive the deep link through the app's own launch-argument hook or push the route from the test's entry screen — follow whatever mechanism `testPlayerScreen` already uses), wait for `shorts.stage`, assert `shorts.likeButton` / `shorts.kebab.button` / `shorts.scrubber` exist and `player.audioLanguageMenu.button` does **not** (the fixture clip is single-track — the same hidden-state proof `testPlayerAudioLanguageMenuHiddenForSingleTrackFixture` uses), then capture. Add a second case `testShortsKebab` that taps `shorts.kebab.button` and captures the open menu.

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `ShortsScrub`, `ShortsOverlay`, `CaptionsMenu`, `EmbedRungView.defaultAspectRatio` do not exist; the screenshot cases cannot find `shorts.stage`.

- [ ] **Step 3: Implement**

Build the overlay to the layout above. Order of work that keeps the gate green throughout: the pure helpers first (`ShortsScrub`, `showsChannelRow`), then the `CaptionsMenu` extraction with `PlayerScreen` switched over to it in the same edit (the gate will catch it if the extraction changed behaviour — `PlayerScreenCaptionsTests` already pins `activeCaptionTrack`), then the overlay, then the kebab, then the `.embed` arm and the `EmbedRungView` parameter.

Three things to be careful about, all of which have bitten this codebase before:

- **Hit testing.** The full-screen tap target must not eat rail taps. Put it directly above the host and let the chrome `VStack` sit above it; do not use `.allowsHitTesting(false)` on the chrome and do not attach the tap gesture to the outermost `ZStack`.
- **The periodic time observer is an owner.** Start it when `model.currentPlayer` becomes non-nil, cancel it in `onDisappear` and whenever the player changes — the same start/store/cancel discipline `CaptionOverlay.TimeObserver` uses. A leaked observer keeps a torn-down player alive and keeps firing.
- **`model.currentPlayer` can be nil.** It is populated by `PlayerHostView`'s hand-off on the first update pass, so the overlay renders before it exists. Every control that touches it must no-op on nil rather than force-unwrap.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`, then the single-line `xcodebuild -only-testing:` invocations for `testShortsScreen` and `testShortsKebab` on iPhone 17 and iPad Pro 13-inch (M5).

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/ShortsScreen.swift \
        ios/FitrahTube/Features/Player/ShortsOverlay.swift \
        ios/FitrahTube/Features/Player/CaptionsMenu.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTube/Features/Player/PlayerStateView.swift \
        ios/FitrahTube/Features/Player/EmbedRungView.swift \
        ios/FitrahTubeTests/ShortsScreenTests.swift \
        ios/FitrahTubeUITests/ScreenshotTests.swift
git commit -m "[FEAT]: iOS shorts overlay rail and kebab"
```

---

### Task 4: Acceptance pass — simulator matrix, then live YouTube, then the device checklist

**Files:** `ios/scripts/screenshots.sh` (append the permanent Shorts block); otherwise touch only what a finding requires. Screenshots under `.superpowers/sdd/2026-08-27-ios-phase2b4-shorts/screenshots/b4-task4/`.

- [ ] **Step 1: Add the permanent screenshot block, then run the simulator matrix (do this yourself)**

Append a block to `screenshots.sh` in the same shape as the B1/B2/B3 blocks (its own `OUT` variable, its own single `-only-testing:` line per case, `simctl shutdown` after). **Remember the device argument does not scope it** — that is why each block names its own destination.

Then check, on iPhone 17 and iPad Pro 13-inch (M5), portrait, en and ar:

  - The stage: 9:16, black letterbox on iPad (centred column, not full-bleed), fill-and-crop on a non-9:16 fixture, **no AVKit transport anywhere**.
  - The loop: leave the 2 s fixture running for 30 s and confirm it restarts each time without a visible stall or a re-buffer.
  - Tap once → pause glyph appears and stays; tap again → play glyph flashes and fades after ~600 ms. With **Reduce Motion** on, both are instant.
  - The scrub bar: drag → the thumb follows and playback does **not** jump; release → it seeks once. Drag past the end → clamped.
  - The rail: Like toggles and its banner reads correctly; Share opens the sheet with the `api/watch/{id}` URL; the audio-language and CC buttons are **absent** on the single-track fixture — and (ruling 52) **toggle Like ten times and confirm they stay absent for the right reason and do not flicker**.
  - The kebab: Quality lists five options with the current one checked and a pick visibly re-applies; Report shows the coming-soon banner.
  - The channel row: present with a fixture that has a channel name, absent with one that does not.
  - Chrome: status bar hidden, **tab bar still visible on iPhone** (ruling 57), Back works, **swipe-from-edge back works** — if it does not, fix it here (Task 3's note).
  - Rotation (iPhone only): the device rotates, the screen does not. Navigate away and confirm the rest of the app rotates again. On iPad the screen rotates with the device (no `UIRequiresFullScreen`; the mask is ignored under multitasking) — that is expected, not a failure.
  - RTL (ar): the rail sits on the **trailing** edge, so in ar it **mirrors to the left** (Android `alignParentEnd`), the channel row to the right; the `@handle` renders with its LRM mark and does not reorder; the scrub bar fills from the correct side.
  - Dynamic Type `.accessibility3`: the title truncates at 2 lines without pushing the rail off screen; the rail glyphs do not grow.
  - VoiceOver: the stage, indicator, scrubber (as an adjustable), each rail button with label **and** value, the kebab, the Back button, the title and handle are all reachable and correctly ordered; a rung-2 or embed transition is announced.
  - Every tap target measured ≥44×44 pt (Accessibility Inspector, not eyeballed).
  - `-fitrah-fake-player-embed`: the embed frame is 9:16, the caption sits above it, the navigation lock is intact, and the frame is comfortably over 200×200 pt on the narrowest capture.
  - `-fitrah-fake-player-error` / `-unavailable` / `-cooldown`: `PlayerStateView` renders on the black Shorts background with Retry where Retry belongs and nowhere else.

- [ ] **Step 2: Live-YouTube checks (do these yourself, on the simulator, with network)**

Pick real Shorts ids from an approved channel. Record each result in the commit message; do not "fix" a rung that is behaving correctly.

  1. A real 9:16 Short resolves and plays, loops, and the scrub bar tracks a real duration.
  2. A 16:9 video opened through `albunyaantube://shorts/{id}` is cropped to fill rather than pillarboxed — the `resizeAspectFill` half of the layout.
  3. A Short whose stream carries **two or more audio languages**: the globe appears, "Original: X" labels the default (`shorts_audio_track_original_prefix`), and a pick survives a re-resolve (`stickyAudioLanguage`).
  4. A Short with auto-generated captions: the CC button appears and cues render over the video without colliding with the bottom overlay (Android reserves 200 dp of clearance — `shorts_caption_bottom_clearance`; check ours does not sit under the title block, and adjust the overlay's bottom padding if it does).
  5. Airplane-mode mid-playback and restore: the stall watchdog fires at ~6 s, one re-resolve happens, playback resumes near position — and if it cannot, `.recoveryExhausted` with a working Retry (reconciliation note 2 — there is no skip).
  6. Quality: pick 480p on a real HLS Short and confirm the rendition actually drops (Charles/`nettop`, or simply that the picture visibly softens); confirm AUTO's cap is the 9:16 layer size, not the screen's.

- [ ] **Step 3: Fix anything steps 1–2 surface**, re-run `ios/scripts/test.sh`, commit `[FIX]: iOS B4 shorts accessibility and layout pass`.

- [ ] **Step 4: Record the device checklist — USER-BLOCKED, do not attempt**

The repo has no signing identity (`DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset; `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: NO` is the only reason simulator builds work). **B4 inherits B2's undone 27-item device checklist as well** — nothing below supersedes it. Report these to the controller as blocked, verbatim:

  1. **Portrait lock on real hardware** — with the device's own rotation lock OFF, the Shorts screen stays portrait and every other screen rotates normally on the way out. `requestGeometryUpdate` behaves differently on device than in the simulator.
  2. **Ringer switch / silent mode** — a Short's audio is not silenced by the hardware switch (this is what the `.playback` category buys, ruling 44).
  3. **Backgrounding** — lock the screen mid-Short: audio stops (fork B's default). With the **Background play setting ON**, it still stops — this is the deliberate divergence from the main player and the single most likely thing to be reported as a bug.
  4. **No PiP** — a home-swipe from a Short produces no floating window, with Background play both on and off.
  5. **Phone call interruption** — a call pauses the loop; ending it resumes only if it was playing.
  6. **Route change** — unplugging headphones pauses (ruling 44's `oldDeviceUnavailable`).
  7. **Display sleep** — a looping Short keeps the display awake (`preventsDisplaySleepDuringVideoPlayback`, the Android gap brief §13 records; confirm iOS gives it for free rather than assuming it).
  8. **Lock screen** — what Now Playing shows for a Short (it is not suppressed; if the looping clip produces a nonsense scrubber, that is a finding for the controller, not a fix to improvise).
  9. **Thermals / battery** — five minutes of a looping Short does not produce a thermal notice.
  10. **VoiceOver on device** — the overlay reading order is stable while playback loops (rotor navigation does not get reset by each restart).

---

## Out of scope for B4 (later sub-plans, or deliberate deferrals)

- **Any Shorts feed, pager or prefetch.** Rulings 50 + 51, reconciliation note 1. If skip-on-failure is later wanted, it arrives as a sibling id list in the route arguments from Plan C's channel Shorts grid — not as a repository.
- **The channel detail Shorts tab and its 9:16 skeleton grid** (ruling 11, spec §11's `ShortsCell`, 2/4/5 columns) — **Plan C**, along with CF-C1's `lockupViewModel` modelling. Plan C constructs `Route.shorts(PlayerArgs)` from a grid cell; that is the whole integration surface, and Task 1 is what makes it available.
- **The real Report flow** — CF-B1-9, Plan C. The Shorts kebab deliberately shows the same coming-soon banner the player's toolbar shows so there is one flow to replace. Plan C owns `contentSubType = "SHORT"` and `parentType = CHANNEL` (brief §9.3).
- **Downloads on Shorts** — rulings 28 and 56, Phase 3. No button, not even disabled.
- **Subscribe on the Shorts overlay** — intentionally absent on Android and here (`ShortsPagerAdapter.kt:44-46`: "that UX lives on the channel detail screen"). The `shorts_subscribe` / `shorts_subscribed` catalog keys stay orphaned.
- **Fullscreen, the gesture overlay and Up Next** — B5. A Short is already full-bleed and portrait-locked; ruling 42's fullscreen story is about the main player. B5 also owns CF-B2-1's app-scoped player holder, and if it lands, the Shorts screen should be checked against it rather than left on its own `@State` view model.
- **Dub-audio enumeration** — ruling 13, Phase 3. The rail's globe shows only languages the resolved asset already carries.
- **Sharing `https://www.youtube.com/shorts/{id}`** — Android's `ShortsItem.canonicalShareUrl` has no callers (brief §7.1, dead code); sharing goes through the same app watch URL every other share uses.
- **A Shorts-specific recovery budget** — reconciliation note 2. One machine.
- **Muting Shorts by default / autoplay-muted feeds.** Not in the spec, not on Android, and a mute affordance on a single deliberately-opened video is a feed idiom. Not built.

## Carry-forward for B5 / Plan C

- **CF-B4-1:** `ShortsScreen` holds its `PlayerViewModel` in `@State`, so a back-navigation releases the player exactly as `PlayerScreen` does (CF-B2-1). If B5 introduces the app-scoped player holder, Shorts must move onto it too, or the app will have one route that holds its player differently from every other.
- **CF-B4-2:** `OrientationLock.mask` is the single writable seam for supported orientations. B5's fullscreen (ruling 42: iPhone auto-landscape, iPad button-only) must write it rather than adding a second mechanism, and must restore it on exit the way `ShortsScreen.onDisappear` does.
- **CF-B4-3:** the Shorts screen deliberately ignores the **Background play** setting (fork B). This is the only place in the app where a Settings toggle is overridden by a screen, and it is undocumented in Settings itself. If users report it, the fix is copy in the setting's description, not a behaviour change — or a controller reversal of fork B.
- **CF-B4-4:** `EmbedRungView`'s `aspectRatio` is now a parameter with a 16:9 default. Any future caller must re-run the ≥200×200 pt arithmetic in its own geometry (Task 3) — the check is a test (`fittedSize`), not a comment, precisely so it travels.
- **CF-B4-5:** `shorts_error_unavailable` and `shorts_error_feed_empty` are live Android keys with no iOS caller (Global Constraints). If Android ever deletes them, `convert-strings.py` drops them silently and nothing breaks — but if a later plan builds the feed, note that ruling 54 asks for the network-vs-empty split, which those two keys do not provide as written.
- **CF-B4-6:** the periodic time observer driving the scrub bar is per-screen, like `CaptionOverlay`'s. If B5's holder makes the player outlive the view, that observer becomes CF-B2-14's problem shape (an observer whose owner is gone) — check both together, not separately.

---

## Forks for the controller (defaults are chosen; work proceeds unless overridden)

**A. No feed, no pager, single short.** Reconciliation note 1 in full. **Default: implement as written** — ruling 51 over spec §10's feed clause and over the plan-B decomposition's B4 line. Overriding this is the only fork that changes the plan's size rather than one of its details.

**B. Shorts pause on background regardless of the Background play setting.** Android parity (brief §9.5); it also makes CF-B2-8/CF-B3-3's single-audio-owner rule trivially true and CF-B2-15's video-in-background cost unreachable. **Default: pause always, no auto-PiP on this screen.** Override = one line (`allowsBackgroundPlayback` returns true for `.shorts`), and then a 60-second clip on repeat-one can loop audio in a pocket indefinitely, which is the reason for the default.

**C. Portrait lock costs this app its first `AppDelegate`.** Spec §10 and brief §9.5 both call for it; SwiftUI has no per-view orientation control, so the delegate callback is the only hook (~35 lines, one new file, one adaptor line). **Default: implement it** — and B5 needs the same seam for ruling 42's fullscreen. Override = delete the file and let a 9:16 short pillarbox in landscape (zero code) — in which case **re-run Task 3's 200×200 pt arithmetic**, because iPhone landscape is the one geometry that gets close.

**D. Hidden navigation bar with our own Back and kebab, rather than the system bar.** Ruling 57 hides the status bar; leaving a system navigation bar under a hidden status bar on a full-bleed black video screen looks wrong, and Android has explicit top-corner controls (brief §9.3). **Default: hide the bar, draw both controls (44 pt, on a scrim)** and verify the edge-swipe pop still works in Task 4. Override = keep the system bar transparent with `.toolbarColorScheme(.dark)` and put the kebab in `.toolbar` — cheaper, and loses the full-bleed look.
