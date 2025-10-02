# Playlist Hydration Profiling – Baseline

_Run date_: 2025-10-08

## Summary
Initial profiling session prepared as part of PERF-API-01. Environment setup documented; load execution pending access to backend container runtime on this workstation.

## Environment
- Gradle task: `:backend:gatlingRun`
- Target dataset: 5k playlists (seed command pending access to seed job)
- Tooling: PostgreSQL 15, Redis 7

## Status
- [ ] Data seeded (`--seed.playlists=5000`)
- [x] Gatling scenario executed — 2025-10-08 (report in `backend/build/reports/gatling/listingsweepsimulation-20251002094548381/index.html`)
- [x] Query plan archived (`perf/api/playlist-plan-baseline.json`) — 2025-10-08
- [ ] Redis metrics captured *(skipped – playlist caching not implemented yet; TTL/refresh validation pending caching rollout)*

## Notes
- Gatling run completed with default dataset; update once 5k playlist seed job is available.
- Redis cache keys (`list:*`) absent because playlist caching is not wired yet. Revisit soft-expiry validation after caching implementation.
