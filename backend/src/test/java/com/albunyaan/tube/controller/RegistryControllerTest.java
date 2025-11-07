package com.albunyaan.tube.controller;

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
    private AuditLogService auditLogService;

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
    void getAllChannels_shouldReturnAllChannels() throws ExecutionException, InterruptedException {
        // Arrange
        List<Channel> channels = Arrays.asList(testChannel);
        when(channelRepository.findAll()).thenReturn(channels);

        // Act
        ResponseEntity<List<Channel>> response = registryController.getAllChannels();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Channel", response.getBody().get(0).getName());
        verify(channelRepository).findAll();
    }

    @Test
    void getChannelsByStatus_shouldReturnChannelsWithStatus() throws ExecutionException, InterruptedException {
        // Arrange
        List<Channel> channels = Arrays.asList(testChannel);
        when(channelRepository.findByStatus("APPROVED")).thenReturn(channels);

        // Act
        ResponseEntity<List<Channel>> response = registryController.getChannelsByStatus("approved");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(channelRepository).findByStatus("APPROVED");
    }

    @Test
    void getChannelById_shouldReturnChannel_whenExists() throws ExecutionException, InterruptedException {
        // Arrange
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Channel> response = registryController.getChannelById("channel-123");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Test Channel", response.getBody().getName());
    }

    @Test
    void getChannelById_shouldReturn404_whenNotFound() throws ExecutionException, InterruptedException {
        // Arrange
        when(channelRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Channel> response = registryController.getChannelById("nonexistent");

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void addChannel_shouldAutoApprove_whenSubmittedByAdmin() throws ExecutionException, InterruptedException {
        // Arrange
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
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
    void addChannel_shouldPendApproval_whenSubmittedByModerator() throws ExecutionException, InterruptedException {
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
    void addChannel_shouldReturnConflict_whenChannelAlreadyExists() throws ExecutionException, InterruptedException {
        // Arrange
        when(channelRepository.findByYoutubeId("UC-test-channel")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(testChannel, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(channelRepository, never()).save(any());
    }

    @Test
    void updateChannel_shouldUpdateExistingChannel() throws ExecutionException, InterruptedException {
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
    void toggleChannelStatus_shouldToggleFromApprovedToPending() throws ExecutionException, InterruptedException {
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
    void toggleChannelStatus_shouldToggleFromPendingToApproved() throws ExecutionException, InterruptedException {
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
    void deleteChannel_shouldDeleteChannel_whenExists() throws ExecutionException, InterruptedException {
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
    void getAllPlaylists_shouldReturnAllPlaylists() throws ExecutionException, InterruptedException {
        // Arrange
        List<Playlist> playlists = Arrays.asList(testPlaylist);
        when(playlistRepository.findAll()).thenReturn(playlists);

        // Act
        ResponseEntity<List<Playlist>> response = registryController.getAllPlaylists();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Playlist", response.getBody().get(0).getTitle());
    }

    @Test
    void getPlaylistsByStatus_shouldReturnPlaylistsWithStatus() throws ExecutionException, InterruptedException {
        // Arrange
        List<Playlist> playlists = Arrays.asList(testPlaylist);
        when(playlistRepository.findByStatus("APPROVED")).thenReturn(playlists);

        // Act
        ResponseEntity<List<Playlist>> response = registryController.getPlaylistsByStatus("approved");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(playlistRepository).findByStatus("APPROVED");
    }

    @Test
    void addPlaylist_shouldAutoApprove_whenSubmittedByAdmin() throws ExecutionException, InterruptedException {
        // Arrange
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
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
    void addPlaylist_shouldReturnConflict_whenPlaylistAlreadyExists() throws ExecutionException, InterruptedException {
        // Arrange
        when(playlistRepository.findByYoutubeId("PL-test-playlist")).thenReturn(Optional.of(testPlaylist));

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(testPlaylist, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(playlistRepository, never()).save(any());
    }

    @Test
    void updatePlaylist_shouldUpdateExistingPlaylist() throws ExecutionException, InterruptedException {
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
    void togglePlaylistStatus_shouldToggleBetweenApprovedAndPending() throws ExecutionException, InterruptedException {
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
    void deletePlaylist_shouldDeletePlaylist_whenExists() throws ExecutionException, InterruptedException {
        // Arrange
        when(playlistRepository.findById("playlist-123")).thenReturn(Optional.of(testPlaylist));

        // Act
        ResponseEntity<Void> response = registryController.deletePlaylist("playlist-123", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(playlistRepository).deleteById("playlist-123");
    }
}

