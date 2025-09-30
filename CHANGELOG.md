# Changelog

## 2024-05-31 — Dark theme tokens and category subcategories
- Introduced tokenized light/dark palette used by all admin screens and documented in the design system.
- Added canonical bottom tab configuration with shared icon set (`frontend/src/constants/tabs.ts`).
- Extended category model and API payloads to support optional localized subcategories; migration `V9__add_category_subcategories.sql` seeds existing rows with empty arrays.
- Updated tests covering tab rendering, category flows, and token coverage.
