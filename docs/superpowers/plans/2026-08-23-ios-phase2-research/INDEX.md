# Phase 2 iOS research corpus — Index

Built after the completeness audit (`AUDIT.md`) and the repair pass that followed it (contradictions
C1–C6 fixed, three "minor line drift" claims checked live and left as-is — see the repair notes at
the end of this file — and missing items M1–M5 appended to their briefs). This index does not
re-derive anything: it summarizes each brief's scope, then consolidates every open question and
every "iOS ruling required" item into one continuously-numbered list (**Q1–Q74**) for the controller
to rule on by number, followed by a consolidated one-line list of every Android defect the briefs
record.

---

## 1. Briefs

### `channel-detail.md`
`ChannelDetailFragment` + `ChannelDetailViewModel`, the five tab fragments (Videos / Live / Shorts /
Playlists / About), `NewPipeChannelDetailRepository`, adapters, layouts for all width classes, and
every navigation entry point into the screen.

### `extraction.md`
Everything between "the user taps a video" and "the player has URLs/manifest to play": NewPipeExtractor
usage, the resolver/prefetch stack, caching, client rotation, rate limiting + cooldown, poToken/nsig
infrastructure, format/audio selection, live streams, error taxonomy, and the backend endpoints
consulted during resolution.

### `phase2-inventory.md`
Every Android screen, dialog, menu, and user-visible behaviour, walked from both navigation graphs and
the full `java/com/albunyaan/tube/**` tree, classified against spec §15 (delivery phases) into P1–P4,
D3-EXCLUDED, and UNASSIGNED buckets.

### `player.md`
`PlayerFragment` / `PlayerViewModel`, ExoPlayer setup, quality selection, audio-only, background
playback (`PlaybackService` / MediaSession / notification), fullscreen + landscape, gestures/overlay,
subtitles, speed, watch progress, queue/autoplay, metadata panel, SafeMode, error UI. Shorts player is
out of scope here (see `playlist-detail-shorts.md`).

### `playlist-detail-shorts.md`
Part A: `PlaylistDetailFragment` / `PlaylistDetailViewModel` / `NewPipePlaylistDetailRepository` /
`PlaylistVideosAdapter`. Part B: `ShortsPlayerFragment` / `ShortsPlayerViewModel` /
`ShortsFeedRepository` / `ShortsPagerAdapter` / `ShortsPageViewHolder` / `PlayerBinder`.

### `remote-config-safemode.md`
The remote-config surface (or its Android absence), the Safe Mode setting, `UpdatePromptFlow` and its
splash gate, `UpdateChecker`/`ReleaseCatalogCache`/`ReleaseSummaryFetcher`, the Available Updates
screen, and `releases-meta.json`. **§§3–6 are Android record only, excluded from the iOS port by spec
D3** — see the brief's BASELINE banner; iOS instead builds the spec:193 `RemoteConfig` +
`minAppVersion` gate.

### `share-report-links.md`
`share/ShareLinks` + `share/ShareMetadataPublisher` and the four share entry points; `ui/report/*` +
`data/report/*` + backend `ContentReportController`/`ContentReportService`; manifest intent filters,
nav-graph deep links, `MainActivity` routing, and the backend watch-page contract a shared URL serves.

---

## 2. Open questions and "iOS ruling required" items (Q1–Q74)

Each entry keeps its original wording; only the label is renumbered to a single continuous sequence.
Original brief-local numbering is given in parentheses for traceability back into the source file.

### From `channel-detail.md` §9 (originally Q1–Q11)

**Q1** *(Q1)* — Live tab on iOS at all? The tab machinery is fully built, but livestream *playback*
may interact with Phase 3 scope. Android treats a live row as a normal player launch with
`contentSubType = "LIVESTREAM"` (`ChannelLiveTabFragment.kt:61-72`). Confirm the Live tab ships in
Phase 2 with playback, ships list-only, or is deferred.

**Q2** *(Q2)* — NewPipeExtractor on iOS. The entire screen is built on NewPipe (Java). iOS needs an
equivalent extraction path (own scraper, server-side proxy, or a port). This brief records the
*behaviour* (paths, fallbacks, cursors, rate-limit retries); the mechanism is an architecture decision
this document deliberately does not make.

**Q3** *(Q3)* — Videos dual-path complexity. The channel-tab/UU split exists to work around specific
NewPipe v0.26 pagination bugs (`ChannelDetailViewModel.kt:413-420`,
`NewPipeChannelDetailRepository.kt:184-196`). If iOS's extraction layer doesn't share those bugs, is a
single reliable path acceptable, with the provenance flag dropped?

**Q4** *(Q4)* — Room video cache pre-paint. Port the disk cache for instant re-open paint
(`ChannelDetailViewModel.kt:396-411`), or accept a skeleton on every open in v1? Cached rows lose
`publishedTime` (§3.4) — worth replicating that quirk?

**Q5** *(Q5)* — In-header search semantics. Client-side filtering of loaded pages only, with
pagination disabled and generic empty copy (§5.4), is arguably surprising (matches deeper in the
channel are invisible). Mirror exactly, or add a "no results in loaded items" copy / server-side
search? Android leaves this ambiguous.

**Q6** *(Q6)* — `excluded` arg is dead. No caller passes `true` (§1.2). Port the banner + arg for
deep-link parity, or drop until a caller exists?

**Q7** *(Q7)* — About tab's permanently-nil rows. `location` / `joinedDate` / `totalViews` can never
render (§2.4) yet the layout and strings exist. Build the rows on iOS (future-proofing) or omit?

**Q8** *(Q8)* — Subscriber "–" placeholder. Unknown/zero subscribers shows a bare en-dash
(`channel_subscribers_unknown`, §4.3). Intentional design or placeholder — hide the line instead?

**Q9** *(Q9)* — Tab-bar overflow behaviour. Phone uses scrollable tabs, ≥600 dp fixed/fill (§4.4). iOS
has no `TabLayout`; confirm the segmented/scrollable control choice per size class, and whether
swipe-between-tabs (ViewPager2 `isUserInputEnabled = true`, `ChannelDetailFragment.kt:196`) must be
preserved.

**Q10** *(Q10)* — Autofill caps differ from Phase 1. These tabs cap autofill at 1–2 pages then show a
button (`BaseChannelListTabFragment.kt:399-404`); Phase 1 tabs allow 5 silent attempts and gate phones
out entirely (Phase 1 §4.3). Unify on one model for iOS, or keep both machines?

**Q11** *(Q11)* — Shorts skeleton. Fix the blank loading state on iOS (render the 9:16 skeleton grid
that `skeleton_channel_short.xml` intends), or mirror the blank?

### From `extraction.md` §21 (originally Q1–Q10)

**Q12** *(Q1)* — What does iOS extract WITH? Android leans on NewPipeExtractor for the innertube
protocol (client payloads, throttling params handled by the library, format parsing). iOS has no
equivalent dependency in scope; the dub path (§12) proves a from-scratch innertube client is viable
but it is audio-only and MWEB-specific. Decision needed: pure-Swift InnerTubeKit mirroring NewPipe's
iOS client fetch (§3), or a different extraction source. This brief documents WHAT must come out of it
(§4's `ResolvedStreams`), not HOW.

**Q13** *(Q2)* — poToken/nsig on iOS. Android's poToken is a WKWebView-equivalent BotGuard run (§13)
and is currently *not* on the critical path for main playback (`WebViewPoTokenProvider.kt:158-165`),
but it IS mandatory for dub audio (server pot, §12.2) and history shows YouTube ratchets enforcement
(ANDROID_VR retirement §1). Does iOS Phase 2 include the dub-audio feature at all, and if so does it
reuse `/api/v1/dub-potoken` + a WKWebView nsig solver?

**Q14** *(Q3)* — Error granularity. Mirror Android's collapse of NewPipe's taxonomy into generic
retry→Error (§15.2), or classify age-restricted/geo-blocked/private during extraction (iOS will be
parsing playabilityStatus itself, so the information is available for free)? Android's behaviour
wastes 3 retries + ~7 s of backoff on deterministic failures.

**Q15** *(Q4)* — Availability-gate parity. The HEAD gate's 404-fail-open / 410-hard-block semantics
and the `sourceChannelId` → CHANNEL-check switch (§5.2) are backend curation policy. Confirm iOS uses
the identical HEAD endpoints and that "fail-open on transport errors" (offline playback of cached
videos) is wanted on iOS, where there is no download cache in Phase 2.

**Q16** *(Q5)* — Priority lanes and the shorts PLAYER default. Port the 4-lane system + cooldown
as-is, or simplify? If ported, does iOS fix the shorts default-priority leak (§20) or replicate it?
Note the whole lane system only has teeth for BACKGROUND_REFRESH traffic (§6.3) — on iOS Phase 2 the
only BACKGROUND_REFRESH producers would be tap-prefetch and queue-prefetch.

**Q17** *(Q6)* — Predictive prefetch. Ship the tap-prefetch design (§7.1–7.2) but the scroll-attach
controller is OFF on Android with a written warning (§7.3). Does iOS implement it at all (dormant
code) or drop it until the Android-side capping work lands?

**Q18** *(Q7)* — Client rotation freshness. The IOS→ANDROID order, "ANDROID = muxed 360p itag 18
only" and "iOS returns the full ladder once poToken'd" are empirical claims dated 2026-08-18 in
comments (§3.4, gradle `:79-87`). Before freezing InnerTubeKit's client table, re-probe live YouTube —
this area has flipped twice in the repo's own history (VR fast path added, then removed).

**Q19** *(Q8)* — US-pinned localization. Mirror `Locale.US`/"US" (§2) for parity, or use the device
locale now that iOS is a fresh implementation? Affects hydrated metadata language and possibly geo
behaviour; Android leaves no rationale comment for the pin.

**Q20** *(Q9)* — Live refresh cadence. The 50-min proactive live re-resolve + seamless-swap event
(§8.5) presumes URLs minted at resolve time expire ~1 h. If iOS plays live via the HLS manifest URL
(Android's preferred live source, §8.5), the manifest itself refreshes segments — confirm whether the
proactive re-resolve is still needed on iOS or is an Android artifact of its progressive/DASH fallback
paths.

**Q21** *(Q10)* — `forceRefresh` only on attempt 1. In `resolveWithRetry`, retries 2–3 of a
force-refresh drop the flag (`PlayerViewModel.kt:1474-1477`), so they can be served by the very cache
the refresh meant to bypass (the cache was only overwritten if attempt 1 succeeded — but attempt 1
failing is why we're retrying, and the stale entry survives per §3.2). Deliberate fallback or defect?
iOS must pick one and document it.

### From `phase2-inventory.md` §8 (originally Q1–Q10)

**Q22** *(Q1)* — Me-telemetry log dialog (§6.1). Which phase owns `MeTelemetryLogDialog` — bundle
with the P4 Me feed it diagnoses, add to the P2 dev-dialog additions, or drop as Android-only operator
tooling?

**Q23** *(Q2)* — `FollowedChannel` store (§6.2). Dead on Android. Does the iOS SwiftData schema
mirror reproduce the table for wire parity, or omit it?

**Q24** *(Q3)* — Player analytics readout (§6.3). Views permanently `gone` but state still rendered.
Port the readout (as a debug feature), or drop views + `renderAnalytics` work from the iOS player?

**Q25** *(Q4)* — Stream indexing (§6.4). Should the iOS P2 detail port replicate the `POST`
stream-index side channel (unnamed in spec §12/§15), or is it Android-only?

**Q26** *(Q5)* — Telemetry pipeline (§6.5). Log-only on Android. Any iOS equivalent, or none?

**Q27** *(Q6)* — Subscribe/save in P2 (§6.6). Do the P2 channel/playlist detail screens ship the
subscribe (30-cap) and save-playlist buttons working against local storage for guests, as Android
does, or hide them until P4?

**Q28** *(Q7)* — Download chrome in P2 (§6.7). What do the player/playlist/Shorts download
affordances (and the `downloadPolicy` label) show while P3 doesn't exist?

**Q29** *(Q8)* — AirPlay leakage into P2 (§6.8). `AVPlayerViewController` stock transport includes
AirPlay; D3 schedules AirPlay for P3. Suppress in P2 or accept early?

**Q30** *(Q9)* — Share-metadata publish before accounts (§6.9). Publish is auth-gated and auth is P4.
Build dormant in P2 (spec's phase) or move to P4?

**Q31** *(Q10)* — Guest Me tab between P1 and P4. The shipped P1 shell has five tabs (D11) including
Me, but every Me behaviour is P4 (`main_tabs_nav.xml:51-68`, spec §13). Confirm what the iOS Me tab
shows during P2/P3 (Android guest Me = favorites + sign-in card — spec §13) and whether any of it
moves earlier.

### From `player.md` §20 (originally Q1–Q12), plus §21 and §23 (M1, M3 — ruling-required, no open-question label in source)

**Q32** *(Q1)* — Watch progress. Android persists nothing (`PlaybackService.kt:986-995`), by explicit
comment. iOS parity = no resume-where-you-left. Ship parity, or is Phase 2 the moment to add
per-video resume (it changes data model + Continue Watching expectations)?

**Q33** *(Q2)* — Related videos / empty Up Next. Single-video mode shows an "Up next" header over an
empty list (`PlayerViewModel.kt:2218`, `PlayerFragment.kt:1534-1536`). Mirror the empty section, hide
it, or add a related-videos source (none exists server-side today)?

**Q34** *(Q3)* — Dead settings. `safeMode`, `backgroundPlay`, settings-`audioOnly` are written but
never read (§15). Should iOS wire them (background-play OFF actually pausing on background, settings
audio-only seeding the toggle), or replicate the dead switches for parity?

**Q35** *(Q4)* — Playback speed. Android exposes speed only through Media3's stock settings menu, not
persisted (§10). Does the iOS player expose a speed control at all, and if so is it per-video or
persisted?

**Q36** *(Q5)* — Quality persistence. The user's pick evaporates per stream; only
`last_successful_height` seeds cold-start AUTO (§4.3). Persist a user quality preference on iOS, or
mirror?

**Q37** *(Q6)* — View-count / duration formatting drift. Player stats use `%.1fM views` + "No views
yet" with an unreachable billions branch (`PlayerFragment.kt:1484-1493`); up-next uses non-localized
`String.format("%.1fK")` and an `m:ss` formatter that never emits hours (`UpNextAdapter.kt:60-79`);
list screens use ICU compact + plurals (Phase 1 §5.6). One formatter on iOS — which?

**Q38** *(Q7)* — Stats line has no upload date though the layout placeholder shows one
(`fragment_player.xml:348`), and **no like count** (orphan strings `strings.xml:114,274`; NewPipe
exposes like counts). Add on iOS or mirror the omission?

**Q39** *(Q8)* — `channelName` arg carries `category` from Videos/Featured (Phase 1 Q4), so the green
author line under the title shows a category name for those entry points. Fix on iOS or replicate?

**Q40** *(Q9)* — Stream resolution stack. §3's recovery machinery is NewPipe/ExoPlayer-specific
(synthetic DASH, 403 refresh ladders, HLS poisoning). Prior iOS probing found different constraints
(VISIONOS streams w/o pot; IOS client 403s ~60 s; itag18 fallback — memory
`ios-youtube-client-findings-2026-08`). What is the Phase 2 acceptance bar: full ladder parity, or
"plays reliably with position-preserving refresh on failure" as the contract (§16 table)?

**Q41** *(Q10)* — Excluded-items scaffolding. `excludedItems`/`excludedMessage`/`analyticsStatus`/
`playerStatus`/`currentlyPlaying` are all invisible 0dp views fed by live code
(`fragment_player.xml:627-686`, `PlayerFragment.kt:1540-1547`) and the stub queue is empty. Skip this
machinery on iOS entirely?

**Q42** *(Q11)* — Tablet fullscreen. Auto-fullscreen-on-landscape is phone-only
(`PlayerFragment.kt:648`); tablets fullscreen only via the button. iPad: mirror (button-only) or use
standard iOS full-screen presentation on rotate?

**Q43** *(Q12)* — PiP. Android offers PiP only from an overflow menu item, no auto-PiP (§7.4). iOS
convention is automatic PiP via AVKit. Mirror the manual trigger, or adopt platform-standard auto-PiP
(interacts with Q3's background-play question)?

**Q44** *(§21, M1 — "NO ANDROID CONTRACT — iOS ruling required")* — The app never requests Android
audio focus; the only interruption handling anywhere is `setHandleAudioBecomingNoisy(true)`
(`PlayerFragment.kt:974`, `ShortsPlayerViewModel.kt:101`). On iOS an `AVAudioSession` category +
interruption policy is unavoidable and there is no Android contract to mirror — this needs an
explicit product ruling on iOS audio session category (e.g. `.playback` with `.mixWithOthers` to
approximate Android's laissez-faire posture, vs. standard interruption handling that pauses on
calls/other audio) rather than a port, since Android supplies no contract to port.

**Q45** *(§23, M3 — "iOS ruling required")* — `PlayerFragment` registers no `OnBackPressedCallback`;
system back in fullscreen is handled solely by `MainActivity.kt:81-107`'s nested-nav-pop callback,
which has no fullscreen awareness, so back pops the player destination entirely instead of exiting
fullscreen first. iOS must decide whether to mirror Android's single-step exit (defect, by common
convention) or implement the two-step fullscreen-then-leave pattern, which has no Android contract to
copy.

### From `playlist-detail-shorts.md`, "Open questions" (originally Q1–Q12, unnumbered section)

**Q46** *(Q1)* — Playlist empty state is unconfigured. `PaginatedState.Empty` shows the shared
`empty_state.xml` with generic headline "No content yet", blank body, blank icon
(`PlaylistDetailFragment.kt:427-433`, `empty_state.xml:19-58`) — for both an empty playlist and zero
search matches. Should iOS design proper copy (and a distinct search-no-results variant, as the tab
screens have)?

**Q47** *(Q2)* — Append is visually silent. The VM emits `Loaded(isAppending = true)`
(`PlaylistDetailViewModel.kt:348`) but the fragment renders no footer spinner (§4.4). Mirror the
silence or add the tab-style bottom spinner?

**Q48** *(Q3)* — `video_views_format` vs plural. Playlist rows use the plain "%s views" string
(`PlaylistVideosAdapter.kt:71`, `strings.xml:389`) while Phase 1 screens use the `video_views` plural
with the Arabic compact-count rule. Unify on the plural on iOS?

**Q49** *(Q4)* — Playlist total duration is dead. `totalDurationSeconds` is always nil
(`NewPipePlaylistDetailRepository.kt:329`) so "%1$d videos • %2$s" never renders. Drop the variant on
iOS, or compute a total from fetched items?

**Q50** *(Q5)* — Shorts swipe-off is load-bearing product policy. `isUserInputEnabled = false`
(`ShortsPlayerFragment.kt:329`). Confirm iOS must equally reject vertical paging gestures (i.e. not a
`TabView`/paging scroll view with gestures enabled).

**Q51** *(Q6)* — Global-feed shorts have no channel attribution. Feed mode leaves
channelId/channelName blank forever (`ShortsFeedRepository.kt:53-56`; overlay hides the row,
`ShortsPageViewHolder.kt:48-50`), and only the deep link reaches feed mode. Is feed mode in iOS scope
at all, or is shorts always channel/Me-launched?

**Q52** *(Q7)* — defect: like toggle hides the globe/CC buttons. The liked-flow re-bind calls
`bindItem(item, liked, hasMultipleAudioTracks = false)` (`ShortsPagerAdapter.kt:99-107`) which resets
both rail buttons to gone (`ShortsPageViewHolder.kt:92-97`); the count StateFlows don't re-emit an
unchanged count, so the buttons stay hidden until the next stream resolution. Fix on iOS (drive
visibility solely from the count publishers)?

**Q53** *(Q8)* — defect: dead tablet Report button. sw600/sw720 `item_shorts_page.xml` adds
`shortReportBtn` (`layout-sw600dp/item_shorts_page.xml:69-79`) that no code wires (grep: zero Kotlin
references). Include a working rail Report on iPad, or keep Report kebab-only everywhere?

**Q54** *(Q9)* — LoadError copy. Any shorts feed failure toasts "No shorts available"
(`ShortsPlayerFragment.kt:434-436`, `strings.xml:648`), conflating network errors with an empty feed.
Keep for parity or split the copy?

**Q55** *(Q10)* — Playlist pagination has no upward-scroll guard and no autofill. Unlike the tabs
(Phase 1 §4.3), `onListScrolled` fires on any scroll (`PlaylistDetailFragment.kt:281-288`) and there
is no fits-on-screen autofill — on a tall iPad a short first page may never trigger page 2 without
scrolling. Adopt the Phase-1 guarded autofill here?

**Q56** *(Q11)* — Shorts rail Download button (Phase 3) placement. The button is always visible on
Android (`item_shorts_page.xml:92-101`) even though downloads are Phase 3 on iOS. Hide it until Phase
3, or ship disabled?

**Q57** *(Q12)* — Bottom nav behind the shorts player. Android keeps the shell bottom nav visible on
phones (margins sized to clear it, §9.5) while hiding the *system* bars. Should iOS keep its tab bar
visible on the shorts screen (Android parity) or go truly full-screen?

### From `remote-config-safemode.md` §8 (originally Q2, Q4–Q9 — **Q1 and Q3 excluded: RESOLVED by the C1 repair**, see repair notes)

**Q58** *(Q2)* — Safe Mode: port, wire, or drop? Android ships a default-ON switch that gates nothing
(§2.3) and a backend with no safe-mode parameter. Porting it as-is reproduces a placebo; wiring it to
real filtering changes behaviour vs Android and needs a backend contract that does not exist. Which?

**Q59** *(Q4)* — `releases-meta.json` branch pin. Code reads `develop`
(`ReleaseSummaryFetcher.kt:122-123`, with a TODO + test to flip on first stable); CLAUDE.md says the
screen reads from `main`. If iOS consumes this file, which branch — and does the flip-on-stable rule
apply to both platforms?

**Q60** *(Q5)* — Play-parity asymmetry on the manual check row. Play-Store installs hide "Available
updates" but keep "Check for updates", which can then only ever toast "up to date" (§3.4). For an App
Store build: hide both, keep the asymmetry, or repurpose the row?

**Q61** *(Q6)* — Splash-timing invariant. Android pins probe budget = splash pre-await (2750 ms) +
500 ms grace, with a test enforcing the equality (`UpdatePromptFlow.kt:547-564`). The iOS splash (if
any) has different timing; the invariant "probe budget ≥ unconditional splash time, small bounded
grace after" needs re-deriving rather than copying 2750/500.

**Q62** *(Q7)* — Developer menu. The hidden 7-tap dev dialog toggles Android playback flags
(`PlaybackFeatureFlags`) that have no iOS counterpart in Phase 2. Skip entirely, or reserve the
gesture for an iOS diagnostics panel?

**Q63** *(Q8)* — `FEATURED_CATEGORY_ID` hardcode (`FeaturedListViewModel.kt:199`) — duplicate of
Phase 1 Q6, kept here because it is the one value a real remote config would obviously own.

**Q64** *(Q9)* — Semver display sanitizer. Android strips non-ASCII from version tags at every
display and URL site (`SemverDisplay.kt:17-19`) as a homoglyph defence. Carry the same allowlist on
iOS, or trust the release pipeline?

### From `share-report-links.md` §5 (originally Q1–Q10)

**Q65** *(Q1)* — Universal Links hosting. Android relies on `autoVerify` App Links for
`app.fitrahtube.com` (`AndroidManifest.xml:76-129`) but no `assetlinks.json` is in this repo, and
there is no `apple-app-site-association` anywhere either. Who owns/serves the association files for
`app.fitrahtube.com`, and can iOS get an AASA entry there? Without it, iOS only gets the custom scheme
+ the watch page's 50 ms JS hop (§1.5).

**Q66** *(Q2)* — Player's two report entry points disagree (§2.1 defect): the on-screen button drops
parent context that the kebab forwards (`PlayerFragment.kt:477-483` vs `:1824-1851`). Should iOS
replicate both entry points, and if so, with unified context?

**Q67** *(Q3)* — Shorts links. `albunyaantube://shorts/{id}` exists only inside the nav graph
(`main_tabs_nav.xml:303`), is not externally reachable, and is never generated — shared Shorts become
`/api/watch/{id}` links that open the *regular* player on receipt. Should iOS route an inbound watch
link that happens to be a Short into its shorts player, or mirror Android (regular player)?

**Q68** *(Q4)* — Share-metadata publisher vs Phase 4. `ShareMetadataPublisher` silently no-ops for
anonymous users (`ShareMetadataPublisher.kt:42-47`) and iOS Phase 2 has no accounts, so the call can
never succeed. Ship the client code dormant, or omit it until Phase 4?

**Q69** *(Q5)* — Device-id mechanism on iOS. Android uses a random UUID persisted in
SharedPreferences — reset on reinstall (`NetworkModule.kt:115-122`), which is also the report-throttle
key. UserDefaults UUID (reinstall resets, closest parity), Keychain UUID (survives reinstall, stricter
throttling), or `identifierForVendor`? Android leaves no guidance.

**Q70** *(Q6)* — Hardcoded English in the report VM (§2.3 defect): "Please select at least one
reason." and "Failed to submit report." bypass the existing localized keys
(`ReportViewModel.kt:35,49` vs `strings.xml:639-640`), and the whole `report_*` set is missing from
`values-ar`/`values-nl` (§2.2). Use the localized keys on iOS (and localize ar/nl), or mirror
Android's current strings?

**Q71** *(Q7)* — Share payload mapping to `UIActivityViewController`. Android sends
`EXTRA_SUBJECT`/`EXTRA_TITLE` alongside `EXTRA_TEXT` (§1.3); iOS activity items have no direct
subject/title split (subject exists only via `activityViewController(_:subjectForActivityType:)`).
Single combined text item, or text + URL as separate activity items (which changes how Messages/Mail
render it)? Android's format doesn't decide this.

**Q72** *(Q8)* — 429 dismisses the sheet. RateLimited closes the sheet, discarding the user's selected
reasons and typed description (`ContentReportBottomSheet.kt:131-135`); Error keeps them. Intended
asymmetry to replicate?

**Q73** *(Q9)* — Dead `menu_report.xml` (§2.1) — confirm iOS ports nothing from it.

**Q74** *(Q10)* — Watch-page CTA on iOS. The backend hop script targets `albunyaantube://…` for
iPhone/iPad UAs (`WatchPageController.java:536-542`). Registering the same custom scheme on iOS makes
the existing pages work unmodified — confirm the iOS bundle claims `albunyaantube` (matching the
back-compat naming rule in CLAUDE.md) rather than a new scheme, which would require a backend change.

**Total: 74 open questions / ruling-required items.**

---

## 3. Android defects recorded (one line each)

**`channel-detail.md`** (§8, "Android defects observed")
1. Shorts skeleton never renders (no adapter set) — blank loading state instead of skeleton cards (§6.3).
2. Shorts `ErrorInitial` shows no message text (§6.3).
3. Shorts view-count formatting bypasses `CountFormat`/plurals — local "%.1fB/M/K views" instead (§6.3).
4. About tab + shared `empty_state` hardcode black/gray text — illegible in dark mode (§6.5, §5.5).
5. Upcoming streams always show an empty meta line — `scheduledStartTime` never populated (§6.2).
6. Search-filtered empty shows the generic tab empty copy, not a "no results" variant (§5.4).
7. Inconsistent nav-arg discipline: Playlist-detail entry passes no avatar; Home/Featured/Search pass no `excluded` (§1.2).

**`extraction.md`** (§20, "Defects and oddities noted factually")
8. NewPipe exception taxonomy unused — age-restricted/geo-blocked/private videos retry 3× then show a generic error, wasting ~7 s of backoff on deterministic failures (§15.2).
9. Shorts binds ride PLAYER priority even for off-screen pager pages, bypassing every extraction-priority gate (`PlayerBinder.kt:404`).
10. Stale version comments ("NewPipeExtractor 0.26.2") vs the actual pinned v0.26.5.
11. Dead code: `AndroidVrStreamResolver` + `ExtractionClient.ANDROID_VR` production paths remain despite the client's retirement.
12. Cold non-prefetched opens get no MPD-TTL watcher — reactive 403 recovery only (documented as deliberate).
13. Client-selection sync race — covers the setting-apply only, not concurrent `fetchPage()` calls (documented, accepted).
14. TOCTOU window — a request can slip past a cooldown trip between the post-acquire re-check and execute (documented, accepted).
15. Hardcoded innertube constants in the dub path (MWEB client version, fallback signature timestamp) will rot as YouTube updates.
16. Localization pinned to US for all extraction — hydrated metadata always returns in en-US regardless of device locale.

**`phase2-inventory.md`** (§7, "Dead code and factual defect notes from the sweep")
17. Three menus (`detail_share_menu.xml`, `menu_report.xml`, `filter_menu.xml`) are defined but never inflated — zero call sites.
18. Dead `FollowedChannel` table/DAO/repository — zero UI or sync consumers (§6.2).
19. Dormant player analytics rows — views permanently `gone` but the state is still computed and rendered into them (§6.3).
20. Dead Paging 3 stack, already ruled not-ported (RULINGS.md #21).
21. No watch history, continue-watching, resume-position persistence, widgets, or app-shortcuts anywhere in the app.

**`player.md`** (§15, plus §23/M3)
22. SafeMode has zero effect inside the player (or the playback stack generally) — dead setting, curation happens server-side only.
23. Background playback is unconditionally enabled regardless of the "Background play" toggle.
24. The Settings audio-only preference does not seed the player's own audio-only toggle.
25. System back while fullscreen pops the player entirely instead of exiting fullscreen first — single-step exit, arguably a defect by common (YouTube-style) convention (§23).

**`playlist-detail-shorts.md`** (scattered §4.3/§4.4/§9.3/§9.6/§13, plus Q7/Q8)
26. sw720 layout ships `headerSkeleton` visible by default, unlike sw600/phone which default to gone (§4.3).
27. Empty state is unconfigured — generic "No content yet" renders for both a genuinely empty playlist and zero search matches (§4.4).
28. Appending is visually silent — `isAppending` state exists but the fragment renders no footer spinner (§4.4).
29. Shorts `LoadError` toast ("No shorts available") is misleading — used for any feed failure, not just an empty feed (§9.6).
30. Shorts kebab content-description is hardcoded "More options", not a string resource (§9.3).
31. `keepScreenOn` asymmetry — the main player pins the screen awake, Shorts pins nothing (§13, new M5 section).
32. Like-toggle rebind resets the globe/CC rail buttons to hidden until the next stream resolution re-derives their visibility (Q7).
33. Dead tablet Report button — `shortReportBtn` exists on sw600/sw720 layouts but no code wires it (Q8).

**`remote-config-safemode.md`** (§2.3)
34. Safe Mode switch persists state that gates nothing anywhere in the app or backend — toggling it (either direction) has no observable effect.

**`share-report-links.md`** (§2.1, §2.2, §2.3, §3.1)
35. Player's two report entry points disagree — the on-screen Report button drops parent context (`channelId`/`playlistId`/`contentSubType`) that the kebab path forwards for the same video (§2.1).
36. Dead resource: `res/menu/menu_report.xml` defines an `action_report` item that no code inflates (§2.1).
37. Report strings exist only in `values/` — zero coverage in `values-ar`/`values-nl`, so ar/nl users get English report UI (§2.2).
38. Report ViewModel error messages ("Please select at least one reason.", "Failed to submit report.") are hardcoded English, bypassing existing unused localized keys (§2.3).
39. Shorts deep link `albunyaantube://shorts/{initialShortId}` is unreachable from outside the app — the manifest has no `shorts` host filter, and nothing ever emits the link either (§3.1).

**Total: 39 recorded Android defects.**

---

## 4. Repair notes (what changed from `AUDIT.md`, and one item not applied)

- C2–C6 fixed in place (channel-detail.md §2.1 GET→HEAD; player.md §13 "unreachable" scoped to the
  player + cross-referenced to `ChannelShortsAdapter.kt:65-68`; phase2-inventory.md §2 line count
  corrected to 4278 (`wc -l`); phase2-inventory.md §5 row corrected so only the Available-updates
  row+divider are described as hiding; player.md §8.3 corrected to the 32/36/40 dp dimen override,
  40 dp on sw720).
- C1 (major) fixed: `remote-config-safemode.md` now opens with a BASELINE banner citing spec:22 (D3),
  spec:193 (RemoteConfig schema), and phase-1 RULINGS.md ruling 6; §§3–6 are marked "ANDROID RECORD
  ONLY — excluded from the iOS port (D3)"; §7's checklist and Q1/Q3 are rewritten to match (Q1 and Q3
  are now marked RESOLVED in that brief and are excluded from this index's open-question count).
- M1–M5 appended as new numbered sections (player.md §21/§22/§23/§24; playlist-detail-shorts.md §13),
  each citation re-verified live against source before writing.
- **Not applied: the three "minor line drift" citation fixes in AUDIT.md §3.** Re-verified all three
  against live source (`awk`/`sed -n` on the actual files) before touching anything:
  - `ContentReportService.java` — `RATE_LIMIT_MAX = 5` is on line **30** today; the brief already
    cites `:30`. AUDIT.md's claim that the correct line is 29 does not hold (line 29 is the
    `Logger log` field).
  - `StreamPrefetchService.kt` — `PREFETCH_RESULT_TTL_MS` is on line **133**, already inside the
    brief's cited range `:118-133`. AUDIT.md's claim that the constant "sits at :134" does not hold
    (134 is the companion object's closing brace).
  - `SyncManager.kt` — the three entity-type map entries are on lines **153-155**, exactly the
    brief's existing citation. AUDIT.md's alternate span `:151-155` includes the enclosing function
    signature, not "the cursors map" itself.

  Since blindly applying these three would have introduced incorrect line numbers into otherwise
  accurate briefs, none of the three citations were changed.
