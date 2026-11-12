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
 * The review queue lists everything pending. The by-user drill-down lists one person's phone
 * imports: every submission records which path it came in on, so those separate cleanly.
 *
 * <p>The drill-down filter is applied here rather than in the browser. Filtering a fetched page
 * client-side let it hand back a page with nothing on it while more pages waited behind it.
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
                userRepository, graduationService, org.mockito.Mockito.mock(LiveCategoryGuard.class));
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

    /** One page of the person's pending channels, with no page behind it. */
    private void stubOnePage(Channel... items) throws Exception {
        when(approvalRepository.findChannelsBySubmitterAndStatus(any(), any(), anyInt(), any()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(List.of(items), null, false));
    }

    private List<String> idsFrom(CursorPageDto<PendingApprovalDto> page) {
        List<String> ids = new ArrayList<>();
        page.getData().forEach(d -> ids.add(d.getId()));
        return ids;
    }

    @Test
    void theQueueListsEverythingPendingPhoneImportsIncluded() throws Exception {
        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(List.of(
                        channel("mod", "MODERATOR"), channel("imported", "USER_IMPORT"), channel("legacy", null)),
                        null, false));

        var page = approvalService.getPendingApprovals("CHANNEL", null, 20, null);

        assertEquals(List.of("mod", "imported", "legacy"), idsFrom(page));
    }

    @Test
    void theDrillDownShowsOnlyThatPersonsPhoneImports() throws Exception {
        // A row from before we recorded the source is not an import: it was never counted as one.
        stubOnePage(channel("mod", "MODERATOR"), channel("imported", "USER_IMPORT"), channel("legacy", null));

        var page = approvalService.getPendingImportsBy("uid-ahmed", "CHANNEL", 20, null);

        assertEquals(List.of("imported"), idsFrom(page));
    }

    @Test
    void keepsFetchingRatherThanHandingBackAPageWithNothingOnIt() throws Exception {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(approvalRepository.findChannelsBySubmitterAndStatus(any(), any(), anyInt(), any())).thenAnswer(inv ->
                call.getAndIncrement() == 0
                        ? new ApprovalRepository.PaginatedResult<>(List.of(channel("mod", "MODERATOR")), "cursor-1", true)
                        : new ApprovalRepository.PaginatedResult<>(List.of(channel("i1", "USER_IMPORT")), null, false));

        var page = approvalService.getPendingImportsBy("uid-ahmed", "CHANNEL", 20, null);

        assertTrue(idsFrom(page).contains("i1"), "the drill-down gave up before reaching an import");
        assertEquals(2, call.get(), "should have gone past the page with no imports");
    }

    @Test
    void stopsWhenThePersonsQueueRunsOutRatherThanLoopingForever() throws Exception {
        stubOnePage(channel("mod", "MODERATOR"));

        var page = approvalService.getPendingImportsBy("uid-ahmed", "CHANNEL", 20, null);

        assertTrue(page.getData().isEmpty());
    }
}
