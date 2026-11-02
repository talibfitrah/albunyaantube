package com.albunyaan.tube.data.model.mappers

import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.api.models.ContentItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec §4.1: an expanded live-category item carries its channel into the domain model. */
class ApiMappersLiveChannelTest {

    @Test fun `a live category item keeps its channel`() {
        val dto = ContentItemDto(id = "eaOtcUNHDB8", type = ContentItemDto.Type.VIDEO, title = "Zad",
            durationSeconds = 0, channelTitle = "Zad TV", liveChannelId = "UCOll3M-P7oKs5cSrQ9ytt6g")

        val video = dto.toDomain() as ContentItem.Video

        assertEquals("UCOll3M-P7oKs5cSrQ9ytt6g", video.liveChannelId)
    }

    @Test fun `an ordinary video has none`() {
        val video = ContentItemDto(id = "Rs7St51oDDc", type = ContentItemDto.Type.VIDEO).toDomain() as ContentItem.Video

        assertNull(video.liveChannelId)
    }
}
