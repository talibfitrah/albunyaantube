package com.albunyaan.tube.util

import android.graphics.Bitmap
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
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

/** Saves a screenshot where AGP's connected-test run collects it: build/outputs/connected_android_test_additional_output. */
fun screenshot(name: String) {
    val inst = InstrumentationRegistry.getInstrumentation()
    Thread.sleep(500) // let the indeterminate bar slide into view and the list settle
    val bitmap = inst.uiAutomation.takeScreenshot()
    val dir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: inst.targetContext.cacheDir.path)
    dir.mkdirs()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
