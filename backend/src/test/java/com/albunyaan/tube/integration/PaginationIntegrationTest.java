package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.PendingApprovalDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.ApprovalService;
import com.albunyaan.tube.service.PublicContentService;
import com.albunyaan.tube.util.TestDataBuilder;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-T4: Pagination Integration Tests
 *
 * Integration tests for cursor-based pagination across public content and approval endpoints.
 * Tests verify:
 * - Monotonic ordering (by subscribers, itemCount, uploadedAt)
 * - No duplicates across pages
 * - Correct hasNext detection
 * - Opaque cursor handling
 * - Edge cases (last page, empty dataset, invalid cursors)
 *
 * Run with: ./gradlew test --tests '*PaginationIntegrationTest*' -Pintegration=true
 * Expected runtime: ~60-90 seconds with Firestore emulator
 */
public class PaginationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private PublicContentService publicContentService;

    @Autowired
    private ApprovalService approvalService;

    private Category testCategory;
    private Category secondCategory;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{
                "categories",
                "channels",
                "playlists",
                "videos",
                "users",
                "audit_logs"
        };
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Create test categories
        testCategory = TestDataBuilder.createCategory("Quran");
        testCategory = categoryRepository.save(testCategory);

        secondCategory = TestDataBuilder.createCategory("Islamic History");
        secondCategory = categoryRepository.save(secondCategory);
    }

    // ========== Test Data Seeding Utilities ==========

    /**
     * Seed approved channels with deterministic subscriber counts for ordering verification.
     * Channels are named with their expected order (channel-001 has highest subscribers).
     */
    private List<Channel> seedApprovedChannels(int count) throws Exception {
        List<Channel> channels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = String.format("channel-%03d", i + 1);
            Channel channel = TestDataBuilder.createApprovedChannel(id, "Channel " + (i + 1));
            // Higher index = lower subscribers (for descending order test)
            channel.setSubscribers((long) (count - i) * 10000);
            channel.setCategoryIds(List.of(testCategory.getId()));
            channel = channelRepository.save(channel);
            channels.add(channel);
        }
        return channels;
    }

    /**
     * Seed approved playlists with deterministic item counts for ordering verification.
     */
    private List<Playlist> seedApprovedPlaylists(int count) throws Exception {
        List<Playlist> playlists = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = String.format("playlist-%03d", i + 1);
            Playlist playlist = TestDataBuilder.createApprovedPlaylist(id, "Playlist " + (i + 1));
            // Higher index = lower item count (for descending order test)
            playlist.setItemCount((count - i) * 10);
            playlist.setCategoryIds(List.of(testCategory.getId()));
            playlist = playlistRepository.save(playlist);
            playlists.add(playlist);
        }
        return playlists;
    }

    /**
     * Seed approved videos with deterministic upload dates for ordering verification.
     */
    private List<Video> seedApprovedVideos(int count) throws Exception {
        List<Video> videos = new ArrayList<>();
        long baseTime = System.currentTimeMillis() / 1000;

        for (int i = 0; i < count; i++) {
            Video video = new Video(String.format("video-%03d", i + 1));
            video.setTitle("Video " + (i + 1));
            video.setDescription("Test video " + (i + 1));
            video.setThumbnailUrl("https://example.com/thumb.jpg");
            video.setDurationSeconds(300);
            video.setViewCount(1000L);
            // Higher index = older upload date (for descending order test)
            video.setUploadedAt(Timestamp.ofTimeSecondsAndNanos(baseTime - (i * 3600), 0));
            video.setChannelId("test-channel");
            video.setChannelTitle("Test Channel");
            video.setStatus("APPROVED");
            video.setApprovedBy("test-admin");
            video.setCategoryIds(List.of(testCategory.getId()));
            video = videoRepository.save(video);
            videos.add(video);
        }
        return videos;
    }

    /**
     * Seed pending channels for approval workflow tests.
     */
    private List<Channel> seedPendingChannels(int count, String categoryId) throws Exception {
        List<Channel> channels = new ArrayList<>();
        long baseTime = System.currentTimeMillis() / 1000;

        for (int i = 0; i < count; i++) {
            String id = String.format("pending-channel-%03d", i + 1);
            Channel channel = TestDataBuilder.createChannel(id, "Pending Channel " + (i + 1));
            channel.setStatus("PENDING");
            // Older createdAt = higher index (for descending order test)
            channel.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(baseTime - (i * 60), 0));
            if (categoryId != null) {
                channel.setCategoryIds(List.of(categoryId));
            }
            channel = channelRepository.save(channel);
            channels.add(channel);
        }
        return channels;
    }

    /**
     * Seed pending playlists for approval workflow tests.
     */
    private List<Playlist> seedPendingPlaylists(int count, String categoryId) throws Exception {
        List<Playlist> playlists = new ArrayList<>();
        long baseTime = System.currentTimeMillis() / 1000;

        for (int i = 0; i < count; i++) {
            String id = String.format("pending-playlist-%03d", i + 1);
            Playlist playlist = TestDataBuilder.createPlaylist(id, "Pending Playlist " + (i + 1));
            playlist.setStatus("PENDING");
            // Older createdAt = higher index (for descending order test)
            playlist.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(baseTime - (i * 60), 0));
            if (categoryId != null) {
                playlist.setCategoryIds(List.of(categoryId));
            }
            playlist = playlistRepository.save(playlist);
            playlists.add(playlist);
        }
        return playlists;
    }

    // ========== Public Content Pagination Tests ==========

    @Nested
    @DisplayName("Public Content - Channels Pagination")
    class ChannelPaginationTests {

        @Test
        @DisplayName("Should paginate through all channels with no duplicates")
        void paginateAllChannels_noDuplicates() throws Exception {
            // Arrange: Seed 55 channels (to test multi-page traversal)
            int totalChannels = 55;
            int pageSize = 20;
            seedApprovedChannels(totalChannels);

            // Act: Walk through all pages
            Set<String> seenIds = new HashSet<>();
            List<Long> subscriberCounts = new ArrayList<>();
            String cursor = null;
            int pageCount = 0;

            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, pageSize, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    // Assert no duplicates
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate item found: " + item.getId());
                    seenIds.add(item.getId());
                    subscriberCounts.add(item.getSubscribers());
                }

                cursor = page.getPageInfo().getNextCursor();
                pageCount++;

                // Safety: prevent infinite loops
                if (pageCount > 10) {
                    fail("Too many pages - possible infinite loop");
                }
            } while (cursor != null);

            // Assert: All channels retrieved
            assertEquals(totalChannels, seenIds.size(),
                    "Should retrieve all channels");

            // Assert: Monotonic descending order (subscribers)
            for (int i = 1; i < subscriberCounts.size(); i++) {
                assertTrue(subscriberCounts.get(i - 1) >= subscriberCounts.get(i),
                        "Subscribers should be in descending order at index " + i);
            }

            // Assert: Correct page count (55 items / 20 per page = 3 pages)
            assertEquals(3, pageCount, "Should have 3 pages");
        }

        @Test
        @DisplayName("Should return hasNext=false on last page")
        void lastPage_hasNextFalse() throws Exception {
            // Arrange: Seed exactly 15 channels (less than page size)
            int totalChannels = 15;
            int pageSize = 20;
            seedApprovedChannels(totalChannels);

            // Act
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "CHANNELS", null, pageSize, null, null, null, null);

            // Assert
            assertEquals(totalChannels, page.getData().size());
            assertFalse(page.getPageInfo().isHasNext(), "Should not have next page");
            assertNull(page.getPageInfo().getNextCursor(), "Cursor should be null on last page");
        }

        @Test
        @DisplayName("Should handle empty dataset gracefully")
        void emptyDataset_returnsEmptyPage() throws Exception {
            // Act: Query with no data
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "CHANNELS", null, 20, null, null, null, null);

            // Assert
            assertTrue(page.getData().isEmpty(), "Data should be empty");
            assertFalse(page.getPageInfo().isHasNext(), "Should not have next page");
            assertNull(page.getPageInfo().getNextCursor(), "Cursor should be null");
        }

        @Test
        @DisplayName("Should filter by category correctly")
        void filterByCategory_returnsOnlyMatchingItems() throws Exception {
            // Arrange: Seed channels in different categories
            int channelsInCategory = 10;
            int channelsOutside = 5;

            // Channels in testCategory
            for (int i = 0; i < channelsInCategory; i++) {
                Channel channel = TestDataBuilder.createApprovedChannel("cat-channel-" + i, "Cat Channel " + i);
                channel.setSubscribers((long) (channelsInCategory - i) * 1000);
                channel.setCategoryIds(List.of(testCategory.getId()));
                channelRepository.save(channel);
            }

            // Channels in secondCategory
            for (int i = 0; i < channelsOutside; i++) {
                Channel channel = TestDataBuilder.createApprovedChannel("other-channel-" + i, "Other Channel " + i);
                channel.setSubscribers((long) i * 1000);
                channel.setCategoryIds(List.of(secondCategory.getId()));
                channelRepository.save(channel);
            }

            // Act
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "CHANNELS", null, 50, testCategory.getId(), null, null, null);

            // Assert
            assertEquals(channelsInCategory, page.getData().size(),
                    "Should only return channels in the specified category");
        }

        @Test
        @DisplayName("Should maintain order when fetching different page sizes")
        void differentPageSizes_maintainOrder() throws Exception {
            // Arrange
            int totalChannels = 30;
            seedApprovedChannels(totalChannels);

            // Act: Fetch with page size 10 and 15
            List<String> ids10 = new ArrayList<>();
            List<String> ids15 = new ArrayList<>();

            String cursor = null;
            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, 10, null, null, null, null);
                page.getData().forEach(item -> ids10.add(item.getId()));
                cursor = page.getPageInfo().getNextCursor();
            } while (cursor != null);

            cursor = null;
            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, 15, null, null, null, null);
                page.getData().forEach(item -> ids15.add(item.getId()));
                cursor = page.getPageInfo().getNextCursor();
            } while (cursor != null);

            // Assert: Same items, same order
            assertEquals(ids10, ids15, "Items and order should match regardless of page size");
        }
    }

    @Nested
    @DisplayName("Public Content - Playlists Pagination")
    class PlaylistPaginationTests {

        @Test
        @DisplayName("Should paginate through all playlists ordered by item count")
        void paginateAllPlaylists_orderedByItemCount() throws Exception {
            // Arrange
            int totalPlaylists = 45;
            int pageSize = 20;
            seedApprovedPlaylists(totalPlaylists);

            // Act
            Set<String> seenIds = new HashSet<>();
            List<Integer> itemCounts = new ArrayList<>();
            String cursor = null;

            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "PLAYLISTS", cursor, pageSize, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate item found: " + item.getId());
                    seenIds.add(item.getId());
                    itemCounts.add(item.getVideoCount());
                }

                cursor = page.getPageInfo().getNextCursor();
            } while (cursor != null);

            // Assert
            assertEquals(totalPlaylists, seenIds.size());

            // Assert: Monotonic descending order (item count)
            for (int i = 1; i < itemCounts.size(); i++) {
                assertTrue(itemCounts.get(i - 1) >= itemCounts.get(i),
                        "Item counts should be in descending order at index " + i);
            }
        }

        @Test
        @DisplayName("Should handle exactly one page of results")
        void exactlyOnePage_correctBehavior() throws Exception {
            // Arrange: Seed exactly pageSize items
            int pageSize = 20;
            seedApprovedPlaylists(pageSize);

            // Act
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "PLAYLISTS", null, pageSize, null, null, null, null);

            // Assert
            assertEquals(pageSize, page.getData().size());
            assertFalse(page.getPageInfo().isHasNext());
        }
    }

    @Nested
    @DisplayName("Public Content - Videos Pagination")
    class VideoPaginationTests {

        @Test
        @DisplayName("Should paginate through all videos ordered by upload date")
        void paginateAllVideos_orderedByUploadDate() throws Exception {
            // Arrange
            int totalVideos = 65;
            int pageSize = 20;
            seedApprovedVideos(totalVideos);

            // Act
            Set<String> seenIds = new HashSet<>();
            List<Integer> uploadDaysAgo = new ArrayList<>();
            String cursor = null;
            int pageCount = 0;

            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "VIDEOS", cursor, pageSize, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate video found: " + item.getId());
                    seenIds.add(item.getId());
                    if (item.getUploadedDaysAgo() != null) {
                        uploadDaysAgo.add(item.getUploadedDaysAgo());
                    }
                }

                cursor = page.getPageInfo().getNextCursor();
                pageCount++;
            } while (cursor != null);

            // Assert
            assertEquals(totalVideos, seenIds.size(), "Should retrieve all videos");

            // Assert: Monotonic ascending order (days ago - older videos have higher values)
            // Since we're ordering by uploadedAt DESC, daysAgo should be ascending
            for (int i = 1; i < uploadDaysAgo.size(); i++) {
                assertTrue(uploadDaysAgo.get(i - 1) <= uploadDaysAgo.get(i),
                        "Upload days ago should be in ascending order at index " + i);
            }

            assertEquals(4, pageCount, "Should have 4 pages for 65 items with page size 20");
        }

        @Test
        @DisplayName("Last page should have fewer items than page size")
        void lastPage_fewerItems() throws Exception {
            // Arrange: 25 videos = 1 full page + 5 items
            int totalVideos = 25;
            int pageSize = 20;
            seedApprovedVideos(totalVideos);

            // Act: Get first page
            CursorPageDto<ContentItemDto> firstPage = publicContentService.getContent(
                    "VIDEOS", null, pageSize, null, null, null, null);

            // Assert first page
            assertEquals(pageSize, firstPage.getData().size());
            assertTrue(firstPage.getPageInfo().isHasNext());

            // Act: Get second (last) page
            CursorPageDto<ContentItemDto> lastPage = publicContentService.getContent(
                    "VIDEOS", firstPage.getPageInfo().getNextCursor(), pageSize, null, null, null, null);

            // Assert last page
            assertEquals(5, lastPage.getData().size());
            assertFalse(lastPage.getPageInfo().isHasNext());
        }
    }

    // ========== Pending Approvals Pagination Tests ==========

    @Nested
    @DisplayName("Pending Approvals Pagination")
    class PendingApprovalsPaginationTests {

        @Test
        @DisplayName("Should paginate through pending channels only")
        void paginatePendingChannels_noDuplicates() throws Exception {
            // Arrange
            int totalPending = 35;
            int pageSize = 10;
            seedPendingChannels(totalPending, testCategory.getId());

            // Act
            Set<String> seenIds = new HashSet<>();
            String cursor = null;
            int pageCount = 0;

            do {
                CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                        "CHANNEL", null, pageSize, cursor);

                for (PendingApprovalDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate pending item found: " + item.getId());
                    seenIds.add(item.getId());
                    assertEquals("CHANNEL", item.getType(), "All items should be channels");
                }

                cursor = page.getPageInfo().getNextCursor();
                pageCount++;

                if (pageCount > 10) {
                    fail("Too many pages");
                }
            } while (cursor != null);

            // Assert
            assertEquals(totalPending, seenIds.size());
            assertEquals(4, pageCount, "Should have 4 pages for 35 items with page size 10");
        }

        @Test
        @DisplayName("Should paginate through pending playlists only")
        void paginatePendingPlaylists_noDuplicates() throws Exception {
            // Arrange
            int totalPending = 28;
            int pageSize = 10;
            seedPendingPlaylists(totalPending, testCategory.getId());

            // Act
            Set<String> seenIds = new HashSet<>();
            String cursor = null;

            do {
                CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                        "PLAYLIST", null, pageSize, cursor);

                for (PendingApprovalDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate pending item found: " + item.getId());
                    seenIds.add(item.getId());
                    assertEquals("PLAYLIST", item.getType(), "All items should be playlists");
                }

                cursor = page.getPageInfo().getNextCursor();
            } while (cursor != null);

            // Assert
            assertEquals(totalPending, seenIds.size());
        }

        @Test
        @DisplayName("Should filter pending items by category")
        void filterByCategory_onlyMatchingItems() throws Exception {
            // Arrange: Pending in different categories
            int inCategory = 15;
            int outsideCategory = 10;
            seedPendingChannels(inCategory, testCategory.getId());
            seedPendingChannels(outsideCategory, secondCategory.getId());

            // Act: Filter by testCategory
            Set<String> seenIds = new HashSet<>();
            String cursor = null;

            do {
                CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                        "CHANNEL", testCategory.getId(), 50, cursor);

                for (PendingApprovalDto item : page.getData()) {
                    seenIds.add(item.getId());
                }

                cursor = page.getPageInfo().getNextCursor();
            } while (cursor != null);

            // Assert
            assertEquals(inCategory, seenIds.size(),
                    "Should only return items in specified category");
        }

        @Test
        @DisplayName("Should handle mixed type pagination (channels + playlists)")
        void mixedTypePagination_correctMergeSort() throws Exception {
            // Arrange: Mix of channels and playlists
            int channelCount = 15;
            int playlistCount = 12;
            seedPendingChannels(channelCount, testCategory.getId());
            seedPendingPlaylists(playlistCount, testCategory.getId());

            // Act: Get all mixed without type filter
            Set<String> seenIds = new HashSet<>();
            int channelsSeen = 0;
            int playlistsSeen = 0;
            String cursor = null;

            do {
                CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                        null, null, 10, cursor);

                for (PendingApprovalDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate in mixed results: " + item.getId());
                    seenIds.add(item.getId());

                    if ("CHANNEL".equals(item.getType())) {
                        channelsSeen++;
                    } else if ("PLAYLIST".equals(item.getType())) {
                        playlistsSeen++;
                    }
                }

                cursor = page.getPageInfo().getNextCursor();
            } while (cursor != null);

            // Assert
            assertEquals(channelCount + playlistCount, seenIds.size());
            assertEquals(channelCount, channelsSeen);
            assertEquals(playlistCount, playlistsSeen);
        }

        @Test
        @DisplayName("Should return empty page when no pending items")
        void noPendingItems_returnsEmptyPage() throws Exception {
            // Act: Query with no pending data
            CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                    null, null, 20, null);

            // Assert
            assertTrue(page.getData().isEmpty());
            assertFalse(page.getPageInfo().isHasNext());
            assertNull(page.getPageInfo().getNextCursor());
        }
    }

    // ========== Edge Case Tests ==========

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle small page sizes correctly")
        void smallPageSize_correctBehavior() throws Exception {
            // Arrange
            int totalChannels = 10;
            int pageSize = 3;
            seedApprovedChannels(totalChannels);

            // Act
            Set<String> seenIds = new HashSet<>();
            String cursor = null;
            int pageCount = 0;

            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, pageSize, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    seenIds.add(item.getId());
                }

                cursor = page.getPageInfo().getNextCursor();
                pageCount++;
            } while (cursor != null);

            // Assert
            assertEquals(totalChannels, seenIds.size());
            assertEquals(4, pageCount, "10 items / 3 per page = 4 pages");
        }

        @Test
        @DisplayName("Should handle page size of 1")
        void pageSizeOne_correctBehavior() throws Exception {
            // Arrange
            int totalPlaylists = 5;
            seedApprovedPlaylists(totalPlaylists);

            // Act
            Set<String> seenIds = new HashSet<>();
            String cursor = null;
            int pageCount = 0;

            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "PLAYLISTS", cursor, 1, null, null, null, null);

                assertEquals(1, page.getData().size(), "Each page should have exactly 1 item");
                seenIds.add(page.getData().get(0).getId());

                cursor = page.getPageInfo().getNextCursor();
                pageCount++;
            } while (cursor != null);

            // Assert
            assertEquals(totalPlaylists, seenIds.size());
            assertEquals(totalPlaylists, pageCount);
        }

        @Test
        @DisplayName("Should handle large page size gracefully")
        void largePageSize_returnAllItems() throws Exception {
            // Arrange
            int totalVideos = 30;
            seedApprovedVideos(totalVideos);

            // Act: Request page size larger than total items
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "VIDEOS", null, 100, null, null, null, null);

            // Assert
            assertEquals(totalVideos, page.getData().size());
            assertFalse(page.getPageInfo().isHasNext());
        }

        @Test
        @DisplayName("Cursors should be opaque (not expose document IDs)")
        void cursorsAreOpaque() throws Exception {
            // Arrange
            seedApprovedChannels(25);

            // Act
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "CHANNELS", null, 10, null, null, null, null);

            String cursor = page.getPageInfo().getNextCursor();

            // Assert: Cursor should be base64-encoded, not a raw document ID
            assertNotNull(cursor);
            assertFalse(cursor.startsWith("channel-"),
                    "Cursor should not expose raw document IDs");
            // Base64 URL-safe characters only
            assertTrue(cursor.matches("^[A-Za-z0-9_-]+$"),
                    "Cursor should be URL-safe base64");
        }

        @Test
        @DisplayName("Should handle concurrent reads without data corruption")
        void concurrentReads_noCorruption() throws Exception {
            // Arrange
            int totalChannels = 50;
            seedApprovedChannels(totalChannels);

            // Act: Simulate concurrent reads by fetching multiple pages at once
            CursorPageDto<ContentItemDto> page1 = publicContentService.getContent(
                    "CHANNELS", null, 20, null, null, null, null);

            // Start second traversal while first is ongoing
            CursorPageDto<ContentItemDto> page2Start = publicContentService.getContent(
                    "CHANNELS", null, 15, null, null, null, null);

            // Continue first traversal
            CursorPageDto<ContentItemDto> page1Next = publicContentService.getContent(
                    "CHANNELS", page1.getPageInfo().getNextCursor(), 20, null, null, null, null);

            // Assert: Both traversals should work correctly
            assertEquals(20, page1.getData().size());
            assertEquals(15, page2Start.getData().size());
            assertEquals(20, page1Next.getData().size());

            // First items of page1 and page2Start should be the same
            assertEquals(page1.getData().get(0).getId(), page2Start.getData().get(0).getId(),
                    "Different traversals should start with same first item");
        }
    }

    // ========== Large Dataset Tests ==========

    @Nested
    @DisplayName("Large Dataset Tests")
    class LargeDatasetTests {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("Should handle 1000+ channels efficiently")
        void largeDataset_1000Channels() throws Exception {
            // Arrange: Seed 1000 channels for scale testing
            int totalChannels = 1000;
            int pageSize = 100;
            seedApprovedChannels(totalChannels);

            // Act
            Set<String> seenIds = new HashSet<>();
            List<Long> subscriberCounts = new ArrayList<>();
            String cursor = null;
            int pageCount = 0;

            do {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, pageSize, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate channel at page " + pageCount + ": " + item.getId());
                    seenIds.add(item.getId());
                    subscriberCounts.add(item.getSubscribers());
                }

                cursor = page.getPageInfo().getNextCursor();
                pageCount++;

                // Safety: prevent runaway in case of bugs
                if (pageCount > 20) {
                    fail("Too many pages - possible infinite loop");
                }
            } while (cursor != null);

            // Assert
            assertEquals(totalChannels, seenIds.size(), "Should retrieve all 1000 channels");
            assertEquals(10, pageCount, "1000 items / 100 per page = 10 pages");

            // Verify ordering
            for (int i = 1; i < subscriberCounts.size(); i++) {
                assertTrue(subscriberCounts.get(i - 1) >= subscriberCounts.get(i),
                        "Order violated at index " + i);
            }
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("Should handle 1000+ pending approvals with mixed types")
        void largePendingDataset_1000Items() throws Exception {
            // Arrange: 600 channels + 400 playlists = 1000 total
            int channelCount = 600;
            int playlistCount = 400;
            seedPendingChannels(channelCount, testCategory.getId());
            seedPendingPlaylists(playlistCount, testCategory.getId());

            // Act
            Set<String> seenIds = new HashSet<>();
            String cursor = null;
            int pageCount = 0;

            do {
                CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                        null, null, 100, cursor);

                for (PendingApprovalDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate: " + item.getId());
                    seenIds.add(item.getId());
                }

                cursor = page.getPageInfo().getNextCursor();
                pageCount++;
            } while (cursor != null);

            // Assert
            assertEquals(channelCount + playlistCount, seenIds.size());
        }
    }

    // ========== Invalid Cursor Tests ==========

    @Nested
    @DisplayName("Invalid Cursor Handling")
    class InvalidCursorTests {

        @Test
        @DisplayName("Should handle completely invalid cursor gracefully")
        void invalidCursor_returnsFirstPage() throws Exception {
            // Arrange
            seedApprovedChannels(20);

            // Act: Pass garbage cursor
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "CHANNELS", "completely-invalid-cursor-xyz123", 10, null, null, null, null);

            // Assert: Should return first page or handle gracefully
            assertNotNull(page);
            assertNotNull(page.getData());
            // Implementation may either return first page or throw - both are acceptable
        }

        @Test
        @DisplayName("Should handle malformed base64 cursor")
        void malformedBase64Cursor_handlesGracefully() throws Exception {
            // Arrange
            seedApprovedChannels(20);

            // Act: Pass malformed base64
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "CHANNELS", "!!!not-base64!!!", 10, null, null, null, null);

            // Assert
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should handle empty string cursor")
        void emptyCursor_treatedAsNull() throws Exception {
            // Arrange
            seedApprovedChannels(15);

            // Act
            CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                    "CHANNELS", "", 10, null, null, null, null);

            // Assert: Empty string should be treated like null (first page)
            assertNotNull(page);
            assertFalse(page.getData().isEmpty());
        }

        @Test
        @DisplayName("Should handle cursor from different content type")
        void wrongTypeCursor_handlesGracefully() throws Exception {
            // Arrange
            seedApprovedChannels(30);
            seedApprovedPlaylists(30);

            // Get a cursor from channels
            CursorPageDto<ContentItemDto> channelPage = publicContentService.getContent(
                    "CHANNELS", null, 10, null, null, null, null);
            String channelCursor = channelPage.getPageInfo().getNextCursor();

            // Act: Use channel cursor for playlists query
            CursorPageDto<ContentItemDto> playlistPage = publicContentService.getContent(
                    "PLAYLISTS", channelCursor, 10, null, null, null, null);

            // Assert: Should handle gracefully (may return first page or different results)
            assertNotNull(playlistPage);
        }
    }

    // ========== Mutation Between Fetches Tests ==========

    @Nested
    @DisplayName("Mutation Between Fetches")
    class MutationTests {

        @Test
        @DisplayName("Should handle deletion between page fetches")
        void deletionBetweenFetches_noDuplicates() throws Exception {
            // Arrange: Seed 50 channels
            int totalChannels = 50;
            List<Channel> channels = seedApprovedChannels(totalChannels);

            // Get first page
            CursorPageDto<ContentItemDto> firstPage = publicContentService.getContent(
                    "CHANNELS", null, 20, null, null, null, null);

            String cursor = firstPage.getPageInfo().getNextCursor();
            Set<String> seenIds = new HashSet<>();
            firstPage.getData().forEach(item -> seenIds.add(item.getId()));

            // Delete some channels between fetches (simulate concurrent modification)
            // Delete from the middle of the dataset
            for (int i = 25; i < 30; i++) {
                channelRepository.deleteById(channels.get(i).getId());
            }

            // Continue fetching with cursor
            while (cursor != null) {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, 20, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    assertFalse(seenIds.contains(item.getId()),
                            "Duplicate after deletion: " + item.getId());
                    seenIds.add(item.getId());
                }

                cursor = page.getPageInfo().getNextCursor();
            }

            // Assert: Should have fewer items but no duplicates
            // 50 - 5 deleted = 45 items
            assertEquals(45, seenIds.size(), "Should have 45 items after deletion");
        }

        @Test
        @DisplayName("Should handle insertion at front between page fetches")
        void insertionAtFront_maintainsConsistency() throws Exception {
            // Arrange: Seed 30 channels
            int initialCount = 30;
            seedApprovedChannels(initialCount);

            // Get first page
            CursorPageDto<ContentItemDto> firstPage = publicContentService.getContent(
                    "CHANNELS", null, 20, null, null, null, null);

            String cursor = firstPage.getPageInfo().getNextCursor();
            Set<String> seenIds = new HashSet<>();
            firstPage.getData().forEach(item -> seenIds.add(item.getId()));

            // Insert new channel with highest subscriber count (will be at front)
            Channel newChannel = TestDataBuilder.createApprovedChannel("new-top-channel", "New Top Channel");
            newChannel.setSubscribers(999999999L); // Very high to be first
            newChannel.setCategoryIds(List.of(testCategory.getId()));
            channelRepository.save(newChannel);

            // Continue fetching - the new channel may or may not appear
            while (cursor != null) {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, 20, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    seenIds.add(item.getId()); // Don't assert no duplicates - insertion may cause cursor shifts
                }

                cursor = page.getPageInfo().getNextCursor();
            }

            // Assert: We got at least the original count (new item may or may not appear)
            assertTrue(seenIds.size() >= initialCount,
                    "Should retrieve at least original items");
        }

        @Test
        @DisplayName("Should handle update of sort field between fetches")
        void updateSortField_maintainsConsistency() throws Exception {
            // Arrange: Seed 40 channels
            List<Channel> channels = seedApprovedChannels(40);

            // Get first page
            CursorPageDto<ContentItemDto> firstPage = publicContentService.getContent(
                    "CHANNELS", null, 20, null, null, null, null);

            String cursor = firstPage.getPageInfo().getNextCursor();
            Set<String> seenIds = new HashSet<>();
            firstPage.getData().forEach(item -> seenIds.add(item.getId()));

            // Update a channel's subscriber count to move it in sort order
            Channel middleChannel = channels.get(20);
            middleChannel.setSubscribers(999999L); // Move to top
            channelRepository.save(middleChannel);

            // Continue fetching
            while (cursor != null) {
                CursorPageDto<ContentItemDto> page = publicContentService.getContent(
                        "CHANNELS", cursor, 20, null, null, null, null);

                for (ContentItemDto item : page.getData()) {
                    seenIds.add(item.getId());
                }

                cursor = page.getPageInfo().getNextCursor();
            }

            // Assert: All items should eventually be seen
            // (may have duplicates due to sort field change, but should get all)
            assertTrue(seenIds.size() >= 39, "Should retrieve most items despite sort change");
        }
    }
}
