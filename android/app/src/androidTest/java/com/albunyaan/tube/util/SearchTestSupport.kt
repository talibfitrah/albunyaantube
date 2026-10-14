package com.albunyaan.tube.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.api.models.ContentItemDto
import okhttp3.Headers
import org.hamcrest.Matchers.allOf
import retrofit2.Response
import java.io.File

/** Shared by the search-screen instrumentation tests: scripted answers, typing, polling, screenshots. */

fun searchVideo(id: String, title: String) = ContentItemDto(
    id = id,
    type = ContentItemDto.Type.VIDEO,
    title = title,
    category = "Test",
    durationSeconds = 600,
    uploadedDaysAgo = 3,
    viewCount = 1200
)

/** A /v1/search answer carrying the X-Search-Match tier. */
fun searchAnswer(match: String, vararg items: ContentItemDto): Response<List<ContentItemDto>> =
    Response.success(items.toList(), Headers.headersOf("X-Search-Match", match))

/** Types into the SearchView's edit box, as a user would (fires onQueryTextChange once). */
fun typeQuery(text: String) {
    onView(allOf(withId(androidx.appcompat.R.id.search_src_text), isDescendantOfA(withId(R.id.searchView))))
        .perform(replaceText(text))
}

/** Retries [check] until it stops throwing: the UI follows the ViewModel asynchronously. */
fun eventually(timeoutMs: Long = 5_000, check: () -> Unit) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        try {
            return check()
        } catch (e: Throwable) {
            if (System.currentTimeMillis() > deadline) throw e
            Thread.sleep(50)
        }
    }
}

/**
 * Runs [block] on the main thread with the resumed activity, without first waiting for an idle main looper.
 * Espresso's onView and ActivityScenario's onActivity/recreate all wait for one, and while an endless animation runs
 * (the tab's refresh spinner, the search bar) a slow emulator rarely gives one: on the sw800dp AVD each spinner frame
 * takes ~230 ms to render, so the next vsync is already due when a frame ends. onView then fails with
 * AppNotIdleException after 60 s, and onActivity/recreate stall for as long. Use this while such an animation runs.
 */
fun <T> onResumedActivity(block: (Activity) -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        result = runCatching { block(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()) }
    }
    return result!!.getOrThrow()
}

/** Espresso's isDisplayed(), read through [onResumedActivity]. */
fun viewDisplayed(@IdRes id: Int): Boolean =
    onResumedActivity { val v = it.findViewById<View>(id); v.isShown && v.getGlobalVisibleRect(Rect()) }

/** Espresso's withEffectiveVisibility(GONE), read through [onResumedActivity]. */
fun viewGone(@IdRes id: Int): Boolean =
    onResumedActivity { a -> generateSequence(a.findViewById<View>(id)) { it.parent as? View }.any { it.visibility == View.GONE } }

/**
 * Saves a screenshot where AGP's connected-test run collects it: build/outputs/connected_android_test_additional_output.
 * [until] lets a caller wait for a moving frame (the indeterminate bar's segment): it retries ~3 s, keeping the last one.
 */
fun screenshot(name: String, until: (Bitmap) -> Boolean = { true }) {
    val inst = InstrumentationRegistry.getInstrumentation()
    // The focused SearchView pops the keyboard up on AVDs without a hardware keyboard. Not Espresso.closeSoftKeyboard():
    // it waits for an idle looper (see onResumedActivity).
    onResumedActivity {
        it.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
    }
    Thread.sleep(500) // let the list settle
    var bitmap = inst.uiAutomation.takeScreenshot()
    repeat(20) {
        if (until(bitmap)) return@repeat
        Thread.sleep(150)
        bitmap = inst.uiAutomation.takeScreenshot()
    }
    val dir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: inst.targetContext.cacheDir.path)
    dir.mkdirs()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
