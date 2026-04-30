package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.IndexStreamsRequest;
import com.albunyaan.tube.dto.StreamItemDto;
import com.albunyaan.tube.service.StreamIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexControllerTest {

    @Mock private StreamIndexService streamIndexService;
    private IndexController controller;

    @BeforeEach
    void setUp() { controller = new IndexController(streamIndexService); }

    @Test
    void rejectsInvalidSourceType() {
        IndexStreamsRequest req = req("UNKNOWN", "UCxxxxxxxxxxxxxxxxxxxxxxxxxxx", List.of());
        assertEquals(HttpStatus.BAD_REQUEST, controller.indexStreams("dev1", req).getStatusCode());
    }

    @Test
    void rejectsInvalidChannelId() {
        // Channel ID too short
        IndexStreamsRequest req = req("CHANNEL", "UC123", List.of());
        assertEquals(HttpStatus.BAD_REQUEST, controller.indexStreams("dev1", req).getStatusCode());
    }

    @Test
    void rejectsInvalidPlaylistId() {
        IndexStreamsRequest req = req("PLAYLIST", "X", List.of());
        assertEquals(HttpStatus.BAD_REQUEST, controller.indexStreams("dev1", req).getStatusCode());
    }

    @Test
    void acceptsValidChannelRequest() {
        IndexStreamsRequest req = req("CHANNEL", "UCxxxxxxxxxxxxxxxxxxxxxxxxxxx", List.of());
        assertEquals(HttpStatus.ACCEPTED, controller.indexStreams("dev1", req).getStatusCode());
    }

    @Test
    void acceptsValidPlaylistRequest() {
        IndexStreamsRequest req = req("PLAYLIST", "PLxxxxxxxxxxxxxxxxxxxxxxxxxx", List.of());
        assertEquals(HttpStatus.ACCEPTED, controller.indexStreams("dev1", req).getStatusCode());
    }

    @Test
    void filtersItemsWithInvalidStreamId() throws Exception {
        StreamItemDto bad = item("TOOSHORT", "Title", "https://i.ytimg.com/vi/x/hq.jpg");
        StreamItemDto good = item("abc12345678", "Good Title", "https://i.ytimg.com/vi/abc12345678/hq.jpg");
        IndexStreamsRequest req = req("CHANNEL", "UCxxxxxxxxxxxxxxxxxxxxxxxxxxx", List.of(bad, good));

        controller.indexStreams("dev1", req);
        Thread.sleep(200); // let async complete

        verify(streamIndexService).indexFromChannel(
            eq("UCxxxxxxxxxxxxxxxxxxxxxxxxxxx"),
            argThat(items -> items.size() == 1 && items.get(0).getId().equals("abc12345678"))
        );
    }

    @Test
    void filtersItemsWithDisallowedThumbnailHost() throws Exception {
        StreamItemDto bad = item("abc12345678", "Title", "https://evil.com/img.jpg");
        IndexStreamsRequest req = req("CHANNEL", "UCxxxxxxxxxxxxxxxxxxxxxxxxxxx", List.of(bad));

        controller.indexStreams("dev1", req);
        Thread.sleep(200);

        verify(streamIndexService).indexFromChannel(
            eq("UCxxxxxxxxxxxxxxxxxxxxxxxxxxx"),
            argThat(List::isEmpty)
        );
    }

    @Test
    void rateLimitsRepeatedRequests() {
        IndexStreamsRequest req = req("CHANNEL", "UCxxxxxxxxxxxxxxxxxxxxxxxxxxx", List.of());
        controller.indexStreams("dev1", req); // first: 202
        ResponseEntity<Void> second = controller.indexStreams("dev1", req);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getStatusCode());
    }

    private IndexStreamsRequest req(String sourceType, String sourceId, List<StreamItemDto> items) {
        IndexStreamsRequest r = new IndexStreamsRequest();
        r.setSourceType(sourceType);
        r.setSourceId(sourceId);
        r.setItems(items);
        return r;
    }

    private StreamItemDto item(String id, String name, String thumbUrl) {
        StreamItemDto d = new StreamItemDto();
        d.setId(id);
        d.setName(name);
        d.setThumbnailUrl(thumbUrl);
        d.setStreamType("VIDEO");
        return d;
    }
}
