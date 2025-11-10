# Design Mockups

The Albunyaan Tube design mockups are maintained in the shared Figma project **"Albunyaan Tube UX"** within the design team's workspace.

## Coverage

**Android App** (Material Design 3, RTL support):
- Onboarding flow (3-page carousel)
- Main navigation (Home, Channels, Playlists, Videos, More)
- Video player screen with controls
- Category selection
- Search interface
- Downloads management
- Settings

**Admin Dashboard** (Vue 3, responsive):
- Content search interface
- Pending approvals queue
- Content library with filters
- Category management
- Dashboard metrics
- User management

## Access

Designers export the latest PNG sequences (e.g., `1_splash_screen.png`–`10_player_screen.png`) from the respective pages when needed for handoff or documentation. Engineering and product stakeholders can request viewer access through the design lead.

To keep the repository lightweight, rendered PNGs are **not committed here**. When snapshots are required for offline reference, place them in dated subfolders (e.g., `2025-11-android/`, `2025-11-admin/`).

## Related Documentation

- **Design System**: See [../design-system.md](../design-system.md) for UI specifications, component library, and design tokens
- **Design Tokens (CSS)**: See `../../../frontend/src/assets/main.css` for CSS custom properties and color tokens
- **i18n Strategy**: See [../i18n-strategy.md](../i18n-strategy.md) for RTL support and localization guidelines
- **PRD**: See [../../PRD.md](../../PRD.md) for complete feature specifications and user stories
