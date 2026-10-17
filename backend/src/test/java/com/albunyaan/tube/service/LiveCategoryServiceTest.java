package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Spec §12 U4: order, one-to-many, hidden offline channels, R11's filter, D13, D4. */
class LiveCategoryServiceTest {

    static final String CAT = "btiLw1LiEqiUHQJiaJKX";
    static final String QURAN = "UCos52azQNBgW63_9uDJoPDA", ONE4KIDS = "UC5vfWTTPnKdFp-8s0KbqJhw", ZAD = "UCOll3M-P7oKs5cSrQ9ytt6g";

    final CategoryContentOrderRepository orders = mock(CategoryContentOrderRepository.class);
    final ChannelRepository channels = mock(ChannelRepository.class);
    final PlaylistRepository playlists = mock(PlaylistRepository.class);
    final VideoRepository videos = mock(VideoRepository.class);
    final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    final LiveChannelService live = new LiveChannelService(mock(LiveTabReader.class), mock(LiveChannelStateRepository.class), Clock.systemUTC());
    final LiveCategoryService service = new LiveCategoryService(categoryRepository, orders, channels, playlists, videos, live);

    final List<CategoryContentOrder> rows = new ArrayList<>();
    final Map<String, Channel> channelDocs = new HashMap<>();
    final Map<String, Video> videoDocs = new HashMap<>();
    final Map<String, Video> registry = new HashMap<>();
    final List<LiveChannelState> states = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        when(orders.findByCategoryIdOrderByPosition(CAT)).thenReturn(rows);
        when(channels.findAllByIds(anyList())).thenReturn(channelDocs);
        when(playlists.findAllByIds(anyList())).thenReturn(Map.of());
        when(videos.findAllByIds(anyList())).thenReturn(videoDocs);
        when(channels.findByCategoryIdAllStatus(anyString(), anyInt())).thenReturn(List.of());
        when(playlists.findByCategoryIdAllStatus(anyString(), anyInt())).thenReturn(List.of());
        when(videos.findByCategoryIdAllStatus(anyString(), anyInt())).thenReturn(List.of());
        when(videos.findByYoutubeIds(any())).thenAnswer(i -> registry);
    }

    private Channel entry(String uc, String status, String... lives) {
        Channel c = new Channel(uc);
        c.setId("channel_" + uc);
        c.setName("name-" + uc);
        c.setStatus("APPROVED");
        channelDocs.put(c.getId(), c);
        rows.add(new CategoryContentOrder(CAT, c.getId(), "channel", rows.size()));
        LiveChannelState s = LiveChannelState.unknown(uc);
        s.setStatus(status);
        for (String id : lives) s.getLives().add(new LiveChannelState.Live(id, "title-" + id));
        states.add(s);
        live.loadStates(states);
        return c;
    }

    private Video link(String youtubeId, String channelId) {
        Video v = new Video(youtubeId);
        v.setId("doc-" + youtubeId);
        v.setStatus("APPROVED");
        v.setChannelId(channelId);
        v.setTitle("stored " + youtubeId);
        videoDocs.put(v.getId(), v);
        rows.add(new CategoryContentOrder(CAT, v.getId(), "video", rows.size()));
        return v;
    }

    private static List<String> ids(List<ContentItemDto> items) {
        return items.stream().map(ContentItemDto::getId).toList();
    }

    @Test
    void entriesExpandInRowOrderAndOne4kidsExpandsToThree() throws Exception {
        entry(QURAN, LiveChannelState.LIVE, "eC4LfEVxvKg");
        entry(ONE4KIDS, LiveChannelState.LIVE, "J3bDNstCnzE", "yee9gj7t8nQ", "bpvNfqWTXhw");
        entry(ZAD, LiveChannelState.LIVE, "eaOtcUNHDB8");

        List<ContentItemDto> items = service.items(CAT);

        assertThat(ids(items)).containsExactly("eC4LfEVxvKg", "J3bDNstCnzE", "yee9gj7t8nQ", "bpvNfqWTXhw", "eaOtcUNHDB8");
        ContentItemDto first = items.get(0);
        assertThat(first.getType()).isEqualTo("VIDEO");
        assertThat(first.getLiveChannelId()).isEqualTo(QURAN);
        assertThat(first.getDurationSeconds()).isZero();
        assertThat(first.getViewCount()).isNull();
        assertThat(first.getThumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/eC4LfEVxvKg/hqdefault_live.jpg");
        assertThat(first.getChannelTitle()).isEqualTo("name-" + QURAN);
    }

    @Test
    void offlineAndUnknownChannelsAreHidden() throws Exception {
        entry(QURAN, LiveChannelState.OFFLINE);
        entry(ZAD, LiveChannelState.UNKNOWN);

        assertThat(service.items(CAT)).isEmpty();
    }

    /** D3 / R10: an erroring or suspect channel stays LIVE in its state and keeps serving its last lives. */
    @Test
    void anErroringChannelKeepsItsLastLives() throws Exception {
        entry(ZAD, LiveChannelState.LIVE, "eaOtcUNHDB8");
        states.get(0).setErrorCount(3);
        live.loadStates(states);

        assertThat(ids(service.items(CAT))).containsExactly("eaOtcUNHDB8");
    }

    /** R11 (a): the channel's own exclusions hold. */
    @Test
    void excludedLivesAreDropped() throws Exception {
        Channel c = entry(ONE4KIDS, LiveChannelState.LIVE, "J3bDNstCnzE", "yee9gj7t8nQ", "bpvNfqWTXhw");
        c.getExcludedItems().setLiveStreams(List.of("yee9gj7t8nQ"));
        c.getExcludedItems().setVideos(List.of("bpvNfqWTXhw"));

        assertThat(ids(service.items(CAT))).containsExactly("J3bDNstCnzE");
    }

    /** R11 (b): no served id may draw a 410 from the public gate. */
    @Test
    void livesWhoseRegistryDocWouldDraw410AreDropped() throws Exception {
        entry(ONE4KIDS, LiveChannelState.LIVE, "J3bDNstCnzE", "yee9gj7t8nQ", "bpvNfqWTXhw");
        entry(QURAN, LiveChannelState.LIVE, "eC4LfEVxvKg");
        registry.put("J3bDNstCnzE", doc("J3bDNstCnzE", "REJECTED", null));
        registry.put("yee9gj7t8nQ", doc("yee9gj7t8nQ", "PENDING", null));
        registry.put("bpvNfqWTXhw", doc("bpvNfqWTXhw", "APPROVED", ValidationStatus.ARCHIVED));
        registry.put("eC4LfEVxvKg", doc("eC4LfEVxvKg", "APPROVED", ValidationStatus.UNAVAILABLE));

        assertThat(service.items(CAT)).isEmpty();
    }

    /** A PERSONAL doc answers 404 on the gate (fail-open), so it is served. */
    @Test
    void aPersonalRegistryDocIsServed() throws Exception {
        entry(ZAD, LiveChannelState.LIVE, "eaOtcUNHDB8");
        Video personal = doc("eaOtcUNHDB8", "APPROVED", ValidationStatus.VALID);
        personal.setVisibility("PERSONAL");
        registry.put("eaOtcUNHDB8", personal);

        assertThat(ids(service.items(CAT))).containsExactly("eaOtcUNHDB8");
    }

    /** D13: a link without a channel id is never served. */
    @Test
    void aLinkWithoutAChannelIdIsNotServed() throws Exception {
        link("PLkCnLrKN8Q", null);
        link("Rs7St51oDDc", "UCROKYPep-UuODNwyipe6JMw");

        List<ContentItemDto> items = service.items(CAT);

        assertThat(ids(items)).containsExactly("Rs7St51oDDc");
        assertThat(items.get(0).getLiveChannelId()).isNull();                // D11: links carry no liveChannelId
    }

    /** D4: a link and a channel live with the same id keep the first position. */
    @Test
    void aDuplicateIdKeepsItsFirstPosition() throws Exception {
        link("Rs7St51oDDc", "UCROKYPep-UuODNwyipe6JMw");
        entry("UCROKYPep-UuODNwyipe6JMw", LiveChannelState.LIVE, "Rs7St51oDDc");

        List<ContentItemDto> items = service.items(CAT);

        assertThat(ids(items)).containsExactly("Rs7St51oDDc");
        assertThat(items.get(0).getLiveChannelId()).isNull();
    }

    @Test
    void anUnapprovedChannelEntryServesNothing() throws Exception {
        entry(ZAD, LiveChannelState.LIVE, "eaOtcUNHDB8").setStatus("PENDING");

        assertThat(service.items(CAT)).isEmpty();
    }

    @Test
    void membersWithoutAnOrderRowAreAppended() throws Exception {
        entry(QURAN, LiveChannelState.LIVE, "eC4LfEVxvKg");
        Channel orphan = new Channel(ZAD);
        orphan.setId("channel_" + ZAD);
        orphan.setName("zad");
        orphan.setStatus("APPROVED");
        when(channels.findByCategoryIdAllStatus(anyString(), anyInt())).thenReturn(List.of(orphan));
        LiveChannelState zad = LiveChannelState.unknown(ZAD);
        zad.setStatus(LiveChannelState.LIVE);
        zad.getLives().add(new LiveChannelState.Live("eaOtcUNHDB8", "t"));
        states.add(zad);
        live.loadStates(states);

        assertThat(ids(service.items(CAT))).containsExactly("eC4LfEVxvKg", "eaOtcUNHDB8");
    }

    /** Pre-flight D10: servable() restates PublicContentService's private isApproved && isAvailable; this pins them. */
    @Test
    void servableMatchesThePublicListFilter() {
        com.albunyaan.tube.service.PublicContentService pcs = mock(com.albunyaan.tube.service.PublicContentService.class);
        for (String status : new String[]{"APPROVED", "PENDING"}) {
            for (String visibility : new String[]{null, "PUBLIC", "PERSONAL"}) {
                for (ValidationStatus vs : new ValidationStatus[]{null, ValidationStatus.ERROR, ValidationStatus.UNAVAILABLE,
                        ValidationStatus.ARCHIVED}) {
                    Video v = doc("Rs7St51oDDc", status, vs);
                    v.setVisibility(visibility);
                    Channel c = new Channel(QURAN);
                    c.setStatus(status);
                    c.setVisibility(visibility);
                    c.setValidationStatus(vs);
                    String at = status + "/" + visibility + "/" + vs;
                    assertThat(LiveCategoryService.servable(v)).as("video " + at).isEqualTo(publicFilter(pcs, v));
                    assertThat(LiveCategoryService.servable(c)).as("channel " + at).isEqualTo(publicFilter(pcs, c));
                }
            }
        }
    }

    private static boolean publicFilter(Object service, Object doc) {
        return Boolean.TRUE.equals(org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "isApproved", doc))
                && Boolean.TRUE.equals(org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "isAvailable", doc));
    }

    private static Video doc(String youtubeId, String status, ValidationStatus vs) {
        Video v = new Video(youtubeId);
        v.setStatus(status);
        v.setValidationStatus(vs);
        return v;
    }
}
