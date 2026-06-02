# YouTube Import → Me List — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Brownfield note:** Several tasks modify large existing files. Where a step says **READ FIRST**, open the named file and integrate against its real current shape — do not assume the snippet here is the file's exact content. New files include full code. Follow existing patterns (Hilt DI on Android, Controller→Service→Repository on backend, Pinia/Vue on frontend).

**Goal:** Let a signed-in user import their YouTube subscriptions, created playlists, and liked videos into the Me list — approved items join the curated feed, unknown items go to the admin approval queue and an "Awaiting review" section, graduating into the feed on approval via the existing account-sync engine.

**Architecture:** Android performs incremental `youtube.readonly` OAuth and all YouTube Data API calls on-device (no token ever reaches our servers), then calls a thin authenticated backend endpoint that resolves each item against the content registry and submits unknowns for approval. Per-user Me-list rows gain an `approvalStatus` field; admin approve/reject runs a Firestore collection-group fan-out that flips/tombstones matching `AWAITING` rows, and the already-shipped Plan D sync engine delivers the change to every device.

**Tech Stack:** Backend Spring Boot + Firestore (JUnit 5, Mockito, Firebase emulator). Android Kotlin MVVM + Hilt + Room + Retrofit/OkHttp + Google Identity Services `AuthorizationClient` (JUnit4, Robolectric, MockWebServer, room-testing, coroutines-test). Frontend Vue 3 + Pinia (Vitest).

**Spec:** `docs/superpowers/specs/2026-06-02-youtube-import-me-list-design.md`

**Ticket prefixes:** `[BACKEND-IMPORT-NN]`, `[ANDROID-IMPORT-NN]`, `[ADMIN-IMPORT-NN]`.

**Test commands (project conventions; 300s wall / 30s per method):**
- Backend unit: `cd backend && ./gradlew test --tests "<FQN>"`
- Backend + integration: `cd backend && ./gradlew test -Pintegration=true --tests "<FQN>"` (needs `GOOGLE_APPLICATION_CREDENTIALS` + Firebase emulator)
- Android unit: `cd android && ./gradlew :app:testDebugUnitTest --tests "<FQN>"`
- Android build: `cd android && ./gradlew assembleDebug`
- Frontend: `cd frontend && npm test -- <path>`

---

## File Structure

### Backend — create
- `backend/src/main/java/com/albunyaan/tube/controller/ImportController.java` — `POST /api/account/import/resolve`.
- `backend/src/main/java/com/albunyaan/tube/dto/importflow/ImportResolveRequest.java`, `ImportItem.java`, `ImportResolveResponse.java`, `ImportResult.java`, `ImportDisposition.java` (enum).
- `backend/src/main/java/com/albunyaan/tube/service/UserImportSubmissionService.java` — create deduped PENDING submissions.
- `backend/src/main/java/com/albunyaan/tube/service/ImportGraduationService.java` — collection-group fan-out on approve/reject.
- Tests mirror under `backend/src/test/java/com/albunyaan/tube/...`.

### Backend — modify (READ FIRST)
- `model/Channel.java`, `model/Playlist.java`, `model/Video.java` — add `source`.
- `controller/ApprovalController.java` + `service/ApprovalService.java` (or equivalent approve/reject service) — require category for empty-category approve; invoke `ImportGraduationService` on approve/reject.
- `controller/SyncController.java` + its sync DTOs (`SubscriptionSyncDto`, `PlaylistSyncDto`, `FavoriteSyncDto`, `Put*Request`) — carry `approvalStatus`/`source`/`importedAt`.
- `security/SecurityConfig` (the Spring Security filter chain config) — allow authenticated users on `/api/account/import/**`.
- Firestore index config (`firestore.indexes.json` or equivalent) — collection-group indexes.

### Android — create
- `data/youtube/YouTubeAuthManager.kt` (+ `AuthResult` sealed type) — incremental authorization seam.
- `data/youtube/YouTubeImportApi.kt` + response DTOs `data/youtube/dto/*.kt`.
- `data/youtube/YouTubeImportRemoteSource.kt` + `ImportCandidate.kt`.
- `data/import/ImportApi.kt` (backend client) + DTOs `data/import/dto/*.kt`.
- `data/import/YouTubeImportRepository.kt` + `ImportProgress.kt`, `ImportSummary.kt`.
- `ui/me/import/ImportFromYouTubeFragment.kt`, `ImportViewModel.kt`, `ImportReviewAdapter.kt`.
- Layouts: `res/layout/fragment_import_youtube.xml` (+ `layout-sw600dp/`, `layout-sw720dp/`), `res/layout/item_import_candidate.xml`, `res/layout/item_import_group_header.xml`.
- `res/drawable/ic_import_youtube.xml` (project-local vector, **no vector-level tint**).

### Android — modify (READ FIRST)
- `data/local/SubscribedChannel.kt`, `SavedPlaylist.kt`, `FavoriteVideo.kt` — add `approvalStatus`, `source`, `importedAt`.
- `data/local/*Dao.kt` (3) — feed queries filter `APPROVED`; new awaiting queries.
- `data/local/AppDatabase.kt` (or wherever migrations live) — bump version + `MIGRATION_N_N+1`.
- `data/me/MeFeedRepository.kt` — feed uses only `APPROVED` rows; add awaiting observation.
- `ui/me/MeViewModel.kt` / `ui/me/MeFragment.kt` — render awaiting section.
- `data/sync/SyncManager.kt` + sync DTO mappers — map new fields.
- `res/menu/menu_me_kebab.xml` + `MeFragment.setupKebab()` — add `action_import_youtube` (all users).
- `onboarding/*` + DataStore prefs — first-login offer + `import_offer_shown` flag.
- `di/NetworkModule.kt`, `di/AppModule.kt` — provide new Retrofit/clients/repository.
- `res/values/strings.xml` (+ `values-ar/`, `values-nl/`) — import strings.

### Frontend — modify (READ FIRST)
- `frontend/src/views/PendingApprovalsView.vue` (or component) — source badge + category-required-on-approve.
- `frontend/src/locales/messages.ts` — en/ar/nl strings.

---

## PHASE A — Backend

### Task A1: Add `source` to content models

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/model/Channel.java`, `Playlist.java`, `Video.java` (READ FIRST)
- Test: `backend/src/test/java/com/albunyaan/tube/model/ContentSourceFieldTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentSourceFieldTest {
    @Test
    void channelSourceRoundTrips() {
        Channel c = new Channel();
        c.setSource("USER_IMPORT");
        assertEquals("USER_IMPORT", c.getSource());
    }

    @Test
    void sourceDefaultsNull() {
        assertNull(new Channel().getSource());
        assertNull(new Playlist().getSource());
        assertNull(new Video().getSource());
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.model.ContentSourceFieldTest"`
Expected: FAIL — `getSource()` undefined.

- [ ] **Step 3: Implement** — in each of `Channel`, `Playlist`, `Video`, add the field next to the existing fields, matching the class's field/getter/setter style (Firestore POJOs; keep `@PropertyName` conventions if present):

```java
private String source; // "USER_IMPORT" | "ADMIN" | "MODERATOR" | "BULK" | null
public String getSource() { return source; }
public void setSource(String source) { this.source = source; }
```

- [ ] **Step 4: Run test, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/ backend/src/test/java/com/albunyaan/tube/model/ContentSourceFieldTest.java
git commit -m "[BACKEND-IMPORT-01]: Add source field to content models"
```

---

### Task A2: Import DTOs

**Files:**
- Create: `backend/.../dto/importflow/ImportItem.java`, `ImportDisposition.java`, `ImportResolveRequest.java`, `ImportResult.java`, `ImportResolveResponse.java`

- [ ] **Step 1: Create the DTOs** (records; Jackson-serializable). `ContentItemDto` already exists — reuse it.

```java
// ImportDisposition.java
package com.albunyaan.tube.dto.importflow;
public enum ImportDisposition { APPROVED, PENDING, REJECTED, ERROR }
```
```java
// ImportItem.java
package com.albunyaan.tube.dto.importflow;
import com.albunyaan.tube.dto.YouTubeContentType; // reuse existing enum (CHANNEL|PLAYLIST|VIDEO)
public record ImportItem(YouTubeContentType type, String youtubeId, String title,
                         String thumbnailUrl, String channelId) {}
```
```java
// ImportResolveRequest.java
package com.albunyaan.tube.dto.importflow;
import jakarta.validation.constraints.*;
import java.util.List;
public record ImportResolveRequest(
    @NotEmpty @Size(max = 200) List<@jakarta.validation.Valid ImportItem> items) {}
```
```java
// ImportResult.java
package com.albunyaan.tube.dto.importflow;
import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.YouTubeContentType;
public record ImportResult(String youtubeId, YouTubeContentType type,
                           ImportDisposition disposition, ContentItemDto content) {}
```
```java
// ImportResolveResponse.java
package com.albunyaan.tube.dto.importflow;
import java.util.List;
public record ImportResolveResponse(List<ImportResult> results) {}
```

> **READ FIRST:** confirm the exact package + name of the existing content-type enum (`YouTubeContentType`) and `ContentItemDto`; adjust imports to match.

- [ ] **Step 2: Compile** — `cd backend && ./gradlew compileJava` → success.
- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/importflow/
git commit -m "[BACKEND-IMPORT-02]: Add import resolve DTOs"
```

---

### Task A3: `UserImportSubmissionService` (deduped PENDING, empty categories)

**Files:**
- Create: `backend/.../service/UserImportSubmissionService.java`
- Test: `backend/.../service/UserImportSubmissionServiceTest.java`

> **READ FIRST:** `RegistrySubmissionWriter` (for `sanitizeThumbnailUrl` + name/note sanitizers — reuse, do not re-implement), and the three repositories' `findByYoutubeId` + save signatures.

- [ ] **Step 1: Write the failing test** (Mockito; one representative type shown — add playlist/video analogues):

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.importflow.*;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserImportSubmissionServiceTest {
    @Mock ChannelRepository channels; @Mock PlaylistRepository playlists; @Mock VideoRepository videos;
    @Mock RegistrySubmissionWriter sanitizer;
    UserImportSubmissionService svc;

    @BeforeEach void setup() {
        MockitoAnnotations.openMocks(this);
        when(sanitizer.sanitizeThumbnailUrl(anyString())).thenAnswer(i -> i.getArgument(0));
        svc = new UserImportSubmissionService(channels, playlists, videos, sanitizer);
    }

    @Test void unknownChannelCreatesPendingEmptyCategoriesUserImport() throws Exception {
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.empty());
        ImportItem item = new ImportItem(YouTubeContentType.CHANNEL, "UC1", "Chan", "https://yt3/ggpht", null);
        ImportDisposition d = svc.submit(item, "uid-7");
        assertEquals(ImportDisposition.PENDING, d);
        ArgumentCaptor<Channel> cap = ArgumentCaptor.forClass(Channel.class);
        verify(channels).save(cap.capture());
        Channel saved = cap.getValue();
        assertEquals("PENDING", saved.getStatus());
        assertEquals("USER_IMPORT", saved.getSource());
        assertEquals("uid-7", saved.getSubmittedBy());
        assertTrue(saved.getCategoryIds() == null || saved.getCategoryIds().isEmpty());
    }

    @Test void existingChannelReturnsItsStatusNoDuplicate() throws Exception {
        Channel existing = new Channel(); existing.setStatus("APPROVED");
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.of(existing));
        ImportDisposition d = svc.submit(new ImportItem(YouTubeContentType.CHANNEL,"UC1","x",null,null), "uid-7");
        assertEquals(ImportDisposition.APPROVED, d);
        verify(channels, never()).save(any());
    }

    @Test void existingRejectedReturnsRejected() throws Exception {
        Channel existing = new Channel(); existing.setStatus("REJECTED");
        when(channels.findByYoutubeId("UC1")).thenReturn(Optional.of(existing));
        assertEquals(ImportDisposition.REJECTED,
            svc.submit(new ImportItem(YouTubeContentType.CHANNEL,"UC1","x",null,null), "uid-7"));
    }
}
```

- [ ] **Step 2: Run, verify fail** (class missing).
  `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.UserImportSubmissionServiceTest"`

- [ ] **Step 3: Implement** `UserImportSubmissionService`:
  - Constructor injects the 3 repositories + `RegistrySubmissionWriter` (or whichever bean exposes the sanitizers).
  - `public ImportDisposition submit(ImportItem item, String uid)`:
    1. `findByYoutubeId` on the repo for `item.type()`.
    2. If present → map its `status` to a disposition (`APPROVED`→APPROVED, `REJECTED`→REJECTED, else→PENDING); **no save**.
    3. If absent → build a new `Channel`/`Playlist`/`Video`: `youtubeId=item.youtubeId()`, `name`/`title`=sanitized `item.title()`, `thumbnailUrl`=`sanitizeThumbnailUrl(item.thumbnailUrl())`, `status="PENDING"`, `source="USER_IMPORT"`, `submittedBy=uid`, `categoryIds=List.of()`, server timestamps; `save`; return PENDING.
    4. Catch a duplicate-create race (re-read via `findByYoutubeId`) → return its status.
  - Helper `statusToDisposition(String status)`.

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/UserImportSubmissionService.java backend/src/test/java/com/albunyaan/tube/service/UserImportSubmissionServiceTest.java
git commit -m "[BACKEND-IMPORT-03]: User import submission service (deduped PENDING)"
```

---

### Task A4: `ImportController` resolve endpoint

**Files:**
- Create: `backend/.../controller/ImportController.java`
- Test: `backend/.../controller/ImportControllerTest.java`

> **READ FIRST:** an existing controller (e.g. `SyncController`) for how the authenticated principal/uid is obtained (`FirebaseUserDetails`), and how `ContentItemDto` is built from a model (reuse the existing mapper).

- [ ] **Step 1: Write the failing test** (`@WebMvcTest(ImportController.class)` with mocked services + security, or a slice test matching the repo's existing controller-test style — READ FIRST an existing controller test):

```java
// Behaviour to assert:
// - POST /api/account/import/resolve with [approved, pending, rejected, unknown] items
//   returns dispositions [APPROVED(+content), PENDING, REJECTED, PENDING] respectively.
// - unknown item triggers UserImportSubmissionService.submit(...).
// - a service exception on one item yields ERROR for that item, others unaffected.
```
Write concrete assertions using the repo's controller-test harness (MockMvc). Mock `ChannelRepository/PlaylistRepository/VideoRepository.findByYoutubeId` and `UserImportSubmissionService`.

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement:**

```java
@RestController
@RequestMapping("/api/account/import")
public class ImportController {
    private final ChannelRepository channels; private final PlaylistRepository playlists;
    private final VideoRepository videos; private final UserImportSubmissionService submissions;
    private final ContentItemDtoMapper mapper; // READ FIRST: real mapper name
    // constructor...

    @PostMapping("/resolve")
    public ImportResolveResponse resolve(@Valid @RequestBody ImportResolveRequest req,
                                         @AuthenticationPrincipal FirebaseUserDetails user) {
        List<ImportResult> out = new ArrayList<>();
        for (ImportItem it : req.items()) {
            try {
                Optional<?> existing = lookup(it);            // by type → findByYoutubeId
                if (existing.isPresent()) {
                    Object m = existing.get();
                    ImportDisposition d = statusToDisposition(statusOf(m));
                    ContentItemDto dto = d == ImportDisposition.APPROVED ? mapper.toDto(m) : null;
                    out.add(new ImportResult(it.youtubeId(), it.type(), d, dto));
                } else {
                    ImportDisposition d = submissions.submit(it, user.getUid());
                    out.add(new ImportResult(it.youtubeId(), it.type(), d, null));
                }
            } catch (Exception e) {
                out.add(new ImportResult(it.youtubeId(), it.type(), ImportDisposition.ERROR, null));
            }
        }
        return new ImportResolveResponse(out);
    }
}
```

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit** `[BACKEND-IMPORT-04]: Import resolve endpoint`

---

### Task A5: Security — allow authenticated users on `/api/account/import/**`

**Files:**
- Modify: `backend/.../security/SecurityConfig*.java` (READ FIRST — find the `SecurityFilterChain`/request-matcher config)
- Test: extend `ImportControllerTest` (or a security slice) — anonymous → 401/403; authenticated USER → 200.

- [ ] **Step 1:** Add a failing test asserting an unauthenticated POST to `/api/account/import/resolve` is rejected and an authenticated USER is allowed (the import endpoint must NOT require ADMIN/MODERATOR).
- [ ] **Step 2:** Run, verify fail (if default rules block or wrongly allow).
- [ ] **Step 3:** In the security config, ensure `/api/account/import/**` is `authenticated()` (matching how `/api/account/**` is already secured — likely already covered; make explicit). Do NOT add role restrictions.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[BACKEND-IMPORT-05]: Authenticated access for import endpoint`

---

### Task A6: Rate limit the import endpoint

**Files:**
- Modify: `ImportController.java` (+ a small limiter). READ FIRST: `BulkSubmissionService`'s existing per-submitter rate limiter — reuse its mechanism if generalizable.
- Test: `ImportControllerTest` — exceeding the per-user item budget returns 429 with an `accepted`/`remaining` body.

- [ ] **Step 1:** Failing test: simulate a user exceeding the daily item budget → 429.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Apply a per-user limiter (items/request already capped at 200 by the DTO; add a rolling daily item budget — pick a concrete constant, e.g. `IMPORT_DAILY_ITEM_BUDGET = 1000`). On exceed, return `429` with `{accepted, remaining}`. Reuse the existing limiter store if present.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[BACKEND-IMPORT-06]: Per-user rate limit on import`

---

### Task A7: Require category when approving empty-category items

**Files:**
- Modify: `controller/ApprovalController.java` + `service/ApprovalService.java` (READ FIRST — find the approve path + `categoryOverride` handling)
- Test: `backend/.../service/ApprovalCategoryRequiredTest.java`

- [ ] **Step 1: Failing test** — approving an item whose `categoryIds` is empty without a `categoryOverride` throws/returns 400; with a non-empty override it succeeds and the override is applied.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** In the approve logic: if the target's `categoryIds` is null/empty AND `categoryOverride` is null/empty → throw the repo's standard 400 (`ResponseStatusException(BAD_REQUEST, "Category required for user-imported submissions")`). Otherwise apply override as today.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[BACKEND-IMPORT-07]: Require category on approving uncategorized items`

---

### Task A8: `ImportGraduationService` — approve/reject fan-out

**Files:**
- Create: `backend/.../service/ImportGraduationService.java`
- Test: `backend/.../service/ImportGraduationServiceTest.java` (integration — needs the Firestore emulator; extend `BaseIntegrationTest`)

> **READ FIRST:** `BaseIntegrationTest` (cache clearing + emulator setup) and how Firestore is accessed (the `Firestore` bean / repository base). Per-user rows live at `users/{uid}/{subscriptions|playlists|favorites}` with fields `youtubeId`, `approvalStatus`, `deleted`, `updatedAt`.

- [ ] **Step 1: Failing integration test:**
  - Seed two users each with a `subscriptions` doc for `youtubeId=UC9`, `approvalStatus=AWAITING`; plus one `APPROVED` doc for a different id (control).
  - Call `graduationService.onApproved(YouTubeContentType.CHANNEL, "UC9")`.
  - Assert both AWAITING docs are now `approvalStatus=APPROVED` with a bumped `updatedAt`; the control doc is untouched.
  - Call `onRejected(CHANNEL, "UC9")` against fresh AWAITING docs → asserts `deleted=true` + bumped `updatedAt`.

- [ ] **Step 2:** Run, verify fail. `cd backend && ./gradlew test -Pintegration=true --tests "com.albunyaan.tube.service.ImportGraduationServiceTest"`

- [ ] **Step 3: Implement** using Firestore collection-group queries:

```java
@Service
public class ImportGraduationService {
    private final Firestore db;
    // subcollection name per type:
    private String coll(YouTubeContentType t) {
        return switch (t) { case CHANNEL -> "subscriptions"; case PLAYLIST -> "playlists"; case VIDEO -> "favorites"; };
    }
    public void onApproved(YouTubeContentType type, String youtubeId) { fanOut(type, youtubeId, true); }
    public void onRejected(YouTubeContentType type, String youtubeId) { fanOut(type, youtubeId, false); }

    private void fanOut(YouTubeContentType type, String youtubeId, boolean approve) {
        try {
            var snap = db.collectionGroup(coll(type))
                .whereEqualTo("youtubeId", youtubeId)
                .whereEqualTo("approvalStatus", "AWAITING")
                .get().get();
            long now = System.currentTimeMillis();
            WriteBatch batch = db.batch(); int n = 0;
            for (var doc : snap.getDocuments()) {
                Map<String,Object> upd = new HashMap<>();
                upd.put("updatedAt", now);
                if (approve) upd.put("approvalStatus", "APPROVED");
                else upd.put("deleted", true);
                batch.update(doc.getReference(), upd);
                if (++n % 450 == 0) { batch.commit().get(); batch = db.batch(); }
            }
            if (n % 450 != 0 || n == 0) batch.commit().get();
        } catch (Exception e) {
            log.error("Import graduation fan-out failed type={} id={}", type, youtubeId, e); // never rethrow into approve
        }
    }
}
```

- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[BACKEND-IMPORT-08]: Approve/reject fan-out for imported rows`

---

### Task A9: Wire fan-out into approve/reject

**Files:**
- Modify: `service/ApprovalService.java` (READ FIRST) — inject `ImportGraduationService`; after the registry status flip + cache eviction, call `onApproved(type, youtubeId)` (in approve) / `onRejected(type, youtubeId)` (in reject).
- Test: `ApprovalService` unit test — verify `ImportGraduationService.onApproved/onRejected` invoked with the right type+youtubeId; verify a thrown fan-out error does NOT fail the approve (it's swallowed in the service, but assert approve still returns success).

- [ ] **Step 1–2:** Failing test + run.
- [ ] **Step 3:** Add the calls. Resolve the item's `youtubeId` + type from the approved/rejected entity.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[BACKEND-IMPORT-09]: Trigger graduation fan-out on approve/reject`

---

### Task A10: Extend sync DTOs with `approvalStatus`/`source`/`importedAt`

**Files:**
- Modify: `SyncController.java` sync DTOs (`SubscriptionSyncDto`, `PlaylistSyncDto`, `FavoriteSyncDto`, `PutSubscriptionRequest`, `PutPlaylistRequest`, `PutFavoriteRequest`) + the Firestore read/write mapping (READ FIRST)
- Test: `backend/.../controller/SyncImportFieldsTest.java`

- [ ] **Step 1: Failing test:**
  - PUT a subscription with `approvalStatus="AWAITING"`, `source="YOUTUBE_IMPORT"`, `importedAt=123` → GET `/sync` returns those fields on that row.
  - PUT a subscription **without** `approvalStatus` → stored/returned as `"APPROVED"` (back-compat default).
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Add the three fields to each DTO/request; in the write path default `approvalStatus` to `"APPROVED"` when null; persist to the per-user Firestore doc; include in the read mapping.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[BACKEND-IMPORT-10]: Carry approvalStatus/source/importedAt in sync`

---

### Task A11: Firestore collection-group indexes

**Files:**
- Modify: `backend/firestore.indexes.json` (READ FIRST — confirm path/format; if indexes are managed elsewhere, document there)

- [ ] **Step 1:** Add collection-group composite indexes on `(youtubeId ASC, approvalStatus ASC)` for collection groups `subscriptions`, `playlists`, `favorites`.
- [ ] **Step 2:** Validate JSON parses (and, if the project deploys indexes via CI, that the deploy command accepts it).
- [ ] **Step 3:** Commit `[BACKEND-IMPORT-11]: Collection-group indexes for graduation fan-out`

> Note: the integration test in A8 runs against the emulator (no index needed there); production needs these deployed before the fan-out query runs at scale.

---

### Task A12: Backend full suite green

- [ ] Run `cd backend && ./gradlew test -Pintegration=true` → all pass (300s wall budget; fix any regressions from DTO/model changes). Commit any fixups as `[BACKEND-IMPORT-12]: Backend suite green for import`.

---

## PHASE B — Android

### Task B1: Room entities + migration

**Files:**
- Modify: `data/local/SubscribedChannel.kt`, `SavedPlaylist.kt`, `FavoriteVideo.kt`; `data/local/AppDatabase.kt` (READ FIRST — current `version`, migration list)
- Test: `app/src/test/java/com/albunyaan/tube/data/local/ImportMigrationTest.kt` (room-testing `MigrationTestHelper`)

> **READ FIRST:** confirm DB version, the `@Database` class name, the migrations array, and the exact table/column names (`subscribed_channels`, etc.). Ensure `room.schemaLocation` exported schemas exist (needed by MigrationTestHelper); if not, the test uses `createDatabase`/`runMigrationsAndValidate` against exported JSON.

- [ ] **Step 1: Failing migration test** — migrate from version `N` to `N+1`; assert the three tables now have `approval_status` (default `'APPROVED'`), `source`, `imported_at`, and an existing seeded row reads `approval_status='APPROVED'`.

```kotlin
@RunWith(AndroidJUnit4::class)
class ImportMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate_addsApprovalStatusDefaultApproved() {
        val db = helper.createDatabase(TEST_DB, OLD_VERSION).apply {
            execSQL("INSERT INTO subscribed_channels (channelId, name, user_id, updated_at, deleted, dirty) " +
                    "VALUES ('UC1','Chan','uid',1,0,0)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(TEST_DB, NEW_VERSION, true, MIGRATION_OLD_NEW)
        migrated.query("SELECT approval_status FROM subscribed_channels WHERE channelId='UC1'").use {
            assertTrue(it.moveToFirst()); assertEquals("APPROVED", it.getString(0))
        }
    }
}
```

- [ ] **Step 2:** Run, verify fail. `cd android && ./gradlew :app:testDebugUnitTest --tests "*ImportMigrationTest"`
- [ ] **Step 3: Implement** — add to each entity:

```kotlin
@ColumnInfo(name = "approval_status", defaultValue = "APPROVED") val approvalStatus: String = "APPROVED",
@ColumnInfo(name = "source") val source: String? = null,
@ColumnInfo(name = "imported_at") val importedAt: Long? = null,
```
Bump `@Database(version = N+1)`; add:
```kotlin
val MIGRATION_OLD_NEW = object : Migration(OLD, NEW) {
  override fun migrate(db: SupportSQLiteDatabase) {
    listOf("subscribed_channels","saved_playlists","favorite_videos").forEach { t ->
      db.execSQL("ALTER TABLE $t ADD COLUMN approval_status TEXT NOT NULL DEFAULT 'APPROVED'")
      db.execSQL("ALTER TABLE $t ADD COLUMN source TEXT")
      db.execSQL("ALTER TABLE $t ADD COLUMN imported_at INTEGER")
    }
  }
}
```
Register the migration; re-export schema JSON.

- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-01]: Room migration for import approvalStatus`

---

### Task B2: DAO queries — feed filters APPROVED, awaiting queries

**Files:**
- Modify: `data/local/SubscribedChannelDao.kt`, `SavedPlaylistDao.kt`, `FavoriteVideoDao.kt`, and `ChannelVideoCacheDao.kt` if feed composition reads channels there (READ FIRST)
- Test: `app/src/test/.../data/local/ImportDaoTest.kt` (in-memory Room)

- [ ] **Step 1: Failing test** — insert APPROVED + AWAITING rows; assert the "feed source" query returns only APPROVED; assert `observeAwaiting*()` returns only AWAITING for that user.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Add/adjust queries:
  - Feed-source queries (the ones `MeFeedRepository` uses to list subscribed channels / saved playlists / favorites) gain `AND approval_status = 'APPROVED'`.
  - New: `@Query("SELECT * FROM subscribed_channels WHERE user_id = :uid AND deleted = 0 AND approval_status = 'AWAITING'") fun observeAwaitingChannels(uid: String): Flow<List<SubscribedChannel>>` (+ playlist + favorite analogues).
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-02]: DAO approval_status filters + awaiting queries`

---

### Task B3: MeFeedRepository — feed APPROVED-only + awaiting flow

**Files:**
- Modify: `data/me/MeFeedRepository.kt` (READ FIRST — large file; integrate carefully)
- Test: `app/src/test/.../data/me/MeFeedApprovalFilterTest.kt`

- [ ] **Step 1: Failing test** — with an AWAITING channel present, `observeFeed()` chips/weeks exclude it and the favorites row excludes AWAITING favorites; a new `observeAwaiting()` exposes the AWAITING items grouped by type.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Ensure all feed-composition reads use the APPROVED-filtered DAO queries (B2). Add `fun observeAwaiting(): Flow<AwaitingImports>` where `data class AwaitingImports(val channels:List<...>, val playlists:List<...>, val videos:List<...>)`.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-03]: Feed excludes AWAITING; expose awaiting flow`

---

### Task B4: Sync mapping carries new fields

**Files:**
- Modify: `data/sync/SyncManager.kt` + its DTO mappers (READ FIRST — find where `SubscriptionSyncDto`↔entity mapping happens, push & pull)
- Test: `app/src/test/.../data/sync/SyncImportFieldMappingTest.kt`

- [ ] **Step 1: Failing test** — pull a sync DTO with `approvalStatus="AWAITING"`/`source`/`importedAt` → entity has them; push a dirty AWAITING entity → request DTO carries them; a DTO missing `approvalStatus` maps to `"APPROVED"`.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Extend the mapping functions both directions; default `approvalStatus` to `"APPROVED"` when DTO null. Add the fields to the Android sync DTO classes (Moshi).
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-04]: Sync maps approvalStatus/source/importedAt`

---

### Task B5: `YouTubeAuthManager` (incremental authorization seam)

**Files:**
- Create: `data/youtube/YouTubeAuthManager.kt` (interface + Google-backed impl + `AuthResult`)
- Test: `app/src/test/.../data/youtube/YouTubeAuthManagerLogicTest.kt`

> **READ FIRST:** current `google-services.json` web client id and how `GoogleSignIn` is set up. Add dependency `com.google.android.gms:play-services-auth` already present; use `com.google.android.gms.auth.api.identity.AuthorizationClient` / `AuthorizationRequest` with scope `Scope("https://www.googleapis.com/auth/youtube.readonly")`.

- [ ] **Step 1: Failing test** — define `interface YouTubeAuthManager { suspend fun authorize(): AuthResult }` and `sealed interface AuthResult { data class Granted(val accessToken:String):AuthResult; data class NeedsConsent(val pendingIntent: PendingIntent):AuthResult; object Denied:AuthResult; data class Failed(val error:Throwable):AuthResult }`. Test a `FakeYouTubeAuthManager` returns `Granted("tok")` — this validates the seam the repository/VM depend on (the real Google impl is exercised manually/instrumented).
- [ ] **Step 2:** Run, verify fail (types missing).
- [ ] **Step 3:** Create the interface + `AuthResult` + a `GoogleYouTubeAuthManager` impl wrapping `AuthorizationClient.authorize(request)` (maps `hasResolution()` → `NeedsConsent(pendingIntent)`, success → `Granted(result.accessToken)`). Keep the Google calls isolated so unit tests use the interface.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-05]: YouTube authorization manager (incremental scope)`

---

### Task B6: YouTube Data API client + DTOs

**Files:**
- Create: `data/youtube/YouTubeImportApi.kt`, `data/youtube/dto/SubscriptionListResponse.kt`, `PlaylistListResponse.kt`, `LikedVideosResponse.kt` (Moshi)
- Test: `app/src/test/.../data/youtube/YouTubeImportApiTest.kt` (MockWebServer)

- [ ] **Step 1: Failing test** — enqueue a canned `subscriptions.list` JSON (with `nextPageToken` + items having `snippet.resourceId.channelId`, `snippet.title`, `snippet.thumbnails.default.url`); assert parsed model has the channelId/title/thumbnail + nextPageToken. Repeat for playlists (`items[].id`, `snippet.title`) and liked videos (`items[].id`, `snippet.title`, `snippet.thumbnails`).
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3: Implement** Retrofit interface:

```kotlin
interface YouTubeImportApi {
  @GET("subscriptions") suspend fun subscriptions(
    @Header("Authorization") bearer: String,
    @Query("part") part: String = "snippet",
    @Query("mine") mine: Boolean = true,
    @Query("maxResults") max: Int = 50,
    @Query("pageToken") pageToken: String? = null): SubscriptionListResponse
  @GET("playlists") suspend fun playlists(/* same headers/params */ ...): PlaylistListResponse
  @GET("videos") suspend fun likedVideos(
    @Header("Authorization") bearer: String,
    @Query("part") part: String = "snippet",
    @Query("myRating") myRating: String = "like",
    @Query("maxResults") max: Int = 50,
    @Query("pageToken") pageToken: String? = null): LikedVideosResponse
}
```
Define the Moshi DTOs to match the JSON (only the fields needed).

- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-06]: YouTube Data API client + DTOs`

---

### Task B7: `YouTubeImportRemoteSource` — paginate + map + partial failure

**Files:**
- Create: `data/youtube/YouTubeImportRemoteSource.kt`, `data/youtube/ImportCandidate.kt`
- Test: `app/src/test/.../data/youtube/YouTubeImportRemoteSourceTest.kt` (MockWebServer)

- [ ] **Step 1: Failing test** — two-page subscriptions are fully collected; if the liked-videos call returns 403, `fetchAll` still returns channels + playlists and reports `likedVideosFailed=true`.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Implement:
```kotlin
data class ImportCandidate(val type: CandidateType, val youtubeId: String, val title: String,
                           val thumbnailUrl: String?, val channelId: String?)
enum class CandidateType { CHANNEL, PLAYLIST, VIDEO }
data class ImportFetchResult(val candidates: List<ImportCandidate>,
                             val failedTypes: Set<CandidateType>)
```
`suspend fun fetchAll(accessToken: String): ImportFetchResult` — loop each list to `nextPageToken == null`, map to candidates, catch per-type failures into `failedTypes`.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-07]: Fetch + paginate YouTube lists`

---

### Task B8: Backend import client

**Files:**
- Create: `data/import/ImportApi.kt` + `data/import/dto/{ImportItemDto,ImportResolveRequestDto,ImportResultDto,ImportResolveResponseDto}.kt`
- Test: `app/src/test/.../data/import/ImportApiTest.kt` (MockWebServer)

- [ ] **Step 1: Failing test** — POST resolve serializes items correctly; parses a response with mixed dispositions (incl. an APPROVED item carrying `content`).
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Retrofit interface `@POST("api/account/import/resolve") suspend fun resolve(@Body req: ImportResolveRequestDto): ImportResolveResponseDto`; Moshi DTOs mirroring backend; `content` reuses the existing content DTO type the app already deserializes from `/api/v1`.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-08]: Backend import resolve client`

---

### Task B9: `YouTubeImportRepository` — orchestrate

**Files:**
- Create: `data/import/YouTubeImportRepository.kt`, `ImportProgress.kt`, `ImportSummary.kt`
- Test: `app/src/test/.../data/import/YouTubeImportRepositoryTest.kt`

> **READ FIRST:** `SubscriptionRepository`, `SavedPlaylistRepository` (`getSavedPlaylists`), `FavoritesRepository` — the exact add/insert methods + how they mark dirty / trigger `pushDirtyAsync`. Reuse them to write rows (do NOT write Room directly, so sync fires).

- [ ] **Step 1: Failing test** — given selected candidates and a fake `ImportApi` returning `[APPROVED, PENDING, REJECTED]`, assert: APPROVED candidate written with `approvalStatus=APPROVED` (canonical metadata from `content`), PENDING written `AWAITING`, REJECTED not written; a candidate already present in Room is skipped before calling resolve; `ImportSummary(added=1, sentForReview=1, skipped=…)`.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Implement:
```kotlin
suspend fun import(selected: List<ImportCandidate>): ImportSummary {
  val fresh = selected.filterNot { localAlreadyHas(it) }
  val results = fresh.chunked(200).flatMap { importApi.resolve(it.toRequest()).results }
  // write rows by disposition via the existing repositories, passing approvalStatus + source=YOUTUBE_IMPORT + importedAt
  // tally summary; emit progress to `_progress`
}
val progress: Flow<ImportProgress>
```
Add `approvalStatus`/`source`/`importedAt` params to the repository add-methods (B-side of A10 — these repos already mark dirty).
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-09]: Import orchestration repository`

---

### Task B10: `ImportViewModel` state machine

**Files:**
- Create: `ui/me/import/ImportViewModel.kt` (+ `ImportUiState`)
- Test: `app/src/test/.../ui/me/import/ImportViewModelTest.kt` (coroutines-test + Turbine if available)

- [ ] **Step 1: Failing test** — drive: authorize Granted → Fetching → Review(candidates) → toggle selections → import → Summary; auth Denied → Error; fetch failure → Error with retry.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Implement `sealed interface ImportUiState { Idle; Authorizing; Fetching; data class Review(groups, selection); Importing(progress); data class Done(summary); data class Error(msg, retry) }` driven by the auth manager + remote source + repository.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-10]: Import view model state machine`

---

### Task B11: Import UI (fragment + layouts + review adapter)

**Files:**
- Create: `ui/me/import/ImportFromYouTubeFragment.kt`, `ImportReviewAdapter.kt`; `res/layout/fragment_import_youtube.xml` (+ `layout-sw600dp/`, `layout-sw720dp/`), `res/layout/item_import_candidate.xml`, `res/layout/item_import_group_header.xml`; `res/navigation/*` entry; `res/drawable/ic_import_youtube.xml`
- Test: `app/src/test/.../ui/me/import/ImportFragmentTest.kt` (Robolectric smoke) + manual matrix

> **READ FIRST:** an existing fragment with a toolbar + RecyclerView (e.g. `SuggestContentFragment`) for the project's view-binding + Hilt fragment pattern; the nav graph; the layout-qualifier + token conventions (`spacing_*`, `viewStart` for RTL, same view IDs across variants).

- [ ] **Step 1: Failing test** — Robolectric: launching the fragment in `Review` state renders three group headers with counts and per-item checkboxes; "Import" triggers VM import.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Build the fragment (collapsible grouped list, select-all per group, all checked by default, counts, summary view, error+retry). Layouts for phone/`sw600dp`/`sw720dp`; RTL via `textAlignment="viewStart"`; tokens for spacing. Vector icon with **no vector-level tint**.
- [ ] **Step 4:** Run, verify pass; build APK `./gradlew assembleDebug`.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-11]: Import review screen + layouts`

---

### Task B12: Kebab entry — "Import from YouTube" (all users)

**Files:**
- Modify: `res/menu/menu_me_kebab.xml`, `ui/me/MeFragment.kt` (`setupKebab`) (READ FIRST)
- Test: `app/src/test/.../ui/me/MeKebabImportTest.kt`

- [ ] **Step 1: Failing test** — kebab inflates `action_import_youtube`; it is visible regardless of role (unlike `action_suggest_content`); selecting it navigates to the import destination.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Add the item (icon `ic_import_youtube`, `showAsAction="never"`, NOT `android:visible="false"`); in `setupKebab` add a click handler that navigates to `ImportFromYouTubeFragment`; apply the same `colorControlNormal` icon-tint treatment used for the other kebab items.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-12]: Me kebab import entry`

---

### Task B13: First-login offer + DataStore flag

**Files:**
- Modify: onboarding/post-sign-in routing + the prefs/DataStore (READ FIRST — find where post-sign-in navigation happens and the existing DataStore prefs)
- Test: `app/src/test/.../onboarding/ImportOfferGateTest.kt`

- [ ] **Step 1: Failing test** — `shouldOfferImport()` returns true when `import_offer_shown` is false, false after it's marked shown; offer shows once.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Add an `import_offer_shown` boolean to DataStore prefs; after first successful sign-in, if not shown, route to the import offer (a lightweight screen/dialog with "Import" / "Not now") and set the flag regardless of choice. "Import" → import flow; "Not now" → dismiss.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-13]: First-login import offer (once)`

---

### Task B14: Awaiting-review section in Me tab

**Files:**
- Modify: `ui/me/MeFragment.kt`, `ui/me/MeViewModel.kt`, `data/me/MeFeedRepository.kt` (awaiting flow from B3); add `ui/me/AwaitingImportsAdapter.kt`; layout `res/layout/item_me_awaiting_*.xml`
- Test: `app/src/test/.../ui/me/MeAwaitingSectionTest.kt`

> **READ FIRST:** how `MeFragment` composes its `ConcatAdapter` (chips, favorites, week sections) so the awaiting section is inserted as another sub-adapter without breaking `setIsolateViewTypes(false)`.

- [ ] **Step 1: Failing test** — `MeViewModel` exposes awaiting items; when present, the awaiting section header + grouped items render; awaiting channels do NOT contribute videos to the feed; after a simulated sync graduating an item, it leaves the awaiting section and the feed includes it.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Add the awaiting section adapter to the ConcatAdapter (above week sections), clearly labeled, count shown, non-playable. Bind to `MeViewModel` state derived from `MeFeedRepository.observeAwaiting()`. Respect the large-screen pagination rule if the awaiting list is its own scrollable grid.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-14]: Me tab Awaiting-review section`

---

### Task B15: Hilt wiring + strings

**Files:**
- Modify: `di/NetworkModule.kt` (a **separate** Retrofit for the YouTube Data API base `https://www.googleapis.com/youtube/v3/`, plus the backend `ImportApi` on the existing authenticated Retrofit), `di/AppModule.kt` (provide `YouTubeAuthManager`, `YouTubeImportRemoteSource`, `YouTubeImportRepository`); `res/values/strings.xml` (+ `values-ar/`, `values-nl/`) (READ FIRST)
- Test: `app/src/test/.../di/ImportGraphSmokeTest.kt` (or rely on a Hilt test component compiling)

- [ ] **Step 1: Failing test/build** — a Hilt smoke test (or `assembleDebug`) fails until providers exist.
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Add `@Provides` for: a named `Retrofit` for the YouTube Data API (Moshi, plain OkHttp — the bearer token is passed per-call as a header, NOT a global interceptor), the `YouTubeImportApi`, the backend `ImportApi` (existing auth-injecting OkHttp), and the new repository/auth manager/remote source. Add all user-facing strings to `strings.xml` and translate in `values-ar`/`values-nl` (RTL-safe). Translate the OAuth-consent rationale text too.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest` + `assembleDebug` → green.
- [ ] **Step 5:** Commit `[ANDROID-IMPORT-15]: Hilt wiring + i18n strings`

---

### Task B16: Android full suite + build green

- [ ] Run `cd android && ./gradlew :app:testDebugUnitTest` and `./gradlew assembleDebug` → all pass. Manually verify the review screen + awaiting section across **phone / sw600dp / sw720dp / RTL Arabic** (CLAUDE.md UI-preservation rule). Commit fixups `[ANDROID-IMPORT-16]: Android suite + multi-device verify`.

---

## PHASE C — Frontend (admin)

### Task C1: Source badge + category-required on approve

**Files:**
- Modify: `frontend/src/views/PendingApprovalsView.vue` (or the approvals component) + the approval service/types if `source` isn't surfaced (READ FIRST); `frontend/src/locales/messages.ts`
- Test: `frontend/src/views/__tests__/PendingApprovalsView.import.spec.ts`

- [ ] **Step 1: Failing test** (Vitest + Vue Test Utils) — a pending item with `source==='USER_IMPORT'` renders a "User import" badge; the Approve action is disabled until a category is chosen for an item with empty `categoryIds`; choosing a category enables it and sends `categoryOverride`.
- [ ] **Step 2:** Run, verify fail. `cd frontend && npm test -- PendingApprovalsView.import`
- [ ] **Step 3:** Surface `source` in the approval DTO/type; render the badge; gate the approve button on category selection for empty-category items; add i18n keys (`approvals.sourceUserImport`, `approvals.categoryRequired`) in en/ar/nl.
- [ ] **Step 4:** Run, verify pass.
- [ ] **Step 5:** Commit `[ADMIN-IMPORT-01]: User-import badge + category-required approve`

---

### Task C2: Frontend suite green

- [ ] Run `cd frontend && npm test` and `npm run build` → green. Commit fixups `[ADMIN-IMPORT-02]: Frontend suite green`.

---

## Final verification

- [ ] Backend: `cd backend && ./gradlew test -Pintegration=true` green.
- [ ] Android: `cd android && ./gradlew :app:testDebugUnitTest assembleDebug` green; multi-device + RTL visual check.
- [ ] Frontend: `cd frontend && npm test && npm run build` green.
- [ ] End-to-end manual (test-user OAuth): sign in → first-login offer → authorize YouTube → review → import → confirm approved items in feed, unknowns in Awaiting; admin approves one with a category → confirm it graduates into the feed on next resume; admin rejects one → confirm it disappears from Awaiting.
- [ ] Update `docs/status/PROJECT_STATUS.md` / `docs/TRUE_PROJECT_STATUS.md` per workflow rules.
- [ ] **Do NOT merge to `main`** (branching policy) — integrate to `develop` when the user calls a stable release.
- [ ] Track the **Google OAuth verification** release gate separately before public GA.

---

## Self-review notes (author)

- **Spec coverage:** OAuth (B5), Data API fetch (B6–B7), review/select (B10–B11), resolve+submit+dedup (A2–A4), approvalStatus storage & feed gate (A10/B1–B3), graduation fan-out via sync (A8–A9/B4/B14), first-login + kebab (B12–B13), admin category-required + badge (A7/C1), security + rate limit (A5–A6), indexes (A11), i18n (B15/C1), tests throughout. Release-gate (OAuth verification) called out, not coded.
- **Type consistency:** `approvalStatus` values `APPROVED`/`AWAITING` (Room col `approval_status`); Me-list row `source='YOUTUBE_IMPORT'`; registry `source='USER_IMPORT'` (distinct fields — see spec §7.4). `ImportDisposition` = `APPROVED|PENDING|REJECTED|ERROR` used identically in A4 and B8/B9. Fan-out subcollections: CHANNEL→subscriptions, PLAYLIST→playlists, VIDEO→favorites (A8) match the sync collections.
- **Brownfield honesty:** READ FIRST markers flag every edit to a large existing file; full code given for new files and contracts.
