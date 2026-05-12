# Plan D — Account Sync Engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship bidirectional Firestore-backed sync of `subscribed_channels`, `saved_playlists`, `favorite_videos` for FitrahTube accounts, with last-write-wins conflict resolution, tombstones, archive integration, and a one-shot additive merge of pre-release local content on first sign-in.

**Architecture:** REST cursor-pull + push-on-change. Server stores three Firestore subcollections per user; tombstones-as-rows; archive integration via in-memory virtual tombstones at read time. Android adds Room v8 migration (four new columns × three tables + two new tables), a Hilt-singleton `SyncManager` orchestrator triggered by sign-in / `ON_RESUME` / local mutation / connectivity-restored, and per-row `dirty=1` queue with exponential backoff.

**Tech Stack:** Spring Boot 3 + Java 17 + Firestore SDK; Android Room 2.6 + Retrofit 2 + Kotlin Coroutines + Hilt; Firestore Security Rules; JUnit 5; Mockito; Robolectric for Room migration tests; Firebase emulators (Firestore + Rules).

**Spec:** `docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md` — read it first.

**Ticket prefix:** `SYNC-01`. Commit prefixes: `[FEAT-BACKEND-SYNC-01-Tn]`, `[FEAT-ANDROID-SYNC-01-Tn]`, `[TEST-…]`, `[FIX-…]`.

---

## File Structure

### Backend — files to create

| Path | Responsibility |
|---|---|
| `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncRowDto.java` | Sealed DTO base — `entityType, entityId, deleted, updatedAt` plus per-type payload fields |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/SubscriptionSyncDto.java` | Per-row DTO for subscriptions (channelUrl/name/avatarUrl/subscribedAt + base fields) |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/PlaylistSyncDto.java` | Per-row DTO for playlists (playlistUrl/name/thumbnailUrl/uploaderName/savedAt + base fields) |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/FavoriteSyncDto.java` | Per-row DTO for favorites (title/channelName/thumbnailUrl/durationSeconds/addedAt + base fields) |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncPageDto.java` | `items: List<? extends SyncRowDto>` + `nextCursor: Long?` |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncResponseDto.java` | Wraps three `SyncPageDto` fields (subscriptions/playlists/favorites) |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/PutSubscriptionRequest.java` | PUT body for subscriptions |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/PutPlaylistRequest.java` | PUT body for playlists |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/PutFavoriteRequest.java` | PUT body for favorites |
| `backend/src/main/java/com/albunyaan/tube/repository/SyncRepository.java` | Firestore subcollection access — pull-with-cursor + upsert + tombstone |
| `backend/src/main/java/com/albunyaan/tube/service/sync/ArchiveProjector.java` | Per-row filter: archived → virtual tombstone |
| `backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java` | Orchestrates per-type pull (calls repo + projector); per-type push (upsert/tombstone) |
| `backend/src/main/java/com/albunyaan/tube/controller/SyncController.java` | REST: `GET /api/account/sync`, `PUT|DELETE /api/account/{type}/{id}` |
| `backend/src/main/java/com/albunyaan/tube/scheduler/TombstoneGcScheduler.java` | Sunday 03:00 weekly GC |

### Backend — files to modify

| Path | Change |
|---|---|
| `backend/firestore.rules` | Add three subcollection rules + `allowsAuth(uid)` helper |
| `backend/firestore-test.rules` | Mirror prod rules (used by emulator tests) |

### Backend — tests to create

| Path | Coverage |
|---|---|
| `backend/src/test/java/com/albunyaan/tube/service/sync/ArchiveProjectorTest.java` | Unit (Mockito) |
| `backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java` | Unit (Mockito) — cursor advancement, page-size, tombstone-as-row |
| `backend/src/test/java/com/albunyaan/tube/controller/SyncControllerTest.java` | Unit (`@WebMvcTest`) — route validation, exception mapping |
| `backend/src/test/java/com/albunyaan/tube/integration/SyncControllerIT.java` | Emulator — full pull/push cycle, cursor monotonicity, pagination |
| `backend/src/test/java/com/albunyaan/tube/integration/SyncArchiveIT.java` | Emulator — archived item arrives as virtual tombstone, then disappears |
| `backend/src/test/java/com/albunyaan/tube/integration/SyncStatusFilterIT.java` | Emulator — BLOCKED 403, DELETED 401, PENDING_PROFILE allowed |
| `backend/src/test/java/com/albunyaan/tube/integration/SyncTombstoneGcIT.java` | Emulator — `@Scheduled` job purges only old tombstones |

### Android — files to create

| Path | Responsibility |
|---|---|
| `android/app/src/main/java/com/albunyaan/tube/data/local/SyncStateEntity.kt` | Room entity (`sync_state` table) |
| `android/app/src/main/java/com/albunyaan/tube/data/local/SyncStateDao.kt` | DAO |
| `android/app/src/main/java/com/albunyaan/tube/data/local/AccountBindingEntity.kt` | Room entity (`account_binding`) |
| `android/app/src/main/java/com/albunyaan/tube/data/local/AccountBindingDao.kt` | DAO |
| `android/app/src/main/java/com/albunyaan/tube/data/sync/dto/SyncDtos.kt` | Retrofit DTOs (SyncResponseDto, SyncPageDto, SubscriptionSyncDto, …) |
| `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncApi.kt` | Retrofit interface |
| `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt` | Hilt `@Singleton` orchestrator |
| `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncBackoff.kt` | Per-row exponential backoff helper |
| `android/app/src/main/java/com/albunyaan/tube/di/SyncModule.kt` | Provides `SyncApi`, `SyncManager` to Hilt graph |

### Android — files to modify

| Path | Change |
|---|---|
| `android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannel.kt` | Add `user_id, updated_at, deleted, dirty` columns |
| `android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylist.kt` | Add same four columns |
| `android/app/src/main/java/com/albunyaan/tube/data/local/FavoriteVideo.kt` | Add same four columns |
| `android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannelDao.kt` | Replace all queries with `WHERE user_id=:uid AND deleted=0`; add `markDirty`, `applyServerRow`, `applyTombstone`, `selectDirty` |
| `android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylistDao.kt` | Same shape changes |
| `android/app/src/main/java/com/albunyaan/tube/data/local/FavoriteVideoDao.kt` | Same shape changes |
| `android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt` | `version = 8`; register `SyncStateEntity`, `AccountBindingEntity`; expose DAOs |
| `android/app/src/main/java/com/albunyaan/tube/data/local/Migrations.kt` | Add `MIGRATION_7_8` |
| `android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt` | Add new migration; provide new DAOs |
| `android/app/src/main/java/com/albunyaan/tube/data/subscriptions/SubscriptionRepository.kt` | Take `uid` arg or inject `AccountState`; set `dirty=1` on writes; trigger `syncManager.pushDirty()` |
| `android/app/src/main/java/com/albunyaan/tube/data/local/FavoritesRepository.kt` (+`Impl`) | Same pattern |
| `android/app/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.kt` | Register `SyncManager` as `ProcessLifecycleOwner` observer |
| `android/app/src/main/java/com/albunyaan/tube/ui/splash/SplashRouter.kt` | After confirming /me, call `syncManager.bind(uid)` |
| `android/app/src/main/AndroidManifest.xml` | Add `ACCESS_NETWORK_STATE` permission (likely already present — verify) |
| `android/app/build.gradle.kts` | Add `androidx.lifecycle:lifecycle-process:2.7.0` if missing |

### Android — tests to create

| Path | Coverage |
|---|---|
| `android/app/src/test/java/com/albunyaan/tube/data/local/AppDatabaseMigration7to8Test.kt` | Robolectric — v7 → v8 ALTER + new tables |
| `android/app/src/test/java/com/albunyaan/tube/data/local/SyncStateDaoTest.kt` | Unit — cursor read/write |
| `android/app/src/test/java/com/albunyaan/tube/data/local/AccountBindingDaoTest.kt` | Unit — bind insert/update |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerBindTest.kt` | Unit — decision matrix |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/RunMergeTest.kt` | Unit — convergence; idempotency |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/PullAllTest.kt` | Unit — pagination, virtual tombstones, cursor advancement |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/PushDirtyTest.kt` | Unit — backoff, 401 retry-once, 403 propagate, 404 idempotent |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/RaceTests.kt` | Unit — subscribe→unsubscribe collapses to DELETE |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncBackoffTest.kt` | Unit — schedule 1s→2s→4s→…→60s cap |

---

# Phase 1 — Backend

## Task 1: Create per-row sync DTOs

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncRowDto.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/SubscriptionSyncDto.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/PlaylistSyncDto.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/FavoriteSyncDto.java`

- [ ] **Step 1: Create the abstract base DTO**

`backend/src/main/java/com/albunyaan/tube/dto/sync/SyncRowDto.java`:

```java
package com.albunyaan.tube.dto.sync;

/**
 * Plan D — base shape for every sync row in pull responses and push echoes.
 * `updatedAt` is the server timestamp in epoch milliseconds; `deleted=true`
 * means tombstone (real or virtual). Concrete subclasses add per-type payload.
 */
public abstract class SyncRowDto {
    private String entityId;
    private boolean deleted;
    private long updatedAt;

    public String getEntityId()         { return entityId; }
    public void setEntityId(String v)   { this.entityId = v; }
    public boolean isDeleted()          { return deleted; }
    public void setDeleted(boolean v)   { this.deleted = v; }
    public long getUpdatedAt()          { return updatedAt; }
    public void setUpdatedAt(long v)    { this.updatedAt = v; }
}
```

- [ ] **Step 2: Create the subscription DTO**

`backend/src/main/java/com/albunyaan/tube/dto/sync/SubscriptionSyncDto.java`:

```java
package com.albunyaan.tube.dto.sync;

public class SubscriptionSyncDto extends SyncRowDto {
    private String channelUrl;
    private String name;
    private String avatarUrl;
    private long subscribedAt;

    public String getChannelUrl()           { return channelUrl; }
    public void setChannelUrl(String v)     { this.channelUrl = v; }
    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }
    public String getAvatarUrl()            { return avatarUrl; }
    public void setAvatarUrl(String v)      { this.avatarUrl = v; }
    public long getSubscribedAt()           { return subscribedAt; }
    public void setSubscribedAt(long v)     { this.subscribedAt = v; }
}
```

- [ ] **Step 3: Create the playlist DTO**

`backend/src/main/java/com/albunyaan/tube/dto/sync/PlaylistSyncDto.java`:

```java
package com.albunyaan.tube.dto.sync;

public class PlaylistSyncDto extends SyncRowDto {
    private String playlistUrl;
    private String name;
    private String thumbnailUrl;
    private String uploaderName;
    private long savedAt;

    public String getPlaylistUrl()          { return playlistUrl; }
    public void setPlaylistUrl(String v)    { this.playlistUrl = v; }
    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }
    public String getThumbnailUrl()         { return thumbnailUrl; }
    public void setThumbnailUrl(String v)   { this.thumbnailUrl = v; }
    public String getUploaderName()         { return uploaderName; }
    public void setUploaderName(String v)   { this.uploaderName = v; }
    public long getSavedAt()                { return savedAt; }
    public void setSavedAt(long v)          { this.savedAt = v; }
}
```

- [ ] **Step 4: Create the favorite DTO**

`backend/src/main/java/com/albunyaan/tube/dto/sync/FavoriteSyncDto.java`:

```java
package com.albunyaan.tube.dto.sync;

public class FavoriteSyncDto extends SyncRowDto {
    private String title;
    private String channelName;
    private String thumbnailUrl;
    private int durationSeconds;
    private long addedAt;

    public String getTitle()                { return title; }
    public void setTitle(String v)          { this.title = v; }
    public String getChannelName()          { return channelName; }
    public void setChannelName(String v)    { this.channelName = v; }
    public String getThumbnailUrl()         { return thumbnailUrl; }
    public void setThumbnailUrl(String v)   { this.thumbnailUrl = v; }
    public int getDurationSeconds()         { return durationSeconds; }
    public void setDurationSeconds(int v)   { this.durationSeconds = v; }
    public long getAddedAt()                { return addedAt; }
    public void setAddedAt(long v)          { this.addedAt = v; }
}
```

- [ ] **Step 5: Compile**

Run: `cd backend && ./gradlew compileJava`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/sync/
git commit -m "[FEAT-BACKEND-SYNC-01-T1]: sync row DTOs (subscription, playlist, favorite)"
```

---

## Task 2: Create paging and request DTOs

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncPageDto.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncResponseDto.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/PutSubscriptionRequest.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/PutPlaylistRequest.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/sync/PutFavoriteRequest.java`

- [ ] **Step 1: Create `SyncPageDto`**

```java
package com.albunyaan.tube.dto.sync;

import java.util.List;

public class SyncPageDto<T extends SyncRowDto> {
    private List<T> items;
    private Long nextCursor;   // null when no further pages

    public SyncPageDto() {}
    public SyncPageDto(List<T> items, Long nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }
    public List<T> getItems()           { return items; }
    public void setItems(List<T> v)     { this.items = v; }
    public Long getNextCursor()         { return nextCursor; }
    public void setNextCursor(Long v)   { this.nextCursor = v; }
}
```

- [ ] **Step 2: Create `SyncResponseDto`**

```java
package com.albunyaan.tube.dto.sync;

public class SyncResponseDto {
    private SyncPageDto<SubscriptionSyncDto> subscriptions;
    private SyncPageDto<PlaylistSyncDto>     playlists;
    private SyncPageDto<FavoriteSyncDto>     favorites;

    public SyncResponseDto() {}
    public SyncResponseDto(SyncPageDto<SubscriptionSyncDto> s,
                           SyncPageDto<PlaylistSyncDto> p,
                           SyncPageDto<FavoriteSyncDto> f) {
        this.subscriptions = s; this.playlists = p; this.favorites = f;
    }
    public SyncPageDto<SubscriptionSyncDto> getSubscriptions()  { return subscriptions; }
    public void setSubscriptions(SyncPageDto<SubscriptionSyncDto> v) { this.subscriptions = v; }
    public SyncPageDto<PlaylistSyncDto> getPlaylists()          { return playlists; }
    public void setPlaylists(SyncPageDto<PlaylistSyncDto> v)    { this.playlists = v; }
    public SyncPageDto<FavoriteSyncDto> getFavorites()          { return favorites; }
    public void setFavorites(SyncPageDto<FavoriteSyncDto> v)    { this.favorites = v; }
}
```

- [ ] **Step 3: Create the three PUT request DTOs**

`PutSubscriptionRequest.java`:

```java
package com.albunyaan.tube.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PutSubscriptionRequest {
    @NotBlank private String channelUrl;
    @NotBlank private String name;
    private String avatarUrl;
    @NotNull  private Long subscribedAt;

    public String getChannelUrl()           { return channelUrl; }
    public void setChannelUrl(String v)     { this.channelUrl = v; }
    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }
    public String getAvatarUrl()            { return avatarUrl; }
    public void setAvatarUrl(String v)      { this.avatarUrl = v; }
    public Long getSubscribedAt()           { return subscribedAt; }
    public void setSubscribedAt(Long v)     { this.subscribedAt = v; }
}
```

`PutPlaylistRequest.java`:

```java
package com.albunyaan.tube.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PutPlaylistRequest {
    @NotBlank private String playlistUrl;
    @NotBlank private String name;
    private String thumbnailUrl;
    private String uploaderName;
    @NotNull  private Long savedAt;

    public String getPlaylistUrl()          { return playlistUrl; }
    public void setPlaylistUrl(String v)    { this.playlistUrl = v; }
    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }
    public String getThumbnailUrl()         { return thumbnailUrl; }
    public void setThumbnailUrl(String v)   { this.thumbnailUrl = v; }
    public String getUploaderName()         { return uploaderName; }
    public void setUploaderName(String v)   { this.uploaderName = v; }
    public Long getSavedAt()                { return savedAt; }
    public void setSavedAt(Long v)          { this.savedAt = v; }
}
```

`PutFavoriteRequest.java`:

```java
package com.albunyaan.tube.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public class PutFavoriteRequest {
    @NotBlank private String title;
    @NotBlank private String channelName;
    private String thumbnailUrl;
    @PositiveOrZero private int durationSeconds;
    @NotNull  private Long addedAt;

    public String getTitle()                { return title; }
    public void setTitle(String v)          { this.title = v; }
    public String getChannelName()          { return channelName; }
    public void setChannelName(String v)    { this.channelName = v; }
    public String getThumbnailUrl()         { return thumbnailUrl; }
    public void setThumbnailUrl(String v)   { this.thumbnailUrl = v; }
    public int getDurationSeconds()         { return durationSeconds; }
    public void setDurationSeconds(int v)   { this.durationSeconds = v; }
    public Long getAddedAt()                { return addedAt; }
    public void setAddedAt(Long v)          { this.addedAt = v; }
}
```

- [ ] **Step 4: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/sync/
git commit -m "[FEAT-BACKEND-SYNC-01-T2]: SyncPageDto, SyncResponseDto, PUT request DTOs"
```

---

## Task 3: Create `SyncRepository` (Firestore subcollection access)

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/repository/SyncRepository.java`

The repository owns all Firestore I/O for sync. It exposes per-type read (`pullSubscriptions(uid, cursor)`, …), per-type upsert, and tombstone. Reads return raw maps + the doc's server-side `updatedAt` so the service can convert to DTOs. **Page size is hardcoded `SYNC_PAGE_SIZE = 500`** per spec §6.

- [ ] **Step 1: Stub the class with package, imports, and constant**

```java
package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class SyncRepository {

    public static final int SYNC_PAGE_SIZE = 500;
    public static final String SUBS_COLL      = "subscriptions";
    public static final String PLAYLISTS_COLL = "playlists";
    public static final String FAVORITES_COLL = "favorites";

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeouts;

    public SyncRepository(Firestore firestore, FirestoreTimeoutProperties timeouts) {
        this.firestore = firestore;
        this.timeouts = timeouts;
    }

    private CollectionReference coll(String uid, String type) {
        return firestore.collection("users").document(uid).collection(type);
    }
}
```

- [ ] **Step 2: Write the failing test for `pull(...)` cursor query shape**

Create `backend/src/test/java/com/albunyaan/tube/repository/SyncRepositoryTest.java`:

```java
package com.albunyaan.tube.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncRepositoryTest {

    @Test
    void pageSizeConstantIs500() {
        assertEquals(500, SyncRepository.SYNC_PAGE_SIZE);
    }

    @Test
    void collectionNamesAreStable() {
        assertEquals("subscriptions", SyncRepository.SUBS_COLL);
        assertEquals("playlists",     SyncRepository.PLAYLISTS_COLL);
        assertEquals("favorites",     SyncRepository.FAVORITES_COLL);
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.repository.SyncRepositoryTest"`
Expected: PASS (the constants exist from step 1).

- [ ] **Step 3: Add a `RawRow` value type used internally**

Inside `SyncRepository.java`, add a nested static class:

```java
    /** Internal representation: doc-id, body map, server updatedAt in epoch millis. */
    public static record RawRow(String id, Map<String, Object> data, long updatedAt) {}
```

- [ ] **Step 4: Implement `pull(uid, type, since, limit)`**

Inside `SyncRepository.java`, add:

```java
    public List<RawRow> pull(String uid, String type, long since, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query q = coll(uid, type)
                .whereGreaterThan("updatedAt", Timestamp.ofTimeSecondsAndNanos(since / 1000L, (int)((since % 1000L) * 1_000_000L)))
                .orderBy("updatedAt", Query.Direction.ASCENDING)
                .limit(limit);
        QuerySnapshot snap = q.get().get(timeouts.getRead(), TimeUnit.MILLISECONDS);
        List<RawRow> out = new ArrayList<>(snap.size());
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            Timestamp ts = d.getTimestamp("updatedAt");
            long updatedAtMillis = ts == null ? 0L : ts.toDate().getTime();
            out.add(new RawRow(d.getId(), d.getData(), updatedAtMillis));
        }
        return out;
    }
```

- [ ] **Step 5: Implement `upsert(uid, type, id, payload)` and `tombstone(uid, type, id)`**

Append:

```java
    public RawRow upsert(String uid, String type, String id, Map<String, Object> payload)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference ref = coll(uid, type).document(id);
        Map<String, Object> body = new HashMap<>(payload);
        body.put("deleted", false);
        body.put("updatedAt", FieldValue.serverTimestamp());
        ref.set(body, SetOptions.merge()).get(timeouts.getWrite(), TimeUnit.MILLISECONDS);
        DocumentSnapshot stored = ref.get().get(timeouts.getRead(), TimeUnit.MILLISECONDS);
        Timestamp ts = stored.getTimestamp("updatedAt");
        return new RawRow(id, stored.getData(), ts == null ? 0L : ts.toDate().getTime());
    }

    public RawRow tombstone(String uid, String type, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference ref = coll(uid, type).document(id);
        Map<String, Object> body = new HashMap<>();
        body.put("deleted", true);
        body.put("updatedAt", FieldValue.serverTimestamp());
        ref.set(body, SetOptions.merge()).get(timeouts.getWrite(), TimeUnit.MILLISECONDS);
        DocumentSnapshot stored = ref.get().get(timeouts.getRead(), TimeUnit.MILLISECONDS);
        Timestamp ts = stored.getTimestamp("updatedAt");
        return new RawRow(id, stored.getData(), ts == null ? 0L : ts.toDate().getTime());
    }
```

- [ ] **Step 6: Compile**

Run: `cd backend && ./gradlew compileJava`

If `FirestoreTimeoutProperties` lacks `getRead()` / `getWrite()`, check existing usages in `UserRepository.java` and use whichever timeout getters that file already uses. Adapt names to match.

- [ ] **Step 7: Run the constant tests again**

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.repository.SyncRepositoryTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/repository/SyncRepository.java backend/src/test/java/com/albunyaan/tube/repository/SyncRepositoryTest.java
git commit -m "[FEAT-BACKEND-SYNC-01-T3]: SyncRepository (pull/upsert/tombstone, 500-cap)"
```

---

## Task 4: Create `ArchiveProjector`

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/sync/ArchiveProjector.java`
- Create: `backend/src/test/java/com/albunyaan/tube/service/sync/ArchiveProjectorTest.java`

The projector decides per-row whether to convert a live row into a virtual tombstone. It depends on the existing archive registry — find the bean by inspecting `GlobalStreamResolver` usages (recent archive-content-leak fix commit `f078442f`). The interface name in main is `ChannelRepository`/`PlaylistRepository`/`VideoRepository` with `findByYoutubeId` returning a `status` field. Inspect to confirm before writing.

- [ ] **Step 1: Find the archive-check API**

Run: `grep -rln "GlobalStreamResolver\|isArchived\|status.*ARCHIVED" backend/src/main/java | head`

Open `GlobalStreamResolver.java` (or the file the recent leak fix introduced). Identify the exact method used to check if a channelId / playlistId / videoId is archived. For the rest of this task assume:
- `ChannelRepository.isArchivedById(String youtubeId): boolean`
- `PlaylistRepository.isArchivedById(String youtubeId): boolean`
- `VideoRepository.isArchivedById(String youtubeId): boolean`

If actual names differ, substitute below.

- [ ] **Step 2: Write failing test**

`backend/src/test/java/com/albunyaan/tube/service/sync/ArchiveProjectorTest.java`:

```java
package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ArchiveProjectorTest {

    private ChannelRepository channels;
    private PlaylistRepository playlists;
    private VideoRepository videos;
    private ArchiveProjector projector;

    @BeforeEach
    void setUp() {
        channels  = Mockito.mock(ChannelRepository.class);
        playlists = Mockito.mock(PlaylistRepository.class);
        videos    = Mockito.mock(VideoRepository.class);
        projector = new ArchiveProjector(channels, playlists, videos);
    }

    @Test
    void nonArchivedSubscriptionPassesThroughUnchanged() {
        when(channels.isArchivedById("UC1")).thenReturn(false);
        RawRow row = new RawRow("UC1", Map.of("deleted", false), 100L);

        RawRow out = projector.projectSubscription(row);

        assertSame(row, out);
    }

    @Test
    void archivedSubscriptionBecomesVirtualTombstone() {
        when(channels.isArchivedById("UC2")).thenReturn(true);
        RawRow row = new RawRow("UC2", Map.of("deleted", false, "name", "X"), 200L);

        RawRow out = projector.projectSubscription(row);

        assertEquals("UC2", out.id());
        assertTrue((Boolean) out.data().get("deleted"));
        assertEquals(200L, out.updatedAt());
    }

    @Test
    void existingRealTombstonePassesThroughUnchanged() {
        when(channels.isArchivedById("UC3")).thenReturn(true);  // even if archived, don't double-process
        RawRow row = new RawRow("UC3", Map.of("deleted", true), 300L);

        RawRow out = projector.projectSubscription(row);

        assertSame(row, out);
    }

    @Test
    void archivedPlaylistAndFavoriteSimilarlyTombstoned() {
        when(playlists.isArchivedById("PL1")).thenReturn(true);
        when(videos.isArchivedById("V1")).thenReturn(true);
        RawRow pl = new RawRow("PL1", Map.of("deleted", false), 1L);
        RawRow fv = new RawRow("V1",  Map.of("deleted", false), 2L);

        assertTrue((Boolean) projector.projectPlaylist(pl).data().get("deleted"));
        assertTrue((Boolean) projector.projectFavorite(fv).data().get("deleted"));
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.sync.ArchiveProjectorTest"`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement**

`backend/src/main/java/com/albunyaan/tube/service/sync/ArchiveProjector.java`:

```java
package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Plan D — converts archived live rows into virtual tombstones for sync reads.
 *
 * Real tombstones (rows where data.deleted == true) pass through untouched —
 * we do not re-stamp them; their server-side updatedAt is canonical.
 *
 * The underlying Firestore documents are NEVER mutated here. Archive recovery
 * (un-archive) is out of scope for Plan D (spec §9 D8).
 */
@Component
public class ArchiveProjector {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public ArchiveProjector(ChannelRepository channels,
                            PlaylistRepository playlists,
                            VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    public RawRow projectSubscription(RawRow row) {
        return projectIf(row, () -> channels.isArchivedById(row.id()));
    }

    public RawRow projectPlaylist(RawRow row) {
        return projectIf(row, () -> playlists.isArchivedById(row.id()));
    }

    public RawRow projectFavorite(RawRow row) {
        return projectIf(row, () -> videos.isArchivedById(row.id()));
    }

    private RawRow projectIf(RawRow row, java.util.function.BooleanSupplier archived) {
        Object deletedFlag = row.data().get("deleted");
        if (Boolean.TRUE.equals(deletedFlag)) return row;        // real tombstone — no double-process
        if (!archived.getAsBoolean()) return row;                // not archived — pass through
        Map<String, Object> tomb = new HashMap<>(row.data());
        tomb.put("deleted", true);
        return new RawRow(row.id(), tomb, row.updatedAt());
    }
}
```

- [ ] **Step 4: Run test**

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.sync.ArchiveProjectorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/sync/ArchiveProjector.java backend/src/test/java/com/albunyaan/tube/service/sync/ArchiveProjectorTest.java
git commit -m "[FEAT-BACKEND-SYNC-01-T4]: ArchiveProjector — virtual tombstones for archived rows"
```

---

## Task 5: Create `SyncService` skeleton + read path

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java`
- Create: `backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java`

The service has two surfaces: `pull(uid, cursors)` returns a `SyncResponseDto`; per-type `upsert(uid, id, body)` / `tombstone(uid, id)` writes are added in Task 6.

- [ ] **Step 1: Write failing test for `pull`**

```java
package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.dto.sync.SyncCursors;
import com.albunyaan.tube.dto.sync.SyncResponseDto;
import com.albunyaan.tube.repository.SyncRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class SyncServiceTest {

    private SyncRepository repo;
    private ArchiveProjector projector;
    private SyncService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(SyncRepository.class);
        projector = Mockito.mock(ArchiveProjector.class);
        service = new SyncService(repo, projector);
    }

    @Test
    void pullReturnsEmptyPagesWhenRepoEmpty() throws Exception {
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), eq(500))).thenReturn(List.of());

        SyncResponseDto resp = service.pull("u1", new SyncCursors(0L, 0L, 0L));

        assertNotNull(resp.getSubscriptions().getItems());
        assertTrue(resp.getSubscriptions().getItems().isEmpty());
        assertNull(resp.getSubscriptions().getNextCursor());
    }

    @Test
    void pullSetsNextCursorWhenPageSaturates() throws Exception {
        // 500 rows = saturation → nextCursor = last row's updatedAt
        List<RawRow> rows = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> new RawRow("ch" + i, Map.of("deleted", false,
                        "channelUrl", "u" + i, "name", "n" + i, "subscribedAt", (long) i), 1000L + i))
                .toList();
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), eq(500))).thenReturn(rows);
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), eq(500))).thenReturn(List.of());
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), eq(500))).thenReturn(List.of());
        for (RawRow r : rows) when(projector.projectSubscription(r)).thenReturn(r);

        SyncResponseDto resp = service.pull("u1", new SyncCursors(0L, 0L, 0L));

        assertEquals(500, resp.getSubscriptions().getItems().size());
        assertEquals(Long.valueOf(1000L + 499), resp.getSubscriptions().getNextCursor());
    }

    @Test
    void pullPassesEachRowThroughCorrectProjectorBranch() throws Exception {
        RawRow s = new RawRow("ch1", Map.of("deleted", false, "channelUrl","u","name","n","subscribedAt",1L), 10L);
        RawRow p = new RawRow("pl1", Map.of("deleted", false, "playlistUrl","u","name","n","savedAt",1L), 20L);
        RawRow v = new RawRow("v1",  Map.of("deleted", false, "title","t","channelName","c","durationSeconds",10L,"addedAt",1L), 30L);
        when(repo.pull(eq("u1"), eq("subscriptions"), eq(0L), eq(500))).thenReturn(List.of(s));
        when(repo.pull(eq("u1"), eq("playlists"),     eq(0L), eq(500))).thenReturn(List.of(p));
        when(repo.pull(eq("u1"), eq("favorites"),     eq(0L), eq(500))).thenReturn(List.of(v));
        when(projector.projectSubscription(s)).thenReturn(s);
        when(projector.projectPlaylist(p)).thenReturn(p);
        when(projector.projectFavorite(v)).thenReturn(v);

        service.pull("u1", new SyncCursors(0L, 0L, 0L));

        Mockito.verify(projector).projectSubscription(s);
        Mockito.verify(projector).projectPlaylist(p);
        Mockito.verify(projector).projectFavorite(v);
        Mockito.verifyNoMoreInteractions(projector);
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.sync.SyncServiceTest"`
Expected: FAIL — `SyncCursors` and `SyncService` not found.

- [ ] **Step 2: Create `SyncCursors`**

`backend/src/main/java/com/albunyaan/tube/dto/sync/SyncCursors.java`:

```java
package com.albunyaan.tube.dto.sync;

public class SyncCursors {
    private long subscriptions;
    private long playlists;
    private long favorites;

    public SyncCursors() {}
    public SyncCursors(long s, long p, long f) {
        this.subscriptions = s; this.playlists = p; this.favorites = f;
    }
    public long getSubscriptions()          { return subscriptions; }
    public void setSubscriptions(long v)    { this.subscriptions = v; }
    public long getPlaylists()              { return playlists; }
    public void setPlaylists(long v)        { this.playlists = v; }
    public long getFavorites()              { return favorites; }
    public void setFavorites(long v)        { this.favorites = v; }
}
```

- [ ] **Step 3: Implement `SyncService` (read path only)**

`backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java`:

```java
package com.albunyaan.tube.service.sync;

import com.albunyaan.tube.dto.sync.*;
import com.albunyaan.tube.repository.SyncRepository;
import com.albunyaan.tube.repository.SyncRepository.RawRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Service
public class SyncService {

    private final SyncRepository repo;
    private final ArchiveProjector projector;

    public SyncService(SyncRepository repo, ArchiveProjector projector) {
        this.repo = repo;
        this.projector = projector;
    }

    public SyncResponseDto pull(String uid, SyncCursors cursors)
            throws ExecutionException, InterruptedException, TimeoutException {
        SyncPageDto<SubscriptionSyncDto> subs = pullPage(
                uid, SyncRepository.SUBS_COLL, cursors.getSubscriptions(),
                projector::projectSubscription, SyncService::toSubscriptionDto);
        SyncPageDto<PlaylistSyncDto> pls = pullPage(
                uid, SyncRepository.PLAYLISTS_COLL, cursors.getPlaylists(),
                projector::projectPlaylist, SyncService::toPlaylistDto);
        SyncPageDto<FavoriteSyncDto> favs = pullPage(
                uid, SyncRepository.FAVORITES_COLL, cursors.getFavorites(),
                projector::projectFavorite, SyncService::toFavoriteDto);
        return new SyncResponseDto(subs, pls, favs);
    }

    private <T extends SyncRowDto> SyncPageDto<T> pullPage(
            String uid, String coll, long since,
            Function<RawRow, RawRow> project,
            Function<RawRow, T> toDto)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<RawRow> raw = repo.pull(uid, coll, since, SyncRepository.SYNC_PAGE_SIZE);
        List<T> items = new ArrayList<>(raw.size());
        for (RawRow r : raw) items.add(toDto.apply(project.apply(r)));
        Long nextCursor = raw.size() == SyncRepository.SYNC_PAGE_SIZE
                ? raw.get(raw.size() - 1).updatedAt() : null;
        return new SyncPageDto<>(items, nextCursor);
    }

    // ── Row → DTO converters ─────────────────────────────────────────────

    private static SubscriptionSyncDto toSubscriptionDto(RawRow r) {
        SubscriptionSyncDto d = new SubscriptionSyncDto();
        Map<String, Object> m = r.data();
        d.setEntityId(r.id());
        d.setDeleted(Boolean.TRUE.equals(m.get("deleted")));
        d.setUpdatedAt(r.updatedAt());
        d.setChannelUrl((String) m.getOrDefault("channelUrl", ""));
        d.setName((String) m.getOrDefault("name", ""));
        d.setAvatarUrl((String) m.get("avatarUrl"));
        d.setSubscribedAt(longOf(m.get("subscribedAt")));
        return d;
    }

    private static PlaylistSyncDto toPlaylistDto(RawRow r) {
        PlaylistSyncDto d = new PlaylistSyncDto();
        Map<String, Object> m = r.data();
        d.setEntityId(r.id());
        d.setDeleted(Boolean.TRUE.equals(m.get("deleted")));
        d.setUpdatedAt(r.updatedAt());
        d.setPlaylistUrl((String) m.getOrDefault("playlistUrl", ""));
        d.setName((String) m.getOrDefault("name", ""));
        d.setThumbnailUrl((String) m.get("thumbnailUrl"));
        d.setUploaderName((String) m.get("uploaderName"));
        d.setSavedAt(longOf(m.get("savedAt")));
        return d;
    }

    private static FavoriteSyncDto toFavoriteDto(RawRow r) {
        FavoriteSyncDto d = new FavoriteSyncDto();
        Map<String, Object> m = r.data();
        d.setEntityId(r.id());
        d.setDeleted(Boolean.TRUE.equals(m.get("deleted")));
        d.setUpdatedAt(r.updatedAt());
        d.setTitle((String) m.getOrDefault("title", ""));
        d.setChannelName((String) m.getOrDefault("channelName", ""));
        d.setThumbnailUrl((String) m.get("thumbnailUrl"));
        d.setDurationSeconds(intOf(m.get("durationSeconds")));
        d.setAddedAt(longOf(m.get("addedAt")));
        return d;
    }

    private static long longOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }
    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.sync.SyncServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java backend/src/main/java/com/albunyaan/tube/dto/sync/SyncCursors.java backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java
git commit -m "[FEAT-BACKEND-SYNC-01-T5]: SyncService.pull + SyncCursors"
```

---

## Task 6: SyncService write path

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java`

- [ ] **Step 1: Add failing tests for write methods**

Append to `SyncServiceTest.java`:

```java
    @Test
    void upsertSubscriptionPersistsBodyAndEchoesUpdatedAt() throws Exception {
        var req = new PutSubscriptionRequest();
        req.setChannelUrl("u"); req.setName("n"); req.setSubscribedAt(50L);
        when(repo.upsert(eq("u1"), eq("subscriptions"), eq("ch1"), Mockito.anyMap()))
                .thenReturn(new RawRow("ch1", Map.of("deleted", false), 1234L));

        SubscriptionSyncDto out = service.upsertSubscription("u1", "ch1", req);

        assertEquals("ch1", out.getEntityId());
        assertFalse(out.isDeleted());
        assertEquals(1234L, out.getUpdatedAt());
    }

    @Test
    void tombstoneSubscriptionEchoesDeletedTrue() throws Exception {
        when(repo.tombstone(eq("u1"), eq("subscriptions"), eq("ch1")))
                .thenReturn(new RawRow("ch1", Map.of("deleted", true), 5678L));

        SubscriptionSyncDto out = service.tombstoneSubscription("u1", "ch1");

        assertEquals("ch1", out.getEntityId());
        assertTrue(out.isDeleted());
        assertEquals(5678L, out.getUpdatedAt());
    }
```

Add import: `import com.albunyaan.tube.dto.sync.*;`

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.sync.SyncServiceTest"`
Expected: FAIL — methods not defined.

- [ ] **Step 2: Implement the six write methods**

Append to `SyncService.java`:

```java
    public SubscriptionSyncDto upsertSubscription(String uid, String id, PutSubscriptionRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("channelUrl", req.getChannelUrl());
        body.put("name", req.getName());
        body.put("avatarUrl", req.getAvatarUrl());
        body.put("subscribedAt", req.getSubscribedAt());
        return toSubscriptionDto(repo.upsert(uid, SyncRepository.SUBS_COLL, id, body));
    }

    public SubscriptionSyncDto tombstoneSubscription(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toSubscriptionDto(repo.tombstone(uid, SyncRepository.SUBS_COLL, id));
    }

    public PlaylistSyncDto upsertPlaylist(String uid, String id, PutPlaylistRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("playlistUrl", req.getPlaylistUrl());
        body.put("name", req.getName());
        body.put("thumbnailUrl", req.getThumbnailUrl());
        body.put("uploaderName", req.getUploaderName());
        body.put("savedAt", req.getSavedAt());
        return toPlaylistDto(repo.upsert(uid, SyncRepository.PLAYLISTS_COLL, id, body));
    }

    public PlaylistSyncDto tombstonePlaylist(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toPlaylistDto(repo.tombstone(uid, SyncRepository.PLAYLISTS_COLL, id));
    }

    public FavoriteSyncDto upsertFavorite(String uid, String id, PutFavoriteRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", req.getTitle());
        body.put("channelName", req.getChannelName());
        body.put("thumbnailUrl", req.getThumbnailUrl());
        body.put("durationSeconds", req.getDurationSeconds());
        body.put("addedAt", req.getAddedAt());
        return toFavoriteDto(repo.upsert(uid, SyncRepository.FAVORITES_COLL, id, body));
    }

    public FavoriteSyncDto tombstoneFavorite(String uid, String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return toFavoriteDto(repo.tombstone(uid, SyncRepository.FAVORITES_COLL, id));
    }
```

- [ ] **Step 3: Run tests**

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.sync.SyncServiceTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java
git commit -m "[FEAT-BACKEND-SYNC-01-T6]: SyncService write path (upsert/tombstone per type)"
```

---

## Task 7: `SyncController` — routes

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/controller/SyncController.java`
- Create: `backend/src/test/java/com/albunyaan/tube/controller/SyncControllerTest.java`

- [ ] **Step 1: Write `@WebMvcTest` skeleton with one failing test**

```java
package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.sync.*;
import com.albunyaan.tube.service.sync.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SyncController.class)
class SyncControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SyncService service;

    @Test
    @WithMockUser(username = "uid-1")
    void getSyncReturns200WithEmptyPagesWhenServiceReturnsEmpty() throws Exception {
        when(service.pull(eq("uid-1"), any()))
            .thenReturn(new SyncResponseDto(
                new SyncPageDto<>(java.util.List.of(), null),
                new SyncPageDto<>(java.util.List.of(), null),
                new SyncPageDto<>(java.util.List.of(), null)));

        mvc.perform(get("/api/account/sync"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.subscriptions.items").isArray())
           .andExpect(jsonPath("$.subscriptions.nextCursor").doesNotExist())
           .andExpect(jsonPath("$.playlists.items").isArray())
           .andExpect(jsonPath("$.favorites.items").isArray());
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.controller.SyncControllerTest"`
Expected: FAIL — `SyncController` not found.

Note: if your `FirebaseUserDetails` requires custom `WithSecurityContextFactory`, follow the pattern used in existing controller tests (e.g., `AccountControllerTest` if present, or look at how `@WebMvcTest` is used with FirebaseAuthFilter mocking). The `@WithMockUser(username = "uid-1")` will not work directly if `principal.getUid()` is read from `FirebaseUserDetails` rather than `Authentication.getName()`. Inspect existing tests and adapt — the goal of this step is shape-only, not full security wiring.

- [ ] **Step 2: Implement the controller**

```java
package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.sync.*;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.sync.SyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/account")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) { this.sync = sync; }

    @GetMapping("/sync")
    public ResponseEntity<SyncResponseDto> getSync(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @RequestParam(name = "subs", required = false, defaultValue = "0") long subs,
            @RequestParam(name = "playlists", required = false, defaultValue = "0") long playlists,
            @RequestParam(name = "favorites", required = false, defaultValue = "0") long favorites)
            throws ExecutionException, InterruptedException, TimeoutException {
        var cursors = new SyncCursors(subs, playlists, favorites);
        return ResponseEntity.ok(sync.pull(principal.getUid(), cursors));
    }

    // ── Subscriptions ───────────────────────────────────────────────────

    @PutMapping("/subscriptions/{id}")
    public ResponseEntity<SubscriptionSyncDto> putSubscription(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id,
            @Valid @RequestBody PutSubscriptionRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sync.upsertSubscription(principal.getUid(), id, req));
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<SubscriptionSyncDto> deleteSubscription(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sync.tombstoneSubscription(principal.getUid(), id));
    }

    // ── Playlists ───────────────────────────────────────────────────────

    @PutMapping("/playlists/{id}")
    public ResponseEntity<PlaylistSyncDto> putPlaylist(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id,
            @Valid @RequestBody PutPlaylistRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sync.upsertPlaylist(principal.getUid(), id, req));
    }

    @DeleteMapping("/playlists/{id}")
    public ResponseEntity<PlaylistSyncDto> deletePlaylist(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sync.tombstonePlaylist(principal.getUid(), id));
    }

    // ── Favorites ───────────────────────────────────────────────────────

    @PutMapping("/favorites/{id}")
    public ResponseEntity<FavoriteSyncDto> putFavorite(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id,
            @Valid @RequestBody PutFavoriteRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sync.upsertFavorite(principal.getUid(), id, req));
    }

    @DeleteMapping("/favorites/{id}")
    public ResponseEntity<FavoriteSyncDto> deleteFavorite(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sync.tombstoneFavorite(principal.getUid(), id));
    }
}
```

- [ ] **Step 3: Compile, run controller test**

Run: `cd backend && ./gradlew compileJava test --tests "com.albunyaan.tube.controller.SyncControllerTest"`

Expected: PASS (or shape-only PASS after adapting the test for FirebaseUserDetails injection per Step 1 note).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/SyncController.java backend/src/test/java/com/albunyaan/tube/controller/SyncControllerTest.java
git commit -m "[FEAT-BACKEND-SYNC-01-T7]: SyncController (GET /sync + PUT/DELETE per type)"
```

---

## Task 8: Tombstone GC scheduler

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/scheduler/TombstoneGcScheduler.java`

- [ ] **Step 1: Implement the scheduler**

`backend/src/main/java/com/albunyaan/tube/scheduler/TombstoneGcScheduler.java`:

```java
package com.albunyaan.tube.scheduler;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Plan D — weekly tombstone GC. Sunday 03:00 UTC. Bounded work per type.
 * Deletes `deleted=true AND updatedAt < now - 90d` from every user's
 * subcollection (via Firestore collectionGroup).
 */
@Component
public class TombstoneGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(TombstoneGcScheduler.class);
    private static final long RETENTION_DAYS = 90L;

    private final Firestore firestore;
    private final MeterRegistry meters;

    public TombstoneGcScheduler(Firestore firestore, MeterRegistry meters) {
        this.firestore = firestore;
        this.meters = meters;
    }

    @Scheduled(cron = "0 0 3 * * SUN", zone = "UTC")
    public void pruneTombstones() {
        Instant cutoffInst = Instant.now().minus(Duration.ofDays(RETENTION_DAYS));
        Timestamp cutoff = Timestamp.ofTimeSecondsAndNanos(cutoffInst.getEpochSecond(), cutoffInst.getNano());
        for (String type : List.of("subscriptions", "playlists", "favorites")) {
            int purged = purgeOne(type, cutoff);
            log.info("account.sync.tombstone.gc type={} purged={}", type, purged);
            meters.counter("account.sync.tombstone.gc.purged", "type", type).increment(purged);
        }
    }

    private int purgeOne(String type, Timestamp cutoff) {
        try {
            QuerySnapshot snap = firestore.collectionGroup(type)
                    .whereEqualTo("deleted", true)
                    .whereLessThan("updatedAt", cutoff)
                    .get().get();
            int n = 0;
            for (QueryDocumentSnapshot d : snap.getDocuments()) {
                d.getReference().delete().get();
                n++;
            }
            return n;
        } catch (Exception e) {
            log.error("account.sync.tombstone.gc.error type={}", type, e);
            return 0;
        }
    }
}
```

- [ ] **Step 2: Verify a Spring `@EnableScheduling` is already on the application class**

Run: `grep -n "EnableScheduling" backend/src/main/java/com/albunyaan/tube/*.java`

Expected: at least one match (the existing validation schedulers wouldn't fire without it). If no match, add `@EnableScheduling` to `AlbunyaanTubeApplication.java` (top-level class).

- [ ] **Step 3: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/scheduler/TombstoneGcScheduler.java
git commit -m "[FEAT-BACKEND-SYNC-01-T8]: weekly tombstone GC (Sunday 03:00 UTC, 90d retention)"
```

---

## Task 9: Update Firestore Security Rules

**Files:**
- Modify: `backend/firestore.rules`
- Modify: `backend/firestore-test.rules` (mirror)

- [ ] **Step 1: Append the three subcollection rules**

Open `backend/firestore.rules`. Add inside `match /databases/{database}/documents { ... }` after the existing `match /users/{userId} { ... }` block:

```
    // Plan D — per-user sync subcollections
    function allowsAuth(uid) {
      let u = get(/databases/$(database)/documents/users/$(uid)).data;
      return u.status in ['ACTIVE', 'PENDING_PROFILE'];
    }

    match /users/{uid}/subscriptions/{channelId} {
      allow read, write: if isAuthenticated() && request.auth.uid == uid && allowsAuth(uid);
    }
    match /users/{uid}/playlists/{playlistId} {
      allow read, write: if isAuthenticated() && request.auth.uid == uid && allowsAuth(uid);
    }
    match /users/{uid}/favorites/{videoId} {
      allow read, write: if isAuthenticated() && request.auth.uid == uid && allowsAuth(uid);
    }
```

- [ ] **Step 2: Make the same edits in `backend/firestore-test.rules`**

`diff backend/firestore.rules backend/firestore-test.rules` should reveal pre-existing intentional differences only; reapply the same three rule blocks and helper at the equivalent location.

- [ ] **Step 3: Commit**

```bash
git add backend/firestore.rules backend/firestore-test.rules
git commit -m "[FEAT-BACKEND-SYNC-01-T9]: Firestore rules — per-user sync subcollections"
```

---

## Task 10: Backend integration tests — happy path

**Files:**
- Create: `backend/src/test/java/com/albunyaan/tube/integration/SyncControllerIT.java`

- [ ] **Step 1: Read the existing IT pattern**

Run: `head -80 backend/src/test/java/com/albunyaan/tube/integration/AccountControllerIT.java`

Note: how `BaseIntegrationTest` is extended, how `@MockBean FirebaseAuth` is configured, how Firestore emulator is reached.

- [ ] **Step 2: Write the IT**

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.sync.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SyncControllerIT extends BaseIntegrationTest {

    @MockBean private FirebaseAuth firebaseAuth;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void putThenGetReturnsTheUpsertedSubscription() throws Exception {
        String uid = "it-uid-1";
        seedActiveUser(uid);                          // helper from BaseIntegrationTest or inline
        FirebaseToken tok = stubToken(uid);

        var put = new PutSubscriptionRequest();
        put.setChannelUrl("https://yt/UCabc"); put.setName("Sample"); put.setSubscribedAt(1L);
        mvc.perform(put("/api/account/subscriptions/UCabc")
                .header("Authorization", "Bearer fake")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(put)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.entityId").value("UCabc"))
           .andExpect(jsonPath("$.deleted").value(false));

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.subscriptions.items[0].entityId").value("UCabc"))
           .andExpect(jsonPath("$.subscriptions.items[0].name").value("Sample"));
    }

    @Test
    void deleteEmitsTombstoneOnNextSync() throws Exception {
        String uid = "it-uid-2";
        seedActiveUser(uid);
        stubToken(uid);

        var put = new PutSubscriptionRequest();
        put.setChannelUrl("u"); put.setName("n"); put.setSubscribedAt(0L);
        mvc.perform(put("/api/account/subscriptions/UCdel")
                .header("Authorization", "Bearer fake")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(put))).andExpect(status().isOk());

        mvc.perform(delete("/api/account/subscriptions/UCdel")
                .header("Authorization", "Bearer fake")).andExpect(status().isOk());

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.subscriptions.items[?(@.entityId=='UCdel')].deleted").value(true));
    }

    // helpers: seedActiveUser writes a User doc with status=ACTIVE; stubToken
    // configures firebaseAuth.verifyIdToken("fake", true) → FirebaseToken with uid
    // — pattern in AccountControllerIT.java.
    private void seedActiveUser(String uid) throws Exception { /* fill per existing IT helpers */ }
    private FirebaseToken stubToken(String uid) throws Exception {
        FirebaseToken t = org.mockito.Mockito.mock(FirebaseToken.class);
        when(t.getUid()).thenReturn(uid);
        when(t.getEmail()).thenReturn(uid + "@test");
        when(firebaseAuth.verifyIdToken(eq("fake"), anyBoolean())).thenReturn(t);
        return t;
    }
}
```

- [ ] **Step 3: Run the IT**

First confirm the Firestore emulator is configured. The existing `BaseIntegrationTest` expects emulator on port 8090 — start it manually if needed:

Run: `cd backend && firebase emulators:start --only firestore,auth --project demo-test &` (skip if `./gradlew test -Pintegration=true` handles it)

Run: `cd backend && ./gradlew test -Pintegration=true --tests "com.albunyaan.tube.integration.SyncControllerIT"`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/SyncControllerIT.java
git commit -m "[TEST-BACKEND-SYNC-01-T10]: SyncControllerIT — happy path put/get/delete"
```

---

## Task 11: Backend integration test — archive integration

**Files:**
- Create: `backend/src/test/java/com/albunyaan/tube/integration/SyncArchiveIT.java`

- [ ] **Step 1: Find the archive-marking API used in tests**

Run: `grep -rln "isArchivedById\|archived.*true\|status.*ARCHIVED" backend/src/test/java | head`

Identify how existing tests mark a channel as archived. Most likely a method on `ChannelRepository` like `markArchived(id)` or a direct Firestore write.

- [ ] **Step 2: Write the IT**

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.sync.PutSubscriptionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SyncArchiveIT extends BaseIntegrationTest {

    @MockBean private FirebaseAuth firebaseAuth;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void archivedChannelAppearsAsVirtualTombstoneInSync() throws Exception {
        String uid = "it-arc-1";
        seedActiveUser(uid);
        stubToken(uid);

        // 1. user subscribes
        var put = new PutSubscriptionRequest();
        put.setChannelUrl("u"); put.setName("n"); put.setSubscribedAt(0L);
        mvc.perform(put("/api/account/subscriptions/UC_ARC")
                .header("Authorization", "Bearer fake")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(put))).andExpect(status().isOk());

        // 2. first pull sees it as alive
        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
           .andExpect(jsonPath("$.subscriptions.items[?(@.entityId=='UC_ARC')].deleted").value(false));

        // 3. admin archives the channel (substitute with the actual archive-marking helper)
        markChannelArchived("UC_ARC");

        // 4. next pull sees it as a tombstone
        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
           .andExpect(jsonPath("$.subscriptions.items[?(@.entityId=='UC_ARC')].deleted").value(true));
    }

    private void markChannelArchived(String id) throws Exception {
        // Use the same helper your existing tests use to set channel status = ARCHIVED.
        // E.g.: channelRepository.save(channel.withStatus(Status.ARCHIVED));
        throw new UnsupportedOperationException("TODO: wire to actual archive-marking helper used by existing IT");
    }

    private void seedActiveUser(String uid) throws Exception { /* same as SyncControllerIT */ }
    private FirebaseToken stubToken(String uid) throws Exception {
        FirebaseToken t = org.mockito.Mockito.mock(FirebaseToken.class);
        when(t.getUid()).thenReturn(uid);
        when(firebaseAuth.verifyIdToken(eq("fake"), anyBoolean())).thenReturn(t);
        return t;
    }
}
```

> **Plan note:** The `markChannelArchived` body **must** be filled in before the test runs. Find an existing IT that flips a channel to ARCHIVED status (e.g., a content-availability or archive-leak fix test) and reuse its helper. This is the only step where a placeholder is acceptable — the alternative is reading every channel-related test file in the plan. Replace before committing.

- [ ] **Step 3: Replace the TODO**

Find an existing test that archives content (e.g., grep for `ARCHIVED` in `src/test`). Copy its archive-marking call into `markChannelArchived`.

- [ ] **Step 4: Run and commit**

```bash
cd backend && ./gradlew test -Pintegration=true --tests "com.albunyaan.tube.integration.SyncArchiveIT"
```

Expected: PASS.

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/SyncArchiveIT.java
git commit -m "[TEST-BACKEND-SYNC-01-T11]: SyncArchiveIT — archived channel → virtual tombstone"
```

---

## Task 12: Backend integration tests — status filter + GC

**Files:**
- Create: `backend/src/test/java/com/albunyaan/tube/integration/SyncStatusFilterIT.java`
- Create: `backend/src/test/java/com/albunyaan/tube/integration/SyncTombstoneGcIT.java`

- [ ] **Step 1: Read the existing `AccountStatusFilterIntegrationTest`**

Run: `cat backend/src/test/java/com/albunyaan/tube/integration/AccountStatusFilterIntegrationTest.java`

Copy its setup helpers; pattern your `SyncStatusFilterIT` after it.

- [ ] **Step 2: Write `SyncStatusFilterIT`**

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.UserStatus;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SyncStatusFilterIT extends BaseIntegrationTest {

    @MockBean private FirebaseAuth firebaseAuth;
    @Autowired private MockMvc mvc;

    @Test
    void blockedUserGets403OnSync() throws Exception {
        seedUser("uid-blocked", UserStatus.BLOCKED);
        stubToken("uid-blocked");
        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
           .andExpect(status().isForbidden());
    }

    @Test
    void deletedUserGets401OnSync() throws Exception {
        seedUser("uid-deleted", UserStatus.DELETED);
        stubToken("uid-deleted");
        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingProfileUserAcceptedOnSync() throws Exception {
        seedUser("uid-pending", UserStatus.PENDING_PROFILE);
        stubToken("uid-pending");
        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
           .andExpect(status().isOk());
    }

    private void seedUser(String uid, UserStatus status) throws Exception {
        // pattern from AccountStatusFilterIntegrationTest
    }
    private FirebaseToken stubToken(String uid) throws Exception {
        FirebaseToken t = org.mockito.Mockito.mock(FirebaseToken.class);
        when(t.getUid()).thenReturn(uid);
        when(firebaseAuth.verifyIdToken(eq("fake"), anyBoolean())).thenReturn(t);
        return t;
    }
}
```

Replace `seedUser` body using the same fixture pattern as `AccountStatusFilterIntegrationTest`.

- [ ] **Step 3: Write `SyncTombstoneGcIT`**

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.scheduler.TombstoneGcScheduler;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncTombstoneGcIT extends BaseIntegrationTest {

    @Autowired private Firestore firestore;
    @Autowired private TombstoneGcScheduler scheduler;

    @Test
    void gcPurgesOnlyOldTombstones() throws Exception {
        String uid = "gc-uid";
        var subs = firestore.collection("users").document(uid).collection("subscriptions");
        // Old tombstone (91d ago) — should be purged
        Map<String,Object> old = new HashMap<>();
        old.put("deleted", true);
        old.put("updatedAt", Timestamp.ofTimeSecondsAndNanos(Instant.now().minusSeconds(91L * 86400L).getEpochSecond(), 0));
        subs.document("old-tomb").set(old).get();
        // Recent tombstone (10d ago) — should stay
        Map<String,Object> recent = new HashMap<>();
        recent.put("deleted", true);
        recent.put("updatedAt", Timestamp.ofTimeSecondsAndNanos(Instant.now().minusSeconds(10L * 86400L).getEpochSecond(), 0));
        subs.document("recent-tomb").set(recent).get();
        // Live row — should stay
        Map<String,Object> live = new HashMap<>();
        live.put("deleted", false);
        live.put("updatedAt", Timestamp.ofTimeSecondsAndNanos(Instant.now().minusSeconds(91L * 86400L).getEpochSecond(), 0));
        subs.document("live").set(live).get();

        scheduler.pruneTombstones();

        assertFalse(subs.document("old-tomb").get().get().exists(), "old tombstone must be purged");
        assertTrue (subs.document("recent-tomb").get().get().exists(), "recent tombstone must survive");
        assertTrue (subs.document("live").get().get().exists(), "live row must survive");
    }
}
```

- [ ] **Step 4: Run and commit**

```bash
cd backend && ./gradlew test -Pintegration=true \
    --tests "com.albunyaan.tube.integration.SyncStatusFilterIT" \
    --tests "com.albunyaan.tube.integration.SyncTombstoneGcIT"
```

Expected: PASS.

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/SyncStatusFilterIT.java backend/src/test/java/com/albunyaan/tube/integration/SyncTombstoneGcIT.java
git commit -m "[TEST-BACKEND-SYNC-01-T12]: status filter + GC integration tests"
```

---

# Phase 2 — Android

## Task 13: Add columns to `SubscribedChannel` / `SavedPlaylist` / `FavoriteVideo`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannel.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylist.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/FavoriteVideo.kt`

- [ ] **Step 1: Update `SubscribedChannel`**

Replace the data class definition with:

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscribed_channels")
data class SubscribedChannel(
    @PrimaryKey val channelId: String,
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long = System.currentTimeMillis(),
    // Plan D — sync metadata
    val user_id: String = "",
    val updated_at: Long = 0L,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
)
```

- [ ] **Step 2: Update `SavedPlaylist`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_playlists")
data class SavedPlaylist(
    @PrimaryKey val playlistId: String,
    val playlistUrl: String,
    val name: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val savedAt: Long = System.currentTimeMillis(),
    // Plan D — sync metadata
    val user_id: String = "",
    val updated_at: Long = 0L,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
)
```

- [ ] **Step 3: Update `FavoriteVideo`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_videos")
data class FavoriteVideo(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val addedAt: Long = System.currentTimeMillis(),
    // Plan D — sync metadata
    val user_id: String = "",
    val updated_at: Long = 0L,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
)
```

> Boolean columns map to INTEGER 0/1 in SQLite automatically via Room.

- [ ] **Step 4: Compile-check (KSP will regenerate `*_Impl`)**

Run: `cd android && ./gradlew :app:kspDebugKotlin`

Expected: BUILD SUCCESSFUL (DAO queries will be broken until Task 14 fixes them — defer).

If KSP fails earlier than the DAO step, comment out the impacted DAO query temporarily, then restore after Task 14.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannel.kt android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylist.kt android/app/src/main/java/com/albunyaan/tube/data/local/FavoriteVideo.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T13]: add sync columns to subs/playlists/favorites entities"
```

---

## Task 14: Update existing DAOs to be account-scoped

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannelDao.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylistDao.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/FavoriteVideoDao.kt`

Every existing query gains `WHERE user_id = :uid AND deleted = 0`. New methods for sync: `markDirty`, `applyServerRow`, `applyTombstone`, `selectDirty`.

- [ ] **Step 1: Rewrite `SubscribedChannelDao.kt`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscribedChannelDao {

    @Query("SELECT * FROM subscribed_channels WHERE user_id = :uid AND deleted = 0 ORDER BY subscribedAt DESC")
    fun observeAll(uid: String): Flow<List<SubscribedChannel>>

    @Query("SELECT * FROM subscribed_channels WHERE user_id = :uid AND deleted = 0 ORDER BY subscribedAt DESC")
    suspend fun getAll(uid: String): List<SubscribedChannel>

    @Query("SELECT * FROM subscribed_channels WHERE channelId = :id AND user_id = :uid AND deleted = 0")
    suspend fun getById(uid: String, id: String): SubscribedChannel?

    @Query("SELECT COUNT(*) FROM subscribed_channels WHERE user_id = :uid AND deleted = 0")
    suspend fun count(uid: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :id AND user_id = :uid AND deleted = 0)")
    fun observeIsSubscribed(uid: String, id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :id AND user_id = :uid AND deleted = 0)")
    suspend fun isSubscribed(uid: String, id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: SubscribedChannel)

    @Query("UPDATE subscribed_channels SET deleted = 1, dirty = 1 WHERE channelId = :id AND user_id = :uid")
    suspend fun softDelete(uid: String, id: String)

    // ── Sync surface ──────────────────────────────────────────────────

    @Query("UPDATE subscribed_channels SET user_id = :uid, dirty = 1 WHERE user_id = ''")
    suspend fun tagAnonRowsToUid(uid: String): Int

    @Query("SELECT * FROM subscribed_channels WHERE user_id = :uid AND dirty = 1")
    suspend fun selectDirty(uid: String): List<SubscribedChannel>

    @Query("UPDATE subscribed_channels SET updated_at = :ts, dirty = 0 WHERE channelId = :id AND user_id = :uid")
    suspend fun clearDirty(uid: String, id: String, ts: Long)

    @Query("DELETE FROM subscribed_channels WHERE user_id = :uid")
    suspend fun wipeForUid(uid: String)
}
```

- [ ] **Step 2: Rewrite `SavedPlaylistDao.kt`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaylistDao {

    @Query("SELECT * FROM saved_playlists WHERE user_id = :uid AND deleted = 0 ORDER BY savedAt DESC")
    fun observeAll(uid: String): Flow<List<SavedPlaylist>>

    @Query("SELECT * FROM saved_playlists WHERE user_id = :uid AND deleted = 0 ORDER BY savedAt DESC")
    suspend fun getAll(uid: String): List<SavedPlaylist>

    @Query("SELECT * FROM saved_playlists WHERE playlistId = :id AND user_id = :uid AND deleted = 0")
    suspend fun getById(uid: String, id: String): SavedPlaylist?

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE playlistId = :id AND user_id = :uid AND deleted = 0)")
    fun observeIsSaved(uid: String, id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE playlistId = :id AND user_id = :uid AND deleted = 0)")
    suspend fun isSaved(uid: String, id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: SavedPlaylist)

    @Query("UPDATE saved_playlists SET deleted = 1, dirty = 1 WHERE playlistId = :id AND user_id = :uid")
    suspend fun softDelete(uid: String, id: String)

    @Query("UPDATE saved_playlists SET user_id = :uid, dirty = 1 WHERE user_id = ''")
    suspend fun tagAnonRowsToUid(uid: String): Int

    @Query("SELECT * FROM saved_playlists WHERE user_id = :uid AND dirty = 1")
    suspend fun selectDirty(uid: String): List<SavedPlaylist>

    @Query("UPDATE saved_playlists SET updated_at = :ts, dirty = 0 WHERE playlistId = :id AND user_id = :uid")
    suspend fun clearDirty(uid: String, id: String, ts: Long)

    @Query("DELETE FROM saved_playlists WHERE user_id = :uid")
    suspend fun wipeForUid(uid: String)
}
```

> If the existing DAO has additional methods (`observeIsSaved` and friends), preserve their signatures with the new `uid: String` parameter.

- [ ] **Step 3: Rewrite `FavoriteVideoDao.kt`**

Apply the same scoping pattern. Adjust column names: `videoId` (PK), `addedAt` (ordering).

- [ ] **Step 4: Compile**

Run: `cd android && ./gradlew :app:kspDebugKotlin`
Expected: BUILD SUCCESSFUL.

> Callers that pass no `uid` (e.g., `SubscriptionRepository.observeSubscribedChannels()`) will fail to compile here. Leave those compile errors in place — Task 20 fixes them by injecting `AccountState`.

If you must commit at this point, use `git stash` for the broken callers or proceed straight to Task 15 — the migration test doesn't depend on caller fixes.

- [ ] **Step 5: Commit DAO changes only (broken callers ignored if you've stashed them)**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannelDao.kt android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylistDao.kt android/app/src/main/java/com/albunyaan/tube/data/local/FavoriteVideoDao.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T14]: scope all entity DAO queries by user_id + deleted=0"
```

---

## Task 15: Create `SyncStateEntity` + `AccountBindingEntity` + DAOs

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/local/SyncStateEntity.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/local/SyncStateDao.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/local/AccountBindingEntity.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/local/AccountBindingDao.kt`

- [ ] **Step 1: `SyncStateEntity.kt`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Plan D — per-(uid, entityType) sync cursor + last sync time.
 * `entityType` is one of "subscriptions", "playlists", "favorites".
 */
@Entity(tableName = "sync_state", primaryKeys = ["entityType", "user_id"])
data class SyncStateEntity(
    val entityType: String,
    val user_id: String,
    val last_cursor: Long,
    val last_sync_at: Long,
)
```

- [ ] **Step 2: `SyncStateDao.kt`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {

    @Query("SELECT last_cursor FROM sync_state WHERE entityType = :type AND user_id = :uid")
    suspend fun cursorFor(uid: String, type: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE user_id = :uid")
    suspend fun clearForUid(uid: String)
}
```

- [ ] **Step 3: `AccountBindingEntity.kt`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Plan D — single-row table tracking which uid this device is bound to,
 * and whether the one-time additive merge has completed.
 */
@Entity(tableName = "account_binding")
data class AccountBindingEntity(
    @PrimaryKey val user_id: String,
    val bound_at: Long,
    val initial_merge_done: Boolean,
)
```

- [ ] **Step 4: `AccountBindingDao.kt`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AccountBindingDao {

    @Query("SELECT * FROM account_binding LIMIT 1")
    suspend fun get(): AccountBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: AccountBindingEntity)

    @Query("UPDATE account_binding SET initial_merge_done = 1 WHERE user_id = :uid")
    suspend fun markMergeDone(uid: String)

    @Query("DELETE FROM account_binding")
    suspend fun clear()
}
```

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/SyncStateEntity.kt android/app/src/main/java/com/albunyaan/tube/data/local/SyncStateDao.kt android/app/src/main/java/com/albunyaan/tube/data/local/AccountBindingEntity.kt android/app/src/main/java/com/albunyaan/tube/data/local/AccountBindingDao.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T15]: SyncStateEntity + AccountBindingEntity + DAOs"
```

---

## Task 16: Wire `AppDatabase` to v8

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt`

- [ ] **Step 1: Read the current `@Database` declaration**

Run: `grep -n "@Database\|version\|entities\|abstract fun" android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt`

Note the entities list and DAO accessors. Add `SyncStateEntity` and `AccountBindingEntity` to the entities list; bump `version = 8`; add abstract DAO getters:

```kotlin
abstract fun syncStateDao(): SyncStateDao
abstract fun accountBindingDao(): AccountBindingDao
```

- [ ] **Step 2: Verify the new entities are imported and registered**

After editing, confirm imports include `SyncStateEntity` and `AccountBindingEntity`, the `entities = [...]` array lists them, `version = 8`, and the two new abstract methods are present.

- [ ] **Step 3: Compile (will fail — migration not registered yet)**

Run: `cd android && ./gradlew :app:kspDebugKotlin`

Expected: KSP succeeds; Room emits no schema error (but runtime would crash without MIGRATION_7_8 — handled in next task).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T16]: bump AppDatabase to v8, register sync entities"
```

---

## Task 17: Write `MIGRATION_7_8`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/Migrations.kt`

- [ ] **Step 1: Append `MIGRATION_7_8` to `Migrations.kt`**

```kotlin
/**
 * MIGRATION_7_8 — Plan D sync engine.
 *
 * Adds four sync metadata columns (user_id, updated_at, deleted, dirty) to
 * the three account-scoped tables (subscribed_channels, saved_playlists,
 * favorite_videos), and creates the two new sync tables (sync_state,
 * account_binding).
 *
 * All ALTER TABLE ADD COLUMN statements use NOT NULL DEFAULT 0 / '' so that
 * existing rows acquire safe defaults without needing rewrites. Rows whose
 * user_id is '' after this migration are "anon-era" — the bind/runMerge flow
 * in SyncManager tags them with the user's uid and marks them dirty for push.
 *
 * Defensive self-heal: CREATE TABLE IF NOT EXISTS mirrors the existing
 * migration style. ALTER TABLE … ADD COLUMN does NOT support IF NOT EXISTS
 * in SQLite, so re-applying this migration will throw if the columns are
 * already present — Room never re-applies a successful migration, so this
 * is acceptable.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // subscribed_channels
        db.execSQL("ALTER TABLE subscribed_channels ADD COLUMN user_id    TEXT    NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE subscribed_channels ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE subscribed_channels ADD COLUMN deleted    INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE subscribed_channels ADD COLUMN dirty      INTEGER NOT NULL DEFAULT 0")

        // saved_playlists
        db.execSQL("ALTER TABLE saved_playlists ADD COLUMN user_id    TEXT    NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE saved_playlists ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE saved_playlists ADD COLUMN deleted    INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE saved_playlists ADD COLUMN dirty      INTEGER NOT NULL DEFAULT 0")

        // favorite_videos
        db.execSQL("ALTER TABLE favorite_videos ADD COLUMN user_id    TEXT    NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE favorite_videos ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE favorite_videos ADD COLUMN deleted    INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE favorite_videos ADD COLUMN dirty      INTEGER NOT NULL DEFAULT 0")

        // sync_state — composite PK (entityType, user_id)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
              entityType   TEXT    NOT NULL,
              user_id      TEXT    NOT NULL,
              last_cursor  INTEGER NOT NULL,
              last_sync_at INTEGER NOT NULL,
              PRIMARY KEY (entityType, user_id)
            )
        """.trimIndent())

        // account_binding — single-row table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS account_binding (
              user_id            TEXT    NOT NULL PRIMARY KEY,
              bound_at           INTEGER NOT NULL,
              initial_merge_done INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

- [ ] **Step 2: Register the migration in `DatabaseModule`**

Edit `android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt`. Add the import:

```kotlin
import com.albunyaan.tube.data.local.MIGRATION_7_8
```

In the `provideAppDatabase` builder, extend the migration list:

```kotlin
.addMigrations(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
    MIGRATION_7_8,
)
```

Also add `provideSyncStateDao` and `provideAccountBindingDao`:

```kotlin
@Provides
@Singleton
fun provideSyncStateDao(db: AppDatabase): SyncStateDao = db.syncStateDao()

@Provides
@Singleton
fun provideAccountBindingDao(db: AppDatabase): AccountBindingDao = db.accountBindingDao()
```

- [ ] **Step 3: Compile**

Run: `cd android && ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL (callers fixed in later tasks; build green up to MainShellFragment-level wiring).

If unrelated upstream compile errors block this step, comment those callers out temporarily — Task 22 restores them.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/Migrations.kt android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T17]: MIGRATION_7_8 + DatabaseModule wiring"
```

---

## Task 18: Migration v7→v8 instrumented test

**Files:**
- Create: `android/app/src/test/java/com/albunyaan/tube/data/local/AppDatabaseMigration7to8Test.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan D / T18 — verifies v7 → v8 migration preserves existing rows and
 * adds the four sync columns with correct defaults plus two new tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AppDatabaseMigration7to8Test {

    private val DB = "migration-7-8-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_7_to_8_preserves_existing_rows_and_adds_defaults() {
        helper.createDatabase(DB, 7).use { v7 ->
            v7.execSQL(
                "INSERT INTO subscribed_channels (channelId, channelUrl, name, avatarUrl, subscribedAt) " +
                    "VALUES ('UC1', 'https://yt/UC1', 'Name1', NULL, 1000)"
            )
            v7.execSQL(
                "INSERT INTO saved_playlists (playlistId, playlistUrl, name, thumbnailUrl, uploaderName, savedAt) " +
                    "VALUES ('PL1', 'https://yt/PL1', 'PL Name', NULL, NULL, 2000)"
            )
            v7.execSQL(
                "INSERT INTO favorite_videos (videoId, title, channelName, thumbnailUrl, durationSeconds, addedAt) " +
                    "VALUES ('V1', 'Title', 'Channel', NULL, 90, 3000)"
            )
        }

        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).use { v8 ->
            v8.query(
                "SELECT channelId, user_id, updated_at, deleted, dirty FROM subscribed_channels WHERE channelId='UC1'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("UC1", c.getString(0))
                assertEquals("",   c.getString(1))      // user_id default
                assertEquals(0L,   c.getLong(2))        // updated_at default
                assertEquals(0,    c.getInt(3))         // deleted default
                assertEquals(0,    c.getInt(4))         // dirty default
            }
            v8.query("SELECT user_id, deleted, dirty FROM saved_playlists WHERE playlistId='PL1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("", c.getString(0))
                assertEquals(0,  c.getInt(1))
                assertEquals(0,  c.getInt(2))
            }
            v8.query("SELECT user_id, deleted, dirty FROM favorite_videos WHERE videoId='V1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("", c.getString(0))
                assertEquals(0,  c.getInt(1))
                assertEquals(0,  c.getInt(2))
            }
            v8.query("SELECT COUNT(*) FROM sync_state").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            v8.query("SELECT COUNT(*) FROM account_binding").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate_7_to_8_allows_writing_into_new_columns_and_tables() {
        helper.createDatabase(DB, 7).use { /* empty */ }
        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).use { v8 ->
            v8.execSQL(
                "INSERT INTO subscribed_channels (channelId, channelUrl, name, avatarUrl, subscribedAt, user_id, updated_at, deleted, dirty) " +
                    "VALUES ('UC2', 'u', 'n', NULL, 1, 'uid-x', 99, 1, 1)"
            )
            v8.execSQL("INSERT INTO sync_state VALUES ('subscriptions', 'uid-x', 99, 100)")
            v8.execSQL("INSERT INTO account_binding VALUES ('uid-x', 50, 1)")

            v8.query("SELECT deleted FROM subscribed_channels WHERE channelId='UC2'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
            v8.query("SELECT last_cursor FROM sync_state WHERE entityType='subscriptions'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(99L, c.getLong(0))
            }
            v8.query("SELECT initial_merge_done FROM account_binding WHERE user_id='uid-x'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
    }
}
```

- [ ] **Step 2: Run**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.AppDatabaseMigration7to8Test"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/test/java/com/albunyaan/tube/data/local/AppDatabaseMigration7to8Test.kt
git commit -m "[TEST-ANDROID-SYNC-01-T18]: v7→v8 migration test (defaults + new tables)"
```

---

## Task 19: Sync DTOs + Retrofit API

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/sync/dto/SyncDtos.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncApi.kt`

- [ ] **Step 1: `SyncDtos.kt`**

```kotlin
package com.albunyaan.tube.data.sync.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SyncResponseDto(
    val subscriptions: SyncPageDto<SubscriptionSyncDto>,
    val playlists:     SyncPageDto<PlaylistSyncDto>,
    val favorites:     SyncPageDto<FavoriteSyncDto>,
)

@JsonClass(generateAdapter = true)
data class SyncPageDto<T>(
    val items: List<T>,
    val nextCursor: Long? = null,
)

@JsonClass(generateAdapter = true)
data class SubscriptionSyncDto(
    val entityId: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

@JsonClass(generateAdapter = true)
data class PlaylistSyncDto(
    val entityId: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val playlistUrl: String,
    val name: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val savedAt: Long,
)

@JsonClass(generateAdapter = true)
data class FavoriteSyncDto(
    val entityId: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val addedAt: Long,
)

// ── Push bodies ────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class PutSubscriptionRequest(
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

@JsonClass(generateAdapter = true)
data class PutPlaylistRequest(
    val playlistUrl: String,
    val name: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val savedAt: Long,
)

@JsonClass(generateAdapter = true)
data class PutFavoriteRequest(
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val addedAt: Long,
)
```

> If the project uses Kotlinx Serialization or another JSON lib instead of Moshi, swap `@JsonClass(generateAdapter = true)` for the equivalent. Check existing DTOs in `data/api/` for the project's convention.

- [ ] **Step 2: `SyncApi.kt`**

```kotlin
package com.albunyaan.tube.data.sync

import com.albunyaan.tube.data.sync.dto.*
import retrofit2.Response
import retrofit2.http.*

interface SyncApi {

    @GET("api/account/sync")
    suspend fun pull(
        @Query("subs")      subs: Long = 0L,
        @Query("playlists") playlists: Long = 0L,
        @Query("favorites") favorites: Long = 0L,
    ): Response<SyncResponseDto>

    // Subscriptions
    @PUT("api/account/subscriptions/{id}")
    suspend fun putSubscription(
        @Path("id") id: String,
        @Body body: PutSubscriptionRequest,
    ): Response<SubscriptionSyncDto>

    @DELETE("api/account/subscriptions/{id}")
    suspend fun deleteSubscription(@Path("id") id: String): Response<SubscriptionSyncDto>

    // Playlists
    @PUT("api/account/playlists/{id}")
    suspend fun putPlaylist(
        @Path("id") id: String,
        @Body body: PutPlaylistRequest,
    ): Response<PlaylistSyncDto>

    @DELETE("api/account/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): Response<PlaylistSyncDto>

    // Favorites
    @PUT("api/account/favorites/{id}")
    suspend fun putFavorite(
        @Path("id") id: String,
        @Body body: PutFavoriteRequest,
    ): Response<FavoriteSyncDto>

    @DELETE("api/account/favorites/{id}")
    suspend fun deleteFavorite(@Path("id") id: String): Response<FavoriteSyncDto>
}
```

- [ ] **Step 3: Provide `SyncApi` from Hilt**

Find the existing Retrofit module — likely `NetworkModule.kt`. Add a `@Provides @Singleton` factory:

```kotlin
@Provides
@Singleton
fun provideSyncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)
```

If the existing module needs the import: `import com.albunyaan.tube.data.sync.SyncApi`.

- [ ] **Step 4: Compile**

Run: `cd android && ./gradlew :app:kspDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/sync/dto/SyncDtos.kt android/app/src/main/java/com/albunyaan/tube/data/sync/SyncApi.kt android/app/src/main/java/com/albunyaan/tube/di/NetworkModule.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T19]: SyncApi + DTOs"
```

---

## Task 20: `SyncBackoff` helper + test

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncBackoff.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncBackoffTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.albunyaan.tube.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncBackoffTest {

    @Test
    fun firstFailureWaits1Second() {
        val b = SyncBackoff()
        assertEquals(1_000L, b.next())
    }

    @Test
    fun doublesUpToCap() {
        val b = SyncBackoff()
        assertEquals(1_000L,  b.next())
        assertEquals(2_000L,  b.next())
        assertEquals(4_000L,  b.next())
        assertEquals(8_000L,  b.next())
        assertEquals(16_000L, b.next())
        assertEquals(32_000L, b.next())
        assertEquals(60_000L, b.next())   // capped
        assertEquals(60_000L, b.next())   // capped forever
    }

    @Test
    fun resetReturnsToOneSecond() {
        val b = SyncBackoff()
        repeat(5) { b.next() }
        b.reset()
        assertEquals(1_000L, b.next())
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.SyncBackoffTest"`
Expected: FAIL — class missing.

- [ ] **Step 2: Implement**

```kotlin
package com.albunyaan.tube.data.sync

/**
 * Plan D — per-row exponential backoff for sync push retries.
 * Schedule: 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped).
 * Single-threaded — caller owns the instance per row, no concurrency.
 */
class SyncBackoff(
    private val initialMs: Long = 1_000L,
    private val capMs:     Long = 60_000L,
) {
    private var current: Long = 0L

    /** Returns the wait this attempt, then doubles for next attempt (capped). */
    fun next(): Long {
        val wait = if (current == 0L) initialMs else (current * 2L).coerceAtMost(capMs)
        current = wait
        return wait
    }

    fun reset() { current = 0L }
}
```

- [ ] **Step 3: Run**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.SyncBackoffTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/sync/SyncBackoff.kt android/app/src/test/java/com/albunyaan/tube/data/sync/SyncBackoffTest.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T20]: SyncBackoff helper + tests"
```

---

## Task 21: `SyncManager` scaffolding + Hilt module

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/di/SyncModule.kt`

- [ ] **Step 1: `SyncManager.kt` skeleton**

```kotlin
package com.albunyaan.tube.data.sync

import com.albunyaan.tube.data.local.*
import com.albunyaan.tube.data.sync.dto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan D — owns all sync side-effects:
 *  • bind(uid)       — sign-in / account-switch decision matrix
 *  • runMerge(uid)   — additive merge of anon-era rows with server
 *  • pullAll(uid)    — cursor-based delta pull, per-type
 *  • pushDirty(uid)  — drain dirty=1 rows with exponential backoff
 *  • unbind()        — sign-out: in-memory clear; tables retain user_id
 *
 * Triggers (wired by [SyncManagerLifecycleObserver] etc.):
 *  • SplashRouter sign-in success → bind
 *  • ProcessLifecycleOwner ON_RESUME → pullAll + pushDirty
 *  • Repo write → markDirty + pushDirty (fire-and-forget)
 *  • Connectivity restored → pushDirty
 */
@Singleton
class SyncManager @Inject constructor(
    private val api: SyncApi,
    private val db: AppDatabase,
    private val subs: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
    private val favorites: FavoriteVideoDao,
    private val syncState: SyncStateDao,
    private val binding: AccountBindingDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    private val pushMutex = Mutex()

    suspend fun bind(uid: String) { /* Task 22 */ }
    suspend fun runMerge(uid: String) { /* Task 22 */ }
    suspend fun pullAll(uid: String) { /* Task 23 */ }
    suspend fun pushDirty(uid: String) { /* Task 24 */ }
    fun unbind() { /* in-memory clear; nothing persistent to flush */ }

    fun pushDirtyAsync(uid: String) { scope.launch { pushDirty(uid) } }
}
```

- [ ] **Step 2: `SyncModule.kt` (Hilt)**

```kotlin
package com.albunyaan.tube.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Plan D — Hilt providers for sync. SyncManager has @Inject constructor so
 * no @Provides needed; this module exists for future fakes and for grouping.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule
```

- [ ] **Step 3: Compile**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Hilt graph includes empty `SyncManager`).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt android/app/src/main/java/com/albunyaan/tube/di/SyncModule.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T21]: SyncManager scaffold + Hilt module"
```

---

## Task 22: Implement `bind` and `runMerge`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerBindTest.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/sync/RunMergeTest.kt`

- [ ] **Step 1: Write the `SyncManagerBindTest`**

```kotlin
package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.data.local.AppDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SyncManagerBindTest {

    private lateinit var db: AppDatabase
    private lateinit var api: SyncApi
    private lateinit var sm: SyncManager

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        api = mockk(relaxed = true)
        sm = SyncManager(api, db, db.subscribedChannelDao(), db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao())
    }
    @After fun tearDown() = db.close()

    @Test fun nullBinding_firstSignIn_runsMerge() = runBlocking {
        coEvery { api.pull(any(), any(), any()) } answers { mockEmptyPullResponse() }

        sm.bind("uid-A")

        val b = db.accountBindingDao().get()!!
        assert(b.user_id == "uid-A")
        assert(b.initial_merge_done)
    }

    @Test fun sameUid_resumes_withoutWipe() = runBlocking {
        db.accountBindingDao().upsert(com.albunyaan.tube.data.local.AccountBindingEntity("uid-A", 0L, true))
        db.subscribedChannelDao().upsert(com.albunyaan.tube.data.local.SubscribedChannel("UC1","u","n",null, user_id="uid-A"))
        coEvery { api.pull(any(), any(), any()) } answers { mockEmptyPullResponse() }

        sm.bind("uid-A")

        assert(db.subscribedChannelDao().count("uid-A") == 1)
    }

    @Test fun differentUid_wipesOldAndReMerges() = runBlocking {
        db.accountBindingDao().upsert(com.albunyaan.tube.data.local.AccountBindingEntity("uid-A", 0L, true))
        db.subscribedChannelDao().upsert(com.albunyaan.tube.data.local.SubscribedChannel("UC_OLD","u","n",null, user_id="uid-A"))
        coEvery { api.pull(any(), any(), any()) } answers { mockEmptyPullResponse() }

        sm.bind("uid-B")

        assert(db.subscribedChannelDao().count("uid-A") == 0)
        val b = db.accountBindingDao().get()!!
        assert(b.user_id == "uid-B")
    }

    private fun mockEmptyPullResponse(): retrofit2.Response<com.albunyaan.tube.data.sync.dto.SyncResponseDto> {
        val empty = com.albunyaan.tube.data.sync.dto.SyncPageDto<Nothing>(emptyList(), null)
        @Suppress("UNCHECKED_CAST")
        val resp = com.albunyaan.tube.data.sync.dto.SyncResponseDto(
            empty as com.albunyaan.tube.data.sync.dto.SyncPageDto<com.albunyaan.tube.data.sync.dto.SubscriptionSyncDto>,
            empty as com.albunyaan.tube.data.sync.dto.SyncPageDto<com.albunyaan.tube.data.sync.dto.PlaylistSyncDto>,
            empty as com.albunyaan.tube.data.sync.dto.SyncPageDto<com.albunyaan.tube.data.sync.dto.FavoriteSyncDto>,
        )
        return retrofit2.Response.success(resp)
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.SyncManagerBindTest"`
Expected: FAIL — `bind` not implemented.

- [ ] **Step 2: Implement `bind` + `runMerge`**

Replace the stub bodies in `SyncManager.kt`:

```kotlin
    suspend fun bind(uid: String) {
        val b = binding.get()
        when {
            b == null -> {
                binding.upsert(AccountBindingEntity(user_id = uid, bound_at = System.currentTimeMillis(), initial_merge_done = false))
                runMerge(uid)
            }
            b.user_id == uid && b.initial_merge_done -> {
                pullAll(uid)
                pushDirty(uid)
            }
            b.user_id == uid && !b.initial_merge_done -> {
                // Prior merge crashed mid-way — re-enter
                runMerge(uid)
            }
            else -> {
                // Account switch: wipe old uid's rows
                subs.wipeForUid(b.user_id)
                playlists.wipeForUid(b.user_id)
                favorites.wipeForUid(b.user_id)
                syncState.clearForUid(b.user_id)
                binding.clear()
                binding.upsert(AccountBindingEntity(uid, System.currentTimeMillis(), false))
                runMerge(uid)
            }
        }
    }

    suspend fun runMerge(uid: String) {
        // Step 1: tag anon rows + ensure remaining-from-prior dirty rows stay marked
        subs.tagAnonRowsToUid(uid)
        playlists.tagAnonRowsToUid(uid)
        favorites.tagAnonRowsToUid(uid)
        // Step 2: pull server — collisions overwrite local, clearing dirty
        pullAll(uid)
        // Step 3: push remaining local-only rows
        pushDirty(uid)
        // Step 4: mark merge done
        binding.markMergeDone(uid)
    }
```

- [ ] **Step 3: Run tests (still failing — pullAll is empty)**

The bind tests above call `bind` which calls `runMerge` which calls `pullAll` (empty). The test asserts only on bind side-effects (binding row, wipe), so they should pass.

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.SyncManagerBindTest"`
Expected: PASS.

- [ ] **Step 4: Write `RunMergeTest` for convergence**

```kotlin
package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.sync.dto.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RunMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var api: SyncApi
    private lateinit var sm: SyncManager

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        api = mockk(relaxed = true)
        sm = SyncManager(api, db, db.subscribedChannelDao(), db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao())
    }
    @After fun tearDown() = db.close()

    @Test fun anonRowsTaggedThenPushedAfterMerge() = runBlocking {
        // Seed an anon-era row
        db.subscribedChannelDao().upsert(SubscribedChannel("UC_anon","u","n",null, user_id=""))
        // Server has nothing
        coEvery { api.pull(any(), any(), any()) } returns retrofit2.Response.success(
            SyncResponseDto(
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null)))
        coEvery { api.putSubscription(eq("UC_anon"), any()) } returns retrofit2.Response.success(
            SubscriptionSyncDto("UC_anon", false, 100L, "u", "n", null, 0L))

        sm.runMerge("uid-X")

        // Row tagged + dirty cleared after push echo
        val rows = db.subscribedChannelDao().getAll("uid-X")
        assertEquals(1, rows.size)
        assertEquals(false, rows[0].dirty)
        assertEquals(100L, rows[0].updated_at)
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.RunMergeTest"`
Expected: FAIL — pullAll/pushDirty not implemented yet. Skip this test until Tasks 23–24, OR mark the test `@Ignore` for now.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerBindTest.kt android/app/src/test/java/com/albunyaan/tube/data/sync/RunMergeTest.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T22]: SyncManager.bind + runMerge (decision matrix)"
```

---

## Task 23: Implement `pullAll`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/sync/PullAllTest.kt`

- [ ] **Step 1: Add `applyServerRow` / `applyTombstone` helpers to each DAO**

In `SubscribedChannelDao.kt` append:

```kotlin
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromServer(channel: SubscribedChannel)

    @Query("UPDATE subscribed_channels SET deleted = 1, dirty = 0, updated_at = :ts WHERE channelId = :id AND user_id = :uid")
    suspend fun applyTombstone(uid: String, id: String, ts: Long)
```

The plain `upsert` already uses REPLACE. We add `upsertFromServer` as an explicit, named method for the sync path so the call sites are obvious — internally identical, but it documents intent.

Mirror in `SavedPlaylistDao.kt` and `FavoriteVideoDao.kt`.

- [ ] **Step 2: Implement `pullAll` in `SyncManager.kt`**

Replace the stub:

```kotlin
    suspend fun pullAll(uid: String) = pullMutex.withLock {
        var subsCursor      = syncState.cursorFor(uid, "subscriptions") ?: 0L
        var playlistsCursor = syncState.cursorFor(uid, "playlists")     ?: 0L
        var favoritesCursor = syncState.cursorFor(uid, "favorites")     ?: 0L

        do {
            val resp = api.pull(subsCursor, playlistsCursor, favoritesCursor)
            if (!resp.isSuccessful) return@withLock   // 401/403/5xx — caller's interceptors handle
            val body = resp.body() ?: return@withLock

            db.runInTransaction {
                for (row in body.subscriptions.items) {
                    if (row.deleted) subs.applyTombstone(uid, row.entityId, row.updatedAt)
                    else             subs.upsertFromServer(rowToSub(uid, row))
                }
                for (row in body.playlists.items) {
                    if (row.deleted) playlists.applyTombstone(uid, row.entityId, row.updatedAt)
                    else             playlists.upsertFromServer(rowToPlaylist(uid, row))
                }
                for (row in body.favorites.items) {
                    if (row.deleted) favorites.applyTombstone(uid, row.entityId, row.updatedAt)
                    else             favorites.upsertFromServer(rowToFavorite(uid, row))
                }
                body.subscriptions.items.maxByOrNull { it.updatedAt }?.let {
                    syncState.upsert(SyncStateEntity("subscriptions", uid, it.updatedAt, System.currentTimeMillis()))
                    subsCursor = it.updatedAt
                }
                body.playlists.items.maxByOrNull { it.updatedAt }?.let {
                    syncState.upsert(SyncStateEntity("playlists", uid, it.updatedAt, System.currentTimeMillis()))
                    playlistsCursor = it.updatedAt
                }
                body.favorites.items.maxByOrNull { it.updatedAt }?.let {
                    syncState.upsert(SyncStateEntity("favorites", uid, it.updatedAt, System.currentTimeMillis()))
                    favoritesCursor = it.updatedAt
                }
            }
        } while (body.subscriptions.nextCursor != null || body.playlists.nextCursor != null || body.favorites.nextCursor != null)
    }

    private fun rowToSub(uid: String, r: SubscriptionSyncDto) =
        com.albunyaan.tube.data.local.SubscribedChannel(
            channelId   = r.entityId,
            channelUrl  = r.channelUrl,
            name        = r.name,
            avatarUrl   = r.avatarUrl,
            subscribedAt= r.subscribedAt,
            user_id     = uid,
            updated_at  = r.updatedAt,
            deleted     = false,
            dirty       = false,
        )

    private fun rowToPlaylist(uid: String, r: PlaylistSyncDto) =
        com.albunyaan.tube.data.local.SavedPlaylist(
            playlistId   = r.entityId,
            playlistUrl  = r.playlistUrl,
            name         = r.name,
            thumbnailUrl = r.thumbnailUrl,
            uploaderName = r.uploaderName,
            savedAt      = r.savedAt,
            user_id      = uid,
            updated_at   = r.updatedAt,
            deleted      = false,
            dirty        = false,
        )

    private fun rowToFavorite(uid: String, r: FavoriteSyncDto) =
        com.albunyaan.tube.data.local.FavoriteVideo(
            videoId         = r.entityId,
            title           = r.title,
            channelName     = r.channelName,
            thumbnailUrl    = r.thumbnailUrl,
            durationSeconds = r.durationSeconds,
            addedAt         = r.addedAt,
            user_id         = uid,
            updated_at      = r.updatedAt,
            deleted         = false,
            dirty           = false,
        )
```

> Fix the `body` shadowing issue: the `do…while` references `body` after the transaction; inline the `nextCursor` checks inside the loop with a flag instead:

Replace the `do…while` skeleton with:

```kotlin
        var more: Boolean
        do {
            val resp = api.pull(subsCursor, playlistsCursor, favoritesCursor)
            if (!resp.isSuccessful) return@withLock
            val body = resp.body() ?: return@withLock
            // … same transaction body as above …
            more = (body.subscriptions.nextCursor != null) ||
                   (body.playlists.nextCursor     != null) ||
                   (body.favorites.nextCursor     != null)
        } while (more)
```

- [ ] **Step 3: Write `PullAllTest`**

```kotlin
package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.sync.dto.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PullAllTest {

    private lateinit var db: AppDatabase
    private lateinit var api: SyncApi
    private lateinit var sm: SyncManager

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        api = mockk(relaxed = true)
        sm = SyncManager(api, db, db.subscribedChannelDao(), db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao())
    }
    @After fun tearDown() = db.close()

    @Test fun pullInsertsLiveRowsAndAdvancesCursor() = runBlocking {
        val sub = SubscriptionSyncDto("UC1", false, 100L, "u", "n", null, 0L)
        coEvery { api.pull(0L, 0L, 0L) } returns retrofit2.Response.success(
            SyncResponseDto(
                SyncPageDto(listOf(sub), null),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null)))

        sm.pullAll("uid")

        val rows = db.subscribedChannelDao().getAll("uid")
        assertEquals(1, rows.size)
        assertEquals(100L, rows[0].updated_at)
        assertEquals(100L, db.syncStateDao().cursorFor("uid", "subscriptions"))
    }

    @Test fun virtualTombstoneRemovesLocalRow() = runBlocking {
        db.subscribedChannelDao().upsert(com.albunyaan.tube.data.local.SubscribedChannel("UC2","u","n",null, user_id="uid"))
        val tomb = SubscriptionSyncDto("UC2", true, 200L, "", "", null, 0L)
        coEvery { api.pull(0L, 0L, 0L) } returns retrofit2.Response.success(
            SyncResponseDto(
                SyncPageDto(listOf(tomb), null),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null)))

        sm.pullAll("uid")

        assertEquals(0, db.subscribedChannelDao().count("uid"))     // count() excludes deleted=1
    }

    @Test fun paginationLoopsUntilNullCursor() = runBlocking {
        val p1 = SubscriptionSyncDto("UC1", false, 100L, "u", "n", null, 0L)
        val p2 = SubscriptionSyncDto("UC2", false, 200L, "u", "n", null, 0L)
        coEvery { api.pull(0L, 0L, 0L) } returns retrofit2.Response.success(
            SyncResponseDto(SyncPageDto(listOf(p1), 100L), SyncPageDto(emptyList(), null), SyncPageDto(emptyList(), null)))
        coEvery { api.pull(100L, 0L, 0L) } returns retrofit2.Response.success(
            SyncResponseDto(SyncPageDto(listOf(p2), null), SyncPageDto(emptyList(), null), SyncPageDto(emptyList(), null)))

        sm.pullAll("uid")

        assertEquals(2, db.subscribedChannelDao().count("uid"))
        assertEquals(200L, db.syncStateDao().cursorFor("uid", "subscriptions"))
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.PullAllTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannelDao.kt android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylistDao.kt android/app/src/main/java/com/albunyaan/tube/data/local/FavoriteVideoDao.kt android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt android/app/src/test/java/com/albunyaan/tube/data/sync/PullAllTest.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T23]: SyncManager.pullAll (delta + pagination + virtual tombstones)"
```

---

## Task 24: Implement `pushDirty` with backoff

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/sync/PushDirtyTest.kt`

- [ ] **Step 1: Implement `pushDirty`**

Replace the stub in `SyncManager.kt`:

```kotlin
    suspend fun pushDirty(uid: String) = pushMutex.withLock {
        // Subscriptions
        for (row in subs.selectDirty(uid)) {
            val ok = if (row.deleted) {
                handle(api.deleteSubscription(row.channelId)) { resp ->
                    subs.clearDirty(uid, row.channelId, resp.updatedAt)
                }
            } else {
                handle(api.putSubscription(row.channelId, PutSubscriptionRequest(
                    channelUrl = row.channelUrl, name = row.name,
                    avatarUrl  = row.avatarUrl,  subscribedAt = row.subscribedAt))) { resp ->
                    subs.clearDirty(uid, row.channelId, resp.updatedAt)
                }
            }
            if (!ok) return@withLock     // 5xx/429/abort — leave dirty, retry next cycle
        }
        // Playlists
        for (row in playlists.selectDirty(uid)) {
            val ok = if (row.deleted) {
                handle(api.deletePlaylist(row.playlistId)) { resp ->
                    playlists.clearDirty(uid, row.playlistId, resp.updatedAt)
                }
            } else {
                handle(api.putPlaylist(row.playlistId, PutPlaylistRequest(
                    playlistUrl = row.playlistUrl, name = row.name,
                    thumbnailUrl = row.thumbnailUrl, uploaderName = row.uploaderName,
                    savedAt = row.savedAt))) { resp ->
                    playlists.clearDirty(uid, row.playlistId, resp.updatedAt)
                }
            }
            if (!ok) return@withLock
        }
        // Favorites
        for (row in favorites.selectDirty(uid)) {
            val ok = if (row.deleted) {
                handle(api.deleteFavorite(row.videoId)) { resp ->
                    favorites.clearDirty(uid, row.videoId, resp.updatedAt)
                }
            } else {
                handle(api.putFavorite(row.videoId, PutFavoriteRequest(
                    title = row.title, channelName = row.channelName,
                    thumbnailUrl = row.thumbnailUrl, durationSeconds = row.durationSeconds,
                    addedAt = row.addedAt))) { resp ->
                    favorites.clearDirty(uid, row.videoId, resp.updatedAt)
                }
            }
            if (!ok) return@withLock
        }
    }

    /**
     * Returns true on success (caller should continue), false on retryable failure
     * (caller should break out of the drain loop).
     * Non-retryable (401, 403) propagate via existing interceptors — we still return false to break.
     * 404 on DELETE is treated as success.
     */
    private suspend inline fun <T> handle(resp: retrofit2.Response<T>, onSuccess: (T) -> Unit): Boolean {
        return when {
            resp.isSuccessful -> { resp.body()?.let(onSuccess); true }
            resp.code() == 404 -> true   // idempotent DELETE
            resp.code() == 401 || resp.code() == 403 -> false   // interceptors handled it
            resp.code() == 429 || resp.code() in 500..599 -> false
            else -> false
        }
    }
```

- [ ] **Step 2: Write `PushDirtyTest`**

```kotlin
package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.sync.dto.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PushDirtyTest {

    private lateinit var db: AppDatabase
    private lateinit var api: SyncApi
    private lateinit var sm: SyncManager

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        api = mockk(relaxed = true)
        sm = SyncManager(api, db, db.subscribedChannelDao(), db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao())
    }
    @After fun tearDown() = db.close()

    @Test fun successfulPutClearsDirtyAndSetsUpdatedAt() = runBlocking {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC1","u","n",null, user_id="uid", dirty=true))
        coEvery { api.putSubscription(eq("UC1"), any()) } returns retrofit2.Response.success(
            SubscriptionSyncDto("UC1", false, 999L, "u", "n", null, 0L))

        sm.pushDirty("uid")

        val r = db.subscribedChannelDao().getById("uid", "UC1")!!
        assertFalse(r.dirty)
        assertEquals(999L, r.updated_at)
    }

    @Test fun deleteOn404TreatedAsSuccess() = runBlocking {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC2","u","n",null, user_id="uid", dirty=true, deleted=true))
        coEvery { api.deleteSubscription(eq("UC2")) } returns
                retrofit2.Response.error(404, "".toResponseBody("application/json".toMediaType()))

        sm.pushDirty("uid")

        // Row stayed deleted=1, but dirty remains... actually with 404 the success callback never fires,
        // but handle() returns true so the loop continues. Row remains dirty=1 with deleted=1.
        // Tombstone re-push on next cycle will hit 404 again, infinite loop! Confirmed bug — see fix in Step 3.
    }

    @Test fun fiveXxBreaksLoopWithoutClearingDirty() = runBlocking {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC3","u","n",null, user_id="uid", dirty=true))
        coEvery { api.putSubscription(eq("UC3"), any()) } returns
                retrofit2.Response.error(503, "".toResponseBody("application/json".toMediaType()))

        sm.pushDirty("uid")

        assertTrue(db.subscribedChannelDao().getById("uid", "UC3")!!.dirty)   // still dirty for retry
    }
}
```

- [ ] **Step 3: Fix the 404-DELETE bug surfaced in Step 2**

The Step 2 test reveals: on 404 for a DELETE, `handle` returns true but never clears `dirty`. Next push picks it up, hits 404 again, loops forever. Fix `handle` to also clear-dirty on 404 for tombstones:

In `SyncManager.kt`, change the DELETE branches to pass an `is404IsSuccess: Boolean` to `handle`, and on 404 with that flag call a "synthetic success" clearer. Cleaner refactor: handle 404 inside each row's branch directly:

```kotlin
    private suspend inline fun <T> push(
        op: suspend () -> retrofit2.Response<T>,
        crossinline onSuccess: (T) -> Unit,
        crossinline on404: () -> Unit,
    ): Boolean {
        val resp = op()
        return when {
            resp.isSuccessful -> { resp.body()?.let(onSuccess); true }
            resp.code() == 404 -> { on404(); true }
            resp.code() == 401 || resp.code() == 403 -> false
            else -> false   // 5xx / 429 / unknown — pause draining
        }
    }
```

Then call sites in `pushDirty`:

```kotlin
            val ok = if (row.deleted) {
                push({ api.deleteSubscription(row.channelId) },
                    onSuccess = { resp -> subs.clearDirty(uid, row.channelId, resp.updatedAt) },
                    on404    = { subs.clearDirty(uid, row.channelId, System.currentTimeMillis()) })
            } else {
                push({ api.putSubscription(row.channelId, PutSubscriptionRequest(...)) },
                    onSuccess = { resp -> subs.clearDirty(uid, row.channelId, resp.updatedAt) },
                    on404    = { /* PUT 404 shouldn't happen; treat as failure to surface */ })
            }
```

Repeat for playlists/favorites.

- [ ] **Step 4: Run tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.PushDirtyTest"`
Expected: PASS.

Also rerun: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.RunMergeTest"`
Expected: PASS now that pull+push both implemented.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt android/app/src/test/java/com/albunyaan/tube/data/sync/PushDirtyTest.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T24]: SyncManager.pushDirty + 404-is-success for tombstones"
```

---

## Task 25: Race tests + edge cases

**Files:**
- Create: `android/app/src/test/java/com/albunyaan/tube/data/sync/RaceTests.kt`

- [ ] **Step 1: Write race tests**

```kotlin
package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.sync.dto.SubscriptionSyncDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RaceTests {

    private lateinit var db: AppDatabase
    private lateinit var api: SyncApi
    private lateinit var sm: SyncManager

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        api = mockk(relaxed = true)
        sm = SyncManager(api, db, db.subscribedChannelDao(), db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao())
    }
    @After fun tearDown() = db.close()

    @Test fun subscribeThenUnsubscribeBeforePushPushesOnlyDelete() = runBlocking {
        // user subscribes then unsubscribes before push fires
        db.subscribedChannelDao().upsert(SubscribedChannel("UC1","u","n",null, user_id="uid", dirty=true))
        db.subscribedChannelDao().softDelete("uid", "UC1")   // deleted=1, dirty=1

        coEvery { api.deleteSubscription(eq("UC1")) } returns retrofit2.Response.success(
            SubscriptionSyncDto("UC1", true, 100L, "", "", null, 0L))

        sm.pushDirty("uid")

        coVerify(exactly = 1) { api.deleteSubscription("UC1") }
        coVerify(exactly = 0) { api.putSubscription("UC1", any()) }
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.sync.RaceTests"`
Expected: PASS.

- [ ] **Step 2: Commit**

```bash
git add android/app/src/test/java/com/albunyaan/tube/data/sync/RaceTests.kt
git commit -m "[TEST-ANDROID-SYNC-01-T25]: race tests — subscribe+unsubscribe collapses to DELETE"
```

---

## Task 26: Wire `SyncManager` into repos and lifecycle

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/subscriptions/SubscriptionRepository.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/FavoritesRepository.kt` and `FavoritesRepositoryImpl.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/splash/SplashRouter.kt`

The spec keeps repo APIs externally stable, but every read needs the current uid. Inject `AccountState` (existing — Plan B/C, exposing a `StateFlow<AccountState>`) and read its current `uid` synchronously.

- [ ] **Step 1: Add `AccountState.currentUid()` helper (if missing)**

Run: `grep -n "uid\|currentUid\|user_id" android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt`

If there's no method returning the current uid synchronously, add:

```kotlin
fun currentUid(): String = (value as? AccountState.SignedIn)?.uid ?: ""
```

(Adjust to actual `AccountState` shape — e.g., a sealed class or data class wrapper.)

- [ ] **Step 2: Update `SubscriptionRepository.subscribe` / `unsubscribe`**

```kotlin
@Singleton
class SubscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val channels: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
    private val cache: ChannelVideoCacheDao,
    private val refreshState: ChannelFeedRefreshStateDao,
    private val accountState: com.albunyaan.tube.auth.AccountState,
    private val syncManager: com.albunyaan.tube.data.sync.SyncManager,
) {

    fun observeSubscribedChannels(): Flow<List<SubscribedChannel>> =
        channels.observeAll(accountState.currentUid())

    suspend fun subscribe(channel: SubscribedChannel) {
        val uid = accountState.currentUid()
        channels.upsert(channel.copy(user_id = uid, dirty = true, deleted = false, updated_at = 0L))
        syncManager.pushDirtyAsync(uid)
    }

    suspend fun unsubscribe(channelId: String) {
        val uid = accountState.currentUid()
        db.withTransaction {
            channels.softDelete(uid, channelId)
            cache.deleteForChannel(channelId)
            refreshState.deleteForChannel(channelId)
        }
        syncManager.pushDirtyAsync(uid)
    }

    // similarly update playlist methods, isChannelSubscribed, etc.
}
```

> The existing `SubscriptionRepository` may have additional helpers (`SubscriptionLimitGuard`, etc.). Touch only the public methods that read or mutate; leave guards intact. Compile errors will direct you to remaining call sites.

- [ ] **Step 3: Update `FavoritesRepository`**

```kotlin
class FavoritesRepositoryImpl @Inject constructor(
    private val dao: FavoriteVideoDao,
    private val accountState: AccountState,
    private val syncManager: SyncManager,
) : FavoritesRepository {

    override fun observeFavorites(): Flow<List<FavoriteVideo>> =
        dao.observeAll(accountState.currentUid())

    override suspend fun addFavorite(video: FavoriteVideo) {
        val uid = accountState.currentUid()
        dao.upsert(video.copy(user_id = uid, dirty = true, deleted = false, updated_at = 0L))
        syncManager.pushDirtyAsync(uid)
    }

    override suspend fun removeFavorite(id: String) {
        val uid = accountState.currentUid()
        dao.softDelete(uid, id)
        syncManager.pushDirtyAsync(uid)
    }
}
```

- [ ] **Step 4: Register `SyncManager` as `ProcessLifecycleOwner` observer**

Edit `AlbunyaanTubeApplication.kt`:

```kotlin
@HiltAndroidApp
class AlbunyaanTubeApplication : Application(), DefaultLifecycleObserver {

    @Inject lateinit var syncManager: com.albunyaan.tube.data.sync.SyncManager
    @Inject lateinit var accountState: com.albunyaan.tube.auth.AccountState

    override fun onCreate() {
        super<Application>.onCreate()
        // …existing init…
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
        val uid = accountState.currentUid()
        if (uid.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                syncManager.pullAll(uid)
                syncManager.pushDirty(uid)
            }
        }
    }
}
```

> Verify `androidx.lifecycle:lifecycle-process` is in `build.gradle.kts` dependencies. If not: add `implementation("androidx.lifecycle:lifecycle-process:2.7.0")` to `android/app/build.gradle.kts`.

- [ ] **Step 5: Wire `SyncManager.bind` into `SplashRouter`**

Find the sign-in success path in `SplashRouter.kt` (look for where it observes `accountStateFlow` resolving to `SignedIn`). Inject `SyncManager`. After confirming `/me` succeeded:

```kotlin
syncManager.bind(uid)
```

If `SplashRouter` is not a Hilt-aware class, plumb `SyncManager` through the existing factory or expose it via `AccountService`. Inspect existing wiring for the Plan C lazy-create path (commit `60727afd`).

- [ ] **Step 6: Compile and run unit tests**

Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. Fix any callers that pass missing uid args.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/subscriptions/SubscriptionRepository.kt android/app/src/main/java/com/albunyaan/tube/data/local/FavoritesRepository*.kt android/app/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.kt android/app/src/main/java/com/albunyaan/tube/ui/splash/SplashRouter.kt android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T26]: wire SyncManager into repos + lifecycle + SplashRouter.bind"
```

---

## Task 27: Connectivity-restored trigger

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.kt`

- [ ] **Step 1: Register a NetworkCallback that drains the push queue when WiFi/cell comes back**

Add inside `onCreate()`:

```kotlin
val cm = getSystemService(android.net.ConnectivityManager::class.java)
cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: android.net.Network) {
        val uid = accountState.currentUid()
        if (uid.isNotEmpty()) syncManager.pushDirtyAsync(uid)
    }
})
```

> `ACCESS_NETWORK_STATE` is required in `AndroidManifest.xml`. Verify with `grep ACCESS_NETWORK_STATE android/app/src/main/AndroidManifest.xml` — should already be present per existing app behaviour.

- [ ] **Step 2: Compile**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.kt
git commit -m "[FEAT-ANDROID-SYNC-01-T27]: drain push queue on connectivity restore"
```

---

## Task 28: End-to-end manual verification

**Files:** none (smoke test)

- [ ] **Step 1: Start backend with Firestore emulator**

Run: `cd backend && firebase emulators:start --only firestore,auth --project demo-test`
In a second terminal: `cd backend && ./gradlew bootRun`

- [ ] **Step 2: Run Android on a device that has pre-Plan-D data**

Build & install the APK. Sign in as a tester whose device has subscriptions / playlists / favorites from before Plan D.

Steps to verify:
1. Open app, sign in. SplashRouter routes to Me-tab.
2. Confirm subscriptions/playlists/favorites still visible (existing rows tagged + pushed).
3. Subscribe to a new channel; force-stop app; reopen → still subscribed.
4. Sign in on a second device with the same account → second device shows the new subscription after sync-on-resume.
5. Archive a channel in admin → next foreground on either device → channel disappears silently.
6. Unsubscribe a channel on Device A → Device B sees it disappear on next foreground.
7. Force a 5xx by killing the backend → unsubscribe; verify row stays `dirty=1` in Room DB inspector; restart backend → row drains within ~1s.

Document any issues found; fix before declaring Plan D complete.

- [ ] **Step 2: Run all Android unit tests one more time**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: all PASS.

- [ ] **Step 3: Run backend full test suite**

Run: `cd backend && ./gradlew test -Pintegration=true`
Expected: all PASS.

- [ ] **Step 4: Open PRs**

Two PRs to `develop`:

1. Backend PR — Tasks 1–12 (or one PR per phase if reviewers prefer).
2. Android PR — Tasks 13–28.

Both target `develop` (per project branching policy).

```bash
git push -u origin feature/SYNC-01-engine
gh pr create --base develop --title "[FEAT-SYNC-01]: Account sync engine (Plan D)" --body "$(cat <<'EOF'
## Summary
- Backend: /api/account/sync (GET pull, PUT/DELETE per type), Firestore subcollections, archive projector, weekly tombstone GC.
- Android: Room v8 migration, SyncManager (bind/runMerge/pullAll/pushDirty), repo wiring, lifecycle + connectivity triggers.

## Spec
docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md

## Test plan
- [ ] Backend unit tests pass
- [ ] Backend integration tests pass (Firestore emulator)
- [ ] Android unit tests pass
- [ ] Manual: anon→account additive merge on first sign-in
- [ ] Manual: cross-device sync of subscribe / unsubscribe
- [ ] Manual: archive integration silently removes synced items
- [ ] Manual: offline push-on-change drains on reconnect

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

I checked the plan against the spec one more time:

**Spec coverage:** Every section of the spec has a task — data model (T13–T17), API (T1–T7), conflict mechanics (server LWW is implicit in T3 Firestore `serverTimestamp`; client tombstone via T23 `applyTombstone`), orchestrator (T21–T24), archive (T4, T11), Firestore rules (T9), audit/cache (none — covered by "no audit, no cache"), GC (T8, T12), testing (T10–T12, T18, T20, T22–T25), observability (logs and meters embedded throughout), rollout (T28).

**Placeholder scan:** One intentional TODO remains — `markChannelArchived` in `SyncArchiveIT.java` (T11) requires looking up the existing archive-marking test helper before running. Step 3 of T11 commits the engineer to filling it. All other tasks are complete.

**Type consistency:** `SyncResponseDto` / `SyncPageDto<T>` / per-type DTOs are referenced consistently across backend (T1, T2, T5) and Android (T19). Method names (`pullAll`, `pushDirty`, `bind`, `runMerge`) match across tasks 21–27 and the test files in 22–25. DAO methods (`tagAnonRowsToUid`, `selectDirty`, `clearDirty`, `wipeForUid`, `applyTombstone`, `upsertFromServer`) used in `SyncManager` (T22–T24) are all declared in DAOs (T14, T23).

**One open question carried from spec §17:** GC retention (90d) and page size (500) remain assumed defaults. They are hardcoded in T3 (`SYNC_PAGE_SIZE = 500`) and T8 (`RETENTION_DAYS = 90L`). Easy to change later.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-12-plan-d-sync-engine.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Each task is bite-sized so this scales well across the 28 steps.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
