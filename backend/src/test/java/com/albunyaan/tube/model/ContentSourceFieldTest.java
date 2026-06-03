package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentSourceFieldTest {
    @Test
    void channelSourceRoundTrips() {
        Channel c = new Channel();
        c.setSource("USER_IMPORT");
        assertEquals("USER_IMPORT", c.getSource());
    }

    @Test
    void sourceDefaultsNull() {
        assertNull(new Channel().getSource());
        assertNull(new Playlist().getSource());
        assertNull(new Video().getSource());
    }
}
