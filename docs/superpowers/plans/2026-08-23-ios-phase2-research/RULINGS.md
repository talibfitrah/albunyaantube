# Phase 2 rulings on INDEX.md Q1–Q74 (2026-08-23)

Default: Android parity. Deviate only where Android is buggy (the 39 recorded defects), where iOS
has no equivalent, or where the spec already decided. Each line: ruling — why — cost if wrong.
"Fix" means the iOS build corrects a recorded Android defect; the Android record stands untouched.

## Channel detail (Q1–Q11)
1. Live tab ships in Phase 2 with playback — a live row is a normal player launch on Android; live HLS is already player scope — cost: player must handle live from day one (it must anyway).
2. Extraction mechanism → ruling 12.
3. Single videos path, provenance flag dropped — the dual path works around NewPipe v0.26 bugs iOS does not inherit — cost: re-add a fallback path if iOS hits its own pagination bugs.
4. No Room-style pre-paint cache in v1; skeleton on open (Phase 1 pattern) — YAGNI — cost: slightly slower perceived re-open; add a cache later if it grates.
5. In-header search mirrors client-side filtering of loaded pages, but uses the tabs' "no results" copy (fixes defect 6) — cost: none.
6. `excluded` arg not ported (dead on Android) — cost: none.
7. About tab's permanently-nil rows (location/joined/totalViews) omitted — YAGNI — cost: add when the data exists.
8. Subscriber "–" placeholder kept (parity; the string exists) — cost: cosmetic.
9. Tabs: custom scrollable tab strip on compact, fixed/fill ≥600 pt (Android IA + tokens, native construction, same as the Phase 1 rail); swipe-between-tabs preserved — cost: none.
10. Keep both autofill machines: channel tabs cap at 1–2 pages then a "show more" button (deliberate Android UX); Phase 1 lists keep their PaginationGuard — cost: two mechanisms, as on Android.
11. Shorts loading state renders the 9:16 skeleton grid (fixes defect 1) — cost: one skeleton variant.

## Extraction (Q12–Q21)
12. Pure-Swift **InnerTubeKit** package per spec §9: innertube `player` requests with **VISIONOS** as the primary client (spec §9 `SessionStore`; confirmed by the 2026-08 live probes — VISIONOS streams without pot, IOS 403s ~60 s), ANDROID for itag 18, WEB for `browse`; producing `ResolvedStreams` per extraction.md §4 — cost: the biggest Phase 2 risk; de-risked by ruling 18's day-1 spike.
13. Dub-audio (server-pot MWEB pipeline, `/api/v1/dub-potoken`, WKWebView nsig) **deferred to Phase 3**; Phase 2 ships the audio-track picker only for languages already present in resolved streams, hidden when there is one — cost: dub users wait one phase; the UI surface is ready.
14. Classify `playabilityStatus` during extraction: age-restricted / geo-blocked / private / removed are terminal states with distinct copy, no retries (fixes defect 8) — cost: none.
15. Availability gate: identical HEAD endpoints, 404-fail-open / 410-hard-block, fail-open on transport errors — backend curation policy, parity — cost: none.
16. Two lanes only (interactive, prefetch) + the cooldown/backoff ladder; the 4-lane system returns with Phase 3's BACKGROUND_REFRESH traffic; the shorts priority leak (defect 9) is impossible by construction — cost: re-introduce lanes in Phase 3.
17. Tap-prefetch yes; the scroll-attach prefetch controller is NOT ported (OFF on Android with a written warning; dead code is not ported — Phase 1 ruling 21 precedent) — cost: none.
18. **Day-1 spike**: re-probe live YouTube (client table, pot requirements, 403 timing) before freezing InnerTubeKit's client table; the Android comments and `ios-youtube-client-findings-2026-08` are dated empirical claims, not contracts — cost: none; it caps the risk of ruling 12.
19. Device locale for `hl` (storefront `gl`), en-US fallback on parse anomaly — the US pin is an unexplained Android artifact yielding en-US metadata everywhere (defect 16) — cost: one constant to pin back if payload shapes vary by locale.
20. No proactive 50-min live re-resolve when playing the HLS manifest URL (the manifest self-refreshes); reactive recovery only — cost: rare long-session stall → re-add the timer.
21. `forceRefresh` stays sticky across all retry attempts (Android drops it after attempt 1 — Q21 records it as defect-or-fallback; sticky is the coherent semantic) — cost: none.

## Inventory strays (Q22–Q31)
22. MeTelemetryLogDialog: dropped (Android operator tooling) — cost: none.
23. `FollowedChannel` store: omitted (dead on Android) — cost: none.
24. Player analytics readout: dropped entirely (views are permanently gone) — cost: none.
25. Stream-index side channel (`POST` IndexStreamsRequest, fire-and-forget, log-and-drop) IS ported — it feeds the shared watch pages — cost: minimal.
26. No telemetry pipeline on iOS (log-only on Android) — cost: none.
27. Subscribe (30-cap) and save-playlist ship in Phase 2 working against local stores for guests, exactly as Android; Phase 4 syncs them — cost: local stores now, sync mapping later.
28. All download affordances hidden until Phase 3 (dead buttons are worse than absent ones) — cost: none.
29. AirPlay via stock AVKit transport accepted in Phase 2 (suppressing it fights the platform; D3's Phase 3 item is the *complete* AirPlay story) — cost: none.
30. ShareMetadataPublisher omitted until Phase 4 (cannot succeed without auth; shipping dormant code violates YAGNI). This amends the spec §15 phase-2 row ("share + metadata publish") — publish moves to Phase 4; share itself stays Phase 2 — cost: small port later.
31. Me tab stays the Phase 1 guest card (favorites + sign-in) through P2/P3 — cost: none.

## Player (Q32–Q45)
32. No watch-progress/resume (parity; Android persists nothing, by comment) — cost: a known gap shared with Android.
33. "Up next" header hidden when the queue is empty (Android renders a header over nothing — defect-adjacent); shown for playlist queues — cost: cosmetic.
34. The settings are REAL on iOS (Phase 1 decision stands): background-play OFF pauses on background; settings audio-only seeds the player toggle. Safe Mode → ruling 58 — cost: better-than-Android behaviour, documented.
35. Playback speed in the player overflow, per-session, not persisted (parity with Media3's menu) — cost: none.
36. Quality: per-stream pick, `last_successful_height` seeds cold-start AUTO (parity) — cost: none.
37. ONE formatter: Phase 1 `Format` (ICU compact + plurals) everywhere, including player stats and up-next rows (fixes the three-way drift, defect 3 family) — cost: minor visual diff from Android's player line.
38. No upload date, no like count in the stats line (parity; the Android strings are orphans) — cost: none.
39. `channelName ← category` leak fixed (Phase 1 ruling 17 pattern: pass the real channel title, fall back only when nil) — cost: none.
40. Acceptance bar for playback: extraction.md §16's contract — "plays reliably, position-preserving refresh on failure" — not Android's ladder-for-ladder recovery parity — cost: defined acceptance criteria in the plan.
41. Excluded-items scaffolding (0dp views, stub queue) skipped — cost: none.
42. Fullscreen: iPhone auto-fullscreens on landscape, iPad by button only (parity on both form factors) — cost: none.
43. Platform-standard auto-PiP via AVKit (iOS convention beats Android's buried menu item; spec's native-idiom principle) — cost: divergence from Android, deliberate.
44. Audio session: `.playback`, no `mixWithOthers`; standard interruption handling (pause on interruption, resume after transient ones). Android's no-focus posture is an omission, not a contract — cost: behaviour differs from Android, correctly.
45. Two-step back: back/swipe exits fullscreen first, then leaves the player (Android's single-step exit is recorded defect 25) — cost: none.

## Playlist detail + Shorts (Q46–Q57)
46. Proper playlist empty copy + a distinct search-no-results variant (fixes defect 27) — cost: two strings ×3 locales.
47. Footer spinner while appending (fixes defect 28) — cost: none.
48. `video_views` plural everywhere (fixes the plain-string drift) — cost: none.
49. Total-duration variant dropped (always nil on Android; computing it from partial pages would lie) — cost: none.
50. Shorts vertical paging gestures disabled (verbatim anti-doom-scrolling policy; confirmed contract) — cost: none.
51. Shorts global feed OUT of scope (unreachable on Android outside an internal deep link, no attribution); shorts open from the channel tab — cost: none.
52. Rail button visibility driven solely by the count/track publishers (fixes defect 32) — cost: none.
53. Report stays kebab-only on every size class (the tablet rail button is dead on Android — defect 33; the working path is the kebab) — cost: none.
54. Split the failure copy: network error vs genuinely empty feed (fixes defect 29) — cost: one string ×3.
55. Playlist list adopts the Phase 1 guarded autofill (fixes the no-autofill gap; the CLAUDE.md pagination rule mandates it on large screens) — cost: none.
56. Shorts rail download button hidden until Phase 3 (= ruling 28) — cost: none.
57. Tab bar stays visible on the shorts screen on iPhone (parity); status bar hidden — cost: cosmetic.

## Remote config / Safe Mode / updates (Q58–Q64)
58. Safe Mode gains its first real effect in Phase 2, as the spec already decides (spec §10 Up Next: "auto-advance on end **unless Safe Mode**"): Safe Mode ON disables player auto-advance. Nothing else is gated (the catalog is admin-curated; Android's switch gates nothing — defect 34) — cost: none.
59. `releases-meta.json` branch question is Android-only (D3 excludes the update system from iOS) — cost: none.
60. No update rows in iOS Settings at all (App Store owns updates; Phase 1 already shipped without them) — cost: none.
61. Splash invariant re-derived: RemoteConfig fetch rides the existing `withCappedWork` cap (2750 ms + 500 ms grace, 3250 ms max) — same shape as Android's probe budget, iOS constants — cost: none.
62. 7-tap gesture stays the iOS DeveloperDialog; Phase 2 adds resolver counters + cooldown state (Phase 1 ruling 35); Android's playback flags are not ported — cost: none.
63. `FEATURED_CATEGORY_ID` becomes a RemoteConfig key with the hardcoded id as bundled default (Phase 1 ruling 19 anticipated exactly this) — cost: none.
64. The ASCII allowlist sanitizer is applied to any remote-sourced version string the minAppVersion screen displays (one function; homoglyph defence carried over) — cost: trivial.

## Share / report / links (Q65–Q74)
65. AASA hosting is USER-BLOCKED (needs the Apple Team ID and control of app.fitrahtube.com). Phase 2 ships the parser + custom scheme; Universal Links activate when the user supplies both — cost: none new; already on the deferral list.
66. ONE report path: both player entry points forward the full context (channelId/playlistId/contentSubType) — fixes defect 35 — cost: none.
67. Inbound watch links open the regular player even for Shorts (parity; the receiver cannot know it is a Short before resolution) — cost: none.
68. = ruling 30 (publisher omitted until Phase 4).
69. Device id: the Phase 1 `DeviceId` (UserDefaults UUID, key `com.albunyaan.tube.deviceId`) — closest parity, already shipped — cost: none.
70. Report UI uses the localized keys (fixes defects 37/38); ar/nl arrive via the converter's R7 fallback until translated — cost: none.
71. Share sheet: text + URL as separate activity items, subject via `activityViewController(_:subjectForActivityType:)` — richest rendering across Mail/Messages — cost: cosmetic.
72. 429 keeps the user's selected reasons and text (inline rate-limit message; Android's dismiss-and-discard is user-hostile and its own Error path already keeps input) — cost: none.
73. `menu_report.xml` ports nothing (dead) — cost: none.
74. iOS claims the `albunyaantube` scheme (CLAUDE.md back-compat rule; the backend watch pages work unmodified; Phase 1's DeepLinkParser already implements it) — cost: none.
