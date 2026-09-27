# Save for Offline, Cast & AirPlay Implementation Plan (iOS Phase 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** After Phase 2 the app resolves, plays, browses and reports, but nothing survives airplane mode and nothing reaches a TV. Phase 3 closes the spec §15 row 3 gate: a per-video **"Save for offline"** affordance (owner ruling 2026-09-01 — the reshaped port of Android's Downloads), a Saved library screen with in-app-only offline playback, Chromecast on the main player, and an explicit AirPlay story. Day 1 is the §17 spike: whether `AVAssetDownloadTask` accepts YouTube's HLS packaging; both outcomes are planned (Task 1). The per-video `offlineAllowed` gate is new backend + admin work (Task 2, BACKEND). The `downloadsEnabled` remote-config kill-switch ships reviewed-ON (Task 5).

**Architecture:** No new package (reconciliation note 1 — this deliberately amends spec D14's `DownloadKit`). The engine is `ios/FitrahTube/Features/Offline/`: pure, `nonisolated` decision types (`OfflineStateMachine`, `OfflineExpiryPolicy`, `OfflineRevalidation`, `OfflineStorage`, `OfflineQuality`) that carry all the interesting logic and test without a network or a simulator-only API, plus one `OfflineManager` actor that owns the background `URLSession`(s) and is deliberately thin glue. Persistence is SwiftData `OfflineItem` in the existing container as `FavoritesSchemaV4` (the `FavoriteVideo.swift` lightweight-migration pattern, exactly as V2/V3 did it). Playback reuses the whole Phase 2 player through the `StreamResolving` seam (`PlayerViewModel.swift:7-10`): an `OfflineResolver` returns a `Resolved` whose URL is a local file, and the player renders its offline mode with save/quality/cast hidden. Cast is a `@MainActor CastController` wrapping the Google Cast XCFramework (fetched by the `fetch-cast-sdk.sh` Phase 0 promised and never shipped — see contradictions); AirPlay is already 90% shipped by AVKit's stock transport and gains only the 403→mirroring fallback.

**Tech Stack:** Swift 6, SwiftUI, `@Observable`, Swift Testing; app target `ios/FitrahTube` plus `ios/Packages/InnerTubeKit` (ONE file: `RemoteConfig.swift`, Task 5) — `ios/Packages/FitrahAPI` untouched. Backend task: Spring Boot (`backend/`, `./gradlew test`) + Vue admin (`frontend/`, `npm test`). Gate for iOS tasks: `ios/scripts/test.sh` (300 s wall). Screenshots: `ios/scripts/screenshots.sh`.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` — D3 (offline/Cast/AirPlay in scope), D8 (Cast SDK via script-fetched XCFramework), D12 (the dead Android download settings implemented for real), §5 (`fetch-cast-sdk.sh`, `Vendor/`), §6 (routes incl. `downloads`; DownloadQuality sheet), §10 (Chromecast and AirPlay paragraphs, verbatim behaviours), §11 (the whole Downloads section — **as reshaped by the owner ruling, reconciliation note 2**), §15 row 3 (gate: "engine tests; simulator download/offline play; Cast verified on the user's network"), §16 ("state machine transitions and action matrix, expiry sweep, resume-data round trip, cellular gate"), §17 items 2 (`AVAssetDownloadTask` open question) and the risk table row ("fallback is itag 18 + itag 140 progressive downloads for every tier (360p ceiling) — stated in the picker").

**THE OWNER RULING (2026-09-01), binding on every task** — `docs/architecture/ios-app-plan.md` intro item 5, §7 downloads row (`:284`), §9 checklist "Offline saving" line (`:360`, commit 062226bb):
- User-facing name **"Save for offline"**, never "Download" — strings, listing, screenshots. (Internal symbols are free; this plan uses `Offline*` everywhere so the greppable boundary is obvious.)
- **App-container storage only**, `isExcludedFromBackup`; **no** `UIFileSharingEnabled`, **no** `LSSupportsOpeningDocumentsInPlace`; no share sheet / Photos / export of saved media; **playback in-app only**.
- Per-video backend **`offlineAllowed`** gate — admin-set, **default false**. Creator-authorization records are the owner's side of 5.2.3; the client's side is simply refusing to save anything not flagged.
- **`downloadsEnabled`** remote-config field (optional Bool, `featuredCategoryId` pattern — `RemoteConfig.swift:48-51`) — reviewed **ON**, kill-switch semantics (2.3.1: never dark past review; exists to turn a reviewed feature OFF on complaint, same-day, alongside `resolverOrder: ["embed"]`).
- Saving uses the **same resolver ladder as playback**; never the embed rung ("This video can't be saved for offline" is the floor).
- **Auto-delete on catalog removal** (the §8 revalidation pipeline is the backend half; Task 7's sweep is the client half).
- Never pair the offline copy with "ad-free" copy (the Musi/ProTube complaint profile). The existing convert-strings ban already covers "ad-free"; Task 5's strings extend the same discipline.

**Behavioural source (Android, cited file:line):** `DownloadWorker.kt` (403 → one re-resolve, `:266-276`; UA must match the minting client, `:52-67`), `DownloadQualityDialog.kt:64-114` (picker order — the range starts in companion constants; the option-building logic follows), `DownloadExpiryPolicy.kt:23-28` (30-day TTL, 1 h grace, sweep on launch/foreground), `DownloadStorage.kt:61-67` (no quota — device storage is the limit), `DownloadErrorCode.kt:12-39` (error codes), `DownloadsFragment.kt` / `DownloadsAdapter.kt:96-125` (row anatomy, action matrix, footer), `CastOptionsProvider.kt:36-42` (receiver `CC1AD845`), `SettingsPreferences.kt:84-87` (`download_quality` / `wifi_only_downloads` — the dead settings iOS implements for real; spec D12 cites `:75-83`, the keys actually sit at `:84-87`).

**Predecessors:** every plan under `docs/superpowers/plans/2026-08-*-ios-*`; carry-forward ledger `docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md`.

---

## Contradictions in the inputs (flagged, resolved here, not silently)

1. **Spec §11 predates the owner ruling and disagrees with it.** §11 says "Downloads"/"Download" throughout (screen name, settings rows, notification copy), keeps Android's Library rows (Favorites / Recently watched / History) on the Downloads screen, and ships a completion notification. The ruling (2026-09-01) renames every user-facing surface "Save for offline"; the §7 parity row (`ios-app-plan.md:284`) moves the Favorites/history links to the Me tab (Phase 4). **The ruling wins.** This plan does not edit the spec (no-`.md`-edits rule); the spec's §11 mechanical content (engine, persistence shape, action matrix, expiry, storage) remains authoritative where the ruling is silent.
2. **`ios-app-plan.md` §7 player row still says "Cut: download, Chromecast"** while spec D3/D8/§10 and the §7 downloads row (amended for the ruling) put both in scope. The spec + ruling win; the player row is a stale fragment the owner amends separately.
3. **The Cast fetch script never shipped.** Spec §15 row 0 lists "Cast fetch script" as a Phase 0 deliverable and §5 names `ios/scripts/fetch-cast-sdk.sh` + `ios/Vendor/`; neither exists (verified: no `cast` reference in `ios/project.yml`, no `Vendor/`, no script). Task 8 creates it — Phase 3 pays Phase 0's debt.
4. **`RequestKind` has no download lane and Android's never did.** InnerTubeKit's enum maps "1:1" to Android's (`ExtractionRateLimiter.swift:3-8`) — and Android's `DownloadWorker` never touches that kind-based limiter. It is NOT ungated, though: its resolve rides the Priority-lane rate-limit + cooldown gates with `forceRefresh = true` and `Priority.USER_FOREGROUND` (`DownloadWorker.kt:329-336`, the ANDROID-PERSONAL-02 comment). So Android parity says "saves go through the shared gates like any user action" but names no *kind* lane. Reconciliation note 4 decides the iOS lane (default: reuse `.prefetch`, fork B).
5. **`GET /api/v1/videos/{id}` returns the raw Firestore `Video` model** (`PublicContentService.java:994`), Timestamp objects included — the exact serialization defect Plan C recorded as BACKEND item 1 (`PHASE2-CARRYFORWARDS.md:154`). The generated Swift client cannot decode it in prod; Task 5 extends the hand-written `PublicHeaders` (`ios/FitrahTube/Catalog/PublicHeaders.swift`) instead.
6. **The §15 gate says "simulator download/offline play", but `AVAssetDownloadTask` has historically been unsupported on the simulator** and no device signing exists (`DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` unset — the standing USER-BLOCKED wall). The progressive engine satisfies the simulator half of the gate; the HLS engine's live verification may be device-only. Task 1 measures instead of assuming.

---

## Global Constraints

Implementers inherit nothing from earlier plans. All of the following are binding:

- **Swift 6, `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor`** on the app target (`ios/project.yml:55`), `SWIFT_STRICT_CONCURRENCY: complete`. Pure value types tests construct off the main actor are `nonisolated`. `OfflineManager` is an `actor`; per spec §5's isolation rule, engine protocols that run off-main are `nonisolated … Sendable` with `async` requirements, and the non-`Sendable` Cast SDK object is wrapped behind a `@MainActor` store so `AppContainer`'s properties stay as they are.
- **One implementer at a time on the iOS build slot.** `ios/DerivedData` is shared; never run two `xcodebuild`s concurrently. Task 2 (BACKEND) uses the backend gradle/npm slot and MAY run in parallel with an iOS task — they share no files.
- **Gate for iOS tasks:** `ios/scripts/test.sh` from the repo root (300 s wall / 60 s per test; runs `convert-strings.py --check` → `xcodegen generate` → `xcodebuild test` iPhone 17 + iPad Pro 13-inch (M5) → `swift test` in both packages → a Release build). Task 2's gate: `cd backend && ./gradlew test` and `cd frontend && npm test`. A task is not done until its gate is green.
- **No new files need project edits** — `project.yml:43-46` globs `FitrahTube/`; `xcodegen generate` is gate stage 2. The exception is Task 8's `Vendor/GoogleCastSDK` framework dependency, which DOES edit `project.yml` (the only project.yml edit in this plan).
- **New `Route` case discipline.** This plan adds exactly ONE `Route` case, `.offline` (Task 6). Plan C's switch enumeration is STALE: `PhaseTwoPlaceholderView` no longer exists (Plan C Task 5 removed the last placeholder), and `MainShellView.destination(for:)` (`MainShellView.swift:138-163`) is now a fully exhaustive switch with NO `default:` arm (its own comment at `:134-136` says so). Adding `case offline` therefore produces exactly one compile error — that switch — and Task 6 fixes it by adding the `SavedScreen` arm there, plus a `MainShellRoutingTests`-style test pinning the destination. No new `StreamState` case anywhere: offline playback rides `.ready`/`.rung2Progressive` (reconciliation note 5).
- **All user-visible strings go through `ios/scripts/convert-strings.py`.** Never hand-edit `Localizable.xcstrings`. The word **"Download"** must not appear in any string a Phase 3 surface renders — new keys are `offline_*` under `EXTRA_KEYS` (en/ar/nl authored, the `share_app_promo` precedent at `convert-strings.py:63-70`), and the FIVE Android settings keys `SettingsView.swift` already renders (`settings_downloads`, `settings_download_quality`, `settings_wifi_only`, `settings_wifi_only_desc` — Android values "Downloads"/"Download Quality"/"WiFi Only"/"Only download over WiFi connections", `strings.xml:502-506` — plus `settings_download_quality_title` "Download Quality", `strings.xml:507`, the quality-picker sheet title at `SettingsView.swift:193` which Task 6 keeps) are added to `REFUSE` and re-authored under `EXTRA_KEYS` with "Save for offline" language (Task 5). `download_*`/`downloads_*` Android keys are NOT ported to any iOS caller and stay orphaned in the catalog; do not DEAD_PREFIX them (out of scope — they cost nothing and pruning them is a converter change with its own blast radius). `update_`/`available_versions_` are already DEAD_PREFIXED (`convert-strings.py:40`) — irrelevant here.
- **Copy rules.** Never "ad-free". Never "Download". Refusal copy says what, never why ("This video can't be saved for offline" — not "the admin hasn't allowed it", not "YouTube blocked us"). The kill-switch OFF state hides affordances silently; it never announces itself.
- **Compliance pins are tests, not comments** (Task 7): Info.plist must not contain `UIFileSharingEnabled` or `LSSupportsOpeningDocumentsInPlace`; the offline directory must have `isExcludedFromBackup`; no `ShareLink`/`UIActivityViewController` may receive a saved file URL (enforced by construction — the Saved screen builds no share affordance — and pinned by the strings/UI tests asserting the row action set).
- **Every backend call goes through the shared seams** — `FitrahAPIClient`/`HTTPTransport` with `X-Device-Id` (the Plan C constraint, verbatim). Task 5's `OfflineGateClient` follows `PublicHeaders`' shape.
- **The resolver is the only extraction path.** `OfflineManager` resolves through `container.resolver` (`StreamResolver.resolve(_:purpose:sourceChannelId:forceRefresh:)`, `StreamResolver.swift:68`) after an `ExtractionRateLimiter.check` (reconciliation note 4). Never a second extraction pipeline, never the embed rung's URL, never `forceRefresh: true` on the first attempt (the manifest cache hit for a just-watched video is the cheap path — `StreamResolver.swift:73`).
- **Single-audio-owner rule (CF-B2-8/CF-B3-3/CF-B4-15).** Nothing in this plan may activate or deactivate `AVAudioSession`. Cast pausing the local player goes through the existing `PlayerViewModel`/host seams; the mini controller renders UI only. Phase 3 does NOT build the app-scoped player holder (CF-B2-1/CF-G-1) — see Carry-forwards.
- **Pagination rule:** the Saved screen is a local SwiftData list, complete in memory — no pagination machinery. Do not add `PaginationGuard` to it.
- **Accessibility floor** (spec §14): ≥44 pt targets, Dynamic Type, single column at `.accessibility1+`, label+value on stateful controls (the `PlayerToolbar.favoriteButton` idiom), RTL via leading/trailing, numerals through `Format`.
- **No new `.md` files; this plan is the only document Phase 3 creates.** Never `git add` files a task's commit step doesn't name.
- **No music-video ids** in tests/fixtures/live checks (owner directive 2026-08-27) — use `xc7keR2piUM` or other approved-catalog lecture ids.
- **Simulator vs device honesty.** Everything USER-BLOCKED is marked in the Acceptance section; do not claim device behaviours from simulator runs.

---

## Reconciliation — read all seven before writing code

**1. No `DownloadKit` package (amends spec D14/§16's package framing). The coupling is all app-side.** What the spec imagined as a package needs, in practice: the SwiftData `ModelContainer` (app-owned, `AppContainer.makeModelContainer`, `AppContainer.swift:202`), `SettingsStore.wifiOnlyDownloads`/`downloadQuality` (`@MainActor` app protocol, `SettingsStore.swift:20-21`), `StreamResolving`/`RateLimitedResolver` (app seam, `PlayerViewModel.swift:7`), `BackendAvailabilityGate`+`PublicHeaders` (app files), `Route`/`PlayerArgs` (app), and the `AppDelegate` background-session hook (app). A package would need a protocol shim for every one of those to buy exactly one thing: `swift test` without a simulator. The pure engine types get that benefit anyway by being `nonisolated` value types in `FitrahTubeTests` — the same trade Plan C made for `ChannelBrowse`/`ReportPayload` and shipped clean. §16's named tests ("state machine transitions and action matrix, expiry sweep, resume-data round trip, cellular gate") all land in `OfflineEngineTests` unchanged. Fork A records the override. Cost if wrong: moving files into a package later is mechanical; the reverse (dissolving a premature package) is what Phase 2 would have had to do.

**2. What survives from spec §11 and what the ruling reshapes.** Kept verbatim: engine-per-rung (HLS → `AVAssetDownloadTask`, itag 18/140 → `URLSessionDownloadTask`), embed rung → refusal, one re-resolve on 403, ≥10 min `expire` guard, SwiftData item shape (+ `resumeData`), files under Application Support excluded from backup, task-identifier re-attach, error codes (MERGE/NO_COMPATIBLE_VIDEO/VIDEO_AUDIO_MISMATCH dropped, NOT_DOWNLOADABLE → `notSaveable`), 30-day TTL + 1 h grace + launch/foreground sweep, no quota, row anatomy + action matrix + storage footer, settings rows, in-app-only playback. Reshaped: every string (Task 5), no Library rows on the Saved screen (Me tab, Phase 4), no completion notification by default (fork E), plus the ruling's additions — `offlineAllowed` gate, `downloadsEnabled` kill-switch, revalidation auto-delete. Renames: screen = "Saved" / "Save for offline", directory = `Application Support/offline/`.

**3. The gate fetch is a hand-written 20-line client, and gate-default is CLOSED.** `offlineAllowed` must be read at save time. `PlayerArgs` carries metadata only (no backend fetch before playback — spec §6), so the Save button's state needs one `GET /api/v1/videos/{id}` per player open. The generated client can't decode that response (contradiction 5), so Task 5 adds `video(_:)` to the `PublicHeaders` pattern, decoding ONLY `{offlineAllowed}` and mapping status: 200 → the flag (absent → false), **404 → not saveable** (channel-sourced videos are not individually registered, `PublicContentService.java:997-1000` — they play via fail-open but were never admin-flagged, so the ruling's default-false applies), **410 → not saveable**, transport error → **not saveable** (fail-closed; the opposite of playback's fail-open, deliberately — playback degrading is a UX cost, saving without authorization is the compliance cost the ruling exists to prevent). The fetch is one request per player open, async, and the button renders hidden until it lands.

**4. Rate-limiter lane: reuse `.prefetch`, with wait-don't-skip semantics.** Android offers no kind-lane precedent (contradiction 4 — its saves ride the Priority gates, not a `RequestKind`). The choices: (a) `.player` — wrong: shares the per-kind 30 s min-interval and consecutive-attempt backoff with the user's own playback taps; (b) a new `.download` case — honest semantics, but edits a shipped, heavily-tested InnerTubeKit type and forces budget-policy decisions nothing yet needs; (c) `.prefetch` — already means "background, deprioritized, never starves manual/recovery" (`ExtractionRateLimiter.swift:217-221`: blocked once per-video budget pressure exists; shares the 10/min global lane with `.player`). (c) is the ladder's answer, with ONE deviation from CF-B2-2's Up Next rule: a `.blocked`/`.delayed` decision for a *save* is not skipped silently — the item stays `queued` and the manager retries after `retryAfter` (a queued save has a UI row; silence is for invisible prefetches). Note the consequences: a playlist-adjacent Up Next prefetch and a save of the same video share a budget — acceptable, both are background. And the permit cost is real even on a cache hit: `ExtractionRateLimiter.check` records the attempt BEFORE returning `.allowed` (`ExtractionRateLimiter.swift:103-131`), so every save spends one per-video and one global-10/min permit even when the resolve is then served from the manifest cache with no POST. One permit per save is an acceptable price — do not "optimize" it by peeking the cache before the check. Fork B records the `.download`-case override.

**5. Offline playback is a resolver stub, not a player fork.** `PlayerViewModel` accepts any `StreamResolving` — note the conformance is the FULL signature `resolve(_:purpose:kind:sourceChannelId:forceRefresh:)` (`PlayerViewModel.swift:7-10`; the protocol carries `kind:`, which `OfflineResolver` accepts and ignores). `OfflineResolver` returns, without network: `.hls` movpkg → `Resolved(stream: .hls(url: localURL, isLive: false, audioOnlyURL: nil, captionTracks: []), client: .visionos, userAgent: "", resolvedAt: now, expiresAt: nil)`; `.mp4` → `.progressive(url: localURL, label: qualityLabel)`; `.m4a` → `.progressive` likewise (an audio-only item renders the player with no video — acceptable, Android's offline open does the same via the system player; the metadata area carries the screen). `expiresAt: nil` means the TTL-refresh path never fires (`Resolved.expiresAt` guard, `Models.swift:65-66`); recovery re-resolves route back into `OfflineResolver`, which is idempotent. `PlayerArgs` gains `var offlineItemId: String? = nil` (Hashable-safe); when set, `PlayerScreen` builds the VM over `OfflineResolver` and passes a presentation flag that hides quality, cast, and the Save button. No new `StreamState` case, so CF-B2-4's policy switch and `NowPlayingSnapshot` keep working unmodified.

**6. The cellular gate is applied at task creation, not "updated live".** Spec §11 says `allowsCellularAccess` "updated live when the setting changes" — but a background `URLSessionConfiguration` is immutable after session creation, and per-request `allowsCellularAccess` is read at task creation. Ponytail: the setting is read when a task is created or resumed; flipping Wi-Fi-only ON while a save is running pauses running cellular tasks (the manager observes the setting through the store it's handed and acts on the next state change notification it gets — a `@MainActor` observation forwarding into the actor); flipping OFF lets `queued` items start on cellular at their next scheduling pass. The pure decision (`OfflineStateMachine.allowedToRun(status:wifiOnly:isOnCellular:)`) is what the "cellular gate" test pins; the observation glue is thin.

**7. One revalidation mechanism serves removal AND gate flips.** The sweep (launch + `willEnterForeground`, the `DownloadExpiryPolicy.kt:23-28` cadence shared with the remote-config refresh hook in `FitrahTubeApp`) walks completed items with the SAME `OfflineGateClient.video(_:)` call the save gate uses: 410/404 → catalog removal → delete files + row (the ruling's auto-delete); 200 with `offlineAllowed == false` → gate flipped → delete (fork C; complaint-shaped semantics — an admin turning the flag off is the same-day remedy path, and a lingering copy defeats it); transport error → keep (fail-open — never mass-delete a library because the phone was offline; the next successful sweep catches up). The 30-day TTL check runs in the same pass, before any network. Expiry and revalidation are ONE sweep function, pure, tested with injected clocks and canned gate answers.

---

### Task 1: The §17 spike — does `AVAssetDownloadTask` take YouTube's HLS? (day 1, both outcomes planned)

**Goal:** Replace the spec's open question with a recorded, dated answer, and pick the engine set Task 4 builds. This task writes throwaway-quality harness code and ONE durable artifact: the `OfflineEngineSupport` decision record + its gated live test.

**Files:**
- Create: `ios/FitrahTubeTests/OfflineSpikeTests.swift` (live-gated, `OFFLINE_LIVE=1`)
- Create: `ios/FitrahTube/Features/Offline/OfflineEngineSupport.swift` (the decision, as code)

**Steps:**

- [ ] **Step 1: Write the harness.** An `OFFLINE_LIVE=1`-gated Swift Testing case in the app test target (it needs AVFoundation's download APIs, so it runs under `xcodebuild test`, not `swift test`). Using the container's real `resolver`, resolve `xc7keR2piUM` (approved catalog lecture; the no-music rule); take the `.hls` manifest URL from `Resolved`. Then:
  1. Construct `AVAssetDownloadURLSession` (background configuration id `spike.offline.hls`), an `AVURLAsset` over the manifest **with the resolved `userAgent` via `AVURLAssetHTTPHeaderFieldsKey`** (`DownloadWorker.kt:52-67`'s 403 lesson — a mismatched UA 403s every googlevideo fetch), and an `AVAssetDownloadTask` with `minimumRequiredMediaBitRate` ≈ 500_000.
  2. Run to completion or first error with a 120 s ceiling; log `didFinishDownloadingTo`, the `.movpkg` size, and any error verbatim.
  3. If a `.movpkg` lands: play it back with `AVPlayer(url:)` and assert a non-zero `duration` loads — the round trip is the whole question.
- [ ] **Step 2: Run it on the simulator** (`xcodebuild test -only-testing:FitrahTubeTests/OfflineSpikeTests …` with `OFFLINE_LIVE=1`; prove non-vacuity per CF-B3-12 — run the whole class, check the log shows the task actually started). Three possible outcomes, all planned:
  - **A. Downloads and plays** → HLS engine is IN for Task 4; picker offers audio-only + 360/480/720/1080 tiers.
  - **B. The API itself refuses on simulator** (the long-standing `AVAssetDownloadTask`-unsupported-in-simulator behaviour — contradiction 6): the question is UNANSWERED, not answered "no". Record outcome B; Task 4 builds the progressive engine as primary AND the HLS engine behind the same `OfflineEngine` protocol, compile-complete but ungated-live; the picker ships the 360p-ceiling shape; the device checklist gains "run OfflineSpikeTests on hardware; if A, flip `OfflineEngineSupport.hls` and the picker follows". USER-BLOCKED (no signing).
  - **C. Runs but YouTube's packaging is rejected** (task errors on the manifest/segments) → the spec's named fallback: progressive-only, itag 18 + itag 140, 360p ceiling stated in the picker; HLS engine not built (delete the seam's second conformer, keep the protocol).
- [ ] **Step 3: Encode the outcome** as `OfflineEngineSupport` — a tiny enum + `static let current` with the dated finding in a doc comment (the CF-B3-9 idiom: record what was OBSERVED, not what was predicted), consumed by Task 4's engine selection and Task 5's picker. Keep the live test in the tree, `OFFLINE_LIVE`-gated, as the re-measurement instrument.
- [ ] **Step 4: Gate** (`ios/scripts/test.sh` — the gated test skips without the env var, so the gate stays hermetic). Commit: `[FEAT]: iOS offline spike, engine decision`.

**Acceptance:** the decision record exists with a dated observation; the live harness is runnable on demand; the plan's Task 4/5 forks are collapsed to one engine set.

---

### Task 2 (BACKEND, parallel-safe): `offlineAllowed` — model, API, admin toggle

**Goal:** A boolean an admin can set per video, default false, readable by the public video-detail endpoint. Minimal surface: no new endpoints, no ContentItemDto change (lists don't gate saves — skipped, say so in the commit), no bulk tooling.

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/model/Video.java` (field + getter/setter, `Boolean offlineAllowed`)
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java` (`updateVideo`, `:981`. **Two facts the implementer must not discover mid-task:** (1) the existing copy block is UNCONDITIONAL (`:1003-1011` — `existing.setTitle(video.getTitle())` etc., and `existing.setStatus(video.getStatus())` runs even when the null-guarded status validation above it was skipped), so a partial body `{"offlineAllowed": true}` would null out title/description/categoryIds/thumbnail/duration/viewCount and un-APPROVE the video via a null status; (2) **no frontend caller of this PUT exists today** — `youtubeService.ts` has only POST/PATCH and `contentLibrary.ts` has no update call — so its replace semantics protect nobody. **Decision: null-guard the copied fields in `updateVideo`** (merge semantics — the root-cause fix, one guard protecting every future caller, safe precisely because there is no existing caller to break), and the new admin toggle sends the partial body `{"offlineAllowed": …}` through this PUT. No new PATCH endpoint.)
- Verify (likely zero-change): `PublicContentService.getVideoDetails` (`:994`) returns the `Video` model → the field serializes automatically; `GET /api/v1/videos/{id}` 404/410 semantics unchanged
- Modify: `docs/architecture/api-specification.yaml` (the video-detail schema gains optional `offlineAllowed: boolean`; document default-false)
- Modify: `frontend/src/views/ContentLibraryView.vue` + `frontend/src/services/contentLibrary.ts` (a "Save for offline" toggle on the video edit affordance. **The service call must be BUILT, not wired** — no video-update call exists in the admin frontend today; add one that PUTs the partial `{offlineAllowed}` body to `/api/admin/registry/videos/{id}`, legal once the null-guarding above lands), `frontend/src/locales/messages.ts` (en/ar/nl labels)
- Create/modify tests: backend controller/service tests; a Vitest for the toggle wiring

**Red tests first:**
- Backend: `updateVideo` with `offlineAllowed: true` persists it; absent field leaves it unchanged; **a partial body `{"offlineAllowed": true}` does NOT wipe title/description/categoryIds/status/thumbnail/duration/viewCount** (the null-guard pin — this test fails against today's unconditional copies, which is the point); `getVideoDetails` response carries the field; a legacy document with no field reads as false/absent (Firestore null → the iOS client's absent→false rule holds end-to-end).
- Frontend: toggling calls the update service with the field; the toggle renders only for videos (not channels/playlists).

**Steps:**
- [ ] Write the failing backend tests → implement the model field + the null-guarded merge in `updateVideo` (every copied field, `offlineAllowed` included) + audit log entry (`video_updated_in_registry` already fires; ensure the field is in the copied set).
- [ ] Update `api-specification.yaml`; regenerate the TS schema (`./scripts/generate-openapi-dtos.sh`) so the admin build compiles against it. Do NOT run the Swift generation step — iOS reads via the hand-written client (reconciliation note 3); note this in the commit body.
- [ ] Write the failing Vitest → implement the Vue toggle with the three locales.
- [ ] Gates: `cd backend && ./gradlew test`; `cd frontend && npm test && npm run build`.
- [ ] Commit: `[FEAT]: Per-video offlineAllowed gate + admin toggle` (backend and frontend may be two commits if cleaner: `[FEAT]: offlineAllowed field on videos`, `[FEAT]: Admin Save-for-offline toggle`).

**Acceptance:** an admin can flip the flag in the Content Library; `GET /api/v1/videos/{id}` shows it; everything else in the API is byte-identical.

**Assumption stated:** no data migration — absent means false, and the iOS client treats absent as false, so the entire existing catalog is correctly "not saveable" the moment this lands (the ruling's default).

---

### Task 3: The pure engine — item model, schema V4, state machine, expiry+revalidation, storage math

**Goal:** Every decision Task 4's glue will execute, as `nonisolated` pure types with exhaustive tests. No `URLSession`, no AVFoundation, no network anywhere in this task.

**Files:**
- Create: `ios/FitrahTube/Features/Offline/OfflineItem.swift` (SwiftData `@Model` + `OfflineStatus` enum)
- Modify: `ios/FitrahTube/Persistence/FavoriteVideo.swift` (`FavoritesSchemaV4` + migration stage — the V2/V3 pattern verbatim: new entity, lightweight stage, `AppContainer.makeModelContainer` schema constant flips to V4, `AppContainer.swift:203`)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (schema constant; plus a lazy `offlineStore` following the `favorites` idiom, `AppContainer.swift:70`)
- Create: `ios/FitrahTube/Features/Offline/OfflineStateMachine.swift` (status transitions + the action matrix + the cellular gate decision)
- Create: `ios/FitrahTube/Features/Offline/OfflineSweep.swift` (expiry TTL + revalidation decisions, pure — reconciliation note 7)
- Create: `ios/FitrahTube/Features/Offline/OfflineStorage.swift` (file layout, directory URL, per-item size accounting, footer math)
- Create: `ios/FitrahTube/Features/Offline/OfflineStore.swift` (`@MainActor @Observable` SwiftData store, the `SwiftDataFavoritesStore` idiom)
- Create: `ios/FitrahTubeTests/OfflineEngineTests.swift`, `ios/FitrahTubeTests/OfflineStoreTests.swift`

**Interfaces:**
- `OfflineItem`: `id: String` (UUID), `videoId: String` (`#Unique` on `videoId` — ONE saved copy per video; a re-save at a different quality replaces. Deviation from Android's `playlistId|quality|videoId` dedupe, which exists for bulk playlist saves this plan defers — fork F), `title`, `channelName: String?`, `thumbnailUrl: String?`, `qualityLabel: String`, `audioOnly: Bool`, `status: String` (raw of `OfflineStatus`), `bytesWritten: Int64`, `totalBytes: Int64?`, `errorCode: String?`, `localPath: String?` (relative to the offline directory — never absolute; the container path changes across reinstalls), `resumeData: Data?`, `createdAt: Date`, `completedAt: Date?`.
- `OfflineStatus`: `queued | running | paused | completed | failed | cancelled` (spec §11 set).
- `OfflineStateMachine`: `transition(from:on:) -> OfflineStatus?` (nil = illegal, callers assert), `actions(for:) -> [OfflineAction]` (the row action matrix: running/paused/queued → Pause|Resume + Cancel; failed/cancelled → Retry + Remove; completed → Open + Delete — `DownloadsAdapter.kt:96-125`), `allowedToRun(wifiOnly:isOnCellular:) -> Bool`.
- `OfflineSweep.decide(item:now:gate:) -> SweepAction` where `SweepAction ∈ {keep, deleteExpired, deleteRemoved, deleteGateRevoked}` and `gate` is a canned `GateAnswer ∈ {allowed, notAllowed, gone, unreachable}` — TTL 30 d + 1 h grace (`DownloadExpiryPolicy.kt:23-28`) checked first, network answer applied per reconciliation note 7.
- `OfflineStorage`: `directoryURL(base:)` = `Application Support/offline/`, `fileName(itemId:kind:)` (`.movpkg`/`.mp4`/`.m4a`), `usedBytes(items:)`, `footer(count:used:available:)` composed via `Format` (the "%d saved • %@ used • %@ available" shape, re-keyed in Task 5).

**Red tests first (write ALL, watch them fail to compile/assert, then implement):**
- Migration: build a V3 store in memory with a favorite + saved playlist + subscription, open it under the V4 plan, assert all rows survive and an `OfflineItem` inserts (the pattern `AppContainerTests` already exercises for recovery — extend, don't duplicate).
- State machine: the full legal-transition table (queued→running→completed; running→paused→running; running→failed→queued on retry; anything→cancelled except completed; completed is terminal but deletable) and at least three illegal ones returning nil.
- Action matrix: exact action set per status (pins the Saved screen's rows AND the no-share/no-export constraint — `completed` yields Open+Delete and nothing else).
- Cellular gate: 4-row truth table.
- Sweep: expired-under-grace kept; expired-past-grace deleted regardless of gate answer; `gone` → `deleteRemoved`; `notAllowed` → `deleteGateRevoked`; `unreachable` → `keep`; fresh+allowed → `keep`.
- Storage: relative-path round trip; footer string uses `Format` (RTL-safe numerals).

**Steps:**
- [ ] Red tests → implement → `ios/scripts/test.sh` green.
- [ ] Commit: `[FEAT]: iOS offline engine core + schema V4`.

**Acceptance:** §16's "state machine transitions and action matrix, expiry sweep" tests exist and pass; the V4 migration is pinned; nothing yet touches the network or the UI.

---

### Task 4: `OfflineManager` — resolve, download, persist, survive relaunch

**Goal:** The actor that turns a save request into bytes on disk, honouring the rate limiter, the cellular gate, the ≥10 min expiry guard, the one-re-resolve-on-403 rule, and background-session re-attach. Thin glue over Task 3's decisions; the engine choice comes from Task 1's `OfflineEngineSupport`.

**Files:**
- Create: `ios/FitrahTube/Features/Offline/OfflineManager.swift` (actor) + `OfflineEngine.swift` (the per-rung engine protocol + `ProgressiveEngine`; `HLSEngine` iff Task 1 outcome A or B)
- Modify: `ios/FitrahTube/App/OrientationLock.swift` (`AppDelegate:44` gains `application(_:handleEventsForBackgroundURLSession:completionHandler:)` forwarding to the manager)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (lazy `offlineManager`, wired to `innerTube` (`AppContainer.swift:81`), `offlineStore`, `settings`, `network`)
- Create: `ios/FitrahTubeTests/OfflineManagerTests.swift`

**Interfaces:**
- `nonisolated protocol OfflineSaving: Sendable` (spec §5 names `DownloadManaging`; renamed with the rest): `save(videoId:quality:audioOnly:metadata:) async`, `pause(_:)`, `resume(_:)`, `cancel(_:)`, `retry(_:)`, `delete(_:) async` (removes files then the row — the "via the manager, not the file system" rule), `reattach() async` (relaunch), `sweep() async` (Task 7 calls it).
- Resolve path: `rateLimiter.check(videoId, kind: .prefetch, now: clock.now)` (`InnerTube.rateLimiter`/`InnerTube.clock` — reuse the ONE clock, `InnerTube.swift:17-20`); `.delayed/.blocked` → stay `queued`, schedule retry at `retryAfter` (reconciliation note 4); `.allowed` → `resolver.resolve(videoId, purpose: .prefetch, sourceChannelId: nil, forceRefresh: false)`. `.embed` outcome → `failed(notSaveable)` terminal. `.hls`/`.progressive` → check `expiresAt` leaves ≥10 min, else re-resolve `forceRefresh: true` once; hand to the engine with the resolved `userAgent` as a header. **CF-G-14 interaction, decided:** a background save walk that bot-checks arms the SAME persisted `StreamResolver` cooldown the player self-gates on (`StreamResolver.swift:128-134`) — an invisible background action can lock the user's next playback tap for an hour. **Accepted, recorded as CF-D-9**: preventing it needs a per-purpose no-trip flag inside the resolver (a package change with its own policy questions), and the exposure is bounded — the cache-first path means the common save never POSTs, and the per-walk dedupe caps it at one trip. The REVERSE direction is handled here: a save attempted during an active cooldown gets `ExtractionError.cooldown` and fails fast back to `queued` with its retry scheduled at the cooldown's end — never a failed row, never a retry into the cooldown.
- Engines: `ProgressiveEngine` — background `URLSessionDownloadTask` (itag 18 for video, `Resolved`'s `.hls` `audioOnlyURL` itag 140 for audio-only; an audio-only save therefore requires the visionos rung to have succeeded — if the ladder lands on `.progressive`, audio-only is unavailable and the picker said so, Task 5); `taskDescription = item.id` (the re-attach key); resume data captured on pause/failure into `OfflineItem.resumeData`. `HLSEngine` — `AVAssetDownloadURLSession` + `minimumRequiredMediaBitRate` per tier, `.movpkg` location persisted on finish.
- 403 mid-download (`DownloadWorker.kt:266-276`): one re-resolve (`forceRefresh: true`), restart the fetch with the fresh URL; resume data from the old URL is discarded (it names the dead URL — the "partial across restarts with an expired URL" answer: re-attach first, and when the resumed task 403s, this path restarts from zero, Android parity). A second 403 → `failed(HTTP_403)`.
- Error codes kept from `DownloadErrorCode.kt:12-39`: `HTTP_403, HTTP_429, NETWORK, NO_STREAM, INVALID_INPUT, UNKNOWN` + `NOT_SAVEABLE`; MERGE/NO_COMPATIBLE_VIDEO/VIDEO_AUDIO_MISMATCH not ported (no FFmpeg).
- Serial execution: ONE active download at a time (`ponytail:` comment at the scheduler — per-item concurrency if saves queue up in practice; a serial queue is the rate-limit-friendly floor and matches the single background session's re-attach simplicity).
- On first file write: create `Application Support/offline/` and set `URLResourceValues.isExcludedFromBackup = true` on the directory.

**Red tests first** (the manager takes protocol seams for the resolver, limiter-check closure, and an `OfflineTransport` abstraction over the download-task layer so tests inject canned completions — the background `URLSession` itself is glue, tested live):
- A save with a cache-warm resolver reaches the engine without a limiter permit being denied; a `.blocked` decision leaves the item `queued` with a scheduled retry (assert no engine call).
- Embed outcome → `failed`, `errorCode == "NOT_SAVEABLE"`, no engine call.
- `expiresAt` < 10 min → exactly one `forceRefresh` re-resolve before the engine.
- `ExtractionError.cooldown` from the resolver → item stays `queued`, retry scheduled at the cooldown's end, no `failed` row (the CF-D-9 reverse direction).
- 403 completion → one re-resolve → restart; second 403 → `failed(HTTP_403)`.
- Pause captures resume data into the row; resume hands it back; cancel/delete remove the row (and the file, via a temp-dir fixture).
- `reattach()` re-binds by `taskDescription` and marks orphaned `running` rows (no live task) as `paused`-with-resume-data or `queued`.
- Wi-Fi-only ON + cellular → new tasks refused by the pure gate (already pinned in Task 3; here assert the manager consults it at creation, reconciliation note 6).

**Steps:**
- [ ] Red tests → implement → gate green.
- [ ] Live smoke (simulator, network): `OFFLINE_LIVE=1` case in `OfflineManagerTests` — save `xc7keR2piUM` audio-only (small), assert a playable `.m4a` lands and the row goes `completed`. This is the §15 "simulator download" gate evidence.
- [ ] Commit: `[FEAT]: iOS OfflineManager + progressive engine` (and `+ HLS engine` if Task 1 said A).

**Acceptance:** §16's "resume-data round trip, cellular gate" pinned; a real save completes on the simulator; relaunch re-attach logic tested; no UI yet.

---

### Task 5: Gating + Save affordance + strings + the kill-switch

**Goal:** The user can save a gated video from the main player; an ungated/unknown video shows nothing; the remote config can turn the whole feature off. Every new string obeys the naming ruling.

**Files:**
- Modify: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/RemoteConfig.swift` (add `public var downloadsEnabled: Bool?` — the `featuredCategoryId` optional-field pattern verbatim, `RemoteConfig.swift:48-51`; nil ⇒ enabled, so a pre-Phase-3 persisted last-known-good config doesn't dark the feature; `sanitized` untouched — a Bool needs no sanitizing)
- Modify: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift` (decode with/without the field; last-known-good round trip)
- Modify: `ios-remote-config.json` (repo root: add `"downloadsEnabled": true` — reviewed-ON; live only after the next merge to `main`, the CF-C-1 mechanics)
- Create: `ios/FitrahTube/Features/Offline/OfflineGateClient.swift` (reconciliation note 3; the `PublicHeaders` shape — `ios/FitrahTube/Catalog/PublicHeaders.swift` — with `X-Device-Id`)
- Create: `ios/FitrahTube/Features/Offline/OfflineQuality.swift` (picker model: audio-only first, then tiers per `OfflineEngineSupport` — 360/480/720/1080 for HLS, else "Standard quality (360p)" with the ceiling stated, spec §17 risk row; preselect from `SettingsStore.downloadQuality` low/medium/high → 360/720/1080, `DownloadQualityDialog.kt:64-114`)
- Create: `ios/FitrahTube/Features/Offline/SaveOfflineSheet.swift` (a sheet, NOT `confirmationDialog` — CF-B3-11)
- Modify: `ios/FitrahTube/Features/Player/PlayerToolbar.swift` (fourth button after favorite/share/report, `PlayerToolbar.swift:20-28`; states: hidden (gate unknown/false, or `downloadsEnabled == false`) / picker (saveable, no item) / progress (item `queued|running|paused` — live `bytesWritten` fraction) / Open (completed → pushes the offline player route); the `favoriteButton` label+value a11y idiom)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (fetch the gate once per open via `OfflineGateClient`, hold it in the VM/screen state; wire the sheet + manager calls)
- Modify: `ios/scripts/convert-strings.py` (`REFUSE` += the five settings keys (Global Constraints list, `settings_download_quality_title` included); `EXTRA_KEYS` += their re-authored values and the new `offline_*` keys — en/ar/nl each: `offline_save` "Save for offline", `offline_saved_title` "Saved", `offline_not_saveable` "This video can't be saved for offline", `offline_quality_title`, `offline_quality_audio_only`, `offline_quality_standard_ceiling`, `offline_status_*` (queued/saving/paused/completed/failed/cancelled), `offline_error_*` (403/429/network/no_stream/invalid/unknown — Android's `download_error_*` meanings re-authored without "Download"), `offline_empty_state`, `offline_footer_format`, `offline_action_*` (pause/resume/cancel/retry/remove/open/delete — reuse Android's generic `cancel`/`retry` where they exist in the catalog; author only what's missing), `settings_offline_storage`, `settings_offline_clear`, `settings_offline_clear_confirm`)
- Create: `ios/FitrahTubeTests/OfflineGateTests.swift`, `OfflineQualityTests.swift`; modify `ios/FitrahTubeTests/` strings test if one pins catalogs

**Red tests first:**
- `RemoteConfigTests`: a config without `downloadsEnabled` decodes nil; nil reads as enabled at the call site helper; `false` round-trips through last-known-good.
- `OfflineGateTests` (canned `HTTPTransport`): 200 `{"offlineAllowed":true}` → saveable; 200 without the field → not; 404/410/transport-error → not (fail-closed, note 3); the request carries `X-Device-Id`.
- `OfflineQualityTests`: option order; preselect mapping; the progressive-only shape states the ceiling.
- Button-state mapping test (pure): (gate, config, item-status) → button state table.
- Strings: the re-authored `settings_*` en values contain "offline" and not "Download"; every `offline_*` en/ar/nl value exists and none contains "Download"/"ad-free" (a loop over the authored `EXTRA_KEYS` — the pin the naming ruling asked for).
- Converter: `python3 ios/scripts/convert-strings.py` regenerates; `--check` green; `git diff` shows exactly the intended keys.

**Steps:**
- [ ] Red tests → implement RemoteConfig field (package first — it's the only package edit; keep it one commit so InnerTubeKit history stays clean: `[FEAT]: RemoteConfig downloadsEnabled flag`).
- [ ] Implement client/picker/sheet/toolbar; regenerate strings; gate green.
- [ ] Commit: `[FEAT]: iOS Save-for-offline affordance + gating`.

**Acceptance:** on a fixture container, the button renders per the state table; with the live backend (Task 2 deployed) flipping the admin toggle flips the button on next player open; `downloadsEnabled: false` in a debug-served config (the `-fitrah-remote-config-url` rig, `AppContainer.swift:89-99`) hides every save affordance; all new copy is "Save for offline"-shaped in en/ar/nl.

---

### Task 6: The Saved screen, `Route.offline`, entry points, settings rows

**Goal:** The library: rows with live progress and the action matrix, the storage footer, entry points, and the Settings section reshaped to the ruling's naming.

**Files:**
- Create: `ios/FitrahTube/Features/Offline/SavedScreen.swift` (+ row view; assembly over `OfflineStore` + Task 3's matrix + Phase-1 components `RemoteImage`/`EmptyStateView` — reuse, don't rebuild)
- Modify: `ios/FitrahTube/App/Route.swift` (add `case offline`), `ios/FitrahTube/Features/Shell/MainShellView.swift` (the ONE compile error the new case produces — `destination(for:)` is exhaustive with no `default:`, `MainShellView.swift:138-163`; add the `SavedScreen` arm)
- Modify: `ios/FitrahTube/Features/Home/HomeView.swift` (toolbar entry icon, `arrow.down.circle` project-local semantics — hidden when `downloadsEnabled == false`)
- Modify: `ios/FitrahTube/Features/Settings/SettingsView.swift` (the `.downloads` section — `SettingsView.swift:11,28,81` — keeps `downloadQuality` + `wifiOnly` rows (now re-authored strings) and gains: Saved library row (pushes `.offline`), Storage row (used/available via `OfflineStorage`), Clear row (confirmation `.alert` — CF-B3-11 — deleting via `OfflineManager.delete`, never the file system))
- Create: `ios/FitrahTubeTests/SavedScreenTests.swift`; modify `ios/FitrahTubeUITests/ScreenshotTests.swift` + `ios/scripts/screenshots.sh` (a `phase3-saved` case: seeded fake items across all six statuses, en + ar)

**Red tests first:**
- The `MainShellView` arm pin (a `Route.offline` destination is `SavedScreen` — the `MainShellRoutingTests` idiom that already pins the Plan C arms).
- Row action set per status delegates to Task 3's `actions(for:)` (assert the view consults the ONE matrix — no second switch).
- Sort: alphabetical by title (`DownloadsFragment` parity); empty state uses `offline_empty_state`.
- Footer math renders through `Format` (ar numerals).
- Settings: Clear invokes the manager once per item (spy manager), not `FileManager`.

**Steps:**
- [ ] Red tests → implement → gate green.
- [ ] Screenshots: run the `phase3-saved` block by hand (single `-only-testing:` invocation — the screenshots.sh scoping trap from Plan C constraints); eyeball en + ar, phone + iPad.
- [ ] Commit: `[FEAT]: iOS Saved screen + settings rows`.

**Acceptance:** seeded fake items render the full matrix; live: a Task 4 save appears with moving progress; entry points appear/disappear with the kill-switch; no Library rows (Favorites/history — Me tab, Phase 4, contradiction 1).

---

### Task 7: Offline playback + the revalidation sweep + compliance pins

**Goal:** Open plays in-app with no resolver and no save/quality/cast controls; the sweep enforces TTL, catalog removal, and gate flips; the ruling's storage constraints are pinned by tests.

**Files:**
- Create: `ios/FitrahTube/Features/Offline/OfflineResolver.swift` (reconciliation note 5)
- Modify: `ios/FitrahTube/App/Route.swift` (`PlayerArgs` gains `var offlineItemId: String? = nil` — additive, Hashable-safe, nil on every existing construction; CF-C-9's all-optionals-nil property preserved)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (when `offlineItemId` set: build the VM over `OfflineResolver`, presentation flags hide quality menu, cast button, save button; Up Next/queue disabled), `ios/FitrahTube/Features/Player/PlayerToolbar.swift` (offline flag hides Save; favorite/share/report still work — sharing the *link* is allowed and unchanged, only media files never leave)
- Modify: `ios/FitrahTube/App/FitrahTubeApp.swift` (the launch/`willEnterForeground` hook that already drives `refreshRemoteConfigIfDue` (`FitrahTubeApp.swift:89`) also fires `offlineManager.sweep()`)
- Modify: `ios/FitrahTube/Features/Offline/OfflineManager.swift` (`sweep()` = Task 3's `OfflineSweep.decide` per completed item, gate answers from `OfflineGateClient`, deletions via the manager's own `delete`)
- Create: `ios/FitrahTubeTests/OfflinePlaybackTests.swift`, `ios/FitrahTubeTests/OfflineComplianceTests.swift`

**Red tests first:**
- `OfflineResolver` maps `.movpkg`→`.hls`, `.mp4`→`.progressive`, `.m4a`→`.progressive`, `expiresAt == nil`; a missing file throws → the player's existing `.contentUnavailable` path (assert the mapped `ExtractionError`).
- PlayerScreen offline presentation: quality/cast/save absent, favorite/share/report present (view-model-level flags pinned; the UI matrix carries the rest).
- Sweep integration: canned gate per reconciliation note 7's table; deletions go through `delete` (file + row together, spy-verified); `unreachable` deletes nothing.
- Compliance: Info.plist (via `Bundle.main`) contains neither `UIFileSharingEnabled` nor `LSSupportsOpeningDocumentsInPlace`; after a manager write, the offline directory's `isExcludedFromBackup` resource value is true; `OfflineStateMachine.actions(for: .completed) == [.open, .delete]` (re-pinned here as the no-export invariant with a comment naming the ruling).
- Sweep cadence: the app hook fires sweep alongside the config refresh (extract the decision as a free function like `isRemoteConfigRefreshDue`, `FitrahTubeApp.swift:111` — same testable shape).

**Steps:**
- [ ] Red tests → implement → gate green.
- [ ] Live: save audio-only on the simulator, airplane-mode the Mac's network off (Network Link Conditioner or just disable Wi-Fi), open from Saved → plays. This is the §15 "offline play" gate evidence.
- [ ] Commit: `[FEAT]: iOS offline playback + revalidation sweep`.

**Acceptance:** offline open plays with the reduced chrome; a video deleted from the admin catalog disappears from Saved on next foreground (verifiable live against the dev backend); the compliance pins are green.

---

### Task 8: Cast + AirPlay — the fetch script Phase 0 owed, the controller, the acceptance pass

**Goal:** `GCKUICastButton` on the main player, the spec §10 session lifecycle, the mini controller, and the AirPlay 403→mirroring fallback. Device verification is USER-BLOCKED and said so.

**Files:**
- Create: `ios/scripts/fetch-cast-sdk.sh` (curl Google's **dynamic** XCFramework zip for Cast iOS Sender **4.8.6** — the spec-pinned version, sources line `:344` — into `ios/Vendor/`, unzip, verify the expected `.xcframework` paths exist, idempotent/no-op when present)
- Modify: `.gitignore` (`ios/Vendor/`), `ios/scripts/test.sh` (a pre-stage: run `fetch-cast-sdk.sh` if `ios/Vendor` is missing — the gate gains a one-time network fetch, cached thereafter; say so in a script comment)
- Modify: `ios/project.yml` (framework dependency on the vendored `GoogleCast.xcframework` (+ `GoogleCastCore`/protobuf members as shipped in the zip — verify against the actual archive contents, not this plan); Info.plist properties: `NSLocalNetworkUsageDescription` (en value via `InfoPlist.strings` if localizing — minimum: a clear en sentence naming TV playback), `NSBonjourServices: [_googlecast._tcp, _CC1AD845._googlecast._tcp]` — receiver `CC1AD845`, `CastOptionsProvider.kt:36-42`)
- Create: `ios/FitrahTube/Features/Player/CastController.swift` (`@MainActor @Observable`; owns `GCKCastContext` setup — called from `AppDelegate` launch, guarded so a missing/failed SDK leaves `castAvailable == false` and NOTHING else references the SDK, spec §10's "not loaded at all" clause), `CastMedia.swift` (pure: `Resolved` + `PlayerArgs` → a `CastMediaInfo` value — contentURL, contentType `application/x-mpegurl`|`video/mp4`, `.live` stream type for live, title/channel/thumbnail — mapped to `GCKMediaInformation` in one thin `@MainActor` function)
- Modify: `PlayerToolbar.swift` (cast button, shown only when `castAvailable` and not offline), `PlayerScreen.swift`/`PlayerViewModel.swift` (session start/resume → **fresh** resolve (`forceRefresh: true`, `.player` kind — a user-initiated cast is a manual action) → load with local position → pause local; load failure → transient banner `offline_cast_failed`-style key… **naming check:** key is `cast_error_format` "Couldn't play on %@" (new EXTRA_KEY, en/ar/nl); session end → seek local to `approximateStreamPosition`, resume — **popped-route case decided:** the mini controller outlives the player route, so a session can end with no `PlayerScreen` mounted; the hand-back then silently does nothing (no local player to seek — deliberate, say so in a comment, do not resurrect the route)), `MainShellView.swift` (mini controller `GCKUIMiniMediaControlsViewController` pinned above the tab bar while a session is active)
- Modify: `ios/FitrahTube/Features/Settings/AboutView.swift` (the IPv4-NAT ceiling sentence in Help text — spec §10's "documented ceiling"; new key `cast_help_network`)
- AirPlay: `PlayerHostView.swift` — stock transport already shows the route picker (`PlayerHostView.swift:9`); add `player.allowsExternalPlayback = true` explicitly (today it's the default — make it a written decision), and the fallback: on item failure **while an external playback route is active** (`player.isExternalPlaybackActive`), set `allowsExternalPlayback = false` and retry once so video mirrors (spec §10 AirPlay paragraph); route through the existing recovery machinery, not a new path — specifically, the fallback decision is consulted in `handleRecoveryEvent` BEFORE spending any `PlaybackRecovery` budget (the pure (failure, externalActive, alreadyFellBack) table is a pre-check, NOT a fourth `PlaybackRecovery` action; a 403 caused by the external route's IP mismatch must not burn the retry/re-resolve budgets that exist for genuinely broken streams)
- Create: `ios/FitrahTubeTests/CastMediaTests.swift`, `AirPlayFallbackTests.swift`

**Red tests first:**
- `CastMedia`: HLS → `application/x-mpegurl`; itag-18 progressive → `video/mp4`; live → `.live`; embed → nil (never castable — assert, it's the no-hand-off directive's cast-shaped edge); metadata fields carried.
- AirPlay fallback: pure decision test — (failure, externalActive, alreadyFellBack) → fallback-once table.
- Cast availability: with no context created, `castAvailable == false` and the toolbar hides the button (fixture container).
- The offline player hides cast (already pinned in Task 7 — extend if the flag moves).

**Steps:**
- [ ] Write `fetch-cast-sdk.sh`; run it; wire `project.yml`; `xcodegen generate`; prove the app still builds AND that a checkout without `Vendor/` heals itself through the test.sh pre-stage.
- [ ] Red tests → implement controller/toolbar/mini-controller/fallback → gate green (`ios/scripts/test.sh` — watch the 300 s margin; the Cast SDK lengthens the build. If the Release-build stage trips the watchdog, report it — do not silently raise the timeout).
- [ ] Simulator sanity: cast button appears when a Chromecast is discoverable on the Mac's network (may work in simulator via the Mac's Bonjour — if not, that's expected; record what was observed).
- [ ] Screenshots: `phase3-player-save-cast` case (toolbar with all five buttons, en + ar).
- [ ] Commit: `[FEAT]: iOS Chromecast + AirPlay fallback` (the fetch script may be its own `[CHORE]: Cast SDK fetch script` commit first).

**Acceptance:** builds green with the vendored SDK from a clean fetch; pure mappings pinned; every device-dependent behaviour is on the USER-BLOCKED list below, not claimed.

---

## Acceptance tiers — what proves what

**Tier 1, hermetic (the gate, every task):** all pure tests above; fixture-driven screens; the compliance pins.

**Tier 2, live-on-simulator (`OFFLINE_LIVE=1` / `C_LIVE`-style, run at Task 4/7/8 and once at the end):**
1. Task 1 spike (records outcome A/B/C).
2. Save audio-only + 360p progressive for an approved lecture id live; rows complete; files playable — the §15 "simulator download" gate half.
3. Offline play with networking disabled — the "offline play" half.
4. Gate round trip against the dev backend: admin toggle ON → button appears; OFF → hidden; delete from catalog → sweep removes the saved copy on foreground.
5. Kill-switch: debug-served config with `downloadsEnabled: false` hides every affordance; flipping back restores (no restart needed beyond the config refresh).
6. Rate limiting: enqueue 3 saves of one id rapidly; assert exactly the permitted resolves fire (log-level check).

**Tier 3, USER-BLOCKED (no signing identity — `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` unset; plus the owner's network):**
1. **Cast verified on the user's network** (the §15 gate item): discovery, load, position hand-back on session end, mini controller, the IPv4-NAT failure mode on an IPv6 network if available.
2. AirPlay to a real device incl. the 403→mirroring fallback.
3. `AVAssetDownloadTask` spike re-run on hardware if Task 1 recorded outcome B; flip `OfflineEngineSupport` per the result.
4. Background-session completion while the app is suspended/killed; relaunch re-attach on hardware.
5. Cellular gate on a real cellular path.
6. The standing B2/B3/B4/B5 device checklists this phase inherits (PHASE2-CARRYFORWARDS "USER-BLOCKED" sections).

**Live-ops note:** Task 2's admin toggling and Task 7's sweep tests against the dev/prod backend mutate real registry rows — use a dedicated test video the owner names, or the dev emulator backend; never flip flags on real curated content without restoring them.

---

## Out of scope (deliberate, say-so-in-review items)

- **Playlist bulk save** (spec §11's `playlistId` dedupe + `PlaylistDetailFragment.kt:693-732` port) and the playlist action-bar Save-offline cell — fork F. `OfflineItem` deliberately has no `playlistId`.
- **Shorts-rail Save button** — fork F. The rail ships 4 controls + kebab; gating adds a fetch per Short.
- **Completion notification** (`UNUserNotificationCenter`) — fork E.
- **Offline captions/media-selections** in the HLS engine — carry-forward; `AVAssetDownloadConfiguration` supports it when wanted.
- **The Library rows** (Favorites count / Recently watched / History) on the Saved screen — moved to the Me tab, Phase 4 (contradiction 1).
- **The app-scoped player holder** (CF-B2-1/CF-G-1) — Cast/AirPlay do not need it: casting pauses the local player through existing seams, and AirPlay is AVKit-internal. Phase 3 is NOT its natural home; it remains with the queue/holder work. Phase 3 must not regress the interim behaviours (nothing here mounts a second player: the offline player replaces the resolver inside the same `PlayerScreen`).
- **CF-G-2** (lock-screen next/prev) — untouched here; still unowned.
- **Multiple saved qualities per video** — `#Unique` on `videoId`; a re-save replaces.
- **Editing the spec/`ios-app-plan.md`** to resolve contradictions 1–2 — flagged, owner's documents.

---

## Carry-forwards out of Phase 3 (CF-D-*)

- **CF-D-1:** `OfflineEngineSupport`'s decision may be simulator-limited (Task 1 outcome B) — the HLS engine's live proof and the picker's tier set are provisional until the hardware re-run.
- **CF-D-2:** `downloadsEnabled` and the Task 5 config edit are inert until `feature/ios-app` merges to `main` (the CF-C-1 raw-URL mechanics). Release checklist: confirm the raw config serves the field before submission; Notes for Review must describe the feature with specificity (2.3.1 — the ruling's own line).
- **CF-D-3:** the sweep's fail-open on transport means a device that never regains network keeps its copies past a gate flip. Accepted (reconciliation 7); the 30-day TTL is the offline bound.
- **CF-D-4:** `.prefetch` lane sharing between Up Next prefetch and saves (reconciliation 4) — if saves visibly starve Up Next warming (or vice versa), that is the evidence for fork B's `.download` case.
- **CF-D-5:** serial download queue (`ponytail:` at the scheduler) — per-item concurrency when someone actually queues enough saves to care.
- **CF-D-6:** the gate fetch is per-player-open with no cache; if it shows up in latency traces, a small TTL cache keyed by videoId is the upgrade.
- **CF-D-7:** Cast SDK in the gate — test.sh's fetch pre-stage adds a first-run network dependency; if CI ever runs this, mirror the zip.
- **CF-D-8:** `contentItem`-level `offlineAllowed` (lists) skipped — needed only if a future surface wants save badges on rows.
- **CF-D-9:** a bot-checked background save walk arms the shared persisted resolver cooldown and can lock the next playback tap (Task 4's accepted CF-G-14 interaction). If live evidence shows saves tripping it in practice, the fix is a per-purpose no-trip flag in `StreamResolver` — decide it together with CF-G-14's own pending owner decision, not separately.
- **CF-D-10 — CLOSED (`6433ee3b`):** `ProgressiveEngine` now keeps a per-id walk generation under `stateLock`, bumped by every `start`/`resume`/`pause`/`cancel` and carried in each chunk's `taskDescription` as `<id>#<generation>` (replacing the row-keyed and task-keyed stop sets). A delegate callback issued under a superseded generation does nothing at all — no byte appended, no next chunk, no `.progress`/`.finished`/`.failed` — so the cancel-then-restart straggler, a paused task's late `.cancelled`, and the relaunch orphan racing a fresh `engine.start` all die where they are. An unknown generation is adopted, so a background relaunch's re-delivered chunk still continues the same `.tmp`. `engine.start` with the same `{url, userAgent}` token resumes the partial instead of deleting it, and `wifiOnly()` is read before the final `stillCurrent` guard. Residual (not a correctness gap): a relaunch that re-resolves to a different URL before the orphan finishes re-downloads the bytes — splicing two resolves is not provably safe.
- **CF-D-11:** `StreamResolving`'s 6-arg default-forwarding means a future conformer implementing only the 5-arg form silently drops `requiresMuxed` (review F6). Mitigated by the doc comment + `RecordingResolver` pinning the flag; keep offline integration fakes asserting it.
- **CF-D-12 (device QA):** Saved's Open / the toolbar's Open push a second `.player` over a playing online player; whether the covered `PlayerHostView` pauses on cover is iOS-version-dependent and unverified on hardware (Task 7 review F1). If a device doubles the audio, pause the covered player on cover. Stacked players pre-date Phase 3; Open makes it one tap.
- **CF-D-13:** `FitrahTubeApp`'s sweep wiring (`Task { await offlineManager.sweep() }` beside the config refresh) is untested view-layer glue — deleting the line fails no test (Task 7 review F3). The due-decision itself is pinned; accept, or pin via a launch-hook seam if it ever regresses.
- **CF-D-14:** sweep skips a completed row with nil `completedAt` forever (unreachable today — `.finished` always sets it); defensive gap only (Task 7 review F4).
- **CF-D-15 (device QA):** the cast slot is a combined accessibility element wrapping `GCKUICastButton`; VoiceOver activation of the merged action and the announced state are unverified without a device (Task 8 review I3/⚠️). Joins Tier 3 items 1-2 (discovery, load, hand-back, mini controller, AirPlay + the 403→mirroring fallback on hardware).
- **CF-D-16:** the AirPlay mirroring fallback latches once per stream — re-picking AirPlay on the same video after a fallback gets no second attempt (`ponytail:` in `PlayerViewModel`); lift it only with a device repro that wants one.
- **CF-D-17:** the cast slot's caption can outlive the SDK's own glyph after the first tap on a network with no receiver (`ponytail:` in `PlayerToolbar`); hiding it needs a `castState` observer on `CastController` — the same observer the mini controller's SDK `active` flag wants (Task 8 review M1).
- **CF-D-18 — CLOSED (`6433ee3b`):** same fix as CF-D-10 — the pending older-task finish now carries a superseded generation and is dropped whole; the zombie walk and the spurious "Network error" row cannot form.
- **CF-D-19:** the revalidation sweep issues one gate GET per saved video on every due launch/foreground (Cubic r3 R3-11). Bounded by the 15-minute spacing and the size of a personal library; the fix is a backend batch endpoint (`POST /api/v1/videos/offline-gate` with ids) plus a client cap — backend work, not Phase 3.
- **CF-D-20 (iPad):** the rail layout publishes no visibility signal, so a cast claim held by a non-selected tab's player is never released on the rail the way the compact `TabView` releases it on `onDisappear` (Part B fix round 1, Minor 4). Documented at the seam; needs a rail-side selection observer.
- **CF-D-21:** `begin()` now consults the per-video gate on every start (security review S-P2-1, adversarial P0-2), so a proceeding user Retry spends two gate GETs (`retry`'s consult, then `begin`'s) and a queued save under a transport-wall park re-asks the gate once per `gateRetryDelay` (flat 60 s) while foregrounded. De-duplicating needs per-id "just authorized" state; the backoff upgrade path is marked `// ponytail:` at `gateRetryDelay`.
- **CF-D-22 (owner):** no certificate pinning on `app.fitrahtube.com`, the host whose answers authorise local deletion (security review S-P2-2). With the Video-model marker (`youtubeId` == requested) and the belted sweep, a device-trusted MITM can at most delete one row per forged Video; pinning needs the deployed TLS chain and a rotation plan — owner decision.
- **CF-D-23:** cast claims are `(videoId, owner)` (adversarial P2-1), so an unreleased stamp is unrecoverable within a session: a claimant whose `onDisappear` never ran (iPad rail = CF-D-20; SwiftUI view-identity churn rebuilding `@State` mid-cast) blocks every other screen, including one on the same video, until `sessionDidBegin`. The same-video takeover was the only escape hatch and it was the double-audio bug. Needs the rail-side selection observer and a stable owner id across identity churn.
- **CF-D-24 (cast expiry):** a cast started inside the 10-minute expiry margin loads the current stream only when its remaining lifetime covers the remaining playback (`expiresAt − now ≥ remaining + 60 s`); a refused re-resolve with an unknown `durationSeconds` (deep links, sources without it) takes the banner path where it previously cast (adversarial P2-3). The receiver still cannot re-resolve mid-playback — `CastController` publishes, it does not drive; closing that needs a receiver-side reload on the SDK's media-status error, device-only to verify.
- **CF-D-26 (cast return):** a claimant that left the screen while another screen replaced its video on the receiver re-loads at the phone's clock when it returns, because nothing samples the receiver's position mid-session (Cubic r7 R7-15, second half); a fresh screen on the video still playing ADOPTS it (closed). Closing this needs a `remoteMediaClient` position read at release time. Related deliberate choice: a claim dropped off-screen (receiver rejected the load, or the session ended under another screen's video) leaves that hidden player PAUSED — resuming audio on a hidden tab is the double-audio class; the screen resumes itself on its next `.appear`, when it is visible again.
- **CF-D-27 (sweep belt):** the revalidation sweep refuses to spend a delete verdict when EVERY answering row (≥2, transport failures discounted) says delete — the belt that stops a backend drift (a nulled `offlineAllowed` on every document, a proxy 200, a catalog outage) from wiping the library (security review S-P0-1, Cubic r5 R5-2). Its cost, accepted (Cubic r8 R8-2): a genuine same-sweep revocation of a user's entire saved library is never reaped by the sweep. The remedies that still apply: `begin` fails a revoked row on its next start, `retry` consults the gate before resuming, TTL expiry, user Delete. A signed batch verdict from the backend (CF-D-19's endpoint) is what would let the belt trust "all revoked".
- **CF-D-28 (attempt token by convention):** `claim`/`attempts`/`stillCurrent`/`release` span `begin`, `resolveAndStart`, `scheduleRetry`, `stopWalkIfNotRunning` and `retryNow`; `scheduleRetry`'s entry contract (the caller holds the claim) is enforced by its currency guard plus a comment, not by a type (Cubic r8 R8-4). An unclaimed caller parks nothing, which is the safe direction. Revisit if a sixth site joins the set — then wrap the token in a non-constructible `Claim` value that only `claim(_:)` mints.
- **CF-D-29 (fixture fork):** `AppContainer` forks `offlineEngine`/`offlineResolver` on the `isFixture` flag inside `#if DEBUG` where every sibling seam uses the injected-or-live idiom (Cubic r9 R9-14; the bloat-trim review accepted the shape). Upgrade path: `engine:`/`resolver:` init parameters on the same idiom, so `fake()` passes the parked doubles, `isFixture` keeps one reader, and `AppContainerTests` counts a recording engine's calls instead of the production-only `reattachCount`.
- **CF-D-30 (process-wide container):** `AppContainer.current` is a static that `OrientationLock`'s two hooks read (Cubic r9 R9-16); tests swap it (hence `.serialized`). Remedy: a `container` property on `AppDelegate` set from `FitrahTubeApp.init` — the adaptor is instantiated before `init()`'s body runs — so both hooks see one object with no global. Phase 4 injects the container everywhere and does not add readers.
- **CF-D-31 (403 arm's claim drop):** the relaunch 403 handler drops the walk's claim unconditionally (`active.remove(id)`, mirroring `fail → forget`) before re-entering `begin` through `claim(_:)` (Phase 4 pre-task 0b, Cubic r9 R9-4). A NEWER claim is reachable only across the handler's entry-read MainActor hop through a full cancel + retry + claim chain, but the blast radius exceeds `fail`'s: two live engine walks on one `.tmp`, because `stopWalkIfNotRunning` bails when a newer attempt owns the row. The close is the attempt token riding `OfflineDownloadEvent` so every failure arm can compare currency — it touches every arm, so it waits for a round that reopens the engine's event shape.
- **CF-D-25 (test seams):** `OfflineEngine`'s `isCurrent` re-checks before the append and each yield, and the catch-arm guard, are untested — the delegate callback is synchronous with no interleaving seam (adversarial P3-5; re-review m6). Add the seam only when another change needs it.

---

## Forks for the controller (defaults chosen; work proceeds unless overridden)

**A. No `DownloadKit` package.** Reconciliation note 1: every real dependency is app-side; pure engine types in the app target get the same test ergonomics; §16's tests land intact. **Default: in-app `Features/Offline/`.** Override = build the package with protocol shims for container/settings/resolver — spec-D14-literal, strictly more files, and Task 3/4 split across a package boundary.

**B. Rate-limiter lane = `.prefetch` (wait, don't skip).** Reconciliation note 4. **Default: reuse `.prefetch`.** Override = add a `.download` case to `ExtractionRateLimiter` (package change + its own budget table + tests); the seam is one enum value in `OfflineManager`, so the override is cheap later with evidence (CF-D-4).

**C. A gate flip (`offlineAllowed` → false) deletes the saved copy on sweep.** Complaint-shaped: the admin flip is the same-day remedy, and a lingering copy defeats it. **Default: delete.** Override = keep existing copies and only block new saves — friendlier to users, weaker as a remedy; one branch in `OfflineSweep.decide` + its test row.

**D. Kill-switch OFF hides saving but the existing library still plays.** Turning a reviewed feature off should stop the *behaviour under complaint* (copying), not confiscate bytes already on devices; the per-video gate (fork C) is the targeted remedy. **Default: hide save affordances + refuse new saves; Saved screen and offline playback remain.** Override = full lockout (hide the screen, refuse offline playback) — one more `downloadsEnabled` consult in two places; choose it if the owner wants the flag to be a total kill.

**E. No completion notification.** Saves are foreground-visible in the Saved screen; the permission prompt is real UX + review surface, and the notification advertises the feature outward. **Default: skip; carry forward.** Override = spec §11 literal (`UNUserNotificationCenter`, permission on first save, "Save for offline" copy) — a contained addition to Task 4's completion path.

**F. Per-video save only: no playlist bulk save, no Shorts-rail button.** Scope control: bulk multiplies gate fetches and resolver load N×, and the Shorts rail is at its ergonomic limit. **Default: main player only.** Override = either surface as a follow-up task; the manager API (`save(videoId:…)`) already supports both callers.

**G. Task 1 outcome B (spike unanswerable on simulator) ships progressive-primary with the HLS engine dormant behind the seam.** **Default: as written** — the picker states the 360p ceiling, hardware re-run flips it (CF-D-1). Override = hold Task 4's HLS engine entirely until hardware exists (less dormant code, but re-opens Task 4 later); second override = treat B as C (never build HLS) — cheapest, and wrong if hardware later says A.
