# UI Specification

This document codifies the canonical UI contract for Albunyaan Tube Android and Admin experiences. It references design tokens in [`design-tokens.json`](design-tokens.json) and aligns with the canonical mockups that live in the design team's Figma project (see [mockup asset location](mockups/README.md)). Exported PNGs follow the naming convention `1_splash_screen.png`–`10_player_screen.png` and should be downloaded from the Figma `Android` page when needed for local review or documentation. If a static snapshot is required inside this repository, store it under `docs/ux/mockups/` in a dated subfolder so the folder structure reflects the asset vintage.

## Global Principles
- Respect the halal curation promise: no comments, ads, or autoplay.
- Primary color: `#275E4B`; background: `#F6F9F9`; text `#1F2937` / `#6B7280`.
- Corner radii 16–20dp with subtle shadows (`card` elevation token).
- Minimum touch target: 48dp.
- Typography scale: H1 28sp, H2 22sp, Body 16sp, Caption 13sp.

## Layout Grid
- 8dp baseline grid for spacing.
- Content max width 640dp (see tokens).
- Bottom navigation height 72dp, icons 24dp with 8dp label gap.

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

## Accessibility & Localization
- Contrast ratios ≥4.5:1; ensure success color on white meets 3:1 for large text.
- Provide TalkBack labels for media controls (e.g., “Audio-only playback”).
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
