package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.HomeCategoryDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.SearchIndex;
import com.albunyaan.tube.util.CursorUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The CHANNELS / PLAYLISTS / VIDEOS tabs walk the categories in Content Sorting order; filters read that kept order. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicContentServiceTabOrderTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryContentOrderRepository orderRepository;
    @Mock private SearchIndex searchIndex;
    @Mock private LiveCategoryService liveCategoryService;

    private PublicContentService service;
    private List<Category> categories;

    @BeforeEach
    void catalog() throws Exception {
        service = new PublicContentService(channelRepository, playlistRepository, videoRepository,
                categoryRepository, orderRepository, Runnable::run, searchIndex, liveCategoryService);

        Category c1 = cat("c1", 1, null);
        Category c1a = cat("c1a", 0, "c1");
        categories = List.of(c1, c1a, cat("c3", 2, null), cat("c2", 0, null));
        when(categoryRepository.findAll()).thenReturn(categories);
        when(categoryRepository.findByParentId("c1")).thenReturn(List.of(c1a));

        Map<String, Channel> chs = docs("ch", PublicContentServiceTabOrderTest::channel);
        Map<String, Playlist> pls = docs("pl", PublicContentServiceTabOrderTest::playlist);
        Map<String, Video> vs = docs("v", PublicContentServiceTabOrderTest::video);
        chs.get("ch-G").setValidationStatus(ValidationStatus.ARCHIVED);
        pls.get("pl-G").setValidationStatus(ValidationStatus.ARCHIVED);
        vs.get("v-G").setValidationStatus(ValidationStatus.ARCHIVED);
        chs.get("ch-P").setStatus("PENDING");
        pls.get("pl-P").setStatus("PENDING");
        vs.get("v-P").setStatus("PENDING");

        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c2")))
                .thenReturn(rows("c2", "C", "A", "G", "P"));
        when(orderRepository.findByCategoryIdOrderByPosition("c1")).thenReturn(rows("c1", "B", "C"));

        when(channelRepository.findAllByIds(anyList())).thenReturn(chs);
        when(playlistRepository.findAllByIds(anyList())).thenReturn(pls);
        when(videoRepository.findAllByIds(anyList())).thenReturn(vs);

        when(channelRepository.findByCategoryIds(eq(List.of("c2")), anyInt()))
                .thenReturn(pick(chs, "ch", "I", "A", "G", "H", "C"));
        when(channelRepository.findByCategoryIds(eq(List.of("c1", "c1a")), anyInt()))
                .thenReturn(pick(chs, "ch", "D", "B", "C"));
        when(playlistRepository.findByCategoryIds(eq(List.of("c2")), anyInt()))
                .thenReturn(pick(pls, "pl", "I", "A", "G", "H", "C"));
        when(playlistRepository.findByCategoryIds(eq(List.of("c1", "c1a")), anyInt()))
                .thenReturn(pick(pls, "pl", "D", "B", "C"));
        when(videoRepository.findByCategoryIds(eq(List.of("c2")), anyInt()))
                .thenReturn(pick(vs, "v", "I", "A", "G", "H", "C"));
        when(videoRepository.findByCategoryIds(eq(List.of("c1", "c1a")), anyInt()))
                .thenReturn(pick(vs, "v", "D", "B", "C"));

        String[] approved = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "O"};
        when(channelRepository.findByStatus("APPROVED")).thenReturn(pick(chs, "ch", approved));
        when(playlistRepository.findByStatus("APPROVED")).thenReturn(pick(pls, "pl", approved));
        when(videoRepository.findByStatus("APPROVED")).thenReturn(pick(vs, "v", approved));
    }

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
    @Test void categoriesFiltersAndSortsReadTheKeptOrderOnly() throws Exception {
        // c2's channels by subscribers (none set: the tab order stands); c1 holds its subcategory c1a's D too.
        assertEquals(List.of("yt-ch-C", "yt-ch-A", "yt-ch-I", "yt-ch-H"), ids("CHANNELS", "c2", null, null));
        assertEquals(List.of("yt-v-C", "yt-v-B", "yt-v-D"), ids("VIDEOS", "c1", null, "NEWEST"));
        assertEquals(List.of("yt-pl-C", "yt-pl-B", "yt-pl-D"), ids("PLAYLISTS", "c1", null, null));
        assertEquals(List.of("yt-v-D"), ids("VIDEOS", "c1a", "SHORT", null));
        verify(categoryRepository, times(1)).findAll();
        verify(categoryRepository, never()).findByParentId("c2"); // the kept tree answers, not a read per page
        verify(videoRepository, never()).findAllByOrderByUploadedAtDesc(anyInt());
    }

    @Test void aVideoWithNoLengthIsDroppedByALengthFilterButKeptWithout() throws Exception {
        videoRepository.findAllByIds(List.of()).get("v-D").setDurationSeconds(null);
        assertEquals(List.of(), ids("VIDEOS", "c1a", "SHORT", null));
        assertEquals(List.of("yt-v-D"), ids("VIDEOS", "c1a", null, null));
    }

    @Test void anUnrecognisedLengthKeepsEveryVideoEvenOneWithNoLength() throws Exception {
        videoRepository.findAllByIds(List.of()).get("v-D").setDurationSeconds(null);
        assertEquals(List.of("yt-v-D"), ids("VIDEOS", "c1a", "ANY", null));
    }

    private List<String> ids(String tab, String category, String length, String sort) throws Exception {
        return service.getContent(tab, null, 20, category, length, null, sort).getData().stream()
                .map(ContentItemDto::getId).toList();
    }

    @Test void sortDefaultServesTheCuratedVideosTab() throws Exception {
        // api-specification.yaml lists DEFAULT as a sort value: it means "no sort", not the newest-first path.
        for (String sort : List.of("DEFAULT", "default")) {
            assertEquals(List.of("yt-v-C", "yt-v-A", "yt-v-I", "yt-v-H"),
                    service.getContent("VIDEOS", null, 4, null, null, null, sort).getData().stream()
                            .map(ContentItemDto::getId).toList(), sort);
        }
    }

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
    @Test void aTimedOutCategoryReadSurfacesAsTheTimeoutAndLeavesNothingPartialCached() throws Exception {
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c2"))).thenThrow(new TimeoutException("firestore"));
        assertThrows(TimeoutException.class, () -> service.getContent("CHANNELS", null, 4, null, null, null, null));
        doReturn(rows("c2", "C", "A", "G", "P")).when(orderRepository).findByCategoryIdsOrderByPosition(List.of("c2"));
        assertEquals(List.of("yt-ch-C","yt-ch-A","yt-ch-I","yt-ch-H","yt-ch-B","yt-ch-D","yt-ch-F","yt-ch-O","yt-ch-E"),
                walk("CHANNELS", "CHANNEL"));
    }
    @Test void aFailedReadThatIsNotATimeoutIsNotKeptEither() throws Exception {
        when(categoryRepository.findAll()).thenThrow(denied()).thenReturn(categories);
        assertThrows(ExecutionException.class, () -> first("CHANNELS"));
        assertEquals("yt-ch-C", first("CHANNELS"));
        verify(categoryRepository, times(2)).findAll();
    }
    @Test void anAToZTwinOfTheSameItemIsListedOnce() throws Exception {
        // Two APPROVED docs for one youtube id (a re-add), neither in a category: one row, as the category walk dedupes.
        Channel twin = channel("ch-F2", "alpha", List.of());
        twin.setYoutubeId("yt-ch-F");
        List<Channel> approved = new ArrayList<>(channelRepository.findByStatus("APPROVED"));
        approved.add(twin);
        when(channelRepository.findByStatus("APPROVED")).thenReturn(approved);
        assertEquals(List.of("yt-ch-C","yt-ch-A","yt-ch-I","yt-ch-H","yt-ch-B","yt-ch-D","yt-ch-F","yt-ch-O","yt-ch-E"),
                walk("CHANNELS", "CHANNEL"));
    }

    @Test void concurrentFirstRequestsShareOneBuild() throws Exception {
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(categoryRepository.findAll()).thenAnswer(inv -> {
            building.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return categories;
        });
        FutureTask<String> builder = start(() -> first("CHANNELS"));
        assertTrue(building.await(10, TimeUnit.SECONDS));
        FutureTask<String> waiter = new FutureTask<>(() -> first("CHANNELS"));
        Thread waiterThread = new Thread(waiter);
        waiterThread.start();
        awaitParkedInTabOrder(waiterThread);
        release.countDown();
        assertEquals("yt-ch-C", builder.get(10, TimeUnit.SECONDS));
        assertEquals("yt-ch-C", waiter.get(10, TimeUnit.SECONDS));
        verify(categoryRepository, times(1)).findAll();
    }

    @Test void aFailedSharedBuildFailsItsWaiterWithoutASecondBuildAndTheNextRequestRebuilds() throws Exception {
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(categoryRepository.findAll()).thenAnswer(inv -> {
            building.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            throw new TimeoutException("firestore");
        }).thenReturn(categories);
        FutureTask<String> builder = start(() -> first("CHANNELS"));
        assertTrue(building.await(10, TimeUnit.SECONDS));
        FutureTask<String> waiter = new FutureTask<>(() -> first("CHANNELS"));
        Thread waiterThread = new Thread(waiter);
        waiterThread.start();
        awaitParkedInTabOrder(waiterThread);
        release.countDown();
        assertInstanceOf(TimeoutException.class,
                assertThrows(ExecutionException.class, () -> builder.get(10, TimeUnit.SECONDS)).getCause());
        assertInstanceOf(TimeoutException.class,
                assertThrows(ExecutionException.class, () -> waiter.get(10, TimeUnit.SECONDS)).getCause());
        verify(categoryRepository, times(1)).findAll(); // the waiter shared the failure, it did not rebuild
        assertEquals("yt-ch-C", first("CHANNELS"));
        verify(categoryRepository, times(2)).findAll();
    }

    /** A read failure that is not a timeout (e.g. gRPC UNAVAILABLE, a missing index, a permission error). */
    private static ExecutionException denied() {
        return new ExecutionException("PERMISSION_DENIED", new RuntimeException("firestore"));
    }

    private static FutureTask<String> start(java.util.concurrent.Callable<String> call) {
        FutureTask<String> task = new FutureTask<>(call);
        new Thread(task).start();
        return task;
    }

    /** Until {@code t} is parked or blocked inside PublicContentService.tabOrder (waiting on the build in flight). */
    private static void awaitParkedInTabOrder(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread.State state = t.getState();
            boolean inTabOrder = Arrays.stream(t.getStackTrace()).anyMatch(f ->
                    f.getClassName().equals(PublicContentService.class.getName()) && f.getMethodName().equals("tabOrder"));
            if (inTabOrder && (state == Thread.State.BLOCKED || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("waiter never reached the in-flight build: " + t.getState());
    }

    private String first(String tab) throws Exception {
        return service.getContent(tab, null, 4, null, null, null, null).getData().get(0).getId();
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

    private static Category cat(String id, int displayOrder, String parentId) {
        Category c = new Category();
        c.setId(id);
        c.setName(id);
        c.setDisplayOrder(displayOrder);
        c.setParentCategoryId(parentId);
        return c;
    }

    /** Letter -> (title, categoryIds) per the fixture table; the doc id is "T-X", the youtube id "yt-T-X". */
    private static final Map<String, Object[]> FIXTURE = new LinkedHashMap<>();
    static {
        FIXTURE.put("C", new Object[]{null, List.of("c2", "c1")});
        FIXTURE.put("A", new Object[]{null, List.of("c2")});
        FIXTURE.put("G", new Object[]{null, List.of("c2")});
        FIXTURE.put("P", new Object[]{null, List.of("c2")});
        FIXTURE.put("I", new Object[]{null, List.of("c2")});
        FIXTURE.put("H", new Object[]{null, List.of("c2")});
        FIXTURE.put("B", new Object[]{null, List.of("c1")});
        FIXTURE.put("D", new Object[]{null, List.of("c1a")});
        FIXTURE.put("F", new Object[]{"alpha", List.of()});
        FIXTURE.put("O", new Object[]{"Omega", List.of("gone")});
        FIXTURE.put("E", new Object[]{"Zeta", List.of()});
    }

    private interface DocFactory<T> { T make(String id, String title, List<String> categoryIds); }

    /** The fixture for one type: doc id "T-X" (also the youtube id, prefixed "yt-"), APPROVED. */
    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> docs(String t, DocFactory<T> factory) {
        Map<String, T> out = new LinkedHashMap<>();
        FIXTURE.forEach((letter, spec) -> {
            String title = spec[0] != null ? (String) spec[0] : t + " " + letter;
            out.put(t + "-" + letter, factory.make(t + "-" + letter, title, (List<String>) spec[1]));
        });
        return out;
    }

    private static Channel channel(String id, String title, List<String> categoryIds) {
        Channel c = new Channel("yt-" + id);
        c.setId(id);
        c.setName(title);
        c.setCategoryIds(categoryIds);
        c.setStatus("APPROVED");
        return c;
    }
    private static Playlist playlist(String id, String title, List<String> categoryIds) {
        Playlist p = new Playlist("yt-" + id);
        p.setId(id);
        p.setTitle(title);
        p.setCategoryIds(categoryIds);
        p.setStatus("APPROVED");
        return p;
    }
    private static Video video(String id, String title, List<String> categoryIds) {
        Video v = new Video("yt-" + id);
        v.setId(id);
        v.setTitle(title);
        v.setCategoryIds(categoryIds);
        v.setStatus("APPROVED");
        v.setDurationSeconds(120); // a known SHORT length: unknown length matches no length filter
        return v;
    }

    private static <T> List<T> pick(Map<String, T> docs, String t, String... letters) {
        return Arrays.stream(letters).map(l -> docs.get(t + "-" + l)).collect(Collectors.toList());
    }

    /** One order row per letter and type, the three types interleaved (channel, playlist, video). */
    private static List<CategoryContentOrder> rows(String categoryId, String... letters) {
        List<CategoryContentOrder> out = new ArrayList<>();
        int pos = 0;
        for (String l : letters) {
            out.add(new CategoryContentOrder(categoryId, "ch-" + l, "channel", pos++));
            out.add(new CategoryContentOrder(categoryId, "pl-" + l, "playlist", pos++));
            out.add(new CategoryContentOrder(categoryId, "v-" + l, "video", pos++));
        }
        return out;
    }
}
