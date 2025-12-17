# Player & Playback Reliability Roadmap (Android)

**Last Updated**: 2025-12-16
**Scope**: Android player stability, progressive/adaptive strategy, and extraction/refresh safety.

> **Note**: Backend YouTube rate-limit remediation is tracked separately on branch `claude/fix-youtube-rate-limiting-clean` in `docs/status/YOUTUBE_RATE_LIMIT_PLAN.md`.

## Guiding Principles (Rock-Solid UX)

1. Prefer adaptive manifests (**HLS/DASH**) whenever available; treat user quality as a **cap**, not a lock.
2. Accept the hard truth: **Media3 cannot “fix” progressive**. Progressive is single-bitrate; the only ways to avoid stalls are:
   - (a) get adaptive streams more reliably, or
   - (b) proactively switch to a lower progressive stream before a stall.
3. Make all “re-resolve/refresh URLs” paths **rate-limit safe** (client + backend) so recovery/proactive switching doesn’t trigger anti-bot blocks.
4. Any UI change must be verified on **phone + `sw600dp` tablet + `sw720dp` large tablet/TV + RTL Arabic** per `AGENTS.md` (large-screen verification required when `sw720dp` resources exist).

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

## Current Status (What's Done vs What's Left)

### Completed (in `main`)
- **PR1 Done**: Adaptive-first selection, consistent DataSource settings + caching, fixed HLS→DASH fallback, accurate `PLAYBACK_START` logging.
- **PR2 Done**: Freeze detection + auto-recovery ladder + state-driven recovery UI; tests pass under 300s policy.
- **PR3 Done**: Progressive playback proactive downshift (buffer health monitoring) to reduce freezes by switching quality before stalls.
- **PR4 Done**: Stream URL lifecycle hardening (URL generation timestamp + conservative TTL checks to avoid switching to expired progressive URLs).
- **PR5 Done**: Android extraction/refresh rate-limit guardrails (`ExtractionRateLimiter` + wiring). Committed 2025-12-15.
- **PR6 Pending Verification**: Media3 migration + MediaSessionService integration (background playback, controller security, lifecycle-safe binding). Code complete 2025-12-16, awaiting manual verification.

### Still required (mandatory validation)
- **Manual visual verification** per `AGENTS.md`: phone + `sw600dp` tablet + `sw720dp` large tablet/TV + RTL Arabic locale.
- **Manual verification**: lockscreen/Bluetooth/Android Auto controller behavior.

---

# PR-by-PR Plan (From Scratch, with statuses)

## PR2 (Finalize Gate + Merge) — *Status: Done (merged to main)*

**Goal**: Lock in "never permanently stuck" behavior.

- ✅ Merged: commit `17e9ebb` on 2025-12-15
- ✅ Manual verification completed:
  - Phone layout: recovery overlay, exhausted retry UI, error overlay.
  - Tablet (`sw600dp`) layout: same behaviors and consistent view IDs.
  - Large tablet/TV (`sw720dp`) layout: verify scale (dimen overrides), focus/touch targets, and consistent view IDs.
- Run: `cd android && timeout 300 ./gradlew testDebugUnitTest`

**Acceptance**
- No “leave screen to fix playback” scenario remains; worst case is exhausted state with retry.

## PR3: Progressive Playback – Proactive Downshift — *Status: Done*

**Goal**: Prevent long freezes on progressive playback by switching down before the stall.

- Progressive-only buffer health monitoring:
  - sample `bufferedPosition - currentPosition` trend (e.g., 1s sampling)
  - trigger downshift when buffer is low and declining (guardrails: grace period, cooldown, max downshifts)
- Downshift using already-resolved progressive tracks (no extra extraction calls unless URLs are expired/invalid).
- Preserve position + play/pause state; cap auto-downshift count per stream.
- Coordinate with PR2: disable proactive downshift while `Recovering/RecoveryExhausted`.

**Acceptance**
- Under throttling, fewer/shorter stalls than PR2-only; no reload loops.

## PR4: Android Stream URL Lifecycle Hardening — *Status: Done*

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
- ✅ Unit tests for limiter logic (injected clock) - 18 tests in `ExtractionRateLimiterTest.kt`
- ✅ Unit tests for recovery manager (injected clock) - 23 tests in `PlaybackRecoveryManagerTest.kt`
- ✅ Unit tests for buffer health monitor (injected clock) - 22 tests in `BufferHealthMonitorTest.kt`
- ✅ Unit tests for quality step-down helper - 14 tests in `QualityStepDownHelperTest.kt`
- Total: 77 player tests, all passing
- Manual: repeated refresh taps and recovery scenarios should not create bursts.

**Acceptance**
- Recovery cannot spiral into rapid refresh loops.
- Users receive clear, bounded feedback when manual refresh is rate-limited.
- Auto-recovery refresh is not blocked by manual refresh limits.

---

# Product / UX Follow-ups (pending after stability baseline)

## PR6: Media3 Migration + MediaSession Integration — *Status: Pending Verification*

**Goal**: Migrate to Media3 for long-term maintenance + better session/notification primitives, without claiming it fixes progressive.

- ✅ Migrated player core (Player/View, MediaSource factories, track selector, listeners).
- ✅ Keep the same layout and controls (IDs and overlays remain; underlying player widget class changes).
- ✅ Port PR2 recovery hooks + PR3 proactive downshift to Media3 `Player` APIs.
- ✅ Implemented `MediaSessionService` (`PlaybackService.kt`) with session wiring.
- ✅ Service lifecycle: bind-based (not start-based) to avoid Android O+ foreground timing issues.
- ✅ Controller security: full commands for own app + legacy controller (system notification/Bluetooth/lockscreen); all others get restricted playback-only commands (play/pause/seek/stop/metadata).
- ✅ Explicit local binding path (`ACTION_LOCAL_BIND`) for robust binder type.
- ✅ Lifecycle-aware service rebind on unexpected disconnect.
- ✅ User pause state preserved across fragment lifecycle.

**Acceptance**
- ✅ Feature parity; no regressions in recovery/proactive behavior (adaptive + progressive + audio-only).
- ✅ Background playback works via MediaSessionService.
- ⏳ Manual verification: lockscreen/Bluetooth/Android Auto controller behavior under the chosen onConnect() policy.

## PR7: Notification Controls + Artwork — *Status: Planned*
- Add media notification with artwork/metadata display.
- Ensure notification actions (play/pause/next/prev/stop) work correctly.
- Add artwork loading for lockscreen/notification.

**Acceptance**
- Controls work reliably in background/lockscreen/headset with proper artwork.

## PR8: Fullscreen + Orientation Policy (Phone + Tablet) — *Status: Planned*
- Landscape → fullscreen automatically; portrait → normal player screen.
- Verify immersive UI + RTL.

## PR9: Downloads – MP4-only Export, Always With Audio — *Status: Planned*
- Add resolution picker UI (video qualities); auto-pick audio; mux in background.
- Always output a single MP4 (with audio), or show a clear failure reason.

## PR10: Favorites (Local Like Replacement) + Favorites Screen — *Status: Planned*
- Local persistence (Room recommended), toggle in player, favorites list reachable via home kebab menu.

## PR11: Share (Absolutely NO YouTube URL) — *Status: Planned*
- Share only app deep-links + rich preview; never `youtube.com`/`youtu.be`.

## PR12: Documentation Sync (PRD + Agent Docs) — *Status: Planned*
- Update `docs/PRD.md`, `AGENTS.md`, `CLAUDE.md` to reflect actual behavior and the stability architecture.

---

# Key Risk Controls (Non-negotiable for “rock solid”)

1. Proactive progressive downshift must be bounded (cooldowns + max attempts) to avoid reload loops.
2. Recovery + proactive + error retry must be coordinated (single owner at a time).
3. Refresh/resolution must be rate-limit safe by design (dedupe + backoff + breaker).
4. Every UI PR must include explicit phone + `sw600dp` + `sw720dp` (when present) + RTL validation steps in its checklist.
