package com.albunyaan.tube.repository;

import com.albunyaan.tube.model.Video;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 9 B11: a youtubeId with a stale second doc answers by the doc that counts, whatever the doc-id order. */
class VideoRepositoryPreferredTest {

    private static Video doc(String id, String status) {
        Video v = new Video("TwoDocs0001");
        v.setId(id);
        v.setStatus(status);
        return v;
    }

    @Test
    void approvedWinsThenAnyNotRejectedThenTheFirst() {
        assertEquals("b", VideoRepository.preferred(List.of(doc("a", "REJECTED"), doc("b", "APPROVED"))).get().getId());
        assertEquals("b", VideoRepository.preferred(List.of(doc("a", "REJECTED"), doc("b", "PENDING"))).get().getId());
        assertEquals("a", VideoRepository.preferred(List.of(doc("a", "REJECTED"), doc("b", "REJECTED"))).get().getId());
        assertTrue(VideoRepository.preferred(List.of()).isEmpty());
    }
}
