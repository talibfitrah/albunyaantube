# Phase 1 rulings on INDEX.md open questions and contradictions (2026-08-23)

Default: Android parity. Deviate only where Android is buggy, where iOS has no equivalent, or where the spec already decided. Each line: ruling — why — cost if wrong.

1. Icon: upscale `albunyaantube_logo.png` (800 px) to 1024 px on an opaque brand-green `#275E4B` plate — no master exists — cost: a soft icon until the user supplies a 1024 master (asked in the phase summary).
2. Splash: logo visible from frame 0; the rest of the 2750 ms timeline as Android (name fade+slide at 600 ms, tagline, spinner, hold) — a blank first 600 ms is a defect, not design — cost: 600 ms earlier logo.
3. Onboarding non-dismissible until Skip/Get started — parity — cost: none.
4. Deep link before onboarding/routing: hold in `Router.pendingRoute`, apply after the shell appears — Android drops it (defect) — cost: ~10 lines.
5. Splash spinner decorative at its Android time; routing happens at max(2750 ms, remote-config+fetchMe done, capped at 3250 ms) — same shape as Android's +500 ms grace — cost: none.
6. `minAppVersion` gate: phase 2 (arrives with remote config) — cost: none now.
7. Guest routing surfaces nothing when `fetchMe` fails; the offline banner and the Me sign-in card cover it — cost: none.
8. Sign-out → guest Main shell — spec D11 — cost: none.
9. Offline banner uses spec `errorBackground`/`errorText` tokens — Android's M3 colours are not tokens — cost: cosmetic.
10. Home title 24 pt bold on every width class (Android) — parity — cost: none.
11. Empty state caused by an active category filter gets a "Clear filter" action (Home + tabs) — obvious gap — cost: one button.
12. Empty/error states live inside the refreshable scroll view so pull-to-refresh always works — Android blocks it (defect) — cost: none.
13. Load-more failures: silent on Home/Featured (parity); tabs show the transient banner with Retry (parity, unified across the three tabs) — cost: none.
14. Channels/Playlists on compact width are 1-column rows (Android), 3/4-column grids on regular/large; spec §7 "2/3/4" is corrected to "1/3/4" — cost: none.
15. Categories entry stays on Channels only, as a toolbar button (no FAB on iOS) — parity of IA, native idiom — cost: none.
16. Skeletons shimmer (spec token) and mirror the real layout (grid skeleton for grids) — cost: one extra skeleton variant.
17. `channelName ← category` bug fixed: pass `channelTitle`, fall back to category only when nil — cost: none.
18. Length/date/sort: dormant state + params, no UI — spec §3 — cost: none.
19. `FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"` mirrored as a constant — parity; remote config may carry it later — cost: none.
20. Featured gets an empty state and pull-to-refresh — free in SwiftUI — cost: none.
21. Paging 3 stack not ported — dead code — cost: none.
22. Debounce: tab inline search 300 ms, Search screen 500 ms; both require ≥2 characters before querying (server requires ≥2) and clear results below that — cost: none.
23. Search: <2 chars → zero-state (history or prompt); no-history zero-state shows an EmptyState prompt; error shows ErrorState with Retry — fixes three Android gaps — cost: none.
24. Toasts/snackbars → one `TransientBanner` (bottom, 2.5 s auto-dismiss, optional action, VoiceOver announcement) — cost: one component.
25. "Parent › Sub" via a localized format `"%1$@ › %2$@"` with bidi isolates — cost: one string ×3 locales.
26. Filter stores category id + display name; the name is re-derived from the categories cache whenever available (language change) — cost: none.
27. Categories sorted by `displayOrder` then localized name — cost: none.
28. `CategoryDto` drift fixed in `api-specification.yaml` (optional `displayOrder`, `localizedNames`, `icon`), Swift client regenerated — cost: TS/Kotlin regenerate (additive).
29. Categories/Subcategories get skeleton/error/empty states — cost: reuse of existing views.
30. Favorites clear-all soft-deletes (deleted=1, dirty=1) so phase-4 sync pushes tombstones — Android hard-delete is a sync bug — cost: none.
31. Favorites store failures surface via the transient banner — cost: none.
32. Storage Location row dropped (decorative on Android; iOS downloads live in the app container) — cost: none.
33. Language row shows the resolved language and opens iOS Settings; in-app picker strings not ported — spec §14 — cost: none.
34. About links verbatim from Android (`albunyaan.tube`, `github.com/albunyaan/albunyaan-tube`) — parity; user to confirm hosts — cost: a later string edit.
35. Developer dialog (7 taps) in phase 1 shows version/build, API base URL, device id; phase 2 adds resolver counters/cooldown — cost: none.
36. All non-dead strings converted in phase 1 (including detail/player/me keys) — the converter is a script; screens arrive in later phases — cost: none.
37. `video_views`/`live_watching_count`: `.xcstrings` substitutions form (plural selected by the clamped integer argument, display uses the compact string) as strings-assets.md §147-182 proposes — cost: if Xcode rejects the form, fall back to two keys (`_one`/`_other`) selected in Swift.

Contradictions: (2) drop the 18-char truncation, `lineLimit(1)`; (3) null view count → omit the views segment; (4) one time-ago ladder (today/days/weeks/months/years) everywhere; (5) trust shell-home/content-lists dimension overrides; (6) SF Symbols per strings-assets.md (compass → `safari`, download → `arrow.down.circle.fill`, channels → `tv`, videos → `film.stack`); (7) inactive dot uses a token (`textMuted`); (8) one `skeleton` token.
