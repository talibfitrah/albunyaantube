package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChannelModelTest {

    @Test
    void statusNormalizationUpdatesFlags() {
        Channel channel = new Channel();

        channel.setStatus("approved");

        assertEquals("APPROVED", channel.getStatus(), "Status should store uppercase variant");
        assertTrue(channel.isApproved());
        assertFalse(channel.isPending());
        assertTrue(channel.getApproved(), "Boolean flag mirrors status");
        assertFalse(channel.getPending());
    }

    @Test
    void pendingSetterSwitchesStatus() {
        Channel channel = new Channel();
        channel.setStatus("APPROVED");
        assertTrue(channel.getApproved());

        channel.setPending(Boolean.TRUE);

        assertEquals("PENDING", channel.getStatus());
        assertTrue(channel.isPending());
        assertTrue(channel.getPending());
        assertFalse(channel.getApproved());
    }

    @Test
    void excludedItemsKeepsTotalInSync() {
        Channel channel = new Channel();
        Channel.ExcludedItems items = new Channel.ExcludedItems();

        items.setVideos(List.of("v1", "v2"));
        items.setShorts(List.of("s1"));
        items.setPlaylists(List.of());
        items.setLiveStreams(List.of("l1"));
        items.setPosts(List.of());

        channel.setExcludedItems(items);

        assertEquals(4, channel.getExcludedItems().getTotalExcludedCount());

        channel.getExcludedItems().setPosts(List.of("p1", "p2"));
        assertEquals(6, channel.getExcludedItems().getTotalExcludedCount());
    }
}


