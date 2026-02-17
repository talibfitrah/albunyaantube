package com.albunyaan.tube.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.core.content.getSystemService

object DeviceConfig {
    /** TV and tablet get 20 items, phone gets 10 */
    fun getHomeDataLimit(context: Context): Int =
        if (isTV(context) || isTablet(context)) 20 else 10

    fun isTV(context: Context): Boolean {
        val uiModeManager = context.getSystemService<UiModeManager>()
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isTablet(context: Context): Boolean {
        val sw = context.resources.configuration.smallestScreenWidthDp
        return sw >= 600
    }
}
