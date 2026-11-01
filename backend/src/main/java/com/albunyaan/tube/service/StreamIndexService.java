package com.albunyaan.tube.service;

import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import com.albunyaan.tube.search.SearchIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Service
public class StreamIndexService {

    private static final Logger log = LoggerFactory.getLogger(StreamIndexService.class);

    private final SearchableStreamRepository streamRepository;
    private final SearchIndex searchIndex;

    public StreamIndexService(SearchableStreamRepository streamRepository, SearchIndex searchIndex) {
        this.streamRepository = streamRepository;
        this.searchIndex = searchIndex;
    }

    /**
     * Remove all streams contributed by a source (called when channel/playlist is rejected).
     */
    public void removeSource(String sourceType, String sourceYoutubeId) {
        String sourceKey = sourceType.toLowerCase() + ":" + sourceYoutubeId;
        try {
            List<SearchableStream> batch;
            int removed = 0;
            do {
                batch = streamRepository.findBySourceKey(sourceKey, 500);
                for (SearchableStream stream : batch) {
                    streamRepository.removeSource(stream.getStreamId(), sourceKey);
                    removed++;
                }
            } while (batch.size() == 500);
            log.info("Removed search index source {} ({} streams)", sourceKey, removed);
            searchIndex.removeSource(sourceKey);
        } catch (Exception e) {
            log.warn("removeSource failed for {}: {}", sourceKey, e.getMessage());
        }
    }

    /**
     * Mark a single stream as invisible — used when an individual video
     * is archived. Unlike removeSource, this flips visibility regardless
     * of remaining sourceKeys (the stream may still be indexed via other
     * still-active channels/playlists, but the video itself is archived).
     */
    public void markStreamArchived(String streamId) {
        try {
            streamRepository.markInvisible(streamId);
            searchIndex.removeStream(streamId);
            log.info("Stream {} marked invisible (archived)", streamId);
        } catch (Exception e) {
            log.warn("markStreamArchived failed for {}: {}", streamId, e.getMessage());
        }
    }

    /** Stage 9 B25 (Cubic r13 #5): after Restore deleted a take-down record, its id stops hiding its stream (SearchIndex). */
    public void releaseVideoId(String youtubeId) {
        searchIndex.releaseVideoId(youtubeId);
    }

    /**
     * Stage 9 B13: undoes markStreamArchived when Restore lifts a report's take-down: visible and searchable again.
     * Stage 9 B14: a failure is thrown, so Restore keeps its record and can run again (nothing else re-shows the stream).
     */
    public void markStreamRestored(String streamId) throws ExecutionException, InterruptedException, TimeoutException {
        SearchableStream stream = streamRepository.markVisible(streamId);
        if (stream != null) searchIndex.restoreStream(stream);
        log.info("Stream {} restored (visible: {})", streamId, stream != null);
    }
}
