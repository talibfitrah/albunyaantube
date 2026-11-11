package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Video;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentItemMapperTest {

    @Test
    void fromVideo_leavesUploadedDaysAgoNullWhenUploadedAtUnknown() {
        Video video = new Video();
        video.setYoutubeId("no-date");

        assertNull(ContentItemMapper.fromVideo(video).getUploadedDaysAgo(),
                "Unknown uploadedAt must be null, not a fabricated 0 (renders as 'Today')");
    }

    @Test
    void fromVideo_sendsNoDurationWhenUnknownOrZero() {
        Video v = new Video();
        v.setYoutubeId("aaaaaaaaaaa");
        assertNull(ContentItemMapper.fromVideo(v).getDurationSeconds());
        v.setDurationSeconds(0);
        assertNull(ContentItemMapper.fromVideo(v).getDurationSeconds());
        v.setDurationSeconds(754);
        assertEquals(754, ContentItemMapper.fromVideo(v).getDurationSeconds());
    }

    /** liveLink marks only LiveCategoryService's exact links; the shared mappers leave it out of the JSON. */
    @Test
    void theMappersLeaveLiveLinkNull() {
        Video video = new Video();
        video.setYoutubeId("aaaaaaaaaaa");

        assertNull(ContentItemMapper.fromVideo(video).getLiveLink());
        assertNull(ContentItemMapper.fromLive(new com.albunyaan.tube.model.Channel("UCa"), "aaaaaaaaaaa", "t").getLiveLink());
    }
}
