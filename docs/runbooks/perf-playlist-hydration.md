# PERF-API-01 – Playlist Hydration Profiling

This runbook captures the workflow for the Phase 10 hardening task that optimises playlist listing latency.

## Objectives
- Measure PostgreSQL execution plans for playlist list queries (`/playlists`, `/channels/{id}/playlists`).
- Verify Redis soft-expiry refresh latency stays <250 ms.
- Tune indexes and refresh cadence when latency budgets or cache hit ratio regress.

## Tooling
- `docker-compose` stack (backend + Postgres + Redis).
- psql 15+ for EXPLAIN ANALYZE.
- Gatling scenario `perf/listing-sweep.scala` (see `perf/` in backend repo).
- Prometheus + Grafana dashboards `Perf:API` (latency, cache hit ratio).

## Step-by-step
1. **Seed sample data**
   ```bash
   ./gradlew :backend:bootRun --args='--seed.playlists=5000'
   ```
   Confirms representative pagination sizes.
2. **Capture baseline plan**
   ```sql
   EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
   SELECT ... -- query used by PlaylistRepository.list()
   ```
   Save output to `perf/api/playlist-plan-baseline.json` and log the run in `perf/api/playlist-findings.md`.
3. **Stress with Gatling**
   ```bash
   ./gradlew :backend:gatlingRun \
     -Dgatling.simulationClass=com.albunyaan.tube.perf.ListingSweepSimulation \
     -Dusers=200 -Dramp=60 -Dlimit=50
   ```
   Record p95 latency, payload size, Redis hit ratio.
4. **Check Redis refresh**
   - Trigger soft-expiry by forcing cache entry age >4 min.
   - Observe `cache_refresh_latency` metric; target <250 ms.
   - If latency spikes, adjust refresh job batch size in `CacheRefreshService` and re-run Gatling.
5. **Tune indexes**
   - Evaluate `btree` on `(channel_id, published_at DESC)` if scans show bitmap heap loops.
   - Run migration draft and compare plan diff. Maintain <3% regression in write cost.
6. **Document findings**
   - Update `perf/api/playlist-findings.md` with metrics and decisions.
   - Create Jira/Linear subtask if schema change required.

## Success Criteria
- `/playlists` p95 latency ≤150 ms at 200 RPS mixed locale load.
- Redis hit ratio ≥85% sustained; refresh latency <250 ms.
- Query plan cost reduced ≥15% compared to baseline.

## Rollback
- Revert migrations and cache cadence toggles.
- Restore previous Gatling baseline artefacts from `perf/api/playlist-plan-baseline.json`.

## Contacts
- Backend Lead (owner)
- DevOps on-call for Grafana alert follow-up
