package com.albunyaan.tube.service;

import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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

    /** Stage 9 B13: Restore of a take-down undoes markStreamArchived: the stored stream is visible and indexed again. */
    @Test
    void markStreamRestored_makesTheStoredStreamVisibleAndIndexesItAgain() throws Exception {
        SearchableStream s = new SearchableStream(); s.setStreamId("ilgiwqpcOC8");
        when(streamRepository.markVisible("ilgiwqpcOC8")).thenReturn(s);
        when(streamRepository.markVisible("innerOnly01")).thenReturn(null); // no stored stream (an inner copy): nothing to index

        service.markStreamRestored("ilgiwqpcOC8");
        service.markStreamRestored("innerOnly01");

        verify(searchIndex).restoreStream(s);
        verify(searchIndex, times(1)).restoreStream(any());
    }
}
