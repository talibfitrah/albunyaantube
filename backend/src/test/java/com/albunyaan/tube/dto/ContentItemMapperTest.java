package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Video;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class ContentItemMapperTest {

    @Test
    void fromVideo_leavesUploadedDaysAgoNullWhenUploadedAtUnknown() {
        Video video = new Video();
        video.setYoutubeId("no-date");

        assertNull(ContentItemMapper.fromVideo(video).getUploadedDaysAgo(),
                "Unknown uploadedAt must be null, not a fabricated 0 (renders as 'Today')");
    }
}
