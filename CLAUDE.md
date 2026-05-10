# FitrahTube — Claude Developer Guide

Ad-free, admin-curated halal YouTube client. Native Android app + Vue 3 admin dashboard + Spring Boot backend (Firestore).

> **Branding**: User-facing name is **FitrahTube**. Internal IDs (`com.albunyaan.tube`, `albunyaantube/`, `albunyaantube://`, `AlbunyaanTubeApplication`) keep the original "albunyaan" naming for back-compat. Don't rename without a coordinated migration.

**Requirements**: [docs/PRD.md](docs/PRD.md). **Library guides**: @docs/library-guides/newpipe-extractor.md

---

## Workflow (mandatory)
1. Check in before starting; verify the plan with me.
2. Provide a high-level explanation at every step.
3. Make changes minimal — only what the task requires.
4. **Find root causes; never patch over symptoms.** No temp fixes.

---

## Critical policies
- **Hilt DI (Android)**: required. No manual service locators.
- **UI preservation**: never break working UI. Verify across phone / `sw600dp` / `sw720dp` (and RTL Arabic) before any UI change.
- **Edge-to-edge (SDK 35)**: all `fragment_main_shell.xml` variants must keep `fitsSystemWindows="true"`.
- **Pagination on large screens**: tablet/TV grids fit a full page without scrolling. Every list fragment must auto-trigger `loadMore()` when items fit on screen — scroll listener alone is insufficient.
- **Navigation icons**: never use `ic_stat_*` (notification drawables) or `@android:drawable/*` for nav bars. Vector-level `android:tint` and OEM quirks (Samsung Android 15) clash with `BottomNavigationView` / `NavigationRailView` `itemIconTint`. Use project-local vectors with no vector-level tint.
- **Don't create new `.md` files** unless explicitly requested.
- **Test timeout**: 300 s wall, 30 s per method.

---

## Layout qualifiers + tokens
| Qualifier | Device | Width |
|-----------|--------|-------|
| `layout/` | Phone | <600dp |
| `layout-sw600dp/` | Tablet | ≥600dp |
| `layout-sw720dp/` | Large tablet/TV | ≥720dp |

Tokens: `spacing_xs`=4dp, `spacing_sm`=8dp, `spacing_md`=16dp, `spacing_lg`=24dp. RTL: use `android:textAlignment="viewStart"`, never `gravity="left"`. Same view IDs across layout variants.

---

## Build / run

```bash
# Backend  (port 8080)
cd backend && ./gradlew bootRun
./gradlew test                       # unit
./gradlew test -Pintegration=true    # + integration (Firebase emulator)

# Frontend (port 5173)
cd frontend && npm ci && npm run dev
npm test          # Vitest, 300s timeout
npm run build

# Android
cd android && ./gradlew assembleDebug
./gradlew test
```

**Emulator → backend**: use `10.0.2.2:8080`. **Port 8080 stuck**: `lsof -ti:8080 | xargs kill -9`. **Firebase creds**: `export GOOGLE_APPLICATION_CREDENTIALS=$HOME/.config/albunyaan/firebase-service-account.json` (file lives outside the repo — NEVER commit it).

---

## Architecture
- Backend: Controller → Service → Repository → Firestore. Public API at `/api/v1/*`, admin at `/api/admin/*`.
- Frontend: Vue 3 + Pinia. i18n: `src/locales/messages.ts` (en, ar, nl).
- Android: MVVM + Hilt. `Fragment → ViewModel → Repository → RetrofitService → Backend`. Modules: `NetworkModule`, `DownloadModule`, `AppModule`.
- OpenAPI codegen: `./scripts/generate-openapi-dtos.sh` → `frontend/src/generated/api/schema.ts` + Kotlin DTOs.
- Caching: Caffeine (dev, 1 h TTL) / Redis (prod). Caches: `youtubeChannelSearch`, `youtubePlaylistSearch`, `youtubeVideoSearch`.

## Approval flow
Admin searches → "Add for Approval" → assign categories → `RegistryController` (PENDING) → review in `PendingApprovalsView` → approve/reject → Content Library → public API.

---

## Git conventions
Commit: `[PREFIX]: Description (≤50 chars)` with prefixes `[FEAT]` `[FIX]` `[REFACTOR]` `[PERF]` `[DOCS]` `[TEST]` `[CHORE]`. Branches: `main` (prod), `feature/{id}-{desc}`, `fix/{id}-{desc}`.

---

## Anti-sycophancy

**Banned openers**: "You're absolutely right!", "Great point!", "Excellent!", "Brilliant!", "Love this!", "I understand your concern, however…", "That's a valid approach, but…".

**On pushback**: don't capitulate by reflex. If user is right, say what you got wrong. If user is wrong, defend with evidence. If uncertain, say so and lay out both sides.

**Output shapes**:
- "Review this" → 3 worst issues first, then minor, then what works.
- "Is this a good idea?" → failure modes first, then strengths, then verdict with confidence %.
- "What do you think of my plan?" → name the weakest link first.
- "Should I do X or Y?" → pick one with reasoning. No "both have merit" cop-outs.

**Anchoring bias**: answer the question in isolation FIRST, ignoring user framing. Then compare to what they seem to want; surface any gap.

**Self-correction**: if you catch yourself being sycophantic mid-response, stop and restart. If user calls it out, acknowledge, fix the response, and ask whether this rule should be strengthened here.

---

## Docs

PRD `docs/PRD.md` · status `docs/status/PROJECT_STATUS.md` · dev `docs/status/DEVELOPMENT_GUIDE.md` · android `docs/status/ANDROID_GUIDE.md` · testing `docs/status/TESTING_GUIDE.md` · deployment `docs/status/DEPLOYMENT_GUIDE.md` · architecture `docs/architecture/overview.md` · API spec `docs/architecture/api-specification.yaml` · design system `docs/design/design-system.md` · i18n `docs/design/i18n-strategy.md`.
