package com.albunyaan.tube.util

import android.os.Build
import android.view.Menu
import androidx.appcompat.widget.PopupMenu

/**
 * PopupMenu and Toolbar overflow menus hide item icons by default. These helpers
 * surface them so kebab entries match the rest of the design system.
 */
fun PopupMenu.showIcons() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setForceShowIcon(true)
        return
    }
    runCatching {
        val field = PopupMenu::class.java.getDeclaredField("mPopup")
        field.isAccessible = true
        val helper = field.get(this)
        helper.javaClass
            .getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
            .invoke(helper, true)
    }
}

fun Menu.showIcons() {
    runCatching {
        val method = javaClass.getDeclaredMethod(
            "setOptionalIconsVisible",
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(this, true)
    }
}
