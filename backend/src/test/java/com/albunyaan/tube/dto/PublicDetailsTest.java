package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Video;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublicDetailsTest {

    @Test
    void videoDetail_sendsNoDurationWhenUnknownOrZero() {
        Video v = new Video();
        v.setYoutubeId("aaaaaaaaaaa");
        assertNull(PublicDetails.of(v).durationSeconds());
        v.setDurationSeconds(0);
        assertNull(PublicDetails.of(v).durationSeconds(), "a stored 0 means unknown, never a 0:00 chip");
        v.setDurationSeconds(754);
        assertEquals(754, PublicDetails.of(v).durationSeconds());
    }
}
