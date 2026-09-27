# Phase 2 research — Share / Report content / Link handling

Scope: `share/ShareLinks`, `share/ShareMetadataPublisher`, the four share entry points,
`ui/report/*` + `data/report/*` + backend `ContentReportController`/`ContentReportService`,
`AndroidManifest.xml` intent filters, nav-graph `<deepLink>` mapping, `MainActivity` routing,
`SplashFragment` deep-link launch, `PlayerDescriptions` link stripping, backend
`WatchPageController` (what a shared URL actually serves).
Every claim cites `android/app/src/main/**` or `backend/src/main/**` file:line.
Behavioural contract for the SwiftUI port — no Swift code here. Phase 3 (downloads/Cast) and
Phase 4 (accounts) surfaces are excluded except where they leak into these flows (noted inline).

---

## 0. TL;DR for the implementer

1. **One URL builder, three content types.** Share URLs are always
   `https://app.fitrahtube.com/api/{watch|channel|playlist}/{id}` when `SHARE_BASE_URL` is
   configured (it is, by default), falling back to `albunyaantube://{video|channel|playlist}/{id}`
   only when the base URL is blank (§1.2). There is no per-type format variance.
2. **Exactly four share entry points**: player Share button, Shorts page Share button, channel
   kebab, playlist kebab (§1.1). No share from list cells, search results, or long-press anywhere.
3. **Report is one bottom sheet, five entry points**, POSTing to `/api/v1/reports` with an
   `X-Device-Id` header attached by an OkHttp interceptor to *every* API request (§2.5). Backend
   throttle: **5 reports per device per rolling hour** → HTTP 429 → dedicated toast (§2.6).
4. **Parent context matters.** Reports launched from channel-tab-originated players carry
   `parentType`/`parentId`/`contentSubType` so admin resolution excludes the item from the right
   bucket — but the player's *on-screen* Report button drops that context while the player's
   *kebab* Report keeps it (defect, §2.1).
5. **Inbound links**: custom scheme `albunyaantube://` (hosts `video|channel|playlist`) + verified
   App Links on `https://app.fitrahtube.com` for `/watch/`, `/api/watch/`, `/channel/`,
   `/api/channel/`, `/playlist/`, `/api/playlist/` (§3.1). Routing is done by the Navigation
   component's `<deepLink>` tags, not hand-parsed (§3.2).

---

## 1. Share

### 1.1 Entry points — exactly four

| # | Surface | Trigger | Handler | Target type |
|---|---|---|---|---|
| 1 | Player | on-screen `shareButton` under the video | `PlayerFragment.kt:497-499` → `shareCurrentVideo()` (`:3314`) | video |
| 2 | Shorts player | per-page `shortShareBtn` | `ShortsPageViewHolder.kt:83` → adapter callback `onShare` (`ShortsPagerAdapter.kt:49,150-152`) → `ShortsPlayerFragment.kt:297` → `shareShort(idx)` (`:594`) | video (a Short shares as a watch link) |
| 3 | Channel detail | toolbar kebab `R.id.action_share` | `ChannelDetailFragment.kt:153-158` → `shareChannel()` (`:410`) | channel |
| 4 | Playlist detail | toolbar kebab `R.id.action_share` | `PlaylistDetailFragment.kt:238-243` → `sharePlaylist()` (`:580`) | playlist |

The kebab menu is `res/menu/menu_detail_kebab.xml` — two items, `action_share` (icon `ic_share`,
title `action_share` = **"Share"**, `strings.xml:281`) and `action_report` (icon `ic_flag`, title
`report_content` = **"Report"**, `strings.xml:621`), both `showAsAction="never"`
(`menu_detail_kebab.xml:5-15`), inflated at `ChannelDetailFragment.kt:148` and
`PlaylistDetailFragment.kt:233`.

There is **no** share action on: the three tab lists, Featured, search results, favorites,
Categories, or the Videos-tab grid. There is also no `ACTION_SEND` *receiver* — the manifest has
no SEND intent filter (§3.1), so the app cannot be a share target.

### 1.2 URL construction — `ShareLinks`

`share/ShareLinks.kt`:

```
video(id)    → publicShareUrl("watch",    id, fallback "albunyaantube://video/{Uri.encode(id)}")     // :8-22
channel(id)  → publicShareUrl("channel",  id, fallback "albunyaantube://channel/{Uri.encode(id)}")   // :24-38
playlist(id) → publicShareUrl("playlist", id, fallback "albunyaantube://playlist/{Uri.encode(id)}")  // :40-54
```

`publicShareUrl` (`:56-73`): take `BuildConfig.SHARE_BASE_URL`, trim trailing `/`; if blank return
the custom-scheme fallback (`:61-63`); else build
`{base}/api/{type}/{id}` via `Uri.buildUpon().appendPath(...)` (`:66-72`).

- `SHARE_BASE_URL` defaults to **`https://app.fitrahtube.com`**, overridable via
  `local.properties share.base.url=` (`app/build.gradle.kts:75-76`; rationale comment `:71-74`:
  unfurlers cannot preview a custom scheme, so the https watch page wins).
- So the production share URL for a video is **`https://app.fitrahtube.com/api/watch/{id}`** —
  note the **`/api/` path variant**, not `/watch/{id}`. Both variants are inbound-routable (§3.1).
- The `title`/`imageUrl`/`description` parameters on all three functions are
  `@Suppress("UNUSED_PARAMETER")` — dead inputs kept for call-site symmetry (`:10-15,26-31,42-47`).

### 1.3 Share message + intent payload

All four entry points build the same 3-block plain-text message:

```
{title}\n\n{“…in FitrahTube:” line}\n{url}\n\n{promo}
```

| Piece | Video (`PlayerFragment.kt:3350-3358`) | Short (`ShortsPlayerFragment.kt:612-621`) | Channel (`ChannelDetailFragment.kt:429-437`) | Playlist (`PlaylistDetailFragment.kt:599-607`) |
|---|---|---|---|---|
| middle line key | `share_watch_in_app` → **"Watch in FitrahTube:"** (`strings.xml:282`) | same (`:616`) | `share_channel_in_app` → **"Open this channel in FitrahTube:"** (`:283`) | `share_playlist_in_app` → **"Open this playlist in FitrahTube:"** (`:284`) |
| promo key | `share_app_promo` → **"Get FitrahTube for ad-free Islamic content!"** (`strings.xml:285`) | same | same | same |

Title selection:

- **Video**: `currentItem.title` truncated to **160 chars** — `take(157) + "..."` when longer
  (`PlayerFragment.kt:3317-3323`). Description is deliberately *excluded* from the message ("often
  contains HTML tags", comment `:3348-3349`).
- **Short**: `item.title` non-blank else `item.id` (`ShortsPlayerFragment.kt:596`). No truncation.
- **Channel**: `header.title` → `channelName` arg → `channelId` (`ChannelDetailFragment.kt:413-416`).
  No truncation.
- **Playlist**: `header.title` → `playlistTitleArg` → `playlistId` (`PlaylistDetailFragment.kt:583-586`).
- Channel/playlist/short share early-return when the id is blank
  (`ChannelDetailFragment.kt:411`, `PlaylistDetailFragment.kt:581`, item-null guard
  `ShortsPlayerFragment.kt:595`).

Intent shape (player/channel/playlist): `ACTION_SEND`, `type = "text/plain"`,
`EXTRA_SUBJECT = title`, `EXTRA_TITLE = title`, `EXTRA_TEXT = message`, wrapped in
`Intent.createChooser` with chooser titles `share_video_chooser` / `share_channel_chooser` /
`share_playlist_chooser` → **"Share video"** / **"Share channel"** / **"Share playlist"**
(`strings.xml:286-288`; `PlayerFragment.kt:3360-3367`, `ChannelDetailFragment.kt:439-445`,
`PlaylistDetailFragment.kt:609-615`).

Shorts differ mechanically: `ShareCompat.IntentBuilder` with `setType("text/plain")`,
`setSubject(title)`, `setText(message)`, chooser title `shorts_share_cd` → **"Share"**
(`strings.xml:644`; `ShortsPlayerFragment.kt:622-627`) — no `EXTRA_TITLE`.

Localization: the `share_*` strings exist in `values-ar` and `values-nl`
(e.g. ar `strings.xml:595`, nl `strings.xml:532`).

### 1.4 `ShareMetadataPublisher` — pre-seeding the unfurl card

Before showing the chooser, every share entry point `launch`es
`ShareMetadataPublisher.publish(type, id, title, imageUrl, description)` and only builds the
chooser after it returns (`PlayerFragment.kt:3338-3346`, `ShortsPlayerFragment.kt:602-608` with
`description = null`, `ChannelDetailFragment.kt:426-427`, `PlaylistDetailFragment.kt:595-596`).
All four re-check `isAdded` after the await (`PlayerFragment.kt:3346` and peers).

`share/ShareMetadataPublisher.kt` behaviour:

- No-op when `SHARE_BASE_URL` is blank or id blank (`:39-40`).
- **No-op when `FirebaseAuth.currentUser == null`** (`:42-47`) — the backend requires a Firebase
  ID token for this endpoint (Plan F+ hardening, `SecurityConfig.java:72-79`:
  `POST /api/share-metadata/**` is `.authenticated()`). Anonymous users share fine; only the
  card-seeding POST is skipped. **Phase 4 dependency**: with no accounts on iOS Phase 2, this
  publisher can never fire.
- Hard timeout **900 ms** via `withTimeoutOrNull` (`:49,86`) — the chooser is delayed at most this long.
- POST `{base}/api/share-metadata/{type}/{id}` with JSON body of only the non-empty fields:
  `title` (trimmed), `image` (only if it starts with `https://`), `description` (trimmed)
  (`:52-66`); skip entirely if all empty (`:67`). Failures are log-warn only (`:74-80`).
- Uses the Hilt OkHttpClient injected at app start
  (`AlBunyaanApplication.kt:146-153`) so `X-Device-Id` + Bearer headers ride along — a bare client
  previously 400'd on the backend's header requirement (comment `ShareMetadataPublisher.kt:16-28`).

Backend contract (`WatchPageController.java`):

- `POST /api/share-metadata/{type}/{id}` (`:119-174`): 400 on bad type/id, missing body, or
  missing `X-Device-Id` (`:127-143`); per-device sliding-window rate limit **30/minute**
  (`:53-55,107-115`, atomic check `:144-157`) → 429; image URL kept only if https, ≤500 chars, and
  host ∈ {`i.ytimg.com`, `img.youtube.com`, `yt3.googleusercontent.com`, `yt3.ggpht.com`}
  (`:69-74`, validation `:185-219`); title truncated to 160, description to 300 (`:50-51,166-167`);
  cached 10 min, max 5000 entries (`:53,93-96`); 204 on success (`:174`).

### 1.5 What the shared URL serves (backend watch pages)

`WatchPageController` renders server-side OpenGraph HTML for
`GET /watch/{videoId}` + `/api/watch/{videoId}` (`:219`), `/channel/{channelId}` +
`/api/channel/{channelId}` (`:252`), `/playlist/{playlistId}` + `/api/playlist/{playlistId}`
(`:305`), all `permitAll` incl. HEAD (`SecurityConfig.java:63-71,80-85`). Each page:

- og/twitter meta tags (title/description/image/canonical, `:470-494`);
- a visible card with thumbnail/title/description and a CTA anchor whose href is the custom-scheme
  deep link — `albunyaantube://video/{id}` with CTA text **"Open in FitrahTube"** (`:369-370`,
  fallback `:390-391`), `albunyaantube://channel/{id}` (`:281,293`), `albunyaantube://playlist/{id}`
  (`:337,349`); anchor emitted at `:523-528`;
- a mobile-hop script: on Android/iPhone/iPad/iPod user agents, auto-redirect to the deep link
  after **50 ms** (`:536-542`). Desktop stays on the card.

So the full inbound chain today is: https link → OG card page → JS hop to `albunyaantube://…` →
manifest custom-scheme filter → app. Verified App Links (§3.1) can short-circuit the page entirely
on Android when installed.

---

## 2. Report content

### 2.1 Entry points — five, with a parent-context matrix

All five open the same `ContentReportBottomSheet`:

| # | Surface | Trigger | Call | target | parentType/parentId | contentSubType |
|---|---|---|---|---|---|---|
| 1 | Player, on-screen | `reportButton` under the video | `PlayerFragment.kt:477-483` | VIDEO, `currentItem.streamId` | **none** | **none** |
| 2 | Player, toolbar kebab | `player_menu.xml:13-16` `action_report` (title `player_action_report` = "Report", `strings.xml:47`; menu inflated `PlayerFragment.kt:1794`, dispatch `:1807-1810`) | `showReportSheet()` `:1824-1851` | VIDEO, `currentItem.streamId ?: args.videoId`; toast `player_video_not_ready` = "Video not ready yet" (`strings.xml:73`) if blank (`:1827-1830`) | PLAYLIST/`playlistId` arg if present, else CHANNEL/`channelId` arg, else none (`:1832-1838`) | `args.contentSubType` parsed leniently (`:1839-1841`) |
| 3 | Shorts player kebab | `shortsMenuBtn` → popup `menu_shorts_kebab.xml:5-15` (Quality + Report), `ShortsPlayerFragment.kt:344-346,765-786` | `showReportSheetForCurrentShort()` `:795-817` | VIDEO, current page id `?: args.initialShortId`; same not-ready toast (`:800-804`) | CHANNEL/`channelId` arg when present (`:805-808`) | forced `SHORT` (`:814`) |
| 4 | Channel detail kebab | `menu_detail_kebab.xml` `action_report`, `ChannelDetailFragment.kt:159-162` | `openReportSheet()` `:449-453` | CHANNEL, `channelId` | none | none |
| 5 | Playlist detail kebab | same menu, `PlaylistDetailFragment.kt:244-247` | `openReportSheet()` `:619-623` | PLAYLIST, `playlistId` | none | none |

The parent context is *planted* by the originating list when it navigates to the player:

- Channel Videos tab → player args `channelId` (`ChannelVideosTabFragment.kt:62-66`, comment `:60-61`).
- Channel Live tab → `channelId` + `contentSubType = "LIVESTREAM"` (`ChannelLiveTabFragment.kt:62-69`).
- Channel Shorts tab → shorts player args `channelId` + `contentSubType = "SHORT"`
  (`ChannelShortsTabFragment.kt:89-101`).
- Playlist detail → player args `playlistId` (`PlaylistDetailFragment.kt:745-753`).
- The Videos tab and Featured pass **no** `channelId`/`playlistId` (Phase 1 brief §4.10) — reports
  from there carry no parent context. That is the working state, not an error.

**Defect: entry points 1 and 2 disagree.** The on-screen Report button uses the 2-arg
`newInstance(VIDEO, videoId)` (`PlayerFragment.kt:480`) and silently drops the
`channelId`/`playlistId`/`contentSubType` that the kebab path (`:1824-1851`) forwards — same
screen, same video, different report payload depending on which affordance the user tapped.

**Defect (dead resource):** `res/menu/menu_report.xml` defines an `action_report` item but no
code inflates it (grep `R.menu.menu_report` — zero call sites).

### 2.2 Bottom sheet UI

`ContentReportBottomSheet` is a `BottomSheetDialogFragment` (Hilt, `ContentReportBottomSheet.kt:24-25`)
with layout `bottom_sheet_content_report.xml` inside a `ScrollView`:

- Title `report_title` → **"Report Content"**, subtitle `report_subtitle` → **"Select all reasons
  that apply"** (`bottom_sheet_content_report.xml:27-44`, `strings.xml:622-623`).
- **11 checkboxes**, ids `checkMusic … checkOther` (`:46-122`), labels `strings.xml:624-634`:
  Music / Instruments, Nudity / Immodesty, Bad Language, Flirting / Innuendo, Romance / Love
  Content, Awrah Exposure, Shirk / Polytheism, Bid'ah / Innovation, Violence / Gore,
  Misinformation, Other.
- "Other" toggles an outlined `TextInputLayout` (hint `report_other_hint` → **"Please describe…"**,
  `strings.xml:635`) with a 200 ms `AutoTransition` (`ContentReportBottomSheet.kt:68-78`); the
  field is `textMultiLine`, **`maxLength=500`**, 2–4 lines, `textAlignment=viewStart`, hidden by
  default; unchecking clears it (`bottom_sheet_content_report.xml:124-142`,
  `ContentReportBottomSheet.kt:76`).
- Button row, end-aligned: outlined **Cancel** (`cancel`) → `dismissAllowingStateLoss()`, filled
  **Submit** (`report_submit` → **"Submit Report"**, `strings.xml:636`)
  (`bottom_sheet_content_report.xml:145-166`, `ContentReportBottomSheet.kt:81-95`).
- Sheet arguments: `targetType` (default VIDEO), `targetId`, optional `parentType`/`parentId`/
  `contentSubType`, all passed as enum names and parsed leniently (`runCatching … getOrNull`,
  `:31-51,158-194`).

State handling (`:115-144`): `Idle` → submit enabled; `Loading` → submit disabled; `Success` →
snackbar `report_success` → **"Thank you — your report has been submitted"** (`strings.xml:637`)
then dismiss; `RateLimited` → snackbar `report_rate_limited` → **"You've sent too many reports
recently. Please try again later."** (`strings.xml:638`) then **dismiss**; `Error` → snackbar with
the message, sheet **stays open**. Snackbars are `LENGTH_LONG`, anchored to the sheet view or the
activity decor (`:146-151`).

**Defect (localization):** the `report_*` strings exist only in `values/` — zero hits in
`values-ar` and `values-nl` (grep across both files), so ar/nl users get English report UI.

### 2.3 ViewModel

`ReportViewModel.kt`: single `StateFlow<ReportUiState>` (`:22-23`), states
`Idle | Loading | Success | RateLimited | Error(message)` (`:61-67`).

- Empty reasons → `Error("Please select at least one reason.")` **without** calling the repository
  (`:34-37`). **Defect:** the message is hardcoded English; the localized key
  `report_select_reason` (`strings.xml:640`) exists but is unused. Same for the fallback
  `"Failed to submit report."` (`:49`) vs unused `report_error` (`strings.xml:639`).
- Otherwise `Loading`, call repository, fold: success → `Success`; `RateLimitException` →
  `RateLimited`; other failure → `Error(e.message ?: …)` (`:38-53`).
- `resetState()` exists (`:56-58`) but the sheet never calls it.

### 2.4 API payload

`ReportApi.kt:10-11`: `POST api/v1/reports` returning `Response<Void>`. Request body
(`ReportModels.kt:5-19`, Moshi):

```json
{
  "targetType": "VIDEO" | "CHANNEL" | "PLAYLIST",
  "targetId": "<YouTube id>",
  "reasons": ["MUSIC", …],                    // enum names, ReportModels.kt:26-29
  "otherDescription": "<text or null>",
  "parentType": "CHANNEL" | "PLAYLIST" | null,
  "parentId": "<parent YouTube id or null>",  // blank coerced to null, ReportRepository.kt:41
  "contentSubType": "SHORT" | "LIVESTREAM" | "POST" | null
}
```

`RetrofitReportRepository` (`ReportRepository.kt:24-55`): success ⇔ 2xx; **429 →
`RateLimitException`** (`:47`); other non-2xx → `IOException("HTTP {code}")` (`:48`);
`CancellationException` rethrown (`:50-51`); any other exception wrapped in `Result.failure`.

### 2.5 `X-Device-Id`

Attached to **every** request on the Hilt OkHttpClient by the first application interceptor
(`NetworkModule.kt:94-101`), value from `getOrCreateDeviceId` (`:82,115-122`): a
`UUID.randomUUID().toString()` generated once and persisted in SharedPreferences
`"device_prefs"` / key `"device_id"` — an **install-scoped** identity (uninstall = new id).
The same client also carries `FirebaseAuthInterceptor` (Bearer when signed in) and
`AccountStatusInterceptor`, in that order (`:102-104`). Timeouts: connect 15 s, read 20 s,
write 20 s (`:110-112`).

### 2.6 Backend contract and throttles

`ContentReportController.java`:

- `POST /api/v1/reports` (`:44`) is anonymous — `/api/v1/**` is `permitAll`
  (`SecurityConfig.java:61`) — but **400s** with `{"error": "Missing X-Device-Id header"}` when the
  header is absent/blank (`:48-57`; rationale comment: no IP fallback behind proxies).
- Validation (`SubmitReportRequest`, `:158-170`): `targetType` required; `targetId` non-blank,
  ≤128; `reasons` non-empty, **≤10**; `otherDescription` ≤500; `parentId` ≤128;
  `contentSubType` ≤16.
- Success → **201** with `{"id": …, "status": "PENDING"}` (`:63-64`). Rate limited → **429**
  `{"error": "Rate limit exceeded. Please try again later."}` (`:65-67`). Other failures → 500 (`:68-75`).

`ContentReportService.java`:

- Throttle: `RATE_LIMIT_MAX = 5` (`:30`), Caffeine cache keyed by device id,
  `expireAfterWrite(1, HOURS)` (`CacheConfig.java:169-175`) — so **max 5 reports per device per
  1-hour window measured from the bucket's creation** (increment does not extend the window;
  `checkRateLimit` `:336-344` increments, and decrements again when over limit).
- Parent context kept only when `parentType ∈ {CHANNEL, PLAYLIST}` and `parentId` non-blank;
  bogus combinations silently dropped (`:83-93`). `contentSubType` normalized to upper-case and
  whitelisted to SHORT/LIVESTREAM/POST (`:94-100`).
- Report stored `PENDING` with `deviceId` and `createdAt` (`:74-81,102`), plus a best-effort admin
  notification write (`:104-108`).

There is **no client-side throttle** — the 429 is the only brake the user ever sees.

### 2.7 Strings inventory (report)

`strings.xml:621-640` — `report_content` "Report", `report_title`, `report_subtitle`, 11
`report_reason_*`, `report_other_hint`, `report_submit`, `report_success`, `report_rate_limited`,
`report_error` (unused, §2.3), `report_select_reason` (unused, §2.3). Menu titles:
`player_action_report` "Report" (`strings.xml:47`), `player_action_quality` "Quality" (`:64`).

---

## 3. Inbound link handling

### 3.1 Manifest intent filters — the complete set

All on `MainActivity` (`launchMode="singleTop"`, `AndroidManifest.xml:42-47`):

| Filter | Lines | Matches |
|---|---|---|
| MAIN/LAUNCHER | `AndroidManifest.xml:48-51` | app icon |
| VIEW + BROWSABLE, `albunyaantube://channel` | `:52-59` | custom scheme, host `channel` |
| VIEW + BROWSABLE, `albunyaantube://playlist` | `:60-67` | host `playlist` |
| VIEW + BROWSABLE, `albunyaantube://video` | `:68-75` | host `video` |
| VIEW + BROWSABLE + `autoVerify` https `app.fitrahtube.com/watch/` | `:76-84` | App Link |
| … `/api/watch/` | `:85-93` | App Link |
| … `/channel/` | `:94-102` | App Link |
| … `/api/channel/` | `:103-111` | App Link |
| … `/playlist/` | `:112-120` | App Link |
| … `/api/playlist/` | `:121-129` | App Link |

Notes:

- No `SEND`/`SEND_MULTIPLE` filters — the app is never a share *target*.
- All six https filters carry `android:autoVerify="true"`, but **no `assetlinks.json` exists
  anywhere in this repo** (find across the tree) — verification hosting is external/unconfirmed.
- **Defect:** the nav graph declares `albunyaantube://shorts/{initialShortId}`
  (`main_tabs_nav.xml:303`) but the manifest has **no** `shorts` host filter, so that URI is
  unreachable from outside the app; nothing ever emits it either (Shorts share as watch links, §1.1).

### 3.2 URI → screen mapping (Navigation `<deepLink>` tags)

Routing is delegated to `NavController.handleDeepLink` against `res/navigation/main_tabs_nav.xml`
(comment `MainActivity.kt:264-266`). The last path segment binds to the destination's first arg:

| Destination | URIs | Args filled |
|---|---|---|
| `channelDetailFragment` (`main_tabs_nav.xml:109`) | `albunyaantube://channel/{channelId}`, `https://app.fitrahtube.com/channel/{channelId}`, `…/api/channel/{channelId}` (`:128-133`) | `channelId`; `channelName` nullable, `channelAvatarUrl` default null, `excluded` default false (`:112-127`) |
| `playlistDetailFragment` (`:137`) | `albunyaantube://playlist/{playlistId}`, `https://…/playlist/{playlistId}`, `…/api/playlist/{playlistId}` (`:163-168`) | `playlistId`; `playlistTitle`/`playlistCategory` nullable, `playlistCount` default 0, `downloadPolicy` default "ENABLED", `excluded` default false (`:140-162`) |
| `playerFragment` (`:245`) | `albunyaantube://video/{videoId}`, `https://…/watch/{videoId}`, `…/api/watch/{videoId}` (`:257-262`) | `videoId` (default ""); `playlistId` nullable default null (`:248-256`) |
| `shortsPlayerFragment` (`:290`) | `albunyaantube://shorts/{initialShortId}` (`:303`) — internal-only, see §3.1 defect | `initialShortId` + 6 optional args (`:292-302`) |

Consequence: a deep-linked screen opens with **only the id** — titles, avatars, counts arrive from
the network, exactly like the sparse-args paths already specced in the Phase 1 briefs.

### 3.3 `MainActivity` routing

Doc contract at `MainActivity.kt:35-38`: `albunyaantube://video/{id}` → PlayerFragment,
`…://channel/{id}` → ChannelDetailFragment, `…://playlist/{id}` → PlaylistDetailFragment.

- Cold start: `onCreate` runs `handleIntent(intent)` once, **only when
  `savedInstanceState == null`**, deferred to `binding.root.post` (`:69-76`).
- Warm (`singleTop`): `onNewIntent` → `setIntent` → `handleIntent` (`:172-176`).
- `handleIntent` dispatches `ACTION_VIEW → handleDeepLink(intent)` (plus the notification action
  `PlaybackService.ACTION_OPEN_PLAYER`) (`:178-185`).
- `handleDeepLink` (`:267-310`): if the outer nav is already on `mainShellFragment`, forward to the
  **nested** nav controller's `handleDeepLink`; if on splash/onboarding, wait for main shell then
  forward; otherwise wait for the nested controller and forward. All failures are caught and
  debug-logged only — a bad link is silently ignored (`:303-309`). Debug logs print scheme+host
  only, never the path/id (`:269-272`).

### 3.4 Splash behaviour on deep-link launch

`SplashFragment.isDeepLinkLaunch()` = activity intent is `ACTION_VIEW` with non-null data
(`SplashFragment.kt:330-333`). When true (`:161-166`): cancel the update-check, **skip the splash
animation entirely**, and route immediately after the onboarding/account-status reads — the
comment records the intent: "the user tapped a link expecting content, not a 'new version
available' dialog" (`:157-160`). Onboarding/sign-in routing itself is *not* bypassed — the deep
link waits for main shell (§3.3).

### 3.5 In-app link interception (outbound)

- **Video description**: NewPipe returns HTML; `PlayerDescriptions.render` parses it and **strips
  every `URLSpan` whose scheme is not http/https** (`PlayerDescriptions.kt:18,35-62`) —
  explicitly to stop a curated description from smuggling `albunyaantube://`, `intent://`, `tel:`
  etc. into a tappable link (comment `:26-33`). Stripped links stay visible as text. Surviving
  http(s) links are tappable via `LinkMovementMethod` (`PlayerFragment.kt:473-474`) and open in
  the default handler — an `https://app.fitrahtube.com/...` link in a description would therefore
  re-enter the app through App Links, not through any in-app interception.
- **Channel About tab** links open externally via `ACTION_VIEW` (`ChannelAboutTabFragment.kt:203`).
- There is **no** WebView URL interception and no in-app "open in app" banner anywhere; the only
  web→app hand-off is the backend watch page's 50 ms mobile redirect (§1.5).

---

## 4. Behavioural checklist for the iOS implementer

**Share**
1. Share exists on exactly four surfaces: player, shorts page, channel detail, playlist detail —
   nowhere else.
2. Share URL = `https://app.fitrahtube.com/api/{watch|channel|playlist}/{id}`; custom-scheme
   fallback only when the base URL is unconfigured.
3. Message = title + blank line + localized "watch/open in FitrahTube" line + URL + blank line +
   promo line; video titles truncated at 160 chars with `"..."`.
4. Metadata pre-seed POST (`/api/share-metadata/{type}/{id}`) fires only when signed in, capped at
   900 ms, never blocks or fails the share visibly. (Accounts are Phase 4 — decide whether Phase 2
   ships the call at all; see Q4.)

**Report**
5. One report sheet: 11 fixed reasons, multi-select, "Other" reveals a 500-char text field;
   Cancel/Submit.
6. Submit with zero reasons is rejected client-side without a network call.
7. POST `/api/v1/reports` with the §2.4 payload; the device-id header must be on the request.
8. 2xx → thank-you toast + dismiss; 429 → rate-limit toast + dismiss; anything else → error toast,
   sheet stays open with Submit re-enabled.
9. Parent context: player launched from a channel tab carries CHANNEL/channelId (+ LIVESTREAM for
   live); from playlist detail carries PLAYLIST/playlistId; shorts player carries CHANNEL/channelId
   + SHORT; channel/playlist self-reports and tab-list-launched videos carry none.
10. Device id: stable per-install UUID, generated once, persisted, sent on every API call.

**Links**
11. Accept `albunyaantube://video|channel|playlist/{id}` and
    `https://app.fitrahtube.com/{watch|channel|playlist}/{id}` **and** the `/api/…` variants of
    all three; route to player / channel detail / playlist detail with only the id populated.
12. A link arriving during launch defers until the main UI exists, then navigates; a link tapped
    while the app runs navigates immediately (no duplicate shell).
13. Deep-link launches skip the splash animation and the update prompt, but not onboarding/routing.
14. A malformed or unroutable link is dropped silently (no error UI on Android).
15. Non-http(s) schemes inside video descriptions must never be tappable.

---

## 5. Open questions

**Q1 — Universal Links hosting.** Android relies on `autoVerify` App Links for
`app.fitrahtube.com` (`AndroidManifest.xml:76-129`) but no `assetlinks.json` is in this repo, and
there is no `apple-app-site-association` anywhere either. Who owns/serves the association files
for `app.fitrahtube.com`, and can iOS get an AASA entry there? Without it, iOS only gets the
custom scheme + the watch page's 50 ms JS hop (§1.5).

**Q2 — Player's two report entry points disagree** (§2.1 defect): the on-screen button drops
parent context that the kebab forwards (`PlayerFragment.kt:477-483` vs `:1824-1851`). Should iOS
replicate both entry points, and if so, with unified context?

**Q3 — Shorts links.** `albunyaantube://shorts/{id}` exists only inside the nav graph
(`main_tabs_nav.xml:303`), is not externally reachable, and is never generated — shared Shorts
become `/api/watch/{id}` links that open the *regular* player on receipt. Should iOS route an
inbound watch link that happens to be a Short into its shorts player, or mirror Android (regular
player)?

**Q4 — Share-metadata publisher vs Phase 4.** `ShareMetadataPublisher` silently no-ops for
anonymous users (`ShareMetadataPublisher.kt:42-47`) and iOS Phase 2 has no accounts, so the call
can never succeed. Ship the client code dormant, or omit it until Phase 4?

**Q5 — Device-id mechanism on iOS.** Android uses a random UUID persisted in SharedPreferences —
reset on reinstall (`NetworkModule.kt:115-122`), which is also the report-throttle key. UserDefaults
UUID (reinstall resets, closest parity), Keychain UUID (survives reinstall, stricter throttling),
or `identifierForVendor`? Android leaves no guidance.

**Q6 — Hardcoded English in the report VM** (§2.3 defect): "Please select at least one reason." and
"Failed to submit report." bypass the existing localized keys (`ReportViewModel.kt:35,49` vs
`strings.xml:639-640`), and the whole `report_*` set is missing from `values-ar`/`values-nl`
(§2.2). Use the localized keys on iOS (and localize ar/nl), or mirror Android's current strings?

**Q7 — Share payload mapping to `UIActivityViewController`.** Android sends
`EXTRA_SUBJECT`/`EXTRA_TITLE` alongside `EXTRA_TEXT` (§1.3); iOS activity items have no direct
subject/title split (subject exists only via `activityViewController(_:subjectForActivityType:)`).
Single combined text item, or text + URL as separate activity items (which changes how Messages/
Mail render it)? Android's format doesn't decide this.

**Q8 — 429 dismisses the sheet.** RateLimited closes the sheet, discarding the user's selected
reasons and typed description (`ContentReportBottomSheet.kt:131-135`); Error keeps them. Intended
asymmetry to replicate?

**Q9 — Dead `menu_report.xml`** (§2.1) — confirm iOS ports nothing from it.

**Q10 — Watch-page CTA on iOS.** The backend hop script targets `albunyaantube://…` for iPhone/iPad
UAs (`WatchPageController.java:536-542`). Registering the same custom scheme on iOS makes the
existing pages work unmodified — confirm the iOS bundle claims `albunyaantube` (matching the
back-compat naming rule in CLAUDE.md) rather than a new scheme, which would require a backend change.
