package com.albunyaan.tube.service;

import com.albunyaan.tube.exception.LiveCategoryException;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Spec §12 U3: the guard's decision table (role × type × added/old), the cap, D10's channel sources, the codes. */
class LiveCategoryGuardTest {

    static final String LIVE = "live-cat", PLAIN = "plain-cat";
    static final String KIDS = "UC5vfWTTPnKdFp-8s0KbqJhw", SUNNAH = "UCROKYPep-UuODNwyipe6JMw";

    final LiveCategoryService liveCategories = mock(LiveCategoryService.class);
    final LiveChannelService live = mock(LiveChannelService.class);
    final VideoRepository videos = mock(VideoRepository.class);
    final ChannelRepository channels = mock(ChannelRepository.class);
    final PlaylistRepository playlists = mock(PlaylistRepository.class);
    final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    final LiveCategoryGuard guard = new LiveCategoryGuard(liveCategories, live, videos, channels, playlists,
            new CategoryMappingService(categoryRepository));

    @BeforeEach
    void setUp() throws Exception {
        when(liveCategories.liveCategoryIds()).thenReturn(Set.of(LIVE));
        when(liveCategories.members(LIVE)).thenReturn(members(4));
    }

    /** {@code n} channel members, none of them SUNNAH or KIDS. */
    static LiveCategoryService.Members members(int n) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Channel(String.format("UCmember%016d", i)));
        }
        return new LiveCategoryService.Members(list);
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
    void everyChannelSourceFailingIs400ChannelUnknown() throws Exception {
        when(live.resolveChannelId("J3bDNstCnzE")).thenReturn(Optional.empty());

        assertThat(refused(true, add("video", "J3bDNstCnzE", null, "APPROVED")).getCode())
                .isEqualTo(LiveCategoryException.CHANNEL_UNKNOWN);
    }

    /** R7: a breaker or rate limit during D10's resolution is no signal; never a definitive CHANNEL_UNKNOWN. */
    @Test
    void aTransientResolutionFailureIs503() throws Exception {
        when(live.resolveChannelId("J3bDNstCnzE")).thenThrow(new YouTubeGateway.CircuitOpenException("open"));

        LiveCategoryException e = refused(true, add("video", "J3bDNstCnzE", null, "APPROVED"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
    }

    /** R7 end to end: oEmbed (D10's last source) pushing back for load reaches the guard as 503, never CHANNEL_UNKNOWN. */
    @Test
    void anOEmbedRateLimitOrServerErrorIs503ThroughTheRealReader() {
        assertThat(refusedWhenOEmbedAnswers(429).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refusedWhenOEmbedAnswers(429).getCode()).isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
        assertThat(refusedWhenOEmbedAnswers(503).getCode()).isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
    }

    /** A 404 is a real answer: oEmbed can't name the author, and it is the last source, so CHANNEL_UNKNOWN as before. */
    @Test
    void anOEmbed404IsStillChannelUnknown() {
        assertThat(refusedWhenOEmbedAnswers(404).getCode()).isEqualTo(LiveCategoryException.CHANNEL_UNKNOWN);
    }

    /** A gone channel (ChannelOrchestrator.channelGone) behind the author's handle is definitive: a retry can't succeed. */
    @Test
    void aGoneChannelInTheLookupIsChannelUnknown() throws Exception {
        LiveCategoryException e = refusedWhenTheChannelLookupThrows(
                new IOException("Channel fetch failed", new AccountTerminatedException("This account has been terminated")));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.CHANNEL_UNKNOWN);
    }

    @Test
    void aRateLimitOrBreakerInTheLookupIsStill503() throws Exception {
        assertThat(refusedWhenTheChannelLookupThrows(new IOException("Channel fetch failed",
                new ReCaptchaException("reCaptcha Challenge requested", "https://www.youtube.com"))).getCode())
                .isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
        assertThat(refusedWhenTheChannelLookupThrows(new YouTubeGateway.CircuitOpenException("open")).getCode())
                .isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
    }

    /** The real LiveChannelService and LiveTabReader over an oEmbed client answering {@code code}; no other source. */
    private LiveCategoryException refusedWhenOEmbedAnswers(int code) {
        return refusedThrough(YouTubeOEmbedClientTest.answering(code), mock(ChannelOrchestrator.class));
    }

    /** oEmbed names the author's handle; the channel-details lookup for it throws {@code failure}. */
    private LiveCategoryException refusedWhenTheChannelLookupThrows(IOException failure) throws IOException {
        YouTubeOEmbedClient oEmbed = mock(YouTubeOEmbedClient.class);
        when(oEmbed.authorUrl("J3bDNstCnzE")).thenReturn(Optional.of("https://www.youtube.com/@ZadTVchannel"));
        ChannelOrchestrator channels = mock(ChannelOrchestrator.class);
        when(channels.getChannelDetails("@ZadTVchannel")).thenThrow(failure);
        return refusedThrough(oEmbed, channels);
    }

    private LiveCategoryException refusedThrough(YouTubeOEmbedClient oEmbed, ChannelOrchestrator channels) {
        LiveTabReader reader = new LiveTabReader(new YouTubeGateway(ServiceList.YouTube, 1, null, null), oEmbed, channels);
        LiveCategoryGuard real = new LiveCategoryGuard(liveCategories,
                new LiveChannelService(reader, mock(LiveChannelStateRepository.class), Clock.systemUTC()), videos,
                mock(ChannelRepository.class), mock(PlaylistRepository.class), mock(CategoryMappingService.class));
        return assertThrows(LiveCategoryException.class, () -> real.check(true, add("video", "J3bDNstCnzE", null, "APPROVED")));
    }

    /** D10: a channel id that is not a UC id (body or stored doc) counts as missing, so resolution runs. */
    @Test
    void aNonUcChannelIdIsResolvedLikeAMissingOne() throws Exception {
        when(live.resolveChannelId("J3bDNstCnzE")).thenReturn(Optional.of(KIDS));
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        assertThat(guard.check(true, add("video", "J3bDNstCnzE", "@kids", "APPROVED"))).isEqualTo(KIDS);
        verify(live, never()).livesNow("@kids");
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
        when(liveCategories.members(LIVE)).thenReturn(members(30));

        assertThat(refused(true, add("channel", SUNNAH, null, "APPROVED")).getCode()).isEqualTo(LiveCategoryException.FULL);
        verify(live, never()).seed(anyString());
    }

    /** A publish of a doc already in the category is no new entry: a full category still lets its members publish. */
    @Test
    void aMemberPublishingIntoAFullCategoryIsNotCountedTwice() throws Exception {
        List<Object> thirty = new ArrayList<>(members(29).ordered());
        thirty.add(new Channel(SUNNAH));
        when(liveCategories.members(LIVE)).thenReturn(new LiveCategoryService.Members(thirty));
        LiveCategoryGuard.Change publish = new LiveCategoryGuard.Change("doc", "channel", SUNNAH, null,
                List.of(LIVE), "PENDING", List.of(LIVE), "APPROVED");

        assertThat(guard.check(true, publish)).isNull();
        verify(live).seed(SUNNAH);
    }

    @Test
    void theThirtiethEntryPasses() throws Exception {
        when(liveCategories.members(LIVE)).thenReturn(members(29));

        assertThat(guard.check(true, add("channel", SUNNAH, null, "APPROVED"))).isNull();
        verify(live).seed(SUNNAH);
    }

    /** D9: one bad row refuses the whole request, and every offender of the failing pass is listed. */
    @Test
    void checkAllRefusesTheWholeRequestAndListsEveryOffender() {
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));
        List<LiveCategoryGuard.Change> rows = List.of(
                add("video", "J3bDNstCnzE", KIDS, "APPROVED"),
                add("video", "yee9gj7t8nQ", KIDS, "APPROVED"),
                add("video", "Rs7St51oDDc", KIDS, "APPROVED"));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkAll(true, rows));

        assertThat(e.getCode()).isEqualTo(LiveCategoryException.NOT_LIVE);
        assertThat(offenders(e)).extracting(o -> o.get("ref")).containsExactly("yee9gj7t8nQ", "Rs7St51oDDc");
        assertThat(offenders(e)).extracting(o -> o.get("code")).containsExactly("LIVE_LINK_NOT_LIVE", "LIVE_LINK_NOT_LIVE");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> offenders(LiveCategoryException e) {
        return (List<Map<String, Object>>) e.getDetails().get("offenders");
    }

    /** Pass 1 (admin, type, cap) refuses before any YouTube read or seed; the non-live video shows up next attempt. */
    @Test
    void aPassOneRefusalSpendsNoYouTubeReadAndSeedsNothing() {
        List<LiveCategoryGuard.Change> rows = List.of(
                add("channel", SUNNAH, null, "APPROVED"),
                add("video", "yee9gj7t8nQ", KIDS, "APPROVED"),
                add("playlist", "PLabc", null, "APPROVED"));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkAll(true, rows));

        assertThat(e.getCode()).isEqualTo(LiveCategoryException.TYPE_NOT_ALLOWED);
        assertThat(offenders(e)).extracting(o -> o.get("ref")).containsExactly("PLabc");
        verifyNoInteractions(live);
    }

    @Test
    void aYouTubeRefusalSeedsNothing() {
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        assertThrows(LiveCategoryException.class, () -> guard.checkAll(true,
                List.of(add("channel", SUNNAH, null, "APPROVED"), add("video", "yee9gj7t8nQ", KIDS, "APPROVED"))));

        verify(live, never()).seed(anyString());
    }

    @Test
    void aPassingRequestSeedsAfterVerification() throws Exception {
        when(live.livesNow(KIDS)).thenReturn(lives("J3bDNstCnzE"));

        guard.checkAll(true, List.of(add("channel", SUNNAH, null, "APPROVED"), add("video", "J3bDNstCnzE", KIDS, "APPROVED")));

        org.mockito.InOrder order = inOrder(live);
        order.verify(live).livesNow(KIDS);
        order.verify(live).seed(SUNNAH);
    }

    /** Seeds are per channel, not per row: an ERROR read is not memoized, so a second seed would read YouTube again. */
    @Test
    void aChannelNamedTwiceIsSeededOnce() throws Exception {
        guard.checkAll(true, List.of(add("channel", SUNNAH, null, "APPROVED"), add("channel", SUNNAH, null, "APPROVED")));

        verify(live, times(1)).seed(SUNNAH);
    }

    /** The headline is the first 4xx offender: a definitive refusal outranks a failed check. */
    @Test
    void theHeadlineIsTheFirst4xxOffender() {
        when(live.livesNow(KIDS)).thenReturn(LiveTabResult.error(1, "boom"));
        when(live.livesNow(SUNNAH)).thenReturn(lives("Rs7St51oDDc"));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkAll(true,
                List.of(add("video", "J3bDNstCnzE", KIDS, "APPROVED"), add("video", "yee9gj7t8nQ", SUNNAH, "APPROVED"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.NOT_LIVE);
        assertThat(offenders(e)).extracting(o -> o.get("ref")).containsExactly("J3bDNstCnzE", "yee9gj7t8nQ");
    }

    @Test
    void checkAllCountsItsOwnAddsAgainstTheCap() throws Exception {
        when(liveCategories.members(LIVE)).thenReturn(members(29));

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

        Video fresh = new Video("J3bDNstCnzE");                              // the doc as it stands after the reads
        fresh.setId("doc-v");
        fresh.setStatus("PENDING");
        when(videos.findById("doc-v")).thenReturn(Optional.of(fresh));

        guard.checkMembersForLive("kids");

        assertThat(fresh.getChannelId()).isEqualTo(KIDS);
        assertThat(fresh.getStatus()).isEqualTo("PENDING");                   // nothing else is overwritten
        verify(videos).save(fresh);
        verify(videos, never()).save(v);
        verify(live).seed(SUNNAH);
        verify(liveCategories, times(1)).members("kids");                    // members are counted, not re-read
    }

    /** More members than the cap: refused FULL before any YouTube read. */
    @Test
    void aHundredMemberCategoryIsRefusedFullWithNoRead() throws Exception {
        List<Object> hundred = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Video v = new Video("J3bDNstCnzE");
            v.setId("doc-" + i);
            v.setStatus("APPROVED");
            v.setChannelId(KIDS);
            hundred.add(v);
        }
        when(liveCategories.members("kids")).thenReturn(new LiveCategoryService.Members(hundred));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkMembersForLive("kids"));

        assertThat(e.getCode()).isEqualTo(LiveCategoryException.FULL);
        verifyNoInteractions(live);
    }

    @Test
    void theMembersRulePassesAFailedCheckThroughAs503() throws Exception {
        when(liveCategories.members("kids")).thenReturn(new LiveCategoryService.Members(List.of(video("doc-v", "J3bDNstCnzE", KIDS))));
        when(live.livesNow(KIDS)).thenReturn(LiveTabResult.breaker(0, "open"));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkMembersForLive("kids"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.CHECK_UNAVAILABLE);
    }

    /** A failed check beside a bad member is still MEMBERS_INVALID, and a refused rule writes no video. */
    @Test
    void aBadMemberBesideAFailedCheckIsMembersInvalidAndWritesNothing() throws Exception {
        Video unresolved = video("doc-a", "Rs7St51oDDc", null);
        when(liveCategories.members("kids")).thenReturn(new LiveCategoryService.Members(List.of(
                video("doc-k", "J3bDNstCnzE", KIDS), unresolved, video("doc-y", "yee9gj7t8nQ", SUNNAH))));
        when(live.livesNow(KIDS)).thenReturn(LiveTabResult.error(1, "boom"));
        when(live.resolveChannelId("Rs7St51oDDc")).thenReturn(Optional.of(SUNNAH));
        when(live.livesNow(SUNNAH)).thenReturn(lives("Rs7St51oDDc"));

        LiveCategoryException e = assertThrows(LiveCategoryException.class, () -> guard.checkMembersForLive("kids"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo(LiveCategoryException.MEMBERS_INVALID);
        assertThat(offenders(e)).extracting(o -> o.get("ref")).containsExactly("doc-k", "doc-y");
        assertThat(unresolved.getChannelId()).isNull();
        verify(videos, never()).findById(anyString());
        verify(videos, never()).save(any());
    }

    private static Video video(String docId, String youtubeId, String channelId) {
        Video v = new Video(youtubeId);
        v.setId(docId);
        v.setStatus("APPROVED");
        v.setChannelId(channelId);
        return v;
    }

    /**
     * Row 16: the simple-import parse (the keywords part is never categories, as in both importers) and the existence
     * skip (the import skips existing rows). A row naming no LIVE category costs no read.
     */
    @Test
    void simpleImportChangesListsOnlyNewRowsThatNameLive() throws Exception {
        Category liveCategory = new Category("Live", null);
        liveCategory.setId(LIVE);
        Category plainCategory = new Category("Plain", null);
        plainCategory.setId(PLAIN);
        when(categoryRepository.findAll()).thenReturn(List.of(liveCategory, plainCategory));
        when(channels.findByYoutubeId("UCold")).thenReturn(Optional.of(new Channel("UCold")));
        List<Map<String, String>> simpleData = List.of(
                Map.of("UCnew", "C|Live|kw", "UCold", "C|Live"),
                Map.of("PLkw", "P|Plain|kw,Live", "PLlive", "P|Live"),
                Map.of("vidplain", "V|Plain"));

        List<LiveCategoryGuard.Change> changes = guard.simpleImportChanges(simpleData, "APPROVED");

        assertThat(changes).extracting(LiveCategoryGuard.Change::ref).containsExactlyInAnyOrder("UCnew", "PLlive");
        assertThat(changes).allSatisfy(c -> assertThat(c.newCategoryIds()).containsExactly(LIVE));
        verify(playlists, never()).findByYoutubeId("PLkw");
        verifyNoInteractions(videos);
    }
}
