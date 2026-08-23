# Phase 1 — Localisation conversion + asset inventory (Android → iOS)

Scope: everything an iOS implementer needs to produce `Localizable.xcstrings` from
`android/app/src/main/res/values*/strings*.xml`, plus the drawable/raster assets the
phase-1 screens need. Behavioural contract only — no Swift here.

All paths are relative to `/Users/farouqabouumar/Development/albunyaantube/`.
Android res root: `android/app/src/main/res/`. Android java root: `android/app/src/main/java/com/albunyaan/tube/`.

Design-spec anchors read first: `docs/superpowers/specs/2026-08-23-ios-app-design.md:103-128` (§6 Navigation)
and `:130-171` (§7 Design system — colors, type, spacing, radii, components, grids, icons, motion).

---

## 1. File inventory

Strings live in **four** files per locale, not one. A generator that only reads `strings.xml` silently drops 46 keys.

| File | `values/` (en, default) | `values-ar/` | `values-nl/` |
|---|---|---|---|
| `strings.xml` | 732 `<string>` + 10 `<plurals>` (903 lines) | 613 + 9 (825 lines) | 613 + 9 (792 lines) |
| `strings_list_states.xml` | 10 + 1 (18 lines) | 10 + 1 (22 lines) | 10 + 1 (17 lines) |
| `strings_locale.xml` | 5 + 0 (8 lines) | 5 + 0 | 5 + 0 |
| `strings_onboarding.xml` | 31 + 0 (46 lines) | 31 + 0 | 31 + 0 |
| **Total distinct keys** | **778 strings + 11 plurals = 789** | **659 + 10 = 669** | **659 + 10 = 669** |

- `res/values/arrays.xml:1-18` is the only `<string-array>` file: exactly **two** arrays,
  `filter_category_entries` and `filter_category_values`, and they are **hardcoded English
  category names** (`Knowledge`, `Kids`, `Quran`, `Stories`) with no `values-ar`/`values-nl`
  override. They are also never referenced from Kotlin or layouts (see §6 dead keys) — the
  live category list comes from the backend. **Do not port them.**
- There are **no other locale buckets**: `res/values-land/` holds only `dimens.xml`,
  `values-night/` only `colors.xml` + `themes.xml`, `values-sw600dp`/`values-sw720dp` only
  `dimens.xml`(+`themes.xml`). No `values-ar/dimens.xml`, no `res/xml/locales_config.xml`.
- RTL is declared once: `android/app/src/main/AndroidManifest.xml:36` `android:supportsRtl="true"`.
  No RTL-specific layout or string bucket exists — mirroring is entirely automatic. On iOS the
  equivalent is leading/trailing layout + `.forward`/`.backward` SF Symbols (spec §7 "Icons",
  `docs/superpowers/specs/2026-08-23-ios-app-design.md:169`).

### Supported languages

`preferences/SettingsPreferences.kt:95` `SUPPORTED_LOCALES = listOf("en", "ar", "nl")`;
`:101` `LOCALE_SYSTEM = "system"`; `:109` `DEFAULT_LOCALE = LOCALE_SYSTEM`.
`locale/LocaleManager.kt:39-43` native display names — `en → "English"`, `ar → "العربية"`,
`nl → "Nederlands"` (hardcoded in Kotlin, **not** in strings.xml; iOS must hardcode the same
three or read `Locale.localizedString(forIdentifier:)`).
`locale/LocaleManager.kt:52-53` selection order is `["system", "en", "ar", "nl"]`.

**iOS note:** spec §6 (`:121`) already decided the in-app Language sheet is dropped in favour of
the iOS Settings deep link. That makes `settings_language_select_title`, `settings_language_changed`,
`settings_language_change_failed`, `settings_language_system_default`,
`settings_language_system_resolved` and all five `locale_settings_*` keys **not needed on iOS**.
Keep `settings_language` (row label) and `settings_language_system_resolved` only if the row still
shows the effective language.

---

## 2. Translation coverage

**AR and NL are missing exactly the same 119 string keys + 1 plural (`dev_settings_steps_away`) — 120 keys, 15.2% untranslated.**
No key exists in `values-ar`/`values-nl` that is absent from `values/` (zero orphans), so the
generator never has to invent a source string.

Untranslated blocks, by owner:

| Block | Count | Phase 1? |
|---|---|---|
| `auth_*`, `account_blocked_*`, `account_deleted_*` (Sign-in, terminal alerts) | 25 | `account_*` + `ok` surface in the shell |
| `dev_settings_*` (Developer Settings dialog, reached from About) | 33 (+1 plural) | **yes — settings/about** |
| `report_*` (Content report sheet) | 19 | no |
| `profile_*` (Profile screen) | 12 | `profile_title` only (shell toolbar) |
| `settings_account_*` (Settings account section) | 9 | **yes — settings** |
| `channels_empty_*`, `playlists_empty_*` | 4 | **yes — lists** |
| misc: `about_version_format`, `search_clear`, `category_filter_*`, `clear_filter`, `cd_live_stream_thumbnail`, `me_kebab_*`, `navigation_error`, `ok`, `player_*` (4), `update_progress_percent`, `update_version_ready`, `settings_icon_background_play`, `settings_icon_wifi` | 17 | partially |

**51 of the 120 untranslated keys are used by phase-1 screens** (35 of those are `dev_settings_*`
behind the About easter-egg, so only 16 are user-visible on a normal path):
`about_version_format`, `account_blocked_body/title`, `account_deleted_body/title`,
`category_filter_error`, `channels_empty_subtitle/title`, `ok`, `playlists_empty_subtitle/title`,
`profile_title`, `search_clear`, `settings_account_header`, `settings_account_sign_out`,
`settings_account_sign_out_cancel/confirm_action/confirm_body/confirm_title`,
`settings_account_signed_in_as`, `settings_account_signed_in_default`,
`settings_icon_background_play`, `settings_icon_wifi`.

8 untranslated keys are also dead on Android (`category_filter_cleared`, `clear_filter`,
`navigation_error`, `player_refreshing_stream`, `profile_email_locked`, `profile_save_success`,
`report_error`, `report_select_reason`) — drop them.

---

## 3. Plurals

11 plural groups. `values/` and `values-nl/` use **2 categories** (`one`, `other`).
`values-ar/` uses **6** (`zero`, `one`, `two`, `few`, `many`, `other`).

| Key | Source file:line (en) | en cats | ar cats | nl cats | Arg style |
|---|---|---|---|---|---|
| `list_footer_status` | `values/strings_list_states.xml:13` | one/other | 6 (`values-ar/strings_list_states.xml:13`) | one/other (`values-nl/strings_list_states.xml:12`) | `%1$d` |
| `live_watching_count` | `values/strings.xml:355` | one/other | 6 (`values-ar/strings.xml:316`) | one/other (`values-nl/strings.xml:310`) | `%s` |
| `video_uploaded_days_ago` | `values/strings.xml:391` | one/other | 6 (`:355`) | one/other (`:345`) | `%d` |
| `video_count` | `values/strings.xml:397` | one/other | 6 (`:365`) | one/other (`:351`) | `%d` |
| `playlist_item_count` | `values/strings.xml:401` | one/other | 6 (`:373`) | one/other (`:545`) | `%d` |
| `video_views` | `values/strings.xml:405` | one/other | 6 (`:381`) | one/other (`:549`) | `%s` |
| `time_ago_weeks` | `values/strings.xml:409` | one/other | 6 (`:389`) | one/other (`:553`) | `%d` |
| `time_ago_months` | `values/strings.xml:413` | one/other | 6 (`:397`) | one/other (`:557`) | `%d` |
| `time_ago_years` | `values/strings.xml:417` | one/other | 6 (`:405`) | one/other (`:561`) | `%d` |
| `import_youtube_button_import` | `values/strings.xml:422` | one/other | 6 (`:415`) | one/other (`:357`) | `%d` |
| `dev_settings_steps_away` | `values/strings.xml:615` | one/other | **absent** | **absent** | `%d` |

English text (phase-1 relevant):
```
list_footer_status         one "Showing %1$d item"   other "Showing %1$d items"
live_watching_count        one "%s watching"          other "%s watching"
video_uploaded_days_ago    one "%d day ago"           other "%d days ago"
video_count                one "%d video"             other "%d videos"
playlist_item_count        one "%d item"              other "%d items"
video_views                one "%s view"              other "%s views"
time_ago_weeks             one "%d week ago"          other "%d weeks ago"
time_ago_months            one "%d month ago"         other "%d months ago"
time_ago_years             one "%d year ago"          other "%d years ago"
import_youtube_button_import one "Import %d item"     other "Import %d items"
dev_settings_steps_away    one "You are %d step away from being a developer"
                           other "You are %d steps away from being a developer"
```

### 3a. Arabic drops the placeholder in 8 of 60 plural items — expected, not a bug

`values-ar` writes the number out in words for `zero`/`one`/`two` and omits `%d`/`%1$d` entirely:

| Key + category | en | ar |
|---|---|---|
| `video_uploaded_days_ago \| one` | `%d day ago` | `منذ يوم` |
| `video_count \| one` | `%d video` | `فيديو واحد` |
| `playlist_item_count \| one` | `%d item` | `عنصر واحد` |
| `time_ago_weeks \| one` | `%d week ago` | `منذ أسبوع` |
| `time_ago_months \| one` | `%d month ago` | `منذ شهر` |
| `time_ago_years \| one` | `%d year ago` | `منذ سنة` |
| `import_youtube_button_import \| one` | `Import %d item` | `استيراد عنصر واحد` |
| `list_footer_status \| one` | `Showing %1$d item` | `عرض عنصر واحد` |

(the `zero` and `two` categories do the same throughout — `لا فيديوهات`, `فيديوهان`, …).
This is legal in `.xcstrings`: a plural variation's `stringUnit.value` need not contain the
specifier. **A generator that asserts "every variation contains the parent's format specifiers"
will reject all 8 and must not.** It should instead assert the weaker rule: *no variation contains
a specifier the parent does not declare*.

### 3b. The two plurals whose quantity is decoupled from the printed argument — the only real conversion hazard

`video_views` and `live_watching_count` print a **pre-formatted, locale-compacted string** (`%s`,
e.g. `1.2K`, `١٫٢ مليون`, `1,2 mln`) but select the plural category from a **different, clamped
integer**:

`util/CountFormat.kt:58`
```kotlin
fun compactPluralCount(count: Long): Long = if (count >= 1_000L) 1_000_000L else count
```
with the rationale spelled out at `util/CountFormat.kt:49-57`: once the number is abbreviated the
counted noun in Arabic follows the *unit word* (ألف/مليون/مليار), which is CLDR `other`, **not** the
raw count's one/two/few form. So 1,103 must read `١٫١ ألف مشاهدة`, never `…مشاهدات`.

Call sites, all identical in shape:
- `ui/adapters/VideoGridAdapter.kt:51-57`
- `ui/adapters/FeaturedListAdapter.kt:170-177`
- `ui/SearchResultsAdapter.kt:178-184`
- `ui/detail/adapters/ChannelVideoAdapter.kt:53-58`
- `ui/detail/adapters/ChannelLiveAdapter.kt:110-117` (`live_watching_count`), `:128-134` (`video_views`)

with `safeQuantityForPlural(count) = count.coerceAtMost(Int.MAX_VALUE).toInt()`
(`ui/SearchResultsAdapter.kt:257-259` and four identical copies).

The compact formatter itself is `util/CountFormat.kt:31-47`: ICU `CompactDecimalFormat`, SHORT
style, `maximumFractionDigits = 1`, per-app locale, cached per `Locale`. iOS equivalent is
`Number.FormatStyle` `.notation(.compact)` with `.precision(.fractionLength(0...1))` and the
app's effective `Locale` — **not** `.abbreviated` on a raw `Int` with default precision (that
rounds `12_700_000` to `13M`, which Android explicitly avoids at `util/CountFormat.kt:44-46`).

**Contract for iOS:** these two keys must take **two** arguments — arg 1 the formatted string
(`%1$@`), arg 2 the clamped selector integer (`%2$lld`) which is *not* rendered — modelled as an
`.xcstrings` `substitutions` entry with `argNum: 2`, `formatSpecifier: "lld"`, and variation values
that contain only `%1$@`. Direct top-level `variations.plural` cannot express this (it keys off
argument 1). Alternatively, resolve the category in Swift and address six explicit keys; the
substitution form is smaller and keeps the catalog authoritative.

---

## 4. Format-argument styles

Across all 789 keys (counting each plural group once):

| Style | Count | Notes |
|---|---|---|
| No arguments | 682 | trivial 1:1 |
| Positional `%1$s` / `%1$d` / `%2$02d` | 87 | dominant style, maps directly |
| Non-positional `%s` / `%d` / `%.1f` | 20 | must be rewritten (§5) |
| Literal `%%` | 6 | `dev_settings_generous_crop_desc`, `download_item_content_description`, `download_notification_progress`, `player_download_in_progress`, `playlist_video_downloading`, `update_progress_percent` |

The 20 non-positional keys (**this is the exact list**):
`a11y_duration_format`, `channel_joined_date`, `channel_subscribers_format`, `channel_total_views`,
`dev_settings_cache_cleared`, `dev_settings_steps_away`†, `import_youtube_button_import`†,
`live_watching_count`†, `playlist_item_count`†, `time_ago_months`†, `time_ago_weeks`†,
`time_ago_years`†, `video_count`†, `video_uploaded_days_ago`†, `video_views`†,
`video_views_format`, `views_count`, `views_count_billions`, `views_count_millions`,
`views_count_thousands` († = plural group).

Float specifiers (`%.1f`) appear in exactly three keys, all dead on Android (§6):
`values/strings.xml:428-430` `views_count_billions` `%.1fB views`, `views_count_millions`
`%.1fM views`, `views_count_thousands` `%.1fK views`. These predate `CountFormat` and are
superseded by it. **Do not port them** — porting them would reintroduce non-locale-aware
abbreviation that `util/CountFormat.kt` exists to replace.

Unusual specifier worth calling out: `values/strings.xml:107`
`player_duration_minutes_seconds` = `%1$d:%2$02d` — zero-padded width. Player screen, not phase 1,
but the `%02d` form must survive the generator (`%02lld` on iOS).

---

## 5. Conversion rules for the xcstrings generator

Input: the four `strings*.xml` files in `values/`, `values-ar/`, `values-nl/`.
Output: one `Localizable.xcstrings` (JSON, `"version": "1.0"`, `"sourceLanguage": "en"`).

### R1 — Key naming: keep Android keys verbatim
Use the Android snake_case key as the `.xcstrings` key unchanged (`home_empty_content`,
`nav_channels`, `onboarding_page1_title`). Reasons: every citation in this brief and in the design
spec references the Android key; a rename table is a second artefact to keep in sync; and the
keys are already namespaced by prefix. Do **not** dot-namespace or camelCase.
Swift access is then `String(localized: "home_empty_content")` and the key stays greppable against
Android when parity is questioned. Generate a `LocKeys` enum of the ~281 phase-1 keys if compile-time
safety is wanted — that is a separate, purely additive step.

### R2 — Merge order and collisions
Read the four files in a fixed order (`strings.xml`, `strings_list_states.xml`,
`strings_locale.xml`, `strings_onboarding.xml`) into one flat namespace. **Verified: there are zero
duplicate keys across the four files in any locale**, so the merge is safe; the generator should
still hard-fail on a duplicate rather than last-write-wins.

### R3 — Format specifier rewriting
Rewrite left-to-right in one pass over the raw XML text value:

| Android | iOS | Rule |
|---|---|---|
| `%%` | `%%` | **Match first**, before any other specifier, so `%1$d%%` does not become `%1$lld%` |
| `%N$s` | `%N$@` | positional string |
| `%N$d` | `%N$lld` | positional int — `lld` not `d`, Android passes `Int`/`Long`; `lld` is safe for both and matches Xcode's own generator |
| `%N$0Wd` (e.g. `%2$02d`) | `%N$0Wlld` | keep flags/width, swap conversion |
| `%s` | `%@` | see R4 |
| `%d` | `%lld` | see R4 |
| `%.Nf` | — | reject; the only three occurrences are dead (§4) |

Assert afterwards that no bare `%s`, `%d` or `%f` survives.

### R4 — Positional mapping for non-positional strings
For a value with non-positional specifiers, number them **in left-to-right order of occurrence**,
starting at 1, and emit positional form (`%1$@`, `%2$lld`, …). Every one of the 20 non-positional
keys has exactly **one** argument, so this is unambiguous in practice — but emit positional form
anyway, because Arabic and Dutch translations of multi-arg strings may reorder and `.xcstrings`
plural substitutions require explicit `argNum`. Then:
- **Cross-check the argument list against every locale.** If `ar` or `nl` for the same key yields a
  different ordered list of conversions, fail the build with the key name. Verified today: the only
  divergences are the 8 Arabic plural items in §3a, which are *fewer* args, not different args —
  whitelist "translation has a strict subset of the source's specifiers" and reject everything else.

### R5 — Android escape unescaping
Apply, in this order, to every value before writing JSON:
1. XML entity decode (`&amp;` → `&`, `&lt;`, `&#…`). Two occurrences, both `&amp;`:
   `values/strings.xml:460` `downloads_library_title` = `Downloads & Library`,
   `values/strings.xml:553` `settings_about_support` = `About & Support`.
2. `\'` → `'` (28 in `values/strings.xml`, 31 in `values-nl/strings.xml`, 0 in `values-ar`).
3. `\"` → `"` (1: `values/strings.xml:576` `search_try_different` = `Try different keywords for "%1$s"`).
4. `\’` → `’` — a *redundant* Android escape of U+2019. **4 occurrences, all in
   `values/strings_onboarding.xml`**: `:31` `bootstrap_error_save_failed`, `:36`
   `bootstrap_error_password_mismatch`, `:37` `bootstrap_error_password_set_failed`, `:44`
   `age_ineligible_body`. A naive `\X → X` rule handles it; a rule that only knows `\'` leaves a
   stray backslash in the shipped string.
5. `\@` → `@` — 3 occurrences, all `shorts_channel_handle`
   (`values/strings.xml:659`, `values-ar/strings.xml:641`, `values-nl/strings.xml:607`).
6. `\n` → real newline. 4 occurrences: `values/strings.xml:217` `update_available_message`
   (`\n\n`), `values/strings.xml:588` `dev_settings_header` (`\n`), plus the two
   `update_available_message` translations (`values-ar/strings.xml:188`, `values-nl/strings.xml:198`).
7. `\uXXXX` → the character. **1 occurrence**: `values-ar/strings.xml:641`
   `shorts_channel_handle` = `‎\@%1$s` — a LEFT-TO-RIGHT MARK before the `@` so the Latin
   handle renders correctly inside an RTL paragraph. The generator must emit the literal U+200E,
   and the file must not be "cleaned" of invisible characters. (Not phase 1 — Shorts.)
8. Leave `%%` alone (R3 already handled it).

**Verified absent, so no rule needed**: `<![CDATA[…]]>` (zero occurrences in all 12 files); any
HTML markup tags (`<b>`, `<i>`, `<u>`, `&lt;…&gt;`) — zero, so **no `AttributedString` / markdown
handling is required anywhere**; `translatable="false"` — zero; leading/trailing significant
whitespace — zero; multi-line literal values — zero; any bidi control character other than the one
`‎` above.

### R6 — Plural emission
- Whole-string plural, single integer argument (9 of 11 groups): emit
  `localizations.<lang>.variations.plural.<category>.stringUnit.value` directly. No `substitutions`
  wrapper.
- `video_views` and `live_watching_count`: emit the two-argument `substitutions` form of §3b.
- Categories: copy exactly what the XML declares. `en`/`nl` → `one`, `other`. `ar` → `zero`, `one`,
  `two`, `few`, `many`, `other`. Do **not** synthesise missing categories; `.xcstrings` falls back
  to `other`, matching Android.
- Do **not** emit `zero` for `en`/`nl` — CLDR has no `zero` for either, and Xcode flags it.
- `dev_settings_steps_away` has no `ar`/`nl` variation at all → emit English only, marked per R7.

### R7 — Untranslated fallback
For the 120 keys absent from a locale, choose **one** of:
- (preferred) omit the `ar`/`nl` `localizations` entry entirely. Foundation falls back to the
  source language at runtime, which is exactly Android's `values/` fallback behaviour, and Xcode
  shows the key as `NEW`/untranslated in the String Catalog editor — a visible, actionable
  backlog rather than a silent English string masquerading as a translation.
- (do not) copy the English value into `ar`/`nl` with `"state": "translated"`. That erases the gap.

If a state must be written, use `"state": "new"` on the source unit and no target unit.
Set `"shouldTranslate": false` only for `app_name` (`values/strings.xml:3` = `FitrahTube`), which
is a brand name identical in all three locales.

### R8 — What the generator must refuse to convert (see §7)
Fail loudly, do not guess: `%.1f` keys, `<string-array>`, any key whose per-locale argument lists
differ beyond the §3a subset rule, any surviving bare `%s`/`%d`.

### R9 — One runnable check
The generator's only non-trivial logic is R3+R4. The minimum check that fails if it breaks:
round-trip the 87 positional + 20 non-positional keys and assert (a) the ordered conversion list
of every emitted value equals the expected list, (b) `%%` survives as `%%` in all 6 keys, (c) the
8 Arabic short-form plural items emit with zero specifiers and are accepted, (d) `‎` survives
in `shorts_channel_handle`.

---

## 6. Dead keys — 140 declared and never referenced

Computed by scanning every `.kt`, every `res/layout*/`, `res/menu/`, `res/navigation/` file and
`AndroidManifest.xml` for `R.string.X` / `@string/X` / `R.plurals.X` / `@plurals/X`.
**140 of 789 keys (17.7%) have zero references.** Porting them is pure cost.

Notable clusters, because they look load-bearing but are not:

- **The entire filter option vocabulary is dead.** `filter_length_any/short/medium/long`,
  `filter_date_any/last_24_hours/last_7_days/last_30_days`, `filter_sort_default/newest/popular`,
  `filter_category_all`, `filter_chip_value`, `clear_filter`, `close_filter`. Cause:
  `data/filters/FilterState.kt:17-21` declares `VideoLength`, `PublishedDate`, `SortOption`
  enums and `res/menu/filter_menu.xml:1-18` declares the five menu entries, but **no fragment ever
  renders the length/date/sort pickers** — grep for `VideoLength.` / `PublishedDate.` /
  `SortOption.` under `ui/` returns nothing. Only the Category filter is wired (via
  Categories → Subcategories, spec §6 `:125`). The strings are fully translated into ar and nl,
  so if iOS *implements* these pickers (a D12-style "make the dead thing real" call) the
  translations are already there — but that is a product decision, not a port.
- **The whole `list_*` empty/error vocabulary from `strings_list_states.xml` is dead except two
  keys.** Live: `list_error_title`, `list_error_description`. Dead: `list_error_offline_title`,
  `list_error_offline_body`, `list_error_server_body`, `list_retry`, `list_empty_title`,
  `list_empty_body`, `list_empty_clear`, `list_footer_status` (the plural!), `list_footer_freshness`.
  The screens use the per-type strings instead (`channels_empty_title`, `videos_empty_subtitle`, …).
- **All five `locale_settings_*` keys are dead** — a Language screen that was designed, translated
  into ar and nl, and never built. Reinforces the §1 note that iOS drops the Language sheet.
- **`error_network`/`error_server`/`error_parse`/`error_timeout` + their `_title` variants are dead**
  (8 keys). Live error copy is `error_state_generic_headline` / `list_error_title` /
  `search_error_generic`. If iOS wants typed error copy it is inventing it, not porting it.
- `cd_bottom_nav_home/categories/library/settings` are dead **and stale** — they name a
  Home/Categories/Library/Settings tab set that no longer exists
  (`res/menu/bottom_nav_menu.xml:1-22` is Home/Channels/Me/Playlists/Videos).
- Others of note: `views_count*` (4, superseded by `CountFormat`), `type_video/channel/playlist`,
  `section_videos/channels/playlists`, `home_see_all_videos/channels/playlists/featured`,
  `nav_downloads`, `menu_settings`, `offline_mode`, `offline_mode_limited`,
  `settings_*_desc` (6 subtitle strings the layouts never bind), `onboarding_help`,
  `onboarding_help_body`, `a11y_duration_format`, `a11y_download_item`, and ~11 `cd_*`
  content-description keys.

---

## 7. Strings that cannot be converted mechanically

Seven items. Everything else in the 789 is a pure text-and-specifier transform.

1. **`video_views` and `live_watching_count`** (`values/strings.xml:405`, `:355`) — plural category
   comes from `CountFormat.compactPluralCount()` (`util/CountFormat.kt:58`), not from the printed
   `%s`. Needs the hand-written two-argument substitution of §3b **and** a Swift-side compact
   formatter that reproduces `maximumFractionDigits = 1` + the ≥1000 → `other` clamp. A mechanical
   converter will produce a catalog that compiles and renders wrong Arabic grammar for every
   view count ≥ 1000.
2. **`res/values/arrays.xml`** — a `<string-array>` has no `.xcstrings` equivalent, and its
   contents (`Knowledge`, `Kids`, `Quran`, `Stories`) are untranslated English category names that
   duplicate backend data. Drop, do not convert.
3. **`views_count_billions` / `views_count_millions` / `views_count_thousands`**
   (`values/strings.xml:428-430`) — `%.1f` + a hardcoded English `B`/`M`/`K` suffix. Not
   locale-correct (Arabic uses ألف/مليون/مليار, Dutch mln/mld) and already dead. Drop; use
   `Number.FormatStyle.notation(.compact)`.
4. **`shorts_channel_handle`** (`values-ar/strings.xml:641`) — the value is `‎\@%1$s`. The
   LRM is semantic, not whitespace. Any pipeline step that normalises or strips invisible
   characters silently breaks Arabic Shorts. Needs an explicit unicode-escape rule (R5.7) plus a
   test. (Not phase 1.)
5. **`player_duration_minutes_seconds`** (`values/strings.xml:107`) `%1$d:%2$02d` — the only
   width/zero-pad specifier. iOS should not port it at all: use
   `Duration.formatted(.time(pattern: .minuteSecond))` so hours roll over correctly, which the
   Android string cannot do. (Not phase 1.)
6. **`dev_settings_steps_away`** — plural present in `en` only. Mechanically fine, but the
   generator must not crash on a plural group with no translations, and must not fabricate `ar`
   categories.
7. **`about_version_format`** (`values/strings.xml:555`) `Version %1$s (%2$d)` — `%2$d` is the
   Android `versionCode`. iOS has `CFBundleVersion`, a **String**, not an int. The specifier must
   become `%2$@`, not `%2$lld`, and the key's argument type changes. It is also untranslated in
   both locales, so nothing downstream breaks.

---

## 8. Phase-1 key inventory (281 keys touched by phase-1 screens)

Method: every `R.string.` / `@string/` / `R.plurals.` / `@plurals/` reference resolved to its
owning file, then to a screen bucket. A key can belong to several buckets. Counts below are
"any bucket", so they sum to more than 281.

| Bucket | Keys | Owning Android sources |
|---|---|---|
| splash | 3 | `ui/SplashFragment.kt`, `res/layout/fragment_splash.xml` |
| onboarding | 10 | `ui/OnboardingFragment.kt`, `onboarding/OnboardingPage.kt`, `res/layout/fragment_onboarding.xml`, `res/layout/page_onboarding_item.xml` |
| shell | 19 | `ui/MainActivity.kt`, `res/layout*/fragment_main_shell.xml`, `res/menu/bottom_nav_menu.xml`, `res/navigation/main_tabs_nav.xml` |
| home | 24 | `ui/HomeFragment.kt`, `ui/adapters/HomeFeaturedAdapter.kt`, `ui/adapters/HomeSectionAdapter.kt`, `res/layout*/fragment_home_new.xml`, `res/layout/home_section_{empty,error}.xml`, `res/menu/home_menu.xml` |
| lists (Channels/Playlists/Videos) | 36 | `ui/{Channels,Playlists,Videos}FragmentNew.kt`, `ui/ContentListViewModel.kt`, `ui/adapters/{Channel,Playlist,VideoGrid}Adapter.kt`, `res/layout/fragment_channels_new.xml`, `res/layout/fragment_simple_list.xml`, `res/layout/item_list_footer.xml`, `res/layout/{empty,error}_state.xml`, `res/menu/filter_menu.xml` |
| search | 21 | `ui/SearchFragment.kt`, `ui/SearchViewModel.kt`, `ui/Search{History,Results}Adapter.kt`, `res/layout*/fragment_search.xml`, `res/layout/item_search_history.xml` |
| categories | 4 | `ui/categories/CategoriesFragment.kt`, `ui/categories/SubcategoriesFragment.kt`, `res/layout/fragment_{categories,subcategories}.xml` |
| featured | 12 | `ui/FeaturedListFragment.kt`, `ui/FeaturedListViewModel.kt`, `ui/adapters/FeaturedListAdapter.kt`, `res/layout/fragment_featured_list.xml` |
| favorites | 14 | `ui/favorites/FavoritesFragment.kt`, `ui/favorites/FavoritesAdapter.kt`, `res/layout*/fragment_favorites.xml`, `res/layout/item_favorite_video.xml`, `res/menu/favorites_menu.xml`, `res/layout/library_item_*.xml` |
| settings | 103 | `ui/settings/SettingsFragment.kt`, `ui/settings/{Theme,Quality,Language}SelectionDialog.kt`, `ui/settings/DeveloperSettingsDialog.kt`, `ui/settings/MeTelemetryLogDialog.kt`, `ui/settings/availableversions/*`, `res/layout*/fragment_settings.xml`, `res/layout/settings_item_*.xml` |
| about | 12 | `ui/settings/AboutFragment.kt`, `res/layout/fragment_about.xml` |

Out of phase 1: **375 keys** across player (115, incl. Shorts), me (73), auth (58), submissions
(50), downloads (49), update (23), import (23), report (19). A further **55 keys are used only by
Channel/Playlist detail** (`ui/detail/**`, `res/layout*/fragment_{channel,playlist}_detail.xml`),
which is neither in the phase-1 list nor in the named out-of-phase list — treat detail as its own
slice and decide explicitly.

### 8a. Exact phase-1 keys with English text

Format: `source-file:line  key = English value`. `[PLURAL]` bodies are in §3.

**splash** — 3
```
strings.xml:3               app_name = FitrahTube                (also about, home, shell)
strings.xml:262             splash_tagline = Your trusted source for Islamic content   (also about)
strings_onboarding.xml:40   splash_couldnt_connect = Couldn't connect — please sign in again.
```

**onboarding** — 10
```
strings_onboarding.xml:3    onboarding_carousel_content = Onboarding slides
strings_onboarding.xml:6    onboarding_continue = Next
strings_onboarding.xml:7    onboarding_get_started = Get Started
strings_onboarding.xml:8    onboarding_skip = Skip
strings_onboarding.xml:11   onboarding_page1_title = Browse
strings_onboarding.xml:12   onboarding_page1_desc = Explore a diverse collection of Islamic videos, from lectures to documentaries, all in one place.
strings_onboarding.xml:15   onboarding_page2_title = Listen in background
strings_onboarding.xml:16   onboarding_page2_desc = Continue listening to lectures and recitations even when the app is in the background.
strings_onboarding.xml:19   onboarding_page3_title = Download for offline
strings_onboarding.xml:20   onboarding_page3_desc = Save your favorite content to watch or listen offline, anytime, anywhere.
```

**shell** — 16 (+ `app_name`, `settings_available_updates`, `favorites_title`)
```
strings.xml:152  nav_home = Home
strings.xml:153  nav_channels = Channels
strings.xml:154  nav_playlists = Playlists
strings.xml:155  nav_videos = Videos
strings.xml:156  nav_me = Me
strings.xml:213  connectivity_offline_banner = You're offline. Check your connection.
strings.xml:29   filtering_by_category = Category: %1$s
strings.xml:703  account_blocked_title = Account blocked                 [untranslated ar/nl]
strings.xml:704  account_blocked_body = Your account has been blocked by an administrator. Contact support for details.   [untranslated]
strings.xml:705  account_deleted_title = Account deleted                 [untranslated]
strings.xml:706  account_deleted_body = Your account has been deleted. To use FitrahTube again, create a new account.     [untranslated]
strings.xml:707  ok = OK                                                 [untranslated]
strings.xml:745  profile_title = Profile                                 [untranslated]  (nav destination label)
strings.xml:720  my_submissions_title = My Submissions                   (nav destination label)
strings.xml:767  me_kebab_suggest_content = Suggest content              (nav destination label)
strings.xml:859  import_youtube_title = Import from YouTube              (nav destination label)
strings.xml:805  settings_available_updates = Available updates          (nav destination label)
```
The last five are `res/navigation/main_tabs_nav.xml` destination labels; they belong to the Me /
submissions / import slices but the shell owns the title bar. On iOS they are the
`.navigationTitle` of those pushed routes (spec §6 `:117`).

**home** — 24
```
strings.xml:246  see_all = See all
strings.xml:247  home_empty_videos = No videos available yet
strings.xml:254  search = Search
strings.xml:257  menu = Menu
strings.xml:258  settings = Settings
strings.xml:259  downloads = Downloads
strings.xml:305  home_select_category = Select content category
strings.xml:310  home_see_all_category = See all content in %1$s
strings.xml:311  home_empty_content = No content available yet
strings.xml:312  home_loading_more = Loading more…
strings.xml:313  home_retry_section = Retry loading section
strings.xml:382  a11y_video_item = Video: %1$s, Duration: %2$s, %3$s, %4$s
strings.xml:383  a11y_channel_item = Channel: %1$s, %2$s
strings.xml:384  a11y_playlist_item = Playlist: %1$s, %2$d items     (also lists)
strings.xml:389  video_views_format = %s views
strings.xml:390  video_uploaded_today = Today                        (also lists, featured, search)
strings.xml:391  video_uploaded_days_ago = [PLURAL]                  (also lists, featured, search)
strings.xml:397  video_count = [PLURAL]
strings.xml:321  channel_subscribers_format = %s subscribers         (also lists, featured, search)
strings.xml:5    filter_category = Category                          (also lists)
strings.xml:192  clear_filters = Clear filters                       (also lists)
strings.xml:194  retry = Retry                                       (also lists, featured)
strings_list_states.xml:4  list_error_description = Check your connection or adjust your filters, then try again.
strings.xml:255  categories = Categories                             (also lists)
```

**lists** (Channels / Playlists / Videos tabs) — 19 own + 17 shared
```
strings.xml:7    filter_length = Length
strings.xml:12   filter_date = Date
strings.xml:17   filter_sort = Sort
strings.xml:21   filter_clear = Clear filters
strings.xml:27   category_filter_active = Active category filter. Double tap to clear.
strings.xml:248  videos_empty_title = No videos yet
strings.xml:249  videos_empty_subtitle = Approved videos will appear here
strings.xml:250  channels_empty_title = No channels yet              [untranslated ar/nl]
strings.xml:251  channels_empty_subtitle = Approved channels will appear here   [untranslated]
strings.xml:252  playlists_empty_title = No playlists yet            [untranslated]
strings.xml:253  playlists_empty_subtitle = Approved playlists will appear here [untranslated]
strings.xml:346  load_more = Load more
strings.xml:347  load_more_error = Failed to load more. Tap to retry.
strings.xml:127  download_status_running = Downloading
strings.xml:129  download_status_completed = Completed
strings.xml:572  search_clear = Clear search                          [untranslated]
strings.xml:819  empty_state_generic_headline = No content yet
strings.xml:820  error_state_generic_headline = Something went wrong
strings_list_states.xml:3  list_error_title = Unable to load content
```
shared with featured/search: `loading_more` (`:348` = `Loading…`), `category_with_overflow`
(`:325` = `%1$s +%2$s`), `time_ago_weeks/months/years`, `video_views`, `playlist_item_count`,
`search_hint` (`:571` = `Search…`), `search_no_results` (`:575` = `No results found`),
`search_try_different_hint` (`:577` = `Try different keywords`).

Translator note preserved verbatim at `values/strings.xml:322-324`, above `category_with_overflow`:
> `%1$s` is category name, `%2$s` is already locale-formatted count (e.g., "٣" in Arabic).

Carry that into the `.xcstrings` `comment` field — the second argument is a **string**, not an int,
precisely so the count keeps Arabic-Indic digits.

**search** — 9 own
```
strings.xml:367  cd_delete_search_history = Delete search history item
strings.xml:385  a11y_search_history = Recent search: %1$s
strings.xml:573  search_recent = Recent searches
strings.xml:574  search_clear_history = Clear
strings.xml:576  search_try_different = Try different keywords for "%1$s"
strings.xml:579  search_error_generic = Search failed. Please try again.
strings.xml:580  search_loading = Searching…
strings.xml:581  error_title = Error
```
Plus `appbar_scrolling_view_behavior` referenced by `res/layout*/fragment_search.xml` — that key
lives in the **Material Components library**, not in this repo. It is a layout-behaviour class
name, not user-facing text; there is nothing to localise.

**categories** — 4
```
strings.xml:23   category_filter_applied = Filtering by: %1$s
strings.xml:25   category_filter_error = Failed to apply category filter   [untranslated ar/nl]
strings.xml:255  categories = Categories
strings.xml:256  subcategories_title = Subcategories
```

**featured** — 1 own + shared
```
strings.xml:242  section_featured = Featured
```

**favorites** — 12
```
strings.xml:363  cd_video_thumbnail = Video thumbnail
strings.xml:475  library_recently_watched = Recently Watched
strings.xml:478  library_history = History
strings.xml:483  favorites_title = Favorites
strings.xml:484  favorites_empty_title = No favorites yet
strings.xml:485  favorites_empty_subtitle = Tap the heart icon on any video to add it to your favorites
strings.xml:486  favorites_remove = Remove from favorites
strings.xml:487  favorites_clear_all = Clear all
strings.xml:488  favorites_clear_all_title = Clear all favorites?
strings.xml:489  favorites_clear_all_message = This will remove all videos from your favorites. This action cannot be undone.
strings.xml:490  favorites_clear_all_confirm = Clear all
strings.xml:491  favorites_remove_description = Remove %1$s from favorites
strings.xml:492  favorites_icon_description = Favorites
strings.xml:568  cancel = Cancel
```

**settings** — 101 (35 of them `dev_settings_*`, all untranslated, all behind the About
7-tap easter egg at `ui/settings/AboutFragment.kt:110-128`)
```
strings.xml:186  settings_library_header = Library
strings.xml:187  settings_downloads_library = Downloads library
strings.xml:188  settings_favorites_title = Favorites
strings.xml:215  settings_check_for_updates = Check for updates
strings.xml:464  downloads_storage_used = Storage Used
strings.xml:466  downloads_storage_calculating = Calculating…
strings.xml:495  settings_title = Settings
strings.xml:496  settings_general = General
strings.xml:497  settings_playback = Playback
strings.xml:498  settings_audio_only = Audio Only
strings.xml:499  settings_audio_only_desc = Play audio without video to save data
strings.xml:500  settings_background_play = Background Play
strings.xml:501  settings_background_play_desc = Continue playback when app is minimized
strings.xml:502  settings_downloads = Downloads
strings.xml:503  settings_wifi_only = WiFi Only
strings.xml:504  settings_wifi_only_desc = Only download over WiFi connections
strings.xml:505  settings_download_quality = Download Quality
strings.xml:507  settings_download_quality_title = Download Quality
strings.xml:508  settings_quality_low = Low (360p)
strings.xml:509  settings_quality_low_desc = Low (360p) - Save data
strings.xml:510  settings_quality_medium = Medium (720p)
strings.xml:511  settings_quality_medium_desc = Medium (720p) - Balanced
strings.xml:512  settings_quality_high = High (1080p)
strings.xml:513  settings_quality_high_desc = High (1080p) - Best quality
strings.xml:514  settings_quality_changed = Download quality set to %1$s
strings.xml:515  settings_quality_change_failed = Failed to change download quality. Please try again.
strings.xml:516  settings_content = Content
strings.xml:517  settings_safe_mode = Safe Mode
strings.xml:518  settings_safe_mode_desc = Show only family-friendly content
strings.xml:520  settings_language = Language
strings.xml:522  settings_language_select_title = Select Language        [iOS: drop, §1]
strings.xml:523  settings_language_system_default = System default       [iOS: drop]
strings.xml:524  settings_language_system_resolved = System default (%1$s)
strings.xml:525  settings_language_changed = Language changed. App will restart.   [iOS: drop]
strings.xml:526  settings_language_change_failed = Failed to change language. Please try again.  [iOS: drop]
strings.xml:527  settings_theme = Theme
strings.xml:530  settings_theme_system = System default
strings.xml:531  settings_theme_system_resolved = System default (%1$s)
strings.xml:532  settings_theme_light = Light
strings.xml:533  settings_theme_dark = Dark
strings.xml:534  settings_theme_select_title = Select Theme
strings.xml:535  settings_theme_changed = Theme changed
strings.xml:536  settings_theme_change_failed = Failed to change theme. Please try again.
strings.xml:538  settings_storage_location = Storage Location
strings.xml:540  settings_storage_format = Downloads: %1$s • Available: %2$s of %3$s
strings.xml:541  settings_clear_downloads = Clear All Downloads
strings.xml:542  settings_clear_downloads_desc = Delete all downloaded content
strings.xml:543  settings_clear_downloads_title = Clear All Downloads?
strings.xml:544  settings_clear_downloads_message = This will delete all downloaded videos and audio files. This action cannot be undone.
strings.xml:545  settings_clear_downloads_confirm = Clear All
strings.xml:546  settings_files_cleared = Cleared %1$d files
strings.xml:547  settings_download_location = Download Location
strings.xml:548  settings_storage_internal = Internal Storage
strings.xml:549  settings_storage_external = External SD Card (if available)      [iOS: no equivalent]
strings.xml:550  settings_storage_internal_selected = Using internal storage
strings.xml:551  settings_storage_external_not_implemented = External storage not yet implemented   [iOS: drop]
strings.xml:553  settings_about_support = About & Support
strings.xml:563  settings_support_center = Support Center
strings.xml:565  settings_icon_wifi = WiFi only setting icon             [untranslated]
strings.xml:566  settings_icon_background_play = Background play setting icon   [untranslated]
strings.xml:567  settings_icon_safe_mode = Safe mode setting icon
strings.xml:710  settings_account_header = Account                       [untranslated]
strings.xml:711  settings_account_signed_in_as = Signed in as %1$s        [untranslated]
strings.xml:712  settings_account_signed_in_default = Signed in           [untranslated]
strings.xml:713  settings_account_sign_out = Sign out                     [untranslated]
strings.xml:714  settings_account_sign_out_confirm_title = Sign out?      [untranslated]
strings.xml:715  settings_account_sign_out_confirm_body = You'll need to sign in again to access admin features and personalised content.  [untranslated]
strings.xml:716  settings_account_sign_out_confirm_action = Sign out      [untranslated]
strings.xml:717  settings_account_sign_out_cancel = Cancel                [untranslated]
strings.xml:805  settings_available_updates = Available updates
strings.xml:806  available_versions_install = Install
strings.xml:807  available_versions_installed = Installed
strings.xml:808  available_versions_downgrade_deferred = Downgrade not available
strings.xml:809  available_versions_downgrade_snackbar = Downgrading older versions isn't supported yet. Coming in a future update.
strings.xml:810  available_versions_empty = No releases available right now. Try again later.
```
`settings_available_updates` / `available_versions_*` are the Android in-app-update surface, which
spec D3 (`docs/superpowers/specs/2026-08-23-ios-app-design.md:22`) drops on iOS — port only if the
"update required" screen reuses the copy.

All 35 `dev_settings_*` (`values/strings.xml:587-617`), English only:
```
:587 dev_settings_title = Developer Settings
:588 dev_settings_header = Build defaults from BuildConfig. Toggle to override at runtime.\nChanges take effect immediately.
:589 dev_settings_reset_all = Reset All to Build Defaults
:590 dev_settings_done = Done
:591 dev_settings_build_default_on = Build default: ON
:592 dev_settings_build_default_off = Build default: OFF
:593 dev_settings_using_default = Using build default
:594 dev_settings_overridden_on = Overridden to ON
:595 dev_settings_overridden_off = Overridden to OFF
:596 dev_settings_status_format = %1$s • %2$s
:597 dev_settings_mpd_prefetch_title = MPD Prefetch
:598 dev_settings_mpd_prefetch_desc = Pre-generate DASH MPD on video tap for faster first-frame
:599 dev_settings_ios_fetch_title = iOS Client Fetch
:600 dev_settings_ios_fetch_desc = Use iOS client for HLS manifest extraction (requires iOS UA)
:601 dev_settings_generous_crop_title = Generous Crop Budget
:602 dev_settings_generous_crop_desc = Use 20%% crop budget for fullscreen (fills screen on S25 Ultra). Default: auto-detected per device.
:603 dev_settings_clear_cache = Clear Stream Cache
:604 dev_settings_cache_cleared = Cleared %d cached entries
:605 dev_settings_cache_clear_failed = Failed to clear cache: %1$s
:607 dev_settings_trip_cooldown = Trip Cooldown (1h)
:608 dev_settings_cooldown_tripped = Cooldown tripped — until %1$d
:609 dev_settings_reset_cooldown = Reset Cooldown
:610 dev_settings_cooldown_reset = Cooldown state cleared
:611 dev_settings_show_telemetry = Show Telemetry Log
:612 dev_settings_telemetry_title = Me-feed Telemetry Log
:613 dev_settings_telemetry_empty = (no events yet)
:614 dev_settings_close = Close
:615 dev_settings_steps_away = [PLURAL]
```
Note `:602` contains the only `%%` in a phase-1 string, and `:608` prints a raw epoch via `%1$d` —
if that screen is ported, `%1$lld` is correct but the value should really be a formatted date.

**about** — 10
```
strings.xml:554  about_title = About
strings.xml:555  about_version_format = Version %1$s (%2$d)    [untranslated; %2$d → %2$@ on iOS, §7.7]
strings.xml:556  about_links = Links
strings.xml:557  about_legal = Legal
strings.xml:558  about_website = Website
strings.xml:559  about_github = GitHub
strings.xml:560  about_privacy_policy = Privacy Policy
strings.xml:561  about_terms_of_service = Terms of Service
strings.xml:562  about_open_source_licenses = Open Source Licenses
strings.xml:615  dev_settings_steps_away = [PLURAL]              (toast on taps 4-6 of 7)
```

---

## 9. Drawable inventory for phase-1 screens

`res/drawable/` holds 100 files: 87 vector `ic_*`/shape XML + 1 PNG (`albunyaantube_logo.png`).
`res/drawable-anydpi/` holds 3 vectors that also ship as PNGs in the four dpi buckets
(`ic_action_more`, `ic_stat_movie`, `ic_stat_playlist` — the latter two are notification icons).

**41 drawables are reachable from a phase-1 screen.** 5 more are detail-only
(`banner_gradient_top`, `bg_live_badge`, `ic_shuffle`, `skeleton_circle`, `toolbar_scrim_gradient`),
44 are out of phase 1, and **13 are referenced by nothing at all**: `bg_popup_menu`,
`bg_upcoming_badge`, `gradient_bottom`, `gradient_top`, `ic_cast`, `ic_comment`, `ic_like`,
`ic_shorts_subscribe`, `ic_shorts_subscribed`, `ic_splash_house`, `player_button_background`,
`selector_filter_option_bg`, `selector_filter_option_check`. (`ic_cast` being dead is notable given
D3 puts Cast in scope — Android never shipped the button.)

### 9a. Icons → SF Symbols

All are 24×24 dp (`viewportWidth` 24) except four Material-Symbols exports at 960×960
(`ic_playlists`, `ic_videos`, `ic_action_more`, and `ic_launcher_movie`). Every one is a stock
Material Symbol, so the SF Symbol mapping is direct. Spec §7 (`:169`) already mandates
`.forward`/`.backward` variants for direction-sensitive glyphs.

| Android drawable | Used by (phase-1) | Meaning | Suggested SF Symbol |
|---|---|---|---|
| `ic_home` | shell tab 1 | filled house | `house.fill` |
| `ic_channels` | shell tab 2, lists | rounded rect frame with play triangle inside | `tv` (or `play.rectangle`) |
| `ic_nav_me` | shell tab 3 | person bust | `person.crop.circle` |
| `ic_playlists` | shell tab 4, lists | bullet list + circled `+` | `list.bullet.rectangle` (or `text.badge.plus`) |
| `ic_videos` | shell tab 5, lists, home | film strip / clapperboard | `film.stack` (or `movieclapper`) |
| `ic_search` | home toolbar, lists, search | magnifier | `magnifyingglass` |
| `ic_settings` | home toolbar | gear | `gearshape` |
| `ic_download` | home menu, settings rows | tray-arrow-down | `arrow.down.circle` |
| `ic_download_circle` | onboarding page 3 | same glyph, circled context | `arrow.down.circle.fill` |
| `ic_action_more` | home | vertical 3-dot overflow (`tint #333333`, `alpha 0.6`) | `ellipsis` (rendered vertically in a menu button) |
| `ic_chevron_right` | about, categories, favorites, home, settings | disclosure chevron | `chevron.forward` — **must mirror in RTL** |
| `ic_arrow_back` | featured, settings toolbars | back arrow | `chevron.backward` — **must mirror**; on iOS use the system back button |
| `ic_expand_more` | home, lists | chevron down | `chevron.down` |
| `ic_close` | favorites, home, lists | X | `xmark` |
| `ic_filter` | lists | 3 horizontal sliders | `line.3.horizontal.decrease.circle` |
| `ic_category` | home, lists | 2×2 rounded squares | `square.grid.2x2` |
| `ic_error` | home, lists | circle with `!` | `exclamationmark.circle` |
| `ic_cloud_off` | shell offline banner | cloud with slash | `icloud.slash` (or `wifi.slash`) |
| `ic_refresh` | home, settings | circular arrow | `arrow.clockwise` |
| `ic_delete` | favorites | trash can | `trash` |
| `ic_favorite` | favorites, settings row | filled heart | `heart.fill` |
| `ic_favorite_border` | favorites | outlined heart | `heart` |
| `ic_compass` | onboarding page 1 | circle with play-shaped needle | `safari` (or `play.circle`) |
| `ic_headphones` | onboarding page 2 | headphones | `headphones` |
| `ic_logout` | settings sign-out row | arrow exiting door | `rectangle.portrait.and.arrow.right` — **mirror in RTL** |
| `ic_play` | settings background-play row | play triangle | `play.fill` — **mirror in RTL** |
| `ic_shield` | settings safe-mode row | shield with check | `checkmark.shield` |
| `ic_wifi` | settings Wi-Fi-only row | wifi arcs | `wifi` |
| `ic_update` | settings check-for-updates | tray-arrow-down over a bar | `arrow.down.app` (drop with D3) |

### 9b. Shape/selector drawables → SwiftUI, not assets

Not images. Each is a fill + corner radius that becomes a `RoundedRectangle`/`Circle` with a token
colour from spec §7 (`docs/superpowers/specs/2026-08-23-ios-app-design.md:136-160`). Do not import.

| Drawable | Definition | iOS |
|---|---|---|
| `onboarding_icon_bg` | oval, `@color/settings_icon_bg` | `Circle().fill(Tokens.settingsIconBg)`. Reused by every settings row icon and by the generic empty-state icon — one shared modifier. |
| `onboarding_indicator_active` | oval 8×8 dp, `@color/primary_green` | 8 pt dot, brand |
| `onboarding_indicator_inactive` | oval 8×8 dp, `#CCCCCC` | 8 pt dot, `#CCCCCC` (literal, not a token) |
| `thumbnail_placeholder` | rect, radius `@dimen/corner_radius`, `@color/surface_variant` | placeholder fill |
| `home_thumbnail_bg` | rect, radius `home_thumbnail_corner_radius` (12), `surface_variant` | |
| `home_channel_avatar_bg` | oval, `surface_variant` | |
| `home_duration_chip_bg` | rect, radius `home_duration_chip_radius`, `@color/home_duration_bg` | duration chip |
| `home_video_count_chip_bg` | rect, same radius, `@color/home_video_count_bg` | count chip |
| `duration_background` | rect, same radius, `home_duration_bg` | duplicate of the above — collapse to one component |
| `duration_badge_background` | rect, same radius, **hardcoded `#CC000000`** | same; note the literal is the `durationChip` token |
| `card_focus_state` | selector: focused → 2 dp `?colorPrimary` stroke, radius `home_card_corner_radius`; else transparent | D-pad focus ring — iOS gets this free from `.focusable()`; skip |
| `skeleton_shimmer` | rect, radius `corner_radius`, `surface_variant` | skeleton |
| `skeleton_circle` | oval, `@color/skeleton_background` | skeleton (detail only) |

### 9c. Raster assets

| Asset | Path | Pixel size | Bytes | Phase-1 use |
|---|---|---|---|---|
| App logo | `res/drawable/albunyaantube_logo.png` | **800 × 800** | 230,741 | **Splash only** (`res/layout/fragment_splash.xml:12-20`). Single density; no `-hdpi`/`-xhdpi` variants. |
| Launcher, legacy | `res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher.png` | 48 / 72 / 96 / 144 / **192** | 4,171 → 34,050 | App icon (legacy square) |
| Launcher, round | `res/mipmap-*/ic_launcher_round.png` | same sizes | byte-identical to `ic_launcher.png` at every density | not needed on iOS |
| Adaptive foreground | `res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher_foreground.png` | 108 / 162 / 216 / 324 / **432** | 6,443 → 52,846 | **The source for the iOS app icon.** Spec §7 (`:169`) says "adaptive foreground at 1024 px" — the largest available is 432 px, so it must be **upscaled 2.37×, or better, re-exported from `albunyaantube_logo.png` (800 px) or the original vector**. Flag as a real gap. |
| Adaptive background | `res/drawable/ic_launcher_background.xml` | 108 dp vector | 493 | **Fully transparent** — `res/drawable/ic_launcher_background.xml:9-10` is `fillColor="#00000000"` with a comment saying the transparency is deliberate so no white frame leaks behind the logo. iOS app icons **cannot** have alpha, so the iOS icon needs an opaque background chosen deliberately (brand `#275E4B` or white). This is a design decision, not a port. |
| Notification icons | `res/drawable-anydpi/ic_stat_{movie,playlist}.xml` + 4 PNG densities | 24 dp | 279–950 | Downloads/player notifications — out of phase 1; iOS uses the app icon. |
| `ic_launcher_movie` | `res/drawable/ic_launcher_movie.xml` | 24 dp vector | 928 | Player only |

There is **no onboarding artwork**. Onboarding pages 1–3 draw a tinted 24 dp vector
(`ic_compass`, `ic_headphones`, `ic_download_circle`) centred in an
`onboarding_icon_bg` circle — `res/layout/page_onboarding_item.xml:32-49`, with the icon resource
chosen in `onboarding/OnboardingPage.kt`. Nothing to export; three SF Symbols in a circle.

The About screen also has **no logo**: `res/layout/fragment_about.xml:48-53` is a plain `View`
sized `@dimen/channel_avatar_size` filled with `@color/primary_variant`, commented
`<!-- App Icon Placeholder -->`. iOS should show the real app icon there instead — a deliberate
improvement, not a parity break.

### 9d. Splash / onboarding metrics (dp), for the SwiftUI port

| Dimen | compact | sw600dp | sw720dp | Source |
|---|---|---|---|---|
| `splash_logo_size` | 160 | 220 | 280 | `values/dimens.xml:209`, `values-sw600dp/dimens.xml:106`, `values-sw720dp/dimens.xml:116` |
| `splash_title_size` | 32 sp | 40 sp | 48 sp | `values/dimens.xml:210`, `:107`, `:117` |
| `onboarding_icon_container_size` | 160 | 160 | 180 | `values/dimens.xml:99`, `values-sw600dp:70`, `values-sw720dp:74` |
| `onboarding_icon_size` | 80 | 80 | 90 | `values/dimens.xml:100`, `:71`, `:75` |
| `onboarding_title_size` | 28 sp | 32 sp | 36 sp | `values/dimens.xml:101`, `:72`, `:76` |
| `onboarding_description_size` | 16 sp | 18 sp | 20 sp | `values/dimens.xml:102`, `:73`, `:77` |
| `onboarding_padding` | 24 | 32 | 40 | `values/dimens.xml:103`, `:74`, `:78` |
| `onboarding_button_height` | 56 | 60 | 64 | `values/dimens.xml:104`, `:75`, `:79` |
| `onboarding_button_corner_radius` | 28 | 30 | 32 | `values/dimens.xml:109`, `:76`, `:80` |
| `onboarding_button_max_width` | 400 | — | — | `values/dimens.xml:110` |
| `onboarding_content_max_width` | 600 | — | — | `values/dimens.xml:111` |

Splash layout (`res/layout/fragment_splash.xml`): logo centred with `verticalBias 0.35`;
app name below it at `spacing_lg` margin, bold, `colorOnBackground`; tagline below that at
`spacing_sm`, `text_subtitle`, `colorOnSurfaceVariant`, `paddingStart/End = spacing_xl`, centred;
indeterminate spinner pinned to the bottom at `spacing_xxl`, tinted `colorPrimary`. All four views
start `invisible` and are revealed by the animation (spec D13, `:36`).

Onboarding page (`res/layout/page_onboarding_item.xml`): 24 dp top guideline, 80 dp bottom
guideline (reserved for the indicator/button overlay), icon + title + description in a packed
vertical chain at `verticalBias 0.35`; title `spacing_xl` below the icon, description `spacing_md`
below the title with `lineSpacingExtra 4dp`; both texts capped at `onboarding_content_max_width`
(600 dp) and centred; colours `@color/home_text_primary` / `@color/home_text_secondary`.

---

## 10. Recommended slice of work

1. Generator reads all four files × three locales, applies R1–R7, emits one `Localizable.xcstrings`.
2. Emit **only the 281 phase-1 keys minus the dead ones** for phase 1 — but write the generator to
   handle all 789 so later phases are a re-run, not a rewrite.
3. Hand-write the two `substitutions` plurals (§3b) and the `CountFormat` port; everything else is
   mechanical.
4. Zero images to import for phase 1 except `albunyaantube_logo.png` (splash) and a re-exported
   1024 px app icon on an opaque background.

---

## 11. Appendix — resolved values for every token cited in §9b

So the shape drawables can be reproduced without a second lookup. Light values; dark pairs are in
spec §7 (`docs/superpowers/specs/2026-08-23-ios-app-design.md:136-160`).

| Reference | Resolved | Source |
|---|---|---|
| `@dimen/corner_radius` | **20 dp** (not 8) | `values/dimens.xml:16` |
| `@dimen/corner_radius_medium` | 16 dp | `values/dimens.xml:18` |
| `@dimen/home_card_corner_radius` | 16 dp | `values/dimens.xml:126` |
| `@dimen/home_thumbnail_corner_radius` | 12 dp | `values/dimens.xml:127` |
| `@dimen/home_duration_chip_radius` | **4 dp** | `values/dimens.xml:132` |
| `@dimen/thumbnail_corner_radius` | 8 dp | `values/dimens.xml:149` |
| `@dimen/channel_avatar_size` | 80 dp | `values/dimens.xml:174` (About placeholder) |
| `@dimen/home_channel_avatar_size` | 72 dp | `values/dimens.xml:153` |
| `@dimen/spacing_sm / lg / xl / xxl` | 8 / 24 / 32 / 48 dp | `values/dimens.xml:7,9,10,11` |
| `@dimen/text_subtitle` | 16 sp | `values/dimens.xml:195` |
| `@color/surface_variant` | `#E3E9E7` | `values/colors.xml:5` |
| `@color/primary_variant` | `#35C491` | `values/colors.xml:4` |
| `@color/home_text_primary` | `#1A1A1A` | `values/colors.xml:34` |
| `@color/home_text_secondary` | `#6B7280` | `values/colors.xml:35` |
| `@color/home_duration_bg` | `#CC000000` | `values/colors.xml:37` |
| `@color/home_video_count_bg` | `#CC275E4B` | `values/colors.xml:38` |
| `@color/skeleton_background` | `#E0E0E0` | `values/colors.xml:57` |
| `@color/settings_icon_bg` | `#F0F0F0` | `values/colors.xml:84` |

Two things a mechanical read would get wrong:
- `thumbnail_placeholder` and `skeleton_shimmer` use `@dimen/corner_radius` = **20 dp**, which does
  not match any radius in spec §7's table (card 16, homeThumbnail 12, thumbnail 8, chip 4). They
  are placeholder fills behind a 12 dp-clipped image, so the 20 dp is invisible in practice — use
  the enclosing component's radius on iOS rather than porting 20.
- `duration_badge_background` hardcodes `#CC000000` instead of referencing `@color/home_duration_bg`,
  which holds the identical value. Same token on iOS; the duplication is an Android artefact.
