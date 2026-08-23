# Phase 1 Research — Shell + Home

**Scope:** `MainShellFragment` (tab shell) and `HomeFragment`/`HomeViewModel`/`HomeSectionAdapter`/`HomeFeaturedAdapter` + `item_home_*` layouts.
**Target:** SwiftUI, iOS 18, iPhone + iPad. Android IA + tokens, native iOS idioms (spec D5, `docs/superpowers/specs/2026-08-23-ios-app-design.md:23`).
**Citations** are `path:line` relative to the repo root `/Users/farouqabouumar/Development/albunyaantube`. Android sources live under `android/app/src/main/`.

Read first: spec §6 Navigation (`docs/superpowers/specs/2026-08-23-ios-app-design.md:103-128`) and §7 Design system (`:130-171`). This document is the behavioural contract underneath those two sections for these two screens. Where Android and the spec disagree, the spec wins and the divergence is flagged.

---

## PART A — MainShellFragment (tab shell)

### A1. Structure per screen-width qualifier

Three layouts, identical semantics, different nav chrome:

| Qualifier | Nav widget | Nav size | Content offset | Banner offset |
|---|---|---|---|---|
| default (phone) | `BottomNavigationView` bottom | height `bottom_nav_height` = **72 dp** (`android/app/src/main/res/values/dimens.xml:26`) | none | none |
| `sw600dp` | `NavigationRailView` start edge | width `navigation_rail_width` = **80 dp**, icon **28 dp** (`android/app/src/main/res/values-sw600dp/dimens.xml:10-11`); `bottom_nav_height` = **0 dp** (`:6`) | `marginStart = 80 dp` (`android/app/src/main/res/layout-sw600dp/fragment_main_shell.xml:38`) | `marginStart = 80 dp` (`:49`) |
| `sw720dp` | `NavigationRailView` start edge | width **96 dp**, icon **32 dp**, label **14 sp** (`android/app/src/main/res/values-sw720dp/dimens.xml:9,13,14`); rail gets top/bottom padding `spacing_md` = 24 dp (`android/app/src/main/res/layout-sw720dp/fragment_main_shell.xml:23-24`) | `marginStart = 96 dp` (`:38`) | `marginStart = 96 dp` (`:49`) |

`is_tablet` bool: false default, true at sw600dp and sw720dp (`android/app/src/main/res/values/dimens.xml:114`, `values-sw600dp/dimens.xml:51`, `values-sw720dp/dimens.xml:55`).

Rail settings shared by both tablet buckets: `menuGravity=center` (items vertically centred, not top-aligned) (`android/app/src/main/res/layout-sw600dp/fragment_main_shell.xml:25`), `labelVisibilityMode=labeled` (`:29`), `itemActiveIndicatorStyle=@null` — the M3 pill selection indicator is **explicitly disabled** on all three buckets so the selected state is *tint only* (`android/app/src/main/res/layout/fragment_main_shell.xml:72`, `layout-sw600dp/…:28`, `layout-sw720dp/…:28`).

Phone bottom nav also sets `itemRippleColor=transparent` and `itemHorizontalTranslationEnabled=false` (`android/app/src/main/res/layout/fragment_main_shell.xml:71,73`).

**iOS mapping:** `TabView(.sidebarAdaptable)` per spec §6 (`docs/superpowers/specs/2026-08-23-ios-app-design.md:117`). Compact width → bottom tab bar; regular width → sidebar/rail. The 80 vs 96 dp rail split maps to the spec's regular <1000 pt / regular ≥1000 pt width classes (`:157`). Do not draw a selection pill; use tint-only selection to match.

### A2. Tabs — order, ids, labels, icons

Order is menu order, **not** nav-graph order (`android/app/src/main/res/menu/bottom_nav_menu.xml:3-22`):

| # | Destination id | String key | English | Android drawable | Suggested SF Symbol |
|---|---|---|---|---|---|
| 1 | `homeFragment` | `nav_home` | **Home** (`android/app/src/main/res/values/strings.xml:152`) | `ic_home` (filled house, `android/app/src/main/res/drawable/ic_home.xml`) | `house.fill` |
| 2 | `channelsFragment` | `nav_channels` | **Channels** (`:153`) | `ic_channels` | `person.2` / `rectangle.stack.person.crop` |
| 3 | `meFragment` | `nav_me` | **Me** (`:156`) | `ic_nav_me` | `person.crop.circle` |
| 4 | `playlistsFragment` | `nav_playlists` | **Playlists** (`:154`) | `ic_playlists` | `list.bullet.rectangle` |
| 5 | `videosFragment` | `nav_videos` | **Videos** (`:155`) | `ic_videos` | `play.rectangle` |

Graph start destination = `homeFragment` (`android/app/src/main/res/navigation/main_tabs_nav.xml:6`).

Tab item colours (selector, `android/app/src/main/res/color/bottom_nav_item_color.xml:4-13`), applied to **both** icon tint and label:
- checked → `@color/primary` = `primary_green` (#275E4B light / #35C491 dark) (`android/app/src/main/res/values/colors.xml:3,9`, `values-night/colors.xml:4,10`)
- focused, not checked → `nav_item_focused` #424242 light / #E0E0E0 dark (`values/colors.xml:63`, `values-night/colors.xml:61`) — D-pad/keyboard only; on iOS this is the focus/hover state, optional
- default → `nav_item_inactive` #757575 light / #B0B0B0 dark (`values/colors.xml:62`, `values-night/colors.xml:60`)

Nav bar/rail background is `background_gray` (#F5F5F5 light / #121212 dark) with `elevation_lg` = 8 dp (`android/app/src/main/res/layout/fragment_main_shell.xml:65-66`, `values/dimens.xml:121`, `values/colors.xml:27`, `values-night/colors.xml:48`).

### A3. Tab selection and reselection behaviour (exact)

Android does **not** use per-tab back stacks. One `NavController` on one graph; the tab bar drives a single stack (`android/app/src/main/java/com/albunyaan/tube/ui/MainShellFragment.kt:72-104`).

`setOnItemSelectedListener` (`MainShellFragment.kt:79-104`):
1. If tapped tab id == current destination id → return `true`, do nothing (`:84-87`).
2. Else `navController.popBackStack(item.itemId, false)` — pop *to* that destination if it is anywhere on the stack (`:90`).
3. If the pop returned false (tab not on stack) → `navController.navigate(item.itemId)`, wrapped in try/catch that only logs on failure (`:92-100`).

`setOnItemReselectedListener` (`MainShellFragment.kt:107-126`) — fires when the already-selected tab is tapped:
- If current destination ≠ the tab id (user is on a sub-screen pushed from that tab) → `popBackStack(tabId, false)`, i.e. **pop to the tab root** (`:111-113`).
- Else (already at tab root) → find the primary nav fragment's `RecyclerView` with id `recyclerView` and `smoothScrollToPosition(0)` (`:117-124`).

**iOS contract:**
- Re-select on a pushed screen → pop that tab's `NavigationStack` to root.
- Re-select at root → animated scroll to top (`ScrollViewReader` / `.scrollPosition`).
- **Home is an edge case:** its scroll container is a `NestedScrollView` (`android/app/src/main/res/layout/fragment_home_new.xml:9`) and its list id is `homeSectionsRecyclerView`, **not** `recyclerView` — so scroll-to-top on Android silently does nothing on the Home tab (`MainShellFragment.kt:118` looks up `R.id.recyclerView`). iOS should implement scroll-to-top for Home properly (fix the bug, do not port it).
- Because Android has one shared stack, deep pushes from tab A then switching to tab B and back can restore a different position than iOS's per-tab stacks would. iOS uses per-tab `NavigationStack` (spec §6, `docs/superpowers/specs/2026-08-23-ios-app-design.md:117`); that is a deliberate improvement, not a parity break.

### A4. Offline banner

Widget: horizontal row pinned to the **top** of the shell, overlaying content, non-blocking (content below stays interactive) (`android/app/src/main/res/layout/fragment_main_shell.xml:19-54`).

- Icon: `ic_cloud_off`, **20 dp × 20 dp**, tint `?attr/colorOnErrorContainer`, `importantForAccessibility="no"` (`:37-42`). SF Symbol: `wifi.slash` or `icloud.slash`.
- Text: string key **`connectivity_offline_banner`** = **"You're offline. Check your connection."** (`android/app/src/main/res/values/strings.xml:213`), `textAppearanceLabelLarge`, colour `?attr/colorOnErrorContainer`, `textAlignment=viewStart` (start-aligned, RTL-aware) (`:44-52`). Translated in `values-ar` and `values-nl`.
- Layout: `paddingStart/End = spacing_md` (16 dp phone / 20 dp sw600 / 24 dp sw720), `paddingTop/Bottom = spacing_sm` (8 dp), `gravity=center_vertical`, icon→text gap `spacing_sm` = 8 dp (`:30-33,48`).
- Elevation `elevation_lg` = 8 dp (`:27`).
- **Colours are Material3 baseline, not app tokens.** `?attr/colorErrorContainer` / `?attr/colorOnErrorContainer` are never overridden in the app theme (`android/app/src/main/res/values/themes.xml:3` parents `Theme.Material3.DayNight.NoActionBar`; no error-colour items). M3 baseline: light container #F9DEDC on #410E0B; dark container #8C1D18 on #F9DEDC. **Open question:** spec §7 has no offline-banner token (`docs/superpowers/specs/2026-08-23-ios-app-design.md:132-159`); pick either the M3 baseline pair or `accentRed`.
- Top inset: an explicit `setOnApplyWindowInsetsListener` applies the system-bars top inset as the banner's top padding, defensively (`MainShellFragment.kt:44-50`). iOS: place the banner inside the safe area (or add top safe-area padding when overlaying).
- Visibility: driven by `NetworkMonitor.isOnline` collected with `repeatOnLifecycle(STARTED)` — VISIBLE when offline, GONE when online (`MainShellFragment.kt:54-60`).

`NetworkMonitor` semantics to replicate with `NWPathMonitor` (`android/app/src/main/java/com/albunyaan/tube/util/NetworkMonitor.kt:18-58`):
- Emits the **aggregate** reachability, never a per-interface event. On any callback (available / lost / capabilities changed) it recomputes `isCurrentlyOnline()` from the *active* network (`:27-45,60-64`).
- The comment at `:19-26` records the bug this fixes: emitting `false` on every `onLost` makes the banner flash back during a Wi-Fi↔cellular handover. `NWPathMonitor` gives aggregate `path.status` directly, so just use it — do not add per-interface logic.
- `.distinctUntilChanged()` (`:58`) — de-dupe; on iOS use `.removeDuplicates()`.
- Emits the current state immediately on subscribe (`:53`) — no "unknown" first frame.
- `isWifiConnected()` also exists (`:66-70`), used elsewhere (Wi-Fi-only downloads); not used by the shell.
- No animation is specified on show/hide (plain visibility toggle). Suggest a subtle slide/fade on iOS, static under Reduce Motion (spec §7 motion rule, `docs/superpowers/specs/2026-08-23-ios-app-design.md:171`).

There is **no** Snackbar and **no** Toast anywhere in the shell or on Home (verified by grep over `MainShellFragment.kt`, `HomeFragment.kt`, `HomeSectionAdapter.kt`, `HomeFeaturedAdapter.kt` — zero hits).

### A5. Hiding nav chrome for fullscreen playback

`MainShellFragment.setBottomNavVisibility(visible: Boolean)` (`MainShellFragment.kt:141-170`):
- Sets the nav widget VISIBLE/GONE (`:143`).
- On tablets (`is_tablet == true`) also retargets the content container's `marginStart` between `navigation_rail_width` and 0 so the player fills the width freed by the rail (`:146-158`).
- On show, posts a layout pass that zeroes any accumulated padding and forces `requestLayout()` (`:162-169`) — an Android Material3 workaround with no iOS analogue.
- The **offline banner is not hidden** in fullscreen — it stays on top of the player.

Callers (`android/app/src/main/java/com/albunyaan/tube/ui/MainActivity.kt:579-584` resolves the shell fragment then forwards):
- `false` on entering fullscreen (`android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt:3567`)
- `true` on exiting fullscreen (`PlayerFragment.kt:3751`) and on player teardown (`PlayerFragment.kt:3875`)

**iOS contract:** entering fullscreen playback hides the tab bar / sidebar (`.toolbar(.hidden, for: .tabBar)` or a fullCover), and the content expands into the freed leading area on iPad. Exiting restores it. The offline banner remains visible over fullscreen.

### A6. Shell edge cases

- Banner and nav both sit in a `CoordinatorLayout` with `fitsSystemWindows=true` and root background `background_gray` (`android/app/src/main/res/layout/fragment_main_shell.xml:2-9`).
- `layoutDirection="locale"` on the shell root (`:9`) — full RTL support for Arabic. On iOS this is automatic; verify the rail lands on the leading edge in RTL.
- The nav view's own inset listener is deliberately swallowed so Material3 does not add bottom padding twice (`MainShellFragment.kt:68-70`); the white strip behind the transparent system nav is filled by the root's `background_gray`, not by extending the bar (comment `:62-67`). iOS equivalent: let the tab bar handle its own safe area; do not add manual bottom padding.
- `defaultNavHost="false"` on the nav host (`android/app/src/main/res/layout/fragment_main_shell.xml:17`) — the shell does not intercept system back.

---

## PART B — HomeFragment / HomeViewModel

### B1. Screen skeleton (top → bottom)

Root: `SwipeRefreshLayout` (id `swipeRefresh`) → `NestedScrollView` (id `homeScrollView`, `fillViewport=true`, `overScrollMode=never`, background `home_surface_background`) → vertical `LinearLayout` with `paddingBottom = bottom_nav_height` (72 dp phone / 0 dp tablet) (`android/app/src/main/res/layout/fragment_home_new.xml:2-21`).

Children in order (`:23-179`):
1. Header (title + search + overflow)
2. Category filter pill
3. `homeSkeleton` (include `home_section_skeleton`, initially gone)
4. `homeError` (include `home_section_error`, initially gone)
5. `homeEmpty` (include `home_section_empty`, initially gone)
6. `homeSectionsRecyclerView` (vertical list of sections, `nestedScrollingEnabled=false`, `clipToPadding=false`, `paddingBottom = spacing_lg`, initially gone)
7. `loadingMoreIndicator` — indeterminate `ProgressBar`, centred horizontally, `marginTop = spacing_md`, `marginBottom = spacing_lg`, tint `primary_green`, `contentDescription = @string/home_loading_more`

The `sw600dp` and `sw720dp` variants are byte-identical to the phone layout except for a comment; all tablet adaptation comes from dimension qualifiers (`android/app/src/main/res/layout-sw600dp/fragment_home_new.xml:2-3`, `layout-sw720dp/fragment_home_new.xml:2-3` — verified by diff).

### B2. Header

Constraint row, `paddingStart/End = home_horizontal_margin` (**16 / 24 / 32 dp** by bucket), `paddingTop = spacing_md`, `paddingBottom = spacing_sm` (`android/app/src/main/res/layout/fragment_home_new.xml:24-30`).

- Title: `@string/app_name` = **"FitrahTube"** (`android/app/src/main/res/values/strings.xml:3`), **24 sp bold**, colour `home_text_primary` (#1A1A1A / #F1F5F9), start-constrained (`fragment_home_new.xml:32-42`). Note: 24 sp here, while spec §7 lists `headline 20 bold (24 on large iPad)` (`docs/superpowers/specs/2026-08-23-ios-app-design.md:151`). Home's title is 24 sp on **every** bucket.
- Search button: `touch_target_min` square = **48 dp** (56 dp at sw720dp, `android/app/src/main/res/values-sw720dp/dimens.xml:41`), `ic_search`, tint `primary_green`, `contentDescription = @string/search` = **"Search"** (`strings.xml:254`), borderless (`fragment_home_new.xml:56-66`). Positioned immediately left of the overflow button.
- Overflow button: same 48 dp box, `ic_action_more` — **vertical** 3-dot glyph with a baked `android:alpha="0.6"` (`android/app/src/main/res/drawable-anydpi/ic_action_more.xml:6-7`), tint overridden to `primary_green`, `contentDescription = @string/menu` = **"Menu"** (`strings.xml:257`), end-constrained (`fragment_home_new.xml:44-54`).

**iOS mapping:** large-ish inline title + two trailing toolbar items. Overflow becomes a SwiftUI `Menu` with `ellipsis` (iOS convention is horizontal). The 0.6 alpha on the Android glyph is an asset accident, not a token — use full-opacity brand tint.

### B3. Overflow menu

`PopupMenu` anchored to the button with `Gravity.END`, icons forced visible (`android/app/src/main/java/com/albunyaan/tube/ui/HomeFragment.kt:298-316`; `android/app/src/main/java/com/albunyaan/tube/util/MenuIconExt.kt:12-25`).

Items, in order (`android/app/src/main/res/menu/home_menu.xml:3-10`):

| id | String key | English | Icon | Action |
|---|---|---|---|---|
| `action_downloads` | `downloads` | **Downloads** (`android/app/src/main/res/values/strings.xml:259`) | `ic_download` (SF: `arrow.down.circle`) | navigate to `downloadsFragment` (`HomeFragment.kt:304-307`) |
| `action_settings` | `settings` | **Settings** (`:258`) | `ic_settings` (SF: `gearshape`) | navigate to `settingsFragment` (`HomeFragment.kt:308-311`) |

Exactly two items. No dividers, no destructive items, no dynamic entries.

### B4. Category pill

`MaterialCardView` id `categoryPillCard`, width `wrap_content`, height `home_category_pill_height` = **40 dp**, corner radius **999 dp** (fully rounded), background `home_category_pill_bg` (#E8F5F0 light / #12352B dark), elevation **1 dp**, stroke 0, `foreground = selectableItemBackground` (`android/app/src/main/res/layout/fragment_home_new.xml:71-86`).

Margins: start/end `home_horizontal_margin`, top `spacing_sm` (8 dp), bottom `spacing_md` (16/20/24 dp) (`:75-78`).
Inner row: `paddingStart/End = home_category_pill_padding_horizontal` = **16 dp**, `gravity=center_vertical` (`:88-94`).

Contents left→right:
1. `categoryIcon` — `ic_category` (2×2 rounded squares grid; SF: `square.grid.2x2`), **20 dp** (`home_category_pill_icon_size`), tint `primary_green` (`:96-102`)
2. `categoryChip` — text, `marginStart = spacing_sm` (8 dp), **14 sp**, `sans-serif-medium`, colour `primary_green` (`:104-113`)
3. `categoryExpandIcon` — `ic_expand_more` (chevron down; SF: `chevron.down`), 20 dp, `marginStart = spacing_xs` (4 dp), tint `primary_green` (`:115-122`)
4. `categoryClearButton` — `ic_close` in a **48 dp** touch target with **8 dp** padding, tint `primary_green`, `contentDescription = @string/clear_filters` = **"Clear filters"** (`strings.xml:192`), initially GONE (`:124-135`)

Pill `contentDescription = @string/home_select_category` = **"Select content category"** (`strings.xml:305`).

Behaviour (`HomeFragment.kt:285-296`, `:196-206`):
- Bound to `filterManager.state` collected with `repeatOnLifecycle(STARTED)`.
- Label = `state.categoryName ?: getString(R.string.filter_category)`; `filter_category` = **"Category"** (`strings.xml:5`).
- `hasCategory = state.category != null` → clear button visible **iff** a category is set; the expand chevron is visible **iff** no category is set. They are mutually exclusive, never both.
- Tap pill → navigate `action_homeFragment_to_categoriesFragment` (`HomeFragment.kt:196-199`; `android/app/src/main/res/navigation/main_tabs_nav.xml:12-18`).
- Tap clear → `filterManager.setCategoryAndAwait(null, null)` in the view lifecycle scope (`HomeFragment.kt:201-206`).

Filter state (shared across Home/Channels/Playlists/Videos, persisted in DataStore) — `android/app/src/main/java/com/albunyaan/tube/data/filters/FilterState.kt:7-22`:
```
category: String?        // the category **id**, not the slug
categoryName: String?    // localized display name shown on the pill
videoLength: VideoLength = ANY
publishedDate: PublishedDate = ANY
sortOption: SortOption = DEFAULT
```
DataStore keys: `filter_category`, `filter_category_name`, `filter_length`, `filter_date`, `filter_sort` (`android/app/src/main/java/com/albunyaan/tube/data/filters/FilterManager.kt:82-86`). Clearing removes both category keys (`:48-56`). Writers store the category **id**: `CategoriesFragment.kt:77` and `SubcategoriesFragment.kt:59` both call `setCategoryAndAwait(category.id, displayName)`. Home only ever reads `category` and `categoryName`; length/date/sort do not affect the home feed request.

### B5. Sections list — order, header, carousel

Vertical: one row per `HomeSection`, `LinearLayoutManager` vertical, `isNestedScrollingEnabled = false` (the outer `NestedScrollView` owns scrolling) (`HomeFragment.kt:166-172`).

**Section order** is server-controlled: the backend sorts parent categories by `displayOrder` ascending, ties broken by category id ascending (`backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java:512-517`). The client never re-sorts. Sections with zero items are dropped server-side (`PublicContentService.java:606`).

Section item layout (`android/app/src/main/res/layout/item_home_section.xml`):
- Root `LinearLayout`, `marginTop = home_vertical_section_spacing` = **24 / 32 / 40 dp** by bucket (`:9`; `android/app/src/main/res/values/dimens.xml:125`, `values-sw600dp/dimens.xml:57`, `values-sw720dp/dimens.xml:61`), `layoutDirection=locale`.
- Header row: `paddingStart/End = home_horizontal_margin`, `minHeight = touch_target_min` (**48 dp**, 56 dp sw720) (`:12-17`).
  - `sectionIcon` — a `TextView` rendering the category's **emoji** at **20 sp**, `marginEnd = spacing_md`, GONE when the emoji is null/blank (`:19-30`; bound at `android/app/src/main/java/com/albunyaan/tube/ui/adapters/HomeSectionAdapter.kt:73-79`). It is text, not a vector icon — on iOS render the emoji string, do not map to SF Symbols.
  - `sectionTitle` — `TextAppearance.Home.SectionTitle` = **20 sp bold**, colour `home_text_primary`, `letterSpacing -0.01` (`android/app/src/main/res/values/styles.xml:22-27`); width 0dp between icon and See-all, `maxLines=1`, `ellipsize=end`, `marginStart = spacing_md`, `marginEnd = spacing_sm`, `horizontal_bias=0` (start-aligned) (`item_home_section.xml:32-47`).
  - `sectionSeeAll` — `@string/see_all` = **"See all"** (`strings.xml:246`), `TextAppearance.Home.SeeAll` = **14 sp**, `sans-serif-medium`, colour `primary_green` (`styles.xml:45-49`); trailing `ic_chevron_right` tinted `primary_green` with `drawablePadding = spacing_xs` (4 dp), `padding = spacing_sm` (8 dp), borderless ripple (`item_home_section.xml:49-62`). SF Symbol: `chevron.forward` (direction-sensitive per spec §7, `docs/superpowers/specs/2026-08-23-ios-app-design.md:169`).
- Carousel: horizontal `RecyclerView` id `sectionRecyclerView`, `marginTop = spacing_xs` (4 dp), `clipToPadding=false`, `clipChildren=false`, `paddingStart/End = home_horizontal_margin`, `paddingBottom = spacing_sm` (`:67-76`), `setHasFixedSize(true)` (`HomeSectionAdapter.kt:58-62`).

Section title text resolution (`HomeSectionAdapter.kt:65-94`):
1. `section.localizedNames?[currentLocaleLanguage] ?: section.categoryName` (`:68-70`) — `localizedNames` is a `Map<languageCode, String>` keyed by the **language** part of the current app locale.
2. Then **hard-truncated to 18 characters** with a trailing `…` after `trimEnd()`: `MAX_TITLE_CHARS = 18` (`HomeSectionAdapter.kt:98-102`). This is on top of the `maxLines=1` + `ellipsize=end` in the layout. Port the character truncation verbatim — it changes what a long category name reads as, independent of available width.
3. See-all `contentDescription = getString(R.string.home_see_all_category, displayName)` = **"See all content in %1$s"** (`strings.xml:310`), built from the **untruncated** display name (`HomeSectionAdapter.kt:91-93`).

"See all" tap → `action_homeFragment_to_featuredListFragment` with bundle `categoryId` = `section.categoryId`, `categoryName` = `section.categoryName` (the **raw** name, not the localized or truncated one) (`HomeFragment.kt:105-114`; destination args at `android/app/src/main/res/navigation/main_tabs_nav.xml:230-242`). iOS route: `featured(mode)` (spec §6, `docs/superpowers/specs/2026-08-23-ios-app-design.md:121`).

Diffing: sections keyed by `categoryId`; content equality via data-class `==` (`HomeSectionAdapter.kt:104-114`). Items keyed by `id` **within the same type** — a Video and a Channel with the same id are never considered the same item (`android/app/src/main/java/com/albunyaan/tube/ui/adapters/HomeFeaturedAdapter.kt:230-247`).

### B6. Carousel cards — three types, one horizontal list

One section's carousel contains a **mixed** list of videos, playlists and channels in server order; the adapter picks a layout per item type (`HomeFeaturedAdapter.kt:42-67`). There is no per-type carousel; the "carousels per type" model does not exist here — it is one heterogeneous row per category.

#### Card widths (computed, not fixed)

`HomeFragment.calculateCardWidths` runs once in `view.post { }` after first measure (`HomeFragment.kt:73, 76-100`):
```
width(n) = ((rootWidth - 2 * home_horizontal_margin - (n - 1) * home_card_spacing) / n) * 0.98
```
(integer truncation; returns 0 if `n <= 0`; the whole pass is skipped while `rootWidth == 0`) (`HomeFragment.kt:97-100`).

Inputs by bucket:

| dimen / integer | phone | sw600dp | sw720dp |
|---|---|---|---|
| `home_horizontal_margin` | 16 dp | 24 dp | 32 dp |
| `home_card_spacing` | 12 dp | 16 dp | 20 dp |
| `home_cards_visible_videos` | 2 | 3 | 5 |
| `home_cards_visible_channels` | 2 | 4 | 6 |
| `home_cards_visible_playlists` | 2 | 3 | 5 |
| `home_video_card_width` (fallback / skeleton) | 260 dp | 280 dp | 320 dp |
| `home_channel_card_width` (unused at runtime) | 100 dp | 110 dp | 120 dp |
| `home_playlist_card_width` (unused at runtime) | 220 dp | 240 dp | 280 dp |
| `home_channel_avatar_size` | 72 dp | 80 dp | 88 dp |
| `home_card_content_height` | 100 dp (no override) | 100 dp | 100 dp |

Sources: `android/app/src/main/res/values/dimens.xml:124,152-170`, `values-sw600dp/dimens.xml:56-67`, `values-sw720dp/dimens.xml:60-71`.

**Two known Android defects to fix, not port:**
1. `home_cards_visible_playlists` is defined in all three buckets but **never read** — `HomeFragment.kt:83-94` only computes `videoCardWidth` and `channelCardWidth`. `HomeFeaturedAdapter.applyWidth` gives channels `channelCardWidth` and **everything else** (videos *and* playlists) `cardWidth` (= the video width) (`HomeFeaturedAdapter.kt:78-86`). So playlists render at the video width on Android. On iOS, honour the playlist count (2 / 3 / 5) and the spec's carousel visible-card table (`docs/superpowers/specs/2026-08-23-ios-app-design.md:164`).
2. Widths are computed once per view creation. On Android a config change recreates the fragment so it self-corrects; on iPad, Split View / Slide Over / rotation resize the window **without** recreating the view — recompute on every geometry change (`GeometryReader` / `containerRelativeFrame`).

The `0.98` factor deliberately leaves a sliver of the next card visible as a scroll affordance. Keep it.

Card gaps: each card sets `layout_marginEnd = home_card_spacing` (12/16/20 dp) — including the **last** card, so the trailing edge has `home_horizontal_margin + home_card_spacing` of space (`item_home_video.xml:8`, `item_home_playlist.xml:8`, `item_home_channel.xml:8`).

#### Video card — `item_home_video.xml`

`MaterialCardView`, style `Widget.Albunyaan.MediaCard`: corner radius `home_card_corner_radius` = **16 dp**, elevation `home_card_elevation` = **2 dp**, background `home_card_background` (#FFFFFF light / #1A231F dark), `contentPadding = 0` (`android/app/src/main/res/values/styles.xml:91-97`; `values/dimens.xml:126,128`; `values/colors.xml:32`, `values-night/colors.xml:23`).

- Thumbnail: `ImageView`, width matches card, **16:9** via `layout_constraintDimensionRatio`, `scaleType=centerCrop`, placeholder background `home_thumbnail_bg` = solid `surface_variant` (#E3E9E7 / #1A2E27) with **12 dp** radius (`item_home_video.xml:19-31`; `android/app/src/main/res/drawable/home_thumbnail_bg.xml`; `values/colors.xml:5`, `values-night/colors.xml:6`). The card's 16 dp radius clips the top corners; the 12 dp drawable radius is only the loading placeholder shape.
- Duration chip: bottom-**right** of the thumbnail, `layout_margin = spacing_sm` (8 dp), padding 6 dp horizontal / 3 dp vertical (`home_duration_chip_padding_horizontal/vertical`), background `home_duration_chip_bg` = `home_duration_bg` **#CC000000** (80 % black, both themes) with **4 dp** radius, text `TextAppearance.Home.Duration` = **11 sp bold white** (`item_home_video.xml:34-47`; `values/dimens.xml:132-134`; `values/colors.xml:37`, `values-night/colors.xml:28`; `values/styles.xml:61-65`).
- Content block: fixed height `home_card_content_height` = **100 dp** on every bucket, `padding = spacing_sm` (8 dp) (`item_home_video.xml:50-58`). Fixed height is what keeps all cards in a row the same height — replicate with a fixed-height meta area, not intrinsic sizing.
  - Title: `TextAppearance.Home.ItemTitle` = **15 sp**, `sans-serif-medium`, `home_text_primary`, `maxLines=2`, ellipsize end, `gravity=top`, takes the remaining weight (`item_home_video.xml:61-71`; `styles.xml:29-36`).
  - Meta: `TextAppearance.Home.ItemMeta` = **13 sp**, colour `home_text_secondary` (#6B7280 / #9CB3A7), `maxLines=2`, ellipsize end, `marginTop = spacing_xs` (4 dp) (`item_home_video.xml:74-82`; `styles.xml:38-43`).

Video meta string, joined with **" • "** (`HomeFeaturedAdapter.kt:168-181`):
1. `@string/video_views_format` = **"%s views"** (`strings.xml:389`) with the count run through `CountFormat.compact` (locale-aware compact: `1.2K` / `3.4M`; Arabic and Dutch forms) (`HomeFeaturedAdapter.kt:171-176`; `android/app/src/main/java/com/albunyaan/tube/util/CountFormat.kt:31-47`). **A null `viewCount` formats as compact(0), i.e. "0 views" — never omitted** (`HomeFeaturedAdapter.kt:172-174`).
2. Uploaded-ago: `uploadedDaysAgo <= 0` → `@string/video_uploaded_today` = **"Today"** (`strings.xml:390`); else plural `video_uploaded_days_ago` = **"%d day ago" / "%d days ago"** (`strings.xml:391-394`) (`HomeFeaturedAdapter.kt:203-210`).
3. `video.category` appended only when non-blank (`HomeFeaturedAdapter.kt:178-180`).

Duration formatting: `h:mm:ss` when hours > 0, else `m:ss`, both with `Locale.US` digits (`HomeFeaturedAdapter.kt:212-221`). Note the deliberate `Locale.US` — durations use Western digits even in Arabic, unlike counts.

Accessibility label: `@string/a11y_video_item` = **"Video: %1$s, Duration: %2$s, %3$s, %4$s"** with title, duration, views text, uploaded-ago (`strings.xml:382`; `HomeFeaturedAdapter.kt:187-196`). The thumbnail itself is `importantForAccessibility="no"` (`item_home_video.xml:26`).

#### Playlist card — `item_home_playlist.xml`

Same `MediaCard` shell and same fixed 100 dp content block as the video card. Differences:
- Overlay chip is bottom-**left** of the thumbnail, background `home_video_count_chip_bg` = `home_video_count_bg` **#CC275E4B** light / **#CC35C491** dark (80 % brand), same 4 dp radius and 11 sp bold white text (`item_home_playlist.xml:34-47`; `values/colors.xml:38`, `values-night/colors.xml:29`). Dark-mode white-on-mint fails AA — apply the spec's `onBrand` rule (light #FFFFFF / dark #0A1F18, `docs/superpowers/specs/2026-08-23-ios-app-design.md:149`).
- Chip text: plural `video_count` = **"%d video" / "%d videos"** with `playlist.itemCount` (`strings.xml:397-400`; `HomeFeaturedAdapter.kt:139-144`).
- Title = `playlist.title`; second line = `playlist.category` bound into the view id `channelName` (`item_home_playlist.xml:74-83`; `HomeFeaturedAdapter.kt:136-137`). It is the **category**, not a channel name — the view id is misleading.
- Accessibility: `@string/a11y_playlist_item` = **"Playlist: %1$s, %2$d items"** (`strings.xml:384`; `HomeFeaturedAdapter.kt:148-152`).

#### Channel card — `item_home_channel.xml`

**Not a card.** Plain vertical `LinearLayout`, no background fill, no elevation, `gravity=center_horizontal`, `paddingTop/Bottom = spacing_sm` (8 dp), `paddingStart/End = spacing_xs` (4 dp), `selectableItemBackground` (`item_home_channel.xml:3-19`).

- Avatar: `ShapeableImageView`, **circular** (`ShapeAppearance.Albunyaan.Circle`, `cornerSize 50%` — `android/app/src/main/res/values/themes.xml:123-127`), size `home_channel_avatar_size` = **72 / 80 / 88 dp**, `scaleType=centerCrop`, placeholder background = oval `surface_variant` (`item_home_channel.xml:22-31`; `android/app/src/main/res/drawable/home_channel_avatar_bg.xml`).
- Name: `TextAppearance.Home.ChannelName` = **13 sp**, `sans-serif-medium`, `home_text_primary`, `gravity=center`, `maxLines=2`, ellipsize end, `marginTop = spacing_sm` (8 dp) (`item_home_channel.xml:34-44`; `styles.xml:51-59`).
- Subscribers: `TextAppearance.Home.ItemMeta` (13 sp, secondary), centred, `marginTop = spacing_xs` (`item_home_channel.xml:47-55`). Text = `@string/channel_subscribers_format` = **"%s subscribers"** (`strings.xml:321`) with `CountFormat.compact(subscribers)` (`HomeFeaturedAdapter.kt:122-126`).
- Accessibility: `@string/a11y_channel_item` = **"Channel: %1$s, %2$s"** (name, formatted subscriber text) (`strings.xml:383`; `HomeFeaturedAdapter.kt:111-115`).

Note the spec's `HomeChannelItem` (circle 72/80/88, centred name) matches exactly (`docs/superpowers/specs/2026-08-23-ios-app-design.md:161`).

#### Focus state

All three item types set `foreground = @drawable/card_focus_state` — a 2 dp `colorPrimary` stroke at 16 dp radius when focused (`android/app/src/main/res/drawable/card_focus_state.xml`). D-pad/keyboard only; on iOS this is the hardware-keyboard focus ring, handled by `.focusable()`.

### B7. Item tap → navigation out

`HomeFragment.handleItemClick` (`HomeFragment.kt:118-148`):

| Item type | Destination | Arguments |
|---|---|---|
| Video | `action_global_playerFragment` (`HomeFragment.kt:150-164`) | `videoId`, `title`, `channelName` ← **`video.category`** (not `video.channelName`), `thumbnailUrl`, `description`, `durationSeconds`, `viewCount` ← `video.viewCount ?: -1L` |
| Playlist | `action_global_playlistDetailFragment` (`:126-134`) | `playlistId`, `playlistTitle`, `playlistCategory`, `playlistCount` |
| Channel | `action_global_channelDetailFragment` (`:138-145`) | `channelId`, `channelName`, `channelAvatarUrl` |

Two things to carry across:
- The video path passes the metadata **fast path** — the player never refetches for these fields (spec §6, `docs/superpowers/specs/2026-08-23-ios-app-design.md:121`). `viewCount` uses the sentinel **-1** for "unknown" (`HomeFragment.kt:161`); iOS should use `Int64?` instead.
- `channelName` receives `video.category`, which is an Android bug (the DTO's real `channelTitle` is mapped into `ContentItem.Video.channelName` at `android/app/src/main/java/com/albunyaan/tube/data/model/mappers/ApiMappers.kt:48` but never used here). On iOS pass the real channel name and fall back to category.

### B8. Prefetch on tap and on scroll

Two independent prefetch triggers, both hitting `StreamPrefetchService.triggerPrefetch(videoId, scope)`:

1. **On tap** — fired immediately before navigating to the player, so extraction overlaps the transition (`HomeFragment.kt:150-152`).
2. **Predictive, on attach** — gated by `featureFlags.isPredictivePrefetchEnabled` (`HomeFragment.kt:56-65`; flag `predictive_prefetch`, build default `BuildConfig.ENABLE_PREDICTIVE_PREFETCH` overridable via developer settings — `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt:58,186-187`). `PredictivePrefetchController` attaches an `OnChildAttachStateChangeListener` to `homeSectionsRecyclerView` and on **every child view attach** resolves a video id and triggers a prefetch (`android/app/src/main/java/com/albunyaan/tube/player/PredictivePrefetchController.kt:22-38`).
   The resolver is `sectionAdapter.currentList[pos].items.filterIsInstance<Video>().firstOrNull()?.id` — i.e. **the first video of each section that scrolls into view**, one per section, not per card (`HomeFragment.kt:60-62`).
   Detached in `onDestroyView` (`HomeFragment.kt:318-320`; `PredictivePrefetchController.kt:40-43`).

There is **no debounce and no throttle** on either trigger at this layer; deduplication is the prefetch service's job (single-flight through `GlobalStreamResolver`, rate limiting through `ExtractionRateLimiter` — `android/app/src/main/java/com/albunyaan/tube/player/StreamPrefetchService.kt:59-76`). iOS must keep the same shape: fire-and-forget from the view, dedupe/rate-limit in the engine, and cancel nothing on scroll-away.

### B9. ViewModel state shape

`HomeViewModel` (`android/app/src/main/java/com/albunyaan/tube/ui/HomeViewModel.kt`), Hilt-scoped to the fragment (`HomeFragment.kt:35`).

Published state, `StateFlow<HomeState>` initial value `Loading` (`:30-31`):
```
sealed class HomeState
  object  Loading
  data    Success(sections: List<HomeSection>, hasMore: Boolean, isLoadingMore: Boolean = false)
  data    Error(message: String)
  object  Empty
```
(`HomeViewModel.kt:139-148`)

Internal, not published (`:33-42`):
```
sections: MutableList<HomeSection>   // accumulated across pages
nextCursor: String?
hasMore: Boolean = true             // optimistic default
isLoadingMore: Boolean = false
loadJob / loadMoreJob: Job?
currentCategory: String?
val canLoadMore get() = hasMore && !isLoadingMore
```

`HomeSection` domain model (`android/app/src/main/java/com/albunyaan/tube/data/model/HomeSection.kt:7-15`):
```
categoryId: String
categoryName: String
categorySlug: String?
localizedNames: Map<String,String>?
icon: String?          // emoji, rendered as text
items: List<ContentItem>
totalItemCount: Int    // from totalContentCount; NOT displayed anywhere on Home
```

`ContentItem` (`android/app/src/main/java/com/albunyaan/tube/data/model/ContentItem.kt:3-35`) — sealed with `Video(id, title, category, durationSeconds, uploadedDaysAgo, description, thumbnailUrl?, viewCount: Long?, channelName?)`, `Channel(id, name, category, subscribers: Int, description?, thumbnailUrl?, videoCount?, categories?)`, `Playlist(id, title, category, itemCount, description?, thumbnailUrl?)`.

**iOS shape:** one `@Observable` view model with `enum HomeState { loading, loaded([HomeSection], hasMore: Bool, isLoadingMore: Bool), error(String), empty }`. Keep `hasMore`/`isLoadingMore` inside the loaded case exactly as Android does — the UI reads `isLoadingMore` off the success state to show the footer spinner (`HomeFragment.kt:245`).

### B10. Loading lifecycle

**Trigger:** the ViewModel does not load in `init` directly. It collects `filterManager.state.map { it.category }.distinctUntilChanged()` and calls `loadInitialFeed()` on every distinct category value, including the first (`HomeViewModel.kt:44-58`).

> **Edge case — double initial fetch.** `FilterManager._state` starts at `FilterState()` (category = nil) and is only later overwritten by the DataStore emission (`FilterManager.kt:20,23-37`). So on a cold start with a persisted category, `distinctUntilChanged` sees `nil` → load unfiltered, then `"catId"` → load filtered. Two requests, and the first result flashes on screen. On iOS, read the persisted filter **before** the first fetch (e.g. `await` the stored value, or seed from `UserDefaults` synchronously) and issue one request.

`loadInitialFeed()` (`HomeViewModel.kt:60-94`):
1. Cancel `loadJob` **and** `loadMoreJob` (`:61-62`).
2. Reset: clear `sections`, `nextCursor = nil`, `hasMore = true`, `isLoadingMore = false`, emit `Loading` (`:64-68`).
3. `contentService.fetchHomeFeed(cursor = nil, categoryLimit = 5, contentLimit = DeviceConfig.getHomeDataLimit(app), category = currentCategory)` (`:71-78`).
4. Append sections, store `nextCursor` and `hasMore` from the response (`:79-81`).
5. Emit `Empty` if `sections.isEmpty()`, else `Success(sections, hasMore)` (`:83-87`).
6. Any non-cancellation exception → `Error(e.message ?: "Unknown error")` (`:88-92`). `CancellationException` is rethrown (`:89`).

`loadMoreSections()` (`HomeViewModel.kt:96-133`):
1. Guard: return immediately if `!canLoadMore` (`:97`). Then set `isLoadingMore = true` **synchronously, before launching** — this is the in-flight guard against the scroll listener firing on every frame (`:98`).
2. Emit `Success(sections, hasMore, isLoadingMore = true)` so the footer spinner appears (`:107`).
3. Fetch with `cursor = nextCursor`, same `categoryLimit`/`contentLimit`/`category` (`:109-114`).
4. **De-duplicate by `categoryId`**: only append sections whose id is not already present (`:115-117`).
5. `nextCursor = result.nextCursor`; **`hasMore = result.hasMore && result.nextCursor != null`** — stop when the server says no more *or* returns no cursor to advance; keep going when the server has more even if every returned section was a duplicate (`:118-122`, and the comment at `:119-121` explains why).
6. `isLoadingMore = false`, emit `Success(sections, hasMore)` (`:123-125`).
7. On error: log, `isLoadingMore = false`, re-emit `Success(sections, hasMore)` — **pagination failures are silent**: no error state, no toast, no snackbar; the spinner just disappears and the user can scroll again to retry (`:126-131`).

`refresh()` is exactly `loadInitialFeed()` (`HomeViewModel.kt:135-137`).

`DeviceConfig.getHomeDataLimit(context)` → **20** on TV or tablet (`smallestScreenWidthDp >= 600`), **10** on phone (`android/app/src/main/java/com/albunyaan/tube/util/DeviceConfig.kt:10-21`). iOS: 10 on iPhone, 20 on iPad (regular width).

### B11. State rendering — exact visibility matrix

Collected with `repeatOnLifecycle(STARTED)` (`HomeFragment.kt:224-283`). Every branch sets **all six** views, so no stale state leaks:

| State | swipeRefresh.isRefreshing | skeleton | error | empty | sections list | footer spinner |
|---|---|---|---|---|---|---|
| `Loading` (`:229-237`) | false | **visible** | gone | gone | gone | gone |
| `Success` (`:238-259`) | false | gone | gone | gone | **visible** | `state.isLoadingMore` |
| `Error` (`:260-268`) | false | gone | **visible** | gone | gone | gone |
| `Empty` (`:269-278`) | false | gone | gone | **visible** | gone | gone |

Header and category pill are **always** visible — they live outside the swapped region (`fragment_home_new.xml:24-139`).

> **Edge case — pull-to-refresh dismisses its own spinner.** `refresh()` emits `Loading`, whose handler sets `isRefreshing = false` and shows the skeleton, replacing all existing content. So a pull-to-refresh visibly wipes the list and shows the skeleton rather than keeping content under a spinner. On iOS, `.refreshable` holds its own spinner until the async task returns; **do not** clear the sections list during a user-initiated refresh — keep the current content and swap it when the new page arrives. Cold load still shows the skeleton.

**Empty**: `binding.homeEmpty.emptyMessage.setText(R.string.home_empty_content)` is applied at render time, overriding the layout default (`HomeFragment.kt:276`). `home_empty_content` = **"No content available yet"** (`strings.xml:311`). The layout's own default `home_empty_videos` = "No videos available yet" (`strings.xml:247`) is dead on this screen (`home_section_empty.xml:41`).

**Error**: the `Error.message` carried in the state is **never displayed** — the error card always shows the static `@string/list_error_description` = **"Check your connection or adjust your filters, then try again."** (`android/app/src/main/res/values/strings_list_states.xml:4`; `home_section_error.xml:41`). Only the log gets the real message (`HomeFragment.kt:261`).

**Auto-load when the content does not fill the viewport** (`HomeFragment.kt:249-258`): after every `Success`, posted to the next layout pass — if `canLoadMore` and the scroll view's child height ≤ the scroll view height, call `loadMoreSections()`. This is the tablet/large-screen case where 5 sections do not fill the screen and the scroll listener would never fire. Spec §7 requires the same on iOS: "`.onAppear` on the last row **and** `onScrollGeometryChange` 'content fits and hasMore → loadMore()' with an in-flight guard" (`docs/superpowers/specs/2026-08-23-ios-app-design.md:166`).

### B12. Skeleton, inline empty/error cards

**Skeleton** (`home_section_skeleton.xml` + `item_home_skeleton.xml`): a non-interactive `HorizontalScrollView` (`scrollbars=none`, `overScrollMode=never`, `paddingStart/End = home_horizontal_margin`) containing exactly **4** copies of `item_home_skeleton` (`home_section_skeleton.xml:2-22`).

Each skeleton card: `MediaCard` style (16 dp radius, 2 dp elevation, card background) at fixed width `home_video_card_width` (**260 / 280 / 320 dp** — the static dimen, *not* the computed width), `marginEnd = home_card_spacing`, `importantForAccessibility="no"` (`item_home_skeleton.xml:2-9`). Placeholder blocks, all solid `shimmer_background_color` (#E0E0E0 light / #2A2A2A dark — `values/colors.xml:41`, `values-night/colors.xml:57`):
- thumbnail: full width, 16:9 (`:16-24`)
- title bar: full width minus 8 dp side margins, height **20 dp**, `marginTop = spacing_sm` (`:27-37`)
- meta bar: width **100 dp**, height **16 dp**, `marginStart = spacing_sm`, `marginTop = spacing_xs`, `marginBottom = spacing_sm` (`:40-50`)

There is **only one skeleton row** — no section header placeholder, no vertical repetition. No shimmer animation is wired (the colour is named `shimmer_*` but the views are static fills). Spec §7 names the component `SkeletonHomeSection` (`docs/superpowers/specs/2026-08-23-ios-app-design.md:161`); a subtle shimmer on iOS is an acceptable upgrade — static under Reduce Motion.

**Error card** (`home_section_error.xml`) — despite the name, used **full-screen**, not per section (`fragment_home_new.xml:148-151`):
- Outer padding: top `spacing_sm`, bottom `spacing_md`, start/end `home_horizontal_margin` (`:6-10`)
- Card: `home_error_bg` (#FFF3E0 light / #3D2A1A dark), radius `home_thumbnail_corner_radius` = **12 dp**, elevation 0, no stroke, `marginBottom = spacing_xs` (`:12-19`)
- Row: `padding = spacing_md`, `gravity=center_vertical`; `ic_error` **20 dp** tinted `home_error_icon` (#FF6F00 / #FFB74D), `marginEnd = spacing_sm`; message `list_error_description` at **14 sp**, colour `home_error_text` (#E65100 / #FFCC80), `lineSpacingMultiplier = 1.2` (`:21-46`; `values/colors.xml:44-46`, `values-night/colors.xml:35-37`)
- Below the card, centred: `Widget.Material3.Button.OutlinedButton` id `retryButton`, text `@string/retry` = **"Retry"** (`strings.xml:194`), leading icon `ic_refresh`, stroke and text and icon all `primary_green`, `contentDescription = @string/home_retry_section` = **"Retry loading section"** (`strings.xml:313`) (`:50-61`). Tap → `viewModel.refresh()` (`HomeFragment.kt:208-211`).

**Empty card** (`home_section_empty.xml`) — also full-screen (`fragment_home_new.xml:154-157`):
- Same outer padding and 12 dp radius; card background `home_empty_bg` (#F5F5F5 light / = `home_card_background` #1A231F dark), elevation 0 (`:12-18`; `values/colors.xml:49`, `values-night/colors.xml:40`)
- Row: `padding = spacing_md`, `gravity=center_vertical`; `ic_videos` at `badge_icon_size` (**20 / 24 / 28 dp**) tinted `home_empty_icon` (#9E9E9E / `home_text_muted` #74847C), `marginEnd = spacing_sm`; message at **14 sp**, colour `home_empty_text` (#757575 / `home_text_secondary` #9CB3A7), `textAlignment=viewStart`, `lineSpacingMultiplier = 1.2` (`:20-47`; `values/colors.xml:49-51`, `values-night/colors.xml:40-42`)
- **No action button on the empty state** — no "Clear filter" affordance even when the empty result is caused by a category filter. Worth adding on iOS (open question below).

Neither card is ever rendered inside a section; per-section inline empty/error does not exist on Home.

### B13. Pull-to-refresh and pagination scroll

**Pull-to-refresh: yes.** `SwipeRefreshLayout` wraps everything, `colorSchemeResources = primary_green`, `onRefresh → viewModel.refresh()` (`fragment_home_new.xml:2-6`; `HomeFragment.kt:188-193`). iOS: `.refreshable { await vm.refresh() }` on the scroll view, with the caveat in B11.

**Infinite scroll:** `homeScrollView.setOnScrollChangeListener` — compute `diff = childHeight - (scrollViewHeight + scrollY)`; if `diff < 300` (**raw pixels, not dp** — `HomeFragment.kt:180-181`) and `viewModel.canLoadMore`, call `loadMoreSections()` (`HomeFragment.kt:174-186`). No debounce; re-entry is prevented solely by `canLoadMore` flipping `isLoadingMore = true` synchronously (`HomeViewModel.kt:98`).

Because the threshold is in raw pixels, the effective trigger distance varies with screen density (≈100 dp at 3×, ≈300 dp at 1×). On iOS use a device-independent threshold — roughly **150–300 pt** — plus the last-row `.onAppear` and the "content fits" check per spec §7 (`docs/superpowers/specs/2026-08-23-ios-app-design.md:166`).

### B14. Backend call — `GET /api/v1/home`

Retrofit interface (`android/app/src/main/java/com/albunyaan/tube/data/source/api/ContentApi.kt:38-44`):
```
GET api/v1/home
  ?cursor={String?}
  &categoryLimit={Int}      // Home always sends 5  (HomeViewModel.kt:152)
  &contentLimit={Int}       // 10 phone / 20 tablet (DeviceConfig.kt:10-11)
  &category={String?}       // FilterState.category = the category **id**
```

Response, mapped by `RetrofitContentService.fetchHomeFeed` into `HomeFeedResult(sections, nextCursor = pageInfo.nextCursor, hasMore = pageInfo.hasNext)` (`android/app/src/main/java/com/albunyaan/tube/data/source/RetrofitContentService.kt:68-81`).

Wire shape (`android/app/src/main/java/com/albunyaan/tube/data/source/api/HomeFeedResponse.kt:12-42`):
```
{ "data": [ HomeCategorySection ], "pageInfo": { hasNext, nextCursor?, totalCount?, truncated? } }

HomeCategorySection = {
  id, name, slug?, localizedNames?: {lang: String},
  displayOrder?: Int, icon?: String,     // icon is an emoji
  items: [ContentItemDto], totalContentCount: Int (default 0)
}
```
`PageInfo` DTO: `android/app/src/main/java/com/albunyaan/tube/data/model/api/models/PageInfo.kt`. Server wrapper: `backend/src/main/java/com/albunyaan/tube/dto/CursorPageDto.java:23-45`.

`ContentItemDto → ContentItem` mapping and its defaults (`android/app/src/main/java/com/albunyaan/tube/data/model/mappers/ApiMappers.kt:37-68`) — an iOS decoder must apply the same fallbacks:
- discriminator `type ∈ {VIDEO, CHANNEL, PLAYLIST}`
- VIDEO: `title ?: ""`, `category ?: "General"`, `durationSeconds ?: 0`, `uploadedDaysAgo ?: 0`, `description ?: ""`, `channelName = channelTitle`
- CHANNEL: `name ?: title ?: ""`, `category ?: "General"`, `subscribers ?: 0`
- PLAYLIST: `title ?: ""`, `category ?: "General"`, `itemCount ?: 0`

Server behaviour worth knowing (`backend/src/main/java/com/albunyaan/tube/controller/PublicContentController.java:46-77`, `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java:456-652`):
- `categoryLimit` clamped to **1..10**; `contentLimit` clamped to **1..20**; blank `category` normalised to null (`PublicContentController.java:53-56`).
- `Cache-Control: max-age=300, public` (5 min) — the response is identical for all users (`PublicContentController.java:63-65`). iOS `URLCache` will honour this; make sure pull-to-refresh forces a revalidation (`.reloadIgnoringLocalCacheData` or a cache-busting policy), otherwise refresh can be a no-op for 5 minutes.
- Failures return **500** with `{"error":"Failed to load home feed"}` (`PublicContentController.java:69-76`).
- Only **top-level** categories become sections; each section aggregates its own content plus all of its children's (`PublicContentService.java:472-476,655-671`).
- Category filter semantics (`PublicContentService.java:478-510`): a **parent id with children** expands into one section per child; a **leaf parent** stays one section; a **subcategory id** yields exactly that one section; an **unknown id** returns an empty page with a null cursor (so the client lands on `Empty`, not `Error`).
- Sections sorted by `displayOrder` asc, then id asc (`:512-517`).
- Cursor is `Base64("displayOrder:categoryId")` of the last section on the page; opaque to the client (`:519-533,555-567`).
- `hasMore` is derived server-side by fetching `categoryLimit + 1` and trimming; `nextCursor` is non-null **only** when there is a next page (`:555-567`).
- **Empty sections are dropped** (`:606`), so a page can contain fewer than `categoryLimit` sections while still having more; and `totalContentCount` falls back to `items.size()` when the count query returns 0 (`:608-616`).
- If every category fetch failed, the service throws rather than caching a degraded empty response (`:550-553`) → the client sees `Error`, not `Empty`.

### B15. Home edge cases checklist for the iOS implementer

1. Double initial fetch from the FilterManager seed value — fix, see B10.
2. Pull-to-refresh wipes content and shows the skeleton — fix, see B11.
3. Tab reselect scroll-to-top does not work on Home — fix, see A3.
4. `home_cards_visible_playlists` is dead on Android; playlists get the video width — honour the real value, see B6.
5. Card widths computed once — recompute on iPad resize/rotation, see B6.
6. Pagination errors are silent (spinner disappears, no message) — keep the silence or add a small inline retry; Android has neither.
7. `Error.message` is captured but never shown; the UI always shows the generic copy — keep the generic copy.
8. Null `viewCount` renders as **"0 views"**, not omitted (`HomeFeaturedAdapter.kt:172-174`).
9. `uploadedDaysAgo <= 0` → **"Today"**; negative values are treated as today (`HomeFeaturedAdapter.kt:205-206`).
10. Section titles are truncated to **18 characters** in code, before any layout ellipsis (`HomeSectionAdapter.kt:98-102`).
11. Duration uses `Locale.US` digits while counts use locale digits — deliberate (`HomeFeaturedAdapter.kt:212-221`, `CountFormat.kt:31-47`).
12. Setting card widths calls `notifyDataSetChanged()` on the section adapter (`HomeSectionAdapter.kt:25-38`) but only a width payload rebind on the inner adapter (`HomeFeaturedAdapter.kt:27-40,69-86`) — an Android perf detail with no SwiftUI analogue.
13. `Empty` has no clear-filter action even when a filter caused it.
14. Every home root sets `layoutDirection="locale"` (`fragment_home_new.xml:12`, `item_home_section.xml:8`, `item_home_video.xml:9`, `item_home_playlist.xml:9`, `item_home_channel.xml:10`) — the screen is fully RTL for Arabic. All four strings checked (`connectivity_offline_banner`, `home_empty_content`, `see_all`, `home_loading_more`) exist in `values-ar/strings.xml` and `values-nl/strings.xml`.
15. Home's bottom padding equals `bottom_nav_height` (72 dp phone, 0 dp tablet) (`fragment_home_new.xml:21`) — on iOS use safe-area insets instead of a hard-coded value.
16. The vertical sections list has `nestedScrollingEnabled=false` inside a scroll view — one scroll container total. In SwiftUI use a single `ScrollView` with a `LazyVStack` of sections, each containing its own horizontal `ScrollView`; do not nest vertical scrollers.

---

## Token cross-reference used by these two screens

| Android resource | Light | Dark | Spec §7 token |
|---|---|---|---|
| `primary_green` | #275E4B | #35C491 | brand |
| `background_gray` | #F5F5F5 | #121212 | background |
| `home_surface_background` | #F5F6F8 | #0F1512 | homeSurface |
| `home_card_background` | #FFFFFF | #1A231F | homeCard |
| `home_category_pill_bg` | #E8F5F0 | #12352B | categoryPill |
| `home_text_primary` | #1A1A1A | #F1F5F9 | textPrimary |
| `home_text_secondary` | #6B7280 | #9CB3A7 | textSecondary |
| `home_text_muted` | #9CA3AF | #74847C | textMuted |
| `surface_variant` | #E3E9E7 | #1A2E27 | surfaceVariant |
| `home_duration_bg` | #CC000000 | #CC000000 | durationChip |
| `home_video_count_bg` | #CC275E4B | #CC35C491 | videoCountChip |
| `home_error_bg / _text / _icon` | #FFF3E0 / #E65100 / #FF6F00 | #3D2A1A / #FFCC80 / #FFB74D | errorBg / errorText / errorIcon |
| `home_empty_bg / _text / _icon` | #F5F5F5 / #757575 / #9E9E9E | homeCard / textSecondary / textMuted | (not in §7 — reuse surface + textSecondary + textMuted) |
| `shimmer_background_color` | #E0E0E0 | #2A2A2A | skeleton |
| `nav_item_inactive` | #757575 | #B0B0B0 | navInactive |
| `nav_item_focused` | #424242 | #E0E0E0 | (not in §7 — focus ring only) |
| `?attr/colorErrorContainer` | M3 baseline #F9DEDC | M3 baseline #8C1D18 | (not in §7 — offline banner) |

Sources: `android/app/src/main/res/values/colors.xml:3-65`, `android/app/src/main/res/values-night/colors.xml:4-63`.
