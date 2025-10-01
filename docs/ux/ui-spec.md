# UI Specification

This document codifies the canonical UI contract for Albunyaan Tube Android and Admin experiences. It references design tokens in [`design-tokens.json`](design-tokens.json) and aligns with the canonical mockups that live in the design team's Figma project (see [mockup asset location](mockups/README.md)). Exported PNGs follow the naming convention `1_splash_screen.png`–`10_player_screen.png` and should be downloaded from the Figma `Android` page when needed for local review or documentation. If a static snapshot is required inside this repository, store it under `docs/ux/mockups/` in a dated subfolder so the folder structure reflects the asset vintage.

## Global Principles
- Respect the halal curation promise: no comments, ads, or autoplay.
- Primary color: light `#275E4B` / dark `#35C491`; backgrounds rely on tokens `--color-bg` (`#F6F9F9` light, `#041712` dark) with text `--color-text-primary` and `--color-text-secondary` for contrast-compliant copy.
- Corner radii 16–20dp with subtle shadows (`card` elevation token).
- Minimum touch target: 48dp.
- Typography scale: H1 28sp, H2 22sp, Body 16sp, Caption 13sp.

## Layout Grid
- 8dp baseline grid for spacing.
- Content max width 640dp (see tokens).
- Bottom navigation height 72dp, icons 24dp with 8dp label gap.

### Android Navigation Blueprint (Phase 5)
- Single-activity structure: Splash → Onboarding carousel → Main shell with bottom navigation.
- Bottom nav labels (left-to-right order): Home, Channels, Playlists, Videos. Active tab uses success tint background + 12dp top indicator; inactive tabs display secondary text color.
- Preserve scroll position per tab; switching tabs restores previous vertical offset and list filters.
- Floating Action Buttons intentionally excluded for MVP to maintain single tap affordances.
- Deep links (phase 6+) map to `/channel/{id}`, `/playlist/{id}`, `/video/{id}` and open inside the main shell; navigation graph reserves actions for those routes.

## Component Library
| Component | Description | States | Accessibility |
| --- | --- | --- | --- |
| Category Chip | Rounded 20dp pill with primary outline. Filled when selected. | Default, Selected, Focused, Disabled. | `role="tab"` semantics, TalkBack label includes locale-aware category name. |
| List Card | Horizontal card with thumbnail/avatar left, text right. | Default, Loading Skeleton. | Provide content description for thumbnail (channel name). |
| Grid Card | 16dp radius image top, text bottom. | Default, Skeleton, Downloaded overlay. | Use aspect ratio 16:9; overlay icons with 12dp padding. |
| Hero Card | Playlist detail hero: 20dp radius, 24dp padding. | Default, Offline available (shows download badge). | Primary heading announced first, then description. |
| Tabs | Used on Channel detail (Videos/Live/Shorts/Playlists/Posts). | Active indicator 4dp, focus ring 2dp success color. | Support RTL mirroring. |
| Filter Row | Horizontal chip row with dropdown triggers (Category, Length, Date, Popular). | Default, Expanded (dropdown). | Dropdown uses modal bottom sheet with keyboard navigation support. |
| Download Button | Primary success button with download icon; 20dp radius. | Idle, Downloading (progress ring), Completed. | Announce download status; long press reveals context menu for remove. |
| Bookmark Toggle | Heart icon filled when bookmarked. | Off, On, Syncing. | Toggle described as “Bookmark video” / “Remove bookmark”. |
| Empty State | Centered icon (96dp), headline, body, action button. | Default. | Provide localized instructions. |
| Skeleton State | Pulsing light-gray placeholders (surfaceMuted). | Default. | Not announced to TalkBack (aria-hidden). |

## Screen Specifications (Android)
Each screen references the mockups. Layout measurements assume 360dp width baseline.

<a id="splash"></a>

### Splash (`1_splash_screen.png`)
- Logo icon 96dp centered vertically with 24dp spacing to tagline.
- Spinner 32dp diameter, success color accent.
- Display for ≥1.5s while initialization completes.

### Onboarding (`2_onboarding_screen_1.png`, `3_onboarding_screen_2.png`)
- Carousel height 320dp, indicator dots 8dp spaced 12dp.
- Help “?” button top-right (touch target 48dp).
- CTA button 56dp height, full width minus 24dp margin.
- Skip CTA text button bottom center.

### Home (`4_home_screen.png`)
- Header H1 “Albunyaan Tube” left-aligned; actions: Search (24dp icon), Profile (optional future) right-aligned.
- Category filter chip row horizontally scrollable; default “All”.
- Sections: Channels, Playlists, Videos each with H2 title and “See all” text button (Body style). Each section shows 3 cards horizontally scrollable with 16dp gutter.
- Up Next from backend is not present here (only in player).

#### Filter Row Interactions (Phase 6 planning)
- **Filters displayed**: Category (chips), Video Length (dropdown), Published Date (dropdown), Popular sort toggle. On phones, the row scrolls horizontally with snap alignment; on tablets, show all filters in one row.
- **Selection states**: Selected filter shows success-colored fill and icon; inactive filters in surface variant. Each filter triggers an announcement to TalkBack summarizing applied criteria.
- **Reset behavior**: A contextual “Clear filters” chip appears when any non-default filter is active. Tapping resets to default (All categories, Any length/date, Default sort) and triggers list refresh.
- **Badges**: When filters applied, show count badge on the filter chip (e.g., “Length • 2–20 min”). This mirrors the admin registry filter semantics.
- **Accessibility**: Filter dropdowns open modal bottom sheets; focus returns to triggering chip after dismissal. Ensure chips support keyboard navigation with `role="tab"` semantics.

#### Loading & Error States (Phase 6 planning)
- **Skeletons**: Each tab displays 6 shimmer cards matching card layout (list vs. grid). Skeletons hide from accessibility tree (`android:importantForAccessibility="no"`).
- **Empty**: If API returns zero results after filters apply, show `EmptyState` with localized message and CTA to clear filters.
  - When filters are active, surface a primary "Clear filters" chip/button inside the empty state for quick recovery.
- **Error**: Inline error card with retry button per tab. Message references filter context (e.g., “Unable to load videos. Check your connection or adjust filters.”). Toasts used only for transient errors.
- **Metrics banner**: Footer text shows page size (ex: “Showing 20 of 20 items”) and surfaces cache freshness (“Last refreshed 2m ago”).
- **Offline**: Provide “Go offline” state that shows cached content when available, else offline empty state with instructions to reconnect.

### Channels Tab (`5_channes_list_screen.png`)
- List items: avatar 56dp circle, left margin 24dp, 16dp spacing to text.
- Metadata: subscriber count (Body), category tags (chips) below.
- Sticky global category filter at top.

### Channel Detail (`6_channel_details_screen.png`)
- Hero: avatar 96dp, channel name H1, subscriber/video counts Body secondary.
- Subscribe button: success color, 20dp radius.
- Tabs across top with 16dp padding; maintain horizontal scroll for overflow.
- Content area uses grid or list based on tab (Videos grid, Live list, etc.).

### Playlists List (`7_playlist_list_screen.png`)
- Card height 132dp, image left 40%, text right with Body + Caption for counts.
- Download badge (success color) for already downloaded playlists.

### Playlist Detail (`8_playlist_details_screen.png`)
- Hero uses gradient overlay on thumbnail, includes owner info (avatar 32dp, name Body) and description (Body).
- Download playlist button anchored under hero; success color.
- Video list inherits list card style with per-item download toggles.

### Videos Tab (`9_all_videos_screen.png`)
- Filters row pinned under header: Category, Length, Date, Popular dropdowns.
- Grid: 2 columns, card width 164dp, height 220dp with 16dp gutter.
- Each card shows length badge top-right, published info Body/Caption.

### Player (`10_player_screen.png`)
- Video stage 16:9, controls overlay includes CC, HD, settings icons top-right.
- Below stage: title (Body bold), speaker (Caption), control row (Bookmark, Share, Download, Audio) spaced 16dp.
- Up Next list shows 3 items with thumbnails 96x54dp, inline play icon overlay.
- Background audio toggle persists state via shared preferences.

## Admin Console UX (Phase 3+)
- Navigation sidebar (left) with sections: Dashboard, Registry (Channels, Playlists, Videos), Moderation, Users, Audit.
- Data tables use sticky headers, column sorting, 16px rows, inline badges for statuses.
- Forms for categories and content editing use i18n input tabs (en/ar/nl). See [`docs/i18n/strategy.md`](../i18n/strategy.md#admin-ui).

### Moderation Queue (Phase 3)
- Status filter pill group defaults to `PENDING`, keyboard navigable with arrow keys, and announces the current selection via `aria-checked`.
- Approve/Reject controls stay inline with each pending row; once actioned, queue refreshes with latest cursor slice.
- Reject confirmation modal traps focus (Tab/Shift+Tab loop), focuses the textarea on open, and restores focus to the triggering button on close. Escape key cancels when submission is not in progress.
- Modal leverages tokenized surfaces (`--color-surface`, `--color-brand`) and keeps the underlying table inert/aria-hidden while open to aid screen readers.
- Approve and reject flows emit audit hooks (`admin:audit` custom event payload) containing proposal ID, ISO timestamp, and optional trimmed rejection reason for downstream logging.

### Search & Import Workspace
- Global search input centered atop the workspace with pill-shaped field, 12px inset shadow, and inline locale-aware placeholder (e.g., “Search Qur’an”). Search triggers on Enter or Search icon press; results update in place without page reload.
- Filter row lives to the right of the search bar and includes dropdowns for Category, Video Length, Published Date, and Sort order. Controls use rounded 12px corners, accessible labels, and persist selections across Channels/Playlists/Videos tabs.
- Results view mirrors YouTube’s blended layout: stacked sections for Channels, Playlists, and Videos rendered in a single scroll surface. Each section title (H2) remains sticky while its cards scroll.
- Cards reuse existing channel/playlist/video summary components with 120×120 thumbnails (channels) or 16:9 previews (playlists/videos) and show subscriber/video counts. Buttons on the right expose the Include/Exclude toggle described below.
- Filter dropdown labels and include/exclude toggles inherit localized copy from the i18n bundles; ensure Arabic renders RTL with mirrored bulk action bar alignment.

#### Include / Exclude Controls
- Every result row includes a tri-state toggle (`Include`, `Pending`, `Excluded`). Default is `Pending` for unseen IDs, `Include` for already allow-listed items, and `Excluded` when the parent has explicit exclusions.
- Bulk actions: a multi-select checkbox column enables selecting multiple items per section. When ≥1 selected, a sticky bulk action bar appears with `Include Selected`, `Exclude Selected`, and `Clear Selection` buttons. Bulk confirmations summarize how many items will be affected and warn if they belong to different parent channels/playlists.
- Bulk action bar labels must adapt to locale width—allow two-line wrapping at 320px and honour RTL alignment when Arabic is active.
- Tooltips surface localized explanations (e.g., “Excluded items remain hidden in Albunyaan Tube even if the channel is included”).

#### Channel Detail Drawer
- Selecting a channel card opens a right-aligned drawer (720px wide) replicating YouTube’s tabbed channel detail: tabs for Videos, Shorts, Live, Playlists, Posts. Drawer header shows avatar, subscriber counts, and Include/Exclude toggle for the channel itself.
- Each tab lists child items with inline toggles and bulk selection identical to the search results surface. Tabs lazy-load on first entry and retain scroll position while the drawer stays open.
- Drawer footer includes `Apply Changes` and `Discard` buttons; closing without applying prompts a confirmation when pending modifications exist.

#### Playlist Detail Drawer
- Playlist cards open a drawer mirroring YouTube’s playlist layout: hero image left, metadata on right, followed by ordered video list. Each video row exposes Include/Exclude toggle. Bulk select supports range selection (shift+click) and an “Exclude All” control for rapid curation.

#### Interaction States
- Loading states use skeleton cards; errors render inline banners at top of each section with retry action.
- Keyboard navigation: Tab focuses search field, arrow keys navigate results, space toggles selection; bulk bar is reachable via Shift+Tab.
- RTL: section headers, counts, and toggles mirror positions; bulk bar anchors bottom-right in RTL contexts.

## Accessibility & Localization
- Contrast ratios ≥4.5:1; ensure success color on white meets 3:1 for large text.
- Provide TalkBack labels for media controls (e.g., “Audio-only playback”).
- Add a skip-to-content link at the top of each admin view; target `main[tabindex="-1"]` region and restore focus after dialogs close.
- Keyboard order: header locale switcher → skip link → primary search/filter controls → tables → pagination; action buttons expose visible focus rings using the 2dp success color token.
- Dialogs (moderation reject, exclusions CRUD) trap focus, announce titles/descriptions via `aria-labelledby`/`aria-describedby`, and return focus to the invoking control on close.
- Tables expose `aria-sort`/`aria-live` messaging when filters change; empty states retain `role="status"` copy for screen readers.
- RTL: mirror layout, align text per locale, swap navigation order. Validate in [`docs/i18n/strategy.md`](../i18n/strategy.md#rtl-support).
- Numerals: use locale digits in captions/durations (Arabic Indic digits for ar).

## Interaction States
- Focus rings 2dp success color; maintain for keyboard and remote usage (future TV support).
- Loading uses skeletons; error states show inline message with retry button.
- No autoplay after video ends; show static end-screen with replay button.

## Asset Guidelines
- Channel avatars circular, 1px border `#FFFFFF` to separate from background.
- Placeholder images use geometric patterns tinted with primary color.
- Download icons follow Material Symbols style; adjust for RTL mirroring when directional.

## Traceability
- UI components map to data contracts in [`docs/api/openapi-draft.yaml`](../api/openapi-draft.yaml) (see `VideoSummary`, `ChannelSummary`, `PlaylistSummary`).
- Acceptance scenarios referencing UI live in [`docs/acceptance/criteria.md`](../acceptance/criteria.md#android-client).
- Design tokens consumption tracked in Android Phase 5 plan within [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md#design-system-integration).
