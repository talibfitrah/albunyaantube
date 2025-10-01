# Playlist Hydration Profiling – Baseline

_Run date_: 2025-10-08

## Summary
Initial profiling session prepared as part of PERF-API-01. Environment setup documented; load execution pending access to backend container runtime on this workstation.

## Environment
- Gradle task: `:backend:gatlingRun-listingSweep`
- Target dataset: 5k playlists (see runbook seeding command)
- Tooling: PostgreSQL 15, Redis 7

## Status
- [ ] Data seeded (`--seed.playlists=5000`)
- [ ] Gatling scenario executed
- [ ] Redis metrics captured
- [ ] Query plan archived (`perf/api/playlist-plan-baseline.json`)

Work blocked awaiting backend container availability. Once unblocked, execute runbook steps and attach artefacts in this folder.
