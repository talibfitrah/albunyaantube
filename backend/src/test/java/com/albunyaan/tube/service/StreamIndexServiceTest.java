package com.albunyaan.tube.service;

import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamIndexServiceTest {

    @Mock private SearchableStreamRepository streamRepository;
    @Mock private com.albunyaan.tube.search.SearchIndex searchIndex;
    private StreamIndexService service;

    @BeforeEach
    void setUp() {
        service = new StreamIndexService(streamRepository, searchIndex);
    }

    @Test
    void markStreamArchived_callsRepositoryMarkInvisible() throws Exception {
        service.markStreamArchived("lesson00001");
        verify(streamRepository).markInvisible("lesson00001");
    }

    @Test
    void removeSource_callsRepositoryForEachStream() throws Exception {
        SearchableStream s1 = new SearchableStream(); s1.setStreamId("aaa11111111");
        SearchableStream s2 = new SearchableStream(); s2.setStreamId("bbb22222222");
        when(streamRepository.findBySourceKey("channel:UC123", 500)).thenReturn(List.of(s1, s2));

        service.removeSource("CHANNEL", "UC123");

        verify(streamRepository).removeSource("aaa11111111", "channel:UC123");
        verify(streamRepository).removeSource("bbb22222222", "channel:UC123");
    }

    @Test
    void removeSourceAndArchive_hookTheSearchIndex() throws Exception {
        when(streamRepository.findBySourceKey("channel:UC123", 500)).thenReturn(List.of());

        service.removeSource("CHANNEL", "UC123");
        service.markStreamArchived("abc12345678");

        verify(searchIndex).removeSource("channel:UC123");
        verify(searchIndex).removeStream("abc12345678");
    }
}
