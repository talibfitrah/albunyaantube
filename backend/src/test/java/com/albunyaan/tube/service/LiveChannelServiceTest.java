package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D1 snapshot, the 60 s memo (§5), and D15's add-time seed. */
class LiveChannelServiceTest {

    static final String SUNNAH = "UCROKYPep-UuODNwyipe6JMw";
    final AtomicLong now = new AtomicLong(1_792_200_000_000L);
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    final LiveTabReader reader = mock(LiveTabReader.class);
    final LiveChannelStateRepository states = mock(LiveChannelStateRepository.class);
    final LiveChannelService service = new LiveChannelService(reader, states, clock);

    static LiveTabResult sunnahLive() {
        return LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("Rs7St51oDDc", "Sunnah")), 1);
    }

    @Test
    void aReadWithinSixtySecondsIsServedFromTheMemo() {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        service.livesNow(SUNNAH);
        now.addAndGet(59_000);
        service.livesNow(SUNNAH);
        now.addAndGet(2_000);
        service.livesNow(SUNNAH);

        verify(reader, times(2)).read(SUNNAH);
    }

    /** D16: a memo hit sends nothing, so it costs nothing against the cycle budget or the daily count. */
    @Test
    void aMemoHitCostsNoRequests() {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        assertThat(service.livesNow(SUNNAH).requests()).isEqualTo(1);
        assertThat(service.livesNow(SUNNAH).requests()).isZero();
    }

    @Test
    void aFreshReadSkipsTheMemo() {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        service.livesNow(SUNNAH);
        service.readFresh(SUNNAH);

        verify(reader, times(2)).read(SUNNAH);
    }

    @Test
    void anErrorIsNeverMemoized() {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.error(1, "x"));

        service.livesNow(SUNNAH);
        service.livesNow(SUNNAH);

        verify(reader, times(2)).read(SUNNAH);
    }

    /** D15: a channel add is visible at once; a failed read seeds ERROR instead of refusing the add. */
    @Test
    void aSeedWritesTheStateAndPublishesIt() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.LIVE);
        verify(states).save(any(LiveChannelState.class));
    }

    @Test
    void aFailedSeedRecordsAnError() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.error(1, "boom"));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getErrorCount()).isEqualTo(1);
        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.UNKNOWN);
    }

    /** D15 / pre-flight M9: a failed state write never refuses the add; the read stays in the snapshot. */
    @Test
    void aFailedStateWriteNeverRefusesTheAdd() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());
        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(states).save(any());

        service.seed(SUNNAH);                                   // no exception

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.LIVE);
    }

    @Test
    void aBreakerSeedWritesNothing() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.breaker(0, "open"));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH)).isNull();
        verify(states, never()).save(any());
    }

    /** R7: a rate limit says nothing about the channel, so the seed records nothing (no UNKNOWN with a fresh checkedAt). */
    @Test
    void aRateLimitedSeedWritesNothing() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH)).isNull();
        verify(states, never()).save(any());
    }

    private void knownLive() {
        LiveChannelState known = LiveChannelState.unknown(SUNNAH);
        known.setStatus(LiveChannelState.LIVE);
        known.getLives().add(new LiveChannelState.Live("Rs7St51oDDc", "Sunnah"));
        service.loadStates(List.of(known));
    }

    /** R10: re-adding a channel that is served now, during a suspect stretch, must not hide its last lives. */
    @Test
    void aReSeedOfAServedChannelNeverHides() {
        knownLive();
        service.publish(Map.of(SUNNAH, new Channel(SUNNAH)), Map.of());
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(), 1));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(service.state(SUNNAH).getLives()).extracting(LiveChannelState.Live::getVideoId)
                .containsExactly("Rs7St51oDDc");
    }

    /** A channel that is not served now (removed earlier, re-added) shows nothing stale: an empty read hides it. */
    @Test
    void aSeedOfAKnownButUnservedChannelHides() {
        knownLive();
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(), 1));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    @Test
    void aFirstSeedOfAnOfflineChannelHidesIt() {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(), 1));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }
}
