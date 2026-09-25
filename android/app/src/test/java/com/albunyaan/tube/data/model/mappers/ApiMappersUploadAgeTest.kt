package com.albunyaan.tube.data.model.mappers

import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.api.models.ContentItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The backend omits uploadedDaysAgo when the upload date is unknown. */
class ApiMappersUploadAgeTest {

    private fun video(daysAgo: Int?) =
        ContentItemDto(id = "v1", type = ContentItemDto.Type.VIDEO, uploadedDaysAgo = daysAgo)
            .toDomain() as ContentItem.Video

    @Test fun `an unknown upload date stays unknown, not today`() {
        assertNull(video(daysAgo = null).uploadedDaysAgo)
    }

    @Test fun `a known age passes through, including a real today`() {
        assertEquals(0, video(daysAgo = 0).uploadedDaysAgo)
        assertEquals(12, video(daysAgo = 12).uploadedDaysAgo)
    }
}
