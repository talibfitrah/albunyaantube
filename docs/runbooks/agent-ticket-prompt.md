# Agent Ticket Prompt Template

Use this template to kick off an AI agent on a specific ticket while enforcing pre-flight reasoning and conflict checks.

---

Project context
- Repo path: /home/farouq/Development/albunyaantube
- Roadmap + backlog are the source of truth. Tickets include YAML meta blocks in:
  - `docs/roadmap/phase-1-backend-plan.md`
  - `docs/roadmap/phase-3-admin-mvp-tickets.md`
  - `docs/roadmap/phases-4-12-ticket-breakdown.md`
- Backlog with execution columns: `docs/backlog/product-backlog.csv`
- Acceptance criteria: `docs/acceptance/criteria.md`
- Traceability: `docs/backlog/ac-traceability.csv`
- Sync process: `docs/runbooks/roadmap-sync.md`

Your assignment
- Ticket ID: [TICKET_ID]
- Goal: [Short business outcome]
- Constraints: Do not alter layouts/spacing/copy unless the ticket explicitly allows it. Respect existing dark/light token system and canonical tab icons.

Pre-flight reasoning (must do before coding)
1) Locate the ticket definition and read its YAML meta (id, status, depends, lastReviewed, owner).
2) Check backlog rows and traceability:
   - Identify AC IDs and the tests you will update/add.
   - Ensure Status is not DONE and Owner is unassigned or yourself.
3) Validate dependencies:
   - Confirm items in `depends` are DONE or not blocking. If blocked, propose a smaller slice or exit.
4) Conflict scan:
   - List files you expect to modify and inspect recent changes to avoid overlap.
   - If overlap with a partial/in‑progress ticket, propose coordination or pick another ticket.
5) Acceptance alignment:
   - Map your changes to specific AC IDs and named tests.
   - State explicit success criteria (code paths, tests, docs updates).

Execution plan
- Produce a short, file-scoped plan (≤8 steps), each step linking to files you’ll touch.
- Include how you will update docs and traceability at the end.

Guardrails
- Keep changes surgical and token/tab‑aware.
- Add/update tests near the changed code.
- Enforce 300s timeout when running `npm test` (see `AGENTS.md`).

Deliverables
- Code changes with paths, updated tests that pass, updates to backlog/traceability and acceptance docs if needed, and a final summary mapping diff → ticket → AC IDs → tests.

Start
1) Print your pre-flight reasoning citing the exact roadmap/backlog lines you’re using (file:line).
2) Print your execution plan.
3) Await confirmation or continue if explicitly allowed.

---

## CLI helper (optional)

You can generate a pre-filled prompt from backlog/roadmap using:

```
node scripts/generate-agent-prompt.mjs <TICKET_ID> [--goal "Short goal"] [--out prompt.txt]
```

The script pulls:
- Backlog row from `docs/backlog/product-backlog.csv` (status/owner/depends/AC/tests).
- Ticket meta YAML from roadmap docs.
- It prints a ready-to-use prompt to stdout or the `--out` file.

