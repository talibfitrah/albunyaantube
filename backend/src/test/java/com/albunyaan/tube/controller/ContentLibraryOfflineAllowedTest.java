package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Owner ruling 2026-09-27: every approved video is saveable offline unless an admin explicitly
 * set offlineAllowed=false. The Content Library toggle must show that EFFECTIVE state — a
 * never-toggled video (every production document) reads as allowed, not blocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentLibraryOfflineAllowedTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private com.google.cloud.firestore.Firestore firestore;
    @Mock private com.albunyaan.tube.config.FirestoreTimeoutProperties timeoutProperties;
    @Mock private com.albunyaan.tube.service.PublicContentCacheService publicContentCacheService;
    @Mock private com.albunyaan.tube.service.SortOrderService sortOrderService;
    @Mock private com.albunyaan.tube.service.TagEnrichmentService tagEnrichmentService;
    @Mock private com.albunyaan.tube.service.ImportGraduationService importGraduationService;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private ContentLibraryController controller;

    private Boolean listedFlag(Boolean stored) throws Exception {
        Video v = new Video("xc7keR2piUM");
        v.setId("video_xc7keR2piUM");
        v.setStatus("APPROVED");
        v.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1_000, 0));
        v.setOfflineAllowed(stored);
        when(videoRepository.findAll(anyInt())).thenReturn(List.of(v));
        return controller.getContent("video", "all", null, null, "newest", 0, 20)
                .getBody().content.get(0).offlineAllowed;
    }

    @Test
    void aNeverToggledVideoListsAsAllowed() throws Exception {
        assertEquals(Boolean.TRUE, listedFlag(null));
    }

    @Test
    void anExplicitAdminFalseListsAsBlocked() throws Exception {
        assertEquals(Boolean.FALSE, listedFlag(false));
    }

    @Test
    void anExplicitAdminTrueListsAsAllowed() throws Exception {
        assertEquals(Boolean.TRUE, listedFlag(true));
    }
}
