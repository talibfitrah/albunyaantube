# Admin Bulk URL Submission — Design Spec

> **Status:** Draft 2026-05-22, awaiting user review
> **Owner:** Admin tooling team
> **Predecessor:** Plan F (Admin User Management, merged in `develop`) and Plan G (Profile Edit + Suggest-Search, PR #17)
> **Branch convention:** lands on `develop` per project branching policy

---

## 1. Goal

Let admins and moderators submit many YouTube URLs at once to the registry approval queue, instead of searching and clicking "Add for Approval" one URL at a time.

Two input methods:
1. **Paste** comma- or newline-separated URLs into a textarea.
2. **Upload** a CSV, Excel (`.xlsx`/`.xls`), or JSON file containing URLs.

Backend auto-detects each URL's content type (CHANNEL / PLAYLIST / VIDEO), fetches metadata via NewPipeExtractor, flags duplicates and errors per-row, then writes valid rows to Firestore via the same registry path as the existing single-add flow. Result: same approval queue, same downstream review UX, much faster intake.

---

## 2. Locked Decisions

Each prefixed `BULK#`, referenced by the implementation plan.

**BULK1. Category assignment:** Hybrid — admin picks a batch-default category set at INPUT step (required, ≥1 category). Preview-table rows inherit the default; admin can override per-row via a popover editor. **Resolved `categoryIds` per row** is defined as: per-row override if the admin has explicitly edited that row; otherwise the batch default. This resolved set is what the frontend ships in the `bulk/submit` body; the backend doesn't recompute it.

**BULK2. UI placement:** Tabs on the existing `ContentSearchView` — `"Search"` (existing UX) + `"Bulk Submit"` (new). Per-tab state preserved across tab switches.

**BULK3. Supported types & enum mapping:** Channels, playlists, standard videos, live videos. Shorts and everything else (Music tracks, community posts, channel-tab URLs, etc.) → rejected as `UNSUPPORTED_*`. Backend `YouTubeContentType` enum stays at `CHANNEL / PLAYLIST / VIDEO` (no schema churn). Live videos submit as `VIDEO` + a new `videoType` flag on the `Video` model (`STANDARD | LIVE`).

**BULK4. Processing model:** Preview-time NewPipe fetch (admin sees full metadata + errors *before* committing categories), synchronous, hard cap **25 URLs per batch**. No async job infra. 5-worker bounded thread pool fans out the NewPipe calls in parallel — worst case ~10s for a full 25-URL batch.

**BULK5. Duplicates & error rows:** Duplicate against an existing PENDING/APPROVED registry doc → row marked `DUPLICATE`, skipped at submit. Duplicate against a REJECTED doc → row marked `DUPLICATE_REJECTED` with a warning chip; admin can keep it (creates a fresh PENDING doc, preserves rejection audit trail) or remove it. Submit always proceeds with valid rows only — error rows are skipped, summary returns `{added, skipped, failed}` counts.

**BULK6. Architecture — two endpoints, not one:** `POST /api/admin/registry/bulk/preview` returns row-by-row preview with NewPipe metadata; `POST /api/admin/registry/bulk/submit` takes the OK rows back (plus per-row resolved `categoryIds`) and writes Firestore docs. Submit does not re-fetch NewPipe metadata — frontend round-trips the preview's metadata back in the submit body. Admin tampering with metadata mid-round-trip is a low-risk concern that's caught downstream in the approval review UI.

**BULK7. Role gating:** Both endpoints `@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")`. Moderators → submitted rows always `status=PENDING`. Admins → `status` defaults to PENDING, optional `status=APPROVED` body field bypasses the queue (mirrors existing single-add behavior in `RegistryController`).

**BULK8. File parsing — frontend, not backend:** No multipart upload to the backend. Frontend parses CSV (`papaparse`), Excel (`xlsx` / SheetJS, lazy-loaded via dynamic import to keep main bundle slim), and JSON (`JSON.parse` + Zod schema). Output is always a `string[]` of URLs sent to the preview endpoint. 1 MB file size cap, enforced client-side.

---

## 3. Backend Architecture

### 3.1 New endpoints on `RegistryController`

```
POST /api/admin/registry/bulk/preview
POST /api/admin/registry/bulk/submit
```

Both inherit the existing `@PreAuthorize` gate and the `sanitizeSubmitterNote()` helper for any free-text inputs.

### 3.2 DTOs

`backend/src/main/java/com/albunyaan/tube/dto/registry/`:

**BulkPreviewRequest**
```java
record BulkPreviewRequest(
  @Size(min=1, max=25) List<@NotBlank String> urls,
  @Size(min=1)         List<@NotBlank String> defaultCategoryIds
) {}
```

**BulkPreviewResponse**
```java
record BulkPreviewResponse(List<PreviewRow> rows) {}

record PreviewRow(
  int               rowIndex,
  String            originalUrl,
  String            normalizedUrl,
  YouTubeContentType detectedType,   // null if ERROR
  VideoType         videoType,        // STANDARD | LIVE — only when detectedType=VIDEO
  PreviewMetadata   metadata,         // null if not OK
  RowStatus         status,           // OK | DUPLICATE | DUPLICATE_REJECTED | ERROR
  String            duplicateOf,      // existing doc ID, if DUPLICATE*
  RegistryStatus    duplicateStatus,  // PENDING | APPROVED | REJECTED, if DUPLICATE*
  PreviewError      error             // {code, messageKey}, if ERROR
) {}

record PreviewMetadata(
  String  youtubeId,
  String  title,
  String  thumbnailUrl,
  String  channelName,    // for VIDEO, PLAYLIST
  String  channelId,      // for VIDEO, PLAYLIST
  Long    subscribers,    // for CHANNEL
  Long    itemCount,      // for PLAYLIST
  Long    durationSeconds,// for VIDEO (null for LIVE in-progress)
  Long    viewCount       // for VIDEO
) {}

record PreviewError(String code, String messageKey) {}
```

**BulkSubmitRequest**
```java
record BulkSubmitRequest(
  @Size(min=1, max=25) List<SubmitRow> rows,
  RegistryStatus       status   // optional, defaults to PENDING; admins only for APPROVED
) {}

record SubmitRow(
  String             originalUrl,
  YouTubeContentType detectedType,
  VideoType          videoType,            // nullable except when detectedType=VIDEO
  PreviewMetadata    metadata,
  @Size(min=1) List<String> categoryIds    // resolved per-row (default + overrides)
) {}
```

**BulkSubmitResponse**
```java
record BulkSubmitResponse(
  int totalSubmitted,
  int added,
  int failed,
  List<SubmitResult> results
) {}

record SubmitResult(
  int     rowIndex,
  String  originalUrl,
  String  registryId,   // null if FAILED
  String  status,       // ADDED | FAILED
  String  errorCode     // null unless FAILED
) {}
```

### 3.3 New service classes

`backend/src/main/java/com/albunyaan/tube/service/`:

**YouTubeUrlParser** — regex-only, no network. Parses any input string and classifies it:
- `/watch?v=ID` and `youtu.be/ID` → `(VIDEO, ID, isShort=false)`
- `/shorts/ID` → `(VIDEO, ID, isShort=true)` → rejected as `UNSUPPORTED_SHORTS`
- `/live/ID` → `(VIDEO, ID, isShort=false)` — type confirmed via NewPipe `StreamType` later
- `/playlist?list=ID` → `(PLAYLIST, ID, …)`
- `/channel/UC...` → `(CHANNEL, UC..., …)`
- `/@handle`, `/c/name`, `/user/name` → `(CHANNEL, resolved-to-UC...)` — NewPipe handles the handle→ID resolution at fetch time
- `/results?search_query=…`, `/post/…`, music.youtube.com, etc. → `UNSUPPORTED_TYPE`
- Non-YouTube hosts → `NOT_YOUTUBE_URL`

Returns: `{type, youtubeId, isShort, normalizedUrl, errorCode?}`.

**BulkSubmissionService** — the orchestrator.
- `preview(BulkPreviewRequest)` → for each URL: parse → dedupe-check → NewPipe fetch (in 5-worker pool via `CompletableFuture`) → assemble `PreviewRow`. Returns `BulkPreviewResponse`.
- `submit(BulkSubmitRequest, principal)` → for each row, delegate to `RegistrySubmissionWriter.write{Channel|Playlist|Video}()` (see refactor below). Best-effort: per-row try/catch, aggregates results. Applies role-based `status` normalization (BULK7).

**RegistrySubmissionWriter** — refactor. Extract the per-type Firestore write logic from the existing `RegistryController.addChannel / addPlaylist / addVideo` methods into a shared `RegistrySubmissionWriter` service with three methods:
- `writeChannel(metadata, categoryIds, status, principal): String`
- `writePlaylist(metadata, categoryIds, status, principal): String`
- `writeVideo(metadata, videoType, categoryIds, status, principal): String`

Both the existing single-add endpoints AND the new bulk-submit endpoint route through this writer, so there's exactly one definition of "what does it mean to add a doc to the registry". Returns the new Firestore doc ID.

**RegistryDuplicateChecker** — extracted from inline logic. Given `(type, youtubeId)`, returns `Optional<{existingDocId, status}>` from Firestore. Caches per-batch (don't re-query the same youtubeId twice in one preview call).

**YouTubeGateway** (existing) — extended with `fetchByDetectedType(detectedType, youtubeId, normalizedUrl): PreviewMetadata` that wraps the existing `ChannelInfo.getInfo()` / `PlaylistInfo.getInfo()` / `StreamInfo.getInfo()` calls and maps NewPipe exceptions to our `PreviewError` codes.

### 3.4 NewPipe exception → error code mapping

| NewPipe exception                          | error code                |
|--------------------------------------------|---------------------------|
| `ContentNotAvailableException`             | `CONTENT_NOT_AVAILABLE`   |
| `PrivateContentException`                  | `PRIVATE_CONTENT`         |
| `AgeRestrictedContentException`            | `AGE_RESTRICTED`          |
| `GeographicRestrictionException`           | `GEO_RESTRICTED`          |
| `AccountTerminatedException`               | `CHANNEL_TERMINATED`      |
| `ParsingException` / other `ExtractionException` | `NEWPIPE_PARSING_ERROR` |
| `IOException`                              | `NETWORK_ERROR`           |

### 3.5 Data model change

`backend/src/main/java/com/albunyaan/tube/model/Video.java`:

Add `VideoType videoType` field. Enum:

```java
public enum VideoType { STANDARD, LIVE }
```

Firestore: optional field; existing docs read as `null` → frontend treats `null` as `STANDARD`. **No migration script needed.** Going forward, both the existing single-add VIDEO endpoint and the new bulk submit endpoint populate this field (the single-add change is a small follow-up in the same PR).

---

## 4. Frontend Architecture

### 4.1 Component tree

```
ContentSearchView.vue                      ← wrapped in <BulkTabHost> with two tabs
├─ ContentSearchTab.vue                    ← existing search UX, extracted into its own component
└─ BulkSubmissionTab.vue                   ← NEW state-machine container (INPUT | PREVIEW | LOADING | RESULT)
   ├─ BulkInputView.vue
   │  ├─ BulkUrlPasteField.vue             ← textarea + live URL count
   │  ├─ BulkFileDropzone.vue              ← drag/drop, click-to-pick, accepts .csv .xlsx .xls .json
   │  ├─ BulkDefaultCategoriesPicker.vue   ← reuses CategoryChipMultiSelect
   │  └─ BulkFormatHelpButton.vue          ← ⓘ trigger
   ├─ BulkPreviewTable.vue                 ← row-by-row table
   │  ├─ BulkPreviewRow.vue                ← thumbnail / type chip / title / status / categories / ×
   │  └─ BulkRowCategoryEditor.vue         ← popover for per-row override
   ├─ BulkResultSummary.vue                ← added / skipped / errors counts + lists
   └─ BulkFormatHelpModal.vue              ← CSV / Excel / JSON tabs + sample downloads
```

All new files under `frontend/src/components/contentSearch/bulk/`.

### 4.2 State management

New Pinia store `frontend/src/stores/bulkSubmissionStore.ts`:

```ts
state: {
  phase: 'INPUT' | 'PREVIEW' | 'LOADING' | 'RESULT',
  pastedUrls: string,
  uploadedFileName: string | null,
  parsedUrls: string[],
  defaultCategoryIds: string[],
  previewRows: BulkPreviewRow[],   // mutable: admin removes rows / edits per-row categoryIds
  submitResult: BulkSubmitResponse | null,
  error: string | null,
}
actions: parseInput, runPreview, runSubmit, setRowCategories, removeRow, reset
```

Pinia (not local component refs) so flipping to the `Search` tab and back preserves an in-flight batch.

### 4.3 Service module

`frontend/src/services/bulkSubmissionService.ts`:
- `previewBulk(urls, defaultCategoryIds): Promise<BulkPreviewResponse>` → POST `/api/admin/registry/bulk/preview`
- `submitBulk(rows, status?): Promise<BulkSubmitResponse>` → POST `/api/admin/registry/bulk/submit`

DTO types auto-generated via `./scripts/generate-openapi-dtos.sh` once the backend lands.

### 4.4 File parsing strategy

| Format | Library         | Bundling                                  |
|--------|-----------------|-------------------------------------------|
| CSV    | `papaparse`     | static import, ~45 KB                     |
| Excel  | `xlsx` (SheetJS)| **lazy via `await import('xlsx')`**, ~600 KB chunk loaded only when admin uses Excel upload |
| JSON   | `JSON.parse`    | native, Zod schema validation             |

All parsers normalize to a `string[]` of URLs passed to the store's `parseInput()` action. Each parser enforces:
- File size ≤ 1 MB
- Row/array length ≤ 25
- For CSV/Excel: exactly one column with header `URL` (case-insensitive)
- For JSON: shape `{ "urls": string[] }`

### 4.5 i18n

All new keys under `contentSearch.bulk.*` in `frontend/src/locales/messages.ts` for **en, ar, nl** in one commit:
- `tabLabel` — tab title
- `input.*` — paste field, dropzone, default-categories label, parse button, URL count
- `preview.*` — column headers, status badges (OK / DUPLICATE / DUPLICATE_REJECTED / ERROR), buttons
- `result.*` — summary copy, "Submit another batch" / "Go to Pending Approvals"
- `formatHelp.*` — modal title, CSV/Excel/JSON tab labels, spec body for each format, "Download sample" link
- `errors.{ERROR_CODE}` — one key per backend error code (`UNSUPPORTED_SHORTS`, `NOT_YOUTUBE_URL`, `CONTENT_NOT_AVAILABLE`, `PRIVATE_CONTENT`, `AGE_RESTRICTED`, `GEO_RESTRICTED`, `CHANNEL_TERMINATED`, `NEWPIPE_PARSING_ERROR`, `NETWORK_ERROR`, `DUPLICATE`, `DUPLICATE_REJECTED`, `UNSUPPORTED_TYPE`)

RTL-safe — Arabic locale uses CSS logical properties; URL/youtubeId cells get `dir="ltr"` to prevent reversed display.

---

## 5. UX States

### 5.1 INPUT state

Two side-by-side input options, plus a required default-categories picker, plus a Parse button.

- **Paste box** — textarea with live counter `"12 / 25 URLs"`. Splits on commas, newlines, and whitespace.
- **File upload** — drop zone accepting `.csv .xlsx .xls .json`. Tiny ⓘ "Format help" button next to the dropzone label opens `BulkFormatHelpModal`.
- **Default categories** — multi-select chips, required (≥1).
- **Parse & Preview button** — disabled until `parsedUrls.length ∈ [1, 25]` AND `defaultCategoryIds.length ≥ 1`.

### 5.2 PREVIEW state

Full-width table with up to 25 rows. Columns: Row # · Thumbnail · Detected Type chip (`CHANNEL` / `PLAYLIST` / `VIDEO` / `LIVE`) · Title · Channel (where applicable) · Status badge · Categories chips (inherits default, click to edit per-row via popover) · Remove (×).

Header bar above the table shows `"{n} valid · {m} duplicates skipped · {k} errors"`. Buttons: `"Submit valid rows"`, `"Back to input"` link.

### 5.3 RESULT state

After Submit completes:
- Big number `"{added} channels/playlists/videos added to approval queue"`
- Collapsible `"Skipped ({n})"` and `"Errors ({k})"` lists with per-row reasons
- Buttons: `"Submit another batch"` (resets store, returns to INPUT), `"Go to Pending Approvals"` (router-link)

### 5.4 Format help modal

Tabbed view: `"CSV"`, `"Excel"`, `"JSON"`. Each tab shows:
- The spec for that format (one column with header `URL`, one URL per row, ≤ 25 rows for CSV/Excel; `{ "urls": string[] }`, ≤ 25 items for JSON)
- A "Download sample" link to a tiny static asset under `frontend/public/samples/` (`sample-bulk-urls.csv`, `sample-bulk-urls.xlsx`, `sample-bulk-urls.json`)

### 5.5 Cross-cutting

- Sticky toast for parse errors (`"Row 4: not a valid YouTube URL"`)
- Vue Router `beforeRouteLeave` warns if PREVIEW phase has unsubmitted rows
- Tab switch warns if INPUT has unsaved URLs or PREVIEW is non-empty
- RTL: tabs flip, table columns reverse via CSS logical properties

---

## 6. Edge Cases & Error Handling

1. **REJECTED resubmission** (BULK5): preview marks the row `DUPLICATE_REJECTED` with a warning chip; admin can keep or remove. If submitted, creates a fresh PENDING doc; the original rejected doc stays untouched.

2. **NewPipe failure mid-batch**: per-row try/catch inside the worker. One row failing returns its error code; the rest of the batch continues. Worker thread pool isolates failures.

3. **Concurrent admin submits with overlapping URLs**: preview-then-submit is race-prone but worst case is a duplicate Firestore doc — caught during approval review. No locking.

4. **Mid-submit partial failure**: bulk/submit writes 12 of 18 then Firestore errors on doc 13. Response returns `{added: 12, failed: 6, ...}` with per-row error codes. No rollback (already-written docs stay).

5. **Malformed / oversized files**: 1 MB cap enforced **client-side only** — frontend parses files locally, the backend never sees the raw file (no multipart endpoint). Spring's default JSON request body limit (10 MB) is more than adequate for 25 URLs × ~200 chars ≈ 5 KB. Misnamed columns / wrong JSON shape → parse error toast showing the issue.

6. **Excel macros / formulas**: SheetJS reads cells as text only; formula cells get displayed value or empty string. Safe — no formula evaluation.

7. **Whitespace / encoding**: URLs trimmed, BOM-stripped. Reuses `RegistryController.sanitizeSubmitterNote()` zero-width / bidi-override sanitization for the URL string itself.

8. **Empty file / header-only**: `parsedUrls = []`, INPUT shows `"0 / 25 URLs"`, Parse button stays disabled. No backend call.

9. **Live stream that becomes a VOD mid-batch**: `videoType` is snapshotted from NewPipe at preview time. Doesn't update if the stream ends. Accepted limitation — admin can re-add the VOD if desired (de-duplicated automatically).

10. **RTL (Arabic admin)**: column order flips via CSS `direction: rtl` on the table; URL/youtubeId cells stay LTR via `dir="ltr"`; logical-property spacing throughout.

---

## 7. Testing

### 7.1 Backend

- `YouTubeUrlParserTest` — exhaustive URL flavor tests:
  - `/watch?v=ID`, `youtu.be/ID`, `youtube.com/watch?...&v=ID&...`
  - `/shorts/ID` → rejected `UNSUPPORTED_SHORTS`
  - `/live/ID` (typed as VIDEO, classification confirmed by NewPipe later)
  - `/playlist?list=ID`
  - `/channel/UC...`, `/@handle`, `/c/name`, `/user/name`
  - `m.youtube.com/...` (mobile prefix)
  - `music.youtube.com/...` → `UNSUPPORTED_TYPE`
  - Non-YouTube hosts → `NOT_YOUTUBE_URL`
  - Whitespace, BOM, zero-width-char-injected URLs (sanitization)
- `BulkSubmissionServiceTest` — unit, mocked NewPipe + Firestore:
  - OK rows for each type
  - DUPLICATE / DUPLICATE_REJECTED detection
  - Each `PreviewError` code
  - Concurrent fanout via `CompletableFuture`
- `RegistryDuplicateCheckerTest` — unit with Firestore stub
- `RegistryControllerIntegrationTest` — `@Pintegration=true`, Firebase emulator:
  - Both endpoints with ADMIN and MODERATOR principals
  - Role-based status normalization (BULK7)
  - Reuses `BaseIntegrationTest` cache-clearing hook

### 7.2 Frontend

- Vitest unit tests for `bulkSubmissionStore` (every action)
- Component tests:
  - `BulkInputView` — URL count math, Parse button enablement gate
  - `BulkPreviewTable` — status badges, per-row category override
  - `BulkResultSummary` — counts, collapsible lists
- File parser tests:
  - `papaparse` CSV happy path + malformed (extra column, missing header, oversized)
  - `xlsx` mock — happy path + missing column
  - JSON Zod schema validation — happy + wrong shape

### 7.3 Manual QA

- Bilingual smoke (en + ar, RTL)
- All four supported URL types in one batch (channel + playlist + standard video + live video)
- One row of each error code in a single batch
- Moderator role test (status forced to PENDING) + admin role test (status=APPROVED option)
- 25-URL stress test (latency, layout stability)

---

## 8. Rollout

- No feature flag — merges to `develop` per project branching policy
- Backend ships first; frontend follows after `./scripts/generate-openapi-dtos.sh` regenerates DTO types
- Existing single-add VIDEO endpoint gets the small `videoType` field addition in the same backend PR
- Sample files (`sample-bulk-urls.{csv,xlsx,json}`) committed under `frontend/public/samples/`
- Standard 7-stage review pipeline runs on the bulk PR before merge to `develop`

---

## 9. Observability

Spring log line per bulk preview / submit call:

```
INFO  BulkSubmissionService — bulk-preview adminUid={uid} rowCount={n} okCount={n} errorCount={n} durationMs={ms}
INFO  BulkSubmissionService — bulk-submit  adminUid={uid} rowCount={n} added={n} failed={n} durationMs={ms}
```

No new metrics infra. Existing log shipping is sufficient for the initial release; if usage justifies, add a `bulk_submission_total{result}` counter in a follow-up.

---

## 10. Out of Scope (deliberately)

- Async background jobs / progress streaming (BULK4: 25-URL sync is enough)
- Server-side multipart file uploads (BULK8: frontend handles parsing)
- Shorts as a content variant (BULK3: rejected outright)
- Per-row admin status overrides (one global `status` per batch is enough)
- Bulk *edit* or bulk *delete* on the existing approval queue (separate feature)
- Bulk *approval* of already-PENDING items (separate feature)
- CLI / API client tooling beyond the admin UI (no third-party consumers)

---

## Appendix A — File format specs (canonical)

### CSV (`.csv`)
```
URL
https://www.youtube.com/watch?v=dQw4w9WgXcQ
https://www.youtube.com/playlist?list=PLAYLISTID
https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx
https://www.youtube.com/live/LIVESTREAMID
```
- One column with header `URL` (case-insensitive)
- One URL per row underneath
- Max 25 rows
- UTF-8, BOM tolerated

### Excel (`.xlsx`, `.xls`)
Same as CSV but in a spreadsheet. First sheet only. Header cell A1 must be `URL`. URLs in A2..A26.

### JSON (`.json`)
```json
{
  "urls": [
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "https://www.youtube.com/playlist?list=PLAYLISTID",
    "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
    "https://www.youtube.com/live/LIVESTREAMID"
  ]
}
```
- Single top-level object with key `urls`
- `urls` is an array of strings, length ∈ [1, 25]

---

## Appendix B — Error code reference

| Code                      | Cause                                                        | Submittable? |
|---------------------------|--------------------------------------------------------------|--------------|
| `UNSUPPORTED_SHORTS`      | `/shorts/...` URL                                            | No           |
| `UNSUPPORTED_TYPE`        | Music tracks, community posts, search results, etc.          | No           |
| `NOT_YOUTUBE_URL`         | Non-YouTube host                                             | No           |
| `CONTENT_NOT_AVAILABLE`   | Deleted / removed content                                    | No           |
| `PRIVATE_CONTENT`         | Private video / playlist                                     | No           |
| `AGE_RESTRICTED`          | Age-gated content                                            | No           |
| `GEO_RESTRICTED`          | Region-blocked content                                       | No           |
| `CHANNEL_TERMINATED`      | Channel terminated by YouTube                                | No           |
| `NEWPIPE_PARSING_ERROR`   | NewPipe extractor parsing failure                            | No           |
| `NETWORK_ERROR`           | IO failure during NewPipe fetch                              | No (retryable on resubmit) |
| `DUPLICATE`               | Already in registry as PENDING or APPROVED                   | No (skipped silently) |
| `DUPLICATE_REJECTED`      | Already in registry as REJECTED                              | Yes (admin confirms keep) |
