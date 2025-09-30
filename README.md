# Albunyaan Tube Design Repository

Welcome to the design workspace for Albunyaan Tube, an ad-free, admin-curated halal YouTube client. This repository captures all discovery outcomes, architectural decisions, experience specifications, and planning artifacts required before implementation. The materials are organized by discipline and phase to keep product, engineering, and design aligned.

## How to Navigate
- Start with [`docs/vision/vision.md`](docs/vision/vision.md) to understand goals, success metrics, and guardrails.
- Review the phased delivery plan in [`docs/roadmap/roadmap.md`](docs/roadmap/roadmap.md) which maps required deliverables to milestones.
- Experience specifications, including canonical layouts and design tokens, live in [`docs/ux/ui-spec.md`](docs/ux/ui-spec.md) and [`docs/ux/design-tokens.json`](docs/ux/design-tokens.json).
- Solution architecture, C4 diagrams, and integration sequences are in [`docs/architecture`](docs/architecture).
- API contracts are drafted in [`docs/api/openapi-draft.yaml`](docs/api/openapi-draft.yaml) with supporting JSON Schemas under [`docs/data/json-schemas`](docs/data/json-schemas).
- Security, internationalization, testing, and acceptance criteria are documented in their respective folders.
- Product backlog and risk register are maintained in [`docs/backlog/product-backlog.csv`](docs/backlog/product-backlog.csv) and [`docs/risk-register.md`](docs/risk-register.md).

## Traceability
Every document references related artifacts to ensure consistency:
- Requirements → APIs → Acceptance criteria are linked through the traceability matrix in [`docs/acceptance/criteria.md`](docs/acceptance/criteria.md).
- Security controls, data models, and internationalization strategies are cross-linked within architecture and API sections.

## Change Workflow
1. Update relevant design artifact(s).
2. Ensure cross-references remain valid.
3. Run documentation linters (TBD in future phases).
4. Submit for stakeholder review per phase exit criteria.

These documents will evolve through the pre-development phases until implementation is approved.

## Dark Mode Tokens
| Token | Light | Dark | Component Mapping |
| --- | --- | --- | --- |
| `--color-bg` | `#f5f8f6` | `#041712` | Workspace backgrounds, admin layout main area |
| `--color-surface` | `#ffffff` | `#0b231c` | Panels, tables, cards, login form |
| `--color-surface-alt` | `#e8f1ec` | `#102c24` | Filter controls, table headers, muted tags |
| `--color-text-primary` | `#132820` | `#eef6f2` | Headings, entity names |
| `--color-text-secondary` | `#4f665c` | `#9ec9b4` | Body copy, helper text, empty states |
| `--color-brand` | `#16835a` | `#35c491` | Primary actions, active chips, progress accents |
| `--color-accent` | `#2fa172` | `#7cdcb3` | Hover states for actions, tab highlights |
| `--color-danger` | `#dc2626` | `#f87171` | Error banners, moderation rejection badges |

The token definitions live in `frontend/src/assets/main.css` and are referenced by all Vue views and registry tables. Tokens are also mirrored in the design asset at [`docs/ux/design-tokens.json`](docs/ux/design-tokens.json) for Figma parity.

## Canonical Navigation Tabs
The end-user app shell uses a single source of truth for the Home/Channels/Playlists/Videos tabs defined in `frontend/src/constants/tabs.ts`. A reusable navigation component (`frontend/src/components/navigation/MainTabBar.vue`) consumes that config so every surface renders the same iconography and labels.
