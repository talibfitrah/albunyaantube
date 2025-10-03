# Albunyaan Tube - Complete Design Specification

> **Last Updated**: 2025-10-03
> **Status**: Admin UI design complete | Android UI in progress

---

## Overview

This document consolidates all UI/UX design specifications for both Admin Panel and Android App. It includes design tokens, component specifications, screen layouts, and interaction patterns.

---

## Table of Contents

1. [Global Design Principles](#global-design-principles)
2. [Design Tokens](#design-tokens)
3. [Admin Panel Design](#admin-panel-design)
4. [Android App Design](#android-app-design)
5. [Component Library](#component-library)
6. [Accessibility](#accessibility)
7. [Internationalization](#internationalization)

---

<a id="global-design-principles"></a>

## 1. Global Design Principles

### Brand Promise
- **Halal Curation**: No comments, ads, or autoplay
- **Family-Safe**: Vetted Islamic educational content only
- **Offline-First**: Support for areas with limited connectivity
- **Multilingual**: English, Arabic, Dutch (with RTL support)

### Visual Identity
- **Primary Color**: Teal/Green (`#2D9B8B` admin, `#275E4B` light / `#35C491` dark for Android)
- **Typography**: Clean, readable sans-serif
- **Spacing**: 8dp baseline grid
- **Corner Radius**: 8-20dp depending on context
- **Elevation**: Subtle shadows for depth

### Interaction Principles
- **Minimum Touch Target**: 48dp (Android) / 44px (Web)
- **Feedback**: Immediate visual response to all interactions
- **Progressive Disclosure**: Advanced features hidden behind collapsible sections
- **Error Prevention**: Confirmation dialogs for destructive actions
- **Loading States**: Skeleton screens for content, spinners for actions

---

<a id="design-tokens"></a>

## 2. Design Tokens

### Colors

#### Admin Panel
```json
{
  "colors": {
    "primary": {
      "main": "#2D9B8B",
      "hover": "#1E7A6D",
      "light": "#E6F7F4"
    },
    "status": {
      "success": "#10B981",
      "warning": "#F59E0B",
      "error": "#EF4444",
      "info": "#3B82F6"
    },
    "neutral": {
      "bg": "#F9FAFB",
      "cardBg": "#FFFFFF",
      "border": "#D1D5DB",
      "textPrimary": "#111827",
      "textSecondary": "#6B7280",
      "textMuted": "#9CA3AF"
    }
  }
}
```

#### Android App
```json
{
  "colors": {
    "light": {
      "primary": "#275E4B",
      "background": "#F6F9F9",
      "surface": "#FFFFFF",
      "surfaceMuted": "#E5E7EB",
      "textPrimary": "#111827",
      "textSecondary": "#6B7280",
      "success": "#35C491"
    },
    "dark": {
      "primary": "#35C491",
      "background": "#041712",
      "surface": "#1F2937",
      "surfaceMuted": "#374151",
      "textPrimary": "#F9FAFB",
      "textSecondary": "#D1D5DB",
      "success": "#10B981"
    }
  }
}
```

### Typography

#### Admin Panel (Web)
- **H1**: 32px, Bold, Line height 1.2
- **H2**: 24px, Semibold, Line height 1.3
- **H3**: 20px, Semibold, Line height 1.4
- **Body**: 14px, Regular, Line height 1.5
- **Caption**: 12px, Regular, Line height 1.4
- **Button**: 14px, Semibold

#### Android App
- **H1**: 28sp, Bold, Line height 1.2
- **H2**: 22sp, Semibold, Line height 1.3
- **Body**: 16sp, Regular, Line height 1.5
- **Caption**: 13sp, Regular, Line height 1.4

### Spacing Scale
```
4px/dp   → xs
8px/dp   → sm
12px/dp  → md
16px/dp  → lg
24px/dp  → xl
32px/dp  → 2xl
48px/dp  → 3xl
64px/dp  → 4xl
```

### Border Radius
- **Small**: 6px/dp (inputs, badges)
- **Medium**: 8px/dp (buttons, cards)
- **Large**: 12px/dp (modals, large cards)
- **XLarge**: 16-20px/dp (hero elements)
- **Pill**: 999px/dp (badges, tags)

### Shadows
- **Card**: `0 1px 3px rgba(0,0,0,0.1)`
- **Elevated**: `0 4px 6px rgba(0,0,0,0.1)`
- **Modal**: `0 10px 25px rgba(0,0,0,0.15)`

---

<a id="admin-panel-design"></a>

## 3. Admin Panel Design

### 3.1 Layout Structure

#### Sidebar Navigation
- **Width**: 260px fixed
- **Background**: White with border-right
- **Sections**:
  - Logo (64px height, top)
  - Navigation menu (scrollable middle)
  - User profile (fixed bottom)
- **Mobile**: Collapses to hamburger menu < 768px

#### Main Content Area
- **Header**: Breadcrumb + Search + Notifications + Profile (64px height)
- **Content**: 24px padding, max-width 1440px
- **Background**: Light gray (#F9FAFB)

### 3.2 Core Screens

#### Login Page
- Centered card (max-width 480px)
- Logo + "Albunyaan Admin" title
- Email and password inputs
- "Remember me" checkbox + "Forgot password?" link
- Full-width teal "Sign In" button
- Loading spinner during authentication

#### Dashboard
**Metrics Cards** (4-column grid):
- Total Content
- Pending Approvals
- Active Users
- Category Count

Each card: White background, large number (48px), icon, subtle shadow

**Recent Activity Table**:
- Columns: User | Action | Timestamp
- Last 10 activities from audit log
- Link to full Activity Log

#### Content Search (YouTube-style)
- Prominent search bar with icon
- Tabs: Channels | Playlists | Videos
- **Advanced Filters** (collapsible):
  - Category dropdown
  - Video Length (SHORT/MEDIUM/LONG)
  - Date range
  - Sort (RECENT/POPULAR)
- Results grid (3 columns desktop, 2 tablet, 1 mobile)
- **Result Card**:
  - 16:9 thumbnail
  - Title (truncated 2 lines)
  - Metadata (subscribers, video count)
  - "Add to Master List" button (teal)
- Grid/List toggle
- Pagination

#### Categories Management
**Hierarchical Tree View**:
```
⋮⋮ › 🛡️ Aqeedah      10 Items  2 Sub  ✏️ 🗑️
    ⋮⋮ 📖 Tawheed    5 Items        ✏️ 🗑️
    ⋮⋮ 🚫 Shirk      5 Items        ✏️ 🗑️
⋮⋮ › ⚖️ Fiqh         15 Items       ✏️ 🗑️
```

**Features**:
- Drag handles (⋮⋮) for reordering
- Expand/collapse arrows (›)
- Icon + Name + Counts + Actions
- "+ Add Category" button (top-right)
- Drag-and-drop updates `displayOrder`
- Edit modal with parent selector
- Delete confirmation (prevent if has children)

#### Users Management
**Table Layout**:
- Columns: Checkbox | Avatar + Name + Email | Role | Status | Last Login | Created | Actions
- **Avatar**: 40px circle
- **Role Badge**: Colored pill (Admin=purple, Moderator=blue)
- **Status Dot**: Green (active), Orange (inactive)
- **Actions Menu** (⋮):
  - Edit Role
  - Change Status
  - Reset Password
  - Delete User

**Features**:
- Search users by name/email
- "+ Add User" button creates Firebase + Firestore user
- Role change updates both Firebase claims and Firestore
- Delete removes from both systems

#### Content Management/Library
**Sidebar Filters** (left, 280px):
- Status checkboxes
- Type (Channel/Playlist/Video)
- Categories (hierarchical tree)
- Date Range picker
- Created By dropdown
- Has Exclusions toggle
- Apply Filters / Clear All buttons

**Main Area**:
- Search bar
- Active filters (removable chips)
- **Bulk Selection Bar** (when items selected):
  - "X items selected"
  - Change Status | Add to Category | Remove | Delete
  - Deselect All
- Table/Grid toggle
- **Table Columns**:
  - Checkbox | Thumbnail | Title | Type | Categories | Status | Exclusions | Created By | Date | Actions

#### Pending Approvals
**Metrics Bar** (4 cards):
- Total Pending | Channels | Playlists | Videos

**Tabs**: All | Channels | Playlists | Videos

**Filters**:
- Date Submitted (date picker)
- Submitter (dropdown)
- Category (dropdown)
- Sort By (Newest/Oldest)

**Content Cards Grid**:
- Thumbnail
- Title + metadata (subscriber count, video count)
- Categories (colored pills)
- Exclusions indicator ("Yes" in red)
- Approve (green) / Reject (red) buttons

#### Activity Log
**Filters**:
- All Users dropdown
- All Action Types dropdown (category_created, user_created, channel_approved, etc.)
- Date Range picker
- "Export to CSV" button

**Timeline View** (grouped by date):
- Date header
- **Action Entries**:
  - Color-coded icon:
    - ➕ Green: Create/Add
    - ✏️ Blue: Update/Edit
    - 🗑️ Red: Delete
    - 🔑 Orange: Auth actions
  - Actor name
  - Action description
  - Timestamp
  - "Show full metadata" expandable

#### Settings
**Tabs** (sidebar):
- Profile
- Notifications
- YouTube API
- System

**Profile Tab**:
- Avatar upload (120px circle, file upload)
- Display Name input
- Email (read-only, from Firebase)
- "Change Password" button (sends Firebase reset email)
- "Save Changes" button (bottom-right, teal)

**Notifications Tab**:
- Toggle switches:
  - Email notifications
  - Browser notifications
  - New content alerts
  - Approval reminders

**YouTube API Tab**:
- API Key input (password field)
- "Test Connection" button
- Usage statistics

**System Tab**:
- Language selector (en/ar/nl)
- Timezone selector
- Theme toggle (Light/Dark - future)

### 3.3 Components & Modals

#### Channel Details Modal
**Header**: Channel badge | Channel Name | Close (X)

**Tabs**:
1. **Overview**:
   - Large thumbnail, Status badge
   - Metadata (Videos, Subscribers, Imported At, Last Sync)
   - Actions (Edit | Delete)
   - Description

2. **Categories**:
   - Assigned categories (chips)
   - "+ Assign Categories" button

3. **Exclusions**:
   - Videos excluded list
   - Playlists excluded
   - Shorts excluded
   - "+ Add Exclusion"

4. **Metadata**:
   - YouTube ID
   - Custom tags
   - Notes (textarea)
   - Import source

5. **History**:
   - Audit log filtered for this channel
   - Timeline of changes

#### Category Assignment Modal
- Content preview (thumbnail + title + type + description)
- **Hierarchical Category Tree** (checkboxes):
  ```
  ☐ Quran
    ☐ Tafsir
  ☐ Hadith
  ☐ Islamic History
  ```
- "Approve immediately" checkbox
- Validation: "Please select at least one category to save"
- Cancel | Save buttons

#### Notifications Panel
- Dropdown from bell icon (max-width 400px)
- Header: "Notifications" + "Mark all as read"
- **Notification Items**:
  - Bell icon
  - Message text
  - Timestamp ("1 hour ago")
  - Unread indicator (blue dot)
- "View all" link

---

<a id="android-app-design"></a>

## 4. Android App Design

### 4.1 Navigation Structure

**Single-Activity Architecture**:
- Splash Screen (1.5s minimum)
- Onboarding Carousel (first launch only)
- Main Shell with Bottom Navigation

**Bottom Navigation** (72dp height):
- **Tabs** (left-to-right):
  1. Home (house icon)
  2. Channels (user-group icon)
  3. Playlists (list icon)
  4. Videos (play icon)
- Active tab: Success tint background + 12dp top indicator
- Inactive: Secondary text color
- Preserve scroll position per tab

### 4.2 Core Screens

#### Splash Screen
- Logo icon (96dp) centered
- Tagline below (24dp spacing)
- Loading spinner (32dp, success color)
- Display ≥1.5s while loading

#### Onboarding Carousel
- Carousel height: 320dp
- Indicator dots: 8dp, spaced 12dp
- Help "?" button top-right (48dp touch target)
- CTA button: 56dp height, full-width minus 24dp margin
- "Skip" text button bottom center

#### Home Screen
- **Header**:
  - "Albunyaan Tube" (H1, left-aligned)
  - Search icon (24dp, right)
  - Profile icon (optional, future)
- **Category Filter**: Horizontal scrollable chips
  - Default: "All"
  - Chips: 20dp radius, primary outline
  - Selected: Filled background
- **Content Sections**:
  - Channels (H2 + "See all" link)
  - Playlists (H2 + "See all")
  - Videos (H2 + "See all")
  - Each: 3 cards horizontally scrollable, 16dp gutter

#### Channels List
- **List Items**:
  - Avatar: 56dp circle, left margin 24dp
  - 16dp spacing to text
  - Channel name (Body, bold)
  - Subscriber count (Body, secondary)
  - Category tags (chips) below
- Sticky category filter at top
- Pull-to-refresh

#### Channel Detail
- **Hero Section**:
  - Avatar: 96dp
  - Channel name (H1)
  - Subscriber/video counts (Body, secondary)
  - Subscribe button (success color, 20dp radius)
- **Tabs** (horizontal scroll if overflow):
  - Videos | Live | Shorts | Playlists | Posts
  - 16dp padding, 4dp active indicator
- **Content Area**: Grid or list based on tab
- **Exclusion Banner**: Red banner if policy restricts channel

#### Playlists List
- **Card Layout** (132dp height):
  - Thumbnail (40% width, left)
  - Text (60% width, right)
  - Title (Body, bold)
  - Video count (Caption)
  - Download badge (success color) if downloaded

#### Playlist Detail
- **Hero Section**:
  - Thumbnail with gradient overlay (20dp radius)
  - Owner info (avatar 32dp + name)
  - Description (Body)
  - 24dp padding
- Download Playlist button (success color, anchored under hero)
- **Video List**: List cards with per-item download toggles
- Exclusion banner if restricted

#### Videos List
- **Filters Row** (pinned under header):
  - Category | Length | Date | Popular dropdowns
  - Horizontal scroll on phone
  - Modal bottom sheets for selections
- **Grid Layout**: 2 columns (phone), 3-4 (tablet)
  - 16:9 thumbnail
  - Title (2 lines max)
  - Channel name (Caption)
  - Duration overlay (bottom-right)
  - Download indicator (if downloaded)

#### Video Player
- **Player**: Embedded YouTube player (or NewPipe Extractor)
- **Below Player**:
  - Title (H2)
  - Channel info (avatar + name + subscribe)
  - View count + date
  - Bookmark toggle (heart icon)
  - Download button
- **Tabs**:
  - Description
  - Up Next (auto-generated playlist)
- **Comments**: Hidden (halal promise)

### 4.3 Android-Specific Features

#### Offline Support
- Download videos for offline viewing
- Download queue management
- Storage location selector
- "Go offline" mode (cached content only)

#### Loading & Error States
- **Skeletons**: Shimmer placeholders (6 cards)
  - `android:importantForAccessibility="no"`
- **Empty State**:
  - Centered icon (96dp)
  - Headline + body text
  - "Clear filters" or "Refresh" CTA
- **Error State**:
  - Inline error card
  - Retry button
  - Connection-aware messaging
- **Metrics Footer**: "Showing X of Y items" + "Last refreshed 2m ago"

#### Filters & Search
- **Category Chips**: Horizontally scrollable, multi-select
- **Dropdowns**: Modal bottom sheets
  - Video Length: < 4min | 4-20min | > 20min
  - Date: Last 24h | Last 7d | Last 30d | Anytime
  - Sort: Recent | Popular
- **Active Filters**: Chips with count badges
- **Reset**: "Clear filters" button when filters active
- **TalkBack**: Announce filter changes

---

<a id="component-library"></a>

## 5. Component Library

### 5.1 Admin Panel Components

#### Buttons
**Variants**:
- Primary: Teal background, white text
- Secondary: White background, teal border + text
- Danger: Red background, white text
- Text: No background, teal text

**Specs**:
- Height: 40px (medium), 32px (small), 48px (large)
- Padding: 16px horizontal
- Border radius: 8px
- Font: 14px, semibold
- Hover: Darken 10%
- Active: Scale 0.98
- Disabled: 50% opacity, no pointer events

#### Form Inputs
**Text Input**:
- Border: 1px solid #D1D5DB
- Focus: 2px teal ring
- Border radius: 6px
- Padding: 12px 16px
- Font: 14px
- Placeholder: #9CA3AF

**Select/Dropdown**:
- Same as text input
- Chevron icon (right, 16px)
- Options: White bg, hover #F3F4F6

**Checkbox/Radio**:
- Size: 20px
- Border: 2px solid #D1D5DB
- Checked: Teal background, white checkmark
- Focus: 2px teal ring offset 2px

**Toggle/Switch**:
- Width: 44px, Height: 24px
- Border radius: 12px (pill)
- Handle: 20px circle
- Off: Gray background
- On: Teal background

#### Cards
- Background: White
- Border radius: 12px
- Shadow: `0 1px 3px rgba(0,0,0,0.1)`
- Padding: 24px
- Hover: Shadow `0 4px 6px rgba(0,0,0,0.1)`, scale 1.01

#### Tables
- Header: #F3F4F6 background, bold text, 12px padding
- Rows: Alternate #FAFAFA and white
- Hover: #E6F7F4 (light teal)
- Cell padding: 12px 16px
- Border: 1px solid #E5E7EB (between rows)

#### Modals/Dialogs
- Overlay: `rgba(0,0,0,0.5)`
- Container: White, 12px radius, max-width 600px (varies)
- Shadow: `0 10px 25px rgba(0,0,0,0.15)`
- Padding: 24px
- Header: H3 + close button (X)
- Footer: Actions right-aligned, 16px gap

#### Badges/Pills
- Border radius: 999px (pill)
- Padding: 4px 12px
- Font: 12px, semibold
- Colors:
  - Published/Active: Green background, dark green text
  - Draft/Pending: Yellow background, dark yellow text
  - Archived/Inactive: Red background, dark red text

#### Toast/Alert
- Position: Top-right, 16px margin
- Width: max 400px
- Border radius: 8px
- Padding: 16px
- Icon (left, 20px) + Message + Close (X)
- Auto-dismiss: 5s (success), 7s (info), manual (error)
- Variants: Success (green), Info (blue), Warning (yellow), Error (red)

### 5.2 Android Components

#### Category Chip
- Height: 32dp
- Border radius: 20dp (pill)
- Border: 2px primary color
- Padding: 8dp 16dp
- Font: 14sp, medium
- States: Default (outline), Selected (filled), Focused, Disabled
- TalkBack: "Category [name], [selected/not selected]"

#### List Card
- Height: 80dp
- Layout: Horizontal
- Thumbnail/Avatar: Left (56dp), 16dp margin
- Text: Right, 12dp spacing from thumbnail
- Title: Body (bold)
- Subtitle: Caption (secondary color)
- Chevron: Right (24dp, optional)

#### Grid Card
- Aspect ratio: 16:9
- Border radius: 16dp
- Image: Top
- Text: Bottom, 12dp padding
- Title: Body (bold), 2 lines max
- Metadata: Caption (views, duration)
- Overlay: Download icon (12dp padding, bottom-right) if downloaded

#### Hero Card (Playlist Detail)
- Border radius: 20dp
- Padding: 24dp
- Gradient overlay on thumbnail
- Avatar: 32dp circle
- Title: H2
- Description: Body, 3 lines max
- Download badge: Success color pill

#### Tabs
- Height: 48dp
- Font: 16sp, medium
- Active: Primary color, 4dp bottom indicator
- Inactive: Secondary text color
- Focus: 2px success ring
- RTL: Mirrored

#### Filter Row
- Height: 56dp
- Horizontal scroll
- Chips: 32dp height, 12dp margin
- Dropdown trigger: Chevron icon
- Bottom sheet: Modal with list options

#### Download Button
- Primary success button
- Icon: Download (20dp)
- States:
  - Idle: "Download"
  - Downloading: Progress ring (indeterminate or %)
  - Completed: Checkmark icon
- Long press: Context menu (Remove download)

#### Bookmark Toggle
- Heart icon: 24dp
- States: Unfilled (outline), Filled (solid)
- Animate: Scale + fill on toggle
- TalkBack: "Bookmark video" / "Remove bookmark"

#### Empty State
- Icon: 96dp, center
- Headline: H2, 16dp below icon
- Body: Body text, 8dp below headline
- Action button: 24dp below body (if applicable)
- Localized messages

#### Skeleton Loader
- Pulsing animation (1.5s duration)
- Color: Surface muted (#E5E7EB light, #374151 dark)
- Shapes mimic actual content (rectangles for text, circles for avatars)
- `android:importantForAccessibility="no"`

---

<a id="accessibility"></a>

## 6. Accessibility

### 6.1 WCAG 2.1 AA Compliance

#### Color Contrast
- **Normal Text**: Minimum 4.5:1
- **Large Text** (18px+ or 14px+ bold): Minimum 3:1
- **UI Components**: Minimum 3:1 (borders, icons)
- **Test Tools**: Use Lighthouse, axe DevTools

#### Keyboard Navigation
- All interactive elements focusable
- Focus indicator: 2px teal ring, 2px offset
- Tab order: Logical (left-to-right, top-to-bottom)
- Escape key: Close modals/dropdowns
- Enter/Space: Activate buttons/toggles
- Arrow keys: Navigate lists/dropdowns

#### Screen Readers
- **Semantic HTML**: `<nav>`, `<main>`, `<article>`, `<aside>`
- **ARIA Labels**: All icon-only buttons
- **ARIA Live Regions**: Dynamic content updates
- **Alt Text**: All images (decorative: `alt=""`)
- **Form Labels**: Associated with inputs (`for`/`id`)
- **Error Messages**: `aria-describedby` linking to error text

#### Focus Management
- **Skip to Content** link (hidden until focused)
- Modal open: Focus first focusable element
- Modal close: Return focus to trigger
- Dropdown open: Focus first option
- Loading complete: Announce to screen reader

### 6.2 Android Accessibility

#### TalkBack
- Content descriptions for all images/icons
- State announcements ("Selected", "Expanded", "Downloading")
- Hint text for complex interactions
- Group related elements (`android:screenReaderFocusable`)

#### Touch Targets
- Minimum: 48dp x 48dp
- Recommended: 56dp x 56dp
- Spacing: 8dp between targets

#### Text Scaling
- Support system font size settings (up to 200%)
- Use `sp` for text sizes (not `dp`)
- Test layouts at various scales

#### Gestures
- Swipe: Navigate tabs, dismiss items
- Double-tap: Activate (TalkBack)
- Two-finger scroll: Scroll views (TalkBack)

---

<a id="internationalization"></a>

## 7. Internationalization (i18n)

### 7.1 Supported Languages
- **English** (en) - Default
- **Arabic** (ar) - RTL
- **Dutch** (nl)

### 7.2 RTL Support (Arabic)

#### Layout
- Flip horizontal layouts (menus, navigation, etc.)
- Use CSS logical properties:
  - `margin-inline-start` instead of `margin-left`
  - `padding-inline-end` instead of `padding-right`
  - `text-align: start` instead of `text-align: left`
- Flip icons/chevrons (point left instead of right)
- Do NOT flip: Numbers, Latin text, logos, media controls

#### Android RTL
- Set `android:supportsRtl="true"` in manifest
- Use `start`/`end` instead of `left`/`right` in layouts
- Test with "Force RTL layout direction" in Developer Options

### 7.3 Translation Keys

#### Admin Panel (Vue I18n)
- Structure: `{screen}.{component}.{key}`
- Example: `dashboard.metrics.totalContent`
- Files: `frontend/src/locales/{en,ar,nl}.json`

#### Android (strings.xml)
- Files: `res/values/strings.xml` (en), `res/values-ar/strings.xml` (ar), `res/values-nl/strings.xml` (nl)
- Use placeholders: `<string name="video_count">%d videos</string>`

### 7.4 Date/Number Formatting

#### Dates
- English: MM/DD/YYYY
- Arabic: DD/MM/YYYY (Hijri calendar optional)
- Dutch: DD-MM-YYYY

#### Numbers
- English: 1,234.56
- Arabic: ١٬٢٣٤٫٥٦ (Eastern Arabic numerals optional)
- Dutch: 1.234,56

#### Relative Time
- English: "2 hours ago"
- Arabic: "منذ ساعتين"
- Dutch: "2 uur geleden"

---

## 8. Design Checklist

### For Every Screen
- [ ] Matches design mockups pixel-perfect
- [ ] Responsive (mobile/tablet/desktop tested)
- [ ] Loading states implemented
- [ ] Error states implemented
- [ ] Empty states implemented
- [ ] Keyboard navigation works
- [ ] Screen reader accessible
- [ ] Color contrast passes WCAG AA
- [ ] Touch targets ≥ 48dp/44px
- [ ] Focus indicators visible
- [ ] RTL tested (for Arabic)
- [ ] Animations smooth (60fps)
- [ ] Text scales with system settings
- [ ] Forms have validation
- [ ] Destructive actions have confirmation

---

## 9. Animation & Motion

### Principles
- **Duration**: 200-300ms (UI transitions), 150ms (micro-interactions)
- **Easing**: `ease-in-out` (default), `ease-out` (entering), `ease-in` (exiting)
- **Respect Motion Preferences**: `prefers-reduced-motion: reduce`

### Common Animations
- **Page Transitions**: Fade in (150ms)
- **Modal Open**: Scale from 0.95 to 1 + fade (200ms)
- **Modal Close**: Scale to 0.95 + fade (150ms)
- **Dropdown**: Height expand (200ms ease-out)
- **Toast**: Slide in from top-right (300ms)
- **Button Hover**: Scale 1.02 (100ms)
- **Card Hover**: Elevation increase (200ms)
- **Loading Spinner**: Continuous rotate (1s linear)
- **Skeleton**: Pulse opacity (1.5s ease-in-out infinite)

---

## 10. Performance Targets

### Admin Panel (Web)
- **Initial Load**: < 2s (on 3G)
- **Time to Interactive**: < 3s
- **Route Change**: < 500ms
- **API Response Display**: < 300ms (show loading if longer)
- **Bundle Size**: < 500KB gzipped
- **Lighthouse Score**: > 90 (Performance, Accessibility, Best Practices)

### Android App
- **Cold Start**: < 2s
- **Warm Start**: < 1s
- **Frame Rate**: 60fps (scrolling, animations)
- **APK Size**: < 20MB
- **Memory Usage**: < 100MB (idle), < 200MB (active)

---

## 11. References

### Design Tools
- **Mockups**: Figma (stored in `docs/ux/mockups/`)
- **Icons**: Heroicons (web), Material Icons (Android)
- **Fonts**: System fonts (San Francisco iOS, Roboto Android, Inter/System web)

### Documentation
- **Admin Mockups**: 12 screens provided 2025-10-03
- **Design Tokens**: `docs/ux/design-tokens.json`
- **Component Specs**: This document

---

**Document Version**: 2.0
**Last Updated**: 2025-10-03
**Next Review**: End of Sprint 1 (Phase 3)
