# YouTube Rate Limiting Remediation Plan

**Last Updated**: December 15, 2025
**Status**: ✅ **Implemented** (P0–P3 functional; 2 manual/integration tests pending; P4 metrics deferred)

This document is the concrete, step-by-step plan to stop triggering YouTube anti-bot rate limits (`SignInConfirmNotBotException`: “Sign in to confirm that you're not a bot”) and make validation safe-by-default.

---

## 1) Problem Statement

The backend performs automated content validation (videos/channels/playlists) by calling YouTube via `NewPipeExtractor` (unauthenticated, browser-like scraping). On Dec 6, 2025, a scheduled run attempted to validate a large batch (≈195 videos) using concurrent requests (`app.newpipe.executor.pool-size=3`). This burst pattern triggered YouTube anti-bot protections and the server IP began returning `SignInConfirmNotBotException`/`LOGIN_REQUIRED` style failures. Subsequent validations continued to hit the block.

**Mid-batch interruption handling:**
- If rate-limiting is detected mid-batch: The circuit breaker opens, remaining items in the batch are **skipped** (not validated), and the run terminates early.
- Skipped items remain in their prior state (not archived, not marked invalid).
- The next scheduled run will re-attempt skipped items (they stay in the validation queue).
- No partial progress is lost: items validated before the interruption retain their updated status.

**Current code paths involved**
- Scheduler: `backend/src/main/java/com/albunyaan/tube/scheduler/VideoValidationScheduler.java`
- Validation orchestration: `backend/src/main/java/com/albunyaan/tube/service/ContentValidationService.java`
- Batch concurrency: `backend/src/main/java/com/albunyaan/tube/service/ChannelOrchestrator.java` → `backend/src/main/java/com/albunyaan/tube/service/YouTubeGateway.java`
- Gateway executor: `app.newpipe.executor.pool-size` in `backend/src/main/resources/application.yml`

---

## 2) Goals / Non-Goals

### Goals
1. Prevent large bursts of YouTube requests from a single server IP.
2. When rate limiting is detected, stop requests quickly (circuit breaker) and cool down automatically.
3. Keep validation conservative: **never archive** content due to transient failures (rate limit/network/parsing).
4. Make limits and schedule adjustable at runtime via configuration (no code deploy needed to “turn it down”).
5. Add observability (metrics + structured logs) and tests so this doesn’t regress.

### Non-Goals
- Bypassing YouTube anti-bot protections (cookies/session harvesting, CAPTCHA solving, etc.).
- Rotating proxies unless explicitly approved and ToS/compliance-reviewed.

---

## 3) Immediate Operational Actions (Today)

These are operational levers to reduce risk immediately while YouTube cooldown occurs (typically 24–48h).

1. **Pause scheduled validation** (preferred)
   - Short-term: disable scheduling (`spring.task.scheduling.enabled=false`) if acceptable (only `VideoValidationScheduler` uses scheduling today).
   - Better (requires code change): add an explicit feature flag to disable only this scheduler without disabling all scheduling.
2. **Reduce concurrency**
   - Set `app.newpipe.executor.pool-size=1` (minimize parallel request patterns).
3. **Reduce batch size**
   - Run manual validations with `maxItems=5–10` only, and avoid “validate everything” actions until cooldown clears.
4. **Monitor recovery**
   - Watch logs for drops in `SignInConfirmNotBotException`/`LOGIN_REQUIRED`.
   - Use existing admin status endpoint to confirm how many items are queued for validation.

---

## 4) Implementation Plan (Phased)

### Phase 0 — Safety switches (P0)

**Deliverable**: Ability to stop validation instantly (config-only), and prevent overlapping runs.

1. **Add scheduler enable flag**
   - Make `VideoValidationScheduler` conditional (e.g., `app.validation.video.scheduler.enabled=true`).
2. **Make cron schedule configurable**
   - Replace hard-coded 3×/day schedules with a single configurable cron (default: 1×/day).
3. **Prevent concurrent runs**
   - Add a guard so a new scheduled run won't start if the previous run is still running.

   **Chosen Implementation: Database-backed Distributed Lock**

   Since the backend may run as multiple instances (horizontal scaling, rolling deployments),
   an in-memory flag is insufficient. We will implement a **Firestore-backed distributed lock**.

   **Lock Semantics:**
   - **Collection**: `system_locks`
   - **Document ID**: `video_validation_scheduler`
   - **Fields**:
     - `lockedBy: String` — Instance identifier (hostname + UUID generated at startup)
     - `lockedAt: Timestamp` — When lock was acquired
     - `expiresAt: Timestamp` — Lock TTL expiration (default: 2 hours)
     - `runId: String` — Unique run identifier for correlation

   **Acquisition Flow:**
   1. Scheduler wakes up on cron trigger
   2. Attempt Firestore transaction:
      - Read lock document
      - If document doesn't exist OR `expiresAt < now()`: acquire lock (write new doc)
      - If document exists AND `expiresAt >= now()`: lock held by another instance, abort
   3. On successful acquisition: proceed with validation run
   4. On failure: log warning, skip this scheduled invocation

   **Release Flow:**
   1. After validation run completes (success or failure): delete lock document
   2. Use try/finally to ensure release even on exceptions

   **TTL / Stale Lock Handling:**
   - Default TTL: 2 hours (configurable via `app.validation.video.scheduler.lock-ttl-minutes`)
   - If an instance crashes mid-run, the lock auto-expires after TTL
   - Next scheduled run will acquire the stale lock once TTL passes
   - TTL should be longer than the maximum expected run duration

   **Failure Modes & Fallbacks:**
   - **Firestore unavailable during acquire**: Log error, skip this run (fail-safe: no run)
   - **Firestore unavailable during release**: Lock remains until TTL expires (safe: prevents overlap)
   - **Instance crash mid-run**: Lock held until TTL expires, then auto-released
   - **Clock skew between instances**: Use Firestore server timestamps (`FieldValue.serverTimestamp()`)

   **Cross-Instance Guarantees:**
   - At most ONE instance can hold the lock at any time
   - Firestore transactions provide atomic read-modify-write semantics
   - No leader election needed; any instance can acquire if lock is free

   **Configuration:**
   ```yaml
   app:
     validation:
       video:
         scheduler:
           lock-ttl-minutes: 120   # 2 hours default
   ```

   **Phase 0 Testing & Acceptance Criteria:**
   - [ ] **Unit test**: Lock acquisition succeeds when no lock exists
   - [ ] **Unit test**: Lock acquisition fails when valid lock held by another instance
   - [ ] **Unit test**: Lock acquisition succeeds when lock is expired (TTL passed)
   - [ ] **Unit test**: Lock release deletes document
   - [ ] **Integration test**: Simulate two scheduler instances; confirm only one runs
   - [ ] **Integration test**: Simulate crash (don't release lock); confirm TTL expiry allows next run
   - [ ] **Manual verification**: Deploy two backend instances, trigger schedule, confirm logs show only one running
   - [ ] Tests pass: `cd backend && timeout 300 ./gradlew test`

   **Alternatives Considered:**
   - **In-memory flag**: Simple but fails with multiple instances or restarts — rejected
   - **Leader election (e.g., Raft, ZooKeeper)**: Overkill for single-job coordination — rejected
   - **Redis distributed lock (Redlock)**: Viable, but adds Redis dependency when we already use Firestore — rejected
   - **Database row-level lock (PostgreSQL advisory locks)**: Not applicable; we use Firestore — rejected

### Phase 1 — Hard caps and throttling (P1)

**Deliverable**: Validation is “slow and steady” by default, even when the backlog is large.

1. **Lower default validation limits**
   - Reduce `ContentValidationService.DEFAULT_MAX_ITEMS_PER_RUN` from 500 to a safer default (e.g., 25 or 10), and make it configurable.
2. **Throttle between YouTube validations**
   - Introduce a configurable delay (e.g., 2–5s) between each `fetchStreamInfo/fetchChannelInfo/fetchPlaylistInfo` call.
   - Prefer a centralized guard in `YouTubeGateway` so all call sites benefit.
3. **Reduce batch fan-out**
   - Avoid scheduling hundreds of async tasks at once. Process in small chunks or sequentially under a global rate limiter.

### Phase 2 — Circuit breaker + cooldown (P2)

**Deliverable**: When anti-bot is detected, the system stops making requests and waits.

1. **Detect rate-limit / anti-bot signals**
   - Treat `SignInConfirmNotBotException` (and any "confirm you're not a bot" messages) as a distinct failure category.

   **Exception detection logic:**
   - **Primary check**: Exception class name equals `SignInConfirmNotBotException` (exact match)
   - **Secondary check**: Exception message contains any of these substrings (case-insensitive):
     - `"confirm you're not a bot"`
     - `"sign in to confirm"`
     - `"LOGIN_REQUIRED"`
     - `"confirm that you're not a bot"`
   - **Error category mapping**:
     | Category | Triggers Breaker | Archives Content |
     |----------|------------------|------------------|
     | Rate-limit/anti-bot | ✅ Yes | ❌ No |
     | Network timeout | ❌ No | ❌ No |
     | Parsing error | ❌ No | ❌ No |
     | Content not found | ❌ No | ✅ Yes |
     | Content deleted | ❌ No | ✅ Yes |
     | Other/unknown | ❌ No | ❌ No |

2. **Open a circuit breaker**
   - When detected, stop further YouTube calls for a configured cooldown window (e.g., 6–24h).
   - Persist breaker state so restarts don't immediately resume hammering.
3. **Fail fast while open**
   - Batch validations should immediately return "error/cooldown" without hitting YouTube.
4. **Record structured diagnostics**
   - Include: breaker opened-at, cooldown-until, exception type, sample message, and counts in `ValidationRun.details`.

   **Chosen Implementation: Firestore-persisted Circuit Breaker**

   The circuit breaker state must survive restarts and be visible to all backend instances.
   We will persist state in Firestore under the `system_settings` collection.

   **Storage Location:**
   - **Collection**: `system_settings`
   - **Document ID**: `youtube_circuit_breaker`
   - **Fields**:
     - `state: String` — `CLOSED` (normal), `OPEN` (blocking requests), `HALF_OPEN` (testing recovery)
     - `openedAt: Timestamp` — When the breaker was opened (Firestore server timestamp)
     - `cooldownUntil: Timestamp` — When the breaker may transition to HALF_OPEN
     - `lastTriggeredBy: String` — Exception class name that caused the open (e.g., `SignInConfirmNotBotException`)
     - `triggerMessage: String` — Truncated exception message (max 500 chars) for diagnostics
     - `consecutiveFailures: int` — Count of rate-limit errors in the current window
     - `lastFailureAt: Timestamp` — Time of most recent rate-limit error
     - `backoffLevel: int` — Current exponential backoff level (0, 1, 2, ... capped at 4)
     - `version: long` — Optimistic locking version for safe concurrent updates

   **State Transitions:**
   ```
   CLOSED ──(N errors in T minutes)──> OPEN
   OPEN ──(cooldownUntil reached)──> HALF_OPEN
   HALF_OPEN ──(probe success)──> CLOSED
   HALF_OPEN ──(probe failure)──> OPEN (increased backoff)
   ```

   **Behavior When Persistence is Unavailable (Fail-Safe Policy):**

   If Firestore is unreachable when checking/updating breaker state:

   1. **On read failure (checking if breaker is open)**:
      - **Default to OPEN/safe**: Reject the outbound YouTube call
      - Log `ERROR` with correlation ID: `"Circuit breaker persistence unavailable, defaulting to OPEN (safe)"`
      - Increment a local metric counter: `circuit_breaker_persistence_failures`
      - Do NOT retry the Firestore read (fail fast)

   2. **On write failure (opening/closing breaker)**:
      - Log `ERROR`: `"Failed to persist circuit breaker state change to [newState]"`
      - For OPEN transitions: Continue blocking locally even if persist fails (safe)
      - For CLOSE transitions: Keep local state OPEN, retry persist on next probe success
      - Alert if persistence failures exceed 3 in 5 minutes (indicates systemic issue)

   3. **Rationale**: We prefer false positives (blocking requests when we shouldn't) over
      false negatives (hammering YouTube when we should be cooling down). Transient
      Firestore issues should not allow runaway requests.

   **TTL, Cleanup, and Timestamp Semantics:**

   1. **Timestamps stored**:
      - `openedAt`: Set via `FieldValue.serverTimestamp()` when breaker opens
      - `cooldownUntil`: Calculated as `openedAt + cooldownDuration(backoffLevel)`
      - All time comparisons use Firestore server time to avoid clock skew

   2. **Cooldown duration by backoff level**:
      | Level | Duration | Triggered after |
      |-------|----------|-----------------|
      | 0     | 1 hour   | First rate-limit detection |
      | 1     | 6 hours  | Second detection within 24h |
      | 2     | 12 hours | Third detection within 24h |
      | 3     | 24 hours | Fourth detection within 24h |
      | 4     | 48 hours | Fifth+ detection (capped) |

   3. **Backoff level decay**:
      - If 48 hours pass with no rate-limit errors, decrement `backoffLevel` by 1
      - Checked on each successful YouTube call when breaker is CLOSED
      - Minimum backoff level is 0

      **Multi-instance decay coordination:**
      - Decay is triggered by the `lastFailureAt` timestamp stored in Firestore (shared across instances)
      - On each successful call, any instance checks: `now - lastFailureAt > 48 hours`
      - If true and `backoffLevel > 0`: decrement `backoffLevel` by 1 and update `lastFailureAt = now`
      - Use optimistic locking (`version` field) to prevent double-decrement race conditions
      - If concurrent updates conflict: The "losing" instance simply retries the read; at worst, decay happens on the next successful call
      - This ensures exactly one decrement per 48-hour success window, regardless of how many instances are running

   4. **No server-side TTL / auto-deletion**:
      - The breaker document is long-lived (singleton per system)
      - We do NOT delete the document; we update `state` to CLOSED when recovered
      - This preserves `backoffLevel` history for graduated response

   5. **Stale state cleanup**:
      - On startup, if `cooldownUntil` is in the past AND `state == OPEN`:
        - Transition to `HALF_OPEN` and allow a probe request
      - This handles the case where no backend was running when cooldown expired

   6. **Grace window for HALF_OPEN probes**:
      - After transitioning to HALF_OPEN, allow exactly 1 probe request
      - If probe succeeds: transition to CLOSED, reset `consecutiveFailures` to 0
      - If probe fails: transition to OPEN, increment `backoffLevel` (capped at 4)
      - Probe timeout: 30 seconds; treat timeout as failure

      **Probe request definition:**
      - A "probe" is the **first real YouTube API call** that arrives after HALF_OPEN transition
      - No synthetic/dedicated probe endpoint is created; the next natural validation request serves as the probe
      - This avoids unnecessary YouTube traffic and tests with realistic workload
      - Example: If a scheduled validation run starts while breaker is HALF_OPEN, the first video validation becomes the probe
      - If no validation requests arrive, the breaker remains HALF_OPEN indefinitely (no harm—requests are allowed)

   **Migration / Fresh Start Behavior:**

   - If document doesn't exist on first read: Breaker is implicitly CLOSED
   - Create document lazily on first rate-limit detection (not at startup)
   - No migration needed from prior versions (this is a new feature)

   **Concurrency Handling:**

   - Use `version` field for optimistic locking
   - Read version, compute new state, write with `version == readVersion` precondition
   - On conflict (another instance updated): Re-read and retry (max 3 attempts)
   - After 3 conflicts: Log warning, proceed with local-only state for this operation only

   **Local-only State Fallback (after max retries):**
   - Scope: Local state is held in-memory for the **current operation only** (not cached indefinitely)
   - Crash behavior: If instance crashes before completing the operation, no inconsistency—next operation reads fresh from Firestore
   - Sync strategy: **Eager** — the very next circuit breaker check/update will read/write Firestore (no lazy sync)
   - Safety: Since we default to OPEN on read failures, local-only fallback cannot cause runaway requests

   **Configuration:**
   ```yaml
   app:
     youtube:
       circuit-breaker:
         enabled: true
         persistence:
           collection: system_settings
           document-id: youtube_circuit_breaker
         cooldown-base-minutes: 60          # Level 0 cooldown
         cooldown-max-minutes: 2880         # 48 hours cap (level 4)
         backoff-decay-hours: 48            # Hours without error to decrement level
         probe-timeout-seconds: 30
         rolling-window:
           error-threshold: 3
           window-minutes: 10
   ```

   **Phase 2 Testing & Acceptance Criteria:**
   - [ ] **Unit test**: Breaker opens after N errors within window
   - [ ] **Unit test**: Breaker rejects calls when OPEN
   - [ ] **Unit test**: Breaker transitions to HALF_OPEN after cooldown expires
   - [ ] **Unit test**: Successful probe closes breaker
   - [ ] **Unit test**: Failed probe reopens breaker with increased backoff
   - [ ] **Unit test**: Persistence failure defaults to OPEN (safe)
   - [ ] **Unit test**: Backoff level decays after 48h of success
   - [ ] **Unit test**: Multi-instance decay coordination uses optimistic locking correctly
   - [ ] **Integration test**: Breaker state survives backend restart
   - [ ] **Integration test**: Multiple instances share breaker state
   - [ ] **Integration test**: Stale OPEN state on startup transitions to HALF_OPEN
   - [ ] **Integration test**: Partial-run interruption:
     - Start validation run with 10 items
     - Simulate rate-limit error on item 5
     - Verify: Items 1-4 have updated status (validated successfully)
     - Verify: Items 5-10 retain prior status (not archived, not marked invalid)
     - Verify: Breaker is OPEN after interruption
     - Verify: Next run re-attempts items 5-10
   - [ ] Tests pass: `cd backend && timeout 300 ./gradlew test`

   **Alternatives Considered:**
   - **In-memory only**: Fails on restart, instances disagree — rejected
   - **Redis**: Adds infrastructure dependency when Firestore already available — rejected
   - **Separate `circuit_breaker_state` collection**: Unnecessary; singleton fits `system_settings` — rejected
   - **Server-side TTL auto-delete**: Would lose backoff history, require re-creation — rejected

### Phase 3 — Backoff strategy (P3)

**Deliverable**: Repeated anti-bot detections increase wait time automatically.

1. **Exponential backoff**
   - First detection: 1–6h cooldown; repeated: 12h; repeated again: 24–48h, capped.
2. **Jitter**
   - Add random jitter to delays to avoid deterministic patterns.
3. **Gradual ramp-up**
   - After cooldown, resume with very small batch sizes; increase only if the error rate stays low.

### Phase 4 — Observability and tests (P4)

**Deliverable**: Prevent regressions and make it obvious when/why throttling is active.

1. **Metrics**
   - Counters for validation attempts, successes, notFound, transient errors, and “rate limited/anti-bot”.
   - Gauge for breaker state (open/closed) and cooldown remaining.
2. **Unit tests (fast, deterministic)**
   - Exception/message classification tests (anti-bot vs notFound vs generic error).
   - Circuit breaker open/close behavior tests.
   - Throttle behavior tests using fakes (no sleeps in unit tests).
3. **Run tests under policy**
   - Backend: `cd backend && timeout 300 ./gradlew test`

---

## 5) Proposed Configuration (Draft)

These keys are proposals to implement the plan without hard-coded values:

**Hot-Reload Behavior:**
| Config Key | Hot-Reloadable | Notes |
|------------|----------------|-------|
| `scheduler.enabled` | ✅ Yes | Checked before each scheduled run |
| `scheduler.cron` | ❌ No | Requires restart (Spring scheduler registers at startup) |
| `scheduler.lock-ttl-minutes` | ✅ Yes | Read on each lock acquisition |
| `max-items-per-run` | ✅ Yes | Checked at run start |
| `throttle.enabled` | ✅ Yes | Checked before each YouTube call |
| `throttle.delay-between-items-ms` | ✅ Yes | Read on each delay |
| `throttle.jitter-ms` | ✅ Yes | Read on each delay |
| `circuit-breaker.enabled` | ✅ Yes | Checked before each YouTube call |
| `circuit-breaker.cooldown-*` | ✅ Yes | Read when computing cooldown duration |
| `circuit-breaker.rolling-window.*` | ✅ Yes | Read when evaluating error threshold |

For hot-reloadable configs, use Spring's `@ConfigurationProperties` with `@RefreshScope` or inject `Environment` and read at runtime.

```yaml
app:
  validation:
    video:
      scheduler:
        enabled: true
        cron: "0 0 6 * * ?"   # default 1×/day, UTC
        lock-ttl-minutes: 120  # distributed lock TTL (2 hours)
      max-items-per-run: 10
  youtube:
    throttle:
      enabled: true
      delay-between-items-ms: 3000
      jitter-ms: 1000
    circuit-breaker:
      enabled: true
      # Persistence (Firestore)
      persistence:
        collection: system_settings
        document-id: youtube_circuit_breaker
      # Cooldown / backoff
      cooldown-base-minutes: 60           # Level 0: 1 hour
      cooldown-max-minutes: 2880          # Level 4 cap: 48 hours
      backoff-decay-hours: 48             # Hours of success before decrementing backoff level
      probe-timeout-seconds: 30           # Timeout for HALF_OPEN probe requests
      # Rolling-window policy: open breaker after N errors within T minutes
      rolling-window:
        error-threshold: 3                # number of rate-limit errors to trigger open
        window-minutes: 10                # time window to count errors within
```

**Circuit Breaker Threshold Rationale:**

The original `max-rate-limit-errors-to-open: 1` was too aggressive—a single transient
network hiccup or temporary YouTube glitch would lock out all requests for 12 hours.

We chose a **rolling-window policy (3 errors within 10 minutes)** because:
1. **Filters transient blips**: A single sporadic error won't trigger lockout
2. **Still responsive to real rate limiting**: 3 errors in 10 minutes strongly indicates
   YouTube is actively blocking us, not just a one-off failure
3. **Matches YouTube behavior**: Real anti-bot triggers tend to produce consistent failures,
   not isolated ones
4. **Safe default**: 3 is conservative enough to catch real issues quickly while avoiding
   false positives from network instability

Alternatives considered:
- **Threshold of 2**: Too sensitive; two back-to-back retries on a flaky request could trigger
- **Threshold of 5**: Too lenient; would allow more requests to hit YouTube after detection
- **Longer window (30 min)**: Risk of accumulating old errors that no longer reflect current state
- **Shorter window (5 min)**: Might miss slow-drip rate limiting patterns

Notes:
- Keep `app.newpipe.executor.pool-size` at `1` by default for validation paths.
- Throttling should apply to all NewPipeExtractor calls that hit YouTube, not only scheduled validations.

---

## 6) Rollout Plan (Safe Ramp)

1. **Deploy P0+P1 with conservative defaults**
   - Scheduler disabled or 1×/day; batch size 5–10; delay 2–5s; pool size 1.
2. **Wait for recovery**
   - Confirm anti-bot errors disappear (or drop to near-zero) before increasing any limits.
3. **Enable P2 breaker**
   - Ensure on detection it stops the run early and sets cooldown.
4. **Gradually increase throughput**
   - Only if stable for multiple days; increase max-items slowly (e.g., 10 → 25 → 50).

Backout:
- Disable scheduler or set `max-items-per-run=0` (treated as “do nothing”) and keep breaker enabled.

---

## 7) Verification Checklist

### Phase 0 — Safety Switches (7/8 complete)
- [x] Scheduled validation can be disabled via config (no deploy).
- [x] Cron schedule is configurable via `app.validation.video.scheduler.cron`.
- [x] **Distributed lock prevents concurrent runs:**
  - [x] Lock acquisition succeeds when no lock exists.
  - [x] Lock acquisition fails when valid lock held by another instance.
  - [x] Lock acquisition succeeds when lock is expired (TTL passed).
  - [x] Lock release deletes document on normal completion.
  - [x] Lock release occurs even on run failure (try/finally).
  - [x] Two backend instances cannot run validation simultaneously (integration test).
  - [x] Simulated crash scenario: lock expires after TTL, next run proceeds.
  - [ ] Manual multi-instance deployment test confirms single runner.

### Phase 1 — Throttling ✅
- [x] Batch size is capped (cannot validate 100s of items in one run by default).
- [x] **Batch size configurable**: `app.validation.video.max-items-per-run` is respected.
- [x] Requests are visibly throttled in logs/metrics (no burst patterns).
- [x] **Throttle delay applied**: Each YouTube call waits `delay-between-items-ms` ± `jitter-ms`.
- [x] **Jitter randomization**: Delays are not identical (verify via log timestamps or metrics).
- [x] **Pool size respected**: Concurrent YouTube requests limited to `app.newpipe.executor.pool-size`.
- [x] **Throttle covers all call sites**: Manual validation, scheduled validation, and admin refresh all throttled.

### Phase 2 — Circuit Breaker Persistence (15/16 complete)
- [x] **Storage**: Breaker state persisted in `system_settings/youtube_circuit_breaker` document.
- [x] **State transitions**: CLOSED → OPEN → HALF_OPEN → CLOSED cycle works correctly.
- [x] **Fail-safe policy**: When Firestore unavailable, breaker defaults to OPEN (rejects calls).
- [x] **Persistence failures logged**: ERROR-level logs with correlation ID on read/write failures.
- [x] **TTL semantics**: `openedAt` and `cooldownUntil` use Firestore server timestamps.
- [x] **Backoff levels**: Cooldown duration increases correctly (1h → 6h → 12h → 24h → 48h cap).
- [x] **Backoff decay**: Level decrements after 48h of successful calls with no rate-limit errors.
- [x] **Stale state recovery**: On startup, expired OPEN state transitions to HALF_OPEN.
- [x] **HALF_OPEN probes**: Single probe request allowed; success → CLOSED, failure → OPEN.
- [x] **Concurrency**: Optimistic locking with version field prevents race conditions.
- [x] **Restart survival**: Breaker state survives backend restart (integration test).
- [x] **Multi-instance**: All backend instances share breaker state (integration test).
- [x] Anti-bot detection opens breaker and stops further requests.
- [x] **Batch fail-fast**: `isCircuitBreakerBlocking()` allows batch callers to fail fast without attempting individual requests (cooldown-aware).
- [x] **Never-archive guarantee on transient failures**:
  - [x] Rate-limit errors (`SignInConfirmNotBotException`) do NOT archive content.
  - [x] Network timeouts do NOT archive content.
  - [x] Parsing errors (NewPipeExtractor bugs) do NOT archive content.
  - [x] Only explicit "content not found" / "content deleted" errors archive.
  - [x] Unit test: Verify `archiveContent()` is NOT called on rate-limit exceptions.
  - [x] Unit test: Verify `archiveContent()` is NOT called on network timeout.
  - [ ] Integration test: Simulate rate-limit mid-batch; confirm no items archived.

### Phase 3 — Backoff Strategy ✅
- [x] **Exponential backoff**: Cooldown increases with backoff level (1h → 6h → 12h → 24h → 48h cap).
- [x] **Jitter**: Random jitter applied to throttle delays via `jitter-ms` config.
- [x] **Backoff decay**: Level decrements after 48h of successful calls.
- [x] **Backoff cap**: Level capped at 4 (48h max cooldown).

### Phase 4 — Observability and Tests (partial)
- [x] **Unit tests**: Exception/message classification tests implemented.
- [x] **Unit tests**: Circuit breaker open/close behavior tests implemented.
- [x] **Unit tests**: Throttle behavior tests implemented.
- [x] **Test policy**: All tests run within 300s timeout.
- [ ] **Metrics**: Counters for validation attempts/successes/errors (deferred).
- [ ] **Metrics**: Gauge for breaker state and cooldown remaining (deferred).

### All Phases
- [x] Tests pass within 300s: `cd backend && timeout 300 ./gradlew test` (387 tests, 0 failures)

