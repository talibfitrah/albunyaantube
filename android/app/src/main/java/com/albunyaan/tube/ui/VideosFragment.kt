package com.albunyaan.tube.ui

import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.ui.list.ContentListFragment

class VideosFragment : ContentListFragment() {
    override val contentType: ContentType = ContentType.VIDEOS
}

