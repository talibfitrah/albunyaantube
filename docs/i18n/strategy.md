# Internationalization Strategy

This strategy covers localization for English (`en`), Modern Standard Arabic (`ar`), and Dutch (`nl`). It aligns with UI specs in [`../ux/ui-spec.md`](../ux/ui-spec.md) and API contracts in [`../api/openapi-draft.yaml`](../api/openapi-draft.yaml).

## Localization Model
- User-facing strings stored as ICU MessageFormat entries.
- Keys follow `feature.scope.message` naming (e.g., `home.section.channels`).
- Admin-managed content (titles, descriptions) stored as `{ locale: string }` maps in the database (see [`../data/json-schemas`](../data/json-schemas)).
- Backend resolves `Accept-Language` header with fallback order: requested locale → `en`.
- Android uses string resources with `values/`, `values-ar/`, `values-nl/`; fonts include Arabic shaping support (Noto Naskh Arabic).
- Admin SPA uses `vue-i18n` with lazy-loaded locale chunks; fallback to English.

## Pluralization & ICU
- Use ICU plurals for counts (`{count, plural, one {...} other {...}}`) respecting each locale's rules (Arabic has 6 plural forms).
- Date/time formatting via `Intl.DateTimeFormat` on web and `java.time` on backend/Android using the Gregorian calendar with locale-specific month names; decision recorded to defer Umm al-Qura adoption for launch while monitoring feedback.
- Dynamic inserts sanitized (no HTML injection).

## RTL Support
- Android enables `android:supportsRtl="true"`; ensure mirrored assets for directional icons (back, forward, download). Layouts tested with `ViewCompat.setLayoutDirection`.
- Admin SPA uses CSS logical properties (`margin-inline-start`).
- Player controls mirror order for RTL (Audio toggle near left). See [`../ux/ui-spec.md`](../ux/ui-spec.md#accessibility--localization).

## Locale Detection & Switching
- Android: on first launch detect via `LocaleListCompat.getAdjustedDefault()`. Prompt user with onboarding to confirm; store preference in DataStore. Provide in-app locale switcher (Settings) with immediate restart of activity.
- Admin SPA: read `Accept-Language`; allow manual switcher in header; persist in localStorage.
- Backend: Accepts `X-Admin-Locale` override for admin operations when editing localized fields.

## Android Implementation
- **Resources**: Maintain base `values/strings.xml` with overlays in `values-ar/` and `values-nl/`, ensuring plural rules map to ICU message keys.
- **Configuration Changes**: Wrap activities with `AppCompatDelegate.setApplicationLocales` to avoid full process restarts and ensure background services pick up locale changes.
- **Testing Hooks**: Expose debug menu to force locale overrides and capture Paparazzi screenshots for QA (see [`../testing/test-strategy.md`](../testing/test-strategy.md#android-testing)).

## Content Entry Workflow
1. Admin searches YouTube via `/admin/search` (see [`../api/openapi-draft.yaml`](../api/openapi-draft.yaml#paths-/admin/search)).
2. On approval, admin enters localized metadata for en/ar/nl. UI enforces at least English and one additional translation.
3. Validation ensures strings ≤ defined lengths (see schemas).
4. Audit log captures locale changes.

## QA Guidelines
- **Truncation**: Validate key screens with longest translations; ensure 20% text expansion tolerance.
- **BiDi**: Test Arabic strings with embedded English numbers; ensure correct direction via Unicode control characters where needed.
- **Numerals**: Use locale digits for durations and counts; Arabic uses Eastern Arabic numerals (via `NumberFormat`).
- **Input**: Admin forms allow RTL input with text alignment toggles.
- **Regression**: Run pseudo-localization (accented English) before string freeze.

## Localization Tooling
- Manage strings in a shared spreadsheet/export pipeline producing JSON for admin and XML for Android.
- Use `crowdin` or `lokalise` for translation management; include context screenshots (see UI spec).
- CI step verifies keys across platforms (no missing translations).

## Traceability
- Requirements tracked in Phase 11 plan (see [`../roadmap/roadmap.md`](../roadmap/roadmap.md)).
- Acceptance criteria for localization in [`../acceptance/criteria.md`](../acceptance/criteria.md#internationalization).
