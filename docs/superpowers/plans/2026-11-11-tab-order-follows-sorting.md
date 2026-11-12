# Tab Order Follows Content Sorting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/v1/content?type=CHANNELS|PLAYLISTS|VIDEOS`, sent with no category, no `q` and no video filters, returns each type in the dashboard's Content Sorting order. Top-level categories go by `displayOrder`, items inside each category by `category_content_order.position`, and the remaining approved items follow A→Z. A dashboard change shows on the next request.

**Architecture:** This is a backend-only change, and the endpoint stays the same. One walk over the top-level categories calls the existing `PublicContentService.getCategoryContentItems` for each category, the same call Home's sections make. Its output goes through one cross-category dedupe, and the leftover live approved items follow as an A→Z tail. The result is split by DTO type and kept in a `PublicContentService` field. A new `ContentChanged` event clears that field. `PublicContentCacheService.evictPublicContentCaches()` publishes the event, while live-status evictions (a new `evictForLiveChange()`) don't, so the order is not rebuilt on every live change. Pages are offset slices of the list and use the existing `encodeCursor`/`parseCursorOffset` cursor. The per-page `@Cacheable` on `getContent` is unchanged.

**Tech Stack:** Spring Boot 3 / Java 17, Spring application events, Firestore repositories, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-11-11-sorting-search-playback-design.md`, section "A. Channels / Playlists / Videos tabs follow Content Sorting".

## Global Constraints

- Backend tests run with `JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home` (a bare `~/.local/jdk` FAILS).
- "Live approved" means `status == "APPROVED"`, PUBLIC visibility and not archived (`ValidationStatus.holds` false). This is exactly `isApproved(x) && isAvailable(x)` in `PublicContentService` (`:1275-1321`). Do not write a new predicate.
- Each tab lists only its own type: CHANNELS → `"CHANNEL"` DTOs, PLAYLISTS → `"PLAYLIST"`, VIDEOS → `"VIDEO"`.
- Order: top-level categories in `displayOrder` (ties broken by id, as Home does). Inside each category the order is whatever `getCategoryContentItems` returns: order rows, then unordered items in `SortOrderService`'s default order. The tail is every remaining live approved item, A→Z by title.
- An item in several categories appears once, at its first position in the walk.
- Sending any of `category`, `q` (≥2 chars, handled by `searchContent`), `length`, `date` or `sort` (including an explicit `NEWEST`) keeps today's code path.
- The endpoint, the response shape and the cursor encoding (`encodeCursor`, `parseCursorOffset`) stay the same. Do not add a new cursor scheme.
- Firestore reads happen once per rebuild, never per page request. A rebuild happens only on the first tab request after a content change (`ContentChanged`), never after a live-status change.
- Hilt DI, UI and layout rules are untouched: this plan makes no app UI change.
- No new `.md` files beyond this plan. Commits use `[PREFIX]: Description` (≤50 chars) plus the attribution lines from the session reminder.
- Work on a short-lived branch `feature/A-tab-order`. Merge it to `main` and delete the branch and its worktree once done (owner rule).
- After the coding tasks, run `graphify update .` and the full 9-stage review pipeline (AGENTS.md "Mandatory Post-Coding Review Pipeline", Cubic last) before merging. Name any stage that can't run, and why.
- This Mac has no Android SDK, so Android unit tests can't run here. The substitutes are code reading plus CI on push, and each task that touches Android says so.
- Run iOS `xcodebuild`/`ios/scripts/test.sh` one at a time, never in parallel.

## Review Focus

1. **An app holds an old Firestore cursor while the deploy happens.** Expected: that next-page request returns an empty page with `nextCursor == null`, so the list ends cleanly. It must not repeat page 1, because duplicates would appear in the list. Pinned by `aCursorThatIsNotAnOffsetEndsTheList` (Task 1).
2. **An item whose only category was deleted, or that sits in an orphan subcategory.** Expected: it still shows, in the A→Z tail. It must not disappear from every tab. Pinned by the `O` fixture row (Task 1).
3. **The user picks "Newest", a length or a date on the Videos tab.** Expected: today's upload-date path or filter path, with curated order not applied. Pinned by `videoFiltersCategoriesAndExplicitSortKeepTheirPaths` (Task 1).
4. **A Firestore timeout during a rebuild.** Expected: the request fails, nothing is cached, and the next request rebuilds. A partial list must never be served for an hour. Pinned by `aFailedRebuildIsNotCached` (Task 2).
5. **Home and a tab disagree for the same category.** Expected: a tab's category segment equals Home's section items of that type, deduped. Pinned by `homeSectionsAndTabsAgreeOnCategoryOrder` (Task 1).

---

### Task 1: Curated order for the three tabs

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java`
  - `getContent` switch `:128-134`: dispatch.
  - `getHomeFeed` `:452-458` (children map) and `:499-504` (sort lambda): extract to helpers and call them.
  - `livePage` `:1424-1436`: rename to `pageOf` (one name now serves live and tab pages). Update its caller at `:125`.
- Create: `backend/src/test/java/com/albunyaan/tube/service/PublicContentServiceTabOrderTest.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/PublicContentServicePaginationTest.java`. Delete the five tests that pin the replaced no-category order: `getContent_channels_returnsNextCursor_whenHasMoreItems`, `getContent_channels_returnsNullCursor_whenLastPage`, `getContent_channels_withCursor_passesCursorToRepository`, `getContent_playlists_returnsNextCursor_whenHasMoreItems` and `getContent_videos_returnsNextCursor_whenHasMoreItems`.
- Modify: `backend/src/test/java/com/albunyaan/tube/service/PublicContentCacheKeyTest.java`
  - `Config.channelRepository()` `:51-56`: drop the `findApprovedBySubscribersDescWithCursor` stub.
  - `aOneCharacterQueryIsServedFromThePlainListCache` `:99-105`: it now verifies `channels` `times(1)).findByStatus("APPROVED")`.
  - `newestVideosPageHoldsOneVideo()` `:197-203`: becomes `tabOrderHoldsOneVideo()`, which stubs `videos.findByStatus("APPROVED")` → `List.of(video)` (the other mocks already return empty lists). Rename its 3 callers.

**Interfaces:**
- Consumes (existing, unchanged): `getCategoryContentItems(List<String>, int, int)` `:673`, `asyncSupply(CheckedSupplier<T>)` `:1474`, `encodeCursor(Object)` `:1420`, `parseCursorOffset(String)` `:1439`, `isApproved`/`isAvailable`/`toDto` overloads, and `findByStatus("APPROVED")` on `ChannelRepository:270`, `PlaylistRepository:247` and `VideoRepository:261`.
- Produces:
  - `private static final Comparator<Category> DISPLAY_ORDER`: `displayOrder` with null as `Integer.MAX_VALUE`, then `id`. This is the exact `getHomeFeed` lambda.
  - `private static Map<String, List<String>> childrenByParent(List<Category> all)`: parent id → child ids, in the same order as `getHomeFeed` today.
  - `private Map<String, List<ContentItemDto>> buildTabOrder() throws ExecutionException, InterruptedException, TimeoutException`: keys are `"CHANNEL"`, `"PLAYLIST"` and `"VIDEO"`. A type with no items still gets its key, holding an empty list.
  - `private CursorPageDto<ContentItemDto> tabOrderPage(String dtoType, String cursor, int limit) throws ExecutionException, InterruptedException, TimeoutException`
  - `private CursorPageDto<ContentItemDto> pageOf(List<ContentItemDto> all, String cursor, int limit)`: the renamed `livePage`, with its body unchanged.

- [ ] **Step 1: Write the failing test file `PublicContentServiceTabOrderTest`**

  Use `@ExtendWith(MockitoExtension.class)` and `@MockitoSettings(strictness = Strictness.LENIENT)`, because one shared catalog fixture is read differently by each test. Mock the 5 repositories plus `SearchIndex` and `LiveCategoryService`, and construct the service exactly as `PublicContentServicePaginationTest.setUp` does (`Runnable::run` executor).

  `@BeforeEach catalog()` builds the fixture below and stubs these calls. Helpers: `private static Category cat(String id, int displayOrder, String parentId)` (sets id, name = id, displayOrder, parentCategoryId), and `channel`/`playlist`/`video(String letter, String title, String... categoryIds)`. Each helper sets id, youtubeId, name or title and status `APPROVED`, and the table's notes are applied after it. Keep the category list in a field `List<Category> categories`.

  - Categories: `categoryRepository.findAll()` → `categories` = `[cat("c1",1,null), cat("c1a",0,"c1"), cat("c3",2,null), cat("c2",0,null)]`. The list is deliberately unsorted. `categoryRepository.findByParentId("c1")` → `[c1a]`.
  - For each type T ∈ {`ch` Channel, `pl` Playlist, `v` Video} and letter X, build a doc with id `T-X`, youtubeId `yt-T-X` (that is the DTO id) and name/title `T X`, except E/F/O. Status is `APPROVED` unless noted:

  | X | categoryIds | order row | note |
  |---|---|---|---|
  | C | `[c2, c1]` | c2, c1 | multi-category |
  | A | `[c2]` | c2 | |
  | G | `[c2]` | c2 | `ValidationStatus.ARCHIVED` (excluded) |
  | P | `[c2]` | c2 | status `PENDING` (excluded) |
  | I | `[c2]` | none | unordered, fallback rank 1 |
  | H | `[c2]` | none | unordered, fallback rank 2 |
  | B | `[c1]` | c1 | |
  | D | `[c1a]` | none | subcategory, unordered |
  | F | `[]` | none | title `"alpha"` |
  | O | `["gone"]` (deleted category) | none | title `"Omega"` |
  | E | `[]` | none | title `"Zeta"` |

  - `orderRepository.findByCategoryIdsOrderByPosition(List.of("c2"))` → rows C, A, G, P, with each letter's three types interleaved (`ch`, `pl`, `v`). `orderRepository.findByCategoryIdOrderByPosition("c1")` → rows B, C, interleaved the same way.
  - `findAllByIds(anyList())` on each repo → that type's full id→doc map.
  - `findByCategoryIds(eq(List.of("c2")), anyInt())` → `[I, A, G, H, C]`, and `findByCategoryIds(eq(List.of("c1","c1a")), anyInt())` → `[D, B, C]`, on each repo.
  - `findByStatus("APPROVED")` on each repo → `[A, B, C, D, E, F, G, H, I, O]` of that type.

  Tests:

```java
@Test void channelsTabFollowsCategoryOrderThenAlphabeticalTail() throws Exception {
    assertEquals(List.of("yt-ch-C","yt-ch-A","yt-ch-I","yt-ch-H","yt-ch-B","yt-ch-D","yt-ch-F","yt-ch-O","yt-ch-E"),
            walk("CHANNELS", "CHANNEL"));
}
@Test void playlistsTabFollowsCategoryOrderThenAlphabeticalTail() throws Exception {
    assertEquals(List.of("yt-pl-C","yt-pl-A","yt-pl-I","yt-pl-H","yt-pl-B","yt-pl-D","yt-pl-F","yt-pl-O","yt-pl-E"),
            walk("PLAYLISTS", "PLAYLIST"));
}
@Test void videosTabFollowsCategoryOrderThenAlphabeticalTail() throws Exception {
    assertEquals(List.of("yt-v-C","yt-v-A","yt-v-I","yt-v-H","yt-v-B","yt-v-D","yt-v-F","yt-v-O","yt-v-E"),
            walk("VIDEOS", "VIDEO"));
}
@Test void homeSectionsAndTabsAgreeOnCategoryOrder() throws Exception {
    List<HomeCategoryDto> home = service.getHomeFeed(null, 5, 20, null).getData();
    assertEquals(List.of("c2", "c1"), home.stream().map(HomeCategoryDto::getId).toList()); // c3 is empty
    List<String> homeChannels = home.stream().flatMap(s -> s.getItems().stream())
            .filter(i -> "CHANNEL".equals(i.getType())).map(ContentItemDto::getId).distinct().toList();
    assertEquals(walk("CHANNELS", "CHANNEL").subList(0, 6), homeChannels);
}
@Test void aCursorThatIsNotAnOffsetEndsTheList() throws Exception {
    for (String old : List.of(CursorUtils.encodeFromDocumentId("ch-A"), b64("-5"), "%%%")) {
        CursorPageDto<ContentItemDto> page = service.getContent("CHANNELS", old, 4, null, null, null, null);
        assertTrue(page.getData().isEmpty(), old);
        assertNull(page.getPageInfo().getNextCursor(), old);
    }
}
@Test void videoFiltersCategoriesAndExplicitSortKeepTheirPaths() throws Exception {
    when(videoRepository.findApprovedByUploadedAtDescWithCursor(eq(4), isNull()))
            .thenReturn(new VideoRepository.PaginatedResult<>(List.of(), null, false));
    when(channelRepository.findApprovedByCategoryAndSubscribersDescWithCursor(eq("c2"), eq(4), isNull()))
            .thenReturn(new ChannelRepository.PaginatedResult<>(List.of(), null, false));
    service.getContent("VIDEOS", null, 4, null, null, null, "NEWEST");
    service.getContent("VIDEOS", null, 4, null, "SHORT", null, null);
    service.getContent("CHANNELS", null, 4, "c2", null, null, null);
    verify(videoRepository).findApprovedByUploadedAtDescWithCursor(4, null);
    verify(videoRepository).findAllByOrderByUploadedAtDesc(anyInt());
    verify(channelRepository).findApprovedByCategoryAndSubscribersDescWithCursor("c2", 4, null);
    verify(categoryRepository, never()).findAll(); // none of them walked the tab order
}

/** Every page of a tab at limit 4: each row is the tab's type; 9 rows = 3 pages, the last without a cursor. */
private List<String> walk(String tab, String dtoType) throws Exception {
    List<String> ids = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
        CursorPageDto<ContentItemDto> page = service.getContent(tab, cursor, 4, null, null, null, null);
        page.getData().forEach(d -> { assertEquals(dtoType, d.getType()); ids.add(d.getId()); });
        cursor = page.getPageInfo().getNextCursor();
        assertTrue(++pages <= 3, "runaway cursor");
    } while (cursor != null);
    assertEquals(3, pages);
    return ids;
}
private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes()); }
```

  Code points would order the tail `Omega, Zeta, alpha`. A collator orders it `alpha, Omega, Zeta`, and that is what the test pins.

- [ ] **Step 2: Run it to verify it fails**

  Run: `cd /Users/farouqabouumar/Development/albunyaantube/backend && JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home ./gradlew test --tests 'com.albunyaan.tube.service.PublicContentServiceTabOrderTest'`
  Expected: the three `*TabFollowsCategoryOrder*` tests and `homeSectionsAndTabsAgreeOnCategoryOrder` fail, because today's no-category path calls `find…DescWithCursor`, which returns null in these mocks.

- [ ] **Step 3: Extract `DISPLAY_ORDER` and `childrenByParent` and use them in `getHomeFeed`**

  This is behaviour-neutral. `PublicContentServiceHomeFeedTest` must stay green unchanged.

- [ ] **Step 4: Implement `buildTabOrder()`**

  Steps, in order:
  1. Call `categoryRepository.findAll()` and `childrenByParent` on the result. Keep the categories with `getParentCategoryId() == null` and sort them by `DISPLAY_ORDER`.
  2. For each top-level category, build `ids = [id] + children`. Launch `asyncSupply(() -> getCategoryContentItems(ids, Integer.MAX_VALUE, 0))` for all of them first, then `join()` in walk order. This is the same fan-out `getHomeFeed` uses. Any failure propagates, because a partial list must not be cached. Unlike Home, do not skip a failed category.
  3. Append each DTO to its type's list unless the key `dto.getType() + ":" + dto.getId()` is already in the shared `Set<String> seen` (the cross-category form of `seenContentKeys`).
  4. Tail, per type: take `findByStatus("APPROVED")`, filter `isApproved && isAvailable`, map with `toDto`, and drop anything already in `seen`. Sort by `Comparator.comparing(d -> d.getTitle() != null ? d.getTitle() : d.getName(), Comparator.nullsLast(Collator.getInstance(Locale.ROOT)))`, then by `ContentItemDto::getId`, and append. Channel DTOs fill only `name` (`ContentItemDto.java:63`), while playlists and videos fill `title`. The id tie-break keeps pages stable across rebuilds. Create the collator per build, because it is not thread-safe.
  5. Put `// ponytail: rebuilds the whole catalog per content change (reads ≈ categories + order rows + ~2-3× approved items); page the walk if that outgrows one rebuild per content change.` on the method.

  Live categories are walked like any other category, through their order rows. The tabs do not show a live category's live-stream expansion.

- [ ] **Step 5: Implement `tabOrderPage` and the dispatch**

  `tabOrderPage(dtoType, cursor, limit)`: if `parseCursorOffset(cursor) < 0`, return `new CursorPageDto<>(new ArrayList<>(), null)`. Otherwise return `pageOf(buildTabOrder().get(dtoType), cursor, limit)`. Task 2 keeps the result until the next content change.

  In the `getContent` switch:
  - `CHANNELS` with `allCategoryIds == null` → `tabOrderPage("CHANNEL", …)`.
  - `PLAYLISTS` with `allCategoryIds == null` → `tabOrderPage("PLAYLIST", …)`.
  - `VIDEOS` with `allCategoryIds == null` and `length`, `date` and `sort` all null or blank → `tabOrderPage("VIDEO", …)`.
  - Everything else falls through to the existing calls.

  Rename `livePage` → `pageOf`.

- [ ] **Step 6: Update the old tests named in Files**

  Delete the 5 `PaginationTest` methods and make the 3 `CacheKeyTest` edits listed under Files.

- [ ] **Step 7: Run the service tests**

  Run: `cd /Users/farouqabouumar/Development/albunyaantube/backend && JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home ./gradlew test --tests 'com.albunyaan.tube.service.*' --tests 'com.albunyaan.tube.search.SearchIndexTest' --tests 'com.albunyaan.tube.controller.PersonalDetailsAccessTest'`
  Expected: BUILD SUCCESSFUL. `PublicContentServiceTabOrderTest` passes 6/6, and `PublicContentServiceHomeFeedTest`, `PublicContentServiceLiveTest` and `PublicContentServiceVisibilityTest` pass unchanged.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java backend/src/test/java/com/albunyaan/tube/service/PublicContentServiceTabOrderTest.java backend/src/test/java/com/albunyaan/tube/service/PublicContentServicePaginationTest.java backend/src/test/java/com/albunyaan/tube/service/PublicContentCacheKeyTest.java
git commit -m "[FEAT]: Tabs follow Content Sorting order"
```

### Task 2: Build the tab order once per content change

The order is kept in a `PublicContentService` field, not in the `public-content` cache. `LiveChannelPoller` clears `public-content` on every live-status change. Live status does not change the tab order, so a rebuild there would cost 2-3× the catalog in reads for nothing. The field is cleared by a new event, `ContentChanged`. `evictPublicContentCaches()` publishes it, and the poller's new `evictForLiveChange()` does not. This adds no constructor parameter, no `CacheConfig` entry and no `CacheManager`.

**Which `evictPublicContentCaches()` call sites clear the order** (from `grep -rn "evictPublicContentCaches()" backend/src/main/java`, 2026-11-11):

| Clears the order | Call sites | Why |
|---|---|---|
| Yes | `SortOrderService:131,206,327,447,598,655` | category/content reorder, add/remove-from-category, order sync |
| Yes | `ApprovalController:216,264,308`; `ContentLibraryController:1327,1364,1405,1454` | approve, reject, request changes, bulk approve/reject/pending/delete |
| Yes | `ContentLibraryController:1552,1832` | bulk category assignment, bulk reorder |
| Yes | `RegistryController:304,353,373,580,624,644,957,1032,1052`; `BulkSubmissionService:431` | add, update and delete of channels/playlists/videos |
| Yes | `CategoryController:145,213,252` | category create, update, delete |
| Yes | `ContentValidationService:531,666,787,923,1234`; `ChannelValidationScheduler:241` (callers `:215,292`); `PlaylistValidationScheduler:284` (callers `:223,335`); `ContentReportService:383` | validation archive, thumbnail heal, restore, report take-down |
| Yes | `ContentLibraryController:1672,1683,1694,2192,2198`; `TagEnrichmentService:99` | keyword and tag edits. Cached rows carry `keywords`, names and thumbnails, so a metadata edit must clear them too. |
| Yes, harmless | `RegistryController:762,797`; `ContentReportService:481` | Exclusion edits don't change tab membership: tabs list approved docs, while exclusions hide videos inside a source. They happen at admin rate, so keeping one call is simpler than a third method. |
| **No** | `LiveChannelPoller:313,382` → switched to `evictForLiveChange()` | live-status changes |

Two paths don't call the hook at all, and neither needs to:
- `VideoValidationScheduler:252,343` clear `public-content` directly through `cacheManager`, with no event. The archives in that run already go through `ContentValidationService:787`.
- `ContentValidationService.deleteContent` (`:1118`) has no eviction. It deletes items from the Archived page, which are already hidden from the tabs.

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/PublicContentCacheService.java`. Add `public record ContentChanged() {}`. Have `evictPublicContentCaches()` also publish `new ContentChanged()`. Add `evictForLiveChange()`, which takes the same `@CacheEvict(value = {...}, allEntries = true)` and publishes only `new Evicted()`.
- Modify: `backend/src/main/java/com/albunyaan/tube/scheduler/LiveChannelPoller.java:313,382`: `caches.evictPublicContentCaches()` → `caches.evictForLiveChange()`.
- Modify: `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java`: the fields, listener and `tabOrder()` from Interfaces. `tabOrderPage` calls `tabOrder()` instead of `buildTabOrder()`.
- Modify: `backend/src/test/java/com/albunyaan/tube/scheduler/LiveChannelPollerTest.java`. Its 13 `caches).evictPublicContentCaches()` references (`:302,389,433,497,506,510,514,595,622,816,1224,1246,1265`) become `evictForLiveChange()`. The `Evicted()` constructions stay as they are.
- Test: `backend/src/test/java/com/albunyaan/tube/service/PublicContentCacheServiceTest.java`, `PublicContentServiceTabOrderTest.java`, `PublicContentCacheKeyTest.java`.

**Interfaces:**
- Consumes: `buildTabOrder()`, `tabOrderPage(...)` and `pageOf(...)` from Task 1.
- Produces:
  - `PublicContentCacheService.ContentChanged` (record, no components)
  - `public void evictForLiveChange()`
  - in `PublicContentService`:
    - `private volatile Map<String, List<ContentItemDto>> tabOrder`
    - `private final AtomicLong tabOrderGeneration`
    - `private final Object tabOrderLock`
    - `@EventListener public void onContentChanged(PublicContentCacheService.ContentChanged event)`: increments the generation, then sets `tabOrder = null`
    - `private Map<String, List<ContentItemDto>> tabOrder() throws ExecutionException, InterruptedException, TimeoutException`

  `tabOrder()` is the one algorithm the signature leaves open. It runs one build at a time, and it drops a build that a change overtook:

```java
Map<String, List<ContentItemDto>> order = tabOrder;
if (order != null) return order;
synchronized (tabOrderLock) {
    if (tabOrder != null) return tabOrder;
    long generation = tabOrderGeneration.get();
    order = buildTabOrder();
    if (tabOrderGeneration.get() == generation) tabOrder = order;
    return order;
}
```

  Put `// ponytail: no TTL; a write that skips evictPublicContentCaches() leaves the order stale until the next content change. Add a built-at check if such a path appears.` on the field.

- [ ] **Step 1: Write the failing tests**

  In `PublicContentServiceTabOrderTest`:

```java
@Test void tabOrderIsBuiltOncePerContentChangeNotPerPage() throws Exception {
    walk("CHANNELS", "CHANNEL"); walk("PLAYLISTS", "PLAYLIST"); walk("VIDEOS", "VIDEO");
    verify(categoryRepository, times(1)).findAll();
    verify(orderRepository, times(1)).findByCategoryIdOrderByPosition("c1");
    verify(channelRepository, times(1)).findByStatus("APPROVED");
    verify(playlistRepository, times(1)).findByStatus("APPROVED");
    verify(videoRepository, times(1)).findByStatus("APPROVED");
}
@Test void aContentChangeServesTheNewOrderOnTheNextRequest() throws Exception {
    assertEquals("yt-ch-C", first("CHANNELS"));
    when(categoryRepository.findAll()).thenReturn(List.of(cat("c1", 0, null), cat("c1a", 0, "c1"), cat("c2", 1, null), cat("c3", 2, null)));
    assertEquals("yt-ch-C", first("CHANNELS"), "kept until a content change");
    service.onContentChanged(new PublicContentCacheService.ContentChanged());
    assertEquals("yt-ch-B", first("CHANNELS"));
}
@Test void aChangeDuringARebuildIsNotKept() throws Exception {
    when(categoryRepository.findAll()).thenAnswer(inv -> {
        service.onContentChanged(new PublicContentCacheService.ContentChanged());
        return categories;
    }).thenReturn(categories);
    first("CHANNELS");
    first("CHANNELS");
    first("CHANNELS");
    verify(categoryRepository, times(2)).findAll(); // the overtaken build was served once, never stored
}
@Test void aFailedRebuildIsNotCached() throws Exception {
    when(categoryRepository.findAll()).thenThrow(new TimeoutException("firestore")).thenReturn(categories);
    assertThrows(TimeoutException.class, () -> service.getContent("CHANNELS", null, 4, null, null, null, null));
    assertEquals("yt-ch-C", first("CHANNELS"));
}
private String first(String tab) throws Exception {
    return service.getContent(tab, null, 4, null, null, null, null).getData().get(0).getId();
}
```

  In `PublicContentCacheServiceTest`:

```java
@Test void aContentEvictionAnnouncesAContentChange() {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    new PublicContentCacheService(events).evictPublicContentCaches();
    verify(events).publishEvent(any(PublicContentCacheService.ContentChanged.class));
}
@Test void aLiveEvictionAnnouncesItselfButNoContentChange() {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    new PublicContentCacheService(events).evictForLiveChange();
    verify(events).publishEvent(any(PublicContentCacheService.Evicted.class));
    verify(events, never()).publishEvent(any(PublicContentCacheService.ContentChanged.class));
}
```

  In `PublicContentCacheKeyTest`:
  - Add the bean `PublicContentCacheService cacheService(ApplicationEventPublisher events) { return new PublicContentCacheService(events); }` and autowire it. The context's own publisher delivers to `@EventListener`s.
  - In `freshCacheAndCounts()`, call `cacheService.evictPublicContentCaches()` before the existing clears. The service bean is shared by every test in the class, and this resets its field.

```java
@Test void aDashboardChangeShowsOnTheNextTabRequestButALiveChangeKeepsTheOrder() throws Exception {
    Channel one = new Channel("UCone"), two = new Channel("UCtwo");
    one.setId("ch1"); one.setStatus("APPROVED");
    two.setId("ch2"); two.setStatus("APPROVED");
    when(channels.findByCategoryIds(eq(List.of("c1")), anyInt())).thenReturn(List.of(one));
    when(channels.findByCategoryIds(eq(List.of("c2")), anyInt())).thenReturn(List.of(two));
    when(categories.findAll()).thenReturn(List.of(category("c1", 0), category("c2", 1)));
    assertEquals(List.of("UCone", "UCtwo"), ids(service.getContent("CHANNELS", null, 20, null, null, null, null)));

    when(categories.findAll()).thenReturn(List.of(category("c1", 1), category("c2", 0))); // the admin moved c2 up
    cacheService.evictForLiveChange();
    assertEquals(List.of("UCone", "UCtwo"), ids(service.getContent("CHANNELS", null, 20, null, null, null, null)));

    cacheService.evictPublicContentCaches();
    assertEquals(List.of("UCtwo", "UCone"), ids(service.getContent("CHANNELS", null, 20, null, null, null, null)));
}
```

  `ids(page)` maps `page.getData()` to `getId()`. Because this test goes through the Spring proxy, it proves the real hooks: the page cache clears on both calls, and the order clears only on the content one.

- [ ] **Step 2: Run them to verify they fail**

  Run: `cd /Users/farouqabouumar/Development/albunyaantube/backend && JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home ./gradlew test --tests 'com.albunyaan.tube.service.PublicContentServiceTabOrderTest' --tests 'com.albunyaan.tube.service.PublicContentCacheKeyTest' --tests 'com.albunyaan.tube.service.PublicContentCacheServiceTest'`
  Expected: compile errors (`ContentChanged`, `evictForLiveChange` and `onContentChanged` don't exist yet). Once those are stubbed, `tabOrderIsBuiltOncePerContentChangeNotPerPage` fails because `findAll` is called 9 times instead of 1.

- [ ] **Step 3: Implement**

  Make the `PublicContentCacheService` additions, the poller switch, the `LiveChannelPollerTest` reference updates, and the `PublicContentService` field, listener and `tabOrder()` from Interfaces.

- [ ] **Step 4: Run the backend suite**

  Run: `cd /Users/farouqabouumar/Development/albunyaantube/backend && JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home timeout 300 ./gradlew test`
  Expected: BUILD SUCCESSFUL. `PublicContentServiceTabOrderTest` passes 10/10, `PublicContentCacheServiceTest` 3/3, and `LiveChannelPollerTest` passes unchanged in count.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java backend/src/main/java/com/albunyaan/tube/service/PublicContentCacheService.java backend/src/main/java/com/albunyaan/tube/scheduler/LiveChannelPoller.java backend/src/test/java/com/albunyaan/tube
git commit -m "[PERF]: Rebuild tab order only on content change"
```

### Task 3: Delete the no-category cursor queries Task 1 orphaned

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/repository/ChannelRepository.java:642-698`: delete `findApprovedBySubscribersDescWithCursor` and its javadoc.
- Modify: `backend/src/main/java/com/albunyaan/tube/repository/PlaylistRepository.java:569-620`: delete `findApprovedByItemCountDescWithCursor` and its javadoc.
- Modify: `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java`
  - `getChannelsWithCursor` `:215-220` and `getPlaylistsWithCursor` `:268-274`: keep only the category branch, since `category` is non-blank whenever they are reached now.
  - Keep `VideoRepository.findApprovedByUploadedAtDescWithCursor`: the explicit `sort=NEWEST` path still uses it.

**Interfaces:**
- Consumes: Task 1's dispatch, which guarantees these methods only run with a category.
- Produces: none.

- [ ] **Step 1: Prove they are unused**

  Run: `grep -rn "findApprovedBySubscribersDescWithCursor\|findApprovedByItemCountDescWithCursor" /Users/farouqabouumar/Development/albunyaantube/backend/src`
  Expected: only the two declarations and the two `else` branches in `PublicContentService`. Tasks 1 and 2 removed the test references.

- [ ] **Step 2: Delete them and the two branches**

  Leave `firestore.indexes.json` as is: dropping a prod index is a separate console operation and gains nothing.

- [ ] **Step 3: Run the backend suite**

  Run: `cd /Users/farouqabouumar/Development/albunyaantube/backend && JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home timeout 300 ./gradlew test`
  Expected: BUILD SUCCESSFUL, with the same test count as after Task 2.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/repository/ChannelRepository.java backend/src/main/java/com/albunyaan/tube/repository/PlaylistRepository.java backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java
git commit -m "[REFACTOR]: Delete unused tab cursor queries"
```

### Task 4: Make the "Most popular" Videos sort work

The Android app sends `sort=MOST_POPULAR` (`RetrofitContentService.kt:66-70`), and the iOS generated client's `SortPayload` and `api-specification.yaml:128-132` use the same enum `[DEFAULT, MOST_POPULAR, NEWEST]`. The backend's `getVideos` switch handles only `"POPULAR"` (`PublicContentService.java:364`). So "Most popular" falls to `default` and comes back in upload order.

The fix goes in the backend, so every shipped app version works without an app update. Rename the label to the contract value: `case "MOST_POPULAR":`. Nothing sends `"POPULAR"`: `grep -rn '"POPULAR"' android/app/src/main ios/FitrahTube frontend/src backend/src` finds only that case label. Proven-unused values are deleted (owner rule), not kept as aliases.

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java:364`
- Test: `backend/src/test/java/com/albunyaan/tube/service/PublicContentServicePaginationTest.java`

**Interfaces:**
- Consumes: none.
- Produces: none (the wire contract is unchanged).

- [ ] **Step 1: Write the failing test**

```java
@Test
void getContent_videos_mostPopular_sortsByViewsDescending() throws Exception {
    List<Video> videos = createTestVideos(3);
    videos.get(0).setViewCount(10L);
    videos.get(1).setViewCount(30L);
    videos.get(2).setViewCount(20L);
    when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt())).thenReturn(videos);

    CursorPageDto<ContentItemDto> response = publicContentService.getContent(
            "VIDEOS", null, 5, null, null, null, "MOST_POPULAR");

    assertEquals(List.of("yt-video-2", "yt-video-3", "yt-video-1"),
            response.getData().stream().map(ContentItemDto::getId).toList());
}
```

- [ ] **Step 2: Run it to verify it fails**

  Run: `cd /Users/farouqabouumar/Development/albunyaantube/backend && JAVA_HOME=$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home ./gradlew test --tests 'com.albunyaan.tube.service.PublicContentServicePaginationTest.getContent_videos_mostPopular_sortsByViewsDescending'`
  Expected: FAIL with the actual order `[yt-video-1, yt-video-2, yt-video-3]`.

- [ ] **Step 3: Rename the case label**

  Change `case "POPULAR":` → `case "MOST_POPULAR":`.

- [ ] **Step 4: Re-run the same command**

  Expected: PASS.

  No Android or iOS change is needed. Android unit tests couldn't run here anyway (no SDK), and none are needed.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java backend/src/test/java/com/albunyaan/tube/service/PublicContentServicePaginationTest.java
git commit -m "[FIX]: Accept MOST_POPULAR video sort"
```

### Task 5: Confirm neither app keeps tab pages past a refresh

Recon (2026-11-11) already found:
- Live `/api/v1/content` responses carry `cache-control: no-cache, no-store, max-age=0, must-revalidate` (Spring Security default) and `cf-cache-status: DYNAMIC`.
- Android's API `OkHttpClient` has no `.cache(...)`: `android/app/src/main/java/com/albunyaan/tube/di/NetworkModule.kt:94-134`, with the comment "No HTTP cache" at `:129`. `RetrofitContentService.fetchContent` calls Retrofit directly.
- iOS `LiveCatalogClient.content` (`ios/FitrahTube/Catalog/LiveCatalogClient.swift:38`) runs on `URLSessionTransport()`, which honours `no-store`.

This task re-proves those findings at execution time and changes code only if one fails.

**Files (read; modify only on a failed check):**
- `android/app/src/main/java/com/albunyaan/tube/di/NetworkModule.kt`
- `android/app/src/main/java/com/albunyaan/tube/data/source/RetrofitContentService.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/ContentListViewModel.kt`: `refresh()` `:238`, `refreshFirstPageInPlace()` `:131`
- `ios/FitrahTube/Catalog/LiveCatalogClient.swift`
- `ios/FitrahTube/App/AppContainer.swift`: transports `:297,735,750`
- `ios/FitrahTube/Features/Lists/ContentListViewModel.swift`: `refresh()` `:187`, `refreshInPlace()` `:196`

**Interfaces:**
- Consumes: Task 2's server behaviour (a fresh order on the first request after a content change).
- Produces: a pass/fail line per check in the executor's final report. No file is created.

- [ ] **Step 1: Check the HTTP headers**

  Run: `curl -s -D - -o /dev/null "https://app.fitrahtube.com/api/v1/content?type=CHANNELS&limit=1" | grep -i "cache-control\|cf-cache-status"`
  Expected: `no-store` and `DYNAMIC`.

- [ ] **Step 2: Check the client caches**

  Run: `grep -rn "\.cache(\|okhttp3.Cache\b" android/app/src/main/java; grep -rn "URLCache\|cachePolicy\|returnCacheDataElseLoad" ios/FitrahTube --include='*.swift'`
  Expected: nothing on the API client or transport. The image-only `URLCache` in `ios/FitrahTube/DesignSystem/Components.swift:95-107` belongs to thumbnails and is fine.

- [ ] **Step 3: Check the in-memory paths**

  Read both `ContentListViewModel`s and confirm three things:
  - `refresh()` refetches from `cursor = nil/null`.
  - No repository or catalog layer memoizes `content(...)` / `fetchContent(...)` results.
  - The in-place refresh drops rows that page 1 already shows. Android does this at `:142-143`, and its existing test is `a page after a return-after-idle refresh drops rows already shown`.

- [ ] **Step 4: Only if a check fails**

  Make the smallest fix where the result gets cached, with a unit test.
  - iOS: run `ios/scripts/test.sh`, one run at a time (IPAD_SIM may be set to `""` for the iPhone-only window).
  - Android: the unit tests can't run here (no SDK). Say so, and rely on CI plus a cold read of the diff.
  - Commit with `[FIX]: …`.

  If no check fails, there is no commit.

### Task 6: Live proof after the backend deploy

This runs after Part D's jar swap, which comes after the 9-stage pipeline and Cubic. It is not a coding task.

**Files:** none.

**Interfaces:**
- Consumes: the deployed Tasks 1-4.
- Produces: before/after id lists in the release report.

- [ ] **Step 1: Record the current order**

  Run: `curl -s "https://app.fitrahtube.com/api/v1/content?type=CHANNELS&limit=10" | jq -r '.data[].id'` and the same with `type=PLAYLISTS` and `type=VIDEOS`.
  Expected: the first rows match the first category's order on the dashboard's Content Sorting page.

- [ ] **Step 2: Change the order and re-check**

  On the dashboard Content Sorting page, move the second channel of the top category to position 0. Re-run the `type=CHANNELS` curl.
  Expected: that channel is now first, on the first request after the move.

- [ ] **Step 3: Restore and confirm the paths**

  Move the channel back and confirm the original order returns. Also confirm the paging and filter paths:
  - Page 2 (`cursor` from page 1) continues without a repeat.
  - `&sort=NEWEST` still orders by upload date.
