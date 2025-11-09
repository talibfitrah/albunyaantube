package com.albunyaan.tube.ui.home

import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.ui.list.ContentListFragment

class HomeFragment : ContentListFragment() {
    override val contentType: ContentType = ContentType.HOME
}
