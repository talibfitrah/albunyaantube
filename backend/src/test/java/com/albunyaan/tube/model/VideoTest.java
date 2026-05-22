package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoTest {

    @Test
    void videoType_defaultIsNull_forLegacyDocsCompat() {
        Video v = new Video();
        assertNull(v.getVideoType(), "new Video() must default videoType to null");
    }

    @Test
    void videoType_setterAndGetterRoundtrip() {
        Video v = new Video();
        v.setVideoType(VideoType.LIVE);
        assertEquals(VideoType.LIVE, v.getVideoType());
        v.setVideoType(VideoType.STANDARD);
        assertEquals(VideoType.STANDARD, v.getVideoType());
    }
}
