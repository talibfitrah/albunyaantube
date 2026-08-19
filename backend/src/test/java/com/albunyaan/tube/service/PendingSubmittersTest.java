package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.PendingSubmitterDto;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * The review queue mixes two very different kinds of work: a handful of curated moderator
 * submissions, and bulk imports from ordinary users. Reviewing the second kind is per-person
 * work — "what did Ahmed send me" — which a flat, chronologically-merged queue cannot express.
 *
 * <p>Pins the per-submitter roll-up that backs the by-user tab.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingSubmittersTest {

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

    private static ApprovalRepository.PendingSubmitterRow row(String uid, String source) {
        return new ApprovalRepository.PendingSubmitterRow(uid, source);
    }

    private void stubRows(ApprovalRepository.PendingSubmitterRow... rows) throws Exception {
        when(approvalRepository.findPendingSubmitterRows(anyInt())).thenReturn(List.of(rows));
    }

    private static User user(String uid, String displayName, String email) {
        User u = new User();
        u.setUid(uid);
        u.setDisplayName(displayName);
        u.setEmail(email);
        return u;
    }

    @Test
    void countsEachSubmittersPendingImports() throws Exception {
        stubRows(
                row("uid-ahmed", "USER_IMPORT"),
                row("uid-ahmed", "USER_IMPORT"),
                row("uid-fatima", "USER_IMPORT"));
        when(userRepository.findByUid("uid-ahmed")).thenReturn(Optional.of(user("uid-ahmed", "Ahmed", "a@x.com")));
        when(userRepository.findByUid("uid-fatima")).thenReturn(Optional.of(user("uid-fatima", "Fatima", "f@x.com")));

        List<PendingSubmitterDto> submitters = approvalService.getPendingSubmitters();

        assertEquals(2, submitters.size());
        assertEquals("uid-ahmed", submitters.get(0).getUid());
        assertEquals(2L, submitters.get(0).getPendingCount());
        assertEquals("Ahmed", submitters.get(0).getDisplayName());
        assertEquals(1L, submitters.get(1).getPendingCount());
    }

    @Test
    void ordersTheBusiestSubmitterFirst() throws Exception {
        stubRows(
                row("uid-quiet", "USER_IMPORT"),
                row("uid-busy", "USER_IMPORT"),
                row("uid-busy", "USER_IMPORT"),
                row("uid-busy", "USER_IMPORT"));

        List<PendingSubmitterDto> submitters = approvalService.getPendingSubmitters();

        assertEquals("uid-busy", submitters.get(0).getUid());
        assertEquals(3L, submitters.get(0).getPendingCount());
    }

    @Test
    void listsOnlyPeopleWhoHaveImportedSomething() throws Exception {
        stubRows(
                row("uid-mod", "MODERATOR"),
                row("uid-admin", "ADMIN"),
                row("uid-legacy", null),
                row("uid-ahmed", "USER_IMPORT"));

        List<PendingSubmitterDto> submitters = approvalService.getPendingSubmitters();

        assertEquals(1, submitters.size());
        assertEquals("uid-ahmed", submitters.get(0).getUid());
    }

    @Test
    void countsOnlyWhatTheDrillDownWillShow() throws Exception {
        // The count and the drill-down must agree. The drill-down shows a person's phone imports
        // and nothing else, so anything they also submitted another way is not counted here —
        // otherwise the tab reads "2 pending" and then renders one card.
        stubRows(
                row("uid-ahmed", "USER_IMPORT"),
                row("uid-ahmed", "MODERATOR"));

        List<PendingSubmitterDto> submitters = approvalService.getPendingSubmitters();

        assertEquals(1, submitters.size());
        assertEquals(1L, submitters.get(0).getPendingCount());
    }

    @Test
    void namesASubmitterWithNoDisplayNameByEmail() throws Exception {
        stubRows(row("uid-noname", "USER_IMPORT"));
        when(userRepository.findByUid("uid-noname"))
                .thenReturn(Optional.of(user("uid-noname", null, "quiet@x.com")));

        List<PendingSubmitterDto> submitters = approvalService.getPendingSubmitters();

        assertEquals("quiet@x.com", submitters.get(0).getEmail());
        assertEquals("quiet@x.com", submitters.get(0).getLabel());
    }

    @Test
    void aSubmitterWhoCannotBeResolvedIsStillListed() throws Exception {
        // A deleted account still has items sitting in the queue; dropping them would hide
        // pending work from the admin entirely.
        stubRows(row("uid-ghost", "USER_IMPORT"));
        when(userRepository.findByUid("uid-ghost")).thenReturn(Optional.empty());

        List<PendingSubmitterDto> submitters = approvalService.getPendingSubmitters();

        assertEquals(1, submitters.size());
        assertEquals("uid-ghost", submitters.get(0).getLabel());
    }

    @Test
    void anEmptyQueueListsNobody() throws Exception {
        stubRows();

        assertTrue(approvalService.getPendingSubmitters().isEmpty());
    }
}
