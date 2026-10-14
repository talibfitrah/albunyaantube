package com.albunyaan.tube.ui

import android.app.LocaleManager
import android.os.Build
import android.graphics.Rect
import android.os.LocaleList
import android.view.View
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.albunyaan.tube.HiltTestActivity
import com.albunyaan.tube.R
import com.albunyaan.tube.di.TestNetworkModule
import com.albunyaan.tube.launchFragmentInHiltContainer
import com.albunyaan.tube.util.eventually
import com.albunyaan.tube.util.onResumedActivity
import com.albunyaan.tube.util.screenshot
import com.albunyaan.tube.util.searchAnswer
import com.albunyaan.tube.util.searchVideo
import com.albunyaan.tube.util.typeQuery
import com.albunyaan.tube.util.viewDisplayed
import com.albunyaan.tube.util.viewGone
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import java.util.concurrent.CountDownLatch

/**
 * Spec §6 on the real SearchFragment: a newer search shows the thin progress bar over the old results,
 * and an approximate/related answer shows the "closest results" note (an exact one hides it).
 * Backend answers are scripted through [TestNetworkModule.fakeContentApi]; no production switch is involved.
 * Each run also saves screenshots named android-{phone|tablet}-{lang}-{loading|results|note}.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
abstract class SearchScreensBase(
    private val lang: String,
    private val rtl: Boolean,
    private val noteText: String
) {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    // Each test builds its own Hilt component, and SimpleCache allows one instance per folder per process.
    @Inject
    lateinit var simpleCache: SimpleCache

    private var scenario: ActivityScenario<HiltTestActivity>? = null
    private val api get() = TestNetworkModule.fakeContentApi

    // The per-app language (what Settings > Language ends up setting), so the activity really renders in [lang].
    // LocaleTestRule only edits the application Resources, which an AppCompatActivity does not pick up.
    @Before
    fun useLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang)
        eventually { assertEquals(lang, context.resources.configuration.locales[0].language) }
    }

    @After
    fun tearDown() {
        api.resetSearch() // first: the shared fake must be reset even if a later step throws
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        scenario?.close()
        simpleCache.release()
    }

    // Views are read through onResumedActivity, not Espresso or ActivityScenario: while the indeterminate bar runs, a
    // slow emulator never lets the main looper idle, and those wait for it (see onResumedActivity).
    private fun name(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.resources.getResourceEntryName(id)
    private fun shown(id: Int) = assertTrue("${name(id)} is not on screen", viewDisplayed(id))
    private fun gone(id: Int) = assertTrue("${name(id)} is not gone", viewGone(id))
    private fun notShown(id: Int) = assertFalse("${name(id)} is on screen", viewDisplayed(id))

    private fun resultsListTop(): Int = onResumedActivity {
        val location = IntArray(2)
        it.findViewById<View>(R.id.searchResultsList).getLocationOnScreen(location)
        location[1]
    }

    @Test
    fun progressBarOverResults_andClosestResultsNote() {
        hiltRule.inject()
        val slowStarted = CountDownLatch(1)
        val slowGate = CompletableDeferred<Unit>()
        api.onSearch = { q ->
            when (q) {
                "quran" -> searchAnswer("exact", searchVideo("v1", "Quran for beginners"), searchVideo("v2", "Tajweed basics"))
                "quran tv" -> {
                    slowStarted.countDown()
                    slowGate.await()
                    searchAnswer("exact", searchVideo("v3", "Quran TV live"), searchVideo("v1", "Quran for beginners"))
                }
                "qurn" -> searchAnswer("approximate", searchVideo("v1", "Quran for beginners"))
                "koran" -> searchAnswer("related", searchVideo("v1", "Quran for beginners"))
                "boom" -> throw java.io.IOException("offline")
                else -> searchAnswer("exact")
            }
        }
        scenario = launchFragmentInHiltContainer<SearchFragment>(themeResId = R.style.Theme_Albunyaan)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario!!.onActivity { assertEquals(lang, it.resources.configuration.locales[0].language) }
        val device = if (InstrumentationRegistry.getInstrumentation().targetContext.resources
                .configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
        val shot = { state: String -> screenshot("android-$device-$lang-$state") }

        // 1. First search: list shown, no bar, no note (exact).
        typeQuery("quran")
        eventually { shown(R.id.searchResultsList) }
        notShown(R.id.searchProgress)
        gone(R.id.searchMatchNote)
        val listTop = resultsListTop()

        // 2. A newer search in flight: the old list stays, the bar shows, no full-screen spinner.
        typeQuery("quran tv")
        eventually { assertEquals(0L, slowStarted.count) }
        eventually { shown(R.id.searchProgress) }
        shown(R.id.searchResultsList)
        gone(R.id.loadingState)
        assertEquals("showing the bar must not push the list down", listTop, resultsListTop())
        onResumedActivity {
            val bar = it.findViewById<LinearProgressIndicator>(R.id.searchProgress)
            assertEquals("the bar spans the screen width", (bar.parent as View).width, bar.width)
            assertEquals("the track follows the theme", it.getColor(R.color.surface_variant), bar.trackColor)
        }
        // The bar is indeterminate: wait for a frame where the green segment is on screen.
        val barRow = Rect().also { r -> onResumedActivity { it.findViewById<View>(R.id.searchProgress).getGlobalVisibleRect(r) } }
        val green = InstrumentationRegistry.getInstrumentation().targetContext.getColor(R.color.primary_green)
        screenshot("android-$device-$lang-loading") { bitmap ->
            (barRow.left until barRow.right step 4).any { x -> bitmap.getPixel(x, barRow.centerY()) == green }
        }

        // 3. It lands: bar gone, exact results show no note.
        slowGate.complete(Unit)
        eventually { notShown(R.id.searchProgress) }
        shown(R.id.searchResultsList)
        gone(R.id.searchMatchNote)
        shot("results")

        // 4. Each closest-match tier shows the note on its own. An exact answer comes first and the test waits for
        //    the note to go, so a note left over from the previous tier cannot satisfy the next assertion.
        for (query in listOf("koran", "qurn")) { // related, then approximate
            typeQuery("quran")
            eventually { gone(R.id.searchMatchNote) }
            typeQuery(query)
            try {
                eventually { shown(R.id.searchMatchNote) }
            } catch (e: Throwable) {
                throw AssertionError("no closest-results note for \"$query\"", e)
            }
            onView(withId(R.id.searchMatchNote)).check(matches(withText(noteText)))
        }
        scenario!!.onActivity {
            val note = it.findViewById<View>(R.id.searchMatchNote)
            val direction = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            assertEquals(direction, ViewCompat.getLayoutDirection(note))
        }
        shot("note")

        // 5. The note never outlives its results. The note is showing here ("qurn"), so each check is real.
        typeQuery("q") // under 2 characters: the history screen
        eventually { gone(R.id.searchResultsList) }
        gone(R.id.searchMatchNote)

        typeQuery("qurn")
        eventually { shown(R.id.searchMatchNote) }
        typeQuery("boom") // the scripted search throws: the error screen
        eventually { shown(R.id.emptyState) }
        gone(R.id.searchMatchNote)
    }
}

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class SearchScreensEnTest : SearchScreensBase("en", rtl = false, noteText = "No exact match — showing closest results")

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class SearchScreensArTest : SearchScreensBase("ar", rtl = true, noteText = "لا يوجد تطابق تام — إليك أقرب النتائج")
