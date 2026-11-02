package com.albunyaan.tube.service;

import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.scheduler.LiveChannelPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spec §4.1: the answers of the public live endpoints (LiveChannelController), from the poller's in-memory view with
 * the R11 filter already applied, and no YouTube read on the request thread. A null answer is "not yet": the endpoint's
 * 503 + Retry-After, on which the apps keep playing. Not found is the apps' definitive stop (404).
 * Apart from {@link LiveChannelService}: it asks the poller, which depends on that service.
 */
@Service
public class LiveEndpointService {

    private static final Logger log = LoggerFactory.getLogger(LiveEndpointService.class);

    private final LiveChannelService live;
    private final LiveChannelPoller poller;
    private final VideoRepository videos;

    public LiveEndpointService(LiveChannelService live, LiveChannelPoller poller, VideoRepository videos) {
        this.live = live;
        this.poller = poller;
        this.videos = videos;
    }

    /**
     * The channel's current lives (a title may be null), null for 503, {@link ResourceNotFoundException} for 404. A plain
     * call asks the poller for an on-demand read. With {@code candidate} (a video id the app saw on the channel's /live
     * page) the answer is that candidate's verdict alone; when the snapshot does not list it, the poller is asked too.
     */
    public List<LiveTabResult.LiveItem> lives(String channelId, String candidate) {
        // 404 is the apps' definitive "not live" stop: never sent before a view built from the loaded states is
        // published. A failed boot load or view refresh waits for the poller's next cycle (~1 min after boot), which
        // publishes only after its Live-tab reads.
        if (!live.published()) return null;
        Channel channel = live.entries().get(channelId);
        if (channel == null) {
            // A channel just added to a LIVE category is seeded at once but listed only by the next cycle (stage 9 Cubic
            // r2), or one a pending refresh may list (Cubic r4 R4-1: a seed covers the guard's adds only)
            if (live.recentlyAdded(channelId) || poller.viewPending()) return null;
            throw new ResourceNotFoundException("LiveChannel", channelId);
        }
        List<LiveTabResult.LiveItem> served = live.served().getOrDefault(channelId, List.of());
        if (candidate == null) {
            poller.refreshOnDemand(channelId);   // async; the apps' retries see its result
            return served;
        }
        // The candidate's verdict only: after a rotation the snapshot lists the ended id until the next read, and an app
        // never switches while its current id is listed.
        List<LiveTabResult.LiveItem> lives = served.stream().filter(l -> l.id().equals(candidate)).toList();
        if (!lives.isEmpty()) return lives;
        // Codex r6 C1: a denial known locally (an exclusion on the channel doc, a live the last view found blocked) costs no
        // lookup budget, so a flood of made-up ids that spends it never keeps a removed stream playing (503)
        if (LiveCategoryService.served(channel, candidate, Map.of()) == LiveCategoryService.Served.EXCLUDED
                || live.blocked(candidate)) {
            poller.refreshOnDemand(channelId);
            return List.of();
        }
        AtomicBoolean judged = new AtomicBoolean();
        // Cubic r2 #3: judged once per view, not per heartbeat; a moderation event or a new view judges again
        Boolean verdict = live.candidateVerdict(channelId, candidate, () -> {
            Channel current = live.entries().get(channelId);   // Codex r6 C2: read after the verdict's generation
            Boolean v = current == null ? null : judge(current, candidate);
            judged.set(v != null);
            return v;
        });
        // the snapshot catches up; a read listing an ended id clears it. Not after a failed lookup (Cubic r3): nothing
        // was judged, the 503 retry asks again, and a Firestore outage never turns into YouTube reads.
        if (judged.get()) poller.refreshStale(channelId);
        // Cubic r3: no verdict (the lookup failed, its budget is spent, or a new view replaced its inputs) is not
        // OFFLINE, which stops a playing live
        if (verdict == null) return null;
        return verdict ? List.of(new LiveTabResult.LiveItem(candidate, lastTitle(live.state(channelId), candidate))) : List.of();
    }

    /**
     * The removal check of an exact live link while it plays: true while the poller's view serves it as a link of a LIVE
     * category; false (404) when not (removed from the category, un-approved, blocked or taken down); null (503) before a
     * view is published, or when the view lacks it while a refresh is pending or a cycle runs (Cubic r4 R4-1: the apps
     * list a link once the caches are evicted). In memory. Cubic r6 K2: an ENDED link is still served (ENDED is never a
     * removal), and (K1 b) its heartbeat asks for a read of its channel, which shows it again once it is live.
     */
    public Boolean linkServed(String videoId) {
        if (!live.published()) return null;
        String uc = live.linkChannel(videoId);
        if (uc != null) {
            if (LiveCategoryService.ended(live.state(uc), videoId)) poller.refreshStale(uc);
            return true;
        }
        return poller.viewPending() ? null : false;
    }

    /**
     * R11 on the candidate (one registry lookup): not excluded, not blocked. Cubic r6 K2: ENDED is ignored, the phone's
     * page lists the id live. A failed lookup is null: no verdict (503), never cached.
     */
    private Boolean judge(Channel channel, String id) {
        try {
            return LiveCategoryService.served(channel, id, videos.findByYoutubeIds(Set.of(id))) == LiveCategoryService.Served.SERVED;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("live candidate {} of {} not judged: {}", id, channel.getYoutubeId(), e.toString());
            return null;
        }
    }

    private static String lastTitle(LiveChannelState state, String id) {
        return state == null ? "" : state.getLives().stream().filter(l -> id.equals(l.getVideoId()) && l.getTitle() != null)
                .map(LiveChannelState.Live::getTitle).findFirst().orElse("");
    }
}
