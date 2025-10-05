package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.ApprovalRequestDto;
import com.albunyaan.tube.dto.ApprovalResponseDto;
import com.albunyaan.tube.dto.PendingApprovalDto;
import com.albunyaan.tube.dto.RejectionRequestDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.service.ApprovalService;
import com.albunyaan.tube.util.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKEND-TEST-01: Approval Integration Tests
 *
 * Tests ApprovalService with real Firestore emulator.
 */
public class ApprovalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Category testCategory;

    @BeforeEach
    public void setUp() throws Exception {
        // Create a test category for use in tests
        testCategory = TestDataBuilder.createCategory("Quran");
        testCategory = categoryRepository.save(testCategory);
    }

    @Test
    public void testGetPendingApprovals_Empty() throws Exception {
        // When
        CursorPageDto<PendingApprovalDto> result = approvalService.getPendingApprovals(
                null, null, 20, null);

        // Then
        assertNotNull(result);
        assertEquals(0, result.getData().size());
    }

    @Test
    public void testGetPendingApprovals_WithChannels() throws Exception {
        // Given
        Channel channel1 = TestDataBuilder.createChannelWithCategory("UC123", "Test Channel 1", testCategory.getId());
        channel1.setStatus("PENDING");
        channelRepository.save(channel1);

        Channel channel2 = TestDataBuilder.createChannelWithCategory("UC456", "Test Channel 2", testCategory.getId());
        channel2.setStatus("PENDING");
        channelRepository.save(channel2);

        // When
        CursorPageDto<PendingApprovalDto> result = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().stream().allMatch(dto -> "CHANNEL".equals(dto.getType())));
    }

    @Test
    public void testGetPendingApprovals_WithPlaylists() throws Exception {
        // Given
        Playlist playlist1 = TestDataBuilder.createPlaylistWithCategory("PL123", "Test Playlist 1", testCategory.getId());
        playlist1.setStatus("PENDING");
        playlistRepository.save(playlist1);

        Playlist playlist2 = TestDataBuilder.createPlaylistWithCategory("PL456", "Test Playlist 2", testCategory.getId());
        playlist2.setStatus("PENDING");
        playlistRepository.save(playlist2);

        // When
        CursorPageDto<PendingApprovalDto> result = approvalService.getPendingApprovals(
                "PLAYLIST", null, 20, null);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().stream().allMatch(dto -> "PLAYLIST".equals(dto.getType())));
    }

    @Test
    public void testGetPendingApprovals_MixedTypes() throws Exception {
        // Given
        Channel channel = TestDataBuilder.createChannelWithCategory("UC123", "Test Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channelRepository.save(channel);

        Playlist playlist = TestDataBuilder.createPlaylistWithCategory("PL123", "Test Playlist", testCategory.getId());
        playlist.setStatus("PENDING");
        playlistRepository.save(playlist);

        // When
        CursorPageDto<PendingApprovalDto> result = approvalService.getPendingApprovals(
                null, null, 20, null);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().stream().anyMatch(dto -> "CHANNEL".equals(dto.getType())));
        assertTrue(result.getData().stream().anyMatch(dto -> "PLAYLIST".equals(dto.getType())));
    }

    @Test
    public void testApproveChannel() throws Exception {
        // Given
        Channel channel = TestDataBuilder.createChannelWithCategory("UC123", "Test Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel = channelRepository.save(channel);

        ApprovalRequestDto request = new ApprovalRequestDto("High quality content");

        // When
        ApprovalResponseDto response = approvalService.approve(
                channel.getId(), request, "test-admin", "Test Admin");

        // Then
        assertEquals("APPROVED", response.getStatus());
        assertEquals("test-admin", response.getReviewedBy());
        assertNotNull(response.getReviewedAt());

        // Verify the channel was updated
        Channel updated = channelRepository.findById(channel.getId()).orElse(null);
        assertNotNull(updated);
        assertEquals("APPROVED", updated.getStatus());
        assertEquals("test-admin", updated.getApprovedBy());
        assertNotNull(updated.getApprovalMetadata());
        assertEquals("High quality content", updated.getApprovalMetadata().getReviewNotes());
    }

    @Test
    public void testApprovePlaylist() throws Exception {
        // Given
        Playlist playlist = TestDataBuilder.createPlaylistWithCategory("PL123", "Test Playlist", testCategory.getId());
        playlist.setStatus("PENDING");
        playlist = playlistRepository.save(playlist);

        ApprovalRequestDto request = new ApprovalRequestDto("Excellent playlist");

        // When
        ApprovalResponseDto response = approvalService.approve(
                playlist.getId(), request, "test-admin", "Test Admin");

        // Then
        assertEquals("APPROVED", response.getStatus());
        assertEquals("test-admin", response.getReviewedBy());

        // Verify the playlist was updated
        Playlist updated = playlistRepository.findById(playlist.getId()).orElse(null);
        assertNotNull(updated);
        assertEquals("APPROVED", updated.getStatus());
        assertEquals("test-admin", updated.getApprovedBy());
        assertNotNull(updated.getApprovalMetadata());
    }

    @Test
    public void testRejectChannel() throws Exception {
        // Given
        Channel channel = TestDataBuilder.createChannel("UC123", "Test Channel");
        channel.setStatus("PENDING");
        channel = channelRepository.save(channel);

        RejectionRequestDto request = new RejectionRequestDto("LOW_QUALITY", "Content does not meet our standards");

        // When
        ApprovalResponseDto response = approvalService.reject(
                channel.getId(), request, "test-admin", "Test Admin");

        // Then
        assertEquals("REJECTED", response.getStatus());
        assertEquals("test-admin", response.getReviewedBy());

        // Verify the channel was updated
        Channel updated = channelRepository.findById(channel.getId()).orElse(null);
        assertNotNull(updated);
        assertEquals("REJECTED", updated.getStatus());
        assertNotNull(updated.getApprovalMetadata());
        assertEquals("LOW_QUALITY", updated.getApprovalMetadata().getRejectionReason());
    }

    @Test
    public void testRejectPlaylist() throws Exception {
        // Given
        Playlist playlist = TestDataBuilder.createPlaylist("PL123", "Test Playlist");
        playlist.setStatus("PENDING");
        playlist = playlistRepository.save(playlist);

        RejectionRequestDto request = new RejectionRequestDto("DUPLICATE", "Playlist already exists");

        // When
        ApprovalResponseDto response = approvalService.reject(
                playlist.getId(), request, "test-admin", "Test Admin");

        // Then
        assertEquals("REJECTED", response.getStatus());

        // Verify the playlist was updated
        Playlist updated = playlistRepository.findById(playlist.getId()).orElse(null);
        assertNotNull(updated);
        assertEquals("REJECTED", updated.getStatus());
        assertEquals("DUPLICATE", updated.getApprovalMetadata().getRejectionReason());
    }

    @Test
    public void testApprove_ItemNotFound() {
        // Given
        ApprovalRequestDto request = new ApprovalRequestDto("Test");

        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
                approvalService.approve("non-existent-id", request, "test-admin", "Test Admin")
        );
    }

    @Test
    public void testReject_ItemNotFound() {
        // Given
        RejectionRequestDto request = new RejectionRequestDto("OTHER", "Test");

        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
                approvalService.reject("non-existent-id", request, "test-admin", "Test Admin")
        );
    }

    @Test
    public void testApproveWithCategoryOverride() throws Exception {
        // Given
        Category newCategory = TestDataBuilder.createCategory("Hadith");
        newCategory = categoryRepository.save(newCategory);

        Channel channel = TestDataBuilder.createChannelWithCategory("UC123", "Test Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel = channelRepository.save(channel);

        ApprovalRequestDto request = new ApprovalRequestDto("Better fit in Hadith category");
        request.setCategoryOverride(newCategory.getId());

        // When
        approvalService.approve(channel.getId(), request, "test-admin", "Test Admin");

        // Then
        Channel updated = channelRepository.findById(channel.getId()).orElse(null);
        assertNotNull(updated);
        assertEquals(1, updated.getCategoryIds().size());
        assertEquals(newCategory.getId(), updated.getCategoryIds().get(0));
    }
}
