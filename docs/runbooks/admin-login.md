# Admin Workspace Login Guide

This guide covers how to sign into the Albunyaan Tube admin workspace when running the stack locally. Pair it with the
[`Admin Onboarding Runbook`](admin-onboarding.md) for end-to-end environment preparation and locale QA expectations.

## Prerequisites

1. Start the backend so Flyway can apply the seed data:
   ```bash
   ./gradlew bootRun
   ```
2. Ensure PostgreSQL and Redis are running (the included `docker-compose.yml` spins them up with `docker compose up`).

## Default Admin Credentials

The backend provisions and repairs a starter administrator on each boot using the settings under
`app.security.initial-admin` in `backend/src/main/resources/application.yml`. Use the following credentials on the `/login`
screen of the admin SPA:

- **Email:** `admin@albunyaan.tube`
- **Password:** `ChangeMe!123`

After signing in you should immediately update the password in a secure environment. Capture the new secret in the shared
password vault per the onboarding runbook and notify the rotation lead when credentials change. To change the default locally,
override the properties (for example with environment variables `APP_SECURITY_INITIAL_ADMIN_PASSWORD` and friends). By default
the backend resets the password and reactivates the account on each startup so the credentials above always work even if the
database previously contained an out-of-sync admin record. Set `app.security.initial-admin.reset-password-on-startup` to `false`
if you want to keep manual password changes between restarts.

## Troubleshooting

If you still encounter authentication errors:

- Confirm the backend logs show Flyway migrations completing successfully at startup.
- Verify the admin user exists:
  ```sql
  SELECT email, status FROM app_user WHERE email = 'admin@albunyaan.tube';
  ```
- If the query above shows the admin user but you still receive `401` responses, ensure the backend has restarted since the
  last database change so the bootstrapper can reapply the configured password.
- Remove any cached session data from `localStorage` (key: `albunyaan-admin-session`) and try again.
- Make sure you are accessing the frontend over `http://localhost:5173` or `http://127.0.0.1:5173`, which are authorized by the CORS configuration.

For other origins, add them under `app.security.cors.allowed-origins` in `backend/src/main/resources/application.yml` and restart the backend.

## Post-Login Locale QA Smoke Test
Immediately after verifying access, switch between English, Arabic, and Dutch locales using the admin header toggle. Confirm
that navigation labels, action buttons, and validation messages update accordingly. Exercise the registry search controls
(category, length, publish window, sort) and the dashboard timeframe chips to make sure each selection localizes and persists
across tabs. If any locale fails to load, strings appear untranslated, or stale-data warnings remain in English, record the
issue in the localization QA tracker referenced in `docs/i18n/strategy.md` before continuing onboarding tasks.
