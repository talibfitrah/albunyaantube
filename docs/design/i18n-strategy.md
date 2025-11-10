# Internationalization Strategy

> **Last Updated**: 2025-11-10
> **Status**: Active - en/ar/nl support across all platforms

This strategy covers localization for English (`en`), Modern Standard Arabic (`ar`), and Dutch (`nl`). It aligns with UI specs in [design-system.md](design-system.md) and API contracts in [../architecture/api-specification.yaml](../architecture/api-specification.yaml).

## Localization Model
- User-facing strings stored as ICU MessageFormat entries.
- Keys follow `feature.scope.message` naming (e.g., `home.section.channels`).
- Admin-managed content (titles, descriptions) stored as `{ locale: string }` maps in Firestore documents.
- Backend resolves `Accept-Language` header with fallback order: requested locale → `en`.
- Android uses string resources with `values/`, `values-ar/`, `values-nl/`; fonts include Arabic shaping support (Noto Naskh Arabic).
- Admin SPA uses `vue-i18n` with lazy-loaded locale chunks; fallback to English.
- Android filter labels replicate admin wording. Ensure `values-ar/strings.xml` uses localized phrasing for category/length/date/sort filters and that the "Clear filters" action conveys resetting state. Include QA checklist to confirm RTL chip row ordering.
- Error/empty copy must be localized; Arabic strings should provide culturally appropriate call-to-actions (e.g., "حاول مرة أخرى"). Footer freshness text should use locale-aware numerals and relative time phrasing.

## Pluralization & ICU
- Use ICU plurals for counts (`{count, plural, one {...} other {...}}`) respecting each locale's rules (Arabic has 6 plural forms).
- Date/time formatting via `Intl.DateTimeFormat` on web and `java.time` on backend/Android using the Gregorian calendar with locale-specific month names; decision recorded to defer Umm al-Qura adoption for launch while monitoring feedback.
- Dynamic inserts sanitized (no HTML injection).

## RTL Support
- Android enables `android:supportsRtl="true"`; ensure mirrored assets for directional icons (back, forward, download). Layouts tested with `ViewCompat.setLayoutDirection`.
- Admin SPA uses CSS logical properties (`margin-inline-start`).
- Player controls mirror order for RTL (Audio toggle near left). See [design-system.md](design-system.md#internationalization) for complete RTL specifications.

## Locale Detection & Switching
- Android: on first launch detect via `LocaleListCompat.getAdjustedDefault()`. Prompt user with onboarding to confirm; store preference in DataStore. Provide in-app locale switcher (Settings) with immediate restart of activity.
- Admin SPA: read `Accept-Language`; allow manual switcher in header; persist in localStorage via the `preferences` Pinia store (`albunyaan.admin.locale` key). The topbar switcher updates `vue-i18n` immediately and survives reloads.
- Backend: Accepts `X-Admin-Locale` override for admin operations when editing localized fields.

## Android Implementation
- **Resources**: Maintain base `values/strings.xml` with overlays in `values-ar/` and `values-nl/`, ensuring plural rules map to ICU message keys.
- **Configuration Changes**: Wrap activities with `AppCompatDelegate.setApplicationLocales` to avoid full process restarts and ensure background services pick up locale changes.
- **Testing Hooks**: Expose debug menu to force locale overrides and capture screenshots for QA (see [../status/TESTING_GUIDE.md](../status/TESTING_GUIDE.md#android-testing)).
- **Onboarding Copy**: Slides and help modal content sourced from localized string resources. Content team reviews Arabic phrasing to keep mission/halal commitment consistent. Ensure RTL slide visuals mirror arrow indicators and skip CTA alignment.

## Content Entry Workflow
1. Admin searches YouTube via `/admin/youtube/search` (see [../architecture/api-specification.yaml](../architecture/api-specification.yaml)).
2. On approval, admin enters localized metadata for en/ar/nl. UI enforces at least English and one additional translation.
3. Validation ensures strings ≤ defined lengths in Firestore document models.
4. Audit log captures locale changes via `AuditLogService`.

- **Truncation**: Validate key screens with longest translations; ensure 20% text expansion tolerance.
- **Registry filters**: Verify Category/Video Length/Published Date/Sort dropdown labels and options translate without clipping and mirror order in RTL contexts.
- **BiDi**: Test Arabic strings with embedded English numbers; ensure correct direction via Unicode control characters where needed.
- **Numerals**: Use locale digits for durations and counts; Arabic uses Eastern Arabic numerals (via `NumberFormat`).
- **Input**: Admin forms allow RTL input with text alignment toggles.
- **Regression**: Run pseudo-localization (accented English) before string freeze.
- **Accessibility sweep**: Execute axe/lighthouse runs in each locale, verify skip link announces target content, ensure dialogs read localized titles/descriptions, and confirm screen reader tables announce `aria-sort` and pagination updates.

## Localization Tooling
- Manage strings in a shared spreadsheet/export pipeline producing JSON for admin and XML for Android.
- Use `crowdin` or `lokalise` for translation management; include context screenshots (see UI spec).
- CI step verifies keys across platforms (no missing translations).
- Admin SPA bundles English (`en`), Arabic (`ar`), and Dutch (`nl`) dictionaries in `frontend/src/locales/messages.ts`; run locale switcher smoke test after updating bundles to confirm RTL layout and translation coverage, including the blended search workspace (filter chips, include/exclude buttons, bulk bar copy).

## Traceability
- Internationalization is a cross-cutting concern implemented across all phases
- Testing checklist available in [../status/TESTING_GUIDE.md](../status/TESTING_GUIDE.md)
- Architecture decisions documented in [../architecture/overview.md](../architecture/overview.md)

**Document Version**: 2.0
**Last Updated**: 2025-11-10
**Previous Update**: Initial creation

**Changelog**:
- **v2.0 (2025-11-10)**: Updated all file references to match simplified documentation structure (4 core categories), replaced broken links, added implementation status section
- **v1.0**: Initial i18n strategy documentation
