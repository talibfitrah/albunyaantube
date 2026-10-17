package com.albunyaan.tube.service;

import com.albunyaan.tube.exception.LiveCategoryException;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Spec §12 U3: the guard's decision table (role × type × added/old), the cap, D10's channel sources, the codes. */
class LiveCategoryGuardTest {

    static final String LIVE = "live-cat", PLAIN = "plain-cat";
    static final String KIDS = "UC5vfWTTPnKdFp-8s0KbqJhw", SUNNAH = "UCROKYPep-UuODNwyipe6JMw";

    final LiveCategoryService liveCategories = mock(LiveCategoryService.class);
    final CategoryContentOrderRepository orders = mock(CategoryContentOrderRepository.class);
    final LiveChannelService live = mock(LiveChannelService.class);
    final VideoRepository videos = mock(VideoRepository.class);
    final LiveCategoryGuard guard = new LiveCategoryGuard(liveCategories, orders, live, videos);

    @BeforeEach
    void setUp() throws Exception {
        when(liveCategories.liveCategoryIds()).thenReturn(Set.of(LIVE));
        when(orders.countByCategoryId(LIVE)).thenReturn(4L);
    }

    static LiveCategoryGuard.Change add(String type, String youtubeId, String channelId, String status) {
        return new LiveCategoryGuard.Change(youtubeId, type, youtubeId, channelId, List.of(), null, List.of(LIVE), status);
    }

    static LiveTabResult lives(String... ids) {
        return LiveTabResult.ok(Arrays.stream(ids).map(i -> new LiveTabResult.LiveItem(i, "t")).toList(), 1);
    }

    private LiveCategoryException refused(boolean admin, LiveCategoryGuard.Change c) {
        return assertThrows(LiveCategoryException.class, () -> guard.check(admin, c));
    }

    @Test
    void anAddWithNoLiveCategoryPassesForAnyone() throws Exception {
        LiveCategoryGuard.Change c = new LiveCategoryGuard.Change("p", "playlist", "PLx", null, List.of(), null, List.of(PLAIN), "PENDING");

        assertThat(guard.check(false, c)).isNull();
        verifyNoInteractions(live);
    }

    @Test
    void aModeratorAddingToLiveIs403() {
        LiveCategoryException e = refused(false, add("channel", SUNNAH, null, "PENDING"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.ADMIN_ONLY);
    }

    @Test
    void aResubmitThatKeepsLiveIsNotAnAdd() throws Exception {
        LiveCategoryGuard.Change c = new LiveCategoryGuard.Change("c", "channel", SUNNAH, null,
                List.of(LIVE), "REQUEST_CHANGES", List.of(LIVE), "PENDING");

        assertThat(guard.check(false, c)).isNull();               // the admin's approval is the guarded publish
    }

    @Test
    void anAdminChannelAddSeedsItsState() throws Exception {
        assertThat(guard.check(true, add("channel", SUNNAH, null, "APPROVED"))).isNull();
        verify(live).seed(SUNNAH);
    }

    @Test
    void anAdminPlaylistAddIs400TypeNotAllowed() {
        LiveCategoryException e = refused(true, add("playlist", "PLabc", null, "APPROVED"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.TYPE_NOT_ALLOWED);
    }

    /** D10: the request body's channel comes first; no resolution is spent. */
    @Test
    void aLiveVideoPassesAndReturnsItsChannel() throws Exception {
        when(live.livesNow(SUNNAH)).thenReturn(lives("Rs7St51oDDc"));

        assertThat(guard.check(true, add("video", "Rs7St51oDDc", SUNNAH, "APPROVED"))).isEqualTo(SUNNAH);
        verify(live, never()).resolveChannelId(anyString());
    }

    @Test
    void aVideoNotLiveNowIs400NotLive() {
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        LiveCategoryException e = refused(true, add("video", "yee9gj7t8nQ", KIDS, "APPROVED"));

        assertThat(e.getCode()).isEqualTo(LiveCategoryException.NOT_LIVE);
        assertThat(e.getMessage()).isEqualTo("Video yee9gj7t8nQ is not live on channel " + KIDS + " now");
        assertThat(e.getDetails()).containsEntry("channelId", KIDS);
    }

    @Test
    void aFailingOrRefusedCheckIs503() {
        when(live.livesNow(KIDS)).thenReturn(LiveTabResult.error(1, "boom"));
        assertThat(refused(true, add("video", "J3bDNstCnzE", KIDS, "APPROVED")).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        when(live.livesNow(KIDS)).thenReturn(LiveTabResult.breaker(0, "open"));
        assertThat(refused(true, add("video", "J3bDNstCnzE", KIDS, "APPROVED")).getCode()).isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
    }

    /** R7: a rate limit says nothing about the video, so a genuinely live link is never refused as NOT_LIVE. */
    @Test
    void aRateLimitedCheckIs503NotNotLive() {
        when(live.livesNow(KIDS)).thenReturn(LiveTabResult.rateLimited(1, "reCAPTCHA"));

        LiveCategoryException e = refused(true, add("video", "J3bDNstCnzE", KIDS, "APPROVED"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
    }

    @Test
    void aTerminatedChannelMeansNotLive() {
        when(live.livesNow(KIDS)).thenReturn(LiveTabResult.terminated(1, "terminated"));

        assertThat(refused(true, add("video", "J3bDNstCnzE", KIDS, "APPROVED")).getCode()).isEqualTo(LiveCategoryException.NOT_LIVE);
    }

    /** D10 / R14: a stored doc with no channel gets it resolved at add time. */
    @Test
    void anUnknownChannelIsResolvedThenChecked() throws Exception {
        when(live.resolveChannelId("J3bDNstCnzE")).thenReturn(Optional.of(KIDS));
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        assertThat(guard.check(true, add("video", "J3bDNstCnzE", null, "APPROVED"))).isEqualTo(KIDS);
    }

    @Test
    void everyChannelSourceFailingIs400ChannelUnknown() {
        when(live.resolveChannelId("J3bDNstCnzE")).thenReturn(Optional.empty());

        assertThat(refused(true, add("video", "J3bDNstCnzE", null, "APPROVED")).getCode())
                .isEqualTo(LiveCategoryException.CHANNEL_UNKNOWN);
    }

    /** R14 / RV I4: an admin status edit to APPROVED on a PENDING doc that already holds LIVE is a publish. */
    @Test
    void approvingAPendingDocHoldingLiveIsChecked() {
        LiveCategoryGuard.Change publish = new LiveCategoryGuard.Change("doc", "playlist", "PLabc", null,
                List.of(LIVE), "PENDING", List.of(LIVE), "APPROVED");

        assertThat(refused(true, publish).getCode()).isEqualTo(LiveCategoryException.TYPE_NOT_ALLOWED);
    }

    @Test
    void reSavingAnApprovedMemberNeverRechecks() throws Exception {
        LiveCategoryGuard.Change resave = new LiveCategoryGuard.Change("doc", "video", "Rs7St51oDDc", SUNNAH,
                List.of(LIVE), "APPROVED", List.of(LIVE), "APPROVED");

        assertThat(guard.check(true, resave)).isNull();
        verifyNoInteractions(live);
    }

    @Test
    void theThirtyFirstEntryIsRefusedFull() throws Exception {
        when(orders.countByCategoryId(LIVE)).thenReturn(30L);

        assertThat(refused(true, add("channel", SUNNAH, null, "APPROVED")).getCode()).isEqualTo(LiveCategoryException.FULL);
        verify(live, never()).seed(anyString());
    }

    /** D9: one bad row refuses the whole request, and every offender is listed. */
    @Test
    void checkAllRefusesTheWholeRequestAndListsEveryOffender() {
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));
        List<LiveCategoryGuard.Change> rows = List.of(
                add("video", "J3bDNstCnzE", KIDS, "APPROVED"),
                add("playlist", "PLabc", null, "APPROVED"),
                add("video", "yee9gj7t8nQ", KIDS, "APPROVED"));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkAll(true, rows));

        assertThat(e.getCode()).isEqualTo(LiveCategoryException.TYPE_NOT_ALLOWED);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> offenders = (List<Map<String, Object>>) e.getDetails().get("offenders");
        assertThat(offenders).extracting(o -> o.get("ref")).containsExactly("PLabc", "yee9gj7t8nQ");
        assertThat(offenders).extracting(o -> o.get("code")).containsExactly("LIVE_CATEGORY_TYPE_NOT_ALLOWED", "LIVE_LINK_NOT_LIVE");
    }

    @Test
    void checkAllCountsItsOwnAddsAgainstTheCap() throws Exception {
        when(orders.countByCategoryId(LIVE)).thenReturn(29L);

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkAll(true,
                List.of(add("channel", SUNNAH, null, "APPROVED"), add("channel", KIDS, null, "APPROVED"))));

        assertThat(e.getCode()).isEqualTo(LiveCategoryException.FULL);
    }

    @Test
    void checkAllReturnsTheVerifiedChannelPerRow() throws Exception {
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        assertThat(guard.checkAll(true, List.of(add("video", "J3bDNstCnzE", KIDS, "APPROVED"))))
                .containsEntry("J3bDNstCnzE", KIDS);
    }

    /** Row 17: setting LIVE on a category that holds a playlist or a non-live video is refused, listing them. */
    @Test
    void theMembersRuleFlagsPlaylistsAndNonLiveVideos() throws Exception {
        Playlist p = new Playlist("PLabc");
        p.setId("pl-1");
        p.setStatus("APPROVED");
        Video v = new Video("yee9gj7t8nQ");
        v.setId("doc-v");
        v.setStatus("APPROVED");
        v.setChannelId(KIDS);
        when(liveCategories.members("kids")).thenReturn(new LiveCategoryService.Members(List.of(p, v)));
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkMembersForLive("kids"));

        assertThat(e.getCode()).isEqualTo(LiveCategoryException.MEMBERS_INVALID);
        assertThat(e.getDetails()).containsKey("offenders");
    }

    @Test
    void theMembersRuleWritesTheVerifiedChannelOnLiveLinks() throws Exception {
        Channel c = new Channel(SUNNAH);
        c.setId("channel_" + SUNNAH);
        c.setStatus("APPROVED");
        Video v = new Video("J3bDNstCnzE");
        v.setId("doc-v");
        v.setStatus("APPROVED");
        when(liveCategories.members("kids")).thenReturn(new LiveCategoryService.Members(List.of(c, v)));
        when(live.resolveChannelId("J3bDNstCnzE")).thenReturn(Optional.of(KIDS));
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        guard.checkMembersForLive("kids");

        assertThat(v.getChannelId()).isEqualTo(KIDS);
        verify(videos).save(v);
        verify(live).seed(SUNNAH);
        verify(orders, never()).countByCategoryId(any());                    // members are counted, not re-counted
    }
}
