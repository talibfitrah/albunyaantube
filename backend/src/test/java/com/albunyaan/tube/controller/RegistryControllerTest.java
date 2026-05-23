package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.SubmitterNoteUpdateRequest;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BACKEND-REG-01: Unit tests for RegistryController
 */
@ExtendWith(MockitoExtension.class)
class RegistryControllerTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private com.albunyaan.tube.repository.VideoRepository videoRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private com.albunyaan.tube.service.PublicContentCacheService publicContentCacheService;

    @Mock
    private com.albunyaan.tube.service.SortOrderService sortOrderService;

    @Mock
    private com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache;

    @InjectMocks
    private RegistryController registryController;

    private FirebaseUserDetails adminUser;
    private FirebaseUserDetails moderatorUser;
    private Channel testChannel;
    private Playlist testPlaylist;

    @BeforeEach
    void setUp() {
        // Create admin user
        adminUser = new FirebaseUserDetails("admin-uid", "admin@test.com", "admin");

        // Create moderator user
        moderatorUser = new FirebaseUserDetails("mod-uid", "mod@test.com", "moderator");

        // Create test channel
        testChannel = new Channel("UC-test-channel");
        testChannel.setId("channel-123");
        testChannel.setName("Test Channel");
        testChannel.setStatus("APPROVED");
        testChannel.setSubscribers(1000L);

        // Create test playlist
        testPlaylist = new Playlist("PL-test-playlist");
        testPlaylist.setId("playlist-123");
        testPlaylist.setTitle("Test Playlist");
        testPlaylist.setStatus("APPROVED");
        testPlaylist.setItemCount(10);
    }

    // ===== CHANNEL TESTS =====

    @Test
    void getAllChannels_shouldReturnAllChannels() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Channel> channels = Arrays.asList(testChannel);
        when(channelRepository.findAll(anyInt())).thenReturn(channels);

        // Act
        ResponseEntity<List<Channel>> response = registryController.getAllChannels(100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Channel", response.getBody().get(0).getName());
        verify(channelRepository).findAll(100);
    }

    @Test
    void getChannelsByStatus_shouldReturnChannelsWithStatus() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Channel> channels = Arrays.asList(testChannel);
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(channels);

        // Act
        ResponseEntity<List<Channel>> response = registryController.getChannelsByStatus("approved", 100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(channelRepository).findByStatus("APPROVED", 100);
    }

    @Test
    void getChannelById_shouldReturnChannel_whenExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Channel> response = registryController.getChannelById("channel-123");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Test Channel", response.getBody().getName());
    }

    @Test
    void getChannelById_shouldReturn404_whenNotFound() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Channel> response = registryController.getChannelById("nonexistent");

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void addChannel_shouldAutoApprove_whenSubmittedByAdmin() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        // Set status to null to trigger auto-approval logic for admins
        newChannel.setStatus(null);
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newChannel.getStatus());
        assertEquals("admin-uid", newChannel.getSubmittedBy());
        assertEquals("admin-uid", newChannel.getApprovedBy());
        verify(channelRepository).save(newChannel);
        verify(auditLogService).log(eq("channel_added_to_registry"), eq("channel"), any(), eq(adminUser));
    }

    @Test
    void addChannel_shouldPendApproval_whenSubmittedByModerator() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, moderatorUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newChannel.getStatus());
        assertEquals("mod-uid", newChannel.getSubmittedBy());
        assertNull(newChannel.getApprovedBy());
    }

    @Test
    void addChannel_moderatorCannotSelfApprove_statusForcedToPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange - moderator sends status: APPROVED in request body
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        newChannel.setStatus("APPROVED");
        newChannel.setApprovedBy("fake-admin-uid");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, moderatorUser);

        // Assert - status must be PENDING, approvedBy must be cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newChannel.getStatus());
        assertNull(newChannel.getApprovedBy());
        assertEquals("mod-uid", newChannel.getSubmittedBy());
    }

    @Test
    void addChannel_adminCanExplicitlySetStatus() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange - admin sends status: PENDING explicitly
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        newChannel.setStatus("PENDING");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, adminUser);

        // Assert - admin's explicit PENDING status is respected, approvedBy cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newChannel.getStatus());
        assertEquals("admin-uid", newChannel.getSubmittedBy());
        assertNull(newChannel.getApprovedBy());
    }

    @Test
    void addChannel_shouldReturnConflict_whenChannelAlreadyExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findByYoutubeId("UC-test-channel")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(testChannel, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(channelRepository, never()).save(any());
    }

    @Test
    void updateChannel_shouldUpdateExistingChannel() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Channel updates = new Channel("UC-test-channel");
        updates.setName("Updated Name");
        updates.setDescription("Updated Description");
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        // Act
        ResponseEntity<Channel> response = registryController.updateChannel("channel-123", updates, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated Name", testChannel.getName());
        assertEquals("Updated Description", testChannel.getDescription());
        verify(channelRepository).save(testChannel);
    }

    @Test
    void toggleChannelStatus_shouldToggleFromApprovedToPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        testChannel.setStatus("APPROVED");
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        // Act
        ResponseEntity<Channel> response = registryController.toggleChannelStatus("channel-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PENDING", testChannel.getStatus());
    }

    @Test
    void toggleChannelStatus_shouldToggleFromPendingToApproved() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        testChannel.setStatus("PENDING");
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        // Act
        ResponseEntity<Channel> response = registryController.toggleChannelStatus("channel-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("APPROVED", testChannel.getStatus());
        assertEquals("admin-uid", testChannel.getApprovedBy());
    }

    @Test
    void deleteChannel_shouldDeleteChannel_whenExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Void> response = registryController.deleteChannel("channel-123", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(channelRepository).deleteById("channel-123");
        verify(auditLogService).log(eq("channel_deleted_from_registry"), eq("channel"), eq("channel-123"), eq(adminUser));
    }

    // ===== PLAYLIST TESTS =====

    @Test
    void getAllPlaylists_shouldReturnAllPlaylists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Playlist> playlists = Arrays.asList(testPlaylist);
        when(playlistRepository.findAll(anyInt())).thenReturn(playlists);

        // Act
        ResponseEntity<List<Playlist>> response = registryController.getAllPlaylists(100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Playlist", response.getBody().get(0).getTitle());
    }

    @Test
    void getPlaylistsByStatus_shouldReturnPlaylistsWithStatus() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Playlist> playlists = Arrays.asList(testPlaylist);
        when(playlistRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(playlists);

        // Act
        ResponseEntity<List<Playlist>> response = registryController.getPlaylistsByStatus("approved", 100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(playlistRepository).findByStatus("APPROVED", 100);
    }

    @Test
    void addPlaylist_shouldAutoApprove_whenSubmittedByAdmin() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        // Set status to null to trigger auto-approval logic for admins
        newPlaylist.setStatus(null);
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newPlaylist.getStatus());
        assertEquals("admin-uid", newPlaylist.getSubmittedBy());
        assertEquals("admin-uid", newPlaylist.getApprovedBy());
    }

    @Test
    void addPlaylist_moderatorCannotSelfApprove_statusForcedToPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange - moderator sends status: APPROVED in request body
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        newPlaylist.setStatus("APPROVED");
        newPlaylist.setApprovedBy("fake-admin-uid");
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, moderatorUser);

        // Assert - status must be PENDING, approvedBy must be cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newPlaylist.getStatus());
        assertNull(newPlaylist.getApprovedBy());
        assertEquals("mod-uid", newPlaylist.getSubmittedBy());
    }

    @Test
    void addPlaylist_shouldReturnConflict_whenPlaylistAlreadyExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(playlistRepository.findByYoutubeId("PL-test-playlist")).thenReturn(Optional.of(testPlaylist));

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(testPlaylist, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(playlistRepository, never()).save(any());
    }

    @Test
    void updatePlaylist_shouldUpdateExistingPlaylist() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Playlist updates = new Playlist("PL-test-playlist");
        updates.setTitle("Updated Title");
        updates.setDescription("Updated Description");
        when(playlistRepository.findById("playlist-123")).thenReturn(Optional.of(testPlaylist));
        when(playlistRepository.save(any(Playlist.class))).thenReturn(testPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.updatePlaylist("playlist-123", updates, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated Title", testPlaylist.getTitle());
        assertEquals("Updated Description", testPlaylist.getDescription());
        verify(playlistRepository).save(testPlaylist);
    }

    @Test
    void togglePlaylistStatus_shouldToggleBetweenApprovedAndPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        testPlaylist.setStatus("APPROVED");
        when(playlistRepository.findById("playlist-123")).thenReturn(Optional.of(testPlaylist));
        when(playlistRepository.save(any(Playlist.class))).thenReturn(testPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.togglePlaylistStatus("playlist-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PENDING", testPlaylist.getStatus());
    }

    @Test
    void deletePlaylist_shouldDeletePlaylist_whenExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(playlistRepository.findById("playlist-123")).thenReturn(Optional.of(testPlaylist));

        // Act
        ResponseEntity<Void> response = registryController.deletePlaylist("playlist-123", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(playlistRepository).deleteById("playlist-123");
    }

    // ===== VIDEO TESTS =====

    @Test
    void addVideo_shouldAutoApprove_whenSubmittedByAdmin() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus(null);
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newVideo.getStatus());
        assertEquals("admin-uid", newVideo.getSubmittedBy());
        assertEquals("admin-uid", newVideo.getApprovedBy());
        verify(videoRepository).save(newVideo);
    }

    @Test
    void addVideo_shouldPendApproval_whenSubmittedByModerator() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, moderatorUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newVideo.getStatus());
        assertEquals("mod-uid", newVideo.getSubmittedBy());
        assertNull(newVideo.getApprovedBy());
    }

    @Test
    void addVideo_moderatorCannotSelfApprove_statusForcedToPending() throws Exception {
        // Arrange - moderator sends status: APPROVED in request body
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus("APPROVED");
        newVideo.setApprovedBy("fake-admin-uid");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, moderatorUser);

        // Assert - status must be PENDING, approvedBy must be cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newVideo.getStatus());
        assertNull(newVideo.getApprovedBy());
        assertEquals("mod-uid", newVideo.getSubmittedBy());
    }

    @Test
    void addVideo_adminCanExplicitlySetStatus() throws Exception {
        // Arrange - admin sends status: PENDING explicitly
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus("PENDING");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, adminUser);

        // Assert - admin's explicit PENDING status is respected, approvedBy cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newVideo.getStatus());
        assertEquals("admin-uid", newVideo.getSubmittedBy());
        assertNull(newVideo.getApprovedBy());
    }

    @Test
    void addVideo_adminExplicitApproved_setsApprovedBy() throws Exception {
        // Arrange - admin explicitly sends APPROVED
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus("APPROVED");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newVideo.getStatus());
        assertEquals("admin-uid", newVideo.getApprovedBy());
    }

    @Test
    void addVideo_shouldReturnConflict_whenVideoAlreadyExists() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video existingVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.of(existingVideo));

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(existingVideo, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(videoRepository, never()).save(any());
    }

    @Test
    void toggleVideoStatus_shouldToggleFromApprovedToPending() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video video = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        video.setId("video-123");
        video.setStatus("APPROVED");
        when(videoRepository.findById("video-123")).thenReturn(Optional.of(video));
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(video);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.toggleVideoStatus("video-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PENDING", video.getStatus());
    }

    @Test
    void toggleVideoStatus_shouldToggleFromPendingToApproved() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video video = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        video.setId("video-123");
        video.setStatus("PENDING");
        when(videoRepository.findById("video-123")).thenReturn(Optional.of(video));
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(video);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.toggleVideoStatus("video-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("APPROVED", video.getStatus());
        assertEquals("admin-uid", video.getApprovedBy());
    }

    @Test
    void deleteVideo_shouldDeleteVideo_whenExists() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video video = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        video.setId("video-123");
        when(videoRepository.findById("video-123")).thenReturn(Optional.of(video));

        // Act
        ResponseEntity<Void> response = registryController.deleteVideo("video-123", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(videoRepository).deleteById("video-123");
    }

    @Test
    void deleteVideo_shouldReturn404_whenNotFound() throws Exception {
        // Arrange
        when(videoRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Void> response = registryController.deleteVideo("nonexistent", adminUser);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(videoRepository, never()).deleteById(any());
    }

    // ===== PLAYLIST ADDITIONAL SECURITY TESTS =====

    @Test
    void addPlaylist_adminCanExplicitlySetStatus() throws Exception {
        // Arrange - admin sends status: PENDING explicitly
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        newPlaylist.setStatus("PENDING");
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, adminUser);

        // Assert - admin's explicit PENDING status is respected, approvedBy cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newPlaylist.getStatus());
        assertEquals("admin-uid", newPlaylist.getSubmittedBy());
        assertNull(newPlaylist.getApprovedBy());
    }

    @Test
    void addPlaylist_adminExplicitApproved_setsApprovedBy() throws Exception {
        // Arrange - admin explicitly sends APPROVED
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        newPlaylist.setStatus("APPROVED");
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newPlaylist.getStatus());
        assertEquals("admin-uid", newPlaylist.getApprovedBy());
    }

    // ===== CHANNEL ADDITIONAL SECURITY TESTS =====

    @Test
    void addChannel_adminExplicitApproved_setsApprovedBy() throws Exception {
        // Arrange - admin explicitly sends APPROVED
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        newChannel.setStatus("APPROVED");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newChannel.getStatus());
        assertEquals("admin-uid", newChannel.getApprovedBy());
    }

    // ===== CROSS-ROLE CONSISTENCY TESTS =====

    @Test
    void allEntityTypes_moderatorAlwaysGetsPending() throws Exception {
        // Verify consistent RBAC behavior across all 3 entity types

        // Channel
        Channel ch = new Channel("UC-ch");
        ch.setName("Ch");
        ch.setStatus("APPROVED");
        when(channelRepository.findByYoutubeId("UC-ch")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(ch);
        registryController.addChannel(ch, moderatorUser);
        assertEquals("PENDING", ch.getStatus());
        assertNull(ch.getApprovedBy());

        // Playlist
        Playlist pl = new Playlist("PL-pl");
        pl.setTitle("Pl");
        pl.setStatus("APPROVED");
        when(playlistRepository.findByYoutubeId("PL-pl")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(pl);
        registryController.addPlaylist(pl, moderatorUser);
        assertEquals("PENDING", pl.getStatus());
        assertNull(pl.getApprovedBy());

        // Video
        com.albunyaan.tube.model.Video vid = new com.albunyaan.tube.model.Video("vid-1");
        vid.setTitle("Vid");
        vid.setStatus("APPROVED");
        when(videoRepository.findByYoutubeId("vid-1")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(vid);
        registryController.addVideo(vid, moderatorUser);
        assertEquals("PENDING", vid.getStatus());
        assertNull(vid.getApprovedBy());
    }

    @Test
    void allEntityTypes_adminAutoApprovesWhenNoStatus() throws Exception {
        // Verify consistent auto-approve behavior across all 3 entity types

        // Channel
        Channel ch = new Channel("UC-ch");
        ch.setName("Ch");
        ch.setStatus(null);
        when(channelRepository.findByYoutubeId("UC-ch")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(ch);
        registryController.addChannel(ch, adminUser);
        assertEquals("APPROVED", ch.getStatus());
        assertEquals("admin-uid", ch.getApprovedBy());

        // Playlist
        Playlist pl = new Playlist("PL-pl");
        pl.setTitle("Pl");
        pl.setStatus(null);
        when(playlistRepository.findByYoutubeId("PL-pl")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(pl);
        registryController.addPlaylist(pl, adminUser);
        assertEquals("APPROVED", pl.getStatus());
        assertEquals("admin-uid", pl.getApprovedBy());

        // Video
        com.albunyaan.tube.model.Video vid = new com.albunyaan.tube.model.Video("vid-1");
        vid.setTitle("Vid");
        vid.setStatus(null);
        when(videoRepository.findByYoutubeId("vid-1")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(vid);
        registryController.addVideo(vid, adminUser);
        assertEquals("APPROVED", vid.getStatus());
        assertEquals("admin-uid", vid.getApprovedBy());
    }

    // ===== submitterNote sanitization on POST =====

    @Test
    void addChannel_blankSubmitterNote_storedAsNull() throws Exception {
        Channel ch = new Channel("UC-blank-note");
        ch.setName("X");
        ch.setSubmitterNote("   \n  "); // blanks only
        when(channelRepository.findByYoutubeId("UC-blank-note")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        registryController.addChannel(ch, moderatorUser);

        assertNull(ch.getSubmitterNote(), "blank-only notes should be normalised to null");
    }

    @Test
    void addChannel_submitterNoteTooLong_returns400() throws Exception {
        Channel ch = new Channel("UC-long-note");
        ch.setName("X");
        ch.setSubmitterNote("x".repeat(RegistryController.MAX_SUBMITTER_NOTE_LEN + 1));
        when(channelRepository.findByYoutubeId("UC-long-note")).thenReturn(Optional.empty());

        ResponseEntity<Channel> resp = registryController.addChannel(ch, moderatorUser);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(channelRepository, never()).save(any(Channel.class));
    }

    @Test
    void addChannel_validSubmitterNote_isTrimmedAndStored() throws Exception {
        Channel ch = new Channel("UC-trimmed");
        ch.setName("X");
        ch.setSubmitterNote("  hello world  ");
        when(channelRepository.findByYoutubeId("UC-trimmed")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        registryController.addChannel(ch, moderatorUser);

        assertEquals("hello world", ch.getSubmitterNote());
    }

    @Test
    void addChannel_resubmitAfterRequestChanges_preservesExistingNote_whenBodyHasNoNote() throws Exception {
        Channel existing = new Channel("UC-resub-keep");
        existing.setId("rsk-1");
        existing.setStatus("REQUEST_CHANGES");
        existing.setSubmittedBy(moderatorUser.getUid());
        existing.setSubmitterNote("my earlier note that should survive");
        when(channelRepository.findByYoutubeId("UC-resub-keep")).thenReturn(Optional.of(existing));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("REQUEST_CHANGES"))).thenAnswer(inv -> inv.getArgument(0));

        Channel resubmit = new Channel("UC-resub-keep");
        resubmit.setName("Y");
        resubmit.setSubmitterNote(null); // client did not retype the note

        ResponseEntity<Channel> resp = registryController.addChannel(resubmit, moderatorUser);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("PENDING", existing.getStatus());
        assertEquals("my earlier note that should survive", existing.getSubmitterNote(),
            "resubmit body without a note must not wipe the previously-stored note");
    }

    @Test
    void addChannel_resubmitAfterRequestChanges_overwritesNote_whenBodyHasNote() throws Exception {
        Channel existing = new Channel("UC-resub-update");
        existing.setId("rsu-1");
        existing.setStatus("REQUEST_CHANGES");
        existing.setSubmittedBy(moderatorUser.getUid());
        existing.setSubmitterNote("old note");
        when(channelRepository.findByYoutubeId("UC-resub-update")).thenReturn(Optional.of(existing));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("REQUEST_CHANGES"))).thenAnswer(inv -> inv.getArgument(0));

        Channel resubmit = new Channel("UC-resub-update");
        resubmit.setName("Y");
        resubmit.setSubmitterNote("updated note text");

        registryController.addChannel(resubmit, moderatorUser);

        assertEquals("updated note text", existing.getSubmitterNote());
    }

    // ===== PATCH /channels/{id}/submitter-note =====

    @Test
    void updateChannelSubmitterNote_happyPath_pendingOwnedByCaller() throws Exception {
        Channel existing = new Channel("UC-own");
        existing.setId("c-1");
        existing.setStatus("PENDING");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("c-1")).thenReturn(Optional.of(existing));
        // Cubic R3 P1: controller uses saveIfStatus to close the TOCTOU window.
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING"))).thenAnswer(inv -> inv.getArgument(0));

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("Halal history channel — well-researched.");

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("c-1", body, moderatorUser);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        assertEquals("Halal history channel — well-researched.", existing.getSubmitterNote());
        verify(channelRepository).saveIfStatus(existing, "PENDING");
        verify(auditLogService).log(eq("channel_submitter_note_updated"), eq("channel"), eq("c-1"), eq(moderatorUser));
    }

    @Test
    void updateChannelSubmitterNote_returns403_whenCallerIsNotSubmitter() throws Exception {
        Channel existing = new Channel("UC-other");
        existing.setId("c-2");
        existing.setStatus("PENDING");
        existing.setSubmittedBy("someone-else-uid");
        when(channelRepository.findById("c-2")).thenReturn(Optional.of(existing));

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("tampering attempt");

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("c-2", body, moderatorUser);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(channelRepository, never()).save(any(Channel.class));
    }

    @Test
    void updateChannelSubmitterNote_returns409_whenAlreadyApproved() throws Exception {
        Channel existing = new Channel("UC-approved");
        existing.setId("c-3");
        existing.setStatus("APPROVED");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("c-3")).thenReturn(Optional.of(existing));

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("too late");

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("c-3", body, moderatorUser);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        verify(channelRepository, never()).save(any(Channel.class));
    }

    @Test
    void updateChannelSubmitterNote_returns409_whenAlreadyRejected() throws Exception {
        Channel existing = new Channel("UC-rejected");
        existing.setId("c-4");
        existing.setStatus("REJECTED");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("c-4")).thenReturn(Optional.of(existing));

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("appeal");

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("c-4", body, moderatorUser);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void updateChannelSubmitterNote_allowsEditing_whenRequestChanges() throws Exception {
        Channel existing = new Channel("UC-rc");
        existing.setId("c-5");
        existing.setStatus("REQUEST_CHANGES");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("c-5")).thenReturn(Optional.of(existing));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("REQUEST_CHANGES"))).thenAnswer(inv -> inv.getArgument(0));

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("Added Arabic subtitles as requested");

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("c-5", body, moderatorUser);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        assertEquals("Added Arabic subtitles as requested", existing.getSubmitterNote());
    }

    @Test
    void updateChannelSubmitterNote_returns404_whenMissing() throws Exception {
        when(channelRepository.findById("missing")).thenReturn(Optional.empty());

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("anything");

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("missing", body, moderatorUser);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void updateChannelSubmitterNote_returns400_whenTooLong() throws Exception {
        Channel existing = new Channel("UC-pending");
        existing.setId("c-6");
        existing.setStatus("PENDING");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("c-6")).thenReturn(Optional.of(existing));

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("x".repeat(RegistryController.MAX_SUBMITTER_NOTE_LEN + 1));

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("c-6", body, moderatorUser);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(channelRepository, never()).save(any(Channel.class));
    }

    @Test
    void updateChannelSubmitterNote_blankClearsTheNote() throws Exception {
        Channel existing = new Channel("UC-clear");
        existing.setId("c-7");
        existing.setStatus("PENDING");
        existing.setSubmittedBy(moderatorUser.getUid());
        existing.setSubmitterNote("previous note");
        when(channelRepository.findById("c-7")).thenReturn(Optional.of(existing));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING"))).thenAnswer(inv -> inv.getArgument(0));

        SubmitterNoteUpdateRequest body = new SubmitterNoteUpdateRequest();
        body.setSubmitterNote("   "); // blanks only

        ResponseEntity<Void> resp = registryController.updateChannelSubmitterNote("c-7", body, moderatorUser);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        assertNull(existing.getSubmitterNote(), "blank submission should clear the note");
    }

    // ===== DELETE /channels/{id}/submission =====

    @Test
    void deleteOwnChannelSubmission_happyPath_pendingOwnedByCaller() throws Exception {
        Channel existing = new Channel("UC-del");
        existing.setId("d-1");
        existing.setStatus("PENDING");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("d-1")).thenReturn(Optional.of(existing));

        ResponseEntity<Void> resp = registryController.deleteOwnChannelSubmission("d-1", moderatorUser);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        // Cubic R3 P1+P2: controller now uses TOCTOU-safe delete and no longer evicts
        // the public cache for submitter-owned (PENDING/REQUEST_CHANGES) rows that
        // never appear in the public cache.
        verify(channelRepository).deleteByIdIfStatusIn(eq("d-1"), any());
        verify(publicContentCacheService, never()).evictPublicContentCaches();
        verify(auditLogService).log(eq("channel_submission_deleted"), eq("channel"), eq("d-1"), eq(moderatorUser));
    }

    @Test
    void deleteOwnChannelSubmission_returns403_whenCallerIsNotSubmitter() throws Exception {
        Channel existing = new Channel("UC-del-403");
        existing.setId("d-2");
        existing.setStatus("PENDING");
        existing.setSubmittedBy("someone-else-uid");
        when(channelRepository.findById("d-2")).thenReturn(Optional.of(existing));

        ResponseEntity<Void> resp = registryController.deleteOwnChannelSubmission("d-2", moderatorUser);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(channelRepository, never()).deleteById(any());
    }

    @Test
    void deleteOwnChannelSubmission_returns409_whenAlreadyApproved() throws Exception {
        Channel existing = new Channel("UC-del-409");
        existing.setId("d-3");
        existing.setStatus("APPROVED");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("d-3")).thenReturn(Optional.of(existing));

        ResponseEntity<Void> resp = registryController.deleteOwnChannelSubmission("d-3", moderatorUser);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        verify(channelRepository, never()).deleteById(any());
    }

    @Test
    void deleteOwnChannelSubmission_returns404_whenMissing() throws Exception {
        when(channelRepository.findById("nope")).thenReturn(Optional.empty());

        ResponseEntity<Void> resp = registryController.deleteOwnChannelSubmission("nope", moderatorUser);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void deleteOwnChannelSubmission_allowed_whenRequestChanges() throws Exception {
        Channel existing = new Channel("UC-del-rc");
        existing.setId("d-4");
        existing.setStatus("REQUEST_CHANGES");
        existing.setSubmittedBy(moderatorUser.getUid());
        when(channelRepository.findById("d-4")).thenReturn(Optional.of(existing));

        ResponseEntity<Void> resp = registryController.deleteOwnChannelSubmission("d-4", moderatorUser);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(channelRepository).deleteByIdIfStatusIn(eq("d-4"), any());
    }

    @Test
    void addChannel_thumbnailUrl_withUserinfo_nulledBeforeSave() throws Exception {
        // Cubic R3 finding #8: `https://attacker@i.ytimg.com/...` has
        // URI.getHost() == "i.ytimg.com" which passes the allowlist, but
        // userinfo gets persisted into Firestore and rendered to admins
        // in the registry UI. Browser ignores userinfo on <img src> so
        // SSRF/exfil is benign at fetch time — purely a data-hygiene fix.
        Channel newChannel = new Channel("UC-tn-userinfo");
        newChannel.setName("Tn Userinfo");
        newChannel.setThumbnailUrl("https://attacker.example@i.ytimg.com/vi/x/hqdefault.jpg");
        when(channelRepository.findByYoutubeId("UC-tn-userinfo")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        registryController.addChannel(newChannel, adminUser);

        // Even though i.ytimg.com is on the allowlist, the userinfo
        // component should disqualify the URL — the sanitiser returns null
        // so the stored Channel doc never carries the attacker host.
        assertNull(newChannel.getThumbnailUrl(),
                "URI with userinfo must be nulled — userinfo persists into Firestore otherwise");
    }

    @Test
    void addChannel_thumbnailUrl_rejectedByAllowlist_nulledBeforeSave() throws Exception {
        // Single-add must enforce the same thumbnail-host allowlist as the
        // bulk path. An attacker-hosted thumbnailUrl (or any host outside
        // ytimg.com / googleusercontent.com) must be nulled before save.
        Channel newChannel = new Channel("UC-tn-evil");
        newChannel.setName("Tn Evil");
        newChannel.setThumbnailUrl("https://attacker.example/track.gif");
        when(channelRepository.findByYoutubeId("UC-tn-evil")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        registryController.addChannel(newChannel, adminUser);

        // Sanitizer returns null for non-allowlisted hosts; the channel saved
        // to Firestore must carry null, never the attacker URL.
        assertNull(newChannel.getThumbnailUrl(),
                "non-allowlisted thumbnail must be nulled before save");
    }

    @Test
    void addChannel_thumbnailUrl_allowlistedHost_preservedThroughSave() throws Exception {
        Channel newChannel = new Channel("UC-tn-ok");
        newChannel.setName("Tn Ok");
        newChannel.setThumbnailUrl("https://i.ytimg.com/vi/xyz/hqdefault.jpg");
        when(channelRepository.findByYoutubeId("UC-tn-ok")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        registryController.addChannel(newChannel, adminUser);

        // Allowlisted host passes through unchanged.
        assertEquals("https://i.ytimg.com/vi/xyz/hqdefault.jpg", newChannel.getThumbnailUrl());
    }

    @Test
    void addPlaylist_thumbnailUrl_rejectedByAllowlist_nulledBeforeSave() throws Exception {
        com.albunyaan.tube.model.Playlist newPlaylist = new com.albunyaan.tube.model.Playlist("PL-tn-evil");
        newPlaylist.setTitle("PL Evil");
        newPlaylist.setThumbnailUrl("javascript:alert(1)");
        when(playlistRepository.findByYoutubeId("PL-tn-evil")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(com.albunyaan.tube.model.Playlist.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        registryController.addPlaylist(newPlaylist, adminUser);

        assertNull(newPlaylist.getThumbnailUrl(),
                "javascript: scheme must be nulled — sanitizer rejects non-https");
    }

    @Test
    void addVideo_thumbnailUrl_rejectedByAllowlist_nulledBeforeSave() throws Exception {
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("V-tn-evil");
        newVideo.setTitle("V Evil");
        newVideo.setThumbnailUrl("https://evil.example/track.svg");
        when(videoRepository.findByYoutubeId("V-tn-evil")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        registryController.addVideo(newVideo, adminUser);

        assertNull(newVideo.getThumbnailUrl(),
                "non-allowlisted thumbnail must be nulled before save");
    }
}

