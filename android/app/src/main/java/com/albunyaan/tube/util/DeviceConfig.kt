package com.albunyaan.tube.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.core.content.getSystemService

object DeviceConfig {
    /** TV gets 20 items, all other devices (phone/tablet) get 10 */
    fun getHomeDataLimit(context: Context): Int =
        if (isTV(context)) 20 else 10

    fun isTV(context: Context): Boolean {
        val uiModeManager = context.getSystemService<UiModeManager>()
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
