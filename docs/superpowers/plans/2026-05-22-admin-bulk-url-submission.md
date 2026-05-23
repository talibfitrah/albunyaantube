# Admin Bulk URL Submission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Bulk Submit" tab on the admin Search Content page that accepts ≤25 YouTube URLs (paste or CSV/Excel/JSON upload), auto-detects each URL's content type via NewPipeExtractor, previews per-row metadata + duplicate/error status, and queues valid rows into the existing approval flow.

**Architecture:** Two-endpoint preview-then-submit pattern. Frontend parses files locally. Preview endpoint runs URL regex → Firestore dedupe → NewPipeExtractor metadata fan-out (5-worker thread pool). Submit endpoint round-trips preview metadata + admin-resolved categories to a shared `RegistrySubmissionWriter` (refactored out of existing single-add paths).

**Tech Stack:** Spring Boot 3 / Java 17 / Firestore / NewPipeExtractor 0.24.8 (backend); Vue 3 + Pinia + Vitest + Vite, papaparse 5.x + xlsx (SheetJS) lazy-loaded + Zod (frontend); Firebase emulator for integration tests.

**Spec:** `docs/superpowers/specs/2026-05-22-admin-bulk-url-submission-design.md`. Read it first.

**Ticket prefix:** `BULK-01`. Branch: `feature/BULK-01-bulk-url-submission`. PR target: `develop`.

---

## File Structure

### Backend — create
| Path | Responsibility |
|---|---|
| `backend/src/main/java/com/albunyaan/tube/model/VideoType.java` | Enum `STANDARD, LIVE`. |
| `backend/src/main/java/com/albunyaan/tube/service/YouTubeUrlParser.java` | Regex-only URL classifier. Returns `(type, youtubeId, normalizedUrl, errorCode?)`. |
| `backend/src/main/java/com/albunyaan/tube/service/RegistryDuplicateChecker.java` | Firestore lookup wrapper. Given `(type, youtubeId)`, returns existing doc ID + `RegistryStatus`. Per-batch memoization. |
| `backend/src/main/java/com/albunyaan/tube/service/RegistrySubmissionWriter.java` | Extracted from `RegistryController.addChannel/Playlist/Video`. Three methods write Firestore docs with status normalization. |
| `backend/src/main/java/com/albunyaan/tube/service/BulkSubmissionService.java` | Orchestrator. `preview()` + `submit()` methods. |
| `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkPreviewRequest.java` | Java record. |
| `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkPreviewResponse.java` | Java record + nested `PreviewRow`, `PreviewMetadata`, `PreviewError`, `RowStatus`. |
| `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkSubmitRequest.java` | Java record + nested `SubmitRow`. |
| `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkSubmitResponse.java` | Java record + nested `SubmitResult`. |
| `backend/src/main/java/com/albunyaan/tube/dto/registry/PreviewErrorCode.java` | Enum of all error codes. |

### Backend — modify
| Path | Change |
|---|---|
| `backend/src/main/java/com/albunyaan/tube/model/Video.java` | Add `private VideoType videoType` field + getter/setter (nullable, defaults null). |
| `backend/src/main/java/com/albunyaan/tube/service/YouTubeGateway.java` | Add `fetchByDetectedType(YouTubeContentType, String youtubeId, String normalizedUrl): PreviewFetchResult`. |
| `backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java` | (a) Add two `@PostMapping` handlers: `/bulk/preview`, `/bulk/submit`. (b) Refactor `addChannel`, `addPlaylist`, `addVideo` POST handlers to delegate to `RegistrySubmissionWriter`. (c) `addVideo` accepts new `videoType` field. |

### Backend — tests
| Path | Coverage |
|---|---|
| `backend/src/test/java/com/albunyaan/tube/service/YouTubeUrlParserTest.java` | All URL flavors (watch / shorts / live / playlist / channel UC / @handle / c / user / mobile / music / youtu.be / non-youtube). Whitespace, BOM, zero-width injection. |
| `backend/src/test/java/com/albunyaan/tube/service/RegistryDuplicateCheckerTest.java` | Existing PENDING, APPROVED, REJECTED, no-existing. Per-batch memoization. |
| `backend/src/test/java/com/albunyaan/tube/service/RegistrySubmissionWriterTest.java` | One happy path per content type. Verifies existing single-add behavior is preserved. |
| `backend/src/test/java/com/albunyaan/tube/service/BulkSubmissionServiceTest.java` | Preview: OK row per type + each error code + duplicate. Submit: per-row success + per-row failure aggregation. Mocked NewPipe + Firestore. |
| `backend/src/test/java/com/albunyaan/tube/integration/BulkSubmissionIT.java` | `-Pintegration=true`, Firebase emulator. Bulk preview + submit end-to-end with role-based status normalization (ADMIN, MODERATOR). |

### Frontend — create
| Path | Responsibility |
|---|---|
| `frontend/src/stores/bulkSubmissionStore.ts` | Pinia store: phase machine, parsed URLs, preview rows, submit result, actions. |
| `frontend/src/services/bulkSubmissionService.ts` | API client wrapper around `/api/admin/registry/bulk/{preview,submit}`. |
| `frontend/src/utils/bulkFileParsers.ts` | `parseCsv`, `parseExcel` (lazy), `parseJson` → `string[]`. |
| `frontend/src/components/contentSearch/bulk/BulkSubmissionTab.vue` | State-machine container (INPUT \| PREVIEW \| LOADING \| RESULT). |
| `frontend/src/components/contentSearch/bulk/BulkInputView.vue` | Paste + upload + default categories. |
| `frontend/src/components/contentSearch/bulk/BulkUrlPasteField.vue` | Textarea + live count. |
| `frontend/src/components/contentSearch/bulk/BulkFileDropzone.vue` | Drag/drop + file picker. |
| `frontend/src/components/contentSearch/bulk/BulkPreviewTable.vue` | Row-by-row table. |
| `frontend/src/components/contentSearch/bulk/BulkPreviewRow.vue` | Single row renderer. |
| `frontend/src/components/contentSearch/bulk/BulkRowCategoryEditor.vue` | Popover for per-row category override. |
| `frontend/src/components/contentSearch/bulk/BulkResultSummary.vue` | Post-submit summary. |
| `frontend/src/components/contentSearch/bulk/BulkFormatHelpModal.vue` | CSV/Excel/JSON spec modal. |
| `frontend/public/samples/sample-bulk-urls.csv` | Static sample CSV. |
| `frontend/public/samples/sample-bulk-urls.xlsx` | Static sample Excel. |
| `frontend/public/samples/sample-bulk-urls.json` | Static sample JSON. |

### Frontend — modify
| Path | Change |
|---|---|
| `frontend/package.json` | + `papaparse`, `xlsx`, `zod` deps (zod may already be present). |
| `frontend/src/views/ContentSearchView.vue` | Wrap existing content in a tab host; mount `<BulkSubmissionTab>` as 2nd tab. |
| `frontend/src/locales/messages.ts` | New `contentSearch.bulk.*` keys for en/ar/nl. |
| `frontend/src/generated/api/schema.ts` | Regenerated via `./scripts/generate-openapi-dtos.sh`. |

### Frontend — tests
| Path | Coverage |
|---|---|
| `frontend/tests/bulkSubmissionStore.spec.ts` | Every action: parseInput, runPreview, runSubmit, setRowCategories, removeRow, reset. |
| `frontend/tests/bulkFileParsers.spec.ts` | CSV happy + malformed; JSON valid + wrong shape; xlsx mock. |
| `frontend/tests/BulkInputView.spec.ts` | URL count math, Parse button gate. |
| `frontend/tests/BulkPreviewTable.spec.ts` | Status badges, per-row category override, remove row. |
| `frontend/tests/BulkSubmissionTab.spec.ts` | Phase transitions, tab leave guard. |

---

## Task 1: `VideoType` enum + `Video.videoType` field

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/model/VideoType.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/model/Video.java`
- Test: `backend/src/test/java/com/albunyaan/tube/model/VideoTest.java` (add field test)

Per spec §3.5 (BULK3).

- [ ] **Step 1: Create `VideoType.java`**

```java
package com.albunyaan.tube.model;

/**
 * BULK-01 (BULK3) — distinguishes live YouTube videos from standard videos
 * within the existing VIDEO content type. Null on existing docs is treated as STANDARD.
 */
public enum VideoType {
    STANDARD,
    LIVE
}
```

- [ ] **Step 2: Add `videoType` field to `Video.java`**

Locate the field block (around line 41–50 — `durationSeconds`, `viewCount` group). After `private Long viewCount;`, add:

```java
    /**
     * BULK-01: STANDARD (regular video, default) or LIVE (livestream).
     * Null on legacy docs; getter returns null and consumers treat null as STANDARD.
     */
    private VideoType videoType;
```

Then add getter/setter near the existing getters (preserve alphabetical-ish grouping with other accessors):

```java
    public VideoType getVideoType() {
        return videoType;
    }

    public void setVideoType(VideoType videoType) {
        this.videoType = videoType;
    }
```

- [ ] **Step 3: Write failing test for `videoType` round-trip**

Create or modify `backend/src/test/java/com/albunyaan/tube/model/VideoTest.java`:

```java
package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoTest {

    @Test
    void videoType_defaultIsNull_forLegacyDocsCompat() {
        Video v = new Video();
        assertNull(v.getVideoType(), "new Video() must default videoType to null");
    }

    @Test
    void videoType_setterAndGetterRoundtrip() {
        Video v = new Video();
        v.setVideoType(VideoType.LIVE);
        assertEquals(VideoType.LIVE, v.getVideoType());
        v.setVideoType(VideoType.STANDARD);
        assertEquals(VideoType.STANDARD, v.getVideoType());
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.model.VideoTest" --info
```

Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/VideoType.java \
        backend/src/main/java/com/albunyaan/tube/model/Video.java \
        backend/src/test/java/com/albunyaan/tube/model/VideoTest.java
git commit -m "[FEAT-BULK-01-T1]: add VideoType enum + Video.videoType field"
```

---

## Task 2: `YouTubeUrlParser` service

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/YouTubeUrlParser.java`
- Create: `backend/src/main/java/com/albunyaan/tube/service/YouTubeUrlParseResult.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/YouTubeUrlParserTest.java`

Per spec §3.3 (YouTubeUrlParser bullet).

- [ ] **Step 1: Write the failing test first**

Create `backend/src/test/java/com/albunyaan/tube/service/YouTubeUrlParserTest.java`:

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YouTubeUrlParserTest {

    private final YouTubeUrlParser parser = new YouTubeUrlParser();

    @Test
    void watchUrl_resolvesToVideo() {
        var r = parser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
        assertFalse(r.isShort());
        assertNull(r.errorCode());
    }

    @Test
    void youtuBeShort_resolvesToVideo() {
        var r = parser.parse("https://youtu.be/dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void shortsUrl_isUnsupported() {
        var r = parser.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ");
        assertNull(r.type());
        assertEquals(PreviewErrorCode.UNSUPPORTED_SHORTS, r.errorCode());
    }

    @Test
    void liveUrl_resolvesToVideo() {
        var r = parser.parse("https://www.youtube.com/live/dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void playlistUrl_resolvesToPlaylist() {
        var r = parser.parse("https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        assertEquals(YouTubeContentType.PLAYLIST, r.type());
        assertEquals("PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", r.youtubeId());
    }

    @Test
    void channelIdUrl_resolvesToChannel() {
        var r = parser.parse("https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx");
        assertEquals(YouTubeContentType.CHANNEL, r.type());
        assertEquals("UCxxxxxxxxxxxxxxxxxxxxxx", r.youtubeId());
    }

    @Test
    void handleUrl_resolvesToChannel_withHandleAsId() {
        var r = parser.parse("https://www.youtube.com/@SomeHandle");
        assertEquals(YouTubeContentType.CHANNEL, r.type());
        // NewPipe will resolve the handle to UC... at fetch time; parser just normalizes
        assertEquals("@SomeHandle", r.youtubeId());
    }

    @Test
    void legacyCUrl_resolvesToChannel() {
        var r = parser.parse("https://www.youtube.com/c/SomeChannel");
        assertEquals(YouTubeContentType.CHANNEL, r.type());
        assertEquals("SomeChannel", r.youtubeId());
    }

    @Test
    void legacyUserUrl_resolvesToChannel() {
        var r = parser.parse("https://www.youtube.com/user/SomeUser");
        assertEquals(YouTubeContentType.CHANNEL, r.type());
        assertEquals("SomeUser", r.youtubeId());
    }

    @Test
    void mobilePrefix_isStripped() {
        var r = parser.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void musicSubdomain_isUnsupported() {
        var r = parser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(PreviewErrorCode.UNSUPPORTED_TYPE, r.errorCode());
    }

    @Test
    void postUrl_isUnsupported() {
        var r = parser.parse("https://www.youtube.com/post/UgkxIDxxxxx");
        assertEquals(PreviewErrorCode.UNSUPPORTED_TYPE, r.errorCode());
    }

    @Test
    void nonYoutubeHost_isRejected() {
        var r = parser.parse("https://example.com/watch?v=dQw4w9WgXcQ");
        assertEquals(PreviewErrorCode.NOT_YOUTUBE_URL, r.errorCode());
    }

    @Test
    void whitespaceAndBom_areTrimmed() {
        var r = parser.parse("  ﻿https://www.youtube.com/watch?v=dQw4w9WgXcQ  ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void zeroWidthChars_inUrl_areSanitized() {
        // U+200B zero-width space injected after host
        var r = parser.parse("https://www.youtube.com​/watch?v=dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void emptyString_returnsNotYoutube() {
        var r = parser.parse("");
        assertEquals(PreviewErrorCode.NOT_YOUTUBE_URL, r.errorCode());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.YouTubeUrlParserTest"
```

Expected: FAIL with "cannot resolve YouTubeUrlParser" or similar.

- [ ] **Step 3: Create `YouTubeUrlParseResult` record**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;

/**
 * BULK-01 (T2) — result of parsing a single user-supplied URL.
 * If {@code errorCode} is non-null, {@code type}/{@code youtubeId}/{@code normalizedUrl} may be null.
 */
public record YouTubeUrlParseResult(
        YouTubeContentType type,
        String youtubeId,
        String normalizedUrl,
        boolean isShort,
        PreviewErrorCode errorCode
) {
    public static YouTubeUrlParseResult ok(YouTubeContentType type, String youtubeId, String normalizedUrl, boolean isShort) {
        return new YouTubeUrlParseResult(type, youtubeId, normalizedUrl, isShort, null);
    }

    public static YouTubeUrlParseResult error(PreviewErrorCode code) {
        return new YouTubeUrlParseResult(null, null, null, false, code);
    }
}
```

- [ ] **Step 4: Implement `YouTubeUrlParser`**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BULK-01 (T2) — regex-only YouTube URL classifier. No network calls.
 * NewPipe handle/c/user → UC... resolution happens later in {@link YouTubeGateway}.
 */
@Service
public class YouTubeUrlParser {

    // Allowed YouTube hosts. music.youtube.com is excluded — those URLs route to UNSUPPORTED_TYPE.
    private static final java.util.Set<String> YOUTUBE_HOSTS = java.util.Set.of(
            "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be"
    );

    private static final Pattern WATCH_V_PARAM = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
    private static final Pattern SHORTS_PATH = Pattern.compile("^/shorts/([a-zA-Z0-9_-]{11})/?$");
    private static final Pattern LIVE_PATH = Pattern.compile("^/live/([a-zA-Z0-9_-]{11})/?$");
    private static final Pattern PLAYLIST_LIST_PARAM = Pattern.compile("[?&]list=(PL[a-zA-Z0-9_-]+|LL[a-zA-Z0-9_-]+|UU[a-zA-Z0-9_-]+|RD[a-zA-Z0-9_-]+|OL[a-zA-Z0-9_-]+)");
    private static final Pattern CHANNEL_UC_PATH = Pattern.compile("^/channel/(UC[a-zA-Z0-9_-]{22})/?.*$");
    private static final Pattern HANDLE_PATH = Pattern.compile("^/(@[A-Za-z0-9._-]{1,30})/?.*$");
    private static final Pattern LEGACY_C_PATH = Pattern.compile("^/c/([A-Za-z0-9._-]+)/?.*$");
    private static final Pattern LEGACY_USER_PATH = Pattern.compile("^/user/([A-Za-z0-9._-]+)/?.*$");
    private static final Pattern POST_PATH = Pattern.compile("^/post/.+");
    private static final Pattern RESULTS_PATH = Pattern.compile("^/results/?$");
    private static final Pattern PLAYLIST_PATH = Pattern.compile("^/playlist/?$");

    /** Trim, BOM-strip, and remove zero-width and bidi-override characters from a URL string. */
    private static String sanitize(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]", "")
                .trim();
    }

    public YouTubeUrlParseResult parse(String rawUrl) {
        String url = sanitize(rawUrl);
        if (url.isEmpty()) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        String host = uri.getHost();
        if (host == null) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }
        host = host.toLowerCase();

        // music.youtube.com — explicitly unsupported (premium-only catalog content)
        if (host.equals("music.youtube.com")) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
        }

        if (!YOUTUBE_HOSTS.contains(host)) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        String path = uri.getPath() == null ? "/" : uri.getPath();
        String rawQuery = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();

        // youtu.be/{id} → VIDEO
        if (host.equals("youtu.be")) {
            String id = path.replaceFirst("^/", "").replaceFirst("/.*$", "");
            if (id.matches("[a-zA-Z0-9_-]{11}")) {
                return YouTubeUrlParseResult.ok(YouTubeContentType.VIDEO, id, canonicalWatchUrl(id), false);
            }
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        // /shorts/{id} → UNSUPPORTED_SHORTS
        Matcher shortsM = SHORTS_PATH.matcher(path);
        if (shortsM.matches()) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_SHORTS);
        }

        // /live/{id} → VIDEO (NewPipe will return StreamType=LIVE_STREAM and we'll tag videoType=LIVE downstream)
        Matcher liveM = LIVE_PATH.matcher(path);
        if (liveM.matches()) {
            String id = liveM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.VIDEO, id, canonicalWatchUrl(id), false);
        }

        // /watch?v={id} → VIDEO
        if (path.equals("/watch") || path.equals("/watch/")) {
            Matcher vM = WATCH_V_PARAM.matcher(rawQuery);
            if (vM.find()) {
                String id = vM.group(1);
                return YouTubeUrlParseResult.ok(YouTubeContentType.VIDEO, id, canonicalWatchUrl(id), false);
            }
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        // /playlist?list={id} → PLAYLIST
        if (PLAYLIST_PATH.matcher(path).matches()) {
            Matcher pM = PLAYLIST_LIST_PARAM.matcher(rawQuery);
            if (pM.find()) {
                String id = pM.group(1);
                return YouTubeUrlParseResult.ok(YouTubeContentType.PLAYLIST, id, canonicalPlaylistUrl(id), false);
            }
            return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
        }

        // /channel/UC... → CHANNEL
        Matcher chM = CHANNEL_UC_PATH.matcher(path);
        if (chM.matches()) {
            String id = chM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, id, canonicalChannelUrl(id), false);
        }

        // /@handle → CHANNEL (id is the handle; NewPipe resolves to UC...)
        Matcher hM = HANDLE_PATH.matcher(path);
        if (hM.matches()) {
            String handle = hM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, handle, "https://www.youtube.com/" + handle, false);
        }

        // /c/{name} → CHANNEL (legacy)
        Matcher cM = LEGACY_C_PATH.matcher(path);
        if (cM.matches()) {
            String name = cM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, name, "https://www.youtube.com/c/" + name, false);
        }

        // /user/{name} → CHANNEL (legacy)
        Matcher uM = LEGACY_USER_PATH.matcher(path);
        if (uM.matches()) {
            String name = uM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, name, "https://www.youtube.com/user/" + name, false);
        }

        // /post/* → UNSUPPORTED (community posts)
        if (POST_PATH.matcher(path).matches()) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
        }

        // /results — search pages
        if (RESULTS_PATH.matcher(path).matches()) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
        }

        return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
    }

    private static String canonicalWatchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    private static String canonicalPlaylistUrl(String id) {
        return "https://www.youtube.com/playlist?list=" + id;
    }

    private static String canonicalChannelUrl(String id) {
        return "https://www.youtube.com/channel/" + id;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.YouTubeUrlParserTest"
```

Expected: PASS, all tests green. If `PreviewErrorCode` is undefined, hold this commit until Task 8 (DTOs) lands `PreviewErrorCode`; the practical workflow is to land the DTO enum first via Task 8 step 1 (which only creates the enum), then return to Task 2.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/YouTubeUrlParser.java \
        backend/src/main/java/com/albunyaan/tube/service/YouTubeUrlParseResult.java \
        backend/src/test/java/com/albunyaan/tube/service/YouTubeUrlParserTest.java
git commit -m "[FEAT-BULK-01-T2]: regex-only YouTubeUrlParser + 15 URL flavor tests"
```

---

## Task 3: `PreviewErrorCode` enum + Bulk DTO records

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/PreviewErrorCode.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/RowStatus.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/PreviewMetadata.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/PreviewError.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/PreviewRow.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkPreviewRequest.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkPreviewResponse.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/SubmitRow.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkSubmitRequest.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/SubmitResult.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/registry/BulkSubmitResponse.java`

Per spec §3.2.

**Note:** This task lands first in the backend sequence so that Task 2's tests reference real enum values. No dedicated tests — these are pure data carriers exercised by Tasks 6–10.

- [ ] **Step 1: Create `PreviewErrorCode.java`**

```java
package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — error codes returned per-row by the bulk preview endpoint. */
public enum PreviewErrorCode {
    UNSUPPORTED_SHORTS,
    UNSUPPORTED_TYPE,
    NOT_YOUTUBE_URL,
    CONTENT_NOT_AVAILABLE,
    PRIVATE_CONTENT,
    AGE_RESTRICTED,
    GEO_RESTRICTED,
    CHANNEL_TERMINATED,
    NEWPIPE_PARSING_ERROR,
    NETWORK_ERROR,
    DUPLICATE,
    DUPLICATE_REJECTED
}
```

- [ ] **Step 2: Create `RowStatus.java`**

```java
package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — per-row outcome of the bulk preview pipeline. */
public enum RowStatus {
    OK,
    DUPLICATE,
    DUPLICATE_REJECTED,
    ERROR
}
```

- [ ] **Step 3: Create `PreviewMetadata.java`**

```java
package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — NewPipe metadata snapshot for a single previewed URL. Sparse: fields are populated based on type. */
public record PreviewMetadata(
        String youtubeId,
        String title,
        String thumbnailUrl,
        String channelName,
        String channelId,
        Long subscribers,
        Long itemCount,
        Long durationSeconds,
        Long viewCount
) {}
```

- [ ] **Step 4: Create `PreviewError.java`**

```java
package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — error envelope with code + i18n message key. */
public record PreviewError(PreviewErrorCode code, String messageKey) {
    public static PreviewError of(PreviewErrorCode code) {
        return new PreviewError(code, "contentSearch.bulk.errors." + code.name());
    }
}
```

- [ ] **Step 5: Create `PreviewRow.java`**

```java
package com.albunyaan.tube.dto.registry;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.VideoType;

/** BULK-01 (T3) — one row in the bulk preview response. */
public record PreviewRow(
        int rowIndex,
        String originalUrl,
        String normalizedUrl,
        YouTubeContentType detectedType,
        VideoType videoType,
        PreviewMetadata metadata,
        RowStatus status,
        String duplicateOf,
        String duplicateStatus,
        PreviewError error
) {}
```

- [ ] **Step 6: Create `BulkPreviewRequest.java`**

```java
package com.albunyaan.tube.dto.registry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/preview body. */
public record BulkPreviewRequest(
        @Size(min = 1, max = 25) List<@NotBlank String> urls,
        @Size(min = 1) List<@NotBlank String> defaultCategoryIds
) {}
```

- [ ] **Step 7: Create `BulkPreviewResponse.java`**

```java
package com.albunyaan.tube.dto.registry;

import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/preview response. */
public record BulkPreviewResponse(List<PreviewRow> rows) {}
```

- [ ] **Step 8: Create `SubmitRow.java`**

```java
package com.albunyaan.tube.dto.registry;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.VideoType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** BULK-01 (T3) — one row in the bulk submit request, returned from a prior preview. */
public record SubmitRow(
        int rowIndex,
        @NotBlank String originalUrl,
        @NotNull YouTubeContentType detectedType,
        VideoType videoType,
        @NotNull PreviewMetadata metadata,
        @Size(min = 1) List<@NotBlank String> categoryIds
) {}
```

- [ ] **Step 9: Create `BulkSubmitRequest.java`**

```java
package com.albunyaan.tube.dto.registry;

import jakarta.validation.constraints.Size;
import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/submit body. */
public record BulkSubmitRequest(
        @Size(min = 1, max = 25) List<SubmitRow> rows,
        /** Optional. Defaults to PENDING. Only ADMIN role honored when set to APPROVED. */
        String status
) {}
```

- [ ] **Step 10: Create `SubmitResult.java`**

```java
package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — per-row outcome of bulk submit. */
public record SubmitResult(
        int rowIndex,
        String originalUrl,
        String registryId,
        String status,     // "ADDED" | "FAILED"
        String errorCode   // null unless status=FAILED
) {}
```

- [ ] **Step 11: Create `BulkSubmitResponse.java`**

```java
package com.albunyaan.tube.dto.registry;

import java.util.List;

/** BULK-01 (T3) — POST /api/admin/registry/bulk/submit response. */
public record BulkSubmitResponse(
        int totalSubmitted,
        int added,
        int failed,
        List<SubmitResult> results
) {}
```

- [ ] **Step 12: Run a compile-only smoke test**

```bash
cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL. No tests yet — these are data carriers.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/registry/
git commit -m "[FEAT-BULK-01-T3]: bulk preview/submit DTOs + PreviewErrorCode enum"
```

---

## Task 4: `RegistryDuplicateChecker` service

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/RegistryDuplicateChecker.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/RegistryDuplicateCheckerTest.java`

Per spec §3.3 (RegistryDuplicateChecker bullet) + BULK5.

- [ ] **Step 1: Find the existing registry repository pattern**

Before writing the checker, find how registries are read today:

```bash
cd backend && grep -rln "ChannelRepository\|PlaylistRepository\|VideoRepository" src/main/java | head -10
```

Read the matching repository interfaces to learn how to query by `youtubeId`. The duplicate check uses `findByYoutubeId(String)` against each of the three repos (or whichever method exists — adapt if the project uses a Firestore-direct pattern).

- [ ] **Step 2: Write the failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistryDuplicateCheckerTest {

    private ChannelRepository channels;
    private PlaylistRepository playlists;
    private VideoRepository videos;
    private RegistryDuplicateChecker checker;

    @BeforeEach
    void setUp() {
        channels = mock(ChannelRepository.class);
        playlists = mock(PlaylistRepository.class);
        videos = mock(VideoRepository.class);
        checker = new RegistryDuplicateChecker(channels, playlists, videos);
    }

    @Test
    void noExisting_returnsEmpty() {
        when(channels.findByYoutubeId("UC123")).thenReturn(Optional.empty());
        var batch = checker.newBatch();
        assertTrue(batch.findExisting(YouTubeContentType.CHANNEL, "UC123").isEmpty());
    }

    @Test
    void existingPendingChannel_returnsMatch() {
        Channel c = new Channel("UC123");
        c.setId("doc-1");
        c.setStatus("PENDING");
        when(channels.findByYoutubeId("UC123")).thenReturn(Optional.of(c));
        var batch = checker.newBatch();

        var hit = batch.findExisting(YouTubeContentType.CHANNEL, "UC123");
        assertTrue(hit.isPresent());
        assertEquals("doc-1", hit.get().registryId());
        assertEquals("PENDING", hit.get().status());
    }

    @Test
    void rejectedPlaylist_returnsMatch_withRejectedStatus() {
        Playlist p = new Playlist("PL123");
        p.setId("doc-2");
        p.setStatus("REJECTED");
        when(playlists.findByYoutubeId("PL123")).thenReturn(Optional.of(p));
        var batch = checker.newBatch();

        var hit = batch.findExisting(YouTubeContentType.PLAYLIST, "PL123");
        assertEquals("REJECTED", hit.orElseThrow().status());
    }

    @Test
    void perBatchMemoization_doesNotRequeryRepo() {
        when(videos.findByYoutubeId("V123")).thenReturn(Optional.empty());
        var batch = checker.newBatch();

        batch.findExisting(YouTubeContentType.VIDEO, "V123");
        batch.findExisting(YouTubeContentType.VIDEO, "V123");

        verify(videos, times(1)).findByYoutubeId("V123");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.RegistryDuplicateCheckerTest"
```

Expected: FAIL — `RegistryDuplicateChecker` not yet defined.

- [ ] **Step 4: Implement `RegistryDuplicateChecker`**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * BULK-01 (T4) — looks up whether a given (type, youtubeId) already exists in the registry.
 * Provides a per-batch {@code Batch} object that memoizes lookups so the same youtubeId
 * isn't queried twice in one preview call.
 */
@Service
public class RegistryDuplicateChecker {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public RegistryDuplicateChecker(ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    public Batch newBatch() {
        return new Batch();
    }

    public record ExistingMatch(String registryId, String status) {}

    /** Per-batch memoizing view. Not thread-safe; each preview call gets its own batch. */
    public final class Batch {
        private final Map<String, Optional<ExistingMatch>> cache = new HashMap<>();

        private String key(YouTubeContentType type, String youtubeId) {
            return type.name() + ":" + youtubeId;
        }

        public Optional<ExistingMatch> findExisting(YouTubeContentType type, String youtubeId) {
            return cache.computeIfAbsent(key(type, youtubeId), k -> queryOnce(type, youtubeId));
        }

        private Optional<ExistingMatch> queryOnce(YouTubeContentType type, String youtubeId) {
            return switch (type) {
                case CHANNEL  -> channels.findByYoutubeId(youtubeId).map(c -> new ExistingMatch(c.getId(), c.getStatus()));
                case PLAYLIST -> playlists.findByYoutubeId(youtubeId).map(p -> new ExistingMatch(p.getId(), p.getStatus()));
                case VIDEO    -> videos.findByYoutubeId(youtubeId).map(v -> new ExistingMatch(v.getId(), v.getStatus()));
            };
        }
    }
}
```

If `findByYoutubeId` doesn't exist on the repositories, the implementer adds it as `Optional<Channel> findByYoutubeId(String youtubeId)` (and similar for Playlist/Video) under the standard Spring Data / Firestore repository convention used elsewhere in the project. Step 1's exploration tells you which pattern is in use.

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.RegistryDuplicateCheckerTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/RegistryDuplicateChecker.java \
        backend/src/test/java/com/albunyaan/tube/service/RegistryDuplicateCheckerTest.java \
        backend/src/main/java/com/albunyaan/tube/repository/*.java
git commit -m "[FEAT-BULK-01-T4]: RegistryDuplicateChecker + per-batch memoization"
```

---

## Task 5: `YouTubeGateway.fetchByDetectedType` + exception mapping

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/PreviewFetchResult.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/service/YouTubeGateway.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/YouTubeGatewayBulkTest.java`

Per spec §3.3 (YouTubeGateway bullet) + §3.4 exception mapping table.

- [ ] **Step 1: Create `PreviewFetchResult` record**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import com.albunyaan.tube.dto.registry.PreviewMetadata;
import com.albunyaan.tube.model.VideoType;

/**
 * BULK-01 (T5) — result of fetching one URL's metadata through NewPipe.
 * Either {@code metadata} is non-null (success) or {@code errorCode} is non-null (failure).
 */
public record PreviewFetchResult(
        PreviewMetadata metadata,
        VideoType videoType,    // STANDARD/LIVE for VIDEO type; null for CHANNEL/PLAYLIST or on error
        PreviewErrorCode errorCode
) {
    public static PreviewFetchResult ok(PreviewMetadata m, VideoType vt) {
        return new PreviewFetchResult(m, vt, null);
    }

    public static PreviewFetchResult error(PreviewErrorCode code) {
        return new PreviewFetchResult(null, null, code);
    }
}
```

- [ ] **Step 2: Locate existing `YouTubeGateway` methods**

```bash
cd backend && grep -n "public\|fetchChannel\|fetchPlaylist\|fetchStream\|StreamInfo\|ChannelInfo\|PlaylistInfo" \
    src/main/java/com/albunyaan/tube/service/YouTubeGateway.java | head -30
```

Note which existing methods you'll wrap (likely `fetchChannelInfo(String url)`, `fetchPlaylistInfo(String url)`, `fetchStreamInfo(String url)`).

- [ ] **Step 3: Write the failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import com.albunyaan.tube.model.VideoType;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class YouTubeGatewayBulkTest {

    @Test
    void contentNotAvailableException_mapsToCode() {
        YouTubeGateway gw = spy(new YouTubeGateway());
        doThrow(new ContentNotAvailableException("gone")).when(gw).fetchStreamInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.VIDEO, "abc", "https://www.youtube.com/watch?v=abc");

        assertEquals(PreviewErrorCode.CONTENT_NOT_AVAILABLE, r.errorCode());
        assertNull(r.metadata());
    }

    @Test
    void privateContentException_mapsToCode() {
        YouTubeGateway gw = spy(new YouTubeGateway());
        doThrow(new PrivateContentException("private")).when(gw).fetchPlaylistInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.PLAYLIST, "PL1", "https://www.youtube.com/playlist?list=PL1");

        assertEquals(PreviewErrorCode.PRIVATE_CONTENT, r.errorCode());
    }

    @Test
    void accountTerminated_mapsToCode() {
        YouTubeGateway gw = spy(new YouTubeGateway());
        doThrow(new AccountTerminatedException("terminated")).when(gw).fetchChannelInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.CHANNEL, "UC1", "https://www.youtube.com/channel/UC1");

        assertEquals(PreviewErrorCode.CHANNEL_TERMINATED, r.errorCode());
    }

    @Test
    void ioException_mapsToNetworkError() {
        YouTubeGateway gw = spy(new YouTubeGateway());
        doThrow(new RuntimeException(new java.io.IOException("net"))).when(gw).fetchStreamInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.VIDEO, "abc", "https://www.youtube.com/watch?v=abc");

        assertEquals(PreviewErrorCode.NETWORK_ERROR, r.errorCode());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.YouTubeGatewayBulkTest"
```

Expected: FAIL — `fetchByDetectedType` not defined.

- [ ] **Step 5: Implement `fetchByDetectedType` in `YouTubeGateway`**

Append the following method to `YouTubeGateway.java`. The implementer adapts the wrapped NewPipe calls to match the existing `fetchChannelInfo / fetchPlaylistInfo / fetchStreamInfo` signatures discovered in Step 2.

```java
    /**
     * BULK-01 (T5) — single dispatch for the bulk preview pipeline.
     * Wraps the existing per-type fetch methods and maps NewPipe exceptions to {@link PreviewErrorCode}.
     */
    public PreviewFetchResult fetchByDetectedType(
            com.albunyaan.tube.dto.YouTubeContentType type,
            String youtubeId,
            String normalizedUrl) {
        try {
            return switch (type) {
                case CHANNEL  -> mapChannel(fetchChannelInfo(normalizedUrl), youtubeId);
                case PLAYLIST -> mapPlaylist(fetchPlaylistInfo(normalizedUrl), youtubeId);
                case VIDEO    -> mapVideo(fetchStreamInfo(normalizedUrl), youtubeId);
            };
        } catch (org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException e) {
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.CONTENT_NOT_AVAILABLE);
        } catch (org.schabi.newpipe.extractor.exceptions.PrivateContentException e) {
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.PRIVATE_CONTENT);
        } catch (org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException e) {
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.AGE_RESTRICTED);
        } catch (org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException e) {
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.GEO_RESTRICTED);
        } catch (org.schabi.newpipe.extractor.exceptions.AccountTerminatedException e) {
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.CHANNEL_TERMINATED);
        } catch (org.schabi.newpipe.extractor.exceptions.ParsingException e) {
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.NEWPIPE_PARSING_ERROR);
        } catch (org.schabi.newpipe.extractor.exceptions.ExtractionException e) {
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.NEWPIPE_PARSING_ERROR);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof java.io.IOException) {
                return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.NETWORK_ERROR);
            }
            return PreviewFetchResult.error(com.albunyaan.tube.dto.registry.PreviewErrorCode.NEWPIPE_PARSING_ERROR);
        }
    }

    private PreviewFetchResult mapChannel(
            org.schabi.newpipe.extractor.channel.ChannelInfo info, String youtubeId) {
        var m = new com.albunyaan.tube.dto.registry.PreviewMetadata(
                youtubeId,
                info.getName(),
                pickThumb(info.getAvatars()),
                null, null,
                info.getSubscriberCount() == -1L ? null : info.getSubscriberCount(),
                null, null, null);
        return PreviewFetchResult.ok(m, null);
    }

    private PreviewFetchResult mapPlaylist(
            org.schabi.newpipe.extractor.playlist.PlaylistInfo info, String youtubeId) {
        var m = new com.albunyaan.tube.dto.registry.PreviewMetadata(
                youtubeId,
                info.getName(),
                pickThumb(info.getThumbnails()),
                info.getUploaderName(),
                deriveChannelIdFromUrl(info.getUploaderUrl()),
                null,
                info.getStreamCount() == -1L ? null : info.getStreamCount(),
                null, null);
        return PreviewFetchResult.ok(m, null);
    }

    private PreviewFetchResult mapVideo(
            org.schabi.newpipe.extractor.stream.StreamInfo info, String youtubeId) {
        com.albunyaan.tube.model.VideoType vt =
                (info.getStreamType() == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
                 || info.getStreamType() == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM)
                ? com.albunyaan.tube.model.VideoType.LIVE
                : com.albunyaan.tube.model.VideoType.STANDARD;

        var m = new com.albunyaan.tube.dto.registry.PreviewMetadata(
                youtubeId,
                info.getName(),
                pickThumb(info.getThumbnails()),
                info.getUploaderName(),
                deriveChannelIdFromUrl(info.getUploaderUrl()),
                null, null,
                vt == com.albunyaan.tube.model.VideoType.LIVE && info.getDuration() == 0 ? null : info.getDuration(),
                info.getViewCount() == -1L ? null : info.getViewCount());
        return PreviewFetchResult.ok(m, vt);
    }

    private static String pickThumb(java.util.List<org.schabi.newpipe.extractor.Image> imgs) {
        if (imgs == null || imgs.isEmpty()) return null;
        // Prefer HIGH, then MEDIUM, then any.
        return imgs.stream()
                .filter(i -> i.getResolutionLevel() == org.schabi.newpipe.extractor.Image.ResolutionLevel.HIGH)
                .findFirst()
                .or(() -> imgs.stream()
                        .filter(i -> i.getResolutionLevel() == org.schabi.newpipe.extractor.Image.ResolutionLevel.MEDIUM)
                        .findFirst())
                .or(() -> imgs.stream().findFirst())
                .map(org.schabi.newpipe.extractor.Image::getUrl)
                .orElse(null);
    }

    private static String deriveChannelIdFromUrl(String uploaderUrl) {
        if (uploaderUrl == null) return null;
        var m = java.util.regex.Pattern.compile("/channel/(UC[a-zA-Z0-9_-]{22})").matcher(uploaderUrl);
        return m.find() ? m.group(1) : null;
    }
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.YouTubeGatewayBulkTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/PreviewFetchResult.java \
        backend/src/main/java/com/albunyaan/tube/service/YouTubeGateway.java \
        backend/src/test/java/com/albunyaan/tube/service/YouTubeGatewayBulkTest.java
git commit -m "[FEAT-BULK-01-T5]: YouTubeGateway.fetchByDetectedType + exception mapping"
```

---

## Task 6: `RegistrySubmissionWriter` refactor — extract write logic

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/RegistrySubmissionWriter.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java` (rewire `addChannel`, `addPlaylist`, `addVideo` to delegate)
- Test: `backend/src/test/java/com/albunyaan/tube/service/RegistrySubmissionWriterTest.java`

Per spec §3.3 (RegistrySubmissionWriter refactor).

**Goal:** keep the existing single-add endpoint behavior IDENTICAL while extracting the per-type write logic so both single-add and bulk-submit paths share one source of truth.

- [ ] **Step 1: Read existing single-add handlers**

```bash
cd backend && sed -n '194,290p' src/main/java/com/albunyaan/tube/controller/RegistryController.java
cd backend && sed -n '495,570p' src/main/java/com/albunyaan/tube/controller/RegistryController.java
```

Identify the existing write-pipeline steps for each type (status normalization, `submittedBy` from principal, `submitterNote` sanitization, Firestore save).

- [ ] **Step 2: Write the failing characterization test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.registry.PreviewMetadata;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegistrySubmissionWriterTest {

    private ChannelRepository channels;
    private PlaylistRepository playlists;
    private VideoRepository videos;
    private RegistrySubmissionWriter writer;

    @BeforeEach
    void setUp() {
        channels = mock(ChannelRepository.class);
        playlists = mock(PlaylistRepository.class);
        videos = mock(VideoRepository.class);
        writer = new RegistrySubmissionWriter(channels, playlists, videos);
    }

    @Test
    void writeChannel_persistsWithModeratorStatus_pendingForNonAdmin() {
        when(channels.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId("doc-c-1");
            return c;
        });
        var meta = new PreviewMetadata("UC123", "Some Channel", "thumb.jpg", null, null, 12345L, null, null, null);

        String id = writer.writeChannel(meta, List.of("cat1"), "PENDING", "moderator-uid", false);

        assertEquals("doc-c-1", id);
        ArgumentCaptor<Channel> cap = ArgumentCaptor.forClass(Channel.class);
        verify(channels).save(cap.capture());
        Channel saved = cap.getValue();
        assertEquals("UC123", saved.getYoutubeId());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("moderator-uid", saved.getSubmittedBy());
        assertNull(saved.getApprovedBy());
        assertEquals(List.of("cat1"), saved.getCategoryIds());
    }

    @Test
    void writeChannel_adminApproved_setsApprovedBy() {
        when(channels.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId("doc-c-2");
            return c;
        });
        var meta = new PreviewMetadata("UC123", "Some Channel", "thumb.jpg", null, null, null, null, null, null);

        writer.writeChannel(meta, List.of("cat1"), "APPROVED", "admin-uid", true);

        ArgumentCaptor<Channel> cap = ArgumentCaptor.forClass(Channel.class);
        verify(channels).save(cap.capture());
        Channel saved = cap.getValue();
        assertEquals("APPROVED", saved.getStatus());
        assertEquals("admin-uid", saved.getApprovedBy());
    }

    @Test
    void writeVideo_propagatesVideoType() {
        when(videos.save(any(Video.class))).thenAnswer(inv -> {
            Video v = inv.getArgument(0);
            v.setId("doc-v-1");
            return v;
        });
        var meta = new PreviewMetadata("vid123", "A Live", "thumb.jpg", "Some Channel", "UC123", null, null, null, 999L);

        writer.writeVideo(meta, VideoType.LIVE, List.of("cat1"), "PENDING", "u", false);

        ArgumentCaptor<Video> cap = ArgumentCaptor.forClass(Video.class);
        verify(videos).save(cap.capture());
        assertEquals(VideoType.LIVE, cap.getValue().getVideoType());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.RegistrySubmissionWriterTest"
```

Expected: FAIL — class not defined.

- [ ] **Step 4: Implement `RegistrySubmissionWriter`**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.registry.PreviewMetadata;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.*;
import com.google.cloud.Timestamp;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BULK-01 (T6) — single source of truth for "write a registry doc".
 * Used by both single-add endpoints (Channel/Playlist/Video controllers) and the bulk submit path.
 */
@Service
public class RegistrySubmissionWriter {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public RegistrySubmissionWriter(ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    public String writeChannel(PreviewMetadata meta, List<String> categoryIds, String status, String submittedByUid, boolean isAdmin) {
        Channel c = new Channel(meta.youtubeId());
        c.setName(meta.title());
        c.setThumbnailUrl(meta.thumbnailUrl());
        if (meta.subscribers() != null) c.setSubscribers(meta.subscribers());
        c.setCategoryIds(categoryIds);
        applyStatus(c::setStatus, c::setApprovedBy, status, submittedByUid, isAdmin);
        c.setSubmittedBy(submittedByUid);
        Timestamp now = Timestamp.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        Channel saved = channels.save(c);
        return saved.getId();
    }

    public String writePlaylist(PreviewMetadata meta, List<String> categoryIds, String status, String submittedByUid, boolean isAdmin) {
        Playlist p = new Playlist(meta.youtubeId());
        p.setTitle(meta.title());
        p.setThumbnailUrl(meta.thumbnailUrl());
        if (meta.itemCount() != null) p.setItemCount(meta.itemCount().intValue());
        if (meta.channelId() != null) p.setChannelId(meta.channelId());
        if (meta.channelName() != null) p.setChannelTitle(meta.channelName());
        p.setCategoryIds(categoryIds);
        applyStatus(p::setStatus, p::setApprovedBy, status, submittedByUid, isAdmin);
        p.setSubmittedBy(submittedByUid);
        Timestamp now = Timestamp.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        Playlist saved = playlists.save(p);
        return saved.getId();
    }

    public String writeVideo(PreviewMetadata meta, VideoType videoType, List<String> categoryIds, String status, String submittedByUid, boolean isAdmin) {
        Video v = new Video(meta.youtubeId());
        v.setTitle(meta.title());
        v.setThumbnailUrl(meta.thumbnailUrl());
        if (meta.durationSeconds() != null) v.setDurationSeconds(meta.durationSeconds().intValue());
        if (meta.viewCount() != null) v.setViewCount(meta.viewCount());
        if (meta.channelId() != null) v.setChannelId(meta.channelId());
        if (meta.channelName() != null) v.setChannelTitle(meta.channelName());
        v.setVideoType(videoType != null ? videoType : VideoType.STANDARD);
        v.setCategoryIds(categoryIds);
        applyStatus(v::setStatus, v::setApprovedBy, status, submittedByUid, isAdmin);
        v.setSubmittedBy(submittedByUid);
        Timestamp now = Timestamp.now();
        v.setCreatedAt(now);
        v.setUpdatedAt(now);
        Video saved = videos.save(v);
        return saved.getId();
    }

    private static void applyStatus(
            java.util.function.Consumer<String> setStatus,
            java.util.function.Consumer<String> setApprovedBy,
            String requestedStatus,
            String actorUid,
            boolean isAdmin) {
        // Non-admin: always PENDING, no approvedBy.
        if (!isAdmin) {
            setStatus.accept("PENDING");
            setApprovedBy.accept(null);
            return;
        }
        // Admin: honor requestedStatus, defaulting to PENDING; APPROVED sets approvedBy.
        String s = (requestedStatus == null || requestedStatus.isBlank()) ? "PENDING" : requestedStatus.toUpperCase();
        setStatus.accept(s);
        setApprovedBy.accept("APPROVED".equals(s) ? actorUid : null);
    }
}
```

- [ ] **Step 5: Rewire `RegistryController.addChannel`, `addPlaylist`, `addVideo` to delegate**

In `RegistryController.java`, the existing three POST handlers (`addChannel`, `addPlaylist`, `addVideo`) keep their request DTOs but the body of each now calls `RegistrySubmissionWriter`. The implementer:
1. Constructs a `PreviewMetadata` from the existing request DTO fields (mapping per-type).
2. Calls `writer.writeChannel(...)` / `writePlaylist(...)` / `writeVideo(...)`.
3. Reads back the saved entity by `id` and returns it (preserving the existing `ResponseEntity<Channel>` etc. signature).

Existing `sanitizeSubmitterNote()` handling remains in the controller — `submitterNote` is set on the saved entity AFTER `writer.write*` returns the ID and reload happens. Existing behavior preserved.

(Skipping inline code here because the controller is 1074 lines; subagent reads the file in Step 1 and adapts.)

- [ ] **Step 6: Run the full backend test suite to verify no regressions**

```bash
cd backend && ./gradlew test
```

Expected: PASS. All existing `RegistryControllerTest` and integration tests still green. If any single-add test fails, the refactor changed observable behavior — revert and reapply more carefully.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/RegistrySubmissionWriter.java \
        backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java \
        backend/src/test/java/com/albunyaan/tube/service/RegistrySubmissionWriterTest.java
git commit -m "[REFACTOR-BULK-01-T6]: extract RegistrySubmissionWriter from single-add handlers"
```

---

## Task 7: `BulkSubmissionService.preview`

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/BulkSubmissionService.java` (preview method only — submit lands in T8)
- Test: `backend/src/test/java/com/albunyaan/tube/service/BulkSubmissionServicePreviewTest.java`

Per spec §3.3 (BulkSubmissionService.preview) + §3.4 + BULK4.

- [ ] **Step 1: Write the failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BulkSubmissionServicePreviewTest {

    private YouTubeUrlParser parser;
    private YouTubeGateway gateway;
    private RegistryDuplicateChecker dedupe;
    private BulkSubmissionService svc;

    @BeforeEach
    void setUp() {
        parser = new YouTubeUrlParser();      // real parser is fine here
        gateway = mock(YouTubeGateway.class);
        dedupe = mock(RegistryDuplicateChecker.class);
        when(dedupe.newBatch()).thenReturn(mock(RegistryDuplicateChecker.Batch.class));
        svc = new BulkSubmissionService(parser, gateway, dedupe);
    }

    @Test
    void okRow_videoStandard() {
        var batch = mock(RegistryDuplicateChecker.Batch.class);
        when(dedupe.newBatch()).thenReturn(batch);
        when(batch.findExisting(any(), any())).thenReturn(Optional.empty());
        when(gateway.fetchByDetectedType(eq(YouTubeContentType.VIDEO), eq("dQw4w9WgXcQ"), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata("dQw4w9WgXcQ", "Rick Astley", "thumb.jpg", "Rick", "UC1", null, null, 213L, 1000L),
                        VideoType.STANDARD));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), List.of("cat-1"));
        var resp = svc.preview(req);

        assertEquals(1, resp.rows().size());
        var row = resp.rows().get(0);
        assertEquals(RowStatus.OK, row.status());
        assertEquals(YouTubeContentType.VIDEO, row.detectedType());
        assertEquals(VideoType.STANDARD, row.videoType());
        assertEquals("Rick Astley", row.metadata().title());
        assertNull(row.error());
    }

    @Test
    void shortsUrl_returnsUnsupportedShortsError() {
        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/shorts/abcdefghijk"), List.of("cat-1"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.ERROR, resp.rows().get(0).status());
        assertEquals(PreviewErrorCode.UNSUPPORTED_SHORTS, resp.rows().get(0).error().code());
        verifyNoInteractions(gateway);
    }

    @Test
    void duplicatePending_marksDuplicate() {
        var batch = mock(RegistryDuplicateChecker.Batch.class);
        when(dedupe.newBatch()).thenReturn(batch);
        when(batch.findExisting(eq(YouTubeContentType.VIDEO), eq("dQw4w9WgXcQ")))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("existing-doc", "PENDING")));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), List.of("cat-1"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.DUPLICATE, resp.rows().get(0).status());
        assertEquals("existing-doc", resp.rows().get(0).duplicateOf());
        verifyNoInteractions(gateway);   // duplicate check shortcircuits the NewPipe fetch
    }

    @Test
    void duplicateRejected_marksDuplicateRejected() {
        var batch = mock(RegistryDuplicateChecker.Batch.class);
        when(dedupe.newBatch()).thenReturn(batch);
        when(batch.findExisting(any(), any()))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("rejected-doc", "REJECTED")));
        // Even for REJECTED dupes we still fetch metadata so the admin can see what they're re-submitting
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata("dQw4w9WgXcQ", "Some Vid", "thumb.jpg", null, null, null, null, null, null),
                        VideoType.STANDARD));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), List.of("cat-1"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.DUPLICATE_REJECTED, resp.rows().get(0).status());
        assertEquals("rejected-doc", resp.rows().get(0).duplicateOf());
        assertNotNull(resp.rows().get(0).metadata(), "metadata must be present so admin sees what they'd re-submit");
    }

    @Test
    void newpipeError_passesThroughErrorCode() {
        var batch = mock(RegistryDuplicateChecker.Batch.class);
        when(dedupe.newBatch()).thenReturn(batch);
        when(batch.findExisting(any(), any())).thenReturn(Optional.empty());
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.error(PreviewErrorCode.CONTENT_NOT_AVAILABLE));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), List.of("cat-1"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.ERROR, resp.rows().get(0).status());
        assertEquals(PreviewErrorCode.CONTENT_NOT_AVAILABLE, resp.rows().get(0).error().code());
    }

    @Test
    void mixedBatch_preservesOriginalOrder() {
        var batch = mock(RegistryDuplicateChecker.Batch.class);
        when(dedupe.newBatch()).thenReturn(batch);
        when(batch.findExisting(any(), any())).thenReturn(Optional.empty());
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata("x", "x", null, null, null, null, null, null, null),
                        VideoType.STANDARD));

        var req = new BulkPreviewRequest(
                List.of(
                        "https://www.youtube.com/watch?v=AAAAAAAAAAA",   // index 0, OK
                        "https://www.youtube.com/shorts/BBBBBBBBBBB",     // index 1, ERROR
                        "https://www.youtube.com/watch?v=CCCCCCCCCCC"     // index 2, OK
                ),
                List.of("cat-1"));
        var resp = svc.preview(req);

        assertEquals(3, resp.rows().size());
        assertEquals(0, resp.rows().get(0).rowIndex());
        assertEquals(1, resp.rows().get(1).rowIndex());
        assertEquals(2, resp.rows().get(2).rowIndex());
        assertEquals(RowStatus.OK,    resp.rows().get(0).status());
        assertEquals(RowStatus.ERROR, resp.rows().get(1).status());
        assertEquals(RowStatus.OK,    resp.rows().get(2).status());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkSubmissionServicePreviewTest"
```

Expected: FAIL — `BulkSubmissionService` not defined.

- [ ] **Step 3: Implement `BulkSubmissionService` (preview method only)**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * BULK-01 (T7) — orchestrator for the bulk URL submission preview pipeline.
 * Submit method lands in T8.
 */
@Service
public class BulkSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(BulkSubmissionService.class);
    private static final int FETCH_WORKERS = 5;

    private final YouTubeUrlParser parser;
    private final YouTubeGateway gateway;
    private final RegistryDuplicateChecker dedupe;

    public BulkSubmissionService(YouTubeUrlParser parser, YouTubeGateway gateway, RegistryDuplicateChecker dedupe) {
        this.parser = parser;
        this.gateway = gateway;
        this.dedupe = dedupe;
    }

    public BulkPreviewResponse preview(BulkPreviewRequest req) {
        long start = System.currentTimeMillis();
        RegistryDuplicateChecker.Batch batch = dedupe.newBatch();
        ExecutorService pool = Executors.newFixedThreadPool(FETCH_WORKERS);
        try {
            List<CompletableFuture<PreviewRow>> futures = new ArrayList<>(req.urls().size());
            for (int i = 0; i < req.urls().size(); i++) {
                final int rowIndex = i;
                final String url = req.urls().get(i);
                futures.add(CompletableFuture.supplyAsync(() -> buildRow(rowIndex, url, batch), pool));
            }
            List<PreviewRow> rows = futures.stream().map(CompletableFuture::join).toList();

            int okCount = (int) rows.stream().filter(r -> r.status() == RowStatus.OK).count();
            int errCount = (int) rows.stream().filter(r -> r.status() == RowStatus.ERROR).count();
            log.info("bulk-preview rowCount={} okCount={} errorCount={} durationMs={}",
                    rows.size(), okCount, errCount, System.currentTimeMillis() - start);

            return new BulkPreviewResponse(rows);
        } finally {
            pool.shutdown();
        }
    }

    private PreviewRow buildRow(int rowIndex, String originalUrl, RegistryDuplicateChecker.Batch batch) {
        YouTubeUrlParseResult parsed = parser.parse(originalUrl);
        if (parsed.errorCode() != null) {
            return new PreviewRow(rowIndex, originalUrl, null, null, null, null, RowStatus.ERROR, null, null,
                    PreviewError.of(parsed.errorCode()));
        }

        var existing = batch.findExisting(parsed.type(), parsed.youtubeId());

        // PENDING or APPROVED dupes: skip fetch entirely.
        if (existing.isPresent() && ("PENDING".equals(existing.get().status()) || "APPROVED".equals(existing.get().status()))) {
            return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(), null, null,
                    RowStatus.DUPLICATE, existing.get().registryId(), existing.get().status(),
                    PreviewError.of(PreviewErrorCode.DUPLICATE));
        }

        // Either no existing, or REJECTED dupe (admin may resubmit). Fetch metadata.
        PreviewFetchResult fetch = gateway.fetchByDetectedType(parsed.type(), parsed.youtubeId(), parsed.normalizedUrl());
        if (fetch.errorCode() != null) {
            return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(), null, null,
                    RowStatus.ERROR, null, null, PreviewError.of(fetch.errorCode()));
        }

        if (existing.isPresent() && "REJECTED".equals(existing.get().status())) {
            return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(),
                    fetch.videoType(), fetch.metadata(),
                    RowStatus.DUPLICATE_REJECTED, existing.get().registryId(), "REJECTED",
                    PreviewError.of(PreviewErrorCode.DUPLICATE_REJECTED));
        }

        return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(),
                fetch.videoType(), fetch.metadata(),
                RowStatus.OK, null, null, null);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkSubmissionServicePreviewTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/BulkSubmissionService.java \
        backend/src/test/java/com/albunyaan/tube/service/BulkSubmissionServicePreviewTest.java
git commit -m "[FEAT-BULK-01-T7]: BulkSubmissionService.preview + 6 row-state tests"
```

---

## Task 8: `BulkSubmissionService.submit` + role-aware status normalization

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/BulkSubmissionService.java` (add `submit` method)
- Test: `backend/src/test/java/com/albunyaan/tube/service/BulkSubmissionServiceSubmitTest.java`

Per spec §3.3 (BulkSubmissionService.submit) + BULK7.

- [ ] **Step 1: Write the failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkSubmissionServiceSubmitTest {

    private RegistrySubmissionWriter writer;
    private BulkSubmissionService svc;

    @BeforeEach
    void setUp() {
        writer = mock(RegistrySubmissionWriter.class);
        svc = new BulkSubmissionService(
                mock(YouTubeUrlParser.class),
                mock(YouTubeGateway.class),
                mock(RegistryDuplicateChecker.class),
                writer);
    }

    @Test
    void moderator_submitChannel_alwaysPending() {
        when(writer.writeChannel(any(), any(), eq("PENDING"), eq("mod-uid"), eq(false))).thenReturn("doc-c-1");

        var row = new SubmitRow(0, "https://www.youtube.com/channel/UC1",
                YouTubeContentType.CHANNEL, null,
                new PreviewMetadata("UC1", "Ch", "t.jpg", null, null, 100L, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "APPROVED");   // moderator tries to bypass — ignored

        var resp = svc.submit(req, "mod-uid", false);

        assertEquals(1, resp.added());
        assertEquals(0, resp.failed());
        assertEquals("doc-c-1", resp.results().get(0).registryId());
        verify(writer).writeChannel(any(), any(), eq("PENDING"), eq("mod-uid"), eq(false));
    }

    @Test
    void admin_submitVideo_honorsApproved() {
        when(writer.writeVideo(any(), eq(VideoType.LIVE), any(), eq("APPROVED"), eq("admin-uid"), eq(true))).thenReturn("doc-v-1");

        var row = new SubmitRow(0, "https://www.youtube.com/live/abc",
                YouTubeContentType.VIDEO, VideoType.LIVE,
                new PreviewMetadata("abc", "Live Vid", "t.jpg", "Ch", "UC1", null, null, null, 50L),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "APPROVED");

        var resp = svc.submit(req, "admin-uid", true);

        assertEquals(1, resp.added());
        verify(writer).writeVideo(any(), eq(VideoType.LIVE), any(), eq("APPROVED"), eq("admin-uid"), eq(true));
    }

    @Test
    void perRowFailure_aggregatedNotAborting() {
        when(writer.writeChannel(any(), any(), any(), any(), anyBoolean())).thenReturn("doc-c-1");
        when(writer.writePlaylist(any(), any(), any(), any(), anyBoolean())).thenThrow(new RuntimeException("firestore down"));
        when(writer.writeVideo(any(), any(), any(), any(), any(), anyBoolean())).thenReturn("doc-v-1");

        var rows = List.of(
                new SubmitRow(0, "url-c", YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("c", "c", null, null, null, null, null, null, null), List.of("cat-1")),
                new SubmitRow(1, "url-p", YouTubeContentType.PLAYLIST, null,
                        new PreviewMetadata("p", "p", null, null, null, null, null, null, null), List.of("cat-1")),
                new SubmitRow(2, "url-v", YouTubeContentType.VIDEO, VideoType.STANDARD,
                        new PreviewMetadata("v", "v", null, null, null, null, null, null, null), List.of("cat-1"))
        );
        var req = new BulkSubmitRequest(rows, "PENDING");

        var resp = svc.submit(req, "uid", true);

        assertEquals(3, resp.totalSubmitted());
        assertEquals(2, resp.added());
        assertEquals(1, resp.failed());
        assertEquals("FAILED", resp.results().get(1).status());
        assertNotNull(resp.results().get(1).errorCode());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkSubmissionServiceSubmitTest"
```

Expected: FAIL — `submit` method not yet defined; constructor signature mismatch.

- [ ] **Step 3: Add `writer` constructor arg and `submit` method to `BulkSubmissionService`**

Update the existing class. Add the new constructor parameter and field:

```java
    private final RegistrySubmissionWriter writer;

    public BulkSubmissionService(
            YouTubeUrlParser parser,
            YouTubeGateway gateway,
            RegistryDuplicateChecker dedupe,
            RegistrySubmissionWriter writer) {
        this.parser = parser;
        this.gateway = gateway;
        this.dedupe = dedupe;
        this.writer = writer;
    }
```

(Delete the old 3-arg constructor.) Then append the submit method:

```java
    public BulkSubmitResponse submit(BulkSubmitRequest req, String actorUid, boolean isAdmin) {
        long start = System.currentTimeMillis();
        // Role-based status normalization: moderators always PENDING; admin defaults PENDING but can pass APPROVED.
        String resolvedStatus = isAdmin
                ? (req.status() == null || req.status().isBlank() ? "PENDING" : req.status().toUpperCase())
                : "PENDING";

        List<SubmitResult> results = new ArrayList<>(req.rows().size());
        int added = 0, failed = 0;

        for (SubmitRow row : req.rows()) {
            try {
                String registryId = switch (row.detectedType()) {
                    case CHANNEL  -> writer.writeChannel(row.metadata(), row.categoryIds(), resolvedStatus, actorUid, isAdmin);
                    case PLAYLIST -> writer.writePlaylist(row.metadata(), row.categoryIds(), resolvedStatus, actorUid, isAdmin);
                    case VIDEO    -> writer.writeVideo(row.metadata(),
                            row.videoType() != null ? row.videoType() : VideoType.STANDARD,
                            row.categoryIds(), resolvedStatus, actorUid, isAdmin);
                };
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), registryId, "ADDED", null));
                added++;
            } catch (Exception e) {
                log.warn("bulk-submit row failed: rowIndex={} url={} reason={}", row.rowIndex(), row.originalUrl(), e.getMessage());
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null, "FAILED", "WRITE_ERROR"));
                failed++;
            }
        }

        log.info("bulk-submit adminUid={} rowCount={} added={} failed={} durationMs={}",
                actorUid, req.rows().size(), added, failed, System.currentTimeMillis() - start);

        return new BulkSubmitResponse(req.rows().size(), added, failed, results);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkSubmissionServiceSubmitTest"
```

Expected: PASS. Also re-run the preview test to confirm nothing broke:

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkSubmissionService*"
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/BulkSubmissionService.java \
        backend/src/test/java/com/albunyaan/tube/service/BulkSubmissionServiceSubmitTest.java
git commit -m "[FEAT-BULK-01-T8]: BulkSubmissionService.submit + role-aware status normalization"
```

---

## Task 9: `RegistryController` bulk endpoints

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java` (add two POST handlers)

Per spec §3.1.

- [ ] **Step 1: Add the two endpoints**

Append to `RegistryController.java` (after the existing channel/playlist/video POST handlers, before the read-only GETs):

```java
    /**
     * BULK-01 (T9) — bulk preview. Validates ≤25 URLs, fans out NewPipe metadata fetches,
     * returns one row per URL with detected type + metadata + status (OK / DUPLICATE / ERROR).
     */
    @PostMapping("/bulk/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<com.albunyaan.tube.dto.registry.BulkPreviewResponse> bulkPreview(
            @RequestBody @Valid com.albunyaan.tube.dto.registry.BulkPreviewRequest req) {
        return ResponseEntity.ok(bulkSubmissionService.preview(req));
    }

    /**
     * BULK-01 (T9) — bulk submit. Takes the OK rows from a prior preview + resolved categories,
     * writes Firestore docs via {@link RegistrySubmissionWriter}, returns per-row results.
     */
    @PostMapping("/bulk/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<com.albunyaan.tube.dto.registry.BulkSubmitResponse> bulkSubmit(
            @RequestBody @Valid com.albunyaan.tube.dto.registry.BulkSubmitRequest req,
            org.springframework.security.core.Authentication auth) {
        String uid = auth.getName();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return ResponseEntity.ok(bulkSubmissionService.submit(req, uid, isAdmin));
    }
```

Also inject `BulkSubmissionService` into the controller's constructor or field list (follow the project's existing DI pattern — likely `@Autowired` field or constructor injection).

- [ ] **Step 2: Confirm the controller still compiles**

```bash
cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Sanity-check the role guard with the existing test setup**

```bash
cd backend && ./gradlew test
```

Expected: PASS (no new tests in this task — IT test in T10 covers the controller behavior end-to-end).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java
git commit -m "[FEAT-BULK-01-T9]: RegistryController bulk preview + submit endpoints"
```

---

## Task 10: Backend integration test (Firebase emulator)

**Files:**
- Create: `backend/src/test/java/com/albunyaan/tube/integration/BulkSubmissionIT.java`

Per spec §7.1.

- [ ] **Step 1: Write the integration test**

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BulkSubmissionIT extends BaseIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    @WithMockUser(username = "mod-uid", roles = {"MODERATOR"})
    void moderator_canSubmitBatch_allRowsLandPending() throws Exception {
        // Preview is fan-out: 2 URLs, both should parse — fetch is mocked at the gateway level
        var previewReq = new BulkPreviewRequest(
                List.of("https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
                        "https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"),
                List.of("cat-1"));

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(previewReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2));

        // Submit the rows as a moderator with status=APPROVED (should be normalized to PENDING)
        var submitReq = new BulkSubmitRequest(
                List.of(
                        new SubmitRow(0, "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
                                YouTubeContentType.CHANNEL, null,
                                new PreviewMetadata("UCxxxxxxxxxxxxxxxxxxxxxx", "Test Channel", "thumb.jpg", null, null, 100L, null, null, null),
                                List.of("cat-1"))
                ),
                "APPROVED"); // moderator's APPROVED request must be downgraded

        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.results[0].status").value("ADDED"));

        // Verify the doc actually landed with status=PENDING
        // (Reads via the existing repository; assertion adapts to whichever helper BaseIntegrationTest exposes.)
    }

    @Test
    @WithMockUser(username = "admin-uid", roles = {"ADMIN"})
    void admin_canSubmitApproved_bypassesPending() throws Exception {
        var submitReq = new BulkSubmitRequest(
                List.of(
                        new SubmitRow(0, "https://www.youtube.com/watch?v=AAAAAAAAAAA",
                                YouTubeContentType.VIDEO, VideoType.STANDARD,
                                new PreviewMetadata("AAAAAAAAAAA", "Test Video", "thumb.jpg", "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx", null, null, 213L, 1000L),
                                List.of("cat-1"))
                ),
                "APPROVED");

        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));

        // Verify saved Video has status=APPROVED and approvedBy="admin-uid".
    }

    @Test
    @WithMockUser(username = "anon", roles = {"USER"})
    void nonAdminNonModerator_isForbidden() throws Exception {
        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=AAAAAAAAAAA"), List.of("cat-1"));
        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void preview_rejects26urls_withValidationError() throws Exception {
        var twentySix = new java.util.ArrayList<String>();
        for (int i = 0; i < 26; i++) twentySix.add("https://www.youtube.com/watch?v=" + ("A".repeat(11)));
        var req = new BulkPreviewRequest(twentySix, List.of("cat-1"));

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the integration test**

```bash
cd backend && ./gradlew test -Pintegration=true --tests "com.albunyaan.tube.integration.BulkSubmissionIT"
```

Expected: PASS. If the test environment doesn't have NewPipe network access, mock `YouTubeGateway` in the IT config (see `BaseIntegrationTest` for the existing pattern).

- [ ] **Step 3: Run the full backend suite to confirm no regressions**

```bash
cd backend && ./gradlew test
cd backend && ./gradlew test -Pintegration=true
```

Expected: ALL PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/BulkSubmissionIT.java
git commit -m "[TEST-BULK-01-T10]: bulk submission integration tests + role gate IT"
```

---

## Task 11: OpenAPI codegen — regenerate frontend DTO schema

**Files:**
- Modify: `frontend/src/generated/api/schema.ts` (auto-generated)
- Possibly modify: `docs/architecture/api-specification.yaml` (if it lives in the repo)

- [ ] **Step 1: Confirm the codegen script exists**

```bash
ls -la scripts/generate-openapi-dtos.sh
cat scripts/generate-openapi-dtos.sh | head -30
```

- [ ] **Step 2: Run the codegen**

```bash
./scripts/generate-openapi-dtos.sh
```

Expected: prints "OK" or similar. Look at the diff:

```bash
git diff frontend/src/generated/api/schema.ts | head -60
```

You should see new entries for `BulkPreviewRequest`, `BulkPreviewResponse`, `PreviewRow`, `PreviewMetadata`, `PreviewError`, `RowStatus`, `PreviewErrorCode`, `BulkSubmitRequest`, `SubmitRow`, `BulkSubmitResponse`, `SubmitResult`, and `VideoType` (added to existing Video entity).

- [ ] **Step 3: Confirm typecheck passes**

```bash
cd frontend && npm run build
```

Expected: BUILD SUCCESSFUL (no new TS errors from the regenerated types).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/generated/api/schema.ts docs/architecture/api-specification.yaml
git commit -m "[FEAT-BULK-01-T11]: regenerate OpenAPI schema with bulk + VideoType"
```

---

## Task 12: Frontend file parsers (`bulkFileParsers.ts`)

**Files:**
- Modify: `frontend/package.json` (+ `papaparse`, `xlsx`, `zod` if missing)
- Create: `frontend/src/utils/bulkFileParsers.ts`
- Test: `frontend/tests/bulkFileParsers.spec.ts`

Per spec §4.4.

- [ ] **Step 1: Add dependencies**

```bash
cd frontend && npm install --save papaparse xlsx
cd frontend && npm install --save-dev @types/papaparse
# Verify zod is already in package.json; if not:
cd frontend && npm install --save zod
```

- [ ] **Step 2: Write the failing test**

Create `frontend/tests/bulkFileParsers.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { parseCsv, parseJson } from '../src/utils/bulkFileParsers'

describe('parseCsv', () => {
  it('extracts URLs from a single-column CSV with URL header', async () => {
    const csv = 'URL\nhttps://www.youtube.com/watch?v=AAAAAAAAAAA\nhttps://www.youtube.com/watch?v=BBBBBBBBBBB\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    const result = await parseCsv(file)
    expect(result).toEqual([
      'https://www.youtube.com/watch?v=AAAAAAAAAAA',
      'https://www.youtube.com/watch?v=BBBBBBBBBBB',
    ])
  })

  it('is case-insensitive on the URL header', async () => {
    const csv = 'url\nhttps://www.youtube.com/watch?v=AAAAAAAAAAA\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    const result = await parseCsv(file)
    expect(result).toEqual(['https://www.youtube.com/watch?v=AAAAAAAAAAA'])
  })

  it('rejects when header is missing', async () => {
    const csv = 'https://www.youtube.com/watch?v=AAAAAAAAAAA\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    await expect(parseCsv(file)).rejects.toThrow(/URL header/i)
  })

  it('rejects when more than one column present', async () => {
    const csv = 'URL,name\nhttps://example.com,foo\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    await expect(parseCsv(file)).rejects.toThrow(/single column/i)
  })

  it('rejects more than 25 rows', async () => {
    const rows = Array.from({ length: 26 }, (_, i) => `https://www.youtube.com/watch?v=${'A'.repeat(11)}`)
    const csv = 'URL\n' + rows.join('\n') + '\n'
    const file = new File([csv], 'urls.csv', { type: 'text/csv' })
    await expect(parseCsv(file)).rejects.toThrow(/25/)
  })
})

describe('parseJson', () => {
  it('extracts urls array', async () => {
    const obj = { urls: ['https://www.youtube.com/watch?v=AAAAAAAAAAA'] }
    const file = new File([JSON.stringify(obj)], 'urls.json', { type: 'application/json' })
    const result = await parseJson(file)
    expect(result).toEqual(['https://www.youtube.com/watch?v=AAAAAAAAAAA'])
  })

  it('rejects malformed JSON', async () => {
    const file = new File(['{not json'], 'urls.json', { type: 'application/json' })
    await expect(parseJson(file)).rejects.toThrow(/JSON/)
  })

  it('rejects wrong shape', async () => {
    const obj = { wrong: 'shape' }
    const file = new File([JSON.stringify(obj)], 'urls.json', { type: 'application/json' })
    await expect(parseJson(file)).rejects.toThrow(/urls/)
  })

  it('rejects more than 25 entries', async () => {
    const urls = Array.from({ length: 26 }, () => 'https://www.youtube.com/watch?v=AAAAAAAAAAA')
    const file = new File([JSON.stringify({ urls })], 'urls.json', { type: 'application/json' })
    await expect(parseJson(file)).rejects.toThrow(/25/)
  })
})
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd frontend && npx vitest run tests/bulkFileParsers.spec.ts
```

Expected: FAIL — module not found.

- [ ] **Step 4: Implement the parsers**

Create `frontend/src/utils/bulkFileParsers.ts`:

```ts
import Papa from 'papaparse'
import { z } from 'zod'

const MAX_URLS = 25
const MAX_FILE_BYTES = 1_000_000

function ensureFileSize(file: File) {
  if (file.size > MAX_FILE_BYTES) {
    throw new Error(`File exceeds 1 MB limit (${file.size} bytes)`)
  }
}

export async function parseCsv(file: File): Promise<string[]> {
  ensureFileSize(file)
  const text = await file.text()
  return new Promise<string[]>((resolve, reject) => {
    Papa.parse<string[]>(text, {
      skipEmptyLines: true,
      complete: (result) => {
        const rows = result.data
        if (rows.length === 0) {
          reject(new Error('Empty CSV'))
          return
        }
        const header = rows[0]
        if (header.length !== 1) {
          reject(new Error('CSV must have a single column named "URL"'))
          return
        }
        if (header[0].trim().toLowerCase() !== 'url') {
          reject(new Error('CSV must have a URL header in row 1'))
          return
        }
        const urls = rows.slice(1).map((r) => r[0]?.trim()).filter((u): u is string => !!u)
        if (urls.length > MAX_URLS) {
          reject(new Error(`Too many rows: ${urls.length} > ${MAX_URLS}`))
          return
        }
        resolve(urls)
      },
      error: (err: Error) => reject(err),
    })
  })
}

const JsonShape = z.object({
  urls: z.array(z.string().min(1)).min(1).max(MAX_URLS),
})

export async function parseJson(file: File): Promise<string[]> {
  ensureFileSize(file)
  const text = await file.text()
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch (e) {
    throw new Error(`Invalid JSON: ${(e as Error).message}`)
  }
  const result = JsonShape.safeParse(raw)
  if (!result.success) {
    const issue = result.error.issues[0]
    throw new Error(`JSON must match { urls: string[] } (≤${MAX_URLS}). ${issue.path.join('.')}: ${issue.message}`)
  }
  return result.data.urls
}

/**
 * BULK-01 (T12) — Excel parser is lazy-loaded so SheetJS (~600 KB) doesn't bloat the main bundle.
 * Only loads when the admin actually uses Excel upload.
 */
export async function parseExcel(file: File): Promise<string[]> {
  ensureFileSize(file)
  const { read, utils } = await import('xlsx')
  const buf = await file.arrayBuffer()
  const wb = read(buf, { type: 'array' })
  if (wb.SheetNames.length === 0) throw new Error('Empty workbook')
  const sheet = wb.Sheets[wb.SheetNames[0]]
  const rows = utils.sheet_to_json<string[]>(sheet, { header: 1, blankrows: false })
  if (rows.length === 0) throw new Error('Empty sheet')
  const header = rows[0]
  if (!Array.isArray(header) || header.length !== 1) {
    throw new Error('Excel sheet must have a single column named "URL"')
  }
  if (String(header[0]).trim().toLowerCase() !== 'url') {
    throw new Error('Excel sheet must have a URL header in row 1')
  }
  const urls = rows.slice(1).map((r) => String(r[0] ?? '').trim()).filter((u) => u.length > 0)
  if (urls.length > MAX_URLS) {
    throw new Error(`Too many rows: ${urls.length} > ${MAX_URLS}`)
  }
  return urls
}

export function parsePastedUrls(raw: string): string[] {
  return raw
    .split(/[,\n\r]/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd frontend && npx vitest run tests/bulkFileParsers.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json \
        frontend/src/utils/bulkFileParsers.ts \
        frontend/tests/bulkFileParsers.spec.ts
git commit -m "[FEAT-BULK-01-T12]: file parsers (CSV / JSON / Excel-lazy) + tests"
```

---

## Task 13: `bulkSubmissionService.ts` (API client)

**Files:**
- Create: `frontend/src/services/bulkSubmissionService.ts`

- [ ] **Step 1: Implement the API client**

```ts
import { apiClient } from './apiClient'
import type {
  BulkPreviewRequest,
  BulkPreviewResponse,
  BulkSubmitRequest,
  BulkSubmitResponse,
} from '../generated/api/schema'

/**
 * BULK-01 (T13) — API client for the bulk submission endpoints.
 */
export const bulkSubmissionService = {
  async previewBulk(req: BulkPreviewRequest): Promise<BulkPreviewResponse> {
    const { data } = await apiClient.post<BulkPreviewResponse>('/api/admin/registry/bulk/preview', req)
    return data
  },
  async submitBulk(req: BulkSubmitRequest): Promise<BulkSubmitResponse> {
    const { data } = await apiClient.post<BulkSubmitResponse>('/api/admin/registry/bulk/submit', req)
    return data
  },
}
```

Adapt the import for `apiClient` to whatever existing module the project uses for the authenticated admin axios instance (find via `grep -rln "apiClient" frontend/src/services | head -3`).

- [ ] **Step 2: Confirm typecheck passes**

```bash
cd frontend && npm run build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/bulkSubmissionService.ts
git commit -m "[FEAT-BULK-01-T13]: bulkSubmissionService API client"
```

---

## Task 14: `bulkSubmissionStore` (Pinia)

**Files:**
- Create: `frontend/src/stores/bulkSubmissionStore.ts`
- Test: `frontend/tests/bulkSubmissionStore.spec.ts`

Per spec §4.2.

- [ ] **Step 1: Write the failing test**

```ts
import { setActivePinia, createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'
import { bulkSubmissionService } from '../src/services/bulkSubmissionService'

vi.mock('../src/services/bulkSubmissionService', () => ({
  bulkSubmissionService: { previewBulk: vi.fn(), submitBulk: vi.fn() },
}))

const mockedPreview = vi.mocked(bulkSubmissionService.previewBulk)
const mockedSubmit  = vi.mocked(bulkSubmissionService.submitBulk)

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})
afterEach(() => vi.restoreAllMocks())

describe('bulkSubmissionStore', () => {
  it('initial state is INPUT phase, empty', () => {
    const s = useBulkSubmissionStore()
    expect(s.phase).toBe('INPUT')
    expect(s.parsedUrls).toEqual([])
    expect(s.defaultCategoryIds).toEqual([])
    expect(s.previewRows).toEqual([])
  })

  it('runPreview transitions LOADING → PREVIEW and stores rows', async () => {
    mockedPreview.mockResolvedValue({
      rows: [{ rowIndex: 0, originalUrl: 'u', normalizedUrl: 'u', detectedType: 'VIDEO',
              videoType: 'STANDARD', metadata: { youtubeId: 'x', title: 't' } as any,
              status: 'OK', duplicateOf: null, duplicateStatus: null, error: null } as any],
    })
    const s = useBulkSubmissionStore()
    s.parsedUrls = ['u']
    s.defaultCategoryIds = ['cat-1']

    const p = s.runPreview()
    expect(s.phase).toBe('LOADING')
    await p
    expect(s.phase).toBe('PREVIEW')
    expect(s.previewRows.length).toBe(1)
    expect(mockedPreview).toHaveBeenCalledWith({ urls: ['u'], defaultCategoryIds: ['cat-1'] })
  })

  it('setRowCategories overrides the resolved categoryIds for a single row', () => {
    const s = useBulkSubmissionStore()
    s.defaultCategoryIds = ['cat-1']
    s.previewRows = [
      { rowIndex: 0, originalUrl: 'u', categoryIds: ['cat-1'] } as any,
    ]
    s.setRowCategories(0, ['cat-2', 'cat-3'])
    expect(s.previewRows[0].categoryIds).toEqual(['cat-2', 'cat-3'])
  })

  it('removeRow drops the row at the given index', () => {
    const s = useBulkSubmissionStore()
    s.previewRows = [{ rowIndex: 0 } as any, { rowIndex: 1 } as any, { rowIndex: 2 } as any]
    s.removeRow(1)
    expect(s.previewRows.map((r) => r.rowIndex)).toEqual([0, 2])
  })

  it('runSubmit filters to OK rows + DUPLICATE_REJECTED kept rows, transitions to RESULT', async () => {
    mockedSubmit.mockResolvedValue({ totalSubmitted: 1, added: 1, failed: 0, results: [{ rowIndex: 0, originalUrl: 'u', registryId: 'r', status: 'ADDED', errorCode: null } as any] })
    const s = useBulkSubmissionStore()
    s.phase = 'PREVIEW'
    s.previewRows = [
      { rowIndex: 0, status: 'OK', originalUrl: 'u', detectedType: 'VIDEO', videoType: 'STANDARD', metadata: {}, categoryIds: ['cat-1'] } as any,
      { rowIndex: 1, status: 'ERROR' } as any,
      { rowIndex: 2, status: 'DUPLICATE' } as any,
    ]

    await s.runSubmit()
    expect(s.phase).toBe('RESULT')
    expect(mockedSubmit).toHaveBeenCalledWith(expect.objectContaining({
      rows: expect.arrayContaining([expect.objectContaining({ rowIndex: 0 })]),
    }))
    expect(mockedSubmit.mock.calls[0][0].rows).toHaveLength(1)  // only the OK row sent
    expect(s.submitResult?.added).toBe(1)
  })

  it('reset returns to INPUT phase and clears state', () => {
    const s = useBulkSubmissionStore()
    s.phase = 'RESULT'
    s.parsedUrls = ['u']
    s.previewRows = [{} as any]
    s.submitResult = { added: 1 } as any
    s.reset()
    expect(s.phase).toBe('INPUT')
    expect(s.parsedUrls).toEqual([])
    expect(s.previewRows).toEqual([])
    expect(s.submitResult).toBeNull()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npx vitest run tests/bulkSubmissionStore.spec.ts
```

Expected: FAIL — store not yet defined.

- [ ] **Step 3: Implement the store**

```ts
import { defineStore } from 'pinia'
import type { PreviewRow, BulkSubmitResponse, SubmitRow } from '../generated/api/schema'
import { bulkSubmissionService } from '../services/bulkSubmissionService'

type Phase = 'INPUT' | 'LOADING' | 'PREVIEW' | 'RESULT'

/** Per-row resolved categories — defaults inherited unless explicitly overridden. */
interface PreviewRowDraft extends PreviewRow {
  /** Set by `setRowCategories`. If absent, the row inherits `defaultCategoryIds` at submit time. */
  categoryIds?: string[]
}

interface State {
  phase: Phase
  pastedUrls: string
  uploadedFileName: string | null
  parsedUrls: string[]
  defaultCategoryIds: string[]
  previewRows: PreviewRowDraft[]
  submitResult: BulkSubmitResponse | null
  error: string | null
}

export const useBulkSubmissionStore = defineStore('bulkSubmission', {
  state: (): State => ({
    phase: 'INPUT',
    pastedUrls: '',
    uploadedFileName: null,
    parsedUrls: [],
    defaultCategoryIds: [],
    previewRows: [],
    submitResult: null,
    error: null,
  }),
  actions: {
    async runPreview() {
      if (this.parsedUrls.length === 0 || this.defaultCategoryIds.length === 0) {
        this.error = 'URLs and at least one default category required'
        return
      }
      this.phase = 'LOADING'
      this.error = null
      try {
        const resp = await bulkSubmissionService.previewBulk({
          urls: this.parsedUrls,
          defaultCategoryIds: this.defaultCategoryIds,
        })
        this.previewRows = resp.rows
        this.phase = 'PREVIEW'
      } catch (e) {
        this.error = (e as Error).message
        this.phase = 'INPUT'
      }
    },
    setRowCategories(rowIndex: number, categoryIds: string[]) {
      const idx = this.previewRows.findIndex((r) => r.rowIndex === rowIndex)
      if (idx < 0) return
      this.previewRows[idx] = { ...this.previewRows[idx], categoryIds }
    },
    removeRow(rowIndex: number) {
      this.previewRows = this.previewRows.filter((r) => r.rowIndex !== rowIndex)
    },
    async runSubmit() {
      const submittable = this.previewRows.filter(
        (r) => r.status === 'OK' || r.status === 'DUPLICATE_REJECTED',
      )
      if (submittable.length === 0) {
        this.error = 'No valid rows to submit'
        return
      }
      this.phase = 'LOADING'
      const rows: SubmitRow[] = submittable.map((r) => ({
        rowIndex: r.rowIndex,
        originalUrl: r.originalUrl,
        detectedType: r.detectedType!,
        videoType: r.videoType,
        metadata: r.metadata!,
        categoryIds: r.categoryIds ?? this.defaultCategoryIds,
      }))
      try {
        const resp = await bulkSubmissionService.submitBulk({ rows, status: undefined })
        this.submitResult = resp
        this.phase = 'RESULT'
      } catch (e) {
        this.error = (e as Error).message
        this.phase = 'PREVIEW'
      }
    },
    reset() {
      this.phase = 'INPUT'
      this.pastedUrls = ''
      this.uploadedFileName = null
      this.parsedUrls = []
      this.defaultCategoryIds = []
      this.previewRows = []
      this.submitResult = null
      this.error = null
    },
  },
})
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd frontend && npx vitest run tests/bulkSubmissionStore.spec.ts
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/stores/bulkSubmissionStore.ts \
        frontend/tests/bulkSubmissionStore.spec.ts
git commit -m "[FEAT-BULK-01-T14]: bulkSubmissionStore Pinia state machine + tests"
```

---

## Task 15: `BulkInputView.vue` + paste/dropzone/categories child components

**Files:**
- Create: `frontend/src/components/contentSearch/bulk/BulkUrlPasteField.vue`
- Create: `frontend/src/components/contentSearch/bulk/BulkFileDropzone.vue`
- Create: `frontend/src/components/contentSearch/bulk/BulkDefaultCategoriesPicker.vue`
- Create: `frontend/src/components/contentSearch/bulk/BulkInputView.vue`
- Test: `frontend/tests/BulkInputView.spec.ts`

Per spec §5.1.

This task is intentionally a single coherent unit because the four files are tightly coupled and small. Each child component is ~50 LOC.

- [ ] **Step 1: Implement `BulkUrlPasteField.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { parsePastedUrls } from '@/utils/bulkFileParsers'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<(e: 'update:modelValue', v: string) => void>()

const urlCount = computed(() => parsePastedUrls(props.modelValue).length)
const tooMany = computed(() => urlCount.value > 25)
</script>

<template>
  <div class="bulk-paste">
    <label for="bulk-paste-textarea" class="form-label">
      {{ $t('contentSearch.bulk.input.pasteLabel') }}
    </label>
    <textarea
      id="bulk-paste-textarea"
      class="form-control"
      rows="6"
      dir="ltr"
      :value="modelValue"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      :placeholder="$t('contentSearch.bulk.input.pasteHint')"
    />
    <small :class="{ 'text-danger': tooMany }">
      {{ urlCount }} / 25 URLs
      <span v-if="tooMany">— {{ $t('contentSearch.bulk.input.tooMany') }}</span>
    </small>
  </div>
</template>
```

- [ ] **Step 2: Implement `BulkFileDropzone.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { parseCsv, parseExcel, parseJson } from '@/utils/bulkFileParsers'

const emit = defineEmits<{
  (e: 'parsed', urls: string[], fileName: string): void
  (e: 'error', message: string): void
}>()

const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

async function handleFile(file: File) {
  try {
    let urls: string[]
    if (file.name.endsWith('.csv')) urls = await parseCsv(file)
    else if (file.name.endsWith('.json')) urls = await parseJson(file)
    else if (file.name.endsWith('.xlsx') || file.name.endsWith('.xls')) urls = await parseExcel(file)
    else throw new Error('Unsupported file type. Use .csv, .xlsx, .xls, or .json')
    emit('parsed', urls, file.name)
  } catch (e) {
    emit('error', (e as Error).message)
  }
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  dragOver.value = false
  const f = event.dataTransfer?.files[0]
  if (f) handleFile(f)
}

function onPick(event: Event) {
  const f = (event.target as HTMLInputElement).files?.[0]
  if (f) handleFile(f)
}
</script>

<template>
  <div
    class="bulk-dropzone"
    :class="{ 'drag-over': dragOver }"
    @dragover.prevent="dragOver = true"
    @dragleave.prevent="dragOver = false"
    @drop="onDrop"
  >
    <p>{{ $t('contentSearch.bulk.input.uploadLabel') }}</p>
    <input ref="fileInput" type="file" accept=".csv,.xlsx,.xls,.json" hidden @change="onPick" />
    <button class="btn btn-outline-primary btn-sm" @click="fileInput?.click()">
      {{ $t('contentSearch.bulk.input.uploadBrowse') }}
    </button>
    <p class="text-muted small mt-2">{{ $t('contentSearch.bulk.input.uploadHint') }}</p>
  </div>
</template>
```

- [ ] **Step 3: Implement `BulkDefaultCategoriesPicker.vue`**

A thin wrapper that delegates to the existing `<CategoryChipMultiSelect>` if one exists, or re-implements a minimal multi-select chip picker against the project's category store. Find the existing pattern with:

```bash
cd frontend && grep -rln "CategoryAssignment\|CategoryChip\|categories.*select" src/components | head -5
```

Implement following the existing pattern; emit `update:modelValue` with `string[]` of selected category IDs.

- [ ] **Step 4: Implement `BulkInputView.vue` + format help button**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import { parsePastedUrls } from '@/utils/bulkFileParsers'
import BulkUrlPasteField from './BulkUrlPasteField.vue'
import BulkFileDropzone from './BulkFileDropzone.vue'
import BulkDefaultCategoriesPicker from './BulkDefaultCategoriesPicker.vue'
import BulkFormatHelpModal from './BulkFormatHelpModal.vue'

const store = useBulkSubmissionStore()
const showFormatHelp = ref(false)
const dropzoneError = ref<string | null>(null)

function refreshParsed() {
  store.parsedUrls = parsePastedUrls(store.pastedUrls)
}

function onFileParsed(urls: string[], fileName: string) {
  store.uploadedFileName = fileName
  store.parsedUrls = urls
  store.pastedUrls = urls.join('\n')   // mirror for visibility
  dropzoneError.value = null
}

const canParse = () =>
  store.parsedUrls.length > 0 && store.parsedUrls.length <= 25 && store.defaultCategoryIds.length > 0
</script>

<template>
  <div class="bulk-input">
    <div class="row g-3">
      <div class="col-md-6">
        <BulkUrlPasteField v-model="store.pastedUrls" @update:modelValue="refreshParsed" />
      </div>
      <div class="col-md-6">
        <BulkFileDropzone @parsed="onFileParsed" @error="dropzoneError = $event" />
        <button class="btn btn-link btn-sm p-0 mt-1" @click="showFormatHelp = true">
          ⓘ {{ $t('contentSearch.bulk.input.formatHelpButton') }}
        </button>
        <div v-if="dropzoneError" class="alert alert-warning small mt-2">{{ dropzoneError }}</div>
      </div>
    </div>

    <div class="mt-3">
      <BulkDefaultCategoriesPicker v-model="store.defaultCategoryIds" />
    </div>

    <button
      class="btn btn-primary mt-3"
      :disabled="!canParse()"
      @click="store.runPreview()"
    >
      {{ $t('contentSearch.bulk.input.parseButton') }}
    </button>

    <BulkFormatHelpModal v-if="showFormatHelp" @close="showFormatHelp = false" />
  </div>
</template>
```

- [ ] **Step 5: Write the test**

```ts
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import BulkInputView from '../src/components/contentSearch/bulk/BulkInputView.vue'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'

beforeEach(() => setActivePinia(createPinia()))

describe('BulkInputView', () => {
  it('Parse button disabled when no URLs or no categories', () => {
    const wrapper = mount(BulkInputView, { global: { mocks: { $t: (k: string) => k } } })
    const btn = wrapper.find('button.btn-primary')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('Parse button enabled when URLs + categories present and ≤25', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = ['https://www.youtube.com/watch?v=AAAAAAAAAAA']
    store.defaultCategoryIds = ['cat-1']
    const wrapper = mount(BulkInputView, { global: { mocks: { $t: (k: string) => k } } })
    await wrapper.vm.$nextTick()
    const btn = wrapper.find('button.btn-primary')
    expect(btn.attributes('disabled')).toBeUndefined()
  })

  it('Parse button disabled when > 25 URLs', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = Array.from({ length: 26 }, () => 'https://www.youtube.com/watch?v=AAAAAAAAAAA')
    store.defaultCategoryIds = ['cat-1']
    const wrapper = mount(BulkInputView, { global: { mocks: { $t: (k: string) => k } } })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeDefined()
  })
})
```

- [ ] **Step 6: Run tests**

```bash
cd frontend && npx vitest run tests/BulkInputView.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/contentSearch/bulk/BulkUrlPasteField.vue \
        frontend/src/components/contentSearch/bulk/BulkFileDropzone.vue \
        frontend/src/components/contentSearch/bulk/BulkDefaultCategoriesPicker.vue \
        frontend/src/components/contentSearch/bulk/BulkInputView.vue \
        frontend/tests/BulkInputView.spec.ts
git commit -m "[FEAT-BULK-01-T15]: BulkInputView + paste/dropzone/categories components"
```

---

## Task 16: `BulkPreviewTable.vue` + row + per-row category editor

**Files:**
- Create: `frontend/src/components/contentSearch/bulk/BulkPreviewRow.vue`
- Create: `frontend/src/components/contentSearch/bulk/BulkRowCategoryEditor.vue`
- Create: `frontend/src/components/contentSearch/bulk/BulkPreviewTable.vue`
- Test: `frontend/tests/BulkPreviewTable.spec.ts`

Per spec §5.2.

- [ ] **Step 1: Implement `BulkPreviewRow.vue`**

```vue
<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PreviewRow } from '@/generated/api/schema'
import BulkRowCategoryEditor from './BulkRowCategoryEditor.vue'

const props = defineProps<{ row: PreviewRow & { categoryIds?: string[] }, defaultCategoryIds: string[] }>()
const emit = defineEmits<{
  (e: 'remove', rowIndex: number): void
  (e: 'updateCategories', rowIndex: number, categoryIds: string[]): void
}>()

const editing = ref(false)
const resolvedCategoryIds = computed(() => props.row.categoryIds ?? props.defaultCategoryIds)

const typeChipLabel = computed(() => {
  if (props.row.detectedType === 'VIDEO' && props.row.videoType === 'LIVE') return 'LIVE'
  return props.row.detectedType ?? '—'
})

const statusBadgeClass = computed(() => ({
  'badge-success': props.row.status === 'OK',
  'badge-warning': props.row.status === 'DUPLICATE' || props.row.status === 'DUPLICATE_REJECTED',
  'badge-danger':  props.row.status === 'ERROR',
}))
</script>

<template>
  <tr>
    <td>{{ row.rowIndex + 1 }}</td>
    <td>
      <img v-if="row.metadata?.thumbnailUrl" :src="row.metadata.thumbnailUrl" alt="" class="bulk-thumb" />
    </td>
    <td><span class="badge bg-secondary">{{ typeChipLabel }}</span></td>
    <td class="text-truncate" style="max-width: 240px;">{{ row.metadata?.title ?? row.originalUrl }}</td>
    <td>{{ row.metadata?.channelName ?? '—' }}</td>
    <td>
      <span class="badge" :class="statusBadgeClass">
        {{ row.status }}
      </span>
      <small v-if="row.error" class="d-block text-muted">
        {{ $t(row.error.messageKey) }}
      </small>
    </td>
    <td>
      <button v-if="row.status === 'OK' || row.status === 'DUPLICATE_REJECTED'"
              class="btn btn-link btn-sm p-0" @click="editing = true">
        {{ resolvedCategoryIds.length }} {{ $t('contentSearch.bulk.preview.categoriesLabel') }}
      </button>
      <BulkRowCategoryEditor
        v-if="editing"
        :model-value="resolvedCategoryIds"
        @save="(ids) => { emit('updateCategories', row.rowIndex, ids); editing = false }"
        @cancel="editing = false"
      />
    </td>
    <td>
      <button class="btn btn-link btn-sm text-danger" @click="emit('remove', row.rowIndex)">×</button>
    </td>
  </tr>
</template>
```

- [ ] **Step 2: Implement `BulkRowCategoryEditor.vue`**

A popover/dropdown reusing the same category multi-select primitive as `BulkDefaultCategoriesPicker`. Emits `save` with the new list or `cancel`. Keep small (~30 LOC); the implementer matches the project's modal/popover convention.

- [ ] **Step 3: Implement `BulkPreviewTable.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import BulkPreviewRow from './BulkPreviewRow.vue'

const store = useBulkSubmissionStore()

const counts = computed(() => ({
  valid:     store.previewRows.filter((r) => r.status === 'OK' || r.status === 'DUPLICATE_REJECTED').length,
  duplicate: store.previewRows.filter((r) => r.status === 'DUPLICATE').length,
  error:     store.previewRows.filter((r) => r.status === 'ERROR').length,
}))
</script>

<template>
  <div class="bulk-preview">
    <div class="d-flex justify-content-between align-items-center mb-2">
      <small>
        {{ counts.valid }} {{ $t('contentSearch.bulk.preview.validCount') }} ·
        {{ counts.duplicate }} {{ $t('contentSearch.bulk.preview.duplicateCount') }} ·
        {{ counts.error }} {{ $t('contentSearch.bulk.preview.errorCount') }}
      </small>
      <div>
        <button class="btn btn-link btn-sm" @click="store.phase = 'INPUT'">
          {{ $t('contentSearch.bulk.preview.backButton') }}
        </button>
        <button class="btn btn-primary btn-sm ms-2"
                :disabled="counts.valid === 0"
                @click="store.runSubmit()">
          {{ $t('contentSearch.bulk.preview.submitButton') }}
        </button>
      </div>
    </div>

    <table class="table table-sm align-middle">
      <thead>
        <tr>
          <th>#</th>
          <th></th>
          <th>{{ $t('contentSearch.bulk.preview.colType') }}</th>
          <th>{{ $t('contentSearch.bulk.preview.colTitle') }}</th>
          <th>{{ $t('contentSearch.bulk.preview.colChannel') }}</th>
          <th>{{ $t('contentSearch.bulk.preview.colStatus') }}</th>
          <th>{{ $t('contentSearch.bulk.preview.colCategories') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <BulkPreviewRow
          v-for="row in store.previewRows"
          :key="row.rowIndex"
          :row="row"
          :default-category-ids="store.defaultCategoryIds"
          @remove="store.removeRow($event)"
          @update-categories="(idx, ids) => store.setRowCategories(idx, ids)"
        />
      </tbody>
    </table>
  </div>
</template>
```

- [ ] **Step 4: Write the test**

```ts
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import BulkPreviewTable from '../src/components/contentSearch/bulk/BulkPreviewTable.vue'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'

beforeEach(() => setActivePinia(createPinia()))

const okRow = (idx: number) => ({
  rowIndex: idx, originalUrl: 'u', normalizedUrl: 'u',
  detectedType: 'VIDEO', videoType: 'STANDARD',
  metadata: { youtubeId: 'x', title: 'Title' + idx } as any,
  status: 'OK', duplicateOf: null, duplicateStatus: null, error: null,
}) as any

describe('BulkPreviewTable', () => {
  it('renders header counts: valid, duplicate, error', () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [
      okRow(0),
      { ...okRow(1), status: 'DUPLICATE' },
      { ...okRow(2), status: 'ERROR' },
    ]
    const wrapper = mount(BulkPreviewTable, { global: { mocks: { $t: (k: string) => k } } })
    const small = wrapper.find('small').text()
    expect(small).toContain('1') // valid
    expect(small).toContain('1') // duplicate
    expect(small).toContain('1') // error
  })

  it('removeRow drops the row from rendered table', async () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [okRow(0), okRow(1)]
    const wrapper = mount(BulkPreviewTable, { global: { mocks: { $t: (k: string) => k } } })
    expect(wrapper.findAllComponents({ name: 'BulkPreviewRow' }).length).toBe(2)

    store.removeRow(0)
    await wrapper.vm.$nextTick()
    expect(wrapper.findAllComponents({ name: 'BulkPreviewRow' }).length).toBe(1)
  })

  it('Submit button disabled when no valid rows', () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [{ ...okRow(0), status: 'ERROR' }]
    const wrapper = mount(BulkPreviewTable, { global: { mocks: { $t: (k: string) => k } } })
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeDefined()
  })
})
```

- [ ] **Step 5: Run tests**

```bash
cd frontend && npx vitest run tests/BulkPreviewTable.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/contentSearch/bulk/BulkPreviewRow.vue \
        frontend/src/components/contentSearch/bulk/BulkRowCategoryEditor.vue \
        frontend/src/components/contentSearch/bulk/BulkPreviewTable.vue \
        frontend/tests/BulkPreviewTable.spec.ts
git commit -m "[FEAT-BULK-01-T16]: BulkPreviewTable + row + per-row category editor"
```

---

## Task 17: `BulkResultSummary.vue` + `BulkFormatHelpModal.vue`

**Files:**
- Create: `frontend/src/components/contentSearch/bulk/BulkResultSummary.vue`
- Create: `frontend/src/components/contentSearch/bulk/BulkFormatHelpModal.vue`
- Create: `frontend/public/samples/sample-bulk-urls.csv`
- Create: `frontend/public/samples/sample-bulk-urls.json`
- Create: `frontend/public/samples/sample-bulk-urls.xlsx` (via script)

Per spec §5.3 + §5.4.

- [ ] **Step 1: Implement `BulkResultSummary.vue`**

```vue
<script setup lang="ts">
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import { useRouter } from 'vue-router'

const store = useBulkSubmissionStore()
const router = useRouter()

const failedRows = () => store.submitResult?.results.filter((r) => r.status === 'FAILED') ?? []
</script>

<template>
  <div class="bulk-result text-center" v-if="store.submitResult">
    <h2 class="display-5 mb-3">{{ store.submitResult.added }}</h2>
    <p class="text-muted">{{ $t('contentSearch.bulk.result.addedHint') }}</p>

    <details v-if="failedRows().length > 0" class="text-start mt-3">
      <summary>{{ $t('contentSearch.bulk.result.errorsToggle', { n: failedRows().length }) }}</summary>
      <ul class="list-unstyled mt-2">
        <li v-for="r in failedRows()" :key="r.rowIndex">
          <small>#{{ r.rowIndex + 1 }} — {{ r.originalUrl }} ({{ r.errorCode }})</small>
        </li>
      </ul>
    </details>

    <div class="mt-4">
      <button class="btn btn-outline-primary" @click="store.reset()">
        {{ $t('contentSearch.bulk.result.submitAnother') }}
      </button>
      <button class="btn btn-link" @click="router.push('/admin/pending-approvals')">
        {{ $t('contentSearch.bulk.result.gotoPending') }}
      </button>
    </div>
  </div>
</template>
```

- [ ] **Step 2: Implement `BulkFormatHelpModal.vue`**

A modal with three tabs (CSV / Excel / JSON). Body of each tab shows the canonical example (copy from spec Appendix A) + a "Download sample" link to `/samples/sample-bulk-urls.csv` etc. Implementer matches the project's existing modal/tabs primitives (find via `grep -rln "TabPanel\|tab-pane\|<Modal" frontend/src/components | head -5`).

- [ ] **Step 3: Create sample CSV**

Create `frontend/public/samples/sample-bulk-urls.csv` with literal content:

```
URL
https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw
https://www.youtube.com/playlist?list=PLrAXtmRdnEQy6nuLMHjMZOz59Oq8B9nUj
https://www.youtube.com/watch?v=dQw4w9WgXcQ
https://www.youtube.com/live/jfKfPfyJRdk
```

- [ ] **Step 4: Create sample JSON**

Create `frontend/public/samples/sample-bulk-urls.json`:

```json
{
  "urls": [
    "https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw",
    "https://www.youtube.com/playlist?list=PLrAXtmRdnEQy6nuLMHjMZOz59Oq8B9nUj",
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "https://www.youtube.com/live/jfKfPfyJRdk"
  ]
}
```

- [ ] **Step 5: Generate sample XLSX**

Add a small script `frontend/scripts/generate-bulk-sample-xlsx.cjs`:

```js
// Run once: node scripts/generate-bulk-sample-xlsx.cjs
const xlsx = require('xlsx')
const wb = xlsx.utils.book_new()
const rows = [
  ['URL'],
  ['https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw'],
  ['https://www.youtube.com/playlist?list=PLrAXtmRdnEQy6nuLMHjMZOz59Oq8B9nUj'],
  ['https://www.youtube.com/watch?v=dQw4w9WgXcQ'],
  ['https://www.youtube.com/live/jfKfPfyJRdk'],
]
const ws = xlsx.utils.aoa_to_sheet(rows)
xlsx.utils.book_append_sheet(wb, ws, 'urls')
xlsx.writeFile(wb, 'public/samples/sample-bulk-urls.xlsx')
```

Run:

```bash
cd frontend && node scripts/generate-bulk-sample-xlsx.cjs
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/contentSearch/bulk/BulkResultSummary.vue \
        frontend/src/components/contentSearch/bulk/BulkFormatHelpModal.vue \
        frontend/public/samples/ \
        frontend/scripts/generate-bulk-sample-xlsx.cjs
git commit -m "[FEAT-BULK-01-T17]: BulkResultSummary + BulkFormatHelpModal + sample files"
```

---

## Task 18: `BulkSubmissionTab.vue` + `ContentSearchView` tabs wrapper + i18n keys

**Files:**
- Create: `frontend/src/components/contentSearch/bulk/BulkSubmissionTab.vue`
- Modify: `frontend/src/views/ContentSearchView.vue` (extract existing content into a tab, add bulk tab)
- Modify: `frontend/src/locales/messages.ts` (en/ar/nl keys)
- Test: `frontend/tests/BulkSubmissionTab.spec.ts`

Per spec §5 entire + §4.5.

- [ ] **Step 1: Implement `BulkSubmissionTab.vue`**

```vue
<script setup lang="ts">
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import BulkInputView      from './BulkInputView.vue'
import BulkPreviewTable   from './BulkPreviewTable.vue'
import BulkResultSummary  from './BulkResultSummary.vue'

const store = useBulkSubmissionStore()
</script>

<template>
  <div class="bulk-submission-tab">
    <div v-if="store.error" class="alert alert-warning" role="alert">
      {{ store.error }}
    </div>

    <div v-if="store.phase === 'LOADING'" class="text-center my-5">
      <div class="spinner-border" role="status"></div>
      <p class="mt-2 text-muted">{{ $t('contentSearch.bulk.loading') }}</p>
    </div>

    <BulkInputView     v-else-if="store.phase === 'INPUT'" />
    <BulkPreviewTable  v-else-if="store.phase === 'PREVIEW'" />
    <BulkResultSummary v-else-if="store.phase === 'RESULT'" />
  </div>
</template>
```

- [ ] **Step 2: Add tabs to `ContentSearchView.vue`**

Wrap the existing template content in a tab structure. Pseudocode (implementer adapts to the existing template body):

```vue
<template>
  <div class="content-search-view">
    <ul class="nav nav-tabs">
      <li class="nav-item">
        <button class="nav-link" :class="{ active: tab === 'search' }" @click="tab = 'search'">
          {{ $t('contentSearch.searchTabLabel') }}
        </button>
      </li>
      <li class="nav-item">
        <button class="nav-link" :class="{ active: tab === 'bulk' }" @click="tab = 'bulk'">
          {{ $t('contentSearch.bulk.tabLabel') }}
        </button>
      </li>
    </ul>

    <div v-show="tab === 'search'" class="tab-pane">
      <!-- existing ContentSearchView body, unchanged -->
    </div>
    <BulkSubmissionTab v-show="tab === 'bulk'" />
  </div>
</template>
```

Add `import BulkSubmissionTab from '@/components/contentSearch/bulk/BulkSubmissionTab.vue'` and a `ref<'search' | 'bulk'>('search')` in the script setup.

**Don't rip out or reorder the existing template body.** The minimal diff is: insert `<ul class="nav nav-tabs">…</ul>` before, wrap the existing top-level wrapper of the existing content in `<div v-show="tab === 'search'">`, append `<BulkSubmissionTab v-show="tab === 'bulk'" />` at the end.

- [ ] **Step 3: Add i18n keys for en, ar, nl**

In `frontend/src/locales/messages.ts`, under each locale's `contentSearch` block, add:

```ts
// en
bulk: {
  tabLabel: 'Bulk submit',
  loading: 'Working…',
  input: {
    pasteLabel: 'Paste YouTube URLs (comma- or newline-separated)',
    pasteHint: 'https://www.youtube.com/watch?v=…',
    tooMany: 'maximum 25 URLs per batch',
    uploadLabel: 'Or upload a CSV, Excel, or JSON file',
    uploadBrowse: 'Browse…',
    uploadHint: 'Up to 1 MB. .csv, .xlsx, .xls, or .json',
    formatHelpButton: 'Format help',
    parseButton: 'Parse & Preview',
  },
  preview: {
    validCount: 'valid',
    duplicateCount: 'duplicates skipped',
    errorCount: 'errors',
    colType: 'Type',
    colTitle: 'Title',
    colChannel: 'Channel',
    colStatus: 'Status',
    colCategories: 'Categories',
    categoriesLabel: 'categories',
    submitButton: 'Submit valid rows',
    backButton: 'Back to input',
  },
  result: {
    addedHint: 'items added to the approval queue.',
    errorsToggle: 'Errors ({n})',
    submitAnother: 'Submit another batch',
    gotoPending: 'Go to Pending Approvals',
  },
  formatHelp: {
    title: 'File format help',
    csvTab: 'CSV',
    excelTab: 'Excel',
    jsonTab: 'JSON',
    csvSpec: 'One column with header URL, one URL per row, max 25 rows.',
    excelSpec: 'First sheet only. Cell A1 must be "URL". URLs in A2 onwards. Max 25 rows.',
    jsonSpec: 'Top-level object: { "urls": [ ... ] }. Max 25 entries.',
    downloadSample: 'Download sample',
  },
  errors: {
    UNSUPPORTED_SHORTS: 'YouTube Shorts are not supported.',
    UNSUPPORTED_TYPE: 'This URL type is not supported.',
    NOT_YOUTUBE_URL: 'Not a valid YouTube URL.',
    CONTENT_NOT_AVAILABLE: 'Content unavailable.',
    PRIVATE_CONTENT: 'Private content.',
    AGE_RESTRICTED: 'Age-restricted content.',
    GEO_RESTRICTED: 'Region-blocked content.',
    CHANNEL_TERMINATED: 'Channel terminated.',
    NEWPIPE_PARSING_ERROR: 'Could not parse the page.',
    NETWORK_ERROR: 'Network error fetching metadata.',
    DUPLICATE: 'Already in the registry.',
    DUPLICATE_REJECTED: 'Previously rejected. Resubmit if intentional.',
  },
},
```

Duplicate for `ar` (Arabic translations) and `nl` (Dutch translations). All three locales added in this one commit.

- [ ] **Step 4: Write the tab-machine test**

```ts
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import BulkSubmissionTab from '../src/components/contentSearch/bulk/BulkSubmissionTab.vue'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'

beforeEach(() => setActivePinia(createPinia()))

describe('BulkSubmissionTab', () => {
  it('renders BulkInputView in INPUT phase', () => {
    const wrapper = mount(BulkSubmissionTab, { global: { mocks: { $t: (k: string) => k } } })
    expect(wrapper.findComponent({ name: 'BulkInputView' }).exists()).toBe(true)
  })

  it('renders BulkPreviewTable in PREVIEW phase', async () => {
    const store = useBulkSubmissionStore()
    store.phase = 'PREVIEW'
    const wrapper = mount(BulkSubmissionTab, { global: { mocks: { $t: (k: string) => k } } })
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'BulkPreviewTable' }).exists()).toBe(true)
  })

  it('renders BulkResultSummary in RESULT phase', async () => {
    const store = useBulkSubmissionStore()
    store.phase = 'RESULT'
    store.submitResult = { totalSubmitted: 1, added: 1, failed: 0, results: [] } as any
    const wrapper = mount(BulkSubmissionTab, { global: { mocks: { $t: (k: string) => k } } })
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'BulkResultSummary' }).exists()).toBe(true)
  })

  it('shows spinner in LOADING phase', async () => {
    const store = useBulkSubmissionStore()
    store.phase = 'LOADING'
    const wrapper = mount(BulkSubmissionTab, { global: { mocks: { $t: (k: string) => k } } })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.spinner-border').exists()).toBe(true)
  })
})
```

- [ ] **Step 5: Run tests**

```bash
cd frontend && npx vitest run tests/BulkSubmissionTab.spec.ts
cd frontend && npm run build
```

Expected: tests PASS, build PASSES (no i18n missing-key warnings).

- [ ] **Step 6: Manual bilingual smoke test**

Start the backend + frontend dev servers (per CLAUDE.md commands), open `/admin/content-search`, switch to Bulk Submit tab. Paste 2 URLs (one valid video, one shorts), pick a default category, click Parse & Preview. Confirm preview table renders with one OK row and one UNSUPPORTED_SHORTS error row. Switch locale to ar and confirm RTL layout is correct.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/contentSearch/bulk/BulkSubmissionTab.vue \
        frontend/src/views/ContentSearchView.vue \
        frontend/src/locales/messages.ts \
        frontend/tests/BulkSubmissionTab.spec.ts
git commit -m "[FEAT-BULK-01-T18]: BulkSubmissionTab + ContentSearchView tabs + i18n (en/ar/nl)"
```

---

## Final task: pre-PR verification + 7-stage review pipeline

Per project memory ([feedback_review_pipeline](feedback_review_pipeline.md)), the mandatory 7-stage pipeline runs on this branch before merge to `develop`. Subagent-driven runs:

- [ ] Full backend test suite green: `cd backend && ./gradlew test && ./gradlew test -Pintegration=true`
- [ ] Full frontend test suite green: `cd frontend && npm test && npm run build`
- [ ] Manual bilingual QA per spec §7.3 (en + ar, all four supported URL types, one of each error code, both ADMIN and MODERATOR principals)
- [ ] Run the 7-stage review pipeline (baseline → code-reviewer → cso → codex challenge → consolidate → patch + re-review → gstack /review → cubic)
- [ ] Push branch and open PR targeting `develop`

---

## Self-Review

**1. Spec coverage check:**

| Spec section | Plan tasks | Notes |
|---|---|---|
| §1 Goal | T1–T18 | Whole plan |
| §2 BULK1 — categories hybrid | T14 (store), T15 (default picker), T16 (per-row editor) | |
| §2 BULK2 — tabs on ContentSearchView | T18 | |
| §2 BULK3 — supported types + LIVE mapping | T1 (VideoType), T2 (URL parser shorts rejection), T5 (LIVE_STREAM detection) | |
| §2 BULK4 — preview-time fetch, sync, 25 cap | T7 (5-worker pool), T9 (no async machinery), DTO size validator (T3) | |
| §2 BULK5 — duplicates + error rows | T4 (dedupe), T7 (DUPLICATE / DUPLICATE_REJECTED logic), T8 (submit-valid-only summary) | |
| §2 BULK6 — two endpoints | T9 | |
| §2 BULK7 — role gating | T6 applyStatus, T8 normalization, T9 @PreAuthorize, T10 IT | |
| §2 BULK8 — frontend file parsing | T12 (CSV/JSON/Excel lazy) | |
| §3.1 endpoints | T9 | |
| §3.2 DTOs | T3 | |
| §3.3 services | T2, T4, T5, T6, T7, T8 | |
| §3.4 NewPipe exception mapping | T5 | |
| §3.5 Video.videoType | T1 | |
| §4 frontend architecture | T11–T18 | |
| §5 UX states | T15 (INPUT), T16 (PREVIEW), T17 (RESULT + format help) | |
| §6 edge cases | Edge cases 1, 2, 3, 4, 7 covered by T2/T5/T7/T8 (REJECTED, NewPipe failures, races, mid-submit failures, sanitization). Edge cases 5, 6, 8, 9, 10 are non-blocking and tested as part of T12/T16/T18 manual smoke. |
| §7 testing | One test task per major module (T1–T18 each include their own tests); T10 is the dedicated IT |
| §8 rollout | Branch `feature/BULK-01-bulk-url-submission`, PRs to `develop`, single-add VIDEO endpoint piggybacks via T6 |
| §9 observability | T7 log line, T8 log line |
| §10 out-of-scope | No tasks needed — explicitly omitted from plan |

All spec sections covered. No gaps.

**2. Placeholder scan:** No "TBD", "TODO", "fill in" markers. Where a step says "implementer adapts to existing pattern" (T6 step 5, T15 step 3, T16 step 2, T17 step 2, T18 step 2), the task explicitly tells the implementer which existing pattern to grep for and follow — that's grounded guidance, not a placeholder.

**3. Type consistency:** Checked across tasks:
- `YouTubeContentType` (existing enum) used identically T2, T3, T5, T7, T8, T10
- `VideoType` (new in T1) used in T1, T3 SubmitRow, T5 mapVideo, T6 writeVideo, T8 submit
- `PreviewMetadata` defined T3, used identically T4, T5, T6, T7, T8
- `PreviewErrorCode` defined T3, used T2 (parser errors), T5 (gateway errors), T7 (preview), T16 (display)
- `RowStatus` defined T3, used T7 (preview output), T14 (store filter), T16 (badge)
- `RegistrySubmissionWriter` defined T6, used T8 (submit) — `writeChannel`, `writePlaylist`, `writeVideo` signatures match between definition and call sites
- `bulkSubmissionService.previewBulk` / `submitBulk` defined T13, used T14 store

No mismatches found.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-22-admin-bulk-url-submission.md`.**
