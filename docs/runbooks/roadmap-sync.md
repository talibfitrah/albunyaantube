# Roadmap Sync Runbook

Purpose: keep roadmap/backlog authoritative for multi‑agent work.

Cadence: Weekly (or before any major ticketing/kickoff).

Checklist
1) Review delivered changes since last sync
   - Scan git log and open PRs.
   - Note shipped files and tests; summarize under the relevant phase’s “Delivered in this repo”.

2) Update execution metadata
   - For each roadmap phase, refresh: Status, Last Reviewed, Dependencies, Owners.
   - For each ticket heading, update the YAML `meta` block (status, owner, depends, lastReviewed).

3) Sync acceptance criteria ↔ backlog ↔ tests
   - Add new ACs in `docs/acceptance/criteria.md` when scope expands (e.g., AC-REG-003 for subcategories).
   - Append mappings in `docs/backlog/ac-traceability.csv` (Story → AC IDs → Tests → Notes).
   - For backlog entries, include AC IDs and test file paths in description if adding new items.

4) Triage gaps
   - For any partial tickets, add “Delivered so far / Gaps” bullets in the ticket text.
   - Create follow-up backlog rows (Phase-appropriate) with IDs and owners (TBD allowed).

5) Publish sync
   - Commit updates.
   - Post a short summary in PR description or team channel: changed statuses, new ACs, next owners.

Conventions
- Date format: YYYY-MM-DD.
- Ticket meta YAML keys: `id, status [planned|partial|in-progress|done], owner, depends[], lastReviewed`.
- Keep file path references to exact repository paths for easy navigation.
