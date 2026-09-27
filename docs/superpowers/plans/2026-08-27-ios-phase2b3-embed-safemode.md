# Embed Rung, Open-in-YouTube Rung and Safe Mode Implementation Plan (iOS Phase 2, Plan B3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Give the player a floor. Today rungs 3 and 4 of the fallback ladder resolve fine and then die in `PlayerViewModel.map` on a placeholder error string — so a video YouTube will not serve natively is simply broken in FitrahTube. B3 builds the two remaining rungs: rung 3 plays the video inside YouTube's own IFrame player in a navigation-locked `WKWebView`, rung 4 offers a confirmation sheet that hands off to the YouTube app. Safe Mode — shipped as a Settings switch in Phase 1 and reading nothing since — gets its first real effect: it removes rung 4 from the ladder, and leaves B5 the hook for playlist auto-advance.

**Architecture:** Two new `StreamState` cases (`.embed`, `.openInYouTube`), each with its own `PlayerScreen` branch, because each is a genuinely different playback surface — the embed has no `AVPlayer` at all. Everything decidable is a pure function tested without a browser: `EmbedPage.html` (the bundled page's one substitution point and its trust boundary), `EmbedNavigationPolicy.allows` (the lock), `EmbedMessage.parse` (the JS→native bridge wire format) and `EmbedErrorPolicy.decide` (IFrame error codes → what the user sees). `EmbedRungView` + its `WKWebView` coordinator are the untestable glue, structured exactly like `PlayerHostView` + its `Coordinator` (one lifecycle owner, weak message-handler proxy, teardown that removes what it added). Safe Mode is one boolean read live off `SettingsStore` at exactly two decision points — `PlayerViewModel.map` and `EmbedErrorPolicy.decide` — and is deliberately *not* plumbed into InnerTubeKit (see Task 2's reconciliation note).

**Tech Stack:** Swift 6, SwiftUI, WebKit, `@Observable`, Swift Testing; app target `ios/FitrahTube` + `ios/Packages/InnerTubeKit` (read-only in this plan — B3 changes no package source). XcodeGen. Gate: `ios/scripts/test.sh` (300 s wall). Acceptance screenshots via `ios/scripts/screenshots.sh`.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §9 (`ResolvedStream.embed` / `.openInYouTube`), §10 ("**Embed rung**" paragraph, "**Safe Mode**" paragraph, "States and per-rung UI exactly as plan §6.6"). Detail: `docs/architecture/ios-app-plan.md` §6.4 rows 3–4 (the ladder and every `WKWebView` flag), §6.6 (the rung-3 / embed-errors / rung-4 / Transitions rows), §6.10 (Safe Mode and kids safeguards), §6.14 (no over-the-air code) and §9 (Guideline 2.5.2 and DPLA §3.3.1(B) verbatim, YouTube API Services policy III.I.5/III.I.9, the age-rating "Unrestricted Web Access: No" answer this plan's navigation lock is what justifies). Behavioural source: `docs/superpowers/plans/2026-08-23-ios-phase2-research/remote-config-safemode.md` §2 (Safe Mode's Android record) and §7 item 3; `.../extraction.md` (no embed rung exists on Android to port). Predecessors: `.../2026-08-24-ios-phase2b1-player-core.md`, `.../2026-08-27-ios-phase2b2-background-audio.md`.

**Android parity: there is none, and that is a finding, not an omission.** A full grep of `android/app/src/main/java/com/albunyaan/tube/` (2026-08-27) returns: no IFrame player, no `assets/embed.html`, no `shouldOverrideUrlLoading`, no `youtube://`, no `openInYouTube`, and no IFrame error codes. `youtube-nocookie.com` **does** appear once — `ui/me/suggest/SuggestContentViewModel.kt:87-88`, an unrelated allow-list of hosts a user may paste into the suggest-content form — and nowhere near a player. Android's only two `WebView`s are headless extractor internals (`data/extractor/potoken/PoTokenWebView.kt`, `data/extractor/nsig/NsigWebView.kt`) — both `loadDataWithBaseURL("https://www.youtube.com", …)` on a bundled asset, both torn down to `about:blank`, neither navigable. Android's playlist auto-advance is unconditional (`ui/player/PlayerFragment.kt:1248-1253`, `STATE_ENDED` → `markCurrentComplete()`), with no Safe Mode check. And **Safe Mode itself was deleted from Android** in commit `2ffde712` ("[FIX]: Remove the fake Safe Mode switch, disable cloud backup", 2026-08-25) precisely because it gated nothing — the tombstone is `preferences/SettingsPreferences.kt:21-24`. Every behaviour in this plan is therefore iOS-new, authored against the spec, with no Android source to mirror and — important for Task 1 — **no Android string resources to port**.

**Rulings this plan implements** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/RULINGS.md`):

| # | Ruling | Where it lands |
|---|---|---|
| **58** | **Safe Mode gains its first real effect in Phase 2; Safe Mode ON disables player auto-advance. "Nothing else is gated."** | Tasks 1 and 2 — with a written amendment: spec §10 and plan §6.10 *also* remove the `openInYouTube` rung under Safe Mode, and both post-date nothing in the ruling's reasoning. See Task 2's reconciliation note. |
| 34 | The settings are REAL on iOS; "Safe Mode → ruling 58" | Task 1 (the setting is read for the first time) |
| 14 | Age-restricted / geo-blocked / private / removed are terminal states with distinct copy, no retries | Task 2 — Safe Mode's suppressed rung 4 lands on `.contentUnavailable`, the one terminal "not playable" surface |
| 43 | Platform-standard auto-PiP via AVKit | Task 4 — the embed has no `AVPlayer`; `allowsPictureInPictureMediaPlayback = false` keeps rung 3 free of a PiP affordance (spec §6.6 rung-3 row) |
| 19 | Device locale for `hl`, en-US fallback on parse anomaly | Task 3 — the embed's `hl` comes from `SettingsStore.resolvedLocale`, validated against {en, ar, nl} before substitution |
| 62 | The 7-tap DeveloperDialog gains resolver counters + cooldown state | **Not extended here** — embed error codes are logged, not surfaced (see "Out of scope") |
| 28 / 56 | Every download affordance hidden until Phase 3 | Nothing in this plan adds one; §11's "Embed/openInYouTube rungs → *This video can't be downloaded*" is Phase 3 |

**Carry-forwards this plan absorbs** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md`, "From Plan B2 (background audio) — 2026-08-27", items CF-B2-1 … CF-B2-9; that section landed while this plan was being written, and its numbering — not B2's own shorter tail — is what the table below uses).

| Item | What it demands | Task |
|---|---|---|
| **CF-B1-7** | Replace the `.error(messageKey: "player_error_generic")` placeholder in `PlayerViewModel.map(_:)` for `.embed` / `.openInYouTube` | 2 |
| CF-B2-3 | `silent:` holds the state on the way OUT. "B3's embed rung must pass `silent: true` for any refresh it performs *while something is playing*, and must NOT pass it for a user-visible load whose failure has to be shown." | 2 — **the embed rung performs no refresh at all**: `.embed` carries `expiresAt == nil`, so `shouldPreemptivelyReResolve` is false for it and no new resolve path exists to flag. The flag's other half — a *native* stream's silent refresh landing on `.embed` — is answered by **not** widening `StreamState.isPlayable`, so that result is dropped and the native stream keeps playing. Test pins it. |
| CF-B2-4 | `onPolicyAction` reads `model.state` live. "If B3's embed rung is a new `StreamState` case, it falls through that switch harmlessly; if B3 instead models the embed *outside* `StreamState`, this closure and `PlayerViewModel.audioOnlyAvailable(for:)` both need revisiting." | 2 and 4 — the embed **is** a new `StreamState` case, so the harmless-fall-through arm applies: `PlayerHostView.resolvedStream(for:)` returns nil for `.embed`, `applyPolicyAction` pauses and releases rather than swapping, and `audioOnlyAvailable(for:)` already answers false. Test pins both. |
| CF-B2-7 | `willEnterForeground()` **awaits** `onWillEnterForeground`; B3 must not chain slow work (an embed reload, an un-timed network round trip) onto it | 4 — the embed touches neither hook. It installs its **own** `didEnterBackground` observer for `pauseVideo()`, and the native host that owns those closures is not mounted on this rung. |
| CF-B2-8 | `detach()` deactivates the audio session unconditionally; once the embed also owns audio, exactly one owner may deactivate | 4 — the embed activates `.playback` on its own and hands it back on teardown. The native host is always dismantled *before* the embed mounts, but SwiftUI does not guarantee that ordering, so the embed re-asserts the session on `.ready` (well after any dismantle). |
| CF-B2-9 | `NowPlayingSnapshot.make` returns nil for `.embed`, so backgrounding the embed could leave a stale Now Playing entry — B3 must clear it when the embed takes over | 4 — verified, not built: leaving rung 1/2 for the embed dismantles `PlayerHostView`, whose teardown (and, per CF-B2-1, its `Coordinator.deinit` backstop) already calls `detach()` → `removeRemoteCommands()` → `nowPlayingInfo = nil`. One test pins that `make` stays nil. |
| B2 Task 5 `// B3:` note | "the embed rung has no `AVPlayer`… B3 owns whatever the embed rung's chrome becomes" (`PlayerHostView.swift:161-162`) | 4 — the note is replaced with the real answer |
| CF-B2-1 / CF-B2-2 / CF-B2-5 / CF-B2-6 | App-scoped player holder (→ B5); `.prefetch` lane rules (→ B5); Now Playing `MediaType .video` in audio-only (unowned follow-up); cipher-only `adaptiveFormats` test debt (routed to "B3 or any plan touching the parser") | **Not absorbed.** None is on an embed or Safe Mode path, and B3 changes no InnerTubeKit source at all — so CF-B2-6 stays open for the next plan that opens `PlayerResponseParser`. |

---

## Global Constraints

Implementers inherit nothing from earlier plans. All of the following are binding:

- **Swift 6, `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor`** on the app and unit-test targets (`ios/project.yml`). `WKWebView` / `WKWebViewConfiguration` / `WKScriptMessage` are not `Sendable`; everything in the app target is main-actor-confined by default. Do not add `nonisolated` to silence a warning. `WKScriptMessageHandler` / `WKNavigationDelegate` callbacks arrive on the main thread — decode any non-`Sendable` payload *inside* the callback and hop with `MainActor.assumeIsolated`, exactly as `BackgroundPlaybackController`'s notification observers do (`BackgroundPlaybackController.swift:76-90`); handing a `WKScriptMessage` to an isolated method is a Swift 6 error.
- **One implementer at a time.** `ios/DerivedData` is shared; two concurrent `xcodebuild` runs corrupt it. Never dispatch two B3 tasks in parallel.
- **Gate:** `ios/scripts/test.sh` from the repo root, 300 s wall-clock watchdog, 60 s per test. It runs `convert-strings.py --check` → `xcodegen generate` → `xcodebuild test` (iPhone 17 + iPad Pro 13-inch (M5)) → `swift test` (packages — InnerTubeKit's suite is ~90 tests and is the bulk of the budget) → a Release build. A task is not done until this is green. The one `WKWebView` integration test in Task 3 must carry an explicit timeout so a hung web content process cannot eat the wall clock.
- **AVKit chrome is not XCUITest-accessible** on this toolchain (Xcode 26.3 / iOS 26.2). **Neither is the IFrame player** — its controls live inside a `WKWebView`'s remote content and expose nothing to XCUITest. Every UI assertion in this plan anchors on FitrahTube's own `player.*` accessibility identifiers. B3 adds `player.embedCaption`, `player.embedReplay`, `player.embedBack`, `player.openInYouTube.button`.
- **No over-the-air code (Guideline 2.5.2, DPLA §3.3.1(B), plan §6.14).** Every line of JavaScript this app *authors* ships in the bundle and goes through review. `embed.html` is a bundled resource; nothing fetches it, nothing patches it, and remote config carries no URL to it. The one piece of remote JavaScript executed is YouTube's own IFrame API (`https://www.youtube.com/iframe_api`) inside the embed's `WKWebView` — explicitly the carve-out plan §6.14 names ("the only remote JavaScript the app executes is YouTube's own player inside the embed `WKWebView`"). Do not add any other remote `<script>`, and do not introduce a "config-driven player variant".
- **All user-visible strings go through `ios/scripts/convert-strings.py`.** B3 needs **nine new keys and one edited value**, all in that script's `EXTRA_KEYS` dict, because **Android has no source string for any of them** (see the parity paragraph above — Safe Mode's strings were deleted from `values/strings.xml` on 2026-08-25 and the embed strings never existed). Never hand-edit `Localizable.xcstrings`; regenerate it. `EXTRA_KEYS` raises on a collision with an Android key (`convert-strings.py:333-336`), so a key that later appears on Android will fail loudly rather than silently override.
- **Copy rules (spec §10, plan §6.6, §9 checklist).** Never the words "ad-free" anywhere in-app — the embed plays YouTube's ads. Never a kids-vs-lecture explanation: say *what* is playing, never *why* this video needed a different player. Never "parental gate" for Safe Mode (Guideline 2.3.8 wording).
- **`.ready` and `.rung2Progressive` share ONE `switch` branch in `PlayerScreen.stateView`** and this is load-bearing: two branches gave SwiftUI two view identities and dropped the live `AVPlayer` (and its `currentTime()`) on a mid-play demotion. B3 does **not** touch that branch's structure. `.embed` gets its own branch — legitimately, because it is a different surface with no `AVPlayer` to preserve — and `.openInYouTube` rides the existing `default:` → `PlayerStateView` mount.
- **A native→embed transition must never be silent** (plan §6.6 Transitions row, plan-B decomposition point 5). Two mechanisms, both required: the embed is a distinct `StreamState` with visibly different chrome (the caption above the frame), and the transition posts an `AccessibilityNotification.Announcement`. **embed→native never happens automatically at all** — there is no code path that promotes a running embed back to rung 1/2; only a user-initiated Retry re-walks the ladder.
- **Rung 4 is never an automatic hand-off** (spec §6.6 "Rung 4 | confirmation sheet, never an automatic hand-off"). Nothing in this plan may call `UIApplication.open` without a user tap on a confirmation.
- **No new `.md` files.** This plan is the only document B3 creates. `docs/superpowers/HANDOFF.md`, `docs/superpowers/plans/2026-08-23-ios-phase2a-innertubekit.md` and any `ios/` peer docs are untracked work owned by other agents — **never `git add` them**; stage only the exact files each task's commit step names.
- **The simulator cannot prove the embed.** A real IFrame load needs the network and YouTube's cooperation; error codes 100/101/150 need videos that are actually removed / embedding-disabled; `webViewWebContentProcessDidTerminate` needs a real jetsam. Each task states exactly what the simulator *can* assert; Task 5 collects the rest and marks the device-only items USER-BLOCKED (the repo has no signing identity — `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset).
- **Ship-dark decision (controller 2026-08-27):** `embed` is removed from the bundled `resolverOrder` (default ladder = `visionosHLS → androidItag18 → terminal`); `RemoteConfig.sanitize` still accepts `embed`, so a published config can enable it as disaster recovery. Rationale: live captures show YouTube's wordmark/chrome/related thumbnails inside the frame, and 0/19 catalog ids need the embed today.

---

### Task 1: Safe Mode's Settings row actually says "Safe Mode", and B3's strings exist

**Why this is first:** the Settings row this plan wires is **currently broken on screen**. `SettingsRow.safeMode.titleKey` is `"settings_safe_mode"` and `.descriptionKey` is `"settings_safe_mode_desc"` (`SettingsView.swift:53,65`), `SettingsSection.content.titleKey` is `"settings_content"` (`SettingsView.swift:19`) — and none of those three keys exists in `ios/FitrahTube/Resources/Localizable.xcstrings` (verified by grep, 2026-08-27). `String(localized:)` falls back to the key itself, so the Content section header and the Safe Mode row render the literal strings `settings_content` / `settings_safe_mode` / `settings_safe_mode_desc` in all three languages. The cause is upstream: those keys were ported from Android's `strings.xml`, and Android deleted them on 2026-08-25 (commit `2ffde712`), so the next converter run dropped them from the catalog. Wiring Safe Mode to real behaviour on top of a row that shows a raw identifier would ship the feature invisible.

**Files:**
- Modify: `ios/scripts/convert-strings.py` (`EXTRA_KEYS` — nine additions, one edit)
- Modify: `ios/FitrahTube/Resources/Localizable.xcstrings` (**regenerated by the script, never hand-edited**)
- Test: `ios/FitrahTubeTests/SettingsRowsTests.swift` (extend)

**Interfaces:**
- Consumes: nothing new.
- Produces: nine localized keys available to Tasks 2–4 (`player_embed_caption`, `player_embed_removed`, `player_embed_owner_only`, `player_open_in_youtube`, `player_open_in_youtube_confirm`, `player_embed_replay`, `settings_safe_mode`, `settings_safe_mode_desc`, `settings_content`) and one re-purposed key (`player_error_generic`).
- No Swift source changes are expected. `SettingsStore.safeMode` already exists (`SettingsStore.swift:19,40,53,78`), defaults to `true` (`defaults.object(forKey:) == nil ? true : …`, Android's `DEFAULT_SAFE_MODE = true` preserved), and the row is already laid out in the Content section (`SettingsView.swift:83`) and already writes the store (`:225-226`). **Verify this is still true before assuming it; if the row has since been removed, restore it in Android's position (Content section, after Downloads) rather than folding it into Playback.**

- [ ] **Step 1: Write the failing test**

The lazy fix here is not "add three keys" — it is one test that makes this whole class of bug impossible for *every* settings row, since the same Android-deletes-a-key mechanism can hit any of them. In `SettingsRowsTests.swift`:

```swift
@Test func everySettingsKeyResolvesToRealCopy() {
    // A missing key is not a crash and not a build error -- `String(localized:)` returns the key
    // itself, so the row renders "settings_safe_mode" to the user. That is exactly what shipped
    // when Android deleted the safe_mode strings (commit 2ffde712) and the converter dropped them
    // from the catalog. One assertion over the whole layout, so the next deletion fails here.
    for row in SettingsLayout.rows {
        let title = String(localized: String.LocalizationValue(row.row.titleKey))
        #expect(title != row.row.titleKey, "missing catalog entry for \(row.row.titleKey)")
        let section = String(localized: String.LocalizationValue(row.section.titleKey))
        #expect(section != row.section.titleKey, "missing catalog entry for \(row.section.titleKey)")
        if let descriptionKey = row.row.descriptionKey {
            let description = String(localized: String.LocalizationValue(descriptionKey))
            #expect(description != descriptionKey, "missing catalog entry for \(descriptionKey)")
        }
    }
}
```

- [ ] **Step 2: Run the test, watch it fail**

Run: `ios/scripts/test.sh`
Expected: FAILS three times — `settings_content`, `settings_safe_mode`, `settings_safe_mode_desc`.

- [ ] **Step 3: Implement**

Add to `EXTRA_KEYS` in `ios/scripts/convert-strings.py`, each with the comment convention the existing entries use (why the key is iOS-only):

```python
    # Safe Mode strings (B3 task 1). These WERE Android keys and were deleted from
    # values/strings.xml on 2026-08-25 (commit 2ffde712, "Remove the fake Safe Mode switch") --
    # Android's switch gated nothing, so it went. iOS keeps the setting because ruling 58 gives it
    # a real effect, so the strings have to be authored here or the row renders its own key.
    # The subtitle is NOT Android's old "Show only family-friendly content": iOS Safe Mode does no
    # content filtering (the catalog is admin-curated), it keeps playback inside the app and turns
    # autoplay off. Promising filtering again would re-ship the placebo that got it deleted.
    "settings_content": {"en": "Content", "ar": "المحتوى", "nl": "Inhoud"},
    "settings_safe_mode": {"en": "Safe Mode", "ar": "الوضع الآمن", "nl": "Veilige modus"},
    "settings_safe_mode_desc": {
        "en": "Keep playback inside the app and turn off autoplay",
        "ar": "أبقِ التشغيل داخل التطبيق وأوقف التشغيل التلقائي",
        "nl": "Houd afspelen in de app en schakel automatisch afspelen uit",
    },
    # Embed rung (B3 tasks 3-4). iOS-only: Android has no IFrame embed player at all (grep of
    # android/app/src/main: no embed.html, no youtube-nocookie, no IFrame error codes), so there is
    # no source string to port for any of these.
    "player_embed_caption": {
        "en": "Playing in YouTube's player",
        "ar": "يتم التشغيل في مشغّل يوتيوب",
        "nl": "Speelt af in de YouTube-speler",
    },
    "player_embed_removed": {
        "en": "This video was removed",
        "ar": "تمت إزالة هذا الفيديو",
        "nl": "Deze video is verwijderd",
    },
    "player_embed_owner_only": {
        "en": "The creator only allows this video on YouTube",
        "ar": "يسمح صاحب القناة بمشاهدة هذا الفيديو على يوتيوب فقط",
        "nl": "De maker staat deze video alleen op YouTube toe",
    },
    "player_embed_replay": {"en": "Replay", "ar": "إعادة التشغيل", "nl": "Opnieuw afspelen"},
    # Rung 4 (B3 task 2). Confirmation sheet, never an automatic hand-off (spec §6.6).
    "player_open_in_youtube": {
        "en": "Open in YouTube",
        "ar": "فتح في يوتيوب",
        "nl": "Openen in YouTube",
    },
    "player_open_in_youtube_confirm": {
        "en": "Open this video in YouTube?",
        "ar": "فتح هذا الفيديو في يوتيوب؟",
        "nl": "Deze video in YouTube openen?",
    },
```

Then **edit** the existing `player_error_generic` entry: drop the word "yet" from all three locales and replace its comment. B1 authored it as a placeholder ("This video can't be played in the app **yet**") for exactly the codepath this plan replaces; rung 4 re-uses it as its reason line, where "yet" would promise a fix that is not coming.

```python
    # player_error_generic (B1 placeholder, re-purposed in B3 task 2): the reason line above the
    # rung-4 "Open in YouTube" card, and the terminal copy when Safe Mode has removed that rung.
    # No "yet" -- there is no later rung.
    "player_error_generic": {
        "en": "This video can't be played in the app",
        "ar": "لا يمكن تشغيل هذا الفيديو داخل التطبيق",
        "nl": "Deze video kan niet in de app worden afgespeeld",
    },
```

Regenerate: `python3 ios/scripts/convert-strings.py` from the repo root (no arguments — `--check` is the gate's verify-only mode), then confirm `ios/scripts/convert-strings.py --check` exits 0.

Do **not** add anything to `EXTRA_KEYS` for "Back", "Retry", "Cancel" or "OK" — `back`, `retry`, `cancel` and `ok` already exist in the catalog and are what Tasks 2 and 4 use.

- [ ] **Step 4: Run the test, watch it pass**

Run: `ios/scripts/test.sh` — expected green.
Manual simulator check: open Settings on the iPhone 17 simulator in English and again with the device language set to Arabic; the Content section header and the Safe Mode row read as real copy, RTL-mirrored in Arabic, and the switch still toggles and persists across a relaunch.

- [ ] **Step 5: Commit**

```bash
git add ios/scripts/convert-strings.py \
        ios/FitrahTube/Resources/Localizable.xcstrings \
        ios/FitrahTubeTests/SettingsRowsTests.swift
git commit -m "[FIX]: iOS Safe Mode settings strings"
```

---

### Task 2: `StreamState.embed` / `.openInYouTube`, the Safe Mode ladder filter, and the rung-4 confirmation sheet

**Superseded 2026-08-27 (owner directive, RULINGS Q75):** rung 4 / `StreamState.openInYouTube` / the confirmation sheet were removed in 2177fa53; terminal outcomes are `StreamState.unplayable(messageKey:)`. Code blocks below are historical — do not implement them.

**Why this is second:** it kills the CF-B1-7 placeholder and delivers a complete, shippable rung 4 with nothing stubbed. Rung 3's UI lands in Task 4; between this task and that one, `.embed` renders the same terminal card rung 4 shows minus the button — honest, never an automatic hand-off, and one line to replace.

**Files:**
- Modify: `ios/FitrahTube/Features/Player/PlayerState.swift` (two cases + their `Equatable` arms)
- Modify: `ios/FitrahTube/Features/Player/PlayerViewModel.swift` (`map(_:safeMode:)`, `safeMode`, `applyEmbedAction`)
- Modify: `ios/FitrahTube/Features/Player/PlayerStateView.swift` (`PlayerStateCopy.map` arms + an optional secondary action on `PlayerStateView`)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (the rung-4 confirmation sheet + `#if DEBUG` fixture resolvers)
- Test: `ios/FitrahTubeTests/PlayerViewModelTests.swift`, `ios/FitrahTubeTests/PlayerStateViewTests.swift` (both exist — add cases, do not create new files)

**Interfaces:**
- Consumes: Task 1's `player_open_in_youtube`, `player_open_in_youtube_confirm`, `player_error_generic`.
- Produces:
  - `StreamState.embed(Resolved)` and `StreamState.openInYouTube(Resolved, messageKey: String)`.
  - `PlayerViewModel.map(_ resolved: Resolved, safeMode: Bool) -> StreamState` (was `map(_:)`).
  - `PlayerViewModel.safeMode: Bool` — a live read of `settings.safeMode`, the same shape as the existing `backgroundPlay` (`PlayerViewModel.swift:150-151`). **This is B5's auto-advance hook** (ruling 58): B5's Up Next reads this property and nothing else.
  - `PlayerViewModel.applyEmbedAction(_:resolved:)` — Task 4 calls it; declared here so Task 4 adds no VM surface.
  - `PlayerStateView` gains an optional `secondaryAction: (title: String, handler: () -> Void)?`.

**Reconciliation — read this before implementing (three points).**

1. **Ruling 58 vs. spec §10 / plan §6.10.** Ruling 58 says Safe Mode ON disables player auto-advance and "Nothing else is gated". Spec §10 ("**Safe Mode** (default on): embed navigation lock (always), `openInYouTube` rung removed, playlist auto-advance off") and plan §6.10 both say the rung is removed as well. The ruling's own justification for "nothing else" is that the *catalog* needs no filtering because it is admin-curated — which is an argument about content filtering, not about a hand-off that takes a child out of the curated app and into YouTube proper. It is also the sentence that plan §9 leans on for the age-rating answer "Unrestricted Web Access: No". **This plan implements the spec: Safe Mode removes rung 4.** Ruling 58 stands otherwise (auto-advance, and no content filtering).
2. **The filter is applied at the outcome, not to `resolverOrder`.** Spec §10 phrases it as "the `openInYouTube` rung removed"; the obvious reading is to filter `RemoteConfig.resolverOrder` before handing it to `StreamResolver`. Do **not** do that. Two reasons, both structural: (a) `StreamResolver.performResolve` has a second, order-independent route to rung 4 — the age-gate branch returns `.jumpToOpenInYouTube`, which `succeed()`s straight out of the loop without consulting `resolverOrder` at all (`StreamResolver.swift:158-160`), so an order filter would leave exactly the Safe Mode hole a parent would care most about; (b) `StreamResolver` is a nonisolated InnerTubeKit actor with no dependency on the app's `SettingsStore`, and giving it one to satisfy a UI policy inverts the package boundary. Filtering the *outcome* in `PlayerViewModel.map` covers both routes with one guard, and costs nothing — the `openInYouTube` rung is a local URL construction with no network call (`StreamResolver.swift:299-302`).
3. **`isPlayable` is deliberately left alone (CF-B2-3).** `performResolve`'s guard is `if silent, !result.isPlayable { return }`. Widening `isPlayable` to include `.embed` would let a *silent* proactive TTL refresh — one that never passes through `.loading` and shows the user nothing — swap a happily playing native stream into the embed. That is precisely what plan §6.6 and the plan-B decomposition forbid. Leaving `.embed` out means the silent result is dropped and the native stream keeps playing until it genuinely fails, at which point reactive recovery (which never passes `silent:`) performs the demotion loudly. **No new refresh path is added for the embed rung**: `.embed` carries `expiresAt == nil` (`StreamResolver.swift:295`), so `shouldPreemptivelyReResolve` returns false for it and there is nothing to refresh.

- [ ] **Step 1: Write the failing tests**

In `PlayerViewModelTests.swift`. **Two existing tests assert the very placeholder this task deletes — `embedMapsToGenericErrorPendingB3` and `openInYouTubeMapsToGenericErrorPendingB3` (`:74-84`). Replace them; do not leave them standing next to the new ones.** Everything below uses the doubles the suite already has: the private `FakeResolver` actor (`:31-58`, `init(outcomes: [Result<Resolved, Error>], gate: Gate? = nil, gatedCallIndex: Int = 0)`), the pre-declared `Self.embed` / `Self.openInYouTube` fixtures (`:15-18`), and `makeSettings()` (`:22-24`), which hands back a real `UserDefaultsSettingsStore` on a per-test `UserDefaults` suite — so Safe Mode is set on the real store, not on a fake.

First widen the existing view-model helper by one defaulted parameter, so no existing call site changes:

```swift
    private func makeViewModel(resolver: FakeResolver, args: PlayerArgs? = nil,
                               safeMode: Bool = true) -> PlayerViewModel {
        let settings = makeSettings()
        settings.safeMode = safeMode     // the store's own default is already true (Android parity)
        return PlayerViewModel(resolver: resolver, settings: settings, args: args ?? makeArgs())
    }
```

and add one fixture next to the others — `Self.hls` carries `expiresAt: nil` (`:10-15`), which `shouldPreemptivelyReResolve` reads as "never expires", so the silent-refresh test needs a stream that does:

```swift
    private static func expiringHLS(now: Date = Date()) -> Resolved {
        Resolved(stream: .hls(url: URL(string: "https://example.com/a.m3u8")!, isLive: false,
                              audioOnlyURL: nil, captionTracks: []),
                 client: .visionos, userAgent: "ua", resolvedAt: now, expiresAt: now.addingTimeInterval(30))
    }
```

```swift
@Test func embedMapsToTheEmbedState() async {
    let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.embed)]))
    await vm.open()
    #expect(vm.state == .embed(Self.embed))
}

@Test func openInYouTubeIsOfferedWhenSafeModeIsOff() async {
    let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.openInYouTube)]), safeMode: false)
    await vm.open()
    #expect(vm.state == .openInYouTube(Self.openInYouTube, messageKey: "player_error_generic"))
}

@Test func safeModeRemovesTheOpenInYouTubeRung() async {
    // Spec §10 / plan §6.10: rung 4 is hidden ENTIRELY in Safe Mode. Ruling 14's single terminal
    // "not playable" surface is where it lands -- not an error with a Retry that can never succeed.
    let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.openInYouTube)]), safeMode: true)
    await vm.open()
    #expect(vm.state == .contentUnavailable)
}

@Test func safeModeDoesNotSuppressTheEmbedRung() async {
    // The rung that keeps a child inside the app is the one Safe Mode must KEEP. Only rung 4 goes.
    let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.embed)]), safeMode: true)
    await vm.open()
    #expect(vm.state == .embed(Self.embed))
}

@Test func aSilentRefreshNeverSwapsAPlayingStreamIntoTheEmbed() async {
    // CF-B2-3 + plan §6.6: `reResolveIfExpiring` shows the user nothing, so it must not be able to
    // change the playback SURFACE. The near-expiry rung-1 stream keeps playing; reactive recovery
    // (which never passes `silent:`) owns the demotion, loudly, once the stream actually fails.
    let expiring = Self.expiringHLS()
    let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(expiring), .success(Self.embed)]),
                           safeMode: false)
    await vm.open()
    #expect(vm.state == .ready(expiring))
    await vm.reResolveIfExpiring(now: .distantFuture)   // well past `expiresAt - margin`, so it fires
    #expect(vm.state == .ready(expiring))               // embed result dropped; rung 1 still playing
}

@Test func embedActionsMapOntoTerminalStates() {
    let vm = makeViewModel(resolver: FakeResolver(outcomes: []), safeMode: false)
    vm.applyEmbedAction(.fail(messageKey: "player_embed_removed"), resolved: Self.embed)
    #expect(vm.state == .error(messageKey: "player_embed_removed"))
    vm.applyEmbedAction(.offerYouTube(messageKey: "player_embed_owner_only"), resolved: Self.embed)
    #expect(vm.state == .openInYouTube(Self.embed, messageKey: "player_embed_owner_only"))
}
```

`EmbedErrorAction` is declared in Task 3. For this task declare it in `PlayerViewModel.swift` next to `applyEmbedAction` and **move it to `EmbedPolicy.swift` in Task 3** (that task's file list says so). Do **not** reach for `ios/FitrahTubeTests/Support/PlayerTestDoubles.swift`'s `RecordingResolver` here: it is a different double that scripts only `.hls` / `.progressive` / `.failure` and cannot produce an `.embed` or `.openInYouTube` outcome at all. It stays the right tool for B2's hold-until-released in-flight tests and the wrong one for these.

In `PlayerStateViewTests.swift`:

```swift
@Test func rung4CopyOffersYouTubeAndNoRetry() {
    let resolved = Resolved(stream: .openInYouTube(url: URL(string: "https://www.youtube.com/watch?v=x")!),
                             client: .web, userAgent: "", resolvedAt: Date(), expiresAt: nil)
    let copy = PlayerStateCopy.map(.openInYouTube(resolved, messageKey: "player_embed_owner_only"), isOnline: true)
    #expect(copy.message == String(localized: "player_embed_owner_only"))
    #expect(copy.showsRetry == false)     // retrying the ladder lands here again; the hand-off is the exit
    #expect(copy.announces)               // spec §6.6 Transitions
}
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `StreamState.embed` / `.openInYouTube` do not exist, `PlayerViewModel.applyEmbedAction` does not exist, `PlayerStateCopy.map` has no arm for the new cases.

- [ ] **Step 3: Implement**

`PlayerState.swift` — two cases and their `Equatable` arms. `Resolved.comparisonKey` already covers `.embed` / `.openInYouTube` streams (`PlayerState.swift:44-45`), so nothing else changes:

```swift
    /// Rung 3 (plan §6.4 row 3): YouTube's own IFrame player in a navigation-locked `WKWebView`.
    /// A DIFFERENT surface, not a degraded `AVPlayer` -- which is why it is its own state and its
    /// own `PlayerScreen` branch, and why nothing promotes it back to rung 1/2 automatically.
    case embed(Resolved)
    /// Rung 4 (plan §6.4 row 4): terminal. The card carries a reason line and an "Open in YouTube"
    /// button; the hand-off itself is behind a confirmation (spec §6.6: "never an automatic
    /// hand-off"). `messageKey` is why we got here -- the ladder bottomed out
    /// (`player_error_generic`) or the embed reported 101/150 (`player_embed_owner_only`).
    case openInYouTube(Resolved, messageKey: String)
```

```swift
        case (.embed(let l), .embed(let r)): return l.comparisonKey == r.comparisonKey
        case (.openInYouTube(let l, let lk), .openInYouTube(let r, let rk)):
            return l.comparisonKey == r.comparisonKey && lk == rk
```

`PlayerViewModel.swift`:

```swift
    /// The Settings "Safe Mode" value, read live so a change made while the player is open takes
    /// effect on the next resolve (same shape as `backgroundPlay`). Ruling 58 + spec §10: this is
    /// also B5's auto-advance hook -- Up Next reads THIS, not `SettingsStore` directly.
    var safeMode: Bool { settings.safeMode }
```

```swift
    private static func map(_ resolved: Resolved, safeMode: Bool) -> StreamState {
        switch resolved.stream {
        case .hls:
            return .ready(resolved)
        case .progressive:
            return .rung2Progressive(resolved)
        case .embed:
            return .embed(resolved)
        case .openInYouTube:
            // Spec §10 / plan §6.10: Safe Mode removes rung 4 entirely -- and filtering the OUTCOME
            // rather than `RemoteConfig.resolverOrder` is what also closes the age-gate route
            // (`StreamResolver`'s `.jumpToOpenInYouTube` bypasses the order). Ruling 14: the
            // suppressed rung lands on the one terminal "not playable" surface.
            return safeMode ? .contentUnavailable
                            : .openInYouTube(resolved, messageKey: "player_error_generic")
        }
    }
```

Update the one call site in `performResolve` to `Self.map(resolved, safeMode: settings.safeMode)`.

```swift
    /// Task 4's bridge from an IFrame error to a `StreamState`. Split from `EmbedErrorPolicy`
    /// (which is pure and knows nothing about `Resolved`) so the policy stays a truth table.
    func applyEmbedAction(_ action: EmbedErrorAction, resolved: Resolved) {
        switch action {
        case .reloadOnce:
            break                       // the view reloads its own web view; the state does not move
        case .fail(let messageKey):
            state = .error(messageKey: messageKey)
        case .offerYouTube(let messageKey):
            state = .openInYouTube(resolved, messageKey: messageKey)
        }
    }
```

`PlayerStateView.swift` — the copy arms and the secondary action:

```swift
        case .embed:
            // Replaced in B3 task 4 by `PlayerScreen`'s own embed branch; until then an embed
            // resolve is honestly terminal rather than silently handed off (spec §6.6 rung 4).
            return Copy(message: String(localized: "player_error_generic"), showsRetry: true, announces: true)
        case .openInYouTube(_, let messageKey):
            // No Retry: the ladder that produced this state will produce it again. The hand-off
            // button (`PlayerScreen`'s secondary action) is the exit.
            return Copy(message: String(localized: String.LocalizationValue(messageKey)),
                       showsRetry: false, announces: true)
```

Give `PlayerStateView` an optional `secondaryAction: (title: String, handler: () -> Void)? = nil` and pass it into the existing `EmptyStateView`/`StateButton` composition with identifier `player.openInYouTube.button`. Follow whatever `StateViews.swift` already offers — if `EmptyStateView` takes only one action, render the secondary button beneath it rather than adding a parameter to the shared component (one screen needs it; `StateViews.swift` is used by every list in the app).

`PlayerScreen.swift` — the `default:` branch supplies the secondary action and owns the confirmation:

```swift
        default:
            PlayerStateView(state: state, isOnline: container.network.isOnline,
                            thumbnailURL: args.thumbnailURL,
                            secondaryAction: Self.isRung4(state)
                                ? (String(localized: "player_open_in_youtube"), { confirmOpenInYouTube = true })
                                : nil) {
                Task { await model.retry() }
            }
            .confirmationDialog(String(localized: "player_open_in_youtube_confirm"),
                                isPresented: $confirmOpenInYouTube, titleVisibility: .visible) {
                Button(String(localized: "player_open_in_youtube")) { Self.openInYouTube(videoId: args.videoId) }
                Button(String(localized: "cancel"), role: .cancel) {}
            }
```

```swift
    /// Rung 4 (plan §6.4 row 4). `youtube://` first so the YouTube app takes it; the completion
    /// handler -- not `canOpenURL` -- carries the `https://youtu.be/` fallback, which is why no
    /// `LSApplicationQueriesSchemes` entry is needed in Info.plist (that key gates `canOpenURL`
    /// only). The fallback is also what covers an iPad-on-Mac install, where `youtube://` has no
    /// handler at all (plan §9).
    private static func openInYouTube(videoId: String) {
        guard let app = URL(string: "youtube://watch?v=\(videoId)"),
              let web = URL(string: "https://youtu.be/\(videoId)") else { return }
        UIApplication.shared.open(app) { opened in
            if !opened { UIApplication.shared.open(web) }
        }
    }
```

Add `@State private var confirmOpenInYouTube = false` to `PlayerScreen`, and a `FixtureEmbedResolver` / `FixtureOpenInYouTubeResolver` pair to the existing `#if DEBUG` block behind `-fitrah-fake-player-embed` and `-fitrah-fake-player-open-in-youtube`, in the same style as `FixtureCooldownResolver`.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh` — expected green.
Manual simulator check: launch with `-fitrah-fake-player-open-in-youtube` and tap "Open in YouTube" → the confirmation appears; confirm → the simulator has no YouTube app, so the completion fallback opens `https://youtu.be/…` in Safari. That path *is* testable here; the `youtube://` half is not. Relaunch with Safe Mode ON and the same argument → the card reads `player_stream_unavailable` with no button at all.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/PlayerState.swift \
        ios/FitrahTube/Features/Player/PlayerViewModel.swift \
        ios/FitrahTube/Features/Player/PlayerStateView.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTubeTests/PlayerViewModelTests.swift \
        ios/FitrahTubeTests/PlayerStateViewTests.swift
git commit -m "[FEAT]: iOS open in YouTube rung and Safe Mode"
```

---

### Task 3: The bundled `embed.html` and the four pure embed policies

**Superseded 2026-08-27 (owner directive, RULINGS Q75):** rung 4 / `StreamState.openInYouTube` / the confirmation sheet were removed in 2177fa53; terminal outcomes are `StreamState.unplayable(messageKey:)`. Code blocks below are historical — do not implement them.

**Why this is third:** every decision the embed rung makes — what HTML gets loaded, which navigations are allowed, what a bridge message means, what an error code does — is a pure function of its inputs. Getting all four right and tested *before* any `WKWebView` exists means Task 4 is glue with nothing to reason about. It also puts the injection trust boundary (`videoId` into a `<script>`) under test before it has a caller.

**Files:**
- Create: `ios/FitrahTube/Resources/embed.html`
- Create: `ios/FitrahTube/Features/Player/EmbedPolicy.swift` (all four types — one small file, not four)
- Test: `ios/FitrahTubeTests/EmbedPolicyTests.swift` (new)
- Modify: `ios/FitrahTube/Features/Player/PlayerViewModel.swift` (move `EmbedErrorAction` out, into `EmbedPolicy.swift`)

XcodeGen needs no change: the app target's `sources` is `path: FitrahTube` with only `Info.plist` excluded (`ios/project.yml:43-46`), and an unknown extension is inferred as a resource — the same route `player-fixture.mp4` already takes. Verify after `xcodegen generate` that `embed.html` appears in the Copy Bundle Resources phase; if it does not, add an explicit `type: file` entry rather than moving the file.

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `EmbedPage.html(videoId:locale:captionsPreferred:) -> String?` and `EmbedPage.baseURL`.
  - `EmbedNavigationPolicy.allows(url:isMainFrame:baseURL:) -> Bool`.
  - `EmbedMessage.parse(_ body: Any) -> EmbedMessage.Event?` with `Event = .ready | .state(Int) | .error(Int)`.
  - `EmbedErrorPolicy.decide(code:alreadyReloaded:safeMode:) -> EmbedErrorAction` and `.decideProcessTermination(alreadyReloaded:safeMode:) -> EmbedErrorAction`.
  - `EmbedErrorAction = .reloadOnce | .fail(messageKey: String) | .offerYouTube(messageKey: String)` (moved from Task 2's temporary home).

**Reconciliation — read this before implementing (two points).**

1. **User-Agent.** The controller's brief asks for "a User-Agent consistent with §6.3". Plan §6.3 governs InnerTube **API** calls — one fixed UA per client family, byte-identical per call, so YouTube's bot heuristics see a coherent session. The embed is not an API call: it is a browser rendering YouTube's own web player, and `youtube-nocookie.com` serves a *different player* to a UA it does not recognise as a mobile browser. **Do not set `customUserAgent`.** WKWebView's stock iOS Safari UA is the consistent choice here, and it carries no app-specific or device-specific token — which is also what "no device identifiers" requires. Record this in a comment in `EmbedPage`.
2. **Picture in Picture on rung 3.** Plan §6.4 row 3 lists `allowsPictureInPictureMediaPlayback` among the embed's `WKWebView` flags; spec §6.6's rung-3 row says "all FitrahTube controls hidden (quality, audio-only, **PiP**, background)". They conflict, and §6.4 also conflicts with *itself* — the same row says the embed is "paused on `didEnterBackground`", which a live PiP window would defeat. Add YouTube API Services policy III.I.9 ("no features that play content from a background player", quoted in plan §9), which binds the embed because the embed is the API-client path. **Set `allowsPictureInPictureMediaPlayback = false`.** Task 4 implements it; this note is where the decision lives.

- [ ] **Step 1: Write the failing tests**

In a new `ios/FitrahTubeTests/EmbedPolicyTests.swift`:

```swift
@Test func htmlSubstitutesOnlyAValidVideoIdAndLocale() {
    #expect(EmbedPage.html(videoId: "xc7keR2piUM", locale: "ar", captionsPreferred: false)?
        .contains("xc7keR2piUM") == true)
    // The one injection point in this app that puts a value inside a <script>. A validated 11-char
    // id is the whole defence -- escaping is not attempted, refusal is.
    #expect(EmbedPage.html(videoId: "\";alert(1);//", locale: "en", captionsPreferred: false) == nil)
    #expect(EmbedPage.html(videoId: "short", locale: "en", captionsPreferred: false) == nil)
    #expect(EmbedPage.html(videoId: "xc7keR2piUM", locale: "en-US\";x", captionsPreferred: false) == nil)
}

@Test func htmlCarriesThePlayerVarsPlan64RequiresAndNothingElse() {
    let html = EmbedPage.html(videoId: "xc7keR2piUM", locale: "nl", captionsPreferred: true)!
    #expect(html.contains("playsinline"))
    #expect(html.contains("enablejsapi"))
    #expect(html.contains("rel"))
    #expect(html.contains("youtube-nocookie.com"))
    #expect(html.contains("hl") && html.contains("nl"))
    #expect(html.contains("cc_load_policy"))          // captionsPreferred: true
    #expect(EmbedPage.html(videoId: "xc7keR2piUM", locale: "nl", captionsPreferred: false)!
        .contains("cc_load_policy") == false)
    // 2.5.2 / plan §6.14: the ONLY remote script is YouTube's own IFrame API.
    let scripts = html.components(separatedBy: "src=\"").dropFirst().map { $0.prefix(while: { $0 != "\"" }) }
    #expect(scripts == ["https://www.youtube.com/iframe_api"])
}

@Test func navigationLockCancelsEveryMainFrameNavigationOffTheBundledPage() {
    let base = EmbedPage.baseURL
    // Allowed: the bundled page itself, and every SUBFRAME navigation -- the IFrame is a subframe
    // and YouTube navigates it constantly; cancelling those breaks the player.
    #expect(EmbedNavigationPolicy.allows(url: base, isMainFrame: true, baseURL: base))
    #expect(EmbedNavigationPolicy.allows(url: URL(string: "about:blank"), isMainFrame: true, baseURL: base))
    #expect(EmbedNavigationPolicy.allows(url: URL(string: "https://www.youtube-nocookie.com/embed/x")!,
                                          isMainFrame: false, baseURL: base))
    // Cancelled (plan §6.10): the title, the logo, "Watch on YouTube", share, end-screen cards.
    #expect(EmbedNavigationPolicy.allows(url: URL(string: "https://www.youtube.com/watch?v=x")!,
                                          isMainFrame: true, baseURL: base) == false)
    #expect(EmbedNavigationPolicy.allows(url: URL(string: "https://accounts.google.com/signin")!,
                                          isMainFrame: true, baseURL: base) == false)
    #expect(EmbedNavigationPolicy.allows(url: URL(string: "javascript:alert(1)")!,
                                          isMainFrame: true, baseURL: base) == false)
    #expect(EmbedNavigationPolicy.allows(url: nil, isMainFrame: true, baseURL: base) == false)
}

@Test func bridgeMessagesParse() {
    #expect(EmbedMessage.parse(["event": "ready"]) == .ready)
    #expect(EmbedMessage.parse(["event": "state", "state": 0]) == .state(0))
    #expect(EmbedMessage.parse(["event": "error", "code": 150]) == .error(150))
    #expect(EmbedMessage.parse(["event": "state"]) == nil)          // malformed
    #expect(EmbedMessage.parse("state") == nil)                     // not a dictionary
    #expect(EmbedMessage.parse(["event": "navigate", "url": "x"]) == nil)  // unknown event, dropped
}

@Test func errorCodesMapExactlyAsPlan66Says() {
    // 100 -> removed. Terminal, distinct copy.
    #expect(EmbedErrorPolicy.decide(code: 100, alreadyReloaded: false, safeMode: false)
        == .fail(messageKey: "player_embed_removed"))
    // 101/150 -> creator only allows it on YouTube, + Open in YouTube UNLESS Safe Mode.
    #expect(EmbedErrorPolicy.decide(code: 101, alreadyReloaded: false, safeMode: false)
        == .offerYouTube(messageKey: "player_embed_owner_only"))
    #expect(EmbedErrorPolicy.decide(code: 150, alreadyReloaded: false, safeMode: false)
        == .offerYouTube(messageKey: "player_embed_owner_only"))
    #expect(EmbedErrorPolicy.decide(code: 150, alreadyReloaded: false, safeMode: true)
        == .fail(messageKey: "player_embed_owner_only"))
    // 2/5/153 -> retry once, then give up. 153 is a missing Referer, i.e. OUR bug -- one reload
    // covers a transient load failure and the second occurrence is worth surfacing, not looping.
    for code in [2, 5, 153, 999] {
        #expect(EmbedErrorPolicy.decide(code: code, alreadyReloaded: false, safeMode: false) == .reloadOnce)
        #expect(EmbedErrorPolicy.decide(code: code, alreadyReloaded: true, safeMode: false)
            == .fail(messageKey: "player_error_message"))
    }
    // Content-process termination: reload once, then rung 4 (plan §6.4 row 3), Safe Mode excepted.
    #expect(EmbedErrorPolicy.decideProcessTermination(alreadyReloaded: false, safeMode: false) == .reloadOnce)
    #expect(EmbedErrorPolicy.decideProcessTermination(alreadyReloaded: true, safeMode: false)
        == .offerYouTube(messageKey: "player_error_generic"))
    #expect(EmbedErrorPolicy.decideProcessTermination(alreadyReloaded: true, safeMode: true)
        == .fail(messageKey: "player_error_generic"))
}

@MainActor
@Test(.timeLimit(.minutes(1))) func theBridgeRoundTripsThroughARealWebView() async throws {
    // The one non-pure test in this plan. It uses a LOCAL html string, never the bundled page and
    // never the network -- what it proves is that the handler name, the message shape and
    // `EmbedMessage.parse` agree end to end, which is the seam a typo silently breaks.
    let config = WKWebViewConfiguration()
    let recorder = MessageRecorder()
    config.userContentController.add(recorder, name: EmbedBridge.handlerName)
    let web = WKWebView(frame: .zero, configuration: config)
    web.loadHTMLString("""
    <script>
    window.webkit.messageHandlers.\(EmbedBridge.handlerName).postMessage({event: "error", code: 150});
    </script>
    """, baseURL: EmbedPage.baseURL)
    let event = try await recorder.next()
    #expect(event == .error(150))
    config.userContentController.removeScriptMessageHandler(forName: EmbedBridge.handlerName)
}
```

`MessageRecorder` is a small `@MainActor final class … : NSObject, WKScriptMessageHandler` in the test file that funnels `EmbedMessage.parse(message.body)` into a continuation. `EmbedBridge.handlerName` is a `static let` on `EmbedPage` (or a tiny `enum EmbedBridge`) so the name exists in exactly one place — the JS in `embed.html`, the handler registration in Task 4, and this test all read it.

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — none of `EmbedPage`, `EmbedNavigationPolicy`, `EmbedMessage`, `EmbedErrorPolicy` exists.

- [ ] **Step 3: Implement**

`ios/FitrahTube/Resources/embed.html` — bundled, reviewed, never fetched. Keep it to one screenful:

```html
<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
#p{width:100%;height:100%;border:0}</style></head>
<body><div id="p"></div>
<script>
// The expected id, substituted by EmbedPage.html() from an id validated to ^[A-Za-z0-9_-]{11}$.
var EXPECTED = "__VIDEO_ID__";
var bridge = function (m) { window.webkit.messageHandlers.__HANDLER__.postMessage(m); };
var player;
function onYouTubeIframeAPIReady() {
  player = new YT.Player("p", {
    host: "https://www.youtube-nocookie.com",
    videoId: EXPECTED,
    playerVars: __PLAYER_VARS__,
    events: {
      onReady: function () { bridge({event: "ready"}); player.playVideo(); },
      onStateChange: function (e) {
        // Plan §6.10: if the player has moved off the video we asked for -- an end-screen card,
        // a related-video click that slipped the navigation lock -- stop it rather than play it.
        var d = player.getVideoData && player.getVideoData();
        if (d && d.video_id && d.video_id !== EXPECTED) { player.stopVideo(); return; }
        bridge({event: "state", state: e.data});
      },
      onError: function (e) { bridge({event: "error", code: e.data}); }
    }
  });
}
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
```

`EmbedPolicy.swift`:

```swift
enum EmbedPage {
    /// `loadHTMLString(_:baseURL:)` with a real https base is what makes WebKit send a Referer;
    /// without one the IFrame API answers 153 ("no Referer"), which is why this is an app-owned
    /// https origin and not `about:blank` (plan §6.4 row 3). It is also the `origin` player var and
    /// the only main-frame URL the navigation lock accepts.
    static let baseURL = URL(string: "https://app.fitrahtube.com/embed")!
    static let handlerName = "fitrahEmbed"

    private static let idPattern = /^[A-Za-z0-9_-]{11}$/
    private static let supportedLocales: Set<String> = ["en", "ar", "nl"]

    /// nil when either substitution value fails validation. The caller shows the generic error --
    /// it never falls back to an unvalidated substitution. This is the app's only place where a
    /// runtime value lands inside a `<script>`; refusal is the defence, not escaping.
    ///
    /// No `customUserAgent` is set anywhere in this rung: plan §6.3's fixed per-client UAs govern
    /// InnerTube API calls, and `youtube-nocookie.com` serves a different player to a UA it does
    /// not read as a mobile browser. WKWebView's stock UA carries no app or device identifier.
    static func html(videoId: String, locale: String, captionsPreferred: Bool) -> String? {
        guard videoId.wholeMatch(of: idPattern) != nil,
              supportedLocales.contains(locale),                  // ruling 19, en fallback upstream
              let template = Bundle.main.url(forResource: "embed", withExtension: "html")
                  .flatMap({ try? String(contentsOf: $0, encoding: .utf8) })
        else { return nil }
        var vars = ["playsinline": 1, "rel": 0, "enablejsapi": 1] as [String: Any]
        vars["origin"] = baseURL.absoluteString
        vars["hl"] = locale
        if captionsPreferred {                                     // plan §6.5's cc_* pair
            vars["cc_load_policy"] = 1
            vars["cc_lang_pref"] = locale
        }
        guard let json = try? JSONSerialization.data(withJSONObject: vars),
              let varsJSON = String(data: json, encoding: .utf8) else { return nil }
        return template
            .replacingOccurrences(of: "__VIDEO_ID__", with: videoId)
            .replacingOccurrences(of: "__HANDLER__", with: handlerName)
            .replacingOccurrences(of: "__PLAYER_VARS__", with: varsJSON)
    }
}
```

```swift
enum EmbedNavigationPolicy {
    /// Plan §6.4 row 3 / §6.10: `decidePolicyFor` cancels every main-frame navigation off the
    /// bundled page. SUBFRAME navigations are always allowed -- the IFrame itself is a subframe and
    /// YouTube's player navigates it continuously; cancelling those breaks playback rather than
    /// locking anything. This is also the answer that justifies "Unrestricted Web Access: No" in
    /// the age-rating questionnaire (plan §9), so it must not grow an allowlist of "safe" hosts.
    static func allows(url: URL?, isMainFrame: Bool, baseURL: URL) -> Bool {
        guard isMainFrame else { return true }
        guard let url else { return false }
        if url.absoluteString == "about:blank" { return true }
        return url.scheme == baseURL.scheme && url.host == baseURL.host
    }
}
```

`EmbedMessage.parse` decodes `[String: Any]` defensively (`body as? [String: Any]`, `["event"] as? String`, `["state"]/["code"] as? Int`, plus `NSNumber` tolerance) and returns nil for anything else. `EmbedErrorPolicy` is the truth table the tests above pin, verbatim, with plan §6.6's error row quoted in a comment. Move `EmbedErrorAction` here from `PlayerViewModel.swift`.

Log every error the policy sees before acting on it (plan §6.6: "2/5/153 → retry once, **log**"). Match the file's neighbours — `BackgroundPlaybackController.swift:48-50` uses a `#if DEBUG print(…)`; do the same, and do not add a DeveloperDialog counter (out of scope).

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh` — expected green, including the `WKWebView` round trip. If that one test is flaky on a cold simulator, keep its `.timeLimit` and let it retry the *load*, never loosen the assertion.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Resources/embed.html \
        ios/FitrahTube/Features/Player/EmbedPolicy.swift \
        ios/FitrahTube/Features/Player/PlayerViewModel.swift \
        ios/FitrahTubeTests/EmbedPolicyTests.swift
git commit -m "[FEAT]: iOS embed page and navigation lock"
```

---

### Task 4: The embed rung on screen — `WKWebView` host, caption, Replay cover, and the transition announcement

**Files:**
- Create: `ios/FitrahTube/Features/Player/EmbedRungView.swift` (`EmbedRungView` + `EmbedWebView` + its `Coordinator` — one file, mirroring `PlayerHostView.swift`'s shape)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (the `.embed` branch, the transition announcement, the `-fitrah-fake-embed-ended` hook)
- Modify: `ios/FitrahTube/Features/Player/PlayerStateView.swift` (`.embed` becomes a `preconditionFailure`, like `.ready`)
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (replace the `// B3:` note at `:161-162` with the answer)
- Test: `ios/FitrahTubeTests/PlayerScreenEmbedTests.swift` (new), `ios/FitrahTubeTests/NowPlayingSnapshotTests.swift` and `ios/FitrahTubeTests/PlayerHostTests.swift` (extend), `ios/FitrahTubeUITests/ScreenshotTests.swift` (extend)

**Interfaces:**
- Consumes: Task 2's `.embed(Resolved)` and `applyEmbedAction`, Task 3's four policies, Task 1's strings.
- Produces: `EmbedRungView(resolved:model:args:)`; accessibility identifiers `player.embedCaption`, `player.embedReplay`, `player.embedBack`.

**Layout (spec §6.6 rung-3 row, exactly):**

```
ScrollView {
  VStack(alignment: .leading, spacing: 0) {
    Text("player_embed_caption")             // ABOVE the frame. RMF forbids overlays ON the player.
    ZStack { EmbedWebView(...); if ended { replayCover } }
      .aspectRatio(16/9, contentMode: .fit).background(.black)
    PlayerToolbar(args: args)
    if verticalSizeClass != .compact { PlayerMetadataView(args: args) }
  }
  .frame(maxWidth: Size.playerMaxWidth(widthClass)).frame(maxWidth: .infinity)
}
```

The overlay control column of the `.ready`/`.rung2Progressive` branch — quality menu, captions menu, audio-language menu, audio-only button, rung-2 pill — is **absent entirely** on this branch. So is PiP: there is no `AVPlayerViewController`, and `allowsPictureInPictureMediaPlayback = false` keeps the web player from offering one (Task 3's reconciliation note 2). `PlayerToolbar` (favorite / share / report) and `PlayerMetadataView` **stay**: spec §6.6's "all FitrahTube controls hidden" enumerates *playback* controls — quality, audio-only, PiP, background — and none of favorite/share/report is a playback control, is an overlay on the player, or has another route in from this screen. This is a reading, so it is written down here rather than left implicit.

**Reconciliation — autoplay.** Plan §6.4 row 3 lists both `mediaTypesRequiringUserActionForPlayback = []` and "no pre-tap autoplay". They are consistent under one reading and only one: the *tap* is the user's tap on the video in the catalog, which is what opened this screen; the flag exists so the `player.playVideo()` that tap authorises is not blocked by WebKit's gesture requirement. RMF's autoplay rule is about embeds that play on a page the user did not ask to play. **Default: `onReady` calls `playVideo()`** (`embed.html` already does), so rung 3 behaves like rungs 1 and 2 instead of demanding a second tap for no compliance gain. This is listed as a fork in the plan hand-off; if the controller vetoes it, delete the one `player.playVideo()` call in `embed.html` and nothing else changes.

- [ ] **Step 1: Write the failing tests**

In a new `ios/FitrahTubeTests/PlayerScreenEmbedTests.swift`, plus additions to the two existing suites:

```swift
@Test func theEmbedRungHasNoAVPlayerAndPausesTheOldOne() {
    // CF-B2-4: `onPolicyAction` reads `model.state` live, so a background policy action that fires
    // during a native->embed transition sees `.embed`. `player(for:)` must then pause and release
    // the outgoing player rather than swap its URL.
    let resolved = Resolved(stream: .embed(videoId: "xc7keR2piUM"), client: .web, userAgent: "",
                             resolvedAt: Date(), expiresAt: nil)
    let existing = AVPlayer(playerItem: AVPlayerItem(url: URL(string: "https://x/y.m3u8")!))
    #expect(PlayerHostView.player(for: .embed(resolved), replacing: existing) == nil)
    #expect(PlayerHostView.streamURL(.embed(videoId: "xc7keR2piUM")) == nil)
}

@Test func theEmbedRungAdvertisesNoNowPlayingEntry() {
    // CF-B2-9: `make` already returns nil here; what this pins is that it STAYS nil, so leaving
    // rung 1 for the embed cannot leave the previous video's metadata on the lock screen. The
    // clearing itself is `PlayerHostView.dismantleUIViewController` -> `detach()` ->
    // `removeRemoteCommands()`, which runs because the embed branch does not mount the host.
    let resolved = Resolved(stream: .embed(videoId: "xc7keR2piUM"), client: .web, userAgent: "",
                             resolvedAt: Date(), expiresAt: nil)
    #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "xc7keR2piUM"), state: .embed(resolved),
                                     elapsed: 0, duration: 120, rate: 1) == nil)
}

@Test func endedStateShowsTheCoverAndNothingElseDoes() {
    // The IFrame API's ENDED is 0; -1 unstarted, 1 playing, 2 paused, 3 buffering, 5 cued.
    #expect(EmbedRungView.showsEndCover(for: .state(0)))
    for other in [-1, 1, 2, 3, 5] { #expect(EmbedRungView.showsEndCover(for: .state(other)) == false) }
    #expect(EmbedRungView.showsEndCover(for: .ready) == false)
}
```

In `ScreenshotTests.swift`, add a case that launches with `-fitrah-fake-player-embed`, waits for `player.embedCaption`, asserts its label is `player_embed_caption`, and captures a screenshot; and a second with `-fitrah-fake-player-embed -fitrah-fake-embed-ended` that asserts `player.embedReplay` exists and captures the cover. **Assert only on FitrahTube's own identifiers** — the IFrame's contents are remote and expose nothing to XCUITest, and on a machine with no network the frame is simply black.

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `EmbedRungView` does not exist; the screenshot cases cannot find `player.embedCaption`.

- [ ] **Step 3: Implement**

`EmbedRungView` (SwiftUI) owns the caption, the frame, the cover and the announcement; `EmbedWebView` (`UIViewRepresentable`) owns the web view; the `Coordinator` owns the delegates, the bridge and the lifecycle observer. The contract, point by point:

- **Configuration** (plan §6.4 row 3, plus the brief's storage rule):
  ```swift
  let config = WKWebViewConfiguration()
  config.websiteDataStore = .nonPersistent()          // no cookies, no local storage across sessions
  config.allowsInlineMediaPlayback = true
  config.mediaTypesRequiringUserActionForPlayback = []
  config.allowsPictureInPictureMediaPlayback = false  // Task 3 reconciliation 2 (spec §6.6, III.I.9)
  config.userContentController.add(WeakScriptMessageProxy(coordinator), name: EmbedPage.handlerName)
  ```
  Plan §6.4 row 3 also requires the embed frame to be **≥ 200×200 points** (YouTube's RMF minimum — below it the player refuses to render its controls). The layout satisfies it by construction: the frame is the full content width at 16:9, which is ≥ 320×180 on the narrowest supported device and ≥ 200 tall from any width ≥ 356 pt. **Do not add a `minHeight` for it** — nothing in this app can produce a narrower player column, and the screenshot matrix is where a regression would show. If the frame is ever parameterised (CF-B3-1's 9:16 Shorts variant), re-check it there: 9:16 makes height the generous dimension and width the tight one.

  `WKWebsiteDataStore.nonPersistent()` is what makes `youtube-nocookie.com` mean what it says across launches; it also matches plan §6.3's cookie discipline for the API session (`httpCookieAcceptPolicy = .never`), by a different mechanism because a `WKWebView` has no `URLSession` to configure.
- **The weak proxy is mandatory.** `WKUserContentController.add(_:name:)` retains its handler for the life of the configuration; a coordinator registered directly never deallocates. A minimal `final class WeakScriptMessageProxy: NSObject, WKScriptMessageHandler` holding `weak var target: (any WKScriptMessageHandler)?` and forwarding is the whole fix (plan §6.4 row 3: "one `WKScriptMessageHandler` (weak proxy, removed on teardown)"). `dismantleUIView` calls `removeScriptMessageHandler(forName: EmbedPage.handlerName)`.
- **The web view:** `isOpaque = false`, `backgroundColor = .black`, `scrollView.isScrollEnabled = false`, `allowsBackForwardNavigationGestures = false`, `navigationDelegate` and `uiDelegate` both the coordinator.
- **Load:** `EmbedPage.html(videoId:locale:captionsPreferred:)` — `locale` from `container.settings.resolvedLocale.language.languageCode?.identifier` narrowed to {en, ar, nl} with `"en"` as the fallback (ruling 19); `captionsPreferred` from `UIAccessibility.isClosedCaptioningEnabled`, the same signal the native rungs' caption auto-enable uses. `nil` html → `model.applyEmbedAction(.fail(messageKey: "player_error_message"), resolved:)` and no load at all.
  Then `web.loadHTMLString(html, baseURL: EmbedPage.baseURL)` — **`loadHTMLString`, never `loadFileURL`**: the base URL is what sends the Referer that stops IFrame error 153.
- **Navigation lock:**
  ```swift
  func webView(_ web: WKWebView, decidePolicyFor action: WKNavigationAction,
               decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
      decisionHandler(EmbedNavigationPolicy.allows(url: action.request.url,
                                                    isMainFrame: action.targetFrame?.isMainFrame ?? true,
                                                    baseURL: EmbedPage.baseURL) ? .allow : .cancel)
  }
  ```
  `action.targetFrame == nil` means a new-window navigation — treated as main frame, i.e. cancelled. Pair it with `func webView(_:createWebViewWith:for:windowFeatures:) -> WKWebView? { nil }` so `target="_blank"` opens nothing (plan §6.4 row 3).
- **Bridge:** `userContentController(_:didReceive:)` → `EmbedMessage.parse(message.body)` →
  - `.ready` → call `BackgroundPlaybackController.configureAudioSession()` (see the audio-session note below).
  - `.state(0)` → set `ended = true` (the cover). Any other state → `ended = false`.
  - `.error(code)` → `EmbedErrorPolicy.decide(code:alreadyReloaded:safeMode: model.safeMode)`; `.reloadOnce` sets `alreadyReloaded = true` and reloads the page (rebuild the HTML and `loadHTMLString` again — `web.reload()` on a string-loaded page is unreliable); anything else goes to `model.applyEmbedAction(_:resolved:)`.
- **Content-process termination:** `webViewWebContentProcessDidTerminate` → `EmbedErrorPolicy.decideProcessTermination(alreadyReloaded:safeMode:)`, same two outcomes.
- **Background:** the coordinator observes `UIApplication.didEnterBackgroundNotification` and evaluates `player && player.pauseVideo()` (plan §6.4 row 3, "paused on `didEnterBackground`"; YouTube API policy III.I.9). Remove the observer in `dismantleUIView`. **Do not touch `BackgroundPlaybackController.onWillEnterForeground` or `onPolicyAction`** — those belong to the native host, which is not mounted here (CF-B2-7: that hook is awaited, and nothing in this rung may be chained onto it).
- **Audio session.** Configure `.playback` (reuse `BackgroundPlaybackController.configureAudioSession()`, which is `static` and idempotent) or the ringer switch silences a lecture. Call it **twice**: once when the web view is created and again on `.ready`. The reason is a real ordering race — SwiftUI does not guarantee that `PlayerHostView.dismantleUIViewController` (whose `detach()` calls `setActive(false, options: .notifyOthersOnDeactivation)`) runs before this view's `makeUIView`, so a single early activation can be deactivated out from under the embed. `.ready` arrives well after any dismantle. On teardown, hand the session back with the same `setActive(false, options: .notifyOthersOnDeactivation)` — this is CF-B2-8's "exactly one owner": while the embed is up, the native host is gone, so the embed is that owner.
- **The end cover** (spec §6.6: "end screen covered by a FitrahTube 'Replay / Back' card on ENDED"): an opaque card filling the frame with two buttons — Replay (`player_embed_replay`, identifier `player.embedReplay`) evaluating `player.seekTo(0, true); player.playVideo();` and clearing `ended`; Back (`back`, identifier `player.embedBack`) calling the environment `dismiss`. It must be opaque and cover the whole frame: its purpose is that YouTube's end-screen recommendation cards are never visible or tappable.
- **The announcement:** extend `PlayerScreen`'s existing `.onChange(of: model?.state)` — currently a `guard case .rung2Progressive` — into a switch that also posts `player_embed_caption` on entry to `.embed` (spec §6.6 Transitions: "Playing in YouTube's player"). Keep the two announcements in that one place; do not add a second announcement site inside `EmbedRungView`.
- **Reduce Motion:** the caption/frame/cover composition uses `.transition(reduceMotion ? .identity : .opacity)` and `.animation(reduceMotion ? nil : .easeInOut, value:)`, copied from `PlayerStateView.body`'s pattern rather than re-derived.
- **`-fitrah-fake-embed-ended`** (DEBUG only): seeds `ended = true` so the screenshot rig can capture the cover without a real video reaching its end. Same technique as `PlayerViewModel.debugForceRecoveryExhausted`.

Then: change `PlayerStateCopy.map`'s `.embed` arm from Task 2's interim copy to a `preconditionFailure` with the same wording as the `.ready` arm (the embed now has its own branch and must never reach the shared state view), and replace `PlayerHostView.swift:161-162`'s `// B3:` note with a statement of what actually happened — the embed rung has no `AVPlayer`, `allowsPictureInPictureMediaPlayback = false` on its `WKWebView`, so there is no PiP affordance on rung 3 at all.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh` — expected green.
Manual simulator check (network required, and this is the first point in the plan where a real IFrame load is possible): launch with `-fitrah-fake-player-embed`; the caption sits above the frame, the video plays inside it, tapping the YouTube logo / title / "Watch on YouTube" does nothing (the lock cancels it), and no FitrahTube playback control is visible. Background the app → audio stops. Let a short video end → the Replay/Back cover appears over the end screen; Replay restarts it. Repeat in Arabic (the caption is leading-aligned and mirrors; the frame does not) and at Dynamic Type `.accessibility3` (the caption wraps, it does not clip).

**What the simulator cannot prove here:** real error codes 100/101/150 (they need genuinely removed / embedding-disabled videos), `webViewWebContentProcessDidTerminate` (needs a real jetsam), and the Screen Time "Only Approved Websites" behaviour. All of that is Task 5.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/EmbedRungView.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTube/Features/Player/PlayerStateView.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTubeTests/PlayerScreenEmbedTests.swift \
        ios/FitrahTubeTests/NowPlayingSnapshotTests.swift \
        ios/FitrahTubeTests/PlayerHostTests.swift \
        ios/FitrahTubeUITests/ScreenshotTests.swift
git commit -m "[FEAT]: iOS embed rung player"
```

---

### Task 5: Acceptance pass — simulator matrix, then the live-YouTube and device checklists

**Files:** touch only what a finding requires; screenshots under `screenshots/b3-task5/`.

- [ ] **Step 1: Simulator matrix (do this yourself)**

Run `ios/scripts/screenshots.sh` (full matrix) and check:
  - Settings → Content → Safe Mode reads as real copy in en / ar / nl, defaults ON, and survives a relaunch.
  - `-fitrah-fake-player-embed`: caption above the frame, no quality / captions / audio-language / audio-only / PiP control anywhere, toolbar and metadata present below. On the narrowest capture (iPhone 17 portrait) confirm the frame is still comfortably over plan §6.4's 200×200 pt floor, and that it stays so in landscape and in Split View widths on the iPad.
  - `-fitrah-fake-player-embed -fitrah-fake-embed-ended`: the Replay/Back cover fully hides the frame.
  - `-fitrah-fake-player-open-in-youtube` with Safe Mode **off**: reason line + "Open in YouTube" → confirmation → (no YouTube app on the simulator) Safari opens `https://youtu.be/…`.
  - The same argument with Safe Mode **on**: `player_stream_unavailable`, no button, no Retry.
  - iPhone 17 + iPad Pro 13-inch (M5), portrait and landscape, en and ar (RTL: the caption is leading-aligned and mirrors; the 16:9 frame does not), Dynamic Type `.accessibility3`.
  - VoiceOver: entering the embed announces "Playing in YouTube's player"; the Replay and Back buttons read their labels; the caption is reachable.
  - Reduce Motion on: the transition into the embed and the appearance of the cover are instant, not cross-dissolved.

- [ ] **Step 2: Live-YouTube checks (do these yourself, on the simulator, with network)**

These need YouTube but not a device. Pick the video ids from the catalog, and record the result of each in the commit message — do not "fix" a rung that is behaving correctly:
  1. A normal catalog video with `resolverOrder` temporarily set to `["embed"]` (the compliance configuration of plan §6.4 — set it in `Packages/InnerTubeKit/Sources/InnerTubeKit/Resources/remote-config-default.json` locally, **revert before committing**): it plays in the embed, with ads, and the caption is present.
  2. An embedding-disabled video → error 101 or 150 → "The creator only allows this video on YouTube" + the button; repeat with Safe Mode ON → same message, no button.
  3. A deleted/private video id → error 100 → "This video was removed", no button, in both Safe Mode states.
  4. Tap every escape the IFrame offers — title, channel avatar, YouTube logo, "Watch on YouTube", share, the end-screen cards — and confirm none of them navigates the main frame or opens Safari.
  5. Airplane-mode the simulator mid-embed and restore it: the rung reloads once and then reports rather than looping.

- [ ] **Step 3: Fix anything steps 1–2 surface**, re-run `ios/scripts/test.sh`, commit `[FIX]: iOS B3 embed accessibility and layout pass`.

**OUTCOME (2026-08-27).** Steps 1–3 are done. Everything below is what actually happened, including where it contradicts the steps above.

**Superseded by owner directive.** Rung 4 no longer exists: the app never offers a redirect or hand-off to YouTube, in any Safe Mode setting (`[FIX]: Remove the Open in YouTube rung`). Step 1's `-fitrah-fake-player-open-in-youtube` bullets and Step 2's items 2 and 3 are therefore void as written — the embed rung is the ladder's floor, and every error below it lands on one terminal card with no Retry and no route out. Step 4's device item 1 is void with them.

**Simulator matrix (Step 1).** Wired into `ios/scripts/screenshots.sh` as `b3-task4` (rung 3 + the ENDED cover) and `b3-task5` (Dynamic Type `.accessibility3`, `ar` RTL, iPad Pro 13-inch portrait + landscape), output under `.superpowers/sdd/2026-08-27-ios-phase2b3-embed-safemode/screenshots/`. Plan §6.4's ≥ 200 × 200 pt floor is now **asserted, not assumed**: `assertEmbedFrameFloor` reads the `WKWebView`'s own frame (identifier `player.embedFrame`) on every device and orientation. There is no `b3-task2` block — its two cases were rung-4 cases.

**Live IFrame checks (Step 2), run on the simulator with network, opt-in behind `EMBED_LIVE=1`** (`TEST_RUNNER_EMBED_LIVE`, the same contract InnerTubeKit's `LiveResolveTests` has with `INNERTUBE_LIVE`). Ids are drawn **only** from the approved catalog (second owner directive):

| Check | Result |
|---|---|
| Page loads, `origin` accepted, autoplays off `onReady` | **PASS** — `ready → state(-1) → state(3) → state(1)` with no tap; `origin=https%3A%2F%2Fapp.fitrahtube.com` in the IFrame URL and **no `error(153)`** |
| Every IFrame escape (title, avatar, logo, "Watch on YouTube", share, end cards) | **PASS** — two `CANCEL window.open https://www.youtube.com/watch?v=…` verdicts logged, the share sheet stayed a subframe `#bottom-sheet` navigation, and **no** main-frame navigation off `app.fitrahtube.com` was ever allowed |
| ENDED raises the cover through the bridge; Replay restarts | **PASS** — real `state(0)` from a 52 s catalog clip, cover raised, Replay → `state(1)` |
| Error 100 on a nonexistent id → terminal card | **PASS with a correction** — the id answered **101/150**, not 100 (see CF-B3-9); the card is terminal, identical in both Safe Mode states, with no Retry and no YouTube control |
| Error 101/150 on an embed-disabled video | **NOT RUN** — every approved-catalog id is embeddable (CF-B3-8); provoking the code needs a video from outside the catalog |
| Airplane-mode mid-embed (Step 2 item 5) | **NOT RUN** — a simulator has no per-device network toggle; the one-reload-then-report budget is pinned by `EmbedPolicyTests` |

**Defect found and fixed by the live pass:** a terminal embed error rendered a **Retry** that could only fail again (ruling 14 forbids it) — fixed with `StreamState.unplayable(messageKey:)`. See CF-B3-10.

**Additional carry-forwards from the final fix round (2026-08-27):**

- **CF-B3-13:** embed load watchdog — no bridge event ever arrives when `iframe_api` is unreachable while the device is online (a hung load, not an error code). Fixed in the B3 final fix round.
- **CF-B3-14:** Safe Mode's subtitle promises "turn off autoplay" — B5 must wire it before any user build (**BLOCKING**).
- **CF-B3-15:** the live nav-lock test needed a positive `CANCEL` assertion, not just the absence of a main-frame navigation. Fixed in the final fix round.
- **CF-B3-16:** this plan doc's rung-4 text is superseded (this edit — see the banners on Tasks 2 and 3 above).

**Decision:** the age gate now aborts the ladder *before* the embed rung — terminal `.ageRestricted` — where it previously jumped to rung 4.

- [ ] **Step 4: Record the device checklist — USER-BLOCKED, do not attempt**

The repo has no signing identity (`DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset; `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: NO` is the only reason simulator builds work). Report these to the controller as blocked, with this list verbatim:

  1. ~~**`youtube://` hand-off**~~ — **VOID.** The owner directive removed the rung; there is no hand-off left to test, and its absence is asserted instead (`ScreenshotTests.testEmbedLiveRemovedCard` counts zero controls labelled "YouTube").
  2. **Screen Time / Web Content = "Only Approved Websites"** (plan §6.10, §10 item 8) — install under a child account with that restriction and record what the embed does: whether the frame loads, blank-loads, or shows Apple's block page, and whether FitrahTube reports it sanely rather than spinning. This is the evidence behind the age-rating answer "Unrestricted Web Access: No" (plan §9); it must be recorded, not assumed.
  3. **Content-process termination** — force a jetsam of the web content process (memory pressure, or `killall -9 com.apple.WebKit.WebContent` on a development device) and confirm the one reload, then the rung-4 card.
  4. **Background** — start the embed and lock the screen: audio stops (this is the *opposite* of rung 1's behaviour and is required by YouTube API policy III.I.9; if it keeps playing, the `didEnterBackground` observer is not firing).
  5. **No PiP affordance** on the embed — the web player offers no PiP button and a home-swipe does not produce a floating window.
  6. **Ringer switch / silent mode** — the embed's audio is not silenced by the hardware switch (this is what the `.playback` category buys).
  7. **Storage** — sign in to YouTube inside the embed if it offers to (it should not be able to: the navigation lock cancels `accounts.google.com`), then relaunch and confirm no session persists (`WKWebsiteDataStore.nonPersistent()`).

**Report these six to the user verbatim as USER-BLOCKED** (item 1 is void). They are the whole of what the simulator could not answer: the live-YouTube half of the acceptance is done and green (see OUTCOME above), so this list is genuinely hardware-only — a real lock screen, a real jetsam, a real ringer switch, a real Screen Time restriction and a real PiP gesture.

---

## Out of scope for B3 (later sub-plans, or deliberate deferrals)

- **Playlist auto-advance itself.** Ruling 58's headline effect lands in **B5** (Up Next / queue), which reads `PlayerViewModel.safeMode` — the hook this plan creates and uses. B3 adds no queue.
- **Shorts on the embed rung** (plan §6.8: "on the embed rung size the `WKWebView` 9:16 and keep the navigation lock") — **B4**. `EmbedRungView` hard-codes `aspectRatio(16/9)`; B4 parameterises it. Nothing else about the rung changes.
- **Fullscreen on the embed** — B5 owns fullscreen. The IFrame's own fullscreen button is left exactly as YouTube ships it (`allowsInlineMediaPlayback = true` keeps playback inline until the user asks); RMF forbids replacing YouTube's player chrome, so FitrahTube adds no fullscreen control of its own here.
- **Captions overlay on the embed.** `CaptionsProvider` exists for rung 1's auto-generated tracks; the embed has YouTube's own caption menu, and plan §6.5's answer for this rung is the `cc_lang_pref` / `cc_load_policy` pair, which Task 3 passes. No overlay.
- **Download on the embed rung** ("This video can't be downloaded", spec §11) — Phase 3, with every other download affordance (rulings 28 and 56).
- **DeveloperDialog embed counters.** Ruling 62 adds resolver counters and cooldown state in Phase 2; embed error codes are logged (`#if DEBUG`), not surfaced. Add a counter when a real diagnosis needs one.
- **Filtering `RemoteConfig.resolverOrder` inside InnerTubeKit.** Rejected with reasons in Task 2's reconciliation note 2; if a later phase needs the resolver itself to know about Safe Mode (e.g. to skip a rung that costs a network call), that is the moment to revisit — not before.
- **A PoToken minter in a `WKWebView`** (plan §6.12) stays on a private branch and out of the shipped binary. B3's `WKWebView` is for the embed and nothing else; do not make it general-purpose.
- **`resolverOrder: ["embed"]` as the shipped configuration.** It is already reachable as data (plan §6.4, §11) and needs no code. Flipping it is a runbook action, not a task.

## Carry-forward for B4+

- **CF-B3-1:** `EmbedRungView` hard-codes a 16:9 frame. B4 (Shorts) needs 9:16 on the same rung — parameterise the aspect ratio, and keep the navigation lock and the end cover unchanged (plan §6.8).
- **CF-B3-2:** `PlayerViewModel.safeMode` is B5's auto-advance gate (ruling 58, spec §10 "auto-advance on end **unless Safe Mode**"). B5 must read that property, not `SettingsStore` directly, so the player has one Safe Mode reader.
- **CF-B3-3:** the embed and the native host both activate/deactivate `AVAudioSession` (CF-B2-8's warning, now with a second owner). Today they never overlap because a `PlayerScreen` branch change dismantles one before mounting the other; Shorts (B4) adding a third player on the same screen would break that invariant. Whoever adds it owns making the ownership explicit.
- **CF-B3-4:** `player_error_generic` is now rung 4's reason line for *both* "the ladder bottomed out" and "the web content process died twice". If those ever want distinct copy, that is a new key, not a re-purposed one.
- **CF-B3-5:** the `WKWebView` bridge round-trip test (Task 3) is the only test in the iOS suite that needs a live web content process. If it proves flaky in CI, the fix is to keep it and quarantine it into a separate test plan — not to delete the only coverage of the handler-name/message-shape seam.
- **CF-B3-13:** embed load watchdog — no bridge event ever arrives when `iframe_api` is unreachable while the device is online (a hung load, not an error code). Fixed in the B3 final fix round.
- **CF-B3-14:** Safe Mode's subtitle promises "turn off autoplay" — B5 must wire it before any user build (**BLOCKING**).
- **CF-B3-15:** the live nav-lock test needed a positive `CANCEL` assertion, not just the absence of a main-frame navigation. Fixed in the final fix round.
- **CF-B3-16:** this plan doc's rung-4 text is superseded (this edit — see the banners on Tasks 2 and 3 above).
