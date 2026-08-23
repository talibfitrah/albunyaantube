# Phase-1 research index (for the iOS plan author)

Six research files in this directory. Citations below are `file:line` into the research files; each cited line carries its own Android `path:line`. Spec = `docs/superpowers/specs/2026-08-23-ios-app-design.md`.

## 1. Per-file summaries (what it covers + 5 plan-critical facts)

### splash-onboarding.md — Splash, SplashRouter, Onboarding, launch/icon assets
Splash animation timeline, the three parallel t=0 tasks, deep-link skip, routing matrix with the iOS deviations (guest Main, remote-config gate), onboarding pages/metrics/behaviour, and the logo/app-icon source inventory.
- Timeline: 600 ms blank → logo snap → name then tagline (400 ms fade + 30 dp slide, 150 ms gap) → spinner → 800 ms hold = 2750 ms; update probe bounded to +500 ms (`splash-onboarding.md:65-90`).
- Router, first match wins: `!onboarding → Onboarding; !signedIn → SignIn; me==nil → SignIn; ACTIVE → Main; PENDING_PROFILE → Bootstrap; else SignIn`. iOS replaces every SignIn with guest Main and drops the toast + forced sign-out (`:154-184`).
- Route exactly once, guarded on "still the splash root"; deep link skips animation and probe but still goes through the router, so the URL is dropped when onboarding/auth intervene (`:120-147`).
- Onboarding: 3 static pages, Skip == Get Started, persist `onboarding_completed` and await the write before dismissing; signed-out exit → guest Main (`:311-332`).
- Assets: only `albunyaantube_logo.png` 800×800 is a real bitmap; launcher foreground max 432 px; launcher background deliberately transparent → iOS icon needs a fresh 1024 px export on an opaque plate (`:350-391`).

### shell-home.md — MainShellFragment (tabs, offline banner) + Home feed
Tab order/icons/colours, reselect semantics, offline banner + NetworkMonitor, nav-hide for fullscreen; Home header/pill/sections/carousel cards, HomeViewModel state + paging, visibility matrix, skeleton/empty/error cards, `/api/v1/home` contract.
- Tabs: Home, Channels, Me, Playlists, Videos; tint-only selection (no pill); reselect on sub-screen → pop to root, at root → scroll-to-top (broken on Home on Android — fix) (`shell-home.md:31-69`).
- Offline banner: top overlay, non-blocking, aggregate reachability via `NWPathMonitor`, stays visible in fullscreen; colours are M3 baseline, no spec token (`:71-105`).
- Home state `Loading | Success(sections, hasMore, isLoadingMore) | Error | Empty`; load on every distinct category; loadMore dedupes by `categoryId`, `hasMore = hasNext && nextCursor != nil`, pagination failures silent (`:315-380`).
- Carousel = one mixed row per category; card width `((w − 2·margin − (n−1)·gap)/n)·0.98`, visible counts vid/ch/pl 2/2/2 → 3/4/3 → 5/6/5; playlists wrongly get video width on Android; recompute on iPad resize (`:215-246`).
- Fix-list: double initial fetch from filter seed, pull-to-refresh wipes content to skeleton, `channelName ← category` bug, null viewCount → "0 views", 18-char title truncation, 300 px raw scroll threshold (`:478-496`).

### content-lists.md — Channels / Playlists / Videos tabs, ContentListViewModel, FilterManager, Featured
One shared VM for three tabs, inline tab search, column rules, two auto-load mechanisms, per-LoadingType visibility matrix, empty/skeleton/chip/FAB chrome, item layouts + formatting rules, filter persistence, Featured dual-mode screen.
- Do not port Paging 3 — dead code; pagination is hand-rolled in `ContentListViewModel` (`content-lists.md:12-22,138-158`).
- State `Loading(INITIAL|REFRESH|PAGINATION) | Success(items, hasMoreData, paginationError?, isSearchActive) | Error`; `hasMore = nextCursor != nil`; one Task per load kind to avoid the shared-job race (`:70-134`).
- Auto-load: scroll-down only, last visible ≥ count−5, plus `AutofillPaginationHelper` with 6 guards (≥600 dp, hasMore, no pagination error, ≤5 attempts, progress invariant, content fits) (`:209-237`).
- Columns: Channels/Playlists 1/3/4 (spec says 2/3/4), Videos always grid `max(2, w/180)` (`:187-207`); Featured: probe `/home` → Sections mode else flat `/content?type=ALL&limit=50`, hardcoded `FEATURED_CATEGORY_ID`, silent load-more failures, no empty state, no pull-to-refresh (`:603-701`).
- Only `category` is ever written to `FilterState`; length/date/sort are persisted+sent but have no UI; Subcategory label is literal `"Parent > Sub"`, pops past Categories to origin (`:510-599`).

### search-categories.md — Search screen, Categories, Subcategories, Featured "See all"
Search entry/exit, field/debounce/history semantics, 5-state layout, flat server-ordered results, `/search` server behaviour; Categories/Subcategories rows, localisation, silent failure, tap → filter → toast → pop; CategoryDto drift; See-all navigation.
- Search: 500 ms debounce, min 2 chars, submit bypasses both; `limit=50`, no paging; server orders channels → playlists → videos, do not regroup; YouTube URL/ID fast path (`search-categories.md:40-61,134-149,259-281`).
- History: `search_prefs/search_history`, `|`-joined, max 10, dedupe-then-prepend; iOS should store `[String]` (`:63-84`).
- Categories: one flat fetch, client derives tree; two full fetches per drill-down on Android — fetch once on iOS; ordering not sorted anywhere; no loading/error/empty UI at all (`:347-359,384-412`).
- `CategoryDto` OpenAPI drift: `displayOrder`, `localizedNames`, `icon` missing from YAML → hand-written wrapper or fix the spec (`:414-452`).
- See-all passes raw name from Home but localised name from Featured; Featured is recursively pushable; `lastLoadFailed` latch prevents auto-fill loops (`:493-581`).

### favorites-settings-about.md — Favorites, Settings, About, Developer dialog, prefs, locale
Room `FavoriteVideo` + DAO semantics (uid scoping, soft delete), FavoritesViewModel/screen, every Settings row/dialog/switch, sign-out, storage display, About links + 7-tap gate, `SettingsPreferences` keys, `LocaleManager` resolution.
- Favorites list = current uid, `deleted=0`, `APPROVED` only, `addedAt DESC`, re-subscribe on auth change; remove = soft delete + push, no confirm; clear-all hard-deletes without push (bug) (`favorites-settings-about.md:27-63,105-116`).
- Favorites row → player with 5 args only (no description/viewCount/channelId); no loading/error state on the screen; `uiEvents` never collected (`:65-72,107`).
- Settings row order (16 rows, 7 sections) + tablet layouts drop the Account/sign-out section (bug, do not port); rows 14–15 (updates) omitted on iOS per D3 (`:139-164,242-246`).
- Dialogs: single-choice tap-to-commit; theme `system/light/dark`, quality `low/medium/high`; five dead Android switches must work on iOS (D12); sign-out → guest Main, not SignIn (`:166-233`).
- Persisted keys/vocab to keep verbatim: `app_locale, audio_only, background_play(true), download_quality, wifi_only_downloads, safe_mode(true), theme, onboarding_completed, import_offer_shown`; system-locale resolution walks preferredLanguages ∩ {en,ar,nl} else en (`:322-350`).

### strings-assets.md — Localisation conversion rules + asset inventory
Four strings files per locale, translation coverage, plurals (Arabic 6-category, two decoupled-quantity plurals), format-specifier rewrite rules, escape rules, dead keys, 281 phase-1 keys with English text, drawable → SF Symbol map, raster assets.
- Read all four `strings*.xml`; keep Android keys verbatim; 120 keys untranslated in ar/nl (51 phase-1, 16 user-visible) → omit locale entry, never copy English (`strings-assets.md:15-88,222-235,303-313`).
- `video_views` / `live_watching_count` select plural from a clamped integer (≥1000 → `other`) not the printed compact string → two-arg `substitutions` form + a `CountFormat` port (`:147-182`).
- Rewrite `%N$s→%N$@`, `%N$d→%N$lld`, `%%` first; 8 Arabic plural items legitimately drop the specifier; `about_version_format` `%2$d→%2$@` (`:237-261,396-399`).
- 140 of 789 keys are dead (filter vocabulary, `list_*` states, `locale_settings_*`, typed `error_*`) — do not port (`:328-365`).
- Phase-1 imports: zero vectors (all SF Symbols), `albunyaantube_logo.png` for splash, new 1024 px icon; shape drawables become SwiftUI shapes (`:718-812,844-852`).

## 2. Shared behaviours (defining file)

**Paging**
- Tabs: load-more when scrolling down and last visible ≥ count−5; guard `!isLoadingMore && !isRefreshing && hasMore`; `hasMore = nextCursor != nil`; page 20 — `content-lists.md:209-218,48-50,109-112`.
- Tabs autofill (content shorter than viewport): 6 guards incl. ≥600 dp gate and 5-attempt cap — `content-lists.md:219-237`.
- Home: threshold 300 raw px (use 150–300 pt), content-fits check after every Success, in-flight flag set synchronously before launch — `shell-home.md:401,427-433,369-377`.
- Featured: threshold 5, every downward scroll clears `lastLoadFailed`, unguarded content-fits autofill (reuse tab guards) — `content-lists.md:663-678`; `search-categories.md:571-581`.
- Page sizes: `/content` 20 (tabs) / 50 (Featured flat); `/home` categoryLimit 5 + contentLimit 10 phone / 20 iPad (Home), 10 + 20 (Featured probe); `/search` 50, no paging — `content-lists.md:789-793`; `shell-home.md:380,435-444`; `search-categories.md:686-696`.
- Load-more failures are silent on Home and Featured; tabs show a one-shot snackbar and keep cursor — `shell-home.md:376`; `content-lists.md:118-126,663-673`.
- One `Task` per load kind, never a shared handle — `content-lists.md:128-134`.

**Filter state**
- Shape + DataStore keys (`filter_category`, `filter_category_name`, `filter_length`, `filter_date`, `filter_sort`) — `content-lists.md:510-547`; `shell-home.md:178-186`.
- Only Categories/Subcategories write it, only `category` ever changes; store id, label is display-only and goes stale on language change — `content-lists.md:549-599`; `search-categories.md:621-627`.
- Consumers: Home sends `category` only; tabs send all five; Featured flat builds a fresh `FilterState(category:)`; `ANY/DEFAULT` → omit param — `shell-home.md:186`; `content-lists.md:29-43,638-639`.
- Cold-start double fetch (default state then persisted value) on Home and tabs — read persisted value before first fetch — `shell-home.md:359`; `content-lists.md:572-575`.
- Active-filter UI: Home pill (clear ⟷ chevron mutually exclusive) — `shell-home.md:156-176`; tabs chip "Category: %1$s" between search bar and list — `content-lists.md:336-352`.

**Skeleton / empty / error**
- Home visibility matrix (Loading/Success/Error/Empty; header + pill always visible) — `shell-home.md:382-401`; skeleton = 1 row × 4 static cards — `:403-412`; error card shows static copy + Retry, empty has no action — `:414-425`.
- Tabs matrix per `LoadingType`; initial-load error keeps the skeleton and relies on the offline banner; 6-row static list skeleton even on grids; empty replaces the list (blocks refresh) — `content-lists.md:247-271,308-323,285-306`.
- Featured: spinner / error text + Retry / no empty state — `content-lists.md:680-699`. Search: 5 states, blank zero-state with no history, error has no retry — `search-categories.md:99-132`.
- Categories/Subcategories: no loading/error/empty UI at all — `search-categories.md:347-359,471-472`. Favorites: empty only — `favorites-settings-about.md:96-101`. Settings/About: none — `:180`.
- Error `message` is captured but never shown on Home and Search; shown raw on Featured — `shell-home.md:399`; `search-categories.md:127-131`; `content-lists.md:690-693`.

**Toasts / snackbars (iOS needs one transient-banner component or an explicit "none" rule)**
- Shell/Home/Search/Favorites: none — `shell-home.md:91`; `search-categories.md:132`; `favorites-settings-about.md:72`.
- Tabs: snackbar on pagination error; Videos-only snackbar on error-with-content (unify) — `content-lists.md:273-283`.
- Categories/Subcategories: toast "Filtering by: X" / "Failed to apply category filter" — `search-categories.md:369-382`.
- Settings: toast for language/theme, snackbar for quality/storage/clear-downloads; About 7-tap countdown toasts — `favorites-settings-about.md:197-233,279-288`.
- Splash `splash_couldnt_connect` toast is dropped under guest routing — `splash-onboarding.md:173-184`.

**Navigation args**
- Player fast path, 7 args: `videoId, title, channelName, thumbnailUrl, description, durationSeconds, viewCount(-1 sentinel)`; `channelName` is wrongly `category` everywhere; prefetch fires before push — `shell-home.md:290-302`; `content-lists.md:354-370`; `search-categories.md:26-38`. Favorites passes only 5 (no description/viewCount/channelId) — `favorites-settings-about.md:107`.
- Channel detail: `channelId, channelName, channelAvatarUrl` (+`excluded=false` from tabs) — `content-lists.md:358`. Playlist detail: `playlistId, playlistTitle` from tabs; +`playlistCategory, playlistCount` from Home/Featured/Search — `content-lists.md:359,371-372`.
- `featured(categoryId="", categoryName="")` optional; `subcategories(categoryId, categoryName)` required; See-all name raw from Home vs localised from Featured — `content-lists.md:610-619`; `search-categories.md:518-534`.
- Pop semantics: subcategory pick pops past Categories to origin; Settings → Favorites/Downloads keeps Settings underneath; splash/onboarding replace the root — `content-lists.md:596-598`; `favorites-settings-about.md:118-125`; `splash-onboarding.md:165-171`.
- Every navigate is guarded by a current-destination check — `favorites-settings-about.md:123`; `splash-onboarding.md:137-138`.

**Other cross-cutting**
- `bottom_nav_height` 72 dp phone / 0 tablet is a manual inset on every list → use safe areas — `content-lists.md:769-772`; `search-categories.md:634-636`.
- Formatting: duration `h:mm:ss`/`m:ss` with `Locale.US`; time-ago ladder; compact counts with 1 fraction digit + plural clamp — `content-lists.md:462-491`; `strings-assets.md:147-182`.
- Category display-name resolution by `Locale.language` duplicated in 6 places → one helper — `search-categories.md:336-345`.
- Spacing/type/radius token tables — `favorites-settings-about.md:7-21`; `content-lists.md:380-392`; colour token cross-ref — `shell-home.md:499-521`.
- RTL: `layoutDirection=locale` everywhere; chevrons/back/play/logout mirror — `strings-assets.md:35-38,751-766`.

## 3. Open questions (merged, deduplicated)

1. App icon: no ≥1024 px master (800 px logo, 432 px foreground) and the launcher background is transparent — upscale/re-export, and which opaque plate colour? — `splash-onboarding.md:397`; `strings-assets.md:799-800`.
2. Splash: first 600 ms is blank on Android — keep, or show the logo from frame 0? — `splash-onboarding.md:398`.
3. Onboarding must be non-dismissible (no back/swipe-down) until Skip/Get Started — confirm — `splash-onboarding.md:399`.
4. Deep link arriving before onboarding/with no account: hold and apply after, or drop like Android? — `splash-onboarding.md:400`.
5. Splash spinner: decorative at fixed t=1550 ms, or bound to real remote-config/`fetchMe` work? — `splash-onboarding.md:401`.
6. Remote-config `minAppVersion` gate has no Android counterpart: timeout, blocking-screen copy (reuse `available_versions_*`?), force vs recommend — `splash-onboarding.md:402`; `strings-assets.md:664-666`.
7. Guest routing removes the "couldn't connect" toast — is anything surfaced when background `fetchMe` retry also fails? — `splash-onboarding.md:403`.
8. Sign-out from Settings → guest Main shell (D11), not a sign-in wall — confirm — `favorites-settings-about.md:396`.
9. Offline banner colours: M3 baseline error pair or spec `accentRed`? — `shell-home.md:79`.
10. Home title is 24 sp on every bucket vs spec headline 20/24 — which wins? — `shell-home.md:137`.
11. Empty state caused by a category filter has no clear-filter action (Home/tabs) — add one? — `shell-home.md:423,492`.
12. Empty state replaces the list so pull-to-refresh is unreachable — put it inside the refreshable scroll view? — `content-lists.md:871-873`.
13. Silent load-more failures on Home/Featured: keep silent or add an inline retry row? — `shell-home.md:485`; `content-lists.md:859-861`.
14. Channels/Playlists on compact width: Android 1 column rows vs spec 2 columns (needs new grid cells) — `content-lists.md:830-834`.
15. Categories entry: only Channels has a FAB — add a toolbar button on Playlists/Videos too? — `content-lists.md:836-838`.
16. Skeleton: animate shimmer (spec has a token; Android is static) and mirror grid shape instead of a 6-row list? — `content-lists.md:840-843`; `shell-home.md:412`.
17. `channelName ← category` bug in every player push: fix (fallback to category only when nil) or replicate? — `content-lists.md:845-848`; `shell-home.md:302`.
18. Length/date/sort filters: persisted, sent, fully translated, but no UI anywhere — build the pickers (D12-style) or ship dormant fields? — `content-lists.md:850-853`; `strings-assets.md:336-345`.
19. `FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"` hardcoded — mirror, remote config, or backend endpoint? — `content-lists.md:855-857`; `search-categories.md:550-553`.
20. Featured has no empty state and no pull-to-refresh — add both? — `content-lists.md:859-861`; `search-categories.md:597-598`.
21. Confirm the dead Paging 3 stack is skipped entirely — `content-lists.md:863-865`.
22. Search debounce policy: tab inline search is 300+300 ≈ 600 ms with no min length; Search screen is 500 ms with min 2 chars (server also requires ≥2) — one rule for both? — `content-lists.md:867-869`; `search-categories.md:46-59`.
23. Search edge cases: 1-char query leaves stale results on screen; empty state with no history is a blank screen; error has no retry — match or fix? — `search-categories.md:56-59,109-111,131`.
24. iOS has no toasts and spec defines none — what replaces the category-filter, settings and pagination toasts/snackbars? — `search-categories.md:380-382`; `favorites-settings-about.md:385`.
25. `"Parent > Sub"` filter label: literal ASCII separator, not localised or RTL-safe — keep or use a localised format? — `search-categories.md:474-479`.
26. Filter label stored at write time goes stale after a language change — store id only and re-derive? — `search-categories.md:623-626`.
27. `/categories` ordering is unsorted on both sides — sort by `displayOrder`, then name, on iOS? — `search-categories.md:409-412`.
28. `CategoryDto` drift: hand-written wrapper on iOS vs fixing `api-specification.yaml` — `search-categories.md:435-441`.
29. Categories/Subcategories have no loading/error/empty UI — add spec `ErrorState` + `EmptyState`? — `search-categories.md:351-356`.
30. Favorites clear-all: Android hard-deletes without a sync push (server pull resurrects) — tombstone + push instead? — `favorites-settings-about.md:395`.
31. Favorites remove/clear failures: `uiEvents` never collected on Android and strings are hardcoded English — surface or stay silent? — `favorites-settings-about.md:401`.
32. Storage Location row is decorative on Android — drop on iOS or show a read-only line? — `favorites-settings-about.md:397`.
33. Language row: deep link to iOS Settings per spec — confirm the row stays with a resolved value and the in-app picker + its 10 strings are dropped — `favorites-settings-about.md:398`; `strings-assets.md:49-54`.
34. About links use `albunyaan.tube` while deep links use `app.fitrahtube.com` — which host ships? — `favorites-settings-about.md:399`.
35. Developer dialog: which `InnerTubeKit` flags replace the three Android ones; keep cooldown/telemetry rows? — `favorites-settings-about.md:400`.
36. 55 strings are used only by Channel/Playlist detail, which is in neither the phase-1 nor the out-of-phase list — decide the slice — `strings-assets.md:423-427`.
37. `video_views`/`live_watching_count`: `.xcstrings` `substitutions` form vs resolving the plural category in Swift — `strings-assets.md:177-182`.

## 4. Contradictions between files

1. **Tab search exists or not.** `search-categories.md:19-22` says there is no search field on Channels/Playlists/Videos; `content-lists.md:164-185` documents an inline in-header search on all three (`fragment_simple_list.xml:32-39`). content-lists is right; search-categories meant "no navigation to SearchFragment".
2. **18-char section title truncation.** `shell-home.md:204` says port it verbatim; `search-categories.md:503-505` says drop it and use `.lineLimit(1)`. Plan must pick (spec §7 forbids `minimumScaleFactor`, which favours dropping).
3. **Null view count.** Home renders "0 views" via `video_views_format` (`shell-home.md:259,487`); tabs/Featured/Search omit the views segment entirely and use the `video_views` plural (`content-lists.md:479-487`; `search-categories.md:184-186`). Two Android adapters disagree; iOS needs one rule.
4. **Time-ago ladder.** Home only has Today / N days (`shell-home.md:260`); tabs/Featured/Search add weeks/months/years (`content-lists.md:467-477`; `search-categories.md:189-190`). Same root cause as #3.
5. **Home dimension overrides.** `search-categories.md:717,722-728` lists `home_horizontal_margin`, `home_vertical_section_spacing`, `home_card_spacing` and `icon_xlarge` with no sw600/sw720 override; `shell-home.md:227-238` and `content-lists.md:714-719` cite 16/24/32, 24/32/40, 12/16/20, and `favorites-settings-about.md:21` cites `icon_xlarge` 96→128. Trust shell-home/content-lists; the search-categories addendum is incomplete.
6. **SF Symbol mappings differ.** `ic_channels`: `person.2` (`shell-home.md:38`) vs `tv`/`play.rectangle` (`strings-assets.md:742`); `ic_videos`: `play.rectangle` (`shell-home.md:41`) vs `film.stack` (`strings-assets.md:745`); `ic_compass`: `play.circle.fill` (`splash-onboarding.md:365`) vs `safari` (`strings-assets.md:763`); `ic_download_circle`: `arrow.down.to.line` (`splash-onboarding.md:367`) vs `arrow.down.circle.fill` (`strings-assets.md:749`). splash-onboarding and strings-assets inspected the vector paths; prefer them over shell-home, and settle compass/download in the plan.
7. **Inactive onboarding dot.** `splash-onboarding.md:309` says replace `#CCCCCC` with a dark-aware token; `strings-assets.md:780` ports the literal. Prefer the token.
8. **Skeleton fill token.** Home skeleton uses `shimmer_background_color` #E0E0E0 (`shell-home.md:407`); list skeleton uses `skeleton_shimmer` = `surface_variant` #E3E9E7 at 20 dp radius (`content-lists.md:316-318`; `strings-assets.md:789,883-886`). Two Android tokens; spec has one `skeleton` token — use it.
9. **Spec line citations drift** by a few lines across files (e.g. FilterState at `:123` in search-categories vs `:127` in content-lists; carousel table at `:164`/`:165`/`:168`). Re-resolve against the spec before quoting.
