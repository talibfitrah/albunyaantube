# Phase 1 research — Favorites, Settings, About (Android → SwiftUI contract)

Scope: `FavoritesFragment` + `FavoritesViewModel` + Room `FavoriteVideo`; `SettingsFragment` (every row, dialogs, switches, sign-out, library/storage rows, About & Support, Available-updates visibility); `LanguageSelectionDialog` / `ThemeSelectionDialog` / `QualitySelectionDialog`; `AboutFragment` + `DeveloperSettingsDialog`; `SettingsPreferences`; `LocaleManager`.

All paths are relative to `android/app/src/main/`. Sizes are Android **dp/sp**; per spec §7 (`docs/superpowers/specs/2026-08-23-ios-app-design.md:163`) spacing selects by width class **compact / regular <1000 pt / regular ≥1000 pt** which approximates `sw600dp` / `sw720dp`.

Spacing tokens used below (`res/values/dimens.xml:5-13`, `res/values-sw600dp/dimens.xml:36-38`, `res/values-sw720dp/dimens.xml:35-37`):

| token | phone | sw600 | sw720 |
|---|---|---|---|
| `spacing_xxs` | 2 | 2 | 2 |
| `spacing_xs` | 4 | 4 | 4 |
| `spacing_sm` | 8 | 8 | 8 |
| `spacing_md` | 16 | 20 | 24 |
| `spacing_lg` | 24 | 32 | 40 |
| `spacing_xl` | 32 | 48 | 64 |
| `spacing_xxl` | 48 | 48 | 48 |

Type tokens (`res/values/dimens.xml:193-197`, `res/values-sw720dp/dimens.xml:99-102`): `text_headline` 20→24sp, `text_section_title` 18sp, `text_subtitle` 16→18sp, `text_body` 14→16sp, `text_caption` 12→14sp, `text_duration` 11sp (`res/values/dimens.xml:141`).

Other tokens: `corner_radius_medium` 16 (`res/values/dimens.xml:18`), `corner_radius_large` 20 (`:19`), `divider_thickness` 1 (`:13`), `elevation_sm` 2 (`:119`), `icon_small` 24 (`:55`), `icon_xlarge` 96 → 128 on sw720 (`:58`, `res/values-sw720dp/dimens.xml:47`), `icon_xxlarge` 128 (`:59`), `library_icon_size` 40 (`:67`), `touch_target_min` 48 → 56 on sw720 (`:22`, `res/values-sw720dp/dimens.xml:41`), `thumbnail_corner_radius` 8 (`:149`), `bottom_nav_height` 72 → **0 on sw600/sw720** (`:26`, `res/values-sw600dp/dimens.xml:6`, `res/values-sw720dp/dimens.xml:6`).

---

## 1. Favorites

### 1.1 Data model — Room `FavoriteVideo`

`data/local/FavoriteVideo.kt:20-38`, table `favorite_videos`, PK `videoId`:

| column | type | default | note |
|---|---|---|---|
| `videoId` | String | — | PK, YouTube video id |
| `title` | String | — | snapshot at favoriting time |
| `channelName` | String | — | snapshot |
| `thumbnailUrl` | String? | — | nullable |
| `durationSeconds` | Int | — | seconds |
| `addedAt` | Long | `System.currentTimeMillis()` | epoch millis, sort key |
| `user_id` | String | `""` | `""` = anon/signed-out sentinel |
| `updated_at` | Long | `0L` | server timestamp, monotonicity guard |
| `deleted` | Boolean | `false` | soft-delete tombstone |
| `dirty` | Boolean | `false` | needs sync push |
| `approval_status` | String | `"APPROVED"` | `APPROVED` \| `AWAITING` |
| `source` | String? | `null` | import provenance |
| `imported_at` | Long? | `null` | import timestamp |

**Per-user scoping is mandatory on every read and write.** All DAO queries take `uid` (`data/local/FavoriteVideoDao.kt:20-171`); `uid` comes from `AccountRepository.currentUid()`, which is `""` when signed out (`data/local/FavoritesRepository.kt:112-115,262-263`). Flow-returning repository methods **re-scope on every account-state transition** via `flatMapLatest` over `accountState` — otherwise the previous user's rows leak after sign-out and anon rows persist after cold-start sign-in (`data/local/FavoritesRepository.kt:124-144`). iOS must replicate: any favorites publisher must be a function of the current uid, re-subscribed when auth state changes.

DAO semantics the iOS store must match (`data/local/FavoriteVideoDao.kt`):

- List query: `WHERE user_id = :uid AND deleted = 0 ORDER BY addedAt DESC` (`:20-24`).
- Favorites **screen** query filters further: `AND approval_status = 'APPROVED'` (`:33-34`). `AWAITING` (imported, unreviewed) rows must **not** appear or be playable on this screen — they live in the Me-tab "Awaiting review" section (`ui/favorites/FavoritesViewModel.kt:43-48`).
- `isFavorite` / `isFavoriteOnce`: `EXISTS(... deleted = 0)` (`:40-44`).
- `getById` filters `deleted = 0`; `getByIdAny` is deleted-agnostic (`:81-86`).
- Remove = **soft delete** (`deleted = 1, dirty = 1`), never a row delete (`:73-74`, `data/local/FavoritesRepository.kt:224-228`).
- Re-add of a soft-deleted row = `clearSoftDelete` **then** `upsert`, in **one transaction** (`resurrectAndUpsert`, `:130-134`). A plain insert with IGNORE-on-conflict silently no-ops against the tombstone; a non-atomic pair can push stale metadata to the server after a process kill (`:121-129`, `data/local/FavoritesRepository.kt:173-188`).
- `toggleFavorite` is transactional: live row → soft-delete, return `false`; absent or tombstoned → resurrect+upsert, return `true` (`:107-119`).
- Sync writes carry a monotonicity guard `AND updated_at < :ts` on `clearDirty` and `applyTombstone` (`:160-168`).
- `clearAll(uid)` is a **hard** `DELETE FROM favorite_videos WHERE user_id = :uid` (`:139-140`).

Every mutating repository method fires `syncManager.pushDirtyAsync(uid)` **except** `clearAll`, which does not (`data/local/FavoritesRepository.kt:189,221,227,248` vs `:258-260`). **Edge case for iOS:** clear-all hard-deletes locally without tombstones and without a push, so a subsequent server pull can resurrect every cleared favorite. Decide explicitly on iOS (recommended: soft-delete all rows + push, i.e. tombstone semantics) and record the deviation.

`addImportedFavorite` pins `uid` from the caller (captured at import start) so a mid-import account switch cannot write/push under another account (`data/local/FavoritesRepository.kt:195-222`).

### 1.2 ViewModel state shape

`ui/favorites/FavoritesViewModel.kt`:

- `favorites: StateFlow<List<FavoriteVideo>>` = `repository.observeApprovedFavorites()`, `stateIn(WhileSubscribed(5000), initialValue = emptyList())` (`:48-53`). **There is no loading state and no error state on the list** — the screen renders `[]` until the first DB emission. iOS equivalent: an `@Published var favorites: [FavoriteVideo] = []` fed by the store; a 5 s subscription grace period is an Android-lifecycle artefact with no iOS analogue.
- `uiEvents: SharedFlow<UiEvent>` with `extraBufferCapacity = 1`, `onBufferOverflow = DROP_OLDEST` (`:37-41`). Cases: `Error(message)`, `FavoriteRemoved`, `AllFavoritesCleared` (`:31-35`).
- `removeFavorite(videoId)` and `clearAllFavorites()` are fire-and-forget coroutines; failures are logged and emitted as `Error("Failed to remove favorite")` / `Error("Failed to clear favorites")` — **hardcoded English, not string resources** (`:58-83`).
- **`FavoritesFragment` never collects `uiEvents`** (no `uiEvents` reference in `ui/favorites/FavoritesFragment.kt`). So on Android these events are silently dropped: no toast, no snackbar, no error surface. iOS should either surface the failure or deliberately keep it silent — flag as a decision, don't accidentally re-implement a dead channel.

### 1.3 Screen — `FavoritesFragment` / `fragment_favorites.xml`

Structure (`res/layout/fragment_favorites.xml:2-75`): vertical stack of `MaterialToolbar` + `FrameLayout{ RecyclerView, empty-state }`, root background `?attr/colorSurface`.

Toolbar (`:10-20`):
- height `?attr/actionBarSize`, background `colorSurface`, elevation `elevation_sm` = 2, title style Headline6, title colour `colorOnSurface`.
- Title = `favorites_title` → **"Favorites"** (`res/values/strings.xml:483`; ar "المفضلة" `res/values-ar/strings.xml:571`, nl "Favorieten" `res/values-nl/strings.xml:508`).
- Navigation icon = up arrow → `findNavController().navigateUp()` (`ui/favorites/FavoritesFragment.kt:46-48`).
- Overflow menu `res/menu/favorites_menu.xml:5-9`: single item `action_clear_all`, icon `ic_delete`, title `favorites_clear_all` → **"Clear all"** (`res/values/strings.xml:487`), `showAsAction="ifRoom"` (so it renders as a toolbar icon, not an overflow item, when there is room).

List (`:28-33`): `LinearLayoutManager` vertical, a **`DividerItemDecoration` (VERTICAL) between rows** (`ui/favorites/FavoritesFragment.kt:61-69`), `clipToPadding=false`, `paddingBottom = bottom_nav_height` (72 on phone, 0 on tablet).

Row `res/layout/item_favorite_video.xml` (ConstraintLayout, `padding = spacing_md`, ripple background):
- Thumbnail `favorite_thumbnail_width` × `favorite_thumbnail_height` = **120×68** phone / **160×90** sw600 / **200×112** sw720 (`res/values/dimens.xml:147-148`, `res/values-sw600dp/dimens.xml:102-103`, `res/values-sw720dp/dimens.xml:112-113`), `centerCrop`, corner radius `thumbnail_corner_radius` = 8 applied by the image loader (`ui/favorites/FavoritesAdapter.kt:62-68`), placeholder + error drawable `thumbnail_placeholder`; null/blank URL → placeholder without a load (`:69-71`).
- Duration chip: bottom-end of the thumbnail, margin `spacing_xs` = 4, padding 8h/2v (`badge_padding_horizontal/vertical`, `res/values/dimens.xml:137-138`), white text at `text_duration` = 11sp, `duration_background` drawable.
- Title: `text_body` 14sp, `colorOnSurface`, **maxLines 2**, ellipsize end, `marginStart = spacing_md`, `marginEnd = spacing_sm`.
- Channel name: `text_caption` 12sp, secondary colour, **maxLines 1**, `marginTop = spacing_xs`.
- Remove button: `touch_target_min` 48×48 (56 on sw720), `ic_close`, borderless ripple, end-aligned, vertically centred.
- Duration format (`ui/favorites/FavoritesAdapter.kt:77-87`): clamp negatives to 0; `h > 0` → `"%d:%02d:%02d"`, else `"%d:%02d"`. Note the minutes field is **not** zero-padded when there are no hours (`5:07`, not `05:07`).
- Diffing by `videoId`, contents by full equality (`:90-98`).
- Accessibility: thumbnail `contentDescription = favorite.title`; remove button `contentDescription = favorites_remove_description` → **"Remove %1$s from favorites"** (`res/values/strings.xml:491`, `ui/favorites/FavoritesAdapter.kt:55-59`). Static row label `favorites_remove` → "Remove from favorites" (`res/values/strings.xml:486`).

Empty state (`res/layout/fragment_favorites.xml:36-71`): centred vertical stack, `padding = spacing_xl` (32/48; `spacing_xxl` 48 on sw720):
- Icon `ic_favorite_border`, `icon_xlarge` 96 (128 on sw720 via `icon_xxlarge`), **alpha 0.3**, tint secondary text colour, not exposed to accessibility.
- Title, `marginTop = spacing_md` (`spacing_lg` on sw720), centred, `text_subtitle` 16sp (`text_headline` 20/24sp on sw720), secondary colour: `favorites_empty_title` → **"No favorites yet"** (`res/values/strings.xml:484`).
- Subtitle, `marginTop = spacing_xs` (`spacing_sm` on sw720), centred, `text_body` 14sp (`text_subtitle` on sw720): `favorites_empty_subtitle` → **"Tap the heart icon on any video to add it to your favorites"** (`res/values/strings.xml:485`).

Empty/non-empty switching (`ui/favorites/FavoritesFragment.kt:71-87`): on every emission set empty view VISIBLE/GONE, list GONE/VISIBLE, and **hide the "Clear all" toolbar action when the list is empty**. Collection is `repeatOnLifecycle(STARTED)` + `collectLatest`. There is **no loading spinner and no error view on this screen at all**.

Tablet variants: content is wrapped in a `MaterialCardView` (`colorSurface`, radius 16 on sw600 / 20 on sw720, elevation 0) inside a container padded `spacing_lg`/`spacing_lg`/`spacing_md` (sw600) or `spacing_xl`/`spacing_xl`/`spacing_lg` (sw720); the RecyclerView gets internal padding `spacing_md` (sw600) / `spacing_lg` (sw720) and the card a bottom margin of `bottom_nav_height` (`res/layout-sw600dp/fragment_favorites.xml:23-95`, `res/layout-sw720dp/fragment_favorites.xml:23-96`). iOS: on regular width, inset the list into a rounded surface card rather than edge-to-edge.

### 1.4 Behaviours

**Row tap → player.** Builds a bundle with exactly five keys and navigates to the global player action (`ui/favorites/FavoritesFragment.kt:89-99`): `videoId`, `title`, `channelName`, `thumbnailUrl`, `durationSeconds`. The player reads twelve possible keys (`ui/player/PlayerFragment.kt:403-417`); the ones favorites omits fall back to: `playlistId` nil, `startIndex` 0, `shuffled` false, `targetVideoId` nil, `description` nil, `viewCount` nil (`-1` sentinel → nil), `channelId` nil. Consequence to preserve on iOS: **opening a favorite plays a single video with the metadata fast path, no backend fetch, and no channel link in the player header** (`channelId` is nil).

**Remove tap** → `viewModel.removeFavorite(videoId)` → soft delete + sync push (`ui/favorites/FavoritesFragment.kt:101-103`). No confirmation, no undo, no snackbar. The row disappears on the next DB emission.

**Clear-all** (`ui/favorites/FavoritesFragment.kt:105-114`) — Material alert dialog:
- title `favorites_clear_all_title` → **"Clear all favorites?"** (`res/values/strings.xml:488`)
- message `favorites_clear_all_message` → **"This will remove all videos from your favorites. This action cannot be undone."** (`:489`)
- positive `favorites_clear_all_confirm` → **"Clear all"** (`:490`) → `clearAllFavorites()`
- negative `android.R.string.cancel` (system "Cancel"), no-op.
iOS: `.alert` with a **destructive** confirm button; the Android button has no destructive styling, but the copy is destructive — use `.destructive` on iOS.

**Navigation in.** Three entries, all pushing the same destination `favoritesFragment` (`res/navigation/main_tabs_nav.xml:103-106`):
1. Settings → Library → "Favorites" row, with `launchSingleTop` **and `popUpTo=settingsFragment` (non-inclusive)** (`ui/settings/SettingsFragment.kt:340-344`, `res/navigation/main_tabs_nav.xml:208-212`).
2. Downloads screen → `action_downloadsFragment_to_favoritesFragment` (`ui/download/DownloadsFragment.kt:124`, `res/navigation/main_tabs_nav.xml:98-100`).
3. Me tab → favorites "See all" tile → navigate by destination id, guarded by `currentDestination == meFragment` (`ui/me/MeFragment.kt:684-687`).

Every navigation call is guarded by a `currentDestination` check — the double-tap/re-entrancy guard. On iOS the `NavigationStack` path push needs the same idempotence (ignore a push when the target is already on top).

**Navigation out.** Toolbar up → `navigateUp()`; system back → default pop. Favorites declares no child destinations.

---

## 2. Settings

`ui/settings/SettingsFragment.kt`, layout `res/layout/fragment_settings.xml` (+ `layout-sw600dp`, `layout-sw720dp`).

Screen chrome: root background `@color/background_gray`; toolbar identical in style to Favorites, title `settings_title` → **"Settings"** (`res/values/strings.xml:495`), up → `navigateUp()` (`ui/settings/SettingsFragment.kt:244-248`); `ScrollView` with `paddingBottom = bottom_nav_height` (72; sw720 uses `spacing_lg` instead), content `padding = spacing_md` (sw600: `spacing_lg` h / `spacing_md` v; sw720: `spacing_xl` h / `spacing_md` v) (`res/layout/fragment_settings.xml:7-32`, `res/layout-sw600dp/fragment_settings.xml:33-47`, `res/layout-sw720dp/fragment_settings.xml:33-47`).

Section pattern, repeated for every group: a bold section header `TextView` at `text_section_title` 18sp, `colorOnSurface`, `marginBottom = spacing_sm`; then a `MaterialCardView` (`colorSurface`, `cardCornerRadius = corner_radius_medium` 16, `cardElevation = 0`) with `marginBottom = spacing_lg`, containing rows separated by 1 dp `?attr/colorOutlineVariant` dividers **inset by `marginStart = spacing_lg`** (`res/layout/fragment_settings.xml:68-104`). iOS: `.insetGrouped` list styling with a custom 16 pt corner radius and flat (elevation 0) cards.

Standard row geometry (`res/layout/settings_item_language.xml:2-64` and siblings): `padding = spacing_md` all round; leading icon `library_icon_size` 40×40 with internal `padding = spacing_sm` 8 and a rounded `onboarding_icon_bg` background, tinted `colorPrimary`; title `marginStart = spacing_md`, `text_subtitle` 16sp, `colorOnSurface`; optional value line below the title, `marginTop = spacing_xxs` 2, `text_body` 14sp, **tinted `colorPrimary`** (brand green — the current value is the accent in the row); trailing chevron `ic_chevron_right`, `icon_small` 24, tinted `colorOnSurfaceVariant`. Switch rows are LinearLayouts with the same 40 dp icon, a title/description column (`text_subtitle` / `text_body` on `colorOnSurfaceVariant`), and a trailing `SwitchMaterial` centred vertically (`res/layout/settings_item_audio_only.xml:2-44`).

### 2.1 Rows, in order (phone layout)

| # | Section (header string → English) | Row | Strings (key → English) | Control | Action |
|---|---|---|---|---|---|
| 0 | `settings_account_header` → **"Account"** (`strings.xml:710`) — **hidden unless signed in** | Sign out (`settings_item_signout.xml`, icon `ic_logout`) | title `settings_account_sign_out` → **"Sign out"** (`:713`); subtitle `settings_account_signed_in_as` → **"Signed in as %1$s"** (`:711`), or `settings_account_signed_in_default` → **"Signed in"** (`:712`) when email is null/blank; subtitle maxLines 1, ellipsize end | chevron | confirm dialog → sign out |
| 1 | `settings_general` → **"General"** (`:496`) | Language | `settings_language` → **"Language"** (`:520`); value = resolved display name | chevron + value | `LanguageSelectionDialog` |
| 2 | | Theme | `settings_theme` → **"Theme"** (`:527`); value = resolved theme name | chevron + value | `ThemeSelectionDialog` |
| 3 | `settings_library_header` → **"Library"** (`:186`) | Downloads library (icon `ic_download`) | `settings_downloads_library` → **"Downloads library"** (`:187`) | chevron | push Downloads |
| 4 | | Favorites (icon `ic_favorite`) | `settings_favorites_title` → **"Favorites"** (`:188`) | chevron | push Favorites |
| 5 | `settings_playback` → **"Playback"** (`:497`) | Audio Only (icon `ic_lock_silent_mode`) | `settings_audio_only` → **"Audio Only"** (`:498`); desc `settings_audio_only_desc` → **"Play audio without video to save data"** (`:499`) | switch | persist only |
| 6 | | Background Play (icon `ic_play`) | `settings_background_play` → **"Background Play"** (`:500`); desc `settings_background_play_desc` → **"Continue playback when app is minimized"** (`:501`) | switch | persist only |
| 7 | `settings_downloads` → **"Downloads"** (`:502`) | Download Quality (icon `stat_sys_download`) | `settings_download_quality` → **"Download Quality"** (`:505`); value = quality display name | chevron + value | `QualitySelectionDialog` |
| 8 | | WiFi Only (icon `ic_wifi`) | `settings_wifi_only` → **"WiFi Only"** (`:503`); desc `settings_wifi_only_desc` → **"Only download over WiFi connections"** (`:504`) | switch | persist only |
| 9 | | Storage Location (icon `ic_menu_save`, **24 dp**, trailing `ic_menu_more`) | `settings_storage_location` → **"Storage Location"** (`:538`); value `settings_storage_internal` → **"Internal Storage"** (`:548`), static | tappable | storage-location dialog |
| 10 | | Storage Used (icon `ic_menu_info_details`, 24 dp) — **not tappable** | `downloads_storage_used` → **"Storage Used"** (`:464`); value starts as `downloads_storage_calculating` → **"Calculating…"** (`:466`), then `settings_storage_format` → **"Downloads: %1$s • Available: %2$s of %3$s"** (`:540`); horizontal `ProgressBar` below, `marginTop = spacing_xs`, tint `colorPrimary` | progress | read-only display |
| 11 | | Clear All Downloads (icon `ic_menu_delete`, 24 dp) | `settings_clear_downloads` → **"Clear All Downloads"** (`:541`); desc `settings_clear_downloads_desc` → **"Delete all downloaded content"** (`:542`) | tappable | confirm dialog |
| 12 | `settings_content` → **"Content"** (`:516`) | Safe Mode (icon `ic_shield`) | `settings_safe_mode` → **"Safe Mode"** (`:517`); desc `settings_safe_mode_desc` → **"Show only family-friendly content"** (`:518`) | switch | persist only |
| 13 | `settings_about_support` → **"About & Support"** (`:553`) | Support Center (icon `ic_menu_help`) | `settings_support_center` → **"Support Center"** (`:563`) | chevron | push About |
| 14 | | Available updates (icon `ic_refresh`) — **conditionally hidden** | `settings_available_updates` → **"Available updates"** (`:805`) | chevron | push Available Versions |
| 15 | | Check for updates (icon `ic_refresh`) | `settings_check_for_updates` → **"Check for updates"** (`:215`) | chevron | run update check |

Row-to-layout map: 0 `settings_item_signout.xml`, 1 `settings_item_language.xml`, 2 `settings_item_theme.xml`, 3 `settings_item_downloads_library.xml`, 4 `settings_item_favorites.xml`, 5 `settings_item_audio_only.xml`, 6 `settings_item_background_play.xml`, 7 `settings_item_download_quality.xml`, 8 `settings_item_wifi_only.xml`, 9 `settings_item_storage_location.xml`, 10 `settings_item_storage_quota.xml`, 11 `settings_item_clear_downloads.xml`, 12 `settings_item_safe_mode.xml`, 13 `settings_item_support.xml`, 14 `settings_item_available_versions.xml`, 15 `settings_item_update_check.xml` — all under `res/layout/`, included in that order at `res/layout/fragment_settings.xml:62,92,101,130-142,172,181,211,220,229,238,247,272,304,314,323`.

Icon note: Language and Theme currently use the **system placeholder** `@android:drawable/ic_dialog_info` (`res/layout/settings_item_language.xml:18`, `res/layout/settings_item_theme.xml:17`); Audio Only uses `@android:drawable/ic_lock_silent_mode` (`res/layout/settings_item_audio_only.xml:14`); storage/clear rows use `ic_menu_save` / `ic_menu_info_details` / `ic_menu_delete` / `ic_menu_more`. These are Android framework stock icons, not app assets — on iOS pick SF Symbols by meaning (`globe`, `circle.lefthalf.filled`, `arrow.down.circle`, `heart`, `speaker.wave.2`, `play.circle`, `wifi`, `externaldrive`, `chart.pie`, `trash`, `shield`, `questionmark.circle`, `arrow.triangle.2.circlepath`).

**Tablet gap (real defect, do not port):** `res/layout-sw600dp/fragment_settings.xml` and `res/layout-sw720dp/fragment_settings.xml` **omit the Account header, Account card, and the sign-out row entirely** (their include lists start at `settings_item_language`, `:64` / `:74`). `setupAccountSection` therefore finds nulls and no-ops on tablets — **a tablet user cannot sign out from Settings**. iOS must show the Account/Sign-out section in every size class.

### 2.2 Account section behaviour

`ui/settings/SettingsFragment.kt:68-93`: `authRepository.authState` collected under `repeatOnLifecycle(STARTED)`. `signedIn = state is AuthState.SignedIn` drives header + card visibility (GONE when signed out) and the subtitle text. Subtitle: email present → `settings_account_signed_in_as`; signed in without email → `settings_account_signed_in_default`; signed out → `null`.

Sign-out confirmation (`ui/settings/SettingsFragment.kt:95-142`):
- title `settings_account_sign_out_confirm_title` → **"Sign out?"** (`res/values/strings.xml:714`)
- message `settings_account_sign_out_confirm_body` → **"You'll need to sign in again to access admin features and personalised content."** (`:715`)
- positive `settings_account_sign_out_confirm_action` → **"Sign out"** (`:716`)
- negative `settings_account_sign_out_cancel` → **"Cancel"** (`:717`)
- On confirm: `authRepository.signOut()`, then navigate to the sign-in destination on the **activity-level** nav controller with `popUpTo(app_nav_graph) { inclusive = true }` — i.e. the whole back stack is cleared. Guarded against a detached/destroyed activity between the tap and the coroutine continuation (`:115-130`).
- iOS translation: sign out, then **reset the root** to the guest shell (spec D11: guest mode, never a forced sign-in — `docs/superpowers/specs/2026-08-23-ios-app-design.md:33`). Android routes to a sign-in screen; iOS should return to the guest Main shell, not a modal sign-in wall.

### 2.3 Value loading and refresh

`loadPreferences()` runs **once** in `onViewCreated` and reads a single snapshot (`.first()`) of each preference (`ui/settings/SettingsFragment.kt:250-287`): four switch states, then `localeSelection` → `LocaleManager.getLanguageDisplayNameWithResolved`, `theme` → `getThemeDisplayName`, `downloadQuality` → `getQualityDisplayName`. Values are **not** observed; after a dialog selection the fragment patches the specific `TextView` by hand (`:187,219`). There is **no** loading/error state anywhere in Settings. iOS may legitimately use observed state instead (simpler and strictly better), but must keep the same displayed strings.

Display-name mapping:
- Theme (`ui/settings/SettingsFragment.kt:295-309`): `"system"` → `settings_theme_system_resolved` = **"System default (%1$s)"** (`res/values/strings.xml:531`) where `%1$s` is `settings_theme_dark` = **"Dark"** (`:533`) or `settings_theme_light` = **"Light"** (`:532`), resolved from the **current device night-mode config**; `"light"` → "Light"; `"dark"` → "Dark"; anything else → `settings_theme_system` = **"System default"** (`:530`).
- Quality (`ui/settings/SettingsFragment.kt:316-322`): `"low"` → `settings_quality_low` = **"Low (360p)"** (`:508`); `"high"` → `settings_quality_high` = **"High (1080p)"** (`:513`); **anything else (including unknown values)** → `settings_quality_medium` = **"Medium (720p)"** (`:510`).
- Language: `LocaleManager.getLanguageDisplayNameWithResolved` — see §4.

### 2.4 Switches

Four switches, all persist-only, all `setOnCheckedChangeListener` → `preferences.setX(isChecked)` in a coroutine, no toast, no confirmation, no side-effect (`ui/settings/SettingsFragment.kt:396-418`). Per spec D12, these five settings (the four switches plus Download quality) are **stored and read by nothing on Android** and must be **implemented for real on iOS** (`docs/superpowers/specs/2026-08-23-ios-app-design.md:35`, `preferences/SettingsPreferences.kt:75-83`).

Note the listener is attached in `setupListeners()` while the initial value is written in `loadPreferences()` — both run in `onViewCreated`, and `loadPreferences` is inside a coroutine, so the programmatic `isChecked =` assignment can fire the listener and write back the value it just read. Harmless on Android (idempotent write); on iOS just bind the toggle to the store and skip the round trip.

### 2.5 Dialogs launched from Settings

All three selection dialogs share a shape: **Material single-choice (radio) list**, a title, a "Cancel" negative button, **selecting an item dismisses immediately and delivers the result** (no OK button). Results travel through the Fragment Result API so they survive process death; the parent registers listeners in `setupFragmentResultListeners()` (`ui/settings/SettingsFragment.kt:148-229`). A cancelled dialog delivers nothing and the listener returns early (`:155-156,178-180,205-207`). iOS equivalent: a pushed selection list or a `.confirmationDialog`/sheet with checkmark rows, tap-to-select-and-dismiss, plus Cancel.

**LanguageSelectionDialog** (`ui/settings/LanguageSelectionDialog.kt:32-62`)
- Title `settings_language_select_title` → **"Select Language"** (`res/values/strings.xml:522`).
- Options, in this order: `LocaleManager.LANGUAGE_SELECTION_KEYS` = `["system", "en", "ar", "nl"]` (`locale/LocaleManager.kt:52-54`, `preferences/SettingsPreferences.kt:95,101`).
- Labels: for `"system"` the **resolved** form `settings_language_system_resolved` = **"System default (%1$s)"** (`:524`) with the native name of the resolved locale; for the rest the **native** names `English` / `العربية` / `Nederlands` (`locale/LocaleManager.kt:39-43`).
- Selected index = index of the current selection, `0` if not found (`:47`).
- Negative `cancel` → **"Cancel"** (`res/values/strings.xml:568`).
- Result key `"language_selection_request"`, payload key `"selected_language"` (`:66-67`).
- On result (`ui/settings/SettingsFragment.kt:148-170`): `LocaleManager.saveAndApplyLocale(ctx, selection)` (persist **then** apply, atomically), then a **`Toast` (LENGTH_LONG)** of `settings_language_changed` → **"Language changed. App will restart."** (`res/values/strings.xml:525`). A Toast, not a Snackbar, precisely because the locale change recreates the Activity and would kill a Snackbar (`:162-164`). On exception: Toast LENGTH_LONG of `settings_language_change_failed` → **"Failed to change language. Please try again."** (`:526`).
- **Per spec §6 the Language dialog is not needed on iOS** — iOS uses a deep link to the system Settings app for per-app language (`docs/superpowers/specs/2026-08-23-ios-app-design.md:119`). If that decision is revisited, the contract above is the fallback. Either way there is no "app will restart" on iOS.

**ThemeSelectionDialog** (`ui/settings/ThemeSelectionDialog.kt:31-60`)
- Title `settings_theme_select_title` → **"Select Theme"** (`res/values/strings.xml:534`).
- Options in order `["system", "light", "dark"]` labelled `settings_theme_system` → **"System default"** (`:530`), `settings_theme_light` → **"Light"** (`:532`), `settings_theme_dark` → **"Dark"** (`:533`). Note the **dialog** uses the plain "System default", while the **row value** uses the resolved "System default (Light)" form.
- Selected index = index of current theme, `0` if not found.
- Result key `"theme_selection_request"`, payload `"selected_theme"`.
- On result (`ui/settings/SettingsFragment.kt:200-228`), in this exact order: persist → apply (`AppCompatDelegate.setDefaultNightMode`: system → FOLLOW_SYSTEM, dark → YES, **everything else → NO**, `:235-242`) → update the row value (after applying, so the resolved name is correct) → **Toast LENGTH_SHORT** `settings_theme_changed` → **"Theme changed"** (`res/values/strings.xml:535`). Failure → Toast LENGTH_SHORT `settings_theme_change_failed` → **"Failed to change theme. Please try again."** (`:536`).
- iOS: `.preferredColorScheme(nil/.light/.dark)` applied at the root; no restart, no toast needed for a change the user can see instantly — but keep the persisted key/values identical (`system`/`light`/`dark`).

**QualitySelectionDialog** (`ui/settings/QualitySelectionDialog.kt:29-53`)
- Title `settings_download_quality_title` → **"Download Quality"** (`res/values/strings.xml:507`).
- Options in order `["low", "medium", "high"]` labelled with the **`_desc` variants**: `settings_quality_low_desc` → **"Low (360p) - Save data"** (`:509`), `settings_quality_medium_desc` → **"Medium (720p) - Balanced"** (`:511`), `settings_quality_high_desc` → **"High (1080p) - Best quality"** (`:513`). The row value uses the **short** variants (`Low (360p)` etc.).
- Selected index = index of current quality, **`1` (medium)** if not found (`:38`).
- Result key `"quality_selection_request"`, payload `"selected_quality"`.
- On result (`ui/settings/SettingsFragment.kt:172-197`): persist, update the row value, then **`Snackbar` LENGTH_SHORT** (not a Toast — no recreation here) of `settings_quality_changed` → **"Download quality set to %1$s"** (`res/values/strings.xml:514`) with the **short** label. Failure → Snackbar LENGTH_SHORT `settings_quality_change_failed` → **"Failed to change download quality. Please try again."** (`:515`).

**Storage-location dialog** (`ui/settings/SettingsFragment.kt:509-536`)
- Title `settings_download_location` → **"Download Location"** (`res/values/strings.xml:547`).
- Single-choice, two items: `settings_storage_internal` → **"Internal Storage"** (`:548`), `settings_storage_external` → **"External SD Card (if available)"** (`:549`). Selected index is **hardcoded to 0** and never persisted.
- Item 0 → Snackbar `settings_storage_internal_selected` → **"Using internal storage"** (`:550`); item 1 → Snackbar `settings_storage_external_not_implemented` → **"External storage not yet implemented"** (`:551`). Either way the dialog dismisses and nothing changes.
- Negative `cancel` → "Cancel".
- **iOS has no user-selectable download location.** Drop this row (recommended) or replace it with a read-only "On My iPhone/iPad" line. Flagged as an open question below.

**Clear-downloads confirmation** (`ui/settings/SettingsFragment.kt:538-548`)
- title `settings_clear_downloads_title` → **"Clear All Downloads?"** (`res/values/strings.xml:543`)
- message `settings_clear_downloads_message` → **"This will delete all downloaded videos and audio files. This action cannot be undone."** (`:544`)
- positive `settings_clear_downloads_confirm` → **"Clear All"** (`:545`), negative `cancel`.
- On confirm (`:550-578`): recursively delete the contents of `filesDir/downloads`, counting **files only** (directories are removed but not counted), then Snackbar LENGTH_SHORT `settings_files_cleared` → **"Cleared %1$d files"** (`:546`), then refresh the storage display. Note this deletes the directory tree directly and does **not** go through `DownloadStorage`, so its in-memory `currentSize` counter is stale until recomputed — on iOS route the clear through the download manager so its accounting stays correct.

### 2.6 Storage display

`updateStorageDisplay()` (`ui/settings/SettingsFragment.kt:469-494`) reads `downloadStorage.getCurrentDownloadSize()` (in-memory counter), `getAvailableDeviceStorage()` = `rootDir.usableSpace`, `getTotalDeviceStorage()` = `rootDir.totalSpace` (`download/DownloadStorage.kt:336-346`), formats all three with `Formatter.formatShortFileSize` (locale-aware short byte sizes → iOS `ByteCountFormatter`/`.formatted(.byteCount(.file))`), and sets:
- value = `settings_storage_format` = **"Downloads: %1$s • Available: %2$s of %3$s"**
- progress = `((total - available) / total) * 100`, integer, clamped 0…100 — i.e. **whole-device usage, not download usage** (`:486-491`).
Called once on setup and again after clearing downloads. `totalBytes == 0` would divide by zero → NaN → `toInt()` = 0, which the clamp absorbs; on iOS guard explicitly.

### 2.7 Available updates + Check for updates

`installSource.isPlayStore()` compares the installer package to `"com.android.vending"` (`update/InstallSource.kt:21-31`). When true, **both the "Available updates" row and its divider are set GONE** and no click listener is attached; otherwise the row pushes `availableVersionsFragment` guarded by `currentDestination == settingsFragment` (`ui/settings/SettingsFragment.kt:381-393`).

**iOS mapping:** there is no sideload-vs-store distinction and no in-app update (spec D3: "In-app update is not [in scope]; App Store handles updates; `minAppVersion` in remote config shows an 'update required' screen" — `docs/superpowers/specs/2026-08-23-ios-app-design.md:23`). So on iOS **omit both rows 14 and 15** (Available updates, Check for updates) — the equivalent of `isPlayStore() == true`, which is exactly what a store-distributed build is. If a "Check for updates" affordance is still wanted, its Android contract is: `updatePromptFlow.runCheck(activity, lifecycleOwner)` → update found → update dialog; failure → Toast `update_check_failed` → **"Couldn't check for updates. Please try again."** (`res/values/strings.xml:225`); no update → Toast `update_check_up_to_date` → **"FitrahTube is up to date."** (`:224`) (`update/UpdatePromptFlow.kt:113-123`).

### 2.8 Navigation in/out of Settings

- In: Home toolbar overflow → "Settings" (`ui/HomeFragment.kt:308-311`, menu `res/menu/home_menu.xml`). Only entry point.
- Out: toolbar up → `navigateUp()`; rows push Downloads / Favorites / About / Available Versions; sign-out resets the whole stack.
- Downloads and Favorites pushes use `launchSingleTop` + `popUpTo(settingsFragment)` non-inclusive, so Settings stays underneath and back returns to it (`res/navigation/main_tabs_nav.xml:203-212`).

---

## 3. About + Developer Settings

### 3.1 `AboutFragment` / `fragment_about.xml`

Root background `?attr/colorSurfaceVariant`; toolbar as elsewhere, title `about_title` → **"About"** (`res/values/strings.xml:554`), up → `navigateUp()` (`ui/settings/AboutFragment.kt:76-80`). Content `ScrollView`, `padding = spacing_md`.

**App info card** (`res/layout/fragment_about.xml:33-85`): `MaterialCardView` (surface, radius 16, elevation 0), inner `padding = spacing_lg`, centred:
- App icon placeholder — a bare `View` of `channel_avatar_size` 80×80 filled with `@color/primary_variant` (**#35C491**), `marginBottom = spacing_md`. iOS: use the real app icon here.
- App name `app_name` → **"FitrahTube"** (`res/values/strings.xml:3`), `text_headline` 20sp bold, `marginBottom = spacing_xs`.
- Version text (id `versionText`), `text_body` 14sp, `colorOnSurfaceVariant`, `marginBottom = spacing_sm`. Format `about_version_format` → **"Version %1$s (%2$d)"** (`res/values/strings.xml:555`) filled with `BuildConfig.VERSION_NAME` and `BuildConfig.VERSION_CODE` (`ui/settings/AboutFragment.kt:83-84`). iOS: `CFBundleShortVersionString` and `CFBundleVersion` → e.g. "Version 1.0.0 (1)".
- Tagline `splash_tagline` → **"Your trusted source for Islamic content"** (`res/values/strings.xml:262`), `text_body`, centred.

**Links section**: header `about_links` → **"Links"** (`:556`), card with two rows, 1 dp `?attr/colorSurfaceVariant` divider inset `marginStart = spacing_md`. Rows are `padding = spacing_md`, title `text_body` 14sp weight-1, trailing `ic_chevron_right` at `icon_small` 24, `autoMirrored`, not exposed to accessibility (`res/layout/fragment_about.xml:88-176`):
1. `about_website` → **"Website"** (`:558`) → `https://albunyaan.tube`
2. `about_github` → **"GitHub"** (`:559`) → `https://github.com/albunyaan/albunyaan-tube`

**Legal section**: header `about_legal` → **"Legal"** (`:557`), card with three rows, same geometry (`:179-301`):
3. `about_privacy_policy` → **"Privacy Policy"** (`:560`) → `https://albunyaan.tube/privacy`
4. `about_terms_of_service` → **"Terms of Service"** (`:561`) → `https://albunyaan.tube/terms`
5. `about_open_source_licenses` → **"Open Source Licenses"** (`:562`) → `https://albunyaan.tube/licenses`

All five open in the **external browser** via `ACTION_VIEW` with no error handling — an unhandled intent would throw `ActivityNotFoundException` (`ui/settings/AboutFragment.kt:142-167`). iOS: `openURL` (Safari) or `SFSafariViewController`. Note the domain here is `albunyaan.tube`, while deep links use `app.fitrahtube.com` (`docs/superpowers/specs/2026-08-23-ios-app-design.md:121`) — confirm which host the marketing/legal pages live on before shipping.

### 3.2 Seven-tap developer gesture

`ui/settings/AboutFragment.kt:38-129`:
- Tap target: the **version text**, `versionText`.
- Threshold `7` taps; timeout `3000 ms` between taps, measured with `SystemClock.elapsedRealtime()` (monotonic — immune to wall-clock changes). iOS: `ProcessInfo.processInfo.systemUptime` or `DispatchTime`, not `Date()`.
- If `now - lastTapTime > 3000` the counter resets to 0 **before** incrementing; `lastTapTime` is updated on every tap regardless.
- Taps 1–3: silent, no feedback.
- Taps 4, 5, 6 (`count >= threshold - 3`): Toast LENGTH_SHORT of the plural `dev_settings_steps_away` with `remaining = 7 - count` → **"You are %d step away from being a developer"** / **"You are %d steps away from being a developer"** (`res/values/strings.xml:615-618`). So tap 4 → "3 steps away", tap 5 → "2 steps away", tap 6 → "1 step away" (singular form).
- Tap 7: counter resets to 0, `featureFlags.logCurrentState()`, then `DeveloperSettingsDialog` is shown (guarded by `isAdded`) (`:110-140`).
- Counter and last-tap time are persisted across configuration changes via `onSaveInstanceState` keys `"developer_tap_count"` / `"developer_last_tap_time"` (`:30-58`). iOS: keep the counter in the view model / `@State` — it survives rotation for free — but do **not** persist it across launches (Android does not).

### 3.3 `DeveloperSettingsDialog` contents

`ui/settings/DeveloperSettingsDialog.kt:67-257`. Material dialog, title `dev_settings_title` → **"Developer Settings"** (`res/values/strings.xml:587`), positive button `dev_settings_done` → **"Done"** (`:590`), no negative button. Body is a `ScrollView` (`isFillViewport = true`) around a vertical stack padded `spacing_md` horizontally / `spacing_sm` vertically.

Header text, `text_caption` 12sp, `home_text_secondary`, `paddingBottom = spacing_md`: `dev_settings_header` → **"Build defaults from BuildConfig. Toggle to override at runtime.\nChanges take effect immediately."** (`:588`).

Then **three feature toggles** in order. Each toggle block (`:259-341`) is `padding = spacing_sm` vertical and renders: a title row (title `text_subtitle` 16sp `home_text_primary`, weight 1, plus a trailing `SwitchMaterial`), a description (`text_caption` 12sp `home_text_secondary`), and a status line (`text_duration` 11sp `home_text_muted`) formatted `dev_settings_status_format` → **"%1$s • %2$s"** (`:596`) from:
- build default → `dev_settings_build_default_on` → **"Build default: ON"** (`:591`) or `dev_settings_build_default_off` → **"Build default: OFF"** (`:592`)
- override state → `dev_settings_using_default` → **"Using build default"** (`:593`), `dev_settings_overridden_on` → **"Overridden to ON"** (`:594`), `dev_settings_overridden_off` → **"Overridden to OFF"** (`:595`)

Override semantics, identical for all three: toggling to a value **equal to the build default clears the override (writes `null`)**; any other value writes an explicit override. The status line recomputes on every toggle from `newOverride = (isChecked == buildDefault) ? nil : isChecked` (`:328-338`).

| # | Title (key → English) | Description | Flag / build default |
|---|---|---|---|
| 1 | `dev_settings_mpd_prefetch_title` → **"MPD Prefetch"** (`:597`) | `dev_settings_mpd_prefetch_desc` → **"Pre-generate DASH MPD on video tap for faster first-frame"** (`:598`) | `isMpdPrefetchEnabled`, default `BuildConfig.ENABLE_MPD_PREFETCH` (`:100-109`) |
| 2 | `dev_settings_ios_fetch_title` → **"iOS Client Fetch"** (`:599`) | `dev_settings_ios_fetch_desc` → **"Use iOS client for HLS manifest extraction (requires iOS UA)"** (`:600`) | `isIosFetchEnabled`, default `BuildConfig.ENABLE_NPE_IOS_FETCH` (`:111-120`) |
| 3 | `dev_settings_generous_crop_title` → **"Generous Crop Budget"** (`:601`) | `dev_settings_generous_crop_desc` → **"Use 20% crop budget for fullscreen (fills screen on S25 Ultra). Default: auto-detected per device."** (`:602`) | `isGenerousCropBudgetEnabled`, default is **device-detected** and must be read from `diagnostics["generous_crop_budget"].buildDefault`, not from the effective value (`:122-134`) |

Then five text-button rows (plain `TextView`s, no button chrome), in order:

| Row | String → English | Colour | Padding (top/bottom) | Behaviour |
|---|---|---|---|---|
| Clear stream cache | `dev_settings_clear_cache` → **"Clear Stream Cache"** (`:603`) | `primary_variant` #35C491 | `spacing_md` / `spacing_sm` | `extractorClient.clearStreamCache()` → Toast SHORT `dev_settings_cache_cleared` → **"Cleared %d cached entries"** (`:604`); on throw → Toast SHORT `dev_settings_cache_clear_failed` → **"Failed to clear cache: %1$s"** (`:605`) (`:137-164`) |
| Reset all | `dev_settings_reset_all` → **"Reset All to Build Defaults"** (`:589`) | `accent_red` | `spacing_lg` / `spacing_sm` | `featureFlags.clearAllOverrides()` then **dismisses the dialog** (`:167-182`) |
| Trip cooldown | `dev_settings_trip_cooldown` → **"Trip Cooldown (1h)"** (`:607`) | `primary_variant` | `spacing_lg` / `spacing_sm` | `cooldownState.trip(IOException("dev-settings"))` → Toast SHORT `dev_settings_cooldown_tripped` → **"Cooldown tripped — until %1$d"** (`:608`) with the raw epoch-millis deadline (`:188-210`) |
| Reset cooldown | `dev_settings_reset_cooldown` → **"Reset Cooldown"** (`:609`) | `primary_variant` | `spacing_md` / `spacing_sm` | `cooldownState.clearAll()` → Toast SHORT `dev_settings_cooldown_reset` → **"Cooldown state cleared"** (`:610`) (`:212-233`) |
| Show telemetry | `dev_settings_show_telemetry` → **"Show Telemetry Log"** (`:611`) | `primary_variant` | `spacing_md` / `spacing_sm` | opens `MeTelemetryLogDialog` — title `dev_settings_telemetry_title` → **"Me-feed Telemetry Log"** (`:612`), empty text `dev_settings_telemetry_empty` → **"(no events yet)"** (`:613`), dismiss `dev_settings_close` → **"Close"** (`:614`) (`:235-250`) |

The three flags are playback-extraction kill switches specific to Android's NewPipe/DASH path; the iOS equivalents belong to `InnerTubeKit`. Port the **dialog mechanics** (7-tap gate, per-flag build-default vs override display, reset-all, clear-cache, cooldown trip/reset, telemetry log) and swap the flag list for whatever iOS extraction flags exist.

---

## 4. `SettingsPreferences` and `LocaleManager`

### 4.1 Keys and defaults

DataStore named **`"settings"`** (`preferences/SettingsPreferences.kt:24`). Keys and defaults (`:71-111,169-172`):

| Key string | Type | Default | Const |
|---|---|---|---|
| `app_locale` | String | `"system"` | `DEFAULT_LOCALE = LOCALE_SYSTEM` (`:101,109`) |
| `audio_only` | Bool | `false` | `DEFAULT_AUDIO_ONLY` (`:169`) |
| `background_play` | Bool | **`true`** | `DEFAULT_BACKGROUND_PLAY` (`:170`) |
| `download_quality` | String | `"medium"` | `DEFAULT_DOWNLOAD_QUALITY` (`:111`) |
| `wifi_only_downloads` | Bool | `false` | `DEFAULT_WIFI_ONLY` (`:171`) |
| `safe_mode` | Bool | **`true`** | `DEFAULT_SAFE_MODE` (`:172`) |
| `theme` | String | `"system"` | `DEFAULT_THEME = THEME_SYSTEM` (`:110`) |
| `onboarding_completed` | Bool | `false` | (`:89,281`) |
| `import_offer_shown` | Bool | `false` | B13 one-time import offer (`:92,292`) |

Value vocabularies: theme `"system"` / `"light"` / `"dark"` (`:104-106`); quality `"low"` / `"medium"` / `"high"` (`ui/settings/QualitySelectionDialog.kt:32`); locale selection `"system"` / `"en"` / `"ar"` / `"nl"` (`preferences/SettingsPreferences.kt:95,101`). **Keep these exact strings on iOS** — they are what syncs and what a future migration would read.

A second, **synchronous** store exists for cold-start reads: `SharedPreferences` named **`"settings_cache"`** with keys **`"cached_theme"`** and **`"cached_locale"`** (`:27-29`). Theme cache stores the *selection*; locale cache stores the **effective** (resolved) locale, not the selection (`:206-208`). Both are written whenever the DataStore value changes (`:276,208`). On iOS `UserDefaults` is already synchronous, so this two-tier cache collapses to one store — **do not port the split**, just note that the persisted locale value on Android is the resolved code while the DataStore value is the selection.

`shouldShowImportOffer()` returns `!importOfferShown` (one snapshot read); callers must additionally check the user is signed in (`:301-310`).

### 4.2 Locale resolution

`getSystemLocale()` (`preferences/SettingsPreferences.kt:126-145`): iterate `Resources.getSystem().configuration.locales` **in the user's priority order** and return the first language in `{en, ar, nl}`; fall back to `"en"`. It deliberately uses `Resources.getSystem()` rather than `LocaleList.getDefault()` so the app's own per-app override does not feed back into the resolution. Example from the source: device order French → Arabic → English resolves to **Arabic**. iOS equivalent: walk `Locale.preferredLanguages`, take the first whose language code is in the supported set, else `"en"` — **not** `Locale.current`, which already reflects the app's override.

`resolveEffectiveLocale(selection)` = `selection == "system" ? getSystemLocale() : selection` (`:152-158`).

`getSystemTheme(context)` reads `configuration.uiMode & UI_MODE_NIGHT_MASK` → `"dark"` / `"light"` (`:164-167`). iOS: `UITraitCollection.userInterfaceStyle` / the environment `colorScheme`.

### 4.3 `LocaleManager` behaviour

`locale/LocaleManager.kt`:
- `LANGUAGE_NATIVE_NAMES = {en: "English", ar: "العربية", nl: "Nederlands"}` (`:39-43`); unknown code → the code uppercased (`:63-65`).
- `LANGUAGE_SELECTION_KEYS = ["system"] + ["en","ar","nl"]` — display order (`:52-54`).
- `applyLocale(code)` → `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))` (`:104-107`).
- `applyStoredLocaleWithResult(context)` reads the **synchronous cache**, falling back to `getSystemLocale()` on a cache miss, applies it, and returns what it applied (`:91-98`).
- `saveAndApplyLocale(context, selection)` is `suspend`: persist the **selection** first, then resolve and apply the **effective** code (`:116-125`). Order matters — persist before apply, because applying recreates the Activity.
- `getLanguageDisplayName(ctx, selection)`: `"system"` → `settings_language_system_default` → **"System default"** (`res/values/strings.xml:523`); otherwise the native name (`:182-188`).
- `getLanguageDisplayNameWithResolved(ctx, selection)`: `"system"` → `settings_language_system_resolved` → **"System default (%1$s)"** (`:524`) with the native name of the resolved locale; otherwise the native name (`:197-205`). Arabic form: **"الافتراضي (%1$s)"** (`res/values-ar/strings.xml:504`), Dutch: **"Systeemstandaard (%1$s)"** (`res/values-nl/strings.xml:442`).
- Documented behaviour to preserve: an **in-app** language change applies immediately (Activity recreation); a **system** language change while the app is backgrounded with `"system"` selected only takes effect **on the next cold start**, when `applyStoredLocale` re-resolves (`:24-30`). On iOS, per-app language lives in system Settings and changing it relaunches the app — closer to Android's cold-start behaviour.

### 4.4 Startup application and the correction pass

`ui/MainActivity.kt:57-62`: **before** `super.onCreate`, apply the cached locale then the cached theme (theme cache miss → `THEME_SYSTEM`, `:527-533`; theme mode mapping identical to the Settings one, `:538-545`).

`onResume` → `verifyAndCorrectStartupSettings()` (`:421,441-508`): runs **once per Activity instance** (both startup values are nulled immediately to defeat rapid pause/resume races), then asynchronously reads the real DataStore values, refreshes the synchronous caches unconditionally (so a mismatch cannot loop), and if the stored theme differs from the applied one calls `applyTheme(stored)` and **returns early** (the Activity may be recreating). Only if theme did not trigger a correction does it check `effectiveLocale` and apply. Every step re-checks `isFinishing || isDestroyed`. DataStore failures are swallowed — settings apply on the next launch.

**iOS:** `UserDefaults` is synchronous, so this whole correction dance is unnecessary. Read theme/locale once at app launch and bind them to the root; the ported requirement is the *outcome* (the persisted value always wins over a stale cache), not the mechanism.

---

## 5. Contract checklist for the iOS implementer

1. Favorites list = APPROVED-only, current-uid-only, `deleted = 0`, sorted `addedAt` DESC; re-subscribe on auth change.
2. Remove is a soft delete + sync push, instant, no confirmation, no undo.
3. Clear-all is gated by an alert ("Clear all favorites?" / "This will remove all videos from your favorites. This action cannot be undone." / "Clear all" / "Cancel"); the toolbar/menu action is **hidden when the list is empty**.
4. Empty state: 96 pt (128 pt regular-wide) heart-outline at 30 % opacity, "No favorites yet", "Tap the heart icon on any video to add it to your favorites". No loading or error state on this screen.
5. Tapping a favorite plays it via the metadata fast path with `videoId`/`title`/`channelName`/`thumbnailUrl`/`durationSeconds` only; no backend fetch, no channel id.
6. Settings row order and section grouping exactly as §2.1, including the Account section **on every size class** (Android's tablet layouts drop it — that is a bug).
7. Selection dialogs: single-choice, tap-to-commit-and-dismiss, Cancel; theme options `system/light/dark`; quality options `low/medium/high` with `_desc` labels in the picker and short labels in the row and confirmation.
8. Feedback channel matters: language and theme changes use a **Toast** (survives recreation on Android); quality, storage and clear-downloads use a **Snackbar**. On iOS all of these are transient banners/none — but keep the *strings*, and drop "App will restart" since iOS does not restart.
9. Five dead Android settings (Audio only, Background play, Safe Mode, Download quality, Wi-Fi-only downloads) must actually do something on iOS (spec D12).
10. Available updates / Check for updates rows: omit on iOS (spec D3).
11. About: version string "Version %1$s (%2$d)", five external links, 7-tap developer gate with silent taps 1–3 and countdown toasts on 4–6, monotonic 3 s window.
12. Persisted keys and value vocabularies unchanged: `app_locale`, `audio_only`, `background_play`, `download_quality`, `wifi_only_downloads`, `safe_mode`, `theme`, `onboarding_completed`, `import_offer_shown`.

---

## 6. Open questions

1. **Clear-all sync semantics.** Android hard-deletes and skips the sync push (`data/local/FavoritesRepository.kt:258-260`), so a later server pull can resurrect cleared favorites. Should iOS tombstone-and-push instead (fixing the bug), or match Android bug-for-bug?
2. **Sign-out destination.** Android routes to a sign-in screen and clears the stack (`ui/settings/SettingsFragment.kt:131-137`); spec D11 says guest mode with no forced sign-in. Confirm iOS returns to the guest Main shell.
3. **Storage Location row.** Purely decorative on Android (never persisted, "External storage not yet implemented"). Drop it on iOS, or replace with a read-only location line?
4. **Language row.** Spec §6 says the Language dialog is not needed (deep link to iOS Settings). Confirm the row stays with a "System default (English)"-style value and a chevron that opens `UIApplication.openSettingsURLString`, and that the three-language list is therefore no longer app-owned.
5. **Legal/marketing host.** About links point at `albunyaan.tube` (`ui/settings/AboutFragment.kt:144-160`) while deep links use `app.fitrahtube.com` and the app is named FitrahTube. Which host ships in the iOS About screen?
6. **Developer flags.** The three Android flags are NewPipe/DASH-specific. Which `InnerTubeKit` flags replace them, and does the iOS dialog keep the cooldown/telemetry affordances (they depend on the Me-feed refresh ladder)?
7. **Favorites error surface.** `FavoritesViewModel.uiEvents` is emitted but never collected on Android, and its two error strings are hardcoded English. Should iOS surface remove/clear failures (needs new localized strings), or stay silent?
