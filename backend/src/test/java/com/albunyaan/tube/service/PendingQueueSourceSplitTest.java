package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.PendingApprovalDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The review queue holds two kinds of work that are reviewed differently: content a moderator
 * added through the dashboard, and bulk imports sent from people's phones. Every submission
 * records which path it came in on, so the two can be separated cleanly.
 *
 * <p>The split is done here rather than in the browser. Filtering a fetched page client-side let
 * the queue hand back a page with nothing on it while more pages waited behind it, and made the
 * pending badge count rows the queue did not show.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingQueueSourceSplitTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private SortOrderService sortOrderService;
    @Mock private StreamIndexService streamIndexService;
    @Mock private UserRepository userRepository;
    @Mock private ImportGraduationService graduationService;

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(
                channelRepository, playlistRepository, videoRepository, categoryRepository,
                approvalRepository, auditLogService, sortOrderService, streamIndexService,
                userRepository, graduationService);
    }

    private static Channel channel(String id, String source) {
        Channel c = new Channel("UC" + id);
        c.setId(id);
        c.setName("Channel " + id);
        c.setStatus("PENDING");
        c.setSource(source);
        c.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1_000, 0));
        return c;
    }

    /** One page of pending channels, with no page behind it. */
    private void stubOnePage(Channel... items) throws Exception {
        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(List.of(items), null, false));
    }

    private List<String> idsFrom(CursorPageDto<PendingApprovalDto> page) {
        List<String> ids = new ArrayList<>();
        page.getData().forEach(d -> ids.add(d.getId()));
        return ids;
    }

    @Test
    void theModeratorQueueLeavesOutPhoneImports() throws Exception {
        stubOnePage(channel("mod", "MODERATOR"), channel("imported", "USER_IMPORT"));

        var page = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null, ApprovalService.SourceScope.MODERATOR_QUEUE);

        assertEquals(List.of("mod"), idsFrom(page));
    }

    @Test
    void aSubmissionFromBeforeWeRecordedTheSourceStaysInTheModeratorQueue() throws Exception {
        // Those rows predate source tracking. The queue is where they have always appeared, and
        // it is the view that hides nothing — sending them to the by-user tab instead would make
        // them reachable only through a list they cannot be grouped into.
        stubOnePage(channel("legacy", null), channel("imported", "USER_IMPORT"));

        var page = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null, ApprovalService.SourceScope.MODERATOR_QUEUE);

        assertEquals(List.of("legacy"), idsFrom(page));
    }

    @Test
    void theByUserViewShowsOnlyPhoneImports() throws Exception {
        stubOnePage(channel("mod", "MODERATOR"), channel("imported", "USER_IMPORT"));

        var page = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null, ApprovalService.SourceScope.USER_IMPORTS);

        assertEquals(List.of("imported"), idsFrom(page));
    }

    @Test
    void noScopeAsksForEverything() throws Exception {
        stubOnePage(channel("mod", "MODERATOR"), channel("imported", "USER_IMPORT"), channel("legacy", null));

        var page = approvalService.getPendingApprovals("CHANNEL", null, 20, null, null);

        assertEquals(3, page.getData().size());
    }

    @Test
    void keepsFetchingRatherThanHandingBackAPageWithNothingOnIt() throws Exception {
        // Filtering one page at a time is what let the queue show its empty state above a
        // "Load more" button: the first page was all imports, and nobody looked further.
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any())).thenAnswer(inv ->
                call.getAndIncrement() == 0
                        ? new ApprovalRepository.PaginatedResult<>(List.of(channel("i1", "USER_IMPORT")), "cursor-1", true)
                        : new ApprovalRepository.PaginatedResult<>(List.of(channel("mod", "MODERATOR")), null, false));

        var page = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null, ApprovalService.SourceScope.MODERATOR_QUEUE);

        assertTrue(idsFrom(page).contains("mod"), "the queue gave up before reaching a moderator item");
        assertEquals(2, call.get(), "should have gone past the all-imports page");
    }

    @Test
    void stopsWhenTheQueueRunsOutRatherThanLoopingForever() throws Exception {
        stubOnePage(channel("i1", "USER_IMPORT"));

        var page = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null, ApprovalService.SourceScope.MODERATOR_QUEUE);

        assertTrue(page.getData().isEmpty());
    }
}
