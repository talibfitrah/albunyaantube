# Admin Onboarding Runbook

This runbook walks new Albunyaan Tube administrators through the first-day setup and recurring onboarding rituals.
Follow the checklist end-to-end whenever someone joins the admin workspace team or rotates onto an admin on-call block.

## 1. Workstation & Repository Setup
- Clone `albunyaantube` and install prerequisites listed in [`README.md`](../../README.md).
- Install Java 21, Node 20, and Docker Desktop (or Docker Engine + Compose) with resource limits that allow backend, PostgreSQL,
  Redis, and the Vite dev server to run concurrently.
- Run `docker compose up postgres redis` to start shared services, then `./gradlew bootRun` to apply Flyway migrations and seed
  the default admin account.
- Install frontend dependencies with `npm install --prefix frontend` and start the admin SPA using `npm run dev -- --host` from
  the `frontend` directory when executing UI checks.

## 2. Account & Credential Rotation
- Sign in with the bootstrap credentials documented in the
  [`Admin Workspace Login Guide`](admin-login.md) to verify backend connectivity.
- Immediately change the password via the admin UI and capture the new secret in the secure password vault.
- For local development, update the `.env.local` overrides or backend environment variables if you need to persist changes between
  restarts. Otherwise leave `reset-password-on-startup=true` so teammates can rely on the shared defaults.
- Confirm that multifactor authentication toggles (if enabled in later phases) are documented and working before handoff.

## 3. Tooling & Communication Alignment
- Join the `#albunyaan-admin` Slack/Teams channel for deployment notices and moderation escalations.
- Bookmark the following shared documents:
  - [`docs/runbooks/moderator-search-walkthrough.md`](moderator-search-walkthrough.md)
  - [`docs/runbooks/backend-search-alignment.md`](backend-search-alignment.md)
  - [`docs/risk-register.md`](../risk-register.md)
- Add yourself to the admin on-call rotation calendar and verify paging integrations fire a test alert to your device.

## 4. Locale QA Smoke Checklist
Perform these checks in **English (en)**, **Arabic (ar)**, and **Dutch (nl)** using the locale switcher documented in the Phase 3
admin specs. Record findings in the localization QA tracker referenced in `docs/i18n/strategy.md`.

1. **Navigation Shell** – Confirm tab labels and icons match the canonical set from `frontend/src/constants/tabs.ts` and mirror
   correctly in RTL (Arabic).
2. **Registry Tables** – Validate column headers, filter chips, and empty states use the localized strings with no truncation.
3. **Registry Filters** – Exercise category, video length, publish window, and sort dropdowns across Channels/Playlists/Videos to confirm selections propagate between tabs and reflect translated labels.
4. **Forms & Drawers** – Submit include/exclude actions and ensure validation errors render in the active locale.
5. **Moderation Queue** – Verify status filter labels and action tooltips translate without breaking layout width.
6. **Dashboard/Home** – Confirm metric cards, timeframe chips, and stale-data warnings localize correctly and display numerals through the locale formatter described in `docs/i18n/strategy.md`.
7. Capture screenshots for any discrepancies and log them with reproduction steps before concluding onboarding.

## 5. Sign-off & Handoff
- Update this runbook with clarifications discovered during onboarding.
- Confirm the backlog ticket for your onboarding cohort references completion of AC-ADM-008 and AC-ADM-009, and loop in QA so Playwright coverage for AC-ADM-010/011 stays aligned with dashboard/filter updates.
- Notify the admin lead that onboarding is complete and schedule a follow-up shadowing session for the first moderation shift.

## Appendix A — Quick Commands
- Restart backend with clean state: `./gradlew bootRun --args='--spring.profiles.active=local'`
- Reset database (destructive): `docker compose down -v && docker compose up postgres redis`
- Run targeted frontend tests with timeout enforcement: `timeout 300s npm test -- --run TestName`
- Dashboard metrics regression: `timeout 300s npm test -- --run useDashboardMetrics`
