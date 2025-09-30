#!/usr/bin/env node
// Generate an agent prompt for a ticket by reading backlog CSV and roadmap meta blocks.
// Usage: node scripts/generate-agent-prompt.mjs <TICKET_ID> [--goal "Short goal"] [--out prompt.txt]

import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');

function readFile(p) {
  return fs.readFileSync(p, 'utf8');
}

// Minimal CSV parser supporting quoted fields with embedded commas
function parseCsv(text) {
  const rows = [];
  let i = 0;
  let field = '';
  let row = [];
  let inQuotes = false;
  while (i < text.length) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i += 2;
          continue;
        } else {
          inQuotes = false;
          i++;
          continue;
        }
      } else {
        field += c;
        i++;
        continue;
      }
    }
    if (c === '"') {
      inQuotes = true;
      i++;
      continue;
    }
    if (c === ',') {
      row.push(field);
      field = '';
      i++;
      continue;
    }
    if (c === '\n' || c === '\r') {
      // commit row only if anything present or previous row started
      if (field.length || row.length) {
        row.push(field);
        rows.push(row);
      }
      field = '';
      row = [];
      // consume CRLF
      if (c === '\r' && text[i + 1] === '\n') i++;
      i++;
      continue;
    }
    field += c;
    i++;
  }
  if (field.length || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

function rowsToObjects(rows) {
  const header = rows[0];
  return rows.slice(1).map(r => {
    const obj = {};
    header.forEach((h, idx) => (obj[h.trim()] = (r[idx] ?? '').trim()))
    return obj;
  });
}

function scanYamlMetaBlocks(mdText) {
  const blocks = [];
  const regex = /```yaml\s+([\s\S]*?)```/g;
  let m;
  while ((m = regex.exec(mdText))) {
    blocks.push(m[1]);
  }
  return blocks.map(parseSimpleYaml);
}

function parseSimpleYaml(yamlText) {
  // Handle simple key: value and key: [a, b] under a top-level 'meta:'
  const obj = {};
  let inMeta = false;
  yamlText.split(/\r?\n/).forEach(line => {
    const l = line.trim();
    if (!l) return;
    if (l.startsWith('meta:')) { inMeta = true; return; }
    if (!inMeta) return;
    const mArr = l.match(/^(\w+):\s*(.*)$/);
    if (!mArr) return;
    const key = mArr[1];
    let val = mArr[2];
    if (val.startsWith('[') && val.endsWith(']')) {
      val = val.slice(1, -1).split(',').map(s => s.trim()).filter(Boolean);
    } else {
      val = val.replace(/^"|"$/g, '');
    }
    obj[key] = val;
  });
  return obj;
}

function findTicketMeta(ticketId) {
  const files = [
    path.join(ROOT, 'docs/roadmap/phase-1-backend-plan.md'),
    path.join(ROOT, 'docs/roadmap/phase-3-admin-mvp-tickets.md'),
    path.join(ROOT, 'docs/roadmap/phases-4-12-ticket-breakdown.md'),
  ];
  for (const f of files) {
    if (!fs.existsSync(f)) continue;
    const txt = readFile(f);
    const metas = scanYamlMetaBlocks(txt);
    for (const meta of metas) {
      if ((meta.id || '').toUpperCase() === ticketId.toUpperCase()) {
        return { file: f, meta };
      }
    }
  }
  return null;
}

function loadBacklogRow(ticketId) {
  const csvPath = path.join(ROOT, 'docs/backlog/product-backlog.csv');
  const txt = readFile(csvPath);
  const rows = parseCsv(txt);
  const objs = rowsToObjects(rows);
  const row = objs.find(o => (o['Story ID'] || '').toUpperCase() === ticketId.toUpperCase());
  return row || null;
}

function loadTrace(ticketId) {
  const csvPath = path.join(ROOT, 'docs/backlog/ac-traceability.csv');
  if (!fs.existsSync(csvPath)) return null;
  const txt = readFile(csvPath);
  const rows = parseCsv(txt);
  const objs = rowsToObjects(rows);
  return objs.find(o => (o['Story ID'] || '').toUpperCase() === ticketId.toUpperCase()) || null;
}

function buildPrompt({ ticketId, goal, meta, backlog, trace }) {
  const owner = meta?.owner || backlog?.Owner || 'TBD';
  const status = meta?.status || backlog?.Status || 'planned';
  const depends = Array.isArray(meta?.depends) ? meta.depends.join(', ') : (backlog?.Depends || '');
  const lastReviewed = meta?.lastReviewed || backlog?.LastReviewed || '';
  const acIds = backlog?.['AC IDs'] || trace?.['AC IDs'] || '';
  const tests = backlog?.Tests || trace?.Tests || '';
  const title = backlog?.['Story Title'] || '';
  const description = backlog?.Description || '';
  const phase = backlog?.Phase || '';

  return `Project context\n- Repo path: /home/farouq/Development/albunyaantube\n- Backlog: docs/backlog/product-backlog.csv (row for ${ticketId})\n- Roadmap meta: ${meta?.file || 'N/A'}\n- ACs: docs/acceptance/criteria.md\n- Traceability: docs/backlog/ac-traceability.csv\n\nYour assignment\n- Ticket ID: ${ticketId}\n- Title: ${title}\n- Phase: ${phase}\n- Goal: ${goal || '[fill in short business outcome]'}\n- Status/Owner/Depends/LastReviewed: ${status}/${owner}/${depends || '—'}/${lastReviewed || '—'}\n- AC IDs: ${acIds || '—'}\n- Tests to touch/add: ${tests || '—'}\n\nConstraints\n- Do not alter layouts/spacing/copy unless the ticket allows it.\n- Respect dark/light token system and canonical tab icons.\n\nPre-flight reasoning (must do before coding)\n1) Locate the ticket meta and confirm status/depends/owner.\n2) Verify backlog row fields and AC/test mapping. Ensure not DONE and no owner conflict.\n3) Validate dependencies are DONE or non-blocking; otherwise propose a smaller slice or exit.\n4) Conflict scan: list files you expect to modify; check recent changes to avoid overlap.\n5) Map changes to AC IDs and named tests; define success criteria (code paths, tests, docs updates).\n\nExecution plan\n- Provide ≤8 file-scoped steps; include docs + traceability updates at end.\n\nGuardrails\n- Keep changes surgical and token/tab-aware; add/update tests near changed code.\n- Enforce 300s timeout for \`npm test\` (see AGENTS.md).\n\nDeliverables\n- Code diff with paths, updated tests that pass, backlog/traceability/docs updates, and a final summary mapping diff → ticket → AC IDs → tests.\n\nStart\n1) Print your pre-flight reasoning with file:line citations to roadmap/backlog.\n2) Print your execution plan.\n3) Await confirmation or continue if explicitly allowed.`;
}

// CLI entry
const args = process.argv.slice(2);
if (!args.length) {
  console.error('Usage: node scripts/generate-agent-prompt.mjs <TICKET_ID> [--goal "Short goal"] [--out prompt.txt]');
  process.exit(1);
}
const ticketId = args[0];
const goalFlagIdx = args.indexOf('--goal');
const outIdx = args.indexOf('--out');
const goal = goalFlagIdx !== -1 ? args[goalFlagIdx + 1] : '';
const out = outIdx !== -1 ? args[outIdx + 1] : '';

const backlog = loadBacklogRow(ticketId);
const metaHit = findTicketMeta(ticketId);
const trace = loadTrace(ticketId);

if (!backlog && !metaHit) {
  console.error(`Ticket not found in backlog or roadmap: ${ticketId}`);
  process.exit(2);
}

const prompt = buildPrompt({ ticketId, goal, meta: metaHit, backlog, trace });

if (out) {
  fs.writeFileSync(path.resolve(out), prompt, 'utf8');
  console.log(`Prompt written to ${out}`);
} else {
  process.stdout.write(prompt + '\n');
}

