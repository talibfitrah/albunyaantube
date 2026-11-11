package com.albunyaan.tube.controller;

import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.VideoValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private UserRepository userRepository;
    @Mock private VideoValidationService videoValidationService;
    @InjectMocks private DashboardController controller;

    @Test
    void pendingModerationCountsEveryPendingChannelPlaylistAndVideo() throws Exception {
        // The Pending Approvals badge and the Content Library "Pending" totals count all three
        // types; channels alone made the dashboard under-report the queue.
        when(approvalRepository.countAllPending()).thenReturn(1082L);

        DashboardController.DashboardMetricsResponse body = controller.getDashboardMetrics("LAST_7_DAYS").getBody();

        assertEquals(1082, body.data.pendingModeration.current);
    }
}
