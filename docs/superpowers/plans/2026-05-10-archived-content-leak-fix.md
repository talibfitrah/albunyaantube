# Archived Content Leak Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make archived content (videos, channels, playlists) unreachable from the Android app via search, deep links, in-app taps from stale lists, or downloads.

**Architecture:** Backend gains symmetric archive-time cleanup of the `searchable_streams` index plus archive checks on the download path. Android gains a backend availability check in the three detail ViewModels (channel, playlist, player); deep link and in-app navigation share the same gate because both routes converge on the same destination ViewModels.

**Tech Stack:** Spring Boot + Firestore (backend, JUnit 5 + Mockito), Kotlin + Hilt + Retrofit + Coroutines (Android, JUnit 4 + MockK).

**Branch:** Continue the project's branching policy — work on `develop`, never `main` (per memory: `feedback_branching_policy.md`).

---

## File Map

### Backend — modify
- `backend/src/main/java/com/albunyaan/tube/repository/SearchableStreamRepository.java` — add `markInvisible(streamId)` method
- `backend/src/main/java/com/albunyaan/tube/service/StreamIndexService.java` — add `markStreamArchived(streamId)` method
- `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java` — runtime archive filter inside `searchStreams()`
- `backend/src/main/java/com/albunyaan/tube/service/ContentValidationService.java` — wire cleanup at three archive sites (channel/playlist/video)
- `backend/src/main/java/com/albunyaan/tube/service/ContentReportService.java` — wire cleanup in `archiveReportedContent()` (three branches)
- `backend/src/main/java/com/albunyaan/tube/service/DownloadService.java` — archive guards in `checkDownloadPolicy` and `getDownloadManifest`

### Backend — test files
- `backend/src/test/java/com/albunyaan/tube/repository/SearchableStreamRepositoryTest.java` — new
- `backend/src/test/java/com/albunyaan/tube/service/StreamIndexServiceTest.java` — extend (or create if absent)
- `backend/src/test/java/com/albunyaan/tube/service/PublicContentServiceTest.java` — extend `searchStreams` test cases
- `backend/src/test/java/com/albunyaan/tube/service/ContentValidationServiceTest.java` — extend
- `backend/src/test/java/com/albunyaan/tube/service/ContentReportServiceTest.java` — extend
- `backend/src/test/java/com/albunyaan/tube/service/DownloadServiceTest.java` — extend

### Android — modify
- `android/app/src/main/java/com/albunyaan/tube/data/source/api/ContentApi.kt` — add 3 HEAD/check endpoints
- `android/app/src/main/java/com/albunyaan/tube/data/source/ContentService.kt` — add `verifyAvailable(type, id)` method
- `android/app/src/main/java/com/albunyaan/tube/data/source/RetrofitContentService.kt` — implement
- `android/app/src/main/java/com/albunyaan/tube/data/source/FakeContentService.kt` — stub implementation
- `android/app/src/main/java/com/albunyaan/tube/data/source/FallbackContentService.kt` — pass-through implementation
- `android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailViewModel.kt` — pre-flight availability check
- `android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailViewModel.kt` — pre-flight availability check
- `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt` (or `PlayerViewModel.kt`) — pre-flight availability check before NewPipe resolve
- `android/app/src/main/res/values/strings.xml` — `content_unavailable_title`, `content_unavailable_message`
- `android/app/src/main/res/values-ar/strings.xml` — Arabic translations
- `android/app/src/main/res/values-nl/strings.xml` — Dutch translations

### Android — test files
- Existing ViewModel test files for `ChannelDetailViewModel`, `PlaylistDetailViewModel`, `PlayerViewModel` (extend; create if absent)

---

## Build/Test Commands

| Command | Purpose |
|---|---|
| `cd backend && ./gradlew test` | Run all backend unit tests |
| `cd backend && ./gradlew test --tests *DownloadServiceTest` | Run a single backend test class |
| `cd backend && ./gradlew bootRun` | Run backend on port 8080 |
| `cd android && ./gradlew test` | Run Android unit tests |
| `cd android && ./gradlew assembleDebug` | Build debug APK |
| `cd android && ./gradlew installDebug` | Install on connected device/emulator |

If port 8080 is stuck: `lsof -ti:8080 \| xargs kill -9`. Firebase creds: `export GOOGLE_APPLICATION_CREDENTIALS=backend/src/main/resources/firebase-service-account.json`.

---

## Task 1: Backend — `SearchableStreamRepository.markInvisible(streamId)`

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/repository/SearchableStreamRepository.java`
- Test: `backend/src/test/java/com/albunyaan/tube/repository/SearchableStreamRepositoryTest.java` (new — only if a Firestore test harness exists; otherwise cover via the StreamIndexService tests in Task 2)

**Why:** Individual videos can have multiple `sourceKey` entries (one per channel/playlist that indexed them). The existing `removeSource(streamId, sourceKey)` only flips `visible=false` when the *last* sourceKey is removed. For a single-video archive we need an unconditional flip — this method does that.

- [ ] **Step 1: Add the method**

Open `SearchableStreamRepository.java`. Locate the existing `removeSource(String streamId, String sourceKey)` method (~line 90). Add this method directly after it:

```java
/**
 * Mark a stream as invisible regardless of its sourceKeys.
 * Used when an individual video is archived — the stream may still
 * have valid sources from other channels/playlists, but the video
 * itself is archived and must not appear in search.
 */
public void markInvisible(String streamId)
        throws ExecutionException, InterruptedException, TimeoutException {
    java.util.Map<String, Object> updates = new java.util.HashMap<>();
    updates.put("visible", false);
    firestore.collection(COLLECTION_NAME)
            .document(streamId)
            .update(updates)
            .get(timeoutProperties.getWriteSeconds(), TimeUnit.SECONDS);
}
```

Imports to add if not already present: `java.util.concurrent.ExecutionException`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.TimeoutException`. Match the existing `upsert()` method's exception/timeout pattern.

- [ ] **Step 2: Build the backend to verify it compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/repository/SearchableStreamRepository.java
git commit -m "[FIX]: Add markInvisible() for single-video archive"
```

---

## Task 2: Backend — `StreamIndexService.markStreamArchived(streamId)` (TDD)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/StreamIndexService.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/StreamIndexServiceTest.java` (create if absent)

- [ ] **Step 1: Write the failing test**

Create `StreamIndexServiceTest.java` (or extend if it exists):

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StreamIndexServiceTest {
    @Mock private SearchableStreamRepository streamRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private SearchTokenizer tokenizer;
    @InjectMocks private StreamIndexService streamIndexService;

    @Test
    void markStreamArchived_callsRepositoryMarkInvisible() throws Exception {
        streamIndexService.markStreamArchived("dQw4w9WgXcQ");
        verify(streamRepository).markInvisible("dQw4w9WgXcQ");
    }
}
```

- [ ] **Step 2: Run test, expect failure**

Run: `cd backend && ./gradlew test --tests StreamIndexServiceTest.markStreamArchived_callsRepositoryMarkInvisible`
Expected: FAIL — `markStreamArchived` does not exist on `StreamIndexService`.

- [ ] **Step 3: Implement the method**

In `StreamIndexService.java`, directly below `removeSource(...)` (~line 116):

```java
/**
 * Mark a single stream as invisible — used when an individual video
 * is archived. Unlike removeSource, this flips visibility regardless
 * of remaining sourceKeys.
 */
public void markStreamArchived(String streamId) {
    try {
        streamRepository.markInvisible(streamId);
        log.info("Stream {} marked invisible (archived)", streamId);
    } catch (Exception e) {
        log.warn("markStreamArchived failed for {}: {}", streamId, e.getMessage());
    }
}
```

- [ ] **Step 4: Run test, expect pass**

Run: `cd backend && ./gradlew test --tests StreamIndexServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/StreamIndexService.java backend/src/test/java/com/albunyaan/tube/service/StreamIndexServiceTest.java
git commit -m "[FIX]: Add markStreamArchived() for individual video archive"
```

---

## Task 3: Backend — Runtime archive filter in `searchStreams()` (TDD)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/PublicContentServiceTest.java`

**Why:** Even after Task 4/5 wire the archive cleanup, recovery on restore needs to be automatic. A runtime filter that consults `Video.validationStatus` for each stream candidate handles individual video archive *and* recovers automatically when status flips back to `VALID`.

- [ ] **Step 1: Write the failing test**

Append to `PublicContentServiceTest.java` (mirror the existing search test setup):

```java
@Test
void searchStreams_excludesArchivedVideos() throws Exception {
    // Index returns two streams
    SearchableStream s1 = stream("video-a", "Halal Cooking");
    SearchableStream s2 = stream("video-b", "Halal Cooking 2");
    when(searchableStreamRepository.searchByToken(eq("halal"), anyInt()))
            .thenReturn(List.of(s1, s2));
    when(searchTokenizer.tokenize("halal", null)).thenReturn(List.of("halal"));

    // video-b is archived in the Video collection
    Video archived = video("video-b", ValidationStatus.ARCHIVED);
    Video valid = video("video-a", ValidationStatus.VALID);
    when(videoRepository.findByYoutubeIds(argThat(ids -> ids.contains("video-a") && ids.contains("video-b"))))
            .thenReturn(List.of(valid, archived));

    List<ContentItemDto> results = invokeSearchStreams("halal", 10);

    assertEquals(1, results.size());
    assertEquals("video-a", results.get(0).getId());
}

// Helper builders — add at the bottom of the test class
private SearchableStream stream(String id, String title) {
    SearchableStream s = new SearchableStream();
    s.setStreamId(id);
    s.setTitle(title);
    s.setTitleNorm(title.toLowerCase());
    s.setSearchTokens(List.of("halal"));
    return s;
}
private Video video(String youtubeId, ValidationStatus status) {
    Video v = new Video();
    v.setYoutubeId(youtubeId);
    v.setValidationStatus(status);
    v.setStatus("APPROVED");
    return v;
}
```

If `VideoRepository.findByYoutubeIds(Collection<String>)` does not exist yet, add it now (chunked `whereIn` query, cap 30 IDs per chunk to honor Firestore limits) — see Step 1a below.

- [ ] **Step 1a: Add `VideoRepository.findByYoutubeIds()` if missing**

Search: `grep -n "findByYoutubeIds" backend/src/main/java/com/albunyaan/tube/repository/VideoRepository.java`. If absent, add this method to `VideoRepository.java`:

```java
/**
 * Batch-fetch videos by their YouTube IDs. Chunks into groups of
 * 30 to honor Firestore's whereIn limit.
 */
public List<Video> findByYoutubeIds(Collection<String> youtubeIds)
        throws ExecutionException, InterruptedException, TimeoutException {
    if (youtubeIds == null || youtubeIds.isEmpty()) return List.of();
    List<String> all = new ArrayList<>(new HashSet<>(youtubeIds));
    List<Video> results = new ArrayList<>();
    int chunkSize = 30;
    for (int i = 0; i < all.size(); i += chunkSize) {
        List<String> chunk = all.subList(i, Math.min(i + chunkSize, all.size()));
        results.addAll(
            firestore.collection(COLLECTION_NAME)
                .whereIn("youtubeId", chunk)
                .get()
                .get(timeoutProperties.getReadSeconds(), TimeUnit.SECONDS)
                .toObjects(Video.class)
        );
    }
    return results;
}
```

Match the existing `findByYoutubeId(String)` style for collection name, timeout, and exception declarations.

- [ ] **Step 2: Run test, expect failure**

Run: `cd backend && ./gradlew test --tests PublicContentServiceTest.searchStreams_excludesArchivedVideos`
Expected: FAIL — both videos returned (no filter yet).

- [ ] **Step 3: Modify `searchStreams()` to add the filter**

In `PublicContentService.java`, replace the `return` block of `searchStreams()` (the last lines of the method, ~1748-1753):

```java
candidates.sort((a, b) -> scoreStream(b, normalizedQuery) - scoreStream(a, normalizedQuery));

// Trim early, then filter archived against the Video collection.
List<SearchableStream> top = candidates.stream()
        .limit(limit * 2L)  // overfetch to absorb filtering
        .collect(java.util.stream.Collectors.toList());

if (top.isEmpty()) return java.util.Collections.emptyList();

Set<String> archivedIds = videoRepository.findByYoutubeIds(
        top.stream().map(SearchableStream::getStreamId).collect(java.util.stream.Collectors.toList())
).stream()
        .filter(v -> v.getValidationStatus() == ValidationStatus.ARCHIVED
                || v.getValidationStatus() == ValidationStatus.UNAVAILABLE
                || (v.getStatus() != null && !"APPROVED".equals(v.getStatus())))
        .map(Video::getYoutubeId)
        .collect(java.util.stream.Collectors.toSet());

return top.stream()
        .filter(s -> !archivedIds.contains(s.getStreamId()))
        .limit(limit)
        .map(this::streamToDto)
        .collect(java.util.stream.Collectors.toList());
```

Add imports if needed: `java.util.Set`, `com.albunyaan.tube.model.Video`, `com.albunyaan.tube.model.ValidationStatus`.

**Note**: Streams whose `streamId` has *no* corresponding `Video` row (i.e., it was indexed via a channel/playlist but never individually approved) are intentionally NOT filtered here — they're protected by the channel/playlist source-key cleanup in Tasks 4–5.

- [ ] **Step 4: Run test, expect pass**

Run: `cd backend && ./gradlew test --tests PublicContentServiceTest.searchStreams_excludesArchivedVideos`
Expected: PASS.

- [ ] **Step 5: Run full search test class to verify no regressions**

Run: `cd backend && ./gradlew test --tests PublicContentServiceTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java backend/src/main/java/com/albunyaan/tube/repository/VideoRepository.java backend/src/test/java/com/albunyaan/tube/service/PublicContentServiceTest.java
git commit -m "[FIX]: Filter archived videos out of searchStreams() index"
```

---

## Task 4: Backend — Wire archive cleanup in `ContentValidationService` (TDD)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/ContentValidationService.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/ContentValidationServiceTest.java`

**Why:** When the auto-validator flips `validationStatus = ARCHIVED`, the search index must be purged of that source. Channels/playlists use `removeSource`; individual videos use the new `markStreamArchived`.

- [ ] **Step 1: Write three failing tests**

Append to `ContentValidationServiceTest.java`:

```java
@Test
void validateChannels_archivedChannel_callsRemoveSource() throws Exception {
    Channel ch = channelMissingFromYouTube("UCabc", "Channel A");
    when(channelRepository.findValidationCandidates(anyInt())).thenReturn(List.of(ch));
    stubYouTubeChannelMissing("UCabc");

    contentValidationService.validateChannels(10, runId);

    verify(streamIndexService).removeSource("CHANNEL", "UCabc");
}

@Test
void validatePlaylists_archivedPlaylist_callsRemoveSource() throws Exception {
    Playlist pl = playlistMissingFromYouTube("PLxyz", "Playlist A");
    when(playlistRepository.findValidationCandidates(anyInt())).thenReturn(List.of(pl));
    stubYouTubePlaylistMissing("PLxyz");

    contentValidationService.validatePlaylists(10, runId);

    verify(streamIndexService).removeSource("PLAYLIST", "PLxyz");
}

@Test
void validateVideos_archivedVideo_callsMarkStreamArchived() throws Exception {
    Video v = videoMissingFromYouTube("dQw4w9WgXcQ", "Video A");
    when(videoRepository.findValidationCandidates(anyInt())).thenReturn(List.of(v));
    stubYouTubeVideoMissing("dQw4w9WgXcQ");

    contentValidationService.validateVideos(10, runId);

    verify(streamIndexService).markStreamArchived("dQw4w9WgXcQ");
}
```

(Reuse the existing test class's setup: it should already wire `streamIndexService` as a mock. If not, add a `@Mock private StreamIndexService streamIndexService;` field and inject it.)

- [ ] **Step 2: Run tests, expect failure**

Run: `cd backend && ./gradlew test --tests ContentValidationServiceTest`
Expected: FAIL on the three new tests — verify never matches.

- [ ] **Step 3: Wire the cleanup calls**

In `ContentValidationService.java`:

**3a — channel branch (~line 453)**:
```java
// existing line:
channel.setValidationStatus(ValidationStatus.ARCHIVED);
// add directly after:
streamIndexService.removeSource("CHANNEL", channel.getYoutubeId());
```

**3b — playlist branch (~line 566)**:
```java
playlist.setValidationStatus(ValidationStatus.ARCHIVED);
streamIndexService.removeSource("PLAYLIST", playlist.getYoutubeId());
```

**3c — video branch (~line 679)**:
```java
video.setValidationStatus(ValidationStatus.ARCHIVED);
streamIndexService.markStreamArchived(video.getYoutubeId());
```

Inject `StreamIndexService` into the constructor if not already present:
```java
private final StreamIndexService streamIndexService;

public ContentValidationService(... existing args ..., StreamIndexService streamIndexService) {
    ...
    this.streamIndexService = streamIndexService;
}
```

- [ ] **Step 4: Run tests, expect pass**

Run: `cd backend && ./gradlew test --tests ContentValidationServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/ContentValidationService.java backend/src/test/java/com/albunyaan/tube/service/ContentValidationServiceTest.java
git commit -m "[FIX]: Purge stream index when validation archives content"
```

---

## Task 5: Backend — Wire archive cleanup in `ContentReportService` (TDD)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/ContentReportService.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/ContentReportServiceTest.java`

- [ ] **Step 1: Write three failing tests**

Append to `ContentReportServiceTest.java`:

```java
@Test
void archiveReportedContent_video_callsMarkStreamArchived() throws Exception {
    Video v = new Video();
    v.setId("vid-1");
    v.setYoutubeId("ytv-1");
    v.setStatus("APPROVED");
    when(videoRepository.findById("vid-1")).thenReturn(Optional.of(v));

    contentReportService.processReportApproval(reportFor(ReportTargetType.VIDEO, "vid-1"));

    verify(streamIndexService).markStreamArchived("ytv-1");
}

@Test
void archiveReportedContent_channel_callsRemoveSource() throws Exception {
    Channel ch = new Channel();
    ch.setId("ch-1");
    ch.setYoutubeId("UCabc");
    when(channelRepository.findById("ch-1")).thenReturn(Optional.of(ch));

    contentReportService.processReportApproval(reportFor(ReportTargetType.CHANNEL, "ch-1"));

    verify(streamIndexService).removeSource("CHANNEL", "UCabc");
}

@Test
void archiveReportedContent_playlist_callsRemoveSource() throws Exception {
    Playlist pl = new Playlist();
    pl.setId("pl-1");
    pl.setYoutubeId("PLxyz");
    when(playlistRepository.findById("pl-1")).thenReturn(Optional.of(pl));

    contentReportService.processReportApproval(reportFor(ReportTargetType.PLAYLIST, "pl-1"));

    verify(streamIndexService).removeSource("PLAYLIST", "PLxyz");
}
```

Adjust `processReportApproval` / `reportFor` to whatever the existing test class uses to drive an "archive this report" code path.

- [ ] **Step 2: Run tests, expect failure**

Run: `cd backend && ./gradlew test --tests ContentReportServiceTest`
Expected: 3 failures — verify never matches.

- [ ] **Step 3: Wire the cleanup calls**

In `ContentReportService.java`, locate `archiveReportedContent()` (line 277). For each branch:

**3a — video branch (~line 284)**:
```java
opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
videoRepository.save(opt.get());
streamIndexService.markStreamArchived(opt.get().getYoutubeId());
archived = true;
```

**3b — channel branch (~line 294)**:
```java
opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
channelRepository.save(opt.get());
streamIndexService.removeSource("CHANNEL", opt.get().getYoutubeId());
archived = true;
```

**3c — playlist branch (~line 304)**:
```java
opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
playlistRepository.save(opt.get());
streamIndexService.removeSource("PLAYLIST", opt.get().getYoutubeId());
archived = true;
```

Inject `StreamIndexService` into the constructor if not already present.

- [ ] **Step 4: Run tests, expect pass**

Run: `cd backend && ./gradlew test --tests ContentReportServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/ContentReportService.java backend/src/test/java/com/albunyaan/tube/service/ContentReportServiceTest.java
git commit -m "[FIX]: Purge stream index when reports archive content"
```

---

## Task 6: Backend — Archive guards in `DownloadService` (TDD)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/DownloadService.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/DownloadServiceTest.java`

**Why:** `checkDownloadPolicy` only checks `status`; `getDownloadManifest` checks neither `status` nor `validationStatus`. An archived video with a still-valid token can be downloaded today.

- [ ] **Step 1: Write four failing tests**

Append to `DownloadServiceTest.java`:

```java
@Test
void checkDownloadPolicy_archivedVideo_denied() throws Exception {
    Video v = new Video();
    v.setStatus("APPROVED");
    v.setValidationStatus(ValidationStatus.ARCHIVED);
    when(videoRepository.findByYoutubeId("ytv-1")).thenReturn(Optional.of(v));

    DownloadPolicyDto policy = downloadService.checkDownloadPolicy("ytv-1");

    assertFalse(policy.isAllowed());
    assertTrue(policy.getReason().toLowerCase().contains("not available")
            || policy.getReason().toLowerCase().contains("archived"));
}

@Test
void checkDownloadPolicy_unavailableVideo_denied() throws Exception {
    Video v = new Video();
    v.setStatus("APPROVED");
    v.setValidationStatus(ValidationStatus.UNAVAILABLE);
    when(videoRepository.findByYoutubeId("ytv-1")).thenReturn(Optional.of(v));

    DownloadPolicyDto policy = downloadService.checkDownloadPolicy("ytv-1");

    assertFalse(policy.isAllowed());
}

@Test
void getDownloadManifest_archivedVideo_throwsResourceNotFound() throws Exception {
    when(tokenService.validateToken("tok", "ytv-1")).thenReturn(true);
    Video v = new Video();
    v.setStatus("APPROVED");
    v.setValidationStatus(ValidationStatus.ARCHIVED);
    when(videoRepository.findByYoutubeId("ytv-1")).thenReturn(Optional.of(v));

    assertThrows(ResourceNotFoundException.class,
            () -> downloadService.getDownloadManifest("ytv-1", "tok", false));
}

@Test
void getDownloadManifest_rejectedVideo_throwsResourceNotFound() throws Exception {
    when(tokenService.validateToken("tok", "ytv-1")).thenReturn(true);
    Video v = new Video();
    v.setStatus("REJECTED");
    v.setValidationStatus(ValidationStatus.VALID);
    when(videoRepository.findByYoutubeId("ytv-1")).thenReturn(Optional.of(v));

    assertThrows(ResourceNotFoundException.class,
            () -> downloadService.getDownloadManifest("ytv-1", "tok", false));
}
```

Add imports: `com.albunyaan.tube.exception.ResourceNotFoundException`, `com.albunyaan.tube.model.ValidationStatus`.

- [ ] **Step 2: Run tests, expect failure**

Run: `cd backend && ./gradlew test --tests DownloadServiceTest`
Expected: 4 failures.

- [ ] **Step 3: Add archive guards to `checkDownloadPolicy()`**

In `DownloadService.java`, modify `checkDownloadPolicy()` (line 47):

```java
public DownloadPolicyDto checkDownloadPolicy(String videoId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
    Video video = videoRepository.findByYoutubeId(videoId).orElse(null);
    if (video == null) {
        return DownloadPolicyDto.denied("Video not found in registry");
    }
    if (!"APPROVED".equals(video.getStatus())) {
        return DownloadPolicyDto.denied("Video not approved for viewing");
    }
    if (video.getValidationStatus() == ValidationStatus.ARCHIVED
            || video.getValidationStatus() == ValidationStatus.UNAVAILABLE) {
        return DownloadPolicyDto.denied("Video no longer available");
    }
    return DownloadPolicyDto.allowedWithEula();
}
```

- [ ] **Step 4: Add archive + status guards to `getDownloadManifest()`**

In the same file, modify `getDownloadManifest()` (line 73). Right after the `if (video == null)` check, add:

```java
if (!"APPROVED".equals(video.getStatus())) {
    throw new ResourceNotFoundException("Video", videoId);
}
if (video.getValidationStatus() == ValidationStatus.ARCHIVED
        || video.getValidationStatus() == ValidationStatus.UNAVAILABLE) {
    throw new ResourceNotFoundException("Video", videoId);
}
```

Add imports if missing: `com.albunyaan.tube.model.ValidationStatus`, `com.albunyaan.tube.exception.ResourceNotFoundException`.

- [ ] **Step 5: Run tests, expect pass**

Run: `cd backend && ./gradlew test --tests DownloadServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/DownloadService.java backend/src/test/java/com/albunyaan/tube/service/DownloadServiceTest.java
git commit -m "[FIX]: Block downloads of archived/unavailable videos"
```

---

## Task 7: Android — Add availability endpoints to `ContentApi`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/source/api/ContentApi.kt`

- [ ] **Step 1: Add three HEAD endpoints**

Open `ContentApi.kt`. Replace the existing imports block to include `retrofit2.Response`, `retrofit2.http.HEAD`, `retrofit2.http.Path`. Add these three method declarations directly before the closing brace of the `interface ContentApi` body:

```kotlin
@HEAD("api/v1/channels/{id}")
suspend fun checkChannelAvailable(@Path("id") id: String): retrofit2.Response<Unit>

@HEAD("api/v1/playlists/{id}")
suspend fun checkPlaylistAvailable(@Path("id") id: String): retrofit2.Response<Unit>

@HEAD("api/v1/videos/{id}")
suspend fun checkVideoAvailable(@Path("id") id: String): retrofit2.Response<Unit>
```

Add `import retrofit2.http.HEAD` and `import retrofit2.http.Path` at the top of the file. Spring Boot serves HEAD as GET-without-body, so no backend change is needed.

- [ ] **Step 2: Build to verify it compiles**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/source/api/ContentApi.kt
git commit -m "[FIX]: Add HEAD availability endpoints to ContentApi"
```

---

## Task 8: Android — `ContentService.verifyAvailable(type, id)` + impls (TDD)

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/source/ContentService.kt` — add interface method
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/source/RetrofitContentService.kt` — real impl
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/source/FakeContentService.kt` — stub impl
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/source/FallbackContentService.kt` — passthrough impl
- Test: `android/app/src/test/java/com/albunyaan/tube/data/source/RetrofitContentServiceTest.kt` (extend or create)

- [ ] **Step 1: Add the type enum + interface method**

In `ContentService.kt`, add (above the interface):

```kotlin
enum class AvailabilityCheckType { CHANNEL, PLAYLIST, VIDEO }
```

Add to the interface body (alongside the existing methods):

```kotlin
/**
 * Returns true if the content is currently available (not archived, rejected,
 * or otherwise hidden). Returns false on a 404 from the backend, which is the
 * sentinel the public detail endpoints use to signal archive/unavailable state.
 * Throws on transport errors so the caller can decide whether to fail-open
 * (e.g., offline scenarios) or fail-closed.
 */
suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean
```

- [ ] **Step 2: Write failing tests**

In `RetrofitContentServiceTest.kt` (create if absent), using the existing project test conventions (MockWebServer or direct mock):

```kotlin
@Test
fun verifyAvailable_video_returns_true_when_backend_returns_200() = runTest {
    val api = mockk<ContentApi>()
    coEvery { api.checkVideoAvailable("ytv-1") } returns Response.success(Unit)
    val service = RetrofitContentService(api)

    val result = service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1")

    assertTrue(result)
}

@Test
fun verifyAvailable_video_returns_false_when_backend_returns_404() = runTest {
    val api = mockk<ContentApi>()
    coEvery { api.checkVideoAvailable("ytv-1") } returns Response.error(
        404, "".toResponseBody(null)
    )
    val service = RetrofitContentService(api)

    val result = service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1")

    assertFalse(result)
}

@Test
fun verifyAvailable_channel_uses_channel_endpoint() = runTest {
    val api = mockk<ContentApi>()
    coEvery { api.checkChannelAvailable("UCabc") } returns Response.success(Unit)
    coEvery { api.checkVideoAvailable(any()) } throws AssertionError("wrong endpoint")
    val service = RetrofitContentService(api)

    assertTrue(service.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
}
```

Imports: `io.mockk.coEvery`, `io.mockk.mockk`, `kotlinx.coroutines.test.runTest`, `okhttp3.ResponseBody.Companion.toResponseBody`, `retrofit2.Response`.

- [ ] **Step 3: Run tests, expect failure**

Run: `cd android && ./gradlew test --tests RetrofitContentServiceTest`
Expected: compile failure (`verifyAvailable` not defined).

- [ ] **Step 4: Implement in `RetrofitContentService`**

Add to `RetrofitContentService.kt`:

```kotlin
override suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean {
    val response = when (type) {
        AvailabilityCheckType.CHANNEL -> api.checkChannelAvailable(youtubeId)
        AvailabilityCheckType.PLAYLIST -> api.checkPlaylistAvailable(youtubeId)
        AvailabilityCheckType.VIDEO -> api.checkVideoAvailable(youtubeId)
    }
    return when {
        response.isSuccessful -> true
        response.code() == 404 -> false
        else -> throw retrofit2.HttpException(response)
    }
}
```

- [ ] **Step 5: Implement in `FakeContentService`**

Add:

```kotlin
override suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean = true
```

(Fake never simulates archived content; tests that need that should mock `ContentService` directly.)

- [ ] **Step 6: Implement in `FallbackContentService`**

Pass-through to the wrapped real service:

```kotlin
override suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean =
    primary.verifyAvailable(type, youtubeId)
```

(Adjust `primary` to whatever the existing field name is.)

- [ ] **Step 7: Run tests, expect pass**

Run: `cd android && ./gradlew test --tests RetrofitContentServiceTest`
Expected: PASS.

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/source/ContentService.kt android/app/src/main/java/com/albunyaan/tube/data/source/RetrofitContentService.kt android/app/src/main/java/com/albunyaan/tube/data/source/FakeContentService.kt android/app/src/main/java/com/albunyaan/tube/data/source/FallbackContentService.kt android/app/src/test/java/com/albunyaan/tube/data/source/RetrofitContentServiceTest.kt
git commit -m "[FIX]: Add ContentService.verifyAvailable for archive checks"
```

---

## Task 9: Android — String resources for "Content not available" (en, ar, nl)

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-ar/strings.xml`
- Modify: `android/app/src/main/res/values-nl/strings.xml`

- [ ] **Step 1: Add English strings**

Append inside `<resources>` of `values/strings.xml`:

```xml
<string name="content_unavailable_title">Content not available</string>
<string name="content_unavailable_message">This content is no longer available. It may have been removed by an admin or by YouTube.</string>
```

- [ ] **Step 2: Add Arabic strings**

Append inside `<resources>` of `values-ar/strings.xml`:

```xml
<string name="content_unavailable_title">المحتوى غير متاح</string>
<string name="content_unavailable_message">هذا المحتوى لم يعد متاحًا. ربما تمت إزالته بواسطة المسؤول أو بواسطة يوتيوب.</string>
```

- [ ] **Step 3: Add Dutch strings**

Append inside `<resources>` of `values-nl/strings.xml`:

```xml
<string name="content_unavailable_title">Inhoud niet beschikbaar</string>
<string name="content_unavailable_message">Deze inhoud is niet meer beschikbaar. Mogelijk is deze verwijderd door een beheerder of door YouTube.</string>
```

- [ ] **Step 4: Build to verify resource references**

Run: `cd android && ./gradlew compileDebugResources`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/values/strings.xml android/app/src/main/res/values-ar/strings.xml android/app/src/main/res/values-nl/strings.xml
git commit -m "[FIX]: Add 'Content not available' strings (en/ar/nl)"
```

---

## Task 10: Android — `ChannelDetailViewModel` pre-flight availability check (TDD)

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailViewModel.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailFragment.kt` — render new error state
- Test: existing `ChannelDetailViewModelTest.kt` (extend or create)

**Why:** This single change covers BOTH the deep-link route and the in-app-tap route — both converge on `ChannelDetailFragment` → `ChannelDetailViewModel.loadHeader()`.

- [ ] **Step 1: Inspect the ViewModel's current header state**

Run: `grep -n "HeaderState\|loadHeader\|sealed class\|ChannelDetailHeader" android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailViewModel.kt`

Note the names of the state class, the loading state, and the error state. The new state below uses placeholder names — replace them with the project's actual state-class hierarchy when you wire this in.

- [ ] **Step 2: Add the new error variant**

In whichever file declares the header state (likely `ChannelDetailViewModel.kt` or a sibling state file), add:

```kotlin
data object ContentUnavailable : ChannelHeaderState  // sealed-class member
```

(Use whatever the actual sealed type is. `data object` requires Kotlin 1.9+; if older, use `object`.)

- [ ] **Step 3: Write failing test**

Append to `ChannelDetailViewModelTest.kt`:

```kotlin
@Test
fun loadHeader_archivedChannel_emitsContentUnavailable() = runTest {
    coEvery { contentService.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc") } returns false
    val vm = ChannelDetailViewModel(
        channelId = "UCabc",
        repository = repository,
        contentService = contentService,
        // ... other deps
    )

    vm.loadHeader()
    advanceUntilIdle()

    assertEquals(ChannelHeaderState.ContentUnavailable, vm.headerState.value)
    coVerify(exactly = 0) { repository.getChannelHeader(any(), any()) }
}
```

(Match the constructor and observable property names of the actual ViewModel.)

- [ ] **Step 4: Run test, expect failure**

Run: `cd android && ./gradlew test --tests ChannelDetailViewModelTest.loadHeader_archivedChannel_emitsContentUnavailable`
Expected: FAIL — verifyAvailable not called yet.

- [ ] **Step 5: Modify `loadHeader()` to gate on availability**

In `ChannelDetailViewModel.kt`, modify `loadHeader()` (~line 125):

```kotlin
fun loadHeader(forceRefresh: Boolean = false) {
    viewModelScope.launch {
        try {
            // Availability gate — runs before any NewPipe work.
            val available = try {
                contentService.verifyAvailable(AvailabilityCheckType.CHANNEL, channelId)
            } catch (e: Exception) {
                // Transport error — fail open (user might be offline).
                Log.w(TAG, "Availability check failed; proceeding with NewPipe", e)
                true
            }
            if (!available) {
                _headerState.value = ChannelHeaderState.ContentUnavailable
                return@launch
            }

            // Existing NewPipe load (unchanged below this line).
            Log.d(TAG, "Loading header for channel: $channelId")
            val header = repository.getChannelHeader(channelId, forceRefresh)
            // ... rest unchanged
```

Inject `ContentService` into the ViewModel via `@Assisted` or constructor (whatever pattern the existing constructor uses for dependencies — `repository` is already there).

- [ ] **Step 6: Add `loadInitial()` short-circuit**

If `loadInitial(tab, forceRefresh)` (~line 161) can be invoked independently of `loadHeader()`, also gate it:

```kotlin
fun loadInitial(tab: ChannelTab, forceRefresh: Boolean = false) {
    if (_headerState.value == ChannelHeaderState.ContentUnavailable) return
    // ... existing body
```

- [ ] **Step 7: Render the unavailable state in `ChannelDetailFragment`**

In `ChannelDetailFragment.kt`, where `headerState` is observed, add a branch:

```kotlin
is ChannelHeaderState.ContentUnavailable -> {
    binding.contentLoadingGroup.isVisible = false
    binding.contentBody.isVisible = false
    binding.errorGroup.isVisible = true
    binding.errorTitle.text = getString(R.string.content_unavailable_title)
    binding.errorMessage.text = getString(R.string.content_unavailable_message)
}
```

(Adapt to the actual view IDs in this fragment — find them via `grep -n "errorGroup\|loadingGroup\|errorTitle" ChannelDetailFragment.kt`.)

- [ ] **Step 8: Run test, expect pass**

Run: `cd android && ./gradlew test --tests ChannelDetailViewModelTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailViewModel.kt android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailFragment.kt android/app/src/test/java/com/albunyaan/tube/ui/detail/ChannelDetailViewModelTest.kt
git commit -m "[FIX]: Gate channel detail load on backend availability check"
```

---

## Task 11: Android — `PlaylistDetailViewModel` pre-flight availability check (TDD)

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailViewModel.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailFragment.kt`
- Test: corresponding test class

Repeat the exact pattern from Task 10 with these substitutions:
- `CHANNEL` → `PLAYLIST` in the `verifyAvailable` call
- `channelId` → `playlistId`
- `ChannelHeaderState` → whatever the playlist state hierarchy is (find with `grep -n "sealed" PlaylistDetailViewModel.kt`)
- `ChannelDetailViewModelTest` → `PlaylistDetailViewModelTest`

- [ ] **Step 1: Inspect playlist header state**

Run: `grep -n "HeaderState\|loadHeader\|sealed class" android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailViewModel.kt`

- [ ] **Step 2: Add `ContentUnavailable` state**

- [ ] **Step 3: Write failing test (analogous to Task 10 step 3, with playlist substitutions)**

- [ ] **Step 4: Run test, expect failure**

- [ ] **Step 5: Modify `loadHeader()` (~line 93) and `loadInitial()` (~line 122) to gate on `verifyAvailable(AvailabilityCheckType.PLAYLIST, playlistId)`**

- [ ] **Step 6: Render the unavailable state in `PlaylistDetailFragment`**

- [ ] **Step 7: Run test, expect pass**

Run: `cd android && ./gradlew test --tests PlaylistDetailViewModelTest`

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailViewModel.kt android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailFragment.kt android/app/src/test/java/com/albunyaan/tube/ui/detail/PlaylistDetailViewModelTest.kt
git commit -m "[FIX]: Gate playlist detail load on backend availability check"
```

---

## Task 12: Android — Player pre-flight availability check (TDD)

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt` OR `PlayerViewModel.kt` (whichever owns the initial-load gate)
- Test: corresponding test class

**Inspection first:** `grep -n "fun load\|init {\|prepareStream\|loadVideo" android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerViewModel.kt` to find the entry method for stream resolution. Add the gate at the top of that method.

- [ ] **Step 1: Identify the player's initial-load entry point**

The PlayerFragment reads `videoId` from arguments at line 381. The first NewPipe resolve call happens shortly after. Trace down to the ViewModel method (or fragment helper) that issues the resolve. That's where the gate goes.

- [ ] **Step 2: Add the `ContentUnavailable` state to `PlayerState` (or equivalent sealed class)**

- [ ] **Step 3: Write failing test**

```kotlin
@Test
fun loadVideo_archivedVideo_emitsContentUnavailable() = runTest {
    coEvery { contentService.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1") } returns false
    val vm = playerViewModel()  // factory in test class

    vm.loadVideo("ytv-1")
    advanceUntilIdle()

    assertTrue(vm.state.value is PlayerState.ContentUnavailable)
    coVerify(exactly = 0) { /* NewPipe resolve call */ }
}
```

- [ ] **Step 4: Run test, expect failure**

- [ ] **Step 5: Implement the gate**

```kotlin
fun loadVideo(videoId: String) {
    viewModelScope.launch {
        val available = try {
            contentService.verifyAvailable(AvailabilityCheckType.VIDEO, videoId)
        } catch (e: Exception) {
            Log.w(TAG, "Availability check failed; proceeding with playback", e)
            true
        }
        if (!available) {
            _state.value = PlayerState.ContentUnavailable
            return@launch
        }
        // existing resolve logic unchanged
    }
}
```

- [ ] **Step 6: Render the unavailable state in `PlayerFragment`**

Hide the player view, show an error overlay with `R.string.content_unavailable_title` / `R.string.content_unavailable_message`. Provide a "back" affordance so the user can leave (deep-link arrivals have no backstack — pop to home or finish the activity).

- [ ] **Step 7: Run test, expect pass**

Run: `cd android && ./gradlew test --tests PlayerViewModelTest`

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/player/
git commit -m "[FIX]: Gate player on backend availability check"
```

---

## Task 13: Manual smoke test on a physical device

**Files:** none

This validates the integrated behavior end-to-end. Must run against a real Firestore (not the fake content service), with admin access to the dashboard.

- [ ] **Step 1: Build and install**

```bash
cd android && ./gradlew installDebug
```

- [ ] **Step 2: Pick three test items** in the running app: one channel, one playlist, one video. Note their YouTube IDs.

- [ ] **Step 3: Open one of each from the in-app list to confirm the baseline works** (loads normally, can play). If the app caches the loaded list, leave the list visible — do not pull to refresh.

- [ ] **Step 4: In the admin dashboard, archive each of the three items** (via the reports flow, since that's the user-facing manual archive path: approve a content report against the item).

- [ ] **Step 5: Without refreshing the in-app list, tap each archived item from the list. Expected:**
  - Channel: detail screen shows "Content not available" — does NOT load the YouTube page.
  - Playlist: same.
  - Video: player overlay shows "Content not available" — does NOT play.

- [ ] **Step 6: Search for the archived items by name. Expected:**
  - Channel: not in results.
  - Playlist: not in results.
  - Video: not in results (specifically: `searchStreams` no longer surfaces it).

- [ ] **Step 7: Test deep links from outside the app:**
  - Send yourself an `albunyaantube://video/{archivedVideoId}` link via Telegram/SMS, tap it. Expected: app opens to "Content not available".
  - Repeat for `albunyaantube://channel/{id}` and `albunyaantube://playlist/{id}`.

- [ ] **Step 8: Test downloads:**
  - With the app showing the archived video's player error, attempt to trigger a download via any UI affordance that bypasses the player (long-press in list, queued offline download, etc., if applicable). Expected: download policy denies, manifest call returns 404.
  - If a download token was issued before archive, attempt to use it: manifest endpoint must 404.

- [ ] **Step 9: Test recovery — restore each item via admin "Restore" action. Expected:**
  - Detail screens load again immediately (deep link / in-app tap → 200, content loads).
  - Search: video/channel/playlist itself reappears in results immediately (Video documents) or on next user visit (channel/playlist stream-index entries — matches the existing reject→re-approve precedent).

- [ ] **Step 10: Commit a smoke-test note** (optional — stash the device's logcat snippet in the PR description, no file artifact).

---

## Self-Review Checklist (run after writing the implementation)

- [ ] Spec coverage:
  - Fix 1 (search index cleanup): Tasks 1, 2, 3, 4, 5 ✅
  - Fix 2+3 merged (deep-link + in-app guard): Tasks 7, 8, 9, 10, 11, 12 ✅
  - Fix 4 (download archive guard): Task 6 ✅
  - Recovery flow: Validated by Task 13 step 9 ✅
- [ ] No TBDs/TODOs/placeholders in code blocks.
- [ ] Type/method names are consistent across tasks (`verifyAvailable`, `AvailabilityCheckType`, `markStreamArchived`, `markInvisible`, `ContentUnavailable`).
- [ ] Each task ends with a single commit using `[FIX]` prefix per project convention.
- [ ] Branch policy honored: all commits land on `develop`, never `main` (per memory `feedback_branching_policy.md`).
- [ ] After all tasks complete: trigger the project's mandatory review pipeline per memory `feedback_review_pipeline.md` before any merge.

---

## Out of scope (intentionally deferred)

- **Eager re-indexing on restore.** Following the existing reject→re-approve convention: search re-indexes lazily when a user next opens the channel/playlist. Closing the gap (search-empty-until-revisited) requires server-side YouTube fetching from `restoreContent()` and is its own ticket.
- **`restoreContent()` blind status flip bug.** Found during analysis: `restoreContent()` flips `validationStatus = VALID` without checking the `status` field. A `REJECTED + ARCHIVED` item still 404s correctly via the detail endpoints (because of `status != APPROVED`), so the leak fix isn't blocked. Worth its own follow-up ticket.
- **In-app list staleness window.** A user holding a stale in-memory list won't see archived items disappear until they refresh — but Tasks 10/11/12 ensure those items can no longer be opened. Closing the visual gap (foreground refresh / push) is a separate UX ticket.
