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
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexControllerTest {

    @Mock private StreamIndexService streamIndexService;
    private IndexController controller;
    private MockHttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        controller = new IndexController(streamIndexService);
        mockRequest = new MockHttpServletRequest();
        mockRequest.setRemoteAddr("127.0.0.1");
    }

    @Test
    void rejectsInvalidSourceType() {
        IndexStreamsRequest req = req("UNKNOWN", "UCxxxxxxxxxxxxxxxxxxxxxx", List.of());
        assertEquals(HttpStatus.BAD_REQUEST, controller.indexStreams("dev1", req, mockRequest).getStatusCode());
    }

    @Test
    void rejectsInvalidChannelId() {
        // Channel ID too short
        IndexStreamsRequest req = req("CHANNEL", "UC123", List.of());
        assertEquals(HttpStatus.BAD_REQUEST, controller.indexStreams("dev1", req, mockRequest).getStatusCode());
    }

    @Test
    void rejectsInvalidPlaylistId() {
        IndexStreamsRequest req = req("PLAYLIST", "X", List.of());
        assertEquals(HttpStatus.BAD_REQUEST, controller.indexStreams("dev1", req, mockRequest).getStatusCode());
    }

    @Test
    void acceptsValidChannelRequest() {
        IndexStreamsRequest req = req("CHANNEL", "UCxxxxxxxxxxxxxxxxxxxxxx", List.of());
        assertEquals(HttpStatus.ACCEPTED, controller.indexStreams("dev1", req, mockRequest).getStatusCode());
    }

    @Test
    void acceptsValidPlaylistRequest() {
        IndexStreamsRequest req = req("PLAYLIST", "PLxxxxxxxxxxxxxxxxxxxxxxxxxx", List.of());
        assertEquals(HttpStatus.ACCEPTED, controller.indexStreams("dev1", req, mockRequest).getStatusCode());
    }

    @Test
    void filtersItemsWithInvalidStreamId() throws Exception {
        StreamItemDto bad = item("TOOSHORT", "Title", "https://i.ytimg.com/vi/x/hq.jpg");
        StreamItemDto good = item("abc12345678", "Good Title", "https://i.ytimg.com/vi/abc12345678/hq.jpg");
        IndexStreamsRequest req = req("CHANNEL", "UCxxxxxxxxxxxxxxxxxxxxxx", List.of(bad, good));

        controller.indexStreams("dev1", req, mockRequest);
        Thread.sleep(200); // let async complete

        verify(streamIndexService).indexFromChannel(
            eq("UCxxxxxxxxxxxxxxxxxxxxxx"),
            argThat(items -> items.size() == 1 && items.get(0).getId().equals("abc12345678"))
        );
    }

    @Test
    void filtersItemsWithDisallowedThumbnailHost() throws Exception {
        StreamItemDto bad = item("abc12345678", "Title", "https://evil.com/img.jpg");
        IndexStreamsRequest req = req("CHANNEL", "UCxxxxxxxxxxxxxxxxxxxxxx", List.of(bad));

        controller.indexStreams("dev1", req, mockRequest);
        Thread.sleep(200);

        verify(streamIndexService).indexFromChannel(
            eq("UCxxxxxxxxxxxxxxxxxxxxxx"),
            argThat(List::isEmpty)
        );
    }

    @Test
    void dedupesExactRepeatedRequests() {
        IndexStreamsRequest req = req(
                "CHANNEL",
                "UCxxxxxxxxxxxxxxxxxxxxxx",
                List.of(item("abc12345678", "Good Title", "https://i.ytimg.com/vi/abc12345678/hq.jpg"))
        );
        controller.indexStreams("dev1", req, mockRequest); // first: 202
        ResponseEntity<Void> second = controller.indexStreams("dev1", req, mockRequest);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getStatusCode());
    }

    @Test
    void acceptsDistinctPaginatedBatchesForSameSource() {
        IndexStreamsRequest firstPage = req(
                "CHANNEL",
                "UCxxxxxxxxxxxxxxxxxxxxxx",
                List.of(item("abc12345678", "First", "https://i.ytimg.com/vi/abc12345678/hq.jpg"))
        );
        IndexStreamsRequest secondPage = req(
                "CHANNEL",
                "UCxxxxxxxxxxxxxxxxxxxxxx",
                List.of(item("def12345678", "Second", "https://i.ytimg.com/vi/def12345678/hq.jpg"))
        );

        assertEquals(HttpStatus.ACCEPTED, controller.indexStreams("dev1", firstPage, mockRequest).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, controller.indexStreams("dev1", secondPage, mockRequest).getStatusCode());
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
