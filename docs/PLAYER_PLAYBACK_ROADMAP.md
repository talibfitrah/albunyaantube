# Player & Playback Reliability Roadmap

**Last Updated**: 2025-12-15  
**Scope**: Android player stability, progressive/adaptive strategy, extraction/refresh safety, and backend YouTube anti-bot mitigation.

## Guiding Principles (Rock-Solid UX)

1. Prefer adaptive manifests (**HLS/DASH**) whenever available; treat user quality as a **cap**, not a lock.
2. Accept the hard truth: **Media3 cannot “fix” progressive**. Progressive is single-bitrate; the only ways to avoid stalls are:
   - (a) get adaptive streams more reliably, or
   - (b) proactively switch to a lower progressive stream before a stall.
3. Make all “re-resolve/refresh URLs” paths **rate-limit safe** (client + backend) so recovery/proactive switching doesn’t trigger anti-bot blocks.
4. Any UI change must be verified on **phone + `sw600dp` tablet + RTL Arabic** per `AGENTS.md`.

## Reality Check: What Media3 Helps vs Doesn’t

### Media3 can improve
- Buffering behavior tuning and retry primitives (implementation-level improvements).
- Long-term maintenance (actively developed APIs).
- MediaSession/notification plumbing (cleaner background controls).

### Media3 does not solve
- Progressive streams are **single-bitrate**: no framework can “ABR” them without adaptive manifests.
- If HLS/DASH is missing or unreliable/expired, stability depends primarily on:
  - extraction reliability,
  - URL lifecycle handling,
  - proactive progressive downshifting,
  - bounded refresh/retry with rate-limit protection.

## Current Status (What’s Done vs What’s Left)

### Completed (in `main`)
- **PR1 Done**: Adaptive-first selection, consistent DataSource settings + caching, fixed HLS→DASH fallback, accurate `PLAYBACK_START` logging.
- **PR2 Done**: Freeze detection + auto-recovery ladder + state-driven recovery UI; tests pass under 300s policy.
- **PR3 Done**: Progressive playback proactive downshift (buffer health monitoring) to reduce freezes by switching quality before stalls.
- **PR4 Done**: Stream URL lifecycle hardening (URL generation timestamp + conservative TTL checks to avoid switching to expired progressive URLs).
- **PR5 Done**: Android extraction/refresh rate-limit guardrails (`ExtractionRateLimiter` + wiring). Committed 2025-12-15.

### Still required (mandatory validation)
- **Manual visual verification** per `AGENTS.md`: phone + `sw600dp` tablet + RTL Arabic locale.

### Backend plan reference
- Backend anti-bot mitigation plan: `docs/status/YOUTUBE_RATE_LIMIT_PLAN.md`
  - **Status**: Draft (tracked in git). PR6–PR8 in this roadmap depend on it.

---

# PR-by-PR Plan (From Scratch, with statuses)

## PR2 (Finalize Gate + Merge) — *Status: Done (merged to main)*

**Goal**: Lock in "never permanently stuck" behavior.

- ✅ Merged: commit `17e9ebb` on 2025-12-15
- ✅ Manual verification completed:
  - Phone layout: recovery overlay, exhausted retry UI, error overlay.
  - Tablet (`sw600dp`) layout: same behaviors and consistent view IDs.
- Run: `cd android && timeout 300 ./gradlew testDebugUnitTest`

**Acceptance**
- No “leave screen to fix playback” scenario remains; worst case is exhausted state with retry.

## PR3: Progressive Playback – Proactive Downshift — *Status: Implemented*

**Goal**: Prevent long freezes on progressive playback by switching down before the stall.

- Progressive-only buffer health monitoring:
  - sample `bufferedPosition - currentPosition` trend (e.g., 1s sampling)
  - trigger downshift when buffer is low and declining (guardrails: grace period, cooldown, max downshifts)
- Downshift using already-resolved progressive tracks (no extra extraction calls unless URLs are expired/invalid).
- Preserve position + play/pause state; cap auto-downshift count per stream.
- Coordinate with PR2: disable proactive downshift while `Recovering/RecoveryExhausted`.

**Acceptance**
- Under throttling, fewer/shorter stalls than PR2-only; no reload loops.

## PR4: Android Stream URL Lifecycle Hardening — *Status: Implemented*

**Goal**: Make URL expiry/segment failures recover smoothly (especially for long videos).

- Track URL generation time and detect conservative expiry.
- Prevent quality switching to likely-expired progressive URLs; refresh first, then apply the user’s cap/selection.
- Ensure refresh resumes at the saved position and doesn’t fight proactive/recovery actions.

**Acceptance**
- Long videos don’t degrade into repeated “buffer forever”; refresh is bounded and reliable.

## PR5: Android Extraction/Refresh Rate-Limit Guardrails (Client-side) — *Status: Done*

**Goal**: Prevent the app from hammering extraction endpoints during retries/recovery while keeping recovery reliable.

- ✅ Committed: 2025-12-15

### Must-have behaviors (rock-solid UX)
- **Record attempts before extraction** (attempt-based limiter; not “success-based”).
- Separate budgets by request kind:
  - `MANUAL`: strict (spam protection).
  - `AUTO_RECOVERY`: reserved budget so recovery is not blocked by manual refresh spam.
  - `PREFETCH`: lowest priority; skip under pressure.
- Cancel stale delayed refresh jobs on video changes.
- Prefer local progressive quality switching over refresh when possible; refresh only when URLs are invalid/expired.

### Tests
- Unit tests for limiter logic (no sleeps; use injected clock).
- Manual: repeated refresh taps and recovery scenarios should not create bursts.

**Acceptance**
- Recovery cannot spiral into rapid refresh loops.
- Users receive clear, bounded feedback when manual refresh is rate-limited.
- Auto-recovery refresh is not blocked by manual refresh limits.

---

# Backend YouTube Rate Limit Remediation (separate but critical)

This should be implemented per `docs/status/YOUTUBE_RATE_LIMIT_PLAN.md`.

## PR6 (Backend P0+P1): Safety switches + throttling — *Status: Planned*
- Config-only scheduler enable flag; configurable cron; prevent overlapping runs.
- Reduce default batch sizes; throttle between validations; set executor pool size defaults safely.

**Acceptance**
- No burst patterns; safe defaults; can disable without deploy.

## PR7 (Backend P2+P3): Circuit breaker + cooldown + exponential backoff — *Status: Planned*
- Detect anti-bot signals, open breaker, persist state, fail fast during cooldown, ramp-up slowly after.

**Acceptance**
- One detection stops the run; restarts don’t resume hammering.

## PR8 (Backend P4): Metrics + tests — *Status: Planned*
- Metrics for attempts/success/fail categories; breaker gauge; deterministic unit tests.

**Acceptance**
- Regressions become obvious; tests stay under 300s.

---

# Product / UX Follow-ups (pending after stability baseline)

## PR9: Media3 Migration (Keep UI layout, preserve stability features) — *Status: Planned*

**Goal**: Migrate to Media3 for long-term maintenance + better session/notification primitives, without claiming it fixes progressive.

- Migrate player core (Player/View, MediaSource factories, track selector, listeners).
- Keep the same layout and controls (IDs and overlays remain; underlying player widget class changes).
- Port PR2 recovery hooks + PR3 proactive downshift to Media3 `Player` APIs.
  - Best practice: keep buffer-health logic framework-agnostic; adapt only the player accessor layer.

**Acceptance**
- Feature parity; no regressions in recovery/proactive behavior (adaptive + progressive + audio-only).

## PR10: Notification / Background Controls + Thumbnail (Media3-first) — *Status: Planned*
- Implement `MediaSessionService`, attach player to a `MediaSession`.
- Provide actions (play/pause/next/prev/stop) + metadata + artwork.

**Acceptance**
- Controls work reliably in background/lockscreen/headset.

## PR11: Fullscreen + Orientation Policy (Phone + Tablet) — *Status: Planned*
- Landscape → fullscreen automatically; portrait → normal player screen.
- Verify immersive UI + RTL.

## PR12: Downloads – MP4-only Export, Always With Audio — *Status: Planned*
- Add resolution picker UI (video qualities); auto-pick audio; mux in background.
- Always output a single MP4 (with audio), or show a clear failure reason.

## PR13: Favorites (Local Like Replacement) + Favorites Screen — *Status: Planned*
- Local persistence (Room recommended), toggle in player, favorites list reachable via home kebab menu.

## PR14: Share (Absolutely NO YouTube URL) — *Status: Planned*
- Share only app deep-links + rich preview; never `youtube.com`/`youtu.be`.

## PR15: Documentation Sync (PRD + Agent Docs) — *Status: Planned*
- Update `docs/PRD.md`, `AGENTS.md`, `CLAUDE.md` to reflect actual behavior and the stability architecture.

---

# Key Risk Controls (Non-negotiable for “rock solid”)

1. Proactive progressive downshift must be bounded (cooldowns + max attempts) to avoid reload loops.
2. Recovery + proactive + error retry must be coordinated (single owner at a time).
3. Refresh/resolution must be rate-limit safe by design (dedupe + backoff + breaker).
4. Every UI PR must include explicit phone + `sw600dp` + RTL validation steps in its checklist.

